package com.clarklevis.dsh.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vc60 P1 判据：平台错误弹窗不得把原始码抛给用户（真机实收「search：gateway-request-failed」，
 * 用户 2026-11-03 深夜截图）。钳点 = humanizePlatformError（显示层）。
 */
class BotPlatformErrorCopyTest {

    /** 用户实收的原始串必须映射为可行动人话（含全角/半角冒号两种形态）。 */
    @Test
    fun userReportedRawCodeBecomesActionableCopy() {
        val humanized = humanizePlatformError("search：gateway-request-failed")
        assertTrue("必须映射为人话", humanized.contains("搜索暂时不可用"))
        assertTrue("原始码不得残留", !humanized.contains("gateway-request-failed"))
        assertEquals(
            "半角冒号形态必须走同一文案",
            humanized,
            humanizePlatformError("search: gateway-request-failed"),
        )
    }

    /** 其他请求类型的同族失败走通用文案，也不残留原始码。 */
    @Test
    fun otherRequestTypesGetGenericCopy() {
        val humanized = humanizePlatformError("history：transport-failed")
        assertTrue("必须映射为通用人话", humanized.contains("操作暂时没有完成"))
        assertTrue("原始码不得残留", !humanized.contains("transport-failed"))
    }

    /** 反向 1：裸码（无请求类型前缀）也要被接住。 */
    @Test
    fun bareCodeIsCaught() {
        assertTrue("裸码不得残留", !humanizePlatformError("gateway-request-failed").contains("gateway-request-failed"))
    }

    /** 反向 2：凭证类与未知文案原样通过（PAIR 派生依赖原文，显示层不得误伤）。 */
    @Test
    fun credentialAndUnknownCopyPassThrough() {
        val credential = "网关凭证已失效（系统密钥不可用），请重新扫码配对"
        assertEquals("凭证文案必须原样通过", credential, humanizePlatformError(credential))
        assertEquals(
            "未知文案必须原样通过",
            "本地搜索结果已失效，请重新搜索。",
            humanizePlatformError("本地搜索结果已失效，请重新搜索。"),
        )
    }
}
