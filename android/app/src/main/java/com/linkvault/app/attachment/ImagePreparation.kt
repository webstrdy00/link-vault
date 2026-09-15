package com.linkvault.app.attachment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext
import kotlin.math.floor
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object ImagePreparation {
    const val MAX_SOURCE_BYTES = 10_000_000L
    const val MAX_PIXELS = 24_000_000L
    const val MAX_LONG_DIMENSION = 4_096
    const val MAX_OUTPUT_BYTES = 2_000_000L

    private val lossyQualities = intArrayOf(92, 84, 76, 68, 60, 52)

    suspend fun prepareImage(
        context: Context,
        uri: Uri,
        destinationFile: File,
    ): PreparedImage = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val destination = validateDestination(context, destinationFile)
        val directory = destination.parentFile
            ?: throw ImagePreparationException(ImagePreparationFailure.INVALID_DESTINATION)
        if (!directory.exists() && !directory.mkdirs()) {
            throw ImagePreparationException(ImagePreparationFailure.OUTPUT_WRITE_FAILED)
        }
        if (!directory.isDirectory || destination.exists()) {
            throw ImagePreparationException(ImagePreparationFailure.INVALID_DESTINATION)
        }

        val sourceFile = createPrivateTempFile("image-source-", directory)
        val encodedFile = try {
            createPrivateTempFile("image-output-", directory)
        } catch (error: ImagePreparationException) {
            sourceFile.delete()
            throw error
        }
        var bitmap: Bitmap? = null
        try {
            copySource(context, uri, sourceFile)
            coroutineContext.ensureActive()

            val detected = detectFormat(sourceFile)
            detected.headerDimensions?.let(::validateDimensions)

            val bounds = decodeBounds(sourceFile)
            validateDimensions(bounds)
            detected.headerDimensions?.let { header ->
                if (header.width != bounds.width || header.height != bounds.height) {
                    throw ImagePreparationException(ImagePreparationFailure.INVALID_IMAGE)
                }
            }

            val orientation = readOrientation(sourceFile)
            val decoded = decodeImage(sourceFile, bounds)
            bitmap = decoded.bitmap
            coroutineContext.ensureActive()

            if (!decoded.orientationApplied) {
                bitmap = replaceBitmap(bitmap, applyOrientation(bitmap, orientation))
            }
            bitmap = replaceBitmap(bitmap, resizeToLongDimension(bitmap))
            coroutineContext.ensureActive()

            val encoded = encodeWithinLimit(bitmap, detected.format, encodedFile)
            coroutineContext.ensureActive()
            movePreparedFile(encodedFile, destination)

            PreparedImage(
                file = destination,
                mimeType = detected.format.mimeType,
                width = encoded.width,
                height = encoded.height,
                bytes = destination.length(),
            )
        } finally {
            bitmap?.recycle()
            sourceFile.delete()
            encodedFile.delete()
        }
    }

    private fun validateDestination(context: Context, destinationFile: File): File {
        val destination = try {
            destinationFile.canonicalFile
        } catch (_: IOException) {
            throw ImagePreparationException(ImagePreparationFailure.INVALID_DESTINATION)
        }
        val privateRoots = listOf(
            context.filesDir,
            context.cacheDir,
            context.noBackupFilesDir,
            context.codeCacheDir,
        ).mapNotNull {
            try {
                it.canonicalFile
            } catch (_: IOException) {
                null
            }
        }
        if (privateRoots.none { destination.isStrictDescendantOf(it) }) {
            throw ImagePreparationException(ImagePreparationFailure.INVALID_DESTINATION)
        }
        return destination
    }

    private fun createPrivateTempFile(prefix: String, directory: File): File = try {
        File.createTempFile(prefix, ".tmp", directory)
    } catch (_: IOException) {
        throw ImagePreparationException(ImagePreparationFailure.OUTPUT_WRITE_FAILED)
    } catch (_: SecurityException) {
        throw ImagePreparationException(ImagePreparationFailure.OUTPUT_WRITE_FAILED)
    }

    private fun File.isStrictDescendantOf(directory: File): Boolean =
        path.startsWith(directory.path + File.separator)

    private suspend fun copySource(context: Context, uri: Uri, destination: File) {
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (_: SecurityException) {
            throw ImagePreparationException(ImagePreparationFailure.SOURCE_UNAVAILABLE)
        } catch (_: FileNotFoundException) {
            throw ImagePreparationException(ImagePreparationFailure.SOURCE_UNAVAILABLE)
        } catch (_: IllegalArgumentException) {
            throw ImagePreparationException(ImagePreparationFailure.SOURCE_UNAVAILABLE)
        } ?: throw ImagePreparationException(ImagePreparationFailure.SOURCE_UNAVAILABLE)

        try {
            input.use { source ->
                FileOutputStream(destination, false).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = source.read(buffer)
                        if (read == -1) break
                        total += read
                        if (total > MAX_SOURCE_BYTES) {
                            throw ImagePreparationException(
                                ImagePreparationFailure.SOURCE_TOO_LARGE,
                            )
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (error: ImagePreparationException) {
            throw error
        } catch (_: SecurityException) {
            throw ImagePreparationException(ImagePreparationFailure.SOURCE_UNAVAILABLE)
        } catch (_: IllegalArgumentException) {
            throw ImagePreparationException(ImagePreparationFailure.SOURCE_UNAVAILABLE)
        } catch (_: IOException) {
            throw ImagePreparationException(ImagePreparationFailure.SOURCE_UNAVAILABLE)
        }
    }

    private fun detectFormat(file: File): DetectedImage {
        if (file.length() < 3L) {
            throw ImagePreparationException(ImagePreparationFailure.UNSUPPORTED_FORMAT)
        }
        val header = ByteArray(minOf(12L, file.length()).toInt())
        RandomAccessFile(file, "r").use { it.readFully(header) }

        return when {
            header.size >= 3 &&
                header[0].unsigned() == 0xff &&
                header[1].unsigned() == 0xd8 &&
                header[2].unsigned() == 0xff -> DetectedImage(ImageFormat.JPEG)

            header.size >= PNG_SIGNATURE.size &&
                header.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE) ->
                inspectPng(file)

            header.size >= 12 &&
                header.asAscii(0, 4) == "RIFF" &&
                header.asAscii(8, 12) == "WEBP" -> inspectWebp(file)

            else -> throw ImagePreparationException(ImagePreparationFailure.UNSUPPORTED_FORMAT)
        }
    }

    private fun inspectPng(file: File): DetectedImage {
        var dimensions: ImageDimensions? = null
        var firstChunk = true
        var sawEnd = false
        RandomAccessFile(file, "r").use { input ->
            input.seek(PNG_SIGNATURE.size.toLong())
            while (input.filePointer + 12L <= input.length()) {
                val chunkLength = input.readInt().toLong() and UINT_MASK
                val typeBytes = ByteArray(4).also(input::readFully)
                val type = typeBytes.toString(Charsets.US_ASCII)
                val dataStart = input.filePointer
                val chunkEnd = dataStart + chunkLength + 4L
                if (chunkEnd < dataStart || chunkEnd > input.length()) {
                    throw ImagePreparationException(ImagePreparationFailure.INVALID_IMAGE)
                }

                if (firstChunk) {
                    if (type != "IHDR" || chunkLength != 13L) {
                        throw ImagePreparationException(ImagePreparationFailure.INVALID_IMAGE)
                    }
                    val width = input.readInt()
                    val height = input.readInt()
                    if (width <= 0 || height <= 0) {
                        throw ImagePreparationException(ImagePreparationFailure.INVALID_IMAGE)
                    }
                    dimensions = ImageDimensions(width, height)
                }
                if (type == "acTL" || type == "fcTL" || type == "fdAT") {
                    throw ImagePreparationException(ImagePreparationFailure.ANIMATED_IMAGE)
                }

                input.seek(chunkEnd)
                firstChunk = false
                if (type == "IEND") {
                    if (chunkLength != 0L) {
                        throw ImagePreparationException(ImagePreparationFailure.INVALID_IMAGE)
                    }
                    sawEnd = true
                    break
                }
            }
        }
        if (!sawEnd || dimensions == null) {
            throw ImagePreparationException(ImagePreparationFailure.INVALID_IMAGE)
        }
        return DetectedImage(ImageFormat.PNG, dimensions)
    }

    private fun inspectWebp(file: File): DetectedImage {
        var sawImageData = false
        RandomAccessFile(file, "r").use { input ->
            input.seek(4L)
            val declaredEnd = input.readUnsignedIntLittleEndian() + 8L
            if (declaredEnd < 12L || declaredEnd > input.length()) {
                throw ImagePreparationException(ImagePreparationFailure.INVALID_IMAGE)
            }
            input.seek(12L)
            while (input.filePointer + 8L <= declaredEnd) {
                val typeBytes = ByteArray(4).also(input::readFully)
                val type = typeBytes.toString(Charsets.US_ASCII)
                val chunkLength = input.readUnsignedIntLittleEndian()
                val dataStart = input.filePointer
                val paddedLength = chunkLength + (chunkLength and 1L)
                val chunkEnd = dataStart + paddedLength
                if (chunkEnd < dataStart || chunkEnd > declaredEnd) {
                    throw ImagePreparationException(ImagePreparationFailure.INVALID_IMAGE)
                }

                when (type) {
                    "ANIM", "ANMF" ->
                        throw ImagePreparationException(ImagePreparationFailure.ANIMATED_IMAGE)

                    "VP8X" -> {
                        if (chunkLength < 10L) {
                            throw ImagePreparationException(
                                ImagePreparationFailure.INVALID_IMAGE,
                            )
                        }
                        val featureFlags = input.readUnsignedByte()
                        if (featureFlags and WEBP_ANIMATION_FLAG != 0) {
                            throw ImagePreparationException(
                                ImagePreparationFailure.ANIMATED_IMAGE,
                            )
                        }
                    }

                    "VP8 ", "VP8L" -> sawImageData = true
                }
                input.seek(chunkEnd)
            }
        }
        if (!sawImageData) {
            throw ImagePreparationException(ImagePreparationFailure.INVALID_IMAGE)
        }
        return DetectedImage(ImageFormat.WEBP)
    }

    private fun decodeBounds(file: File): ImageDimensions {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw ImagePreparationException(ImagePreparationFailure.INVALID_IMAGE)
        }
        return ImageDimensions(options.outWidth, options.outHeight)
    }

    private fun validateDimensions(dimensions: ImageDimensions) {
        val pixels = dimensions.width.toLong() * dimensions.height.toLong()
        if (pixels > MAX_PIXELS) {
            throw ImagePreparationException(ImagePreparationFailure.PIXEL_LIMIT_EXCEEDED)
        }
    }

    private fun readOrientation(file: File): Int = try {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    } catch (_: IOException) {
        throw ImagePreparationException(ImagePreparationFailure.INVALID_IMAGE)
    }

    private fun decodeImage(file: File, dimensions: ImageDimensions): DecodedBitmap =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeModern(file)
        } else {
            DecodedBitmap(
                bitmap = decodeSampled(file, dimensions),
                orientationApplied = false,
            )
        }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeModern(file: File): DecodedBitmap {
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longDimension = maxOf(info.size.width, info.size.height)
                if (longDimension > MAX_LONG_DIMENSION) {
                    val scale = MAX_LONG_DIMENSION.toDouble() / longDimension.toDouble()
                    decoder.setTargetSize(
                        maxOf(1, floor(info.size.width * scale).toInt()),
                        maxOf(1, floor(info.size.height * scale).toInt()),
                    )
                }
            }
        } catch (_: IOException) {
            throw ImagePreparationException(ImagePreparationFailure.INVALID_IMAGE)
        } catch (_: RuntimeException) {
            throw ImagePreparationException(ImagePreparationFailure.INVALID_IMAGE)
        }
        return DecodedBitmap(bitmap = bitmap, orientationApplied = true)
    }

    private fun decodeSampled(file: File, dimensions: ImageDimensions): Bitmap {
        var sampleSize = 1
        while (
            dimensions.width / sampleSize > MAX_LONG_DIMENSION ||
            dimensions.height / sampleSize > MAX_LONG_DIMENSION
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize
            inScaled = false
        }
        return try {
            BitmapFactory.decodeFile(file.absolutePath, options)
                ?: throw ImagePreparationException(ImagePreparationFailure.INVALID_IMAGE)
        } catch (error: ImagePreparationException) {
            throw error
        } catch (_: RuntimeException) {
            throw ImagePreparationException(ImagePreparationFailure.INVALID_IMAGE)
        }
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_NORMAL,
            ExifInterface.ORIENTATION_UNDEFINED,
            -> return bitmap

            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> throw ImagePreparationException(ImagePreparationFailure.INVALID_IMAGE)
        }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (_: IllegalArgumentException) {
            throw ImagePreparationException(ImagePreparationFailure.INVALID_IMAGE)
        }
    }

    private fun resizeToLongDimension(bitmap: Bitmap): Bitmap {
        val longDimension = maxOf(bitmap.width, bitmap.height)
        if (longDimension <= MAX_LONG_DIMENSION) return bitmap

        val scale = MAX_LONG_DIMENSION.toDouble() / longDimension.toDouble()
        val width = maxOf(1, floor(bitmap.width * scale).toInt())
        val height = maxOf(1, floor(bitmap.height * scale).toInt())
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private suspend fun encodeWithinLimit(
        source: Bitmap,
        format: ImageFormat,
        output: File,
    ): ImageDimensions {
        var current = source
        var ownsCurrent = false
        try {
            repeat(MAX_SCALE_ATTEMPTS) {
                coroutineContext.ensureActive()
                val qualities = if (format == ImageFormat.PNG) {
                    intArrayOf(100)
                } else {
                    lossyQualities
                }
                for (quality in qualities) {
                    coroutineContext.ensureActive()
                    writeBitmap(current, format, quality, output)
                    val encodedBytes = output.length()
                    if (encodedBytes == 0L) {
                        throw ImagePreparationException(
                            ImagePreparationFailure.OUTPUT_WRITE_FAILED,
                        )
                    }
                    if (encodedBytes <= MAX_OUTPUT_BYTES) {
                        return ImageDimensions(current.width, current.height)
                    }
                }

                if (current.width == 1 && current.height == 1) {
                    throw ImagePreparationException(ImagePreparationFailure.OUTPUT_TOO_LARGE)
                }
                val ratio = sqrt(MAX_OUTPUT_BYTES.toDouble() / output.length().toDouble())
                    .times(SCALE_SAFETY_FACTOR)
                    .coerceIn(MIN_SCALE_STEP, MAX_SCALE_STEP)
                val nextWidth = scaledDimension(current.width, ratio)
                val nextHeight = scaledDimension(current.height, ratio)
                val next = Bitmap.createScaledBitmap(current, nextWidth, nextHeight, true)
                if (ownsCurrent && next !== current) current.recycle()
                current = next
                ownsCurrent = current !== source
            }
            throw ImagePreparationException(ImagePreparationFailure.OUTPUT_TOO_LARGE)
        } finally {
            if (ownsCurrent) current.recycle()
        }
    }

    private fun scaledDimension(value: Int, ratio: Double): Int {
        val scaled = floor(value * ratio).toInt().coerceAtLeast(1)
        return if (scaled == value && value > 1) value - 1 else scaled
    }

    private fun writeBitmap(
        bitmap: Bitmap,
        format: ImageFormat,
        quality: Int,
        output: File,
    ) {
        val compressFormat = when (format) {
            ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
            ImageFormat.PNG -> Bitmap.CompressFormat.PNG
            ImageFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
        }
        try {
            FileOutputStream(output, false).use { stream ->
                if (!bitmap.compress(compressFormat, quality, stream)) {
                    throw ImagePreparationException(
                        ImagePreparationFailure.OUTPUT_WRITE_FAILED,
                    )
                }
            }
        } catch (error: ImagePreparationException) {
            throw error
        } catch (_: IOException) {
            throw ImagePreparationException(ImagePreparationFailure.OUTPUT_WRITE_FAILED)
        }
    }

    private fun movePreparedFile(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: IOException) {
            throw ImagePreparationException(ImagePreparationFailure.OUTPUT_WRITE_FAILED)
        }
    }

    private fun replaceBitmap(previous: Bitmap, next: Bitmap): Bitmap {
        if (previous !== next) previous.recycle()
        return next
    }

    private fun RandomAccessFile.readUnsignedIntLittleEndian(): Long {
        val b0 = readUnsignedByte().toLong()
        val b1 = readUnsignedByte().toLong()
        val b2 = readUnsignedByte().toLong()
        val b3 = readUnsignedByte().toLong()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun Byte.unsigned(): Int = toInt() and 0xff

    private fun ByteArray.asAscii(fromIndex: Int, toIndex: Int): String =
        copyOfRange(fromIndex, toIndex).toString(Charsets.US_ASCII)

    private data class DetectedImage(
        val format: ImageFormat,
        val headerDimensions: ImageDimensions? = null,
    )

    private enum class ImageFormat(
        val mimeType: String,
    ) {
        JPEG("image/jpeg"),
        PNG("image/png"),
        WEBP("image/webp"),
    }

    private data class ImageDimensions(
        val width: Int,
        val height: Int,
    )

    private data class DecodedBitmap(
        val bitmap: Bitmap,
        val orientationApplied: Boolean,
    )

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4e,
        0x47,
        0x0d,
        0x0a,
        0x1a,
        0x0a,
    )
    private const val UINT_MASK = 0xffff_ffffL
    private const val WEBP_ANIMATION_FLAG = 0x02
    private const val SCALE_SAFETY_FACTOR = 0.94
    private const val MIN_SCALE_STEP = 0.50
    private const val MAX_SCALE_STEP = 0.85
    private const val MAX_SCALE_ATTEMPTS = 64
}

data class PreparedImage(
    val file: File,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val bytes: Long,
)

class ImagePreparationException(
    val reason: ImagePreparationFailure,
) : IOException(reason.name)

enum class ImagePreparationFailure {
    SOURCE_UNAVAILABLE,
    SOURCE_TOO_LARGE,
    UNSUPPORTED_FORMAT,
    ANIMATED_IMAGE,
    INVALID_IMAGE,
    PIXEL_LIMIT_EXCEEDED,
    INVALID_DESTINATION,
    OUTPUT_TOO_LARGE,
    OUTPUT_WRITE_FAILED,
}
