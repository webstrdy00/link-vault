package com.linkvault.app.attachment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.zip.CRC32
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImagePreparationTest {
    private lateinit var context: Context
    private lateinit var testDirectory: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testDirectory = File(context.cacheDir, "image-preparation-${UUID.randomUUID()}")
        assertTrue(testDirectory.mkdirs())
    }

    @After
    fun tearDown() {
        testDirectory.deleteRecursively()
    }

    @Test
    fun jpegPngAndStaticWebpAreActuallyDecoded() = runBlocking {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(32, 96, 160))
        try {
            val inputs = listOf(
                EncodedInput("jpeg", Bitmap.CompressFormat.JPEG, "image/jpeg"),
                EncodedInput("png", Bitmap.CompressFormat.PNG, "image/png"),
                EncodedInput("webp", webpLossless(), "image/webp"),
            )
            inputs.forEach { input ->
                val source = File(testDirectory, "source.${input.extension}")
                writeBitmap(bitmap, input.compressFormat, source)
                val result = ImagePreparation.prepareImage(
                    context,
                    Uri.fromFile(source),
                    destination("${input.extension}-prepared"),
                )

                assertEquals(input.mimeType, result.mimeType)
                assertEquals(640, result.width)
                assertEquals(480, result.height)
                assertTrue(result.bytes in 1..ImagePreparation.MAX_OUTPUT_BYTES)
                assertTrue(BitmapFactoryProbe.canDecode(result.file))
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun sourceLargerThanTenMillionBytesIsRejectedWhileStreaming() = runBlocking {
        val source = File(testDirectory, "oversized.jpg")
        RandomAccessFile(source, "rw").use {
            it.setLength(ImagePreparation.MAX_SOURCE_BYTES + 1L)
        }

        assertPreparationFailure(ImagePreparationFailure.SOURCE_TOO_LARGE) {
            ImagePreparation.prepareImage(context, Uri.fromFile(source), destination("oversized"))
        }
    }

    @Test
    fun oversizedPngHeaderIsRejectedBeforePixelAllocation() = runBlocking {
        val source = File(testDirectory, "pixel-bomb.png")
        source.writeBytes(pngContainer(width = 6_000, height = 5_000))

        assertPreparationFailure(ImagePreparationFailure.PIXEL_LIMIT_EXCEEDED) {
            ImagePreparation.prepareImage(context, Uri.fromFile(source), destination("pixel-bomb"))
        }
    }

    @Test
    fun invalidAndAnimatedInputsAreRejected() = runBlocking {
        val invalidJpeg = File(testDirectory, "invalid.jpg").apply {
            writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x00))
        }
        assertPreparationFailure(ImagePreparationFailure.INVALID_IMAGE) {
            ImagePreparation.prepareImage(
                context,
                Uri.fromFile(invalidJpeg),
                destination("invalid"),
            )
        }

        val gif = File(testDirectory, "animated.gif").apply {
            writeBytes("GIF89a".toByteArray(Charsets.US_ASCII))
        }
        assertPreparationFailure(ImagePreparationFailure.UNSUPPORTED_FORMAT) {
            ImagePreparation.prepareImage(context, Uri.fromFile(gif), destination("gif"))
        }

        val apng = File(testDirectory, "animated.png").apply {
            writeBytes(pngContainer(width = 32, height = 32, animated = true))
        }
        assertPreparationFailure(ImagePreparationFailure.ANIMATED_IMAGE) {
            ImagePreparation.prepareImage(context, Uri.fromFile(apng), destination("apng"))
        }

        val animatedWebp = File(testDirectory, "animated.webp").apply {
            writeBytes(animatedWebpContainer())
        }
        assertPreparationFailure(ImagePreparationFailure.ANIMATED_IMAGE) {
            ImagePreparation.prepareImage(
                context,
                Uri.fromFile(animatedWebp),
                destination("animated-webp"),
            )
        }
    }

    @Test
    fun noisyImageIsDeterministicallyReducedBelowUploadLimit() = runBlocking {
        val width = 2_400
        val height = 2_400
        val pixels = IntArray(width * height)
        var random = 0x1357_2468
        for (index in pixels.indices) {
            random = random xor (random shl 13)
            random = random xor (random ushr 17)
            random = random xor (random shl 5)
            pixels[index] = Color.rgb(
                random ushr 16 and 0xff,
                random ushr 8 and 0xff,
                random and 0xff,
            )
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val source = File(testDirectory, "noise.jpg")
        try {
            writeBitmap(bitmap, Bitmap.CompressFormat.JPEG, source, quality = 90)
        } finally {
            bitmap.recycle()
        }
        assertTrue(source.length() <= ImagePreparation.MAX_SOURCE_BYTES)

        val first = ImagePreparation.prepareImage(
            context,
            Uri.fromFile(source),
            destination("noise-first"),
        )
        val second = ImagePreparation.prepareImage(
            context,
            Uri.fromFile(source),
            destination("noise-second"),
        )

        assertTrue(first.bytes in 1..ImagePreparation.MAX_OUTPUT_BYTES)
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        assertEquals(first.bytes, second.bytes)
        assertTrue(first.file.readBytes().contentEquals(second.file.readBytes()))
    }

    @Test
    fun longDimensionIsReducedWithoutUpscaling() = runBlocking {
        val bitmap = Bitmap.createBitmap(5_000, 1_000, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.CYAN)
        val source = File(testDirectory, "wide.jpg")
        try {
            writeBitmap(bitmap, Bitmap.CompressFormat.JPEG, source)
        } finally {
            bitmap.recycle()
        }

        val result = ImagePreparation.prepareImage(
            context,
            Uri.fromFile(source),
            destination("wide"),
        )

        assertTrue(maxOf(result.width, result.height) <= ImagePreparation.MAX_LONG_DIMENSION)
        assertTrue(result.width > result.height)
    }

    @Test
    fun jpegExifRotationIsAppliedToPreparedPixels() = runBlocking {
        val bitmap = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.MAGENTA)
        val source = File(testDirectory, "rotated.jpg")
        try {
            writeBitmap(bitmap, Bitmap.CompressFormat.JPEG, source)
        } finally {
            bitmap.recycle()
        }
        ExifInterface(source.absolutePath).apply {
            setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_ROTATE_90.toString(),
            )
            saveAttributes()
        }

        val result = ImagePreparation.prepareImage(
            context,
            Uri.fromFile(source),
            destination("rotated"),
        )

        assertEquals(60, result.width)
        assertEquals(120, result.height)
    }

    @Test
    fun unavailableContentUriReturnsTypedFailureAndNoOutput() = runBlocking {
        val destination = destination("missing")

        assertPreparationFailure(ImagePreparationFailure.SOURCE_UNAVAILABLE) {
            ImagePreparation.prepareImage(
                context,
                Uri.parse("content://com.linkvault.app.unavailable/image"),
                destination,
            )
        }
        assertFalse(destination.exists())
    }

    @Test
    fun bundledLatinRecognizerReadsGeneratedFontImage() = runBlocking {
        val bitmap = Bitmap.createBitmap(1_600, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 180f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("LINK VAULT 42", 60f, 260f, paint)
        val fixture = File(testDirectory, "latin-ocr-fixture.png")
        try {
            writeBitmap(bitmap, Bitmap.CompressFormat.PNG, fixture)
        } finally {
            bitmap.recycle()
        }

        val result = OcrProcessor.recognize(context, fixture)

        assertEquals(OcrState.READY, result.state)
        assertTrue(result.text.orEmpty().uppercase().contains("LINK"))
        assertFalse(result.truncated)
        assertEquals(null, result.failure)
    }

    @Test
    fun bundledKoreanRecognizerReadsGeneratedHangulImage() = runBlocking {
        val bitmap = Bitmap.createBitmap(1_600, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 180f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        canvas.drawText("서울 여행 준비", 60f, 260f, paint)
        val fixture = File(testDirectory, "korean-ocr-fixture.png")
        try {
            writeBitmap(bitmap, Bitmap.CompressFormat.PNG, fixture)
        } finally {
            bitmap.recycle()
        }
        val result = OcrProcessor.recognize(context, fixture)
        assertEquals(OcrState.READY, result.state)
        assertTrue(result.text.orEmpty().contains("서울"))
        assertFalse(result.truncated)
        assertEquals(null, result.failure)
    }

    private fun destination(name: String): File = File(testDirectory, "$name-prepared.jpg")

    private suspend fun assertPreparationFailure(
        expected: ImagePreparationFailure,
        action: suspend () -> Unit,
    ) {
        val error = try {
            action()
            throw AssertionError("Expected $expected")
        } catch (caught: ImagePreparationException) {
            caught
        }
        assertEquals(expected, error.reason)
    }

    private fun writeBitmap(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        destination: File,
        quality: Int = 95,
    ) {
        FileOutputStream(destination).use { output ->
            assertTrue(bitmap.compress(format, quality, output))
        }
    }

    private fun webpLossless(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSLESS
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

    private fun pngContainer(
        width: Int,
        height: Int,
        animated: Boolean = false,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.write(PNG_SIGNATURE)
            val header = ByteArrayOutputStream()
            DataOutputStream(header).use { data ->
                data.writeInt(width)
                data.writeInt(height)
                data.writeByte(8)
                data.writeByte(2)
                data.writeByte(0)
                data.writeByte(0)
                data.writeByte(0)
            }
            stream.writePngChunk("IHDR", header.toByteArray())
            if (animated) {
                val animation = ByteArrayOutputStream()
                DataOutputStream(animation).use { data ->
                    data.writeInt(2)
                    data.writeInt(0)
                }
                stream.writePngChunk("acTL", animation.toByteArray())
            }
            stream.writePngChunk("IEND", byteArrayOf())
        }
        return output.toByteArray()
    }

    private fun DataOutputStream.writePngChunk(type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        writeInt(data.size)
        write(typeBytes)
        write(data)
        writeInt(crc.value.toInt())
    }

    private fun animatedWebpContainer(): ByteArray {
        val payload = ByteArrayOutputStream().apply {
            write("WEBP".toByteArray(Charsets.US_ASCII))
            write("VP8X".toByteArray(Charsets.US_ASCII))
            writeLittleEndianInt(10)
            write(0x02)
            repeat(9) { write(0) }
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write("RIFF".toByteArray(Charsets.US_ASCII))
            writeLittleEndianInt(payload.size)
            write(payload)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLittleEndianInt(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
        write(value ushr 16 and 0xff)
        write(value ushr 24 and 0xff)
    }

    private data class EncodedInput(
        val extension: String,
        val compressFormat: Bitmap.CompressFormat,
        val mimeType: String,
    )

    private object BitmapFactoryProbe {
        fun canDecode(file: File): Boolean {
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return false
            bitmap.recycle()
            return true
        }
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4e,
            0x47,
            0x0d,
            0x0a,
            0x1a,
            0x0a,
        )
    }
}
