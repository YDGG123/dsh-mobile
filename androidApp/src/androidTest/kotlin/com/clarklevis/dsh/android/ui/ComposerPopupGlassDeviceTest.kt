package com.clarklevis.dsh.android.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class ComposerPopupGlassDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun downwardArrowJoinHasNoInteriorBorder() = assertArrowJoin(pointsUp = false)

    @Test
    fun upwardArrowJoinHasNoInteriorBorder() = assertArrowJoin(pointsUp = true)

    private fun assertArrowJoin(pointsUp: Boolean) {
        var expanded by mutableStateOf(false)
        var anchorCenterX = Float.NaN
        var contentOriginX = Float.NaN
        compose.setContent {
            DshTheme {
                DshMenuBackdropHost { backdropModifier ->
                    Box(backdropModifier.fillMaxSize().background(Color.White)) {
                        Box(Modifier.align(if (pointsUp) Alignment.TopEnd else Alignment.BottomEnd).padding(40.dp)) {
                            Box(Modifier.size(60.dp).onGloballyPositioned {
                                anchorCenterX = it.positionOnScreen().x + it.size.width / 2f
                            }.testTag("join-trigger").clickable { expanded = true })
                            ComposerGlassPopover(expanded, { expanded = false }, "join-surface", width = 240.dp) {
                                Spacer(Modifier.fillMaxWidth().height(120.dp).onGloballyPositioned {
                                    contentOriginX = it.positionOnScreen().x
                                })
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("join-trigger").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val image = compose.onNodeWithTag("join-surface").assertIsDisplayed().captureToImage()
        val pixels = image.toPixelMap()
        val density = compose.density.density
        val arrowHeight = (8 * density).toInt()
        val baseY = if (pointsUp) arrowHeight else image.height - arrowHeight
        // 使用真实屏幕坐标，避免把紧邻窗口边缘的箭头误判成圆角/投影。
        assertTrue(anchorCenterX.isFinite() && contentOriginX.isFinite())
        val arrowMargin = 34 * density // 24 dp 圆角 + 10 dp 箭头半宽。
        val arrowX = (anchorCenterX - contentOriginX + 22 * density)
            .coerceIn(arrowMargin, image.width - arrowMargin).toInt()
        val samples = (baseY - 4..baseY + 4).map { y -> pixels[arrowX, y].red }
        assertTrue("箭头连接处不能出现内部描边：$samples", samples.maxOrNull()!! - samples.minOrNull()!! < 0.015f)
    }

    @Test
    fun popupSamplesItsScreenPositionAndBlursBackgroundStripes() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        var expanded by mutableStateOf(false)
        compose.setContent {
            DshTheme {
                DshMenuBackdropHost { backdropModifier ->
                    Box(backdropModifier.fillMaxSize()) {
                        Canvas(Modifier.fillMaxSize()) {
                            val stripe = 4.dp.toPx()
                            var x = 0f
                            var index = 0
                            while (x < size.width) {
                                val color = if (index % 2 == 0) Color.Black
                                    else if (x < size.width / 2) Color.Red else Color.Blue
                                drawRect(color, Offset(x, 0f), Size(stripe, size.height))
                                x += stripe
                                index++
                            }
                        }
                        Box(Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
                            Box(Modifier.size(60.dp).testTag("glass-trigger").clickable { expanded = true })
                            ComposerGlassPopover(expanded, { expanded = false }, "glass-surface", width = 160.dp) {
                                Spacer(Modifier.height(120.dp))
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("glass-trigger").performClick()
        val image = compose.onNodeWithTag("glass-surface").assertIsDisplayed().captureToImage()
        val pixels = image.toPixelMap()
        val samples = (image.width / 2 - 12..image.width / 2 + 12).map { x -> pixels[x, image.height / 2] }
        assertTrue("跨窗口采样必须对应弹窗后方的蓝色区域", samples.all { it.blue - it.red > 0.12f })
        val variation = samples.maxOf { it.blue } - samples.minOf { it.blue }
        assertTrue("背景条纹应被实际模糊，不能只覆盖半透明色", variation < 0.08f)
    }
}
