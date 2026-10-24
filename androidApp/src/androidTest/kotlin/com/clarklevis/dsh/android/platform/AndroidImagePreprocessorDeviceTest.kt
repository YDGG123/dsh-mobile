package com.clarklevis.dsh.android.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidImagePreprocessorDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun supportedFormatsKeepOriginalBytes() = runBlocking {
        val images = listOf(
            Triple("jpg", "image/jpeg", bitmapBytes(Bitmap.CompressFormat.JPEG)),
            Triple("png", "image/png", bitmapBytes(Bitmap.CompressFormat.PNG)),
            Triple("webp", "image/webp", bitmapBytes(Bitmap.CompressFormat.WEBP)),
            Triple(
                "gif",
                "image/gif",
                Base64.decode(
                    "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7",
                    Base64.DEFAULT
                )
            )
        )
        for ((extension, mediaType, original) in images) {
            val prepared = prepare(original, extension)
            assertEquals(mediaType, prepared.outgoing.mediaType)
            assertArrayEquals(original, decodedBytes(prepared))
            assertEquals(original.size, prepared.byteCount)
        }
    }

    @Test
    fun jpegWithHeicExtensionUsesActualMediaType() = runBlocking {
        val original = bitmapBytes(Bitmap.CompressFormat.JPEG)
        val prepared = prepare(original, "heic")
        assertEquals("image/jpeg", prepared.outgoing.mediaType)
        assertArrayEquals(original, decodedBytes(prepared))
    }

    @Test
    fun pngWithoutImageExtensionUsesActualMediaType() = runBlocking {
        val original = bitmapBytes(Bitmap.CompressFormat.PNG)
        val prepared = prepare(original, "unknown")
        assertEquals("image/png", prepared.outgoing.mediaType)
        assertArrayEquals(original, decodedBytes(prepared))
    }

    @Test
    fun pngMislabelledAsJpegUsesActualMediaType() = runBlocking {
        val original = bitmapBytes(Bitmap.CompressFormat.PNG)
        val prepared = prepare(original, "jpg")
        assertEquals("image/png", prepared.outgoing.mediaType)
        assertArrayEquals(original, decodedBytes(prepared))
    }

    @Test
    fun smallBmpIsTranscodedToJpeg() = runBlocking {
        assertJpeg(prepare(bmpBytes(), "bmp"), 2, 2)
    }

    @Test
    fun bmpMislabelledAsJpegStillRequiresTranscoding() = runBlocking {
        assertJpeg(prepare(bmpBytes(), "jpg"), 2, 2)
    }

    @Test
    fun smallHeicIsTranscodedToJpeg() = runBlocking {
        // 由系统编码器生成的 32 × 16 纯红 HEIC，不包含用户照片或元数据。
        val original = instrumentation.context.assets.open("images/red-32x16.heic").use { it.readBytes() }
        assertJpeg(prepare(original, "heic"), 32, 16)
    }

    @Test
    fun oversizedSupportedImageStillResizes() = runBlocking {
        val original = bitmapBytes(Bitmap.CompressFormat.PNG, width = 2_400, height = 1_200)
        assertJpeg(prepare(original, "png"), 2_000, 1_000)
    }

    @Test
    fun rotatedJpegStillAppliesOrientation() = runBlocking {
        val original = bitmapBytes(Bitmap.CompressFormat.JPEG)
        val file = createImageFile(original, "jpg")
        try {
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val prepared = AndroidImagePreprocessor(context.contentResolver).prepare(
                FileProvider.getUriForFile(context, "${context.packageName}.workspace-files", file)
            )
            assertJpeg(prepared, 16, 32)
        } finally {
            file.delete()
        }
    }

    private suspend fun prepare(bytes: ByteArray, extension: String): AndroidPreparedImage {
        val file = createImageFile(bytes, extension)
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.workspace-files", file)
            AndroidImagePreprocessor(context.contentResolver).prepare(uri)
        } finally {
            file.delete()
        }
    }

    private fun createImageFile(bytes: ByteArray, extension: String): File {
        val directory = File(context.cacheDir, "workspace-files/image-preprocessor-tests").apply { mkdirs() }
        return File.createTempFile("image-", ".$extension", directory).apply { writeBytes(bytes) }
    }

    private fun assertJpeg(prepared: AndroidPreparedImage, width: Int, height: Int) {
        assertEquals("image/jpeg", prepared.outgoing.mediaType)
        assertEquals(width, prepared.width)
        assertEquals(height, prepared.height)
        val bytes = decodedBytes(prepared)
        assertEquals(bytes.size, prepared.byteCount)
        assertTrue(bytes.size <= AndroidImagePreprocessor.MAXIMUM_BYTES)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        assertEquals("image/jpeg", options.outMimeType)
        assertEquals(width, options.outWidth)
        assertEquals(height, options.outHeight)
    }

    private fun decodedBytes(prepared: AndroidPreparedImage): ByteArray = Base64.decode(prepared.outgoing.base64Data, Base64.DEFAULT)

    private fun bitmapBytes(format: Bitmap.CompressFormat, width: Int = 32, height: Int = 16): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.RED)
            ByteArrayOutputStream().use {
                assertTrue(bitmap.compress(format, 90, it))
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun bmpBytes(): ByteArray = ByteBuffer.allocate(70).order(ByteOrder.LITTLE_ENDIAN).apply {
        put('B'.code.toByte()).put('M'.code.toByte())
        putInt(70).putInt(0).putInt(54)
        putInt(40).putInt(2).putInt(2)
        putShort(1).putShort(24)
        putInt(0).putInt(16).putInt(0).putInt(0).putInt(0).putInt(0)
        repeat(2) {
            repeat(2) { put(0).put(0).put(0xFF.toByte()) }
            putShort(0)
        }
    }.array()
}
