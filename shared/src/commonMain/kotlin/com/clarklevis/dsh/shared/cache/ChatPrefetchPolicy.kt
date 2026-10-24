package com.clarklevis.dsh.shared.cache

import kotlin.math.pow
import kotlinx.serialization.Serializable

@Serializable
internal data class ChatPrefetchCandidate(
    val id: String,
    val lastActivityEpochSeconds: Double = 0.0,
    val isRunning: Boolean = false,
    val hasUnread: Boolean = false,
    val hasConversation: Boolean = true
)

@Serializable
internal data class ChatPrefetchTicket(val gatewayId: String, val sessionId: String, val epoch: Long, val revision: Long)

/** 热度只来自真实打开；后台读写不增加热度。频率和最近性均随时间衰减。 */
internal object ChatPrefetchPolicy {
    const val MAXIMUM_SESSIONS = 6
    const val MAXIMUM_HEAT_RECORDS = 4096
    const val MAXIMUM_IDENTIFIER_LENGTH = 1024
    const val MAXIMUM_OPEN_WEIGHT = 64.0
    const val MAXIMUM_RESPONSE_BYTES = 256L * 1024
    const val MAXIMUM_RESPONSE_EVENTS = 80
    private const val HALF_LIFE_SECONDS = 7.0 * 24 * 60 * 60
    private const val FREQUENCY_WEIGHT = 4.0
    private const val RECENCY_WEIGHT = 8.0
    private const val ACTIVITY_WEIGHT = 2.0
    private const val RUNNING_WEIGHT = 3.0
    private const val UNREAD_WEIGHT = 2.0

    fun decay(value: Double, age: Double): Double = value * 0.5.pow(age.coerceAtLeast(0.0) / HALF_LIFE_SECONDS)

    fun rank(
        candidates: List<ChatPrefetchCandidate>,
        heat: List<SessionHeatEntity>,
        selectedSessionId: String?,
        now: Double
    ): List<String> {
        val records = heat.associateBy { it.sessionId }
        return candidates.asSequence()
            .filter { it.hasConversation && validIdentifier(it.id) && it.id != selectedSessionId && it.lastActivityEpochSeconds.isFinite() }
            .distinctBy { it.id }
            .map { candidate ->
                val record = records[candidate.id]
                val userHeat = record?.let {
                    FREQUENCY_WEIGHT * decay(it.decayedOpens, now - it.lastOpenedAt) +
                        RECENCY_WEIGHT * decay(1.0, now - it.lastOpenedAt)
                } ?: 0.0
                val activity = if (candidate.lastActivityEpochSeconds > 0) {
                    ACTIVITY_WEIGHT * decay(1.0, now - candidate.lastActivityEpochSeconds)
                } else {
                    0.0
                }
                val running = if (candidate.isRunning) RUNNING_WEIGHT else 0.0
                val unread = if (candidate.hasUnread) UNREAD_WEIGHT else 0.0
                candidate to (userHeat + activity + running + unread)
            }
            .sortedWith(compareByDescending<Pair<ChatPrefetchCandidate, Double>> { it.second }.thenBy { it.first.id })
            .take(MAXIMUM_SESSIONS)
            .map { it.first.id }
            .toList()
    }

    fun validIdentifier(value: String): Boolean = value.isNotBlank() && value.length <= MAXIMUM_IDENTIFIER_LENGTH
}
