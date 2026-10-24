package com.clarklevis.dsh.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// Android 模块未启用 serialization 编译插件，桥接响应通过共享 JSON 协议显式解析。
enum class CacheStorageCategory { CHATS, IMAGES, PREVIEWS, TEMPORARY, UPDATES }

data class AndroidStorageUsage(
    val chatBytes: Long,
    val imageBytes: Long,
    val previewBytes: Long,
    val temporaryBytes: Long,
    val updateBytes: Long,
    val protectedBytes: Long,
    val freeBytes: Long
) {
    val totalBytes: Long get() = chatBytes + imageBytes + previewBytes + temporaryBytes + updateBytes

    fun bytes(category: CacheStorageCategory): Long = when (category) {
        CacheStorageCategory.CHATS -> chatBytes
        CacheStorageCategory.IMAGES -> imageBytes
        CacheStorageCategory.PREVIEWS -> previewBytes
        CacheStorageCategory.TEMPORARY -> temporaryBytes
        CacheStorageCategory.UPDATES -> updateBytes
    }
}

data class AndroidLocalChatMatch(val sessionId: String, val seq: Int, val time: Double, val snippet: String)

internal data class AndroidLocalChatMatches(val hasMore: Boolean = false, val results: List<AndroidLocalChatMatch> = emptyList()) {
    companion object {
        fun decode(payload: String): AndroidLocalChatMatches {
            val root = Json.parseToJsonElement(payload).jsonObject
            val matches = root["results"]?.jsonArray.orEmpty().mapNotNull { element ->
                val value = element.jsonObject
                val id = value["sessionId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val seq = value["seq"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 } ?: return@mapNotNull null
                val time = value["time"]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() } ?: return@mapNotNull null
                val snippet = value["snippet"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                AndroidLocalChatMatch(id, seq, time, snippet)
            }
            return AndroidLocalChatMatches(root["hasMore"]?.jsonPrimitive?.content == "true", matches)
        }
    }
}

internal data class AndroidChatCacheMemoryStatistics(
    val memoryBytes: Long,
    val memorySessions: Int,
    val memoryHits: Long,
    val diskHits: Long,
    val misses: Long
) {
    companion object {
        fun decode(payload: String): AndroidChatCacheMemoryStatistics {
            val root = Json.parseToJsonElement(payload).jsonObject
            fun number(name: String): Long = root[name]?.jsonPrimitive?.longOrNull ?: 0
            return AndroidChatCacheMemoryStatistics(
                number("memoryBytes"),
                number("memorySessions").toInt(),
                number("memoryHits"),
                number("diskHits"),
                number("misses")
            )
        }
    }
}
