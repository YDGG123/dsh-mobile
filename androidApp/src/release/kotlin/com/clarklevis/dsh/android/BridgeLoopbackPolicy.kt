package com.clarklevis.dsh.android

/**
 * Release 构建：桥接 origin 强制 https-only（对齐 iOS 生产两处调用点
 * `BridgeTransport.allowInsecureLoopback: false`）。明文 http 一律拒绝。
 */
internal const val BRIDGE_ALLOW_INSECURE_LOOPBACK: Boolean = false
