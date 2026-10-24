package com.clarklevis.dsh.shared.cache

/**
 * JVM 测试后端的日志实现。
 *
 * 上游只提供了 Android/iOS 的 actual（他们不跑 JVM 目标），本 fork 的 `:shared:jvmTest`
 * 需要 jvmMain actual，否则 commonTest 编译失败。只打印到标准输出/错误，不落盘、不含正文。
 */
internal actual fun writeChatCacheLog(message: String, warning: Boolean) {
    if (warning) System.err.println(message) else println(message)
}
