package com.clarklevis.dsh.android

/**
 * Debug 构建：放行「明文 http + loopback」的桥接 origin（模拟器 10.0.2.2 / 本机 127.*）。
 * 仅 debug 源集为 true；release 源集恒 false，生产语义与 iOS https-only 对齐。
 */
internal const val BRIDGE_ALLOW_INSECURE_LOOPBACK: Boolean = true
