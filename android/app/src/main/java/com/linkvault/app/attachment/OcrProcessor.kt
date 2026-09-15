package com.linkvault.app.attachment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.Executor
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

object OcrProcessor {
    const val MAX_TEXT_CODE_POINTS = 20_000

    suspend fun recognize(
        context: Context,
        imageFile: File,
    ): OcrResult {
        val input = try {
            withContext(Dispatchers.IO) {
                ensureActive()
                if (!imageFile.isFile) {
                    return@withContext null
                }
                InputImage.fromFilePath(context, Uri.fromFile(imageFile))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: FileNotFoundException) {
            return OcrResult.failed(OcrFailure.SOURCE_UNAVAILABLE)
        } catch (_: IOException) {
            return OcrResult.failed(OcrFailure.INVALID_IMAGE)
        } catch (_: IllegalArgumentException) {
            return OcrResult.failed(OcrFailure.INVALID_IMAGE)
        }
        return if (input == null) {
            OcrResult.failed(OcrFailure.SOURCE_UNAVAILABLE)
        } else {
            recognize(input)
        }
    }

    suspend fun recognize(bitmap: Bitmap): OcrResult {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return OcrResult.failed(OcrFailure.INVALID_IMAGE)
        }
        val input = try {
            InputImage.fromBitmap(bitmap, 0)
        } catch (_: IllegalArgumentException) {
            return OcrResult.failed(OcrFailure.INVALID_IMAGE)
        }
        return recognize(input)
    }

    private suspend fun recognize(input: InputImage): OcrResult {
        var koreanRecognizer: TextRecognizer? = null
        var latinRecognizer: TextRecognizer? = null
        return try {
            val koreanClient = TextRecognition.getClient(
                KoreanTextRecognizerOptions.Builder().build(),
            )
            koreanRecognizer = koreanClient
            val latinClient = TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS,
            )
            latinRecognizer = latinClient
            val korean = koreanClient.process(input).await()
            coroutineContext.ensureActive()
            val latin = latinClient.process(input).await()
            coroutineContext.ensureActive()

            val mergedText = mergeRecognizedLines(korean, latin)
            val capped = capCodePoints(mergedText)
            OcrResult.ready(capped.text, capped.truncated)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            OcrResult.failed(OcrFailure.RECOGNITION_FAILED)
        } finally {
            koreanRecognizer?.close()
            latinRecognizer?.close()
        }
    }

    private fun mergeRecognizedLines(korean: Text, latin: Text): String {
        val candidates = buildList {
            addAll(korean.toCandidates(Model.KOREAN))
            addAll(latin.toCandidates(Model.LATIN))
        }
        val accepted = mutableListOf<RecognizedLine>()
        candidates.forEach { candidate ->
            val duplicateIndex = accepted.indexOfFirst { existing ->
                existing.model != candidate.model && existing.representsSameRegion(candidate)
            }
            if (duplicateIndex == -1) {
                accepted += candidate
            } else {
                accepted[duplicateIndex] = preferred(accepted[duplicateIndex], candidate)
            }
        }

        return accepted
            .sortedWith(
                compareBy<RecognizedLine>(
                    { it.bounds?.top ?: Int.MAX_VALUE },
                    { it.bounds?.left ?: Int.MAX_VALUE },
                    { it.model.priority },
                    { it.ordinal },
                ),
            )
            .joinToString(separator = "\n") { it.text }
    }

    private fun Text.toCandidates(model: Model): List<RecognizedLine> {
        var ordinal = 0
        return textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                val text = line.text.trim()
                if (text.isEmpty()) {
                    null
                } else {
                    RecognizedLine(
                        text = text,
                        normalizedText = text.replace(WHITESPACE, " "),
                        bounds = line.boundingBox?.let { Rect(it) },
                        model = model,
                        ordinal = ordinal++,
                    )
                }
            }
        }
    }

    private fun RecognizedLine.representsSameRegion(other: RecognizedLine): Boolean {
        val first = bounds
        val second = other.bounds
        if (first == null || second == null) {
            return normalizedText == other.normalizedText
        }
        if (!Rect.intersects(first, second)) return false

        val intersection = Rect(first)
        if (!intersection.intersect(second)) return false
        if (normalizedText == other.normalizedText) return true
        val intersectionArea = intersection.width().toLong() * intersection.height().toLong()
        val firstArea = first.width().toLong() * first.height().toLong()
        val secondArea = second.width().toLong() * second.height().toLong()
        val unionArea = firstArea + secondArea - intersectionArea
        return unionArea > 0L &&
            intersectionArea.toDouble() / unionArea.toDouble() >= MIN_REGION_OVERLAP
    }

    private fun preferred(first: RecognizedLine, second: RecognizedLine): RecognizedLine {
        val korean = if (first.model == Model.KOREAN) first else second
        val latin = if (first.model == Model.LATIN) first else second
        return if (korean.text.containsHangul()) korean else latin
    }

    private fun String.containsHangul(): Boolean = codePoints().anyMatch { codePoint ->
        codePoint in HANGUL_JAMO ||
            codePoint in HANGUL_COMPATIBILITY_JAMO ||
            codePoint in HANGUL_SYLLABLES
    }

    private fun capCodePoints(text: String): CappedText {
        val count = text.codePointCount(0, text.length)
        if (count <= MAX_TEXT_CODE_POINTS) return CappedText(text, truncated = false)

        val end = text.offsetByCodePoints(0, MAX_TEXT_CODE_POINTS)
        return CappedText(text.substring(0, end), truncated = true)
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener(DIRECT_EXECUTOR) { completed ->
            if (!continuation.isActive) return@addOnCompleteListener
            when {
                completed.isCanceled -> continuation.cancel()
                completed.isSuccessful -> continuation.resume(completed.result)
                else -> continuation.resumeWithException(
                    completed.exception ?: IllegalStateException("OCR task failed"),
                )
            }
        }
    }

    private data class RecognizedLine(
        val text: String,
        val normalizedText: String,
        val bounds: Rect?,
        val model: Model,
        val ordinal: Int,
    )

    private data class CappedText(
        val text: String,
        val truncated: Boolean,
    )

    private enum class Model(
        val priority: Int,
    ) {
        KOREAN(0),
        LATIN(1),
    }

    private val DIRECT_EXECUTOR = Executor { command -> command.run() }
    private val WHITESPACE = Regex("\\s+")
    private const val MIN_REGION_OVERLAP = 0.60
    private val HANGUL_JAMO = 0x1100..0x11ff
    private val HANGUL_COMPATIBILITY_JAMO = 0x3130..0x318f
    private val HANGUL_SYLLABLES = 0xac00..0xd7af
}

data class OcrResult private constructor(
    val state: OcrState,
    val text: String?,
    val truncated: Boolean,
    val failure: OcrFailure?,
) {
    companion object {
        internal fun ready(text: String, truncated: Boolean) = OcrResult(
            state = OcrState.READY,
            text = text,
            truncated = truncated,
            failure = null,
        )

        internal fun failed(failure: OcrFailure) = OcrResult(
            state = OcrState.FAILED,
            text = null,
            truncated = false,
            failure = failure,
        )
    }
}

enum class OcrState {
    READY,
    FAILED,
}

enum class OcrFailure {
    SOURCE_UNAVAILABLE,
    INVALID_IMAGE,
    RECOGNITION_FAILED,
}
