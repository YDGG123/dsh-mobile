package com.clarklevis.dsh.shared.cache

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.room.Room

object AndroidChatHistoryCacheFactory {
    fun create(context: Context): SharedChatHistoryCache {
        val path = context.getDatabasePath("chat-history-room.db").absolutePath
        return Room.databaseBuilder<ChatCacheDatabase>(context.applicationContext, path)
            .buildCache(SharedChatHistoryCache.DEFAULT_MAXIMUM_BYTES, SharedChatHistoryCache.DEFAULT_TARGET_BYTES) {
                runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrNull()
            }
    }
}

internal actual fun writeChatCacheLog(message: String, warning: Boolean) {
    if (warning) Log.w("ChatHistoryCache", message) else Log.i("ChatHistoryCache", message)
}
