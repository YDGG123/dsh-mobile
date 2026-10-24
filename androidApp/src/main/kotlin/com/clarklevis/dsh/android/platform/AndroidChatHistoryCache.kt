package com.clarklevis.dsh.android.platform

import android.content.Context
import com.clarklevis.dsh.shared.cache.AndroidChatHistoryCacheFactory
import com.clarklevis.dsh.shared.cache.SharedChatHistoryCache

/** 仅管理进程内共享实例，数据与淘汰策略由 KMP 实现。 */
internal object AndroidChatHistoryCache {
    @Volatile private var instance: SharedChatHistoryCache? = null

    fun shared(context: Context): SharedChatHistoryCache = instance ?: synchronized(this) {
        instance ?: AndroidChatHistoryCacheFactory.create(context).also { instance = it }
    }
}
