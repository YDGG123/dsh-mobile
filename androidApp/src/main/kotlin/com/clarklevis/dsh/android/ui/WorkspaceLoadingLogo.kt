package com.clarklevis.dsh.android.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.clarklevis.dsh.android.R
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class WorkspaceLoadingLogoAnimation(
    val frames: List<ImageBitmap>,
    val frameMilliseconds: Int
)

private object WorkspaceLoadingLogoCache {
    private var animation: WorkspaceLoadingLogoAnimation? = null

    suspend fun load(context: Context): WorkspaceLoadingLogoAnimation = withContext(Dispatchers.IO) {
        synchronized(this@WorkspaceLoadingLogoCache) {
            animation ?: context.resources.openRawResource(R.raw.orcarness_loading).use {
                decodeWorkspaceLoadingLogo(it.readBytes()).also { decoded -> animation = decoded }
            }
        }
    }
}

@Composable
internal fun WorkspaceLoadingLogo() {
    val context = LocalContext.current
    val animation by produceState<WorkspaceLoadingLogoAnimation?>(null, context) {
        value = WorkspaceLoadingLogoCache.load(context)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var active by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            active = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val reduceMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val decoded = animation
    val modifier = Modifier.size(72.dp).testTag("workspace-loading-logo")
    if (decoded == null) {
        Image(painterResource(R.drawable.orcarness_loading_poster), null, modifier)
    } else {
        val frame = if (active && !reduceMotion) {
            val position = rememberInfiniteTransition(label = "loading-logo").animateFloat(
                initialValue = 0f,
                targetValue = decoded.frames.size.toFloat(),
                animationSpec = infiniteRepeatable(
                    tween(decoded.frameMilliseconds * decoded.frames.size, easing = LinearEasing)
                ),
                label = "loading-logo-frame"
            )
            val index by remember(position, decoded) {
                derivedStateOf { position.value.toInt().coerceIn(decoded.frames.indices) }
            }
            index
        } else 0
        Image(decoded.frames[frame], null, modifier)
    }
}

/** 仅解码应用内生成的全画布 SOURCE APNG，不接收网络图片，也不承担通用图片解码。 */
internal fun decodeWorkspaceLoadingLogo(data: ByteArray): WorkspaceLoadingLogoAnimation {
    val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    val input = DataInputStream(ByteArrayInputStream(data))
    require(ByteArray(8).also(input::readFully).contentEquals(signature))
    var header: ByteArray? = null
    var frameCount = 0
    var duration = 0
    val pixels = ByteArrayOutputStream()
    val frames = mutableListOf<ImageBitmap>()

    fun writeChunk(output: DataOutputStream, type: String, payload: ByteArray) {
        val bytes = type.toByteArray(Charsets.US_ASCII)
        output.writeInt(payload.size)
        output.write(bytes)
        output.write(payload)
        val crc = CRC32().apply { update(bytes); update(payload) }
        output.writeInt(crc.value.toInt())
    }
    fun finishFrame() {
        if (pixels.size() == 0) return
        val png = ByteArrayOutputStream()
        DataOutputStream(png).use { output ->
            output.write(signature)
            writeChunk(output, "IHDR", requireNotNull(header))
            writeChunk(output, "IDAT", pixels.toByteArray())
            writeChunk(output, "IEND", byteArrayOf())
        }
        val bytes = png.toByteArray()
        frames += requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)).asImageBitmap()
        pixels.reset()
    }
    while (input.available() > 0) {
        val length = input.readInt()
        require(length in 0..1_048_576 && length <= input.available() - 8)
        val typeBytes = ByteArray(4).also(input::readFully)
        val type = typeBytes.toString(Charsets.US_ASCII)
        val payload = ByteArray(length).also(input::readFully)
        val crc = CRC32().apply { update(typeBytes); update(payload) }
        require(input.readInt() == crc.value.toInt())
        val fields = DataInputStream(ByteArrayInputStream(payload))
        when (type) {
            "IHDR" -> {
                require(length == 13)
                val width = fields.readInt()
                val height = fields.readInt()
                require(width in 1..256 && height in 1..256)
                header = payload
            }
            "acTL" -> {
                frameCount = fields.readInt()
                require(frameCount in 2..60 && fields.readInt() == 0)
            }
            "fcTL" -> {
                finishFrame()
                require(length == 26 && frames.size < frameCount)
                fields.readInt() // sequence
                val canvas = DataInputStream(ByteArrayInputStream(requireNotNull(header)))
                require(fields.readInt() == canvas.readInt() && fields.readInt() == canvas.readInt())
                require(fields.readInt() == 0 && fields.readInt() == 0)
                val numerator = fields.readUnsignedShort()
                val denominator = fields.readUnsignedShort().let { if (it == 0) 100 else it }
                val milliseconds = numerator * 1_000 / denominator
                require(milliseconds in 10..1_000 && (duration == 0 || duration == milliseconds))
                duration = milliseconds
                require(fields.readUnsignedByte() == 0 && fields.readUnsignedByte() == 0)
            }
            "IDAT" -> pixels.write(payload)
            "fdAT" -> { require(length > 4); pixels.write(payload, 4, length - 4) }
            "IEND" -> { finishFrame(); break }
        }
    }
    require(frames.size == frameCount && duration > 0)
    return WorkspaceLoadingLogoAnimation(frames, duration)
}
