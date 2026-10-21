package com.clarklevis.dsh.shared.domain

import com.clarklevis.dsh.shared.protocol.BridgeServerInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 能力门控（fail-closed，镜像 apps/mobile/src/logic/mobile-capabilities.ts 语义）：
 * - `/info.capabilities.actions` 数组存在 ⇒ 只有列出的 endpoint 允许调用；
 * - `{feature}: false` 一票否决；信息缺失一律视为不支持。
 */
@Serializable
data class TeamCapabilities(
    /** null = 未知 ⇒ 全部动作 deny（fail-closed）。 */
    val actionsWhitelist: List<String>? = null,
    val features: Map<String, Boolean> = emptyMap()
) {
    val isKnown: Boolean get() = actionsWhitelist != null

    fun supportsAction(endpoint: String): Boolean = actionsWhitelist?.contains(endpoint) == true

    fun supportsFeature(name: String): Boolean = features[name] == true

    /** 面板仅当所需 capability 全组就绪才渲染；缺一即整体隐藏（§6 P4 语义，P1 起沿用）。 */
    fun mobilePanelAllowed(vararg requiredFeatures: String): Boolean =
        requiredFeatures.all { supportsFeature(it) }

    companion object {
        val Denied = TeamCapabilities(actionsWhitelist = null, features = emptyMap())

        fun fromInfo(info: BridgeServerInfo?): TeamCapabilities {
            if (info == null) return Denied
            val whitelist: List<String>? = info.capabilities["actions"]?.let { element ->
                (element as? JsonArray)?.map { item -> item.jsonPrimitive.content }
            }
            val features = LinkedHashMap<String, Boolean>()
            for ((key, value) in info.capabilities) {
                if (key == "actions") continue
                val primitive = value as? JsonPrimitive ?: continue
                val flag = primitive.booleanOrNull
                if (flag != null) features[key] = flag
            }
            return TeamCapabilities(actionsWhitelist = whitelist, features = features)
        }
    }
}
