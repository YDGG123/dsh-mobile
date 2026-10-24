package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.protocol.GatewayWorkspace
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** 离线目录只包含导航元数据；正文、游标、订阅和流状态始终由各自 Store 管理。 */
@Serializable
internal data class SharedOfflineCatalog(
    val version: Int = 1,
    val sessionList: SharedSessionListSnapshot,
    val workspaces: List<GatewayWorkspace> = emptyList(),
    val selectedWorkspaceId: String? = null,
    val conversationTargets: List<ConversationTarget> = emptyList(),
    val teamAvatars: List<TeamAvatarAssignment> = emptyList()
)

/** Swift 与 Android 共用的有界目录 JSON 契约；错误输入不越过平台桥接边界。 */
class SharedOfflineCatalogCodec {
    fun create(sessionListJson: String, workspacesJson: String, selectedWorkspaceId: String?): String? = runCatching {
        require(sessionListJson.length <= MAXIMUM_BYTES && workspacesJson.length <= MAXIMUM_BYTES)
        encode(
            SharedOfflineCatalog(
                sessionList = wireJson.decodeFromString(sessionListJson),
                workspaces = wireJson.decodeFromString(workspacesJson),
                selectedWorkspaceId = selectedWorkspaceId
            )
        )
    }.getOrNull()

    fun withConversationTargets(catalogJson: String, targetsJson: String): String? = runCatching {
        require(targetsJson.length <= MAXIMUM_BYTES)
        val targets = wireJson.decodeFromString<List<ConversationTarget>>(targetsJson)
        require(validConversationTargets(targets))
        val catalog = decode(catalogJson)
        val parents = targets.associate { it.sessionId to it.parentSessionId }
        var selected = catalog.sessionList.selectedSessionId
        val visited = mutableSetOf<String>()
        while (selected != null && visited.add(selected)) {
            selected = parents[selected] ?: break
        }
        encode(catalog.copy(
            sessionList = catalog.sessionList.copy(selectedSessionId = selected),
            conversationTargets = targets
        ))
    }.getOrNull()

    fun conversationTargetsJson(catalogJson: String): String? = runCatching {
        wireJson.encodeToString(decode(catalogJson).conversationTargets)
    }.getOrNull()

    fun withTeamAvatars(catalogJson: String, avatarsJson: String): String? = runCatching {
        require(avatarsJson.length <= MAXIMUM_BYTES)
        val avatars = wireJson.decodeFromString<List<TeamAvatarAssignment>>(avatarsJson)
        require(validTeamAvatarAssignments(avatars))
        encode(decode(catalogJson).copy(teamAvatars = avatars))
    }.getOrNull()

    fun teamAvatarsJson(catalogJson: String): String? = runCatching {
        wireJson.encodeToString(decode(catalogJson).teamAvatars)
    }.getOrNull()

    fun normalize(catalogJson: String): String? = runCatching { encode(decode(catalogJson)) }.getOrNull()
    fun sessionListJson(catalogJson: String): String? = runCatching { wireJson.encodeToString(decode(catalogJson).sessionList) }.getOrNull()
    fun workspacesJson(catalogJson: String): String? = runCatching { wireJson.encodeToString(decode(catalogJson).workspaces) }.getOrNull()
    fun selectedWorkspaceId(catalogJson: String): String? = runCatching { decode(catalogJson).selectedWorkspaceId }.getOrNull()

    internal fun decode(json: String): SharedOfflineCatalog {
        require(json.length <= MAXIMUM_BYTES && json.encodeToByteArray().size <= MAXIMUM_BYTES)
        return wireJson.decodeFromString<SharedOfflineCatalog>(json).also(::validate)
    }

    internal fun encode(catalog: SharedOfflineCatalog): String {
        validate(catalog)
        return wireJson.encodeToString(catalog).also { require(it.encodeToByteArray().size <= MAXIMUM_BYTES) }
    }

    private fun validate(catalog: SharedOfflineCatalog) {
        require(catalog.version == 1)
        require(validConversationTargets(catalog.conversationTargets))
        require(validTeamAvatarAssignments(catalog.teamAvatars))
        val list = catalog.sessionList
        require(list.sessions.size <= MAXIMUM_SESSIONS && list.archivedSessionIds.size <= MAXIMUM_SESSIONS)
        require(catalog.workspaces.size <= MAXIMUM_WORKSPACES)
        require(list.sessions.map { it.id }.toSet().size == list.sessions.size)
        require(catalog.workspaces.map { it.workspaceId }.toSet().size == catalog.workspaces.size)
        require(
            list.sessions.all {
                validId(it.id) &&
                    it.title.length <= MAXIMUM_TEXT &&
                    it.lastActivityEpochSeconds.isFinite() &&
                    (it.agentPreset == null || it.agentPreset.length <= MAXIMUM_TEXT)
            }
        )
        require(list.archivedSessionIds.all(::validId))
        require(list.selectedSessionId == null || validId(list.selectedSessionId))
        require(catalog.selectedWorkspaceId == null || validId(catalog.selectedWorkspaceId))
        require(
            catalog.workspaces.all {
                validId(it.workspaceId) &&
                    it.path.length <= MAXIMUM_TEXT &&
                    it.title.length <= MAXIMUM_TEXT &&
                    it.createdAt.length <= MAXIMUM_TEXT &&
                    it.updatedAt.length <= MAXIMUM_TEXT &&
                    it.sessionIds.size <= MAXIMUM_SESSIONS &&
                    it.sessionIds.all(::validId)
            }
        )
    }

    private fun validId(value: String): Boolean = value.isNotBlank() && value.length <= MAXIMUM_IDENTIFIER

    internal companion object {
        const val MAXIMUM_BYTES = 1024 * 1024
        const val MAXIMUM_SESSIONS = 4096
        const val MAXIMUM_WORKSPACES = 512
        const val MAXIMUM_TEXT = 8192
        const val MAXIMUM_IDENTIFIER = 512
    }
}

internal fun validTeamAvatarAssignments(avatars: List<TeamAvatarAssignment>): Boolean =
    avatars.size <= SharedOfflineCatalogCodec.MAXIMUM_SESSIONS &&
        avatars.map { it.leadSessionId to it.sessionId }.toSet().size == avatars.size &&
        avatars.all {
            it.index in 0 until 144 && it.leadSessionId.isNotBlank() && it.sessionId.isNotBlank() &&
                it.leadSessionId.length <= SharedOfflineCatalogCodec.MAXIMUM_IDENTIFIER &&
                it.sessionId.length <= SharedOfflineCatalogCodec.MAXIMUM_IDENTIFIER
        }

/** 缓存地址必须是有界、无环的直接父子关系；普通 fork 不参与。 */
internal fun validConversationTargets(targets: List<ConversationTarget>): Boolean {
    if (targets.size > SharedOfflineCatalogCodec.MAXIMUM_SESSIONS ||
        targets.map { it.sessionId }.toSet().size != targets.size) return false
    val parents = targets.associate { it.sessionId to it.parentSessionId }
    return targets.all { target ->
        val parent = target.parentSessionId
        if (target.sessionId.isBlank() || target.sessionId.length > SharedOfflineCatalogCodec.MAXIMUM_IDENTIFIER ||
            parent.isNullOrBlank() || parent.length > SharedOfflineCatalogCodec.MAXIMUM_IDENTIFIER ||
            target.subagentMode !in setOf("continuable", "one-shot", "unknown", "external")) return@all false
        val visited = mutableSetOf<String>()
        var id: String? = target.sessionId
        while (id != null) {
            if (!visited.add(id)) return@all false
            id = parents[id]
        }
        true
    }
}
