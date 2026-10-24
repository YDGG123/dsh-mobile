package com.clarklevis.dsh.android.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** 防止新增页面重新绕过菜单组件，约束覆盖整个 Android 源码。 */
class MenuArchitectureTest {
    @Test fun appMenusOnlyUseSharedMenuComponents() {
        val root = listOf(File("src/main/kotlin"), File("androidApp/src/main/kotlin")).first { it.isDirectory }
        val violations = root.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            val source = file.readText()
            buildList {
                if (Regex("import (androidx\\.compose\\.material3\\.(DropdownMenu|DropdownMenuItem|ExposedDropdownMenuBox)|android\\.widget\\.(PopupMenu|PopupWindow))\\b").containsMatchIn(source))
                    add("${file.name}: 使用了平台或 Material 菜单")
                if (Regex("\\b(DropdownMenu|DropdownMenuItem|ExposedDropdownMenuBox|PopupMenu|PopupWindow)\\s*\\(").containsMatchIn(source))
                    add("${file.name}: 直接创建平台或 Material 菜单")
                if (file.name != "DshMenuWebView.kt" && Regex("\\bWebView\\s*\\(").containsMatchIn(source))
                    add("${file.name}: 原生网页绕过了统一文字菜单")
                if (file.name != "DshMenu.kt" && Regex("\\bPopup\\s*\\(").containsMatchIn(source))
                    add("${file.name}: 绕过统一菜单浮层")
            }
        }.toList()
        assertTrue(violations.joinToString("\n"), violations.isEmpty())
    }
}
