package com.clarklevis.dsh.android.ui

import java.util.Locale
import org.junit.rules.ExternalResource

/** 固定使用中文文案作断言的设备测试，结束后恢复原进程语言。 */
class ChineseDeviceLocaleRule : ExternalResource() {
    private lateinit var previous: Locale
    override fun before() {
        previous = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
    }
    override fun after() { Locale.setDefault(previous) }
}
