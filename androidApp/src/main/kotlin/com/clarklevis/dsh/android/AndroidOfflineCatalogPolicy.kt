package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.protocol.GatewayFrame

/** 完整目录仍由 shared 严格验证；这里仅决定哪些已应用的响应可能改变导航元数据。 */
internal fun GatewayFrame.hasOfflineCatalogMutation(): Boolean = when (kind) {
    "sessions", "workspaces" -> items != null
    "session-archives", "session-archived" -> archivedSessionIds != null
    "session-title-changed", "session-renamed" -> !sessionId.isNullOrBlank() && !title.isNullOrBlank() && seq != null
    "session-created", "sent", "session-snapshot" -> !sessionId.isNullOrBlank()
    "workspace-create" -> workspace != null
    "event" -> event != null
    else -> false
}
