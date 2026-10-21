package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedMviEvent
import com.clarklevis.dsh.shared.facade.SharedMviEventObserver
import com.clarklevis.dsh.shared.facade.SharedTeamEffect
import com.clarklevis.dsh.shared.facade.SharedTeamStore
import com.clarklevis.dsh.shared.protocol.BridgeWireKnownCode
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** P2 写操作四件套（§6 P2 验收口径）：乐观更新、回执、自动纠偏、能力门控、设备管理。 */
class TeamWriteActionsTest {

    private fun infoJson(
        actions: String = "\"models\",\"efforts\",\"update-bot\",\"set-flags\",\"set-main\""
    ): String = """
        {"protocolVersion":1,"serverId":"computer-1","name":"DS Bot",
         "capabilities":{"actions":[$actions],"groups":true,"modelSelection":true}}
    """.trimIndent()

    private fun stateJson(revision: Long = 7, readOnly: String? = null): String {
        val readOnlyJson = readOnly?.let { "\"$it\"" } ?: "null"
        return """
        {"revision":$revision,"readOnly":$readOnlyJson,
         "bots":[
           {"id":"chief","kind":"bot","name":"Chief","role":"Main","sessionId":"s-chief",
            "model":"mock/model","effort":"low","pinned":true,"hidden":false},
           {"id":"coder","kind":"bot","name":"Coder","sessionId":"s-coder",
            "model":"mock/model","effort":"low","pinned":false,"hidden":false}],
         "rooms":[],
         "models":[{"ref":"mock/model","name":"Mock"}],
         "defaultModel":"mock/model","mainBotIds":["chief"],"mainBotId":"chief",
         "questions":{},"activity":{},"work":{"bots":{},"rooms":{},"approvals":[],"waiting":{}},
         "memory":true,"browser":false}
        """.trimIndent()
    }

    private fun conversationsJson(): String = """{"conversations":[]}"""

    private fun readyStore(
        actions: String = "\"models\",\"efforts\",\"update-bot\",\"set-flags\",\"set-main\"",
        readOnly: String? = null
    ): SharedTeamStore {
        val store = SharedTeamStore()
        val events = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(events::add))
        store.sessionPaired(fixtureSession())
        store.refreshTeam(infoJson(actions), stateJson(readOnly = readOnly), conversationsJson())
        return store
    }

    private fun fixtureSession(): String =
        """{"accessToken":"acc","refreshToken":"ref","deviceId":"dev-1","serverId":"computer-1",
            "serverUrl":"https://host.example","expiresAt":1730000000000}"""

    private fun effects(result: com.clarklevis.dsh.shared.facade.SharedTeamResult): List<SharedTeamEffect> =
        result.effectJson?.let { wireJson.decodeFromString<List<SharedTeamEffect>>(it) } ?: emptyList()

    private fun snapshot(store: SharedTeamStore) =
        wireJson.parseToJsonElement(store.snapshot().snapshotJson!!).jsonObject

    // ---- set-flags ----

    @Test
    fun setFlagsOptimisticallyUpdatesRowAndEmitsActionEffect() {
        val store = readyStore()

        val result = store.setFlags("chief", pinned = false, hidden = true)
        assertTrue(result.isSuccess)
        assertNotNull(result.snapshotJson, "乐观更新必须立即发布 snapshot")

        val snap = snapshot(store)
        val chief = (snap["bots"] as kotlinx.serialization.json.JsonArray).first {
            it.jsonObject["id"]!!.jsonPrimitive.content == "chief"
        }.jsonObject
        assertEquals("false", chief["pinned"]!!.jsonPrimitive.content)
        assertEquals("true", chief["hidden"]!!.jsonPrimitive.content)
        assertEquals(
            "set-flags",
            snap["pendingActions"]!!.jsonObject["chief"]!!.jsonObject["action"]!!.jsonPrimitive.content
        )

        val effect = effects(result).single()
        assertEquals(SharedTeamEffect.KIND_BRIDGE_HTTP, effect.kind)
        assertEquals("POST", effect.method)
        assertEquals("/actions", effect.path)
        assertEquals(true, effect.requiresAuth)
        val body = wireJson.parseToJsonElement(effect.bodyJson!!).jsonObject
        assertEquals("set-flags", body["endpoint"]!!.jsonPrimitive.content)
        val payload = body["payload"]!!.jsonObject
        assertEquals("chief", payload["id"]!!.jsonPrimitive.content)
        assertEquals("false", payload["pinned"]!!.jsonPrimitive.content)
        assertEquals("true", payload["hidden"]!!.jsonPrimitive.content)
    }

    @Test
    fun writeAppliedClearsPending() {
        val store = readyStore()
        val effectId = effects(store.setFlags("chief", true, null)).single().effectId!!

        val result = store.acceptBridgeResponse(effectId, 200, """{"id":"chief","pinned":true}""")
        assertTrue(result.isSuccess)
        val snap = snapshot(store)
        assertTrue(snap["pendingActions"]!!.jsonObject.isEmpty(), "成功回执必须清 pending")
        assertNull(snap["lastError"])
    }

    @Test
    fun targetChangedFailureTriggersAutoRefreshEffect() {
        val store = readyStore()
        val effectId = effects(store.setFlags("coder", true, null)).single().effectId!!

        val result = store.acceptBridgeResponse(
            effectId,
            409,
            """{"error":{"code":"TARGET_CHANGED","message":"目标已变化"}}"""
        )
        val snap = snapshot(store)
        assertTrue(snap["pendingActions"]!!.jsonObject.isEmpty())
        assertEquals(
            BridgeWireKnownCode.TARGET_CHANGED.name,
            snap["lastError"]!!.jsonObject["knownWireCode"]!!.jsonPrimitive.content
        )
        assertEquals(SharedTeamEffect.KIND_REFRESH_TEAM, effects(result).single().kind)
    }

    @Test
    fun readOnlyFailureTriggersAutoRefreshEffect() {
        val store = readyStore()
        val effectId = effects(store.setFlags("coder", true, null)).single().effectId!!

        val result = store.acceptBridgeResponse(
            effectId,
            409,
            """{"error":{"code":"READ_ONLY","message":"宿主只读"}}"""
        )
        assertEquals(SharedTeamEffect.KIND_REFRESH_TEAM, effects(result).single().kind)
    }

    @Test
    fun capabilityMissingBlocksWriteFailClosed() {
        val store = readyStore(actions = "\"models\"")
        val before = snapshot(store)

        val result = store.setFlags("chief", false, null)
        assertEquals(BridgeWireKnownCode.ACTION_NOT_ALLOWED.wireValue, result.errorCode)
        assertNull(result.effectJson, "能力缺失不得发 effect")
        assertNull(result.snapshotJson, "能力缺失不得改状态")
        assertEquals(before, snapshot(store))
    }

    @Test
    fun readOnlyTeamRejectsWrites() {
        val store = readyStore(readOnly = "宿主维护中")
        val result = store.setFlags("chief", false, null)
        assertEquals(BridgeWireKnownCode.READ_ONLY.wireValue, result.errorCode)
        assertNull(result.effectJson)
    }

    // ---- update-bot ----

    @Test
    fun updateBotSendsNullModelForDefaultAndUpdatesOptimistically() {
        val store = readyStore()
        val result = store.updateBot(
            id = "coder",
            includeModel = true,
            model = null,
            includeEffort = true,
            effort = "high"
        )
        assertTrue(result.isSuccess)

        val effect = effects(result).single()
        val body = wireJson.parseToJsonElement(effect.bodyJson!!).jsonObject
        assertEquals("update-bot", body["endpoint"]!!.jsonPrimitive.content)
        val payload = body["payload"]!!.jsonObject
        assertTrue(payload.containsKey("model"), "model:null 必须显式带上（表示使用默认模型）")
        assertEquals(JsonNull, payload["model"])
        assertEquals("high", payload["effort"]!!.jsonPrimitive.content)

        val snap = snapshot(store)
        val coder = (snap["bots"]!! as kotlinx.serialization.json.JsonArray).first {
            it.jsonObject["id"]!!.jsonPrimitive.content == "coder"
        }.jsonObject
        // 乐观更新：model=null（默认）、effort=high。
        assertTrue(!coder.containsKey("model") || coder["model"] == JsonNull)
        assertEquals("high", coder["effort"]!!.jsonPrimitive.content)

        val effectId = effect.effectId!!
        store.acceptBridgeResponse(effectId, 200, """{"id":"coder","changed":true}""")
        assertTrue(snapshot(store)["pendingActions"]!!.jsonObject.isEmpty())
    }

    // ---- set-main ----

    @Test
    fun setMainOptimisticallyMovesBotToFrontAndSendsPayload() {
        val store = readyStore()
        val result = store.setMain("coder")
        assertTrue(result.isSuccess)

        val snap = snapshot(store)
        assertEquals(
            "coder",
            (snap["mainBotIds"]!! as kotlinx.serialization.json.JsonArray).first().jsonPrimitive.content
        )
        val body = wireJson.parseToJsonElement(effects(result).single().bodyJson!!).jsonObject
        val payload = body["payload"]!!.jsonObject
        assertEquals("true", payload["main"]!!.jsonPrimitive.content)
        assertEquals("true", payload["primary"]!!.jsonPrimitive.content)
    }

    // ---- models / efforts ----

    @Test
    fun modelsAndEffortsLoadRoundTrip() {
        val store = readyStore()

        val modelsResult = store.loadModels()
        assertEquals("models", wireJson.parseToJsonElement(effects(modelsResult).single().bodyJson!!)
            .jsonObject["endpoint"]!!.jsonPrimitive.content)
        store.acceptBridgeResponse(
            effects(modelsResult).single().effectId!!,
            200,
            """{"models":[{"ref":"other/model","name":"Other","providerName":"Inc"}],
                "defaultModel":"other/model"}"""
        )
        val snap = snapshot(store)
        assertEquals("true", snap["modelsLoaded"]!!.jsonPrimitive.content)
        assertEquals(
            "other/model",
            (snap["models"]!! as kotlinx.serialization.json.JsonArray)
                .first().jsonObject["ref"]!!.jsonPrimitive.content
        )

        val effortsResult = store.loadEfforts("other/model")
        val effortsBody = wireJson.parseToJsonElement(effects(effortsResult).single().bodyJson!!).jsonObject
        assertEquals("efforts", effortsBody["endpoint"]!!.jsonPrimitive.content)
        assertEquals("other/model", effortsBody["payload"]!!.jsonObject["ref"]!!.jsonPrimitive.content)
        store.acceptBridgeResponse(
            effects(effortsResult).single().effectId!!,
            200,
            """{"efforts":[{"id":"low","name":"Low"},{"id":"high","name":"High"}],"default":"low"}"""
        )
        val after = snapshot(store)
        val efforts = after["effortsByModel"]!!.jsonObject["other/model"]!!.jsonObject
        assertEquals(2, (efforts["efforts"] as kotlinx.serialization.json.JsonArray).size)
        assertEquals("low", efforts["default"]!!.jsonPrimitive.content)
    }

    // ---- devices ----

    @Test
    fun devicesLoadAndRevokeRoundTrip() {
        val store = readyStore()

        val listResult = store.loadDevices()
        val listEffect = effects(listResult).single()
        assertEquals("GET", listEffect.method)
        assertEquals("/devices", listEffect.path)

        store.acceptBridgeResponse(
            listEffect.effectId!!,
            200,
            """{"devices":[
                 {"id":"dev-1","name":"我的 iPhone","createdAt":1730000000000,
                  "lastSeenAt":1730000001000,"revokedAt":null},
                 {"id":"dev-2","name":"旧手机","createdAt":1730000000000,
                  "lastSeenAt":1730000002000,"revokedAt":null}],
               "currentDeviceId":"dev-1"}"""
        )
        var snap = snapshot(store)
        assertEquals(2, (snap["devices"] as kotlinx.serialization.json.JsonArray).size)
        assertEquals("dev-1", snap["currentDeviceId"]!!.jsonPrimitive.content)
        assertEquals("false", snap["devicesLoading"]!!.jsonPrimitive.content)

        val revokeResult = store.revokeDevice("dev-2")
        val revokeEffect = effects(revokeResult).single()
        assertEquals("DELETE", revokeEffect.method)
        assertEquals("/devices/dev-2", revokeEffect.path)

        store.acceptBridgeResponse(revokeEffect.effectId!!, 200, """{"id":"dev-2","revoked":true}""")
        snap = snapshot(store)
        assertEquals(1, (snap["devices"] as kotlinx.serialization.json.JsonArray).size)
        assertTrue(snap["pendingActions"]!!.jsonObject.isEmpty())
    }

    @Test
    fun deviceRevokeFailureReloadsDevicesAutomatically() {
        val store = readyStore()
        val listResult = store.loadDevices()
        store.acceptBridgeResponse(
            effects(listResult).single().effectId!!,
            200,
            """{"devices":[{"id":"dev-1"},{"id":"dev-2"}],"currentDeviceId":"dev-1"}"""
        )

        val revokeResult = store.revokeDevice("dev-2")
        val result = store.acceptBridgeResponse(
            effects(revokeResult).single().effectId!!,
            404,
            """{"error":{"code":"DEVICE_NOT_FOUND","message":"设备不存在"}}"""
        )
        // 失败后自动重拉设备列表（bridge-http GET /devices）。
        val recovery = effects(result).single()
        assertEquals(SharedTeamEffect.KIND_BRIDGE_HTTP, recovery.kind)
        assertEquals("GET", recovery.method)
        assertEquals("/devices", recovery.path)
        assertEquals(
            BridgeWireKnownCode.DEVICE_NOT_FOUND.name,
            snapshot(store)["devicesLoadError"]!!.jsonObject["knownWireCode"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun tokenRefreshSingleFlightAtStoreLevel() {
        val store = SharedTeamStore()
        store.sessionPaired(fixtureSession())
        assertTrue(store.beginTokenRefresh())
        assertTrue(!store.beginTokenRefresh(), "single-flight：刷新进行中不得重复发起")
        store.tokenRefreshRejected("timeout")
        assertTrue(store.beginTokenRefresh(), "临时失败后允许再次发起")
    }
}
