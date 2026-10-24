package com.clarklevis.dsh.shared.cache

import androidx.room.ColumnInfo
import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Entity(
    tableName = "chat_session_cache",
    primaryKeys = ["gatewayId", "sessionId"],
    indices = [Index("lastAccessAt")]
)
internal data class CachedSessionEntity(
    val gatewayId: String,
    val sessionId: String,
    val headerJson: String,
    val logicalBytes: Long,
    val lastAccessAt: Long,
    @ColumnInfo(defaultValue = "0") val searchVersion: Int = 1
)

@Entity(
    tableName = "chat_cached_event",
    primaryKeys = ["gatewayId", "sessionId", "seq"],
    foreignKeys = [
        ForeignKey(
            entity = CachedSessionEntity::class,
            parentColumns = ["gatewayId", "sessionId"],
            childColumns = ["gatewayId", "sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
internal data class CachedEventEntity(val gatewayId: String, val sessionId: String, val seq: Int, val eventJson: String)

@Entity(
    tableName = "chat_search_text",
    primaryKeys = ["gatewayId", "sessionId", "seq"],
    indices = [Index("gatewayId", "gramIndexed")],
    foreignKeys = [
        ForeignKey(
            entity = CachedEventEntity::class,
            parentColumns = ["gatewayId", "sessionId", "seq"],
            childColumns = ["gatewayId", "sessionId", "seq"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
internal data class CachedSearchText(
    val gatewayId: String,
    val sessionId: String,
    val seq: Int,
    val time: Double,
    val text: String,
    val normalizedText: String,
    val gramIndexed: Boolean = true
)

// 首三字符定位候选，再用完整字面量校验；中文和 emoji 无需依赖平台分词器。
@Entity(
    tableName = "chat_search_gram",
    primaryKeys = ["gatewayId", "gram", "sessionId", "seq"],
    indices = [Index("gatewayId", "sessionId", "seq")],
    foreignKeys = [
        ForeignKey(
            entity = CachedSearchText::class,
            parentColumns = ["gatewayId", "sessionId", "seq"],
            childColumns = ["gatewayId", "sessionId", "seq"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
internal data class CachedSearchGram(val gatewayId: String, val gram: String, val sessionId: String, val seq: Int)

/** 用户实际进入会话的热度，与正文 LRU 生命周期独立。 */
@Entity(tableName = "chat_session_heat", primaryKeys = ["gatewayId", "sessionId"], indices = [Index("lastOpenedAt")])
internal data class SessionHeatEntity(val gatewayId: String, val sessionId: String, val lastOpenedAt: Double, val decayedOpens: Double)

/** 与正文 LRU 独立的有界导航目录，绝不包含聊天事件。 */
@Entity(tableName = "chat_offline_catalog", primaryKeys = ["gatewayId"], indices = [Index("lastAccessAt")])
internal data class OfflineCatalogEntity(val gatewayId: String, val catalogJson: String, val logicalBytes: Long, val lastAccessAt: Long)

private const val MAXIMUM_OFFLINE_CATALOGS = 64
private const val MAXIMUM_OFFLINE_CATALOG_BYTES = 8L * 1024 * 1024

internal class CacheCapacityExceeded : Exception()
internal data class CachedWindowRows(val header: CachedSessionEntity, val events: List<CachedEventEntity>)
internal data class CacheWindowWrite(
    val header: CachedSessionEntity,
    val events: List<CachedEventEntity>,
    val texts: List<CachedSearchText>,
    val grams: List<CachedSearchGram>
)
internal data class CacheWriteResult(val totalBytes: Long, val evictedSessions: Int, val evictedBytes: Long)

// Room 将相关表的查询与事务集中到单个 DAO，保留事务边界便于审计。
@Suppress("TooManyFunctions")
@Dao
internal abstract class ChatCacheDao {
    @Query("SELECT * FROM chat_offline_catalog WHERE gatewayId=:gatewayId")
    abstract suspend fun offlineCatalog(gatewayId: String): OfflineCatalogEntity?

    @Query("UPDATE chat_offline_catalog SET lastAccessAt=MAX(lastAccessAt,:time) WHERE gatewayId=:gatewayId")
    abstract suspend fun touchCatalog(gatewayId: String, time: Long)

    @Query("DELETE FROM chat_offline_catalog WHERE gatewayId=:gatewayId")
    abstract suspend fun removeCatalog(gatewayId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putCatalog(catalog: OfflineCatalogEntity)

    @Query("SELECT COALESCE(MAX(lastAccessAt),0) FROM chat_offline_catalog")
    abstract suspend fun latestCatalogAccess(): Long

    @Query("SELECT * FROM chat_offline_catalog ORDER BY lastAccessAt,gatewayId")
    abstract suspend fun catalogs(): List<OfflineCatalogEntity>

    @Transaction
    open suspend fun replaceCatalog(catalog: OfflineCatalogEntity) {
        putCatalog(catalog)
        val entries = catalogs()
        var count = entries.size
        var bytes = entries.sumOf { it.logicalBytes }
        for (entry in entries.filterNot { it.gatewayId == catalog.gatewayId }) {
            if (count <= MAXIMUM_OFFLINE_CATALOGS && bytes <= MAXIMUM_OFFLINE_CATALOG_BYTES) break
            removeCatalog(entry.gatewayId)
            count -= 1
            bytes -= entry.logicalBytes
        }
    }

    @Query("SELECT * FROM chat_session_heat WHERE gatewayId=:gatewayId")
    abstract suspend fun sessionHeat(gatewayId: String): List<SessionHeatEntity>

    @Query("SELECT * FROM chat_session_heat WHERE gatewayId=:gatewayId AND sessionId=:sessionId")
    abstract suspend fun heat(gatewayId: String, sessionId: String): SessionHeatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putHeat(heat: SessionHeatEntity)

    @Query("DELETE FROM chat_session_heat WHERE :gatewayId IS NULL OR gatewayId=:gatewayId")
    abstract suspend fun clearHeat(gatewayId: String?)

    @Query("DELETE FROM chat_session_heat WHERE gatewayId=:gatewayId AND sessionId=:sessionId")
    abstract suspend fun removeHeat(gatewayId: String, sessionId: String)

    @Query(
        "DELETE FROM chat_session_heat WHERE (gatewayId, sessionId) IN (SELECT gatewayId, sessionId FROM chat_session_heat ORDER BY lastOpenedAt DESC,gatewayId,sessionId LIMIT -1 OFFSET :maximum)"
    )
    abstract suspend fun boundHeat(maximum: Int)

    @Transaction
    open suspend fun recordOpen(gatewayId: String, sessionId: String, now: Double) {
        val previous = heat(gatewayId, sessionId)
        val time = maxOf(now, previous?.lastOpenedAt ?: now)
        val count = previous?.let { ChatPrefetchPolicy.decay(it.decayedOpens, time - it.lastOpenedAt) } ?: 0.0
        putHeat(SessionHeatEntity(gatewayId, sessionId, time, (count + 1.0).coerceAtMost(ChatPrefetchPolicy.MAXIMUM_OPEN_WEIGHT)))
        boundHeat(ChatPrefetchPolicy.MAXIMUM_HEAT_RECORDS)
    }

    @Query("SELECT * FROM chat_session_cache WHERE gatewayId=:gatewayId AND sessionId=:sessionId")
    abstract suspend fun session(gatewayId: String, sessionId: String): CachedSessionEntity?

    @Query("SELECT * FROM chat_cached_event WHERE gatewayId=:gatewayId AND sessionId=:sessionId ORDER BY seq")
    abstract suspend fun events(gatewayId: String, sessionId: String): List<CachedEventEntity>

    @Query("SELECT * FROM chat_session_cache ORDER BY lastAccessAt,gatewayId,sessionId")
    abstract suspend fun evictionCandidates(): List<CachedSessionEntity>

    @Query("SELECT COALESCE(SUM(logicalBytes),0) FROM chat_session_cache")
    abstract suspend fun totalBytes(): Long

    @Query("SELECT COALESCE(MAX(lastAccessAt),0) FROM chat_session_cache")
    abstract suspend fun latestAccess(): Long

    @Query("SELECT COUNT(*) FROM chat_session_cache")
    abstract suspend fun sessionCount(): Long

    @Query("SELECT COUNT(*) FROM chat_cached_event")
    abstract suspend fun eventCount(): Long

    @Query("DELETE FROM chat_session_cache WHERE :gatewayId IS NULL OR gatewayId=:gatewayId")
    abstract suspend fun clear(gatewayId: String?)

    @Transaction
    open suspend fun clearCacheAndHeat(gatewayId: String?) {
        clear(gatewayId)
        clearHeat(gatewayId)
    }

    @Transaction
    open suspend fun removeCacheAndHeat(gatewayId: String, sessionId: String) {
        remove(gatewayId, sessionId)
        removeHeat(gatewayId, sessionId)
    }

    @Query("SELECT * FROM chat_session_cache WHERE searchVersion=0")
    abstract suspend fun unindexedSessions(): List<CachedSessionEntity>

    @Query("UPDATE chat_session_cache SET logicalBytes=:bytes, searchVersion=1 WHERE gatewayId=:gatewayId AND sessionId=:sessionId")
    abstract suspend fun markIndexed(gatewayId: String, sessionId: String, bytes: Long)

    @Query(
        """
                SELECT *
                FROM chat_cached_event
                WHERE gatewayId=:gatewayId
                AND sessionId=:sessionId
                AND (:beforeSequence IS NULL OR seq<:beforeSequence)
                ORDER BY seq DESC
                LIMIT :limit
            """
    )
    abstract suspend fun eventPage(gatewayId: String, sessionId: String, beforeSequence: Int?, limit: Int): List<CachedEventEntity>

    @Query(
        """
                SELECT *
                FROM chat_search_text
                WHERE gatewayId=:gatewayId
                AND (:sessionId IS NULL OR sessionId=:sessionId)
                AND instr(normalizedText,:query)>0
                ORDER BY time DESC, sessionId, seq DESC
                LIMIT :limit
            """
    )
    abstract suspend fun searchShort(gatewayId: String, sessionId: String?, query: String, limit: Int): List<CachedSearchText>

    @Query(
        """
                SELECT t.*
                FROM chat_search_gram g
                JOIN chat_search_text t ON t.gatewayId=g.gatewayId
                AND t.sessionId=g.sessionId
                AND t.seq=g.seq
                WHERE g.gatewayId=:gatewayId
                AND g.gram=:gram
                AND (:sessionId IS NULL OR g.sessionId=:sessionId)
                AND instr(t.normalizedText,:query)>0
                UNION ALL SELECT *
                FROM chat_search_text
                WHERE gatewayId=:gatewayId
                AND gramIndexed=0
                AND (:sessionId IS NULL OR sessionId=:sessionId)
                AND instr(normalizedText,:query)>0
                ORDER BY time DESC,sessionId,seq DESC
                LIMIT :limit
            """
    )
    abstract suspend fun searchLong(gatewayId: String, sessionId: String?, gram: String, query: String, limit: Int): List<CachedSearchText>

    @Query("DELETE FROM chat_session_cache WHERE gatewayId=:gatewayId AND sessionId=:sessionId")
    abstract suspend fun remove(gatewayId: String, sessionId: String)

    @Query("UPDATE chat_session_cache SET lastAccessAt=MAX(lastAccessAt,:time) WHERE gatewayId=:gatewayId AND sessionId=:sessionId")
    abstract suspend fun touch(gatewayId: String, sessionId: String, time: Long)

    @Insert
    abstract suspend fun insertSession(session: CachedSessionEntity)

    @Insert
    abstract suspend fun insertEvents(events: List<CachedEventEntity>)

    @Insert
    abstract suspend fun insertTexts(texts: List<CachedSearchText>)

    @Insert
    abstract suspend fun insertGrams(grams: List<CachedSearchGram>)

    @Transaction
    open suspend fun load(gatewayId: String, sessionId: String, time: Long): CachedWindowRows? {
        val header = session(gatewayId, sessionId) ?: return null
        val events = events(gatewayId, sessionId)
        touch(gatewayId, sessionId, time)
        return CachedWindowRows(header, events)
    }

    @Transaction
    open suspend fun loadPage(gatewayId: String, sessionId: String, beforeSequence: Int?, limit: Int): CachedWindowRows? {
        val header = session(gatewayId, sessionId) ?: return null
        return CachedWindowRows(header, eventPage(gatewayId, sessionId, beforeSequence, limit))
    }

    // 淘汰与窗口替换处于同一事务；容量不足或写入失败时保留旧窗口。
    @Transaction
    open suspend fun replace(
        write: CacheWindowWrite,
        protected: Map<String, Set<String>>,
        maximumBytes: Long,
        targetBytes: Long
    ): CacheWriteResult {
        val header = write.header
        val events = write.events
        val texts = write.texts
        val grams = write.grams
        val old = session(header.gatewayId, header.sessionId)?.logicalBytes ?: 0
        var projected = totalBytes() - old + header.logicalBytes
        var evictedSessions = 0
        var evictedBytes = 0L
        if (projected > maximumBytes) {
            val candidates = evictionCandidates().filter { victim ->
                (victim.gatewayId != header.gatewayId || victim.sessionId != header.sessionId) &&
                    victim.sessionId !in protected[victim.gatewayId].orEmpty()
            }
            for (victim in candidates) {
                if (projected <= targetBytes) break
                remove(victim.gatewayId, victim.sessionId)
                projected -= victim.logicalBytes
                evictedSessions += 1
                evictedBytes += victim.logicalBytes
            }
            if (projected > maximumBytes) throw CacheCapacityExceeded()
        }
        remove(header.gatewayId, header.sessionId)
        insertSession(header)
        insertEvents(events)
        insertTexts(texts)
        insertGrams(grams)
        return CacheWriteResult(projected, evictedSessions, evictedBytes)
    }

    @Transaction
    open suspend fun indexLegacy(
        header: CachedSessionEntity,
        texts: List<CachedSearchText>,
        grams: List<CachedSearchGram>,
        indexBytes: Long,
        maximumBytes: Long
    ) {
        // 迁移不丢已有正文；索引空间不足时由调用方使用兼容查询，待下次正常写入时重建。
        if (totalBytes() + indexBytes > maximumBytes) throw CacheCapacityExceeded()
        insertTexts(texts)
        insertGrams(grams)
        markIndexed(header.gatewayId, header.sessionId, header.logicalBytes + indexBytes)
    }

    @Transaction
    open suspend fun trim(targetBytes: Long, protected: Map<String, Set<String>>): Long {
        var bytes = totalBytes()
        var reclaimed = 0L
        val candidates = evictionCandidates().filter { it.sessionId !in protected[it.gatewayId].orEmpty() }
        for (candidate in candidates) {
            if (bytes <= targetBytes) break
            remove(candidate.gatewayId, candidate.sessionId)
            bytes -= candidate.logicalBytes
            reclaimed += candidate.logicalBytes
        }
        return reclaimed
    }
}

@Database(
    entities = [
        CachedSessionEntity::class,
        CachedEventEntity::class,
        CachedSearchText::class,
        CachedSearchGram::class,
        SessionHeatEntity::class,
        OfflineCatalogEntity::class
    ],
    version = 4
)
@ConstructedBy(ChatCacheDatabaseConstructor::class)
internal abstract class ChatCacheDatabase : RoomDatabase() {
    abstract fun cacheDao(): ChatCacheDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
internal expect object ChatCacheDatabaseConstructor : RoomDatabaseConstructor<ChatCacheDatabase> {
    override fun initialize(): ChatCacheDatabase
}

internal val CHAT_CACHE_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE chat_session_cache ADD COLUMN searchVersion INTEGER NOT NULL DEFAULT 0")
        connection.execSQL(
            """
                CREATE TABLE IF NOT EXISTS chat_search_text (gatewayId TEXT NOT NULL,
                sessionId TEXT NOT NULL,
                seq INTEGER NOT NULL,
                time REAL NOT NULL,
                text TEXT NOT NULL,
                normalizedText TEXT NOT NULL,
                gramIndexed INTEGER NOT NULL,
                PRIMARY KEY(gatewayId,sessionId,seq),
                FOREIGN KEY(gatewayId,sessionId,seq) REFERENCES chat_cached_event(gatewayId,sessionId,seq)
                ON UPDATE NO ACTION ON DELETE CASCADE)
            """
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS index_chat_search_text_gatewayId_gramIndexed ON chat_search_text(gatewayId,gramIndexed)"
        )
        connection.execSQL(
            """
                CREATE TABLE IF NOT EXISTS chat_search_gram (gatewayId TEXT NOT NULL,
                gram TEXT NOT NULL,
                sessionId TEXT NOT NULL,
                seq INTEGER NOT NULL,
                PRIMARY KEY(gatewayId,gram,sessionId,seq),
                FOREIGN KEY(gatewayId,sessionId,seq) REFERENCES chat_search_text(gatewayId,sessionId,seq)
                ON UPDATE NO ACTION ON DELETE CASCADE)
            """
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS index_chat_search_gram_gatewayId_sessionId_seq ON chat_search_gram(gatewayId,sessionId,seq)"
        )
    }
}

internal val CHAT_CACHE_MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS chat_session_heat (gatewayId TEXT NOT NULL,sessionId TEXT NOT NULL,lastOpenedAt REAL NOT NULL,decayedOpens REAL NOT NULL,PRIMARY KEY(gatewayId,sessionId))"
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS index_chat_session_heat_lastOpenedAt ON chat_session_heat(lastOpenedAt)")
    }
}

@Suppress("MagicNumber")
internal val CHAT_CACHE_MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
                CREATE TABLE IF NOT EXISTS chat_offline_catalog (gatewayId TEXT NOT NULL,
                catalogJson TEXT NOT NULL,logicalBytes INTEGER NOT NULL,lastAccessAt INTEGER NOT NULL,
                PRIMARY KEY(gatewayId))
            """
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS index_chat_offline_catalog_lastAccessAt ON chat_offline_catalog(lastAccessAt)")
    }
}
