package com.clarklevis.dsh.android.ui

import android.graphics.Picture
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.SVG
import com.clarklevis.dsh.android.R

/** 本地矢量按身份分配；缓存绘制指令，不随重组重复解析 SVG。 */
private object AgentAvatarPictures {
    val cache = LruCache<Int, Picture>(144)
    val resources = intArrayOf(
        R.raw.agent_avatar_000, R.raw.agent_avatar_001, R.raw.agent_avatar_002, R.raw.agent_avatar_003, R.raw.agent_avatar_004, R.raw.agent_avatar_005,
        R.raw.agent_avatar_006, R.raw.agent_avatar_007, R.raw.agent_avatar_008, R.raw.agent_avatar_009, R.raw.agent_avatar_010, R.raw.agent_avatar_011,
        R.raw.agent_avatar_012, R.raw.agent_avatar_013, R.raw.agent_avatar_014, R.raw.agent_avatar_015, R.raw.agent_avatar_016, R.raw.agent_avatar_017,
        R.raw.agent_avatar_018, R.raw.agent_avatar_019, R.raw.agent_avatar_020, R.raw.agent_avatar_021, R.raw.agent_avatar_022, R.raw.agent_avatar_023,
        R.raw.agent_avatar_024, R.raw.agent_avatar_025, R.raw.agent_avatar_026, R.raw.agent_avatar_027, R.raw.agent_avatar_028, R.raw.agent_avatar_029,
        R.raw.agent_avatar_030, R.raw.agent_avatar_031, R.raw.agent_avatar_032, R.raw.agent_avatar_033, R.raw.agent_avatar_034, R.raw.agent_avatar_035,
        R.raw.agent_avatar_036, R.raw.agent_avatar_037, R.raw.agent_avatar_038, R.raw.agent_avatar_039, R.raw.agent_avatar_040, R.raw.agent_avatar_041,
        R.raw.agent_avatar_042, R.raw.agent_avatar_043, R.raw.agent_avatar_044, R.raw.agent_avatar_045, R.raw.agent_avatar_046, R.raw.agent_avatar_047,
        R.raw.agent_avatar_048, R.raw.agent_avatar_049, R.raw.agent_avatar_050, R.raw.agent_avatar_051, R.raw.agent_avatar_052, R.raw.agent_avatar_053,
        R.raw.agent_avatar_054, R.raw.agent_avatar_055, R.raw.agent_avatar_056, R.raw.agent_avatar_057, R.raw.agent_avatar_058, R.raw.agent_avatar_059,
        R.raw.agent_avatar_060, R.raw.agent_avatar_061, R.raw.agent_avatar_062, R.raw.agent_avatar_063, R.raw.agent_avatar_064, R.raw.agent_avatar_065,
        R.raw.agent_avatar_066, R.raw.agent_avatar_067, R.raw.agent_avatar_068, R.raw.agent_avatar_069, R.raw.agent_avatar_070, R.raw.agent_avatar_071,
        R.raw.agent_avatar_072, R.raw.agent_avatar_073, R.raw.agent_avatar_074, R.raw.agent_avatar_075, R.raw.agent_avatar_076, R.raw.agent_avatar_077,
        R.raw.agent_avatar_078, R.raw.agent_avatar_079, R.raw.agent_avatar_080, R.raw.agent_avatar_081, R.raw.agent_avatar_082, R.raw.agent_avatar_083,
        R.raw.agent_avatar_084, R.raw.agent_avatar_085, R.raw.agent_avatar_086, R.raw.agent_avatar_087, R.raw.agent_avatar_088, R.raw.agent_avatar_089,
        R.raw.agent_avatar_090, R.raw.agent_avatar_091, R.raw.agent_avatar_092, R.raw.agent_avatar_093, R.raw.agent_avatar_094, R.raw.agent_avatar_095,
        R.raw.agent_avatar_096, R.raw.agent_avatar_097, R.raw.agent_avatar_098, R.raw.agent_avatar_099, R.raw.agent_avatar_100, R.raw.agent_avatar_101,
        R.raw.agent_avatar_102, R.raw.agent_avatar_103, R.raw.agent_avatar_104, R.raw.agent_avatar_105, R.raw.agent_avatar_106, R.raw.agent_avatar_107,
        R.raw.agent_avatar_108, R.raw.agent_avatar_109, R.raw.agent_avatar_110, R.raw.agent_avatar_111, R.raw.agent_avatar_112, R.raw.agent_avatar_113,
        R.raw.agent_avatar_114, R.raw.agent_avatar_115, R.raw.agent_avatar_116, R.raw.agent_avatar_117, R.raw.agent_avatar_118, R.raw.agent_avatar_119,
        R.raw.agent_avatar_120, R.raw.agent_avatar_121, R.raw.agent_avatar_122, R.raw.agent_avatar_123, R.raw.agent_avatar_124, R.raw.agent_avatar_125,
        R.raw.agent_avatar_126, R.raw.agent_avatar_127, R.raw.agent_avatar_128, R.raw.agent_avatar_129, R.raw.agent_avatar_130, R.raw.agent_avatar_131,
        R.raw.agent_avatar_132, R.raw.agent_avatar_133, R.raw.agent_avatar_134, R.raw.agent_avatar_135, R.raw.agent_avatar_136, R.raw.agent_avatar_137,
        R.raw.agent_avatar_138, R.raw.agent_avatar_139, R.raw.agent_avatar_140, R.raw.agent_avatar_141, R.raw.agent_avatar_142, R.raw.agent_avatar_143,
    )
}

@Composable
internal fun AgentAvatar(avatarIndex: Int, modifier: Modifier = Modifier) {
    val resources = LocalContext.current.resources
    val index = avatarIndex.coerceIn(0, AgentAvatarPictures.resources.lastIndex)
    val picture = remember(resources, index) {
        AgentAvatarPictures.cache[index] ?: resources.openRawResource(AgentAvatarPictures.resources[index]).use {
            SVG.getFromInputStream(it).renderToPicture(64, 64)
        }.also { AgentAvatarPictures.cache.put(index, it) }
    }
    Canvas(modifier.size(32.dp)) {
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val saved = native.save()
            native.scale(size.width / 64f, size.height / 64f)
            native.drawPicture(picture)
            native.restoreToCount(saved)
        }
    }
}
