package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.domain.BridgeSessionStore
import com.clarklevis.dsh.shared.domain.BridgeTokenMachine
import com.clarklevis.dsh.shared.domain.BridgeTokenOutcome
import com.clarklevis.dsh.shared.facade.SharedMviEvent
import com.clarklevis.dsh.shared.facade.SharedMviEventObserver
import com.clarklevis.dsh.shared.facade.SharedTeamEffect
import com.clarklevis.dsh.shared.facade.SharedBridgeTeamStore
import com.clarklevis.dsh.shared.protocol.BridgeClientKnownCode
import com.clarklevis.dsh.shared.protocol.BridgeWireKnownCode
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private class FakeSessionStore : BridgeSessionStore {
    var stored: String? = null
    var cleared = 0
    override fun loadJson(): String? = stored
    override fun saveJson(json: String) {
        stored = json
    }
    override fun clear() {
        stored = null
        cleared++
    }
}

private fun fixtureSessionJson(
    accessToken: String = "acc-1",
    serverId: String = "computer-1"
): String = """{"accessToken":"$accessToken","refreshToken":"ref-1","deviceId":"dev-1",
        "serverId":"$serverId","serverUrl":"https://host.example","expiresAt":1730000000000}"""

private fun fixtureInfoJson(actions: String = "\"models\",\"set-flags\""): String = """
    {"protocolVersion":1,"serverId":"computer-1","name":"DS Bot",
     "capabilities":{"actions":[$actions],"groups":true,"queue":false}}
""".trimIndent()

private fun fixtureStateJson(revision: Long = 7): String = """
    {"revision":$revision,"readOnly":null,
     "bots":[
       {"id":"chief","kind":"bot","name":"Chief","role":"Main","sessionId":"s-chief","pinned":true},
       {"id":"coder","kind":"bot","name":"Coder","sessionId":"s-coder"}],
     "rooms":[{"id":"room-1","kind":"room","name":"组","sessionId":"room-1","members":["chief"]}],
     "models":[{"ref":"mock/model","name":"Mock"}],
     "defaultModel":"mock/model","mainBotIds":["chief"],"mainBotId":"chief",
     "questions":{},"activity":{},"work":{"bots":{},"rooms":{},"approvals":[],"waiting":{}},
     "memory":true,"browser":false}
""".trimIndent()

private fun fixtureConversationsJson(): String =
    """{"conversations":[{"id":"chief","kind":"bot","name":"Chief","sessionId":"s-chief"}]}"""

class SharedBridgeTeamStoreTest {

    private fun eventList(store: SharedBridgeTeamStore): MutableList<SharedMviEvent> {
        val events = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(events::add))
        return events
    }

    private fun toReadyStore(store: SharedBridgeTeamStore, events: MutableList<SharedMviEvent>? = null): SharedBridgeTeamStore {
        val sink = events ?: eventList(store)
        store.sessionPaired(fixtureSessionJson())
        store.refreshTeam(fixtureInfoJson(), fixtureStateJson(), fixtureConversationsJson())
        assertEquals(3, sink.size, "snapshot + 配对 + 首刷应各产生一个事件")
        return store
    }

    @Test
    fun subscribePushesSnapshotThenTransitionsInOrder() {
        val store = SharedBridgeTeamStore()
        val events = eventList(store)
        assertEquals(1, events.size)
        assertEquals("snapshot", events.first().kind)
        assertEquals("team", events.first().domain)

        store.sessionPaired(fixtureSessionJson())
        store.refreshTeam(fixtureInfoJson(), fixtureStateJson(), fixtureConversationsJson())

        assertEquals(listOf(0L, 1L, 2L), events.map { it.sequence })
        val ready = events.last()
        assertEquals("transition", ready.kind)
        assertNotNull(ready.statePayloadJson)
        val snapshot = wireJson.parseToJsonElement(ready.statePayloadJson!!).jsonObject
        assertEquals("READY", snapshot["phase"]!!.jsonPrimitive.content)
        assertEquals(7L, snapshot["revision"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun staleRevisionRefreshDoesNotEmitAnyEvent() {
        val store = SharedBridgeTeamStore()
        val events = eventList(store)
        toReadyStore(store, events)

        val result = store.refreshTeam(
            fixtureInfoJson(),
            fixtureStateJson(revision = 6),
            fixtureConversationsJson()
        )
        assertNull(result.snapshotJson)
        assertNull(result.effectJson)
        assertTrue(result.isSuccess)
        assertEquals(3, events.size, "revision 回退不得产生新事件")
        assertEquals(7L, wireJson.parseToJsonElement(store.snapshot().snapshotJson!!)
            .jsonObject["revision"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun undecodableRefreshBodyGoesOfflineWithInvalidResponse() {
        val store = SharedBridgeTeamStore()
        val events = eventList(store)
        toReadyStore(store, events)

        val result = store.refreshTeam("not-json", fixtureStateJson(), fixtureConversationsJson())
        assertNotNull(result.snapshotJson)
        val snapshot = wireJson.parseToJsonElement(result.snapshotJson!!).jsonObject
        assertEquals("OFFLINE", snapshot["phase"]!!.jsonPrimitive.content)
        assertEquals("invalid_response", snapshot["lastError"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun openConversationEmitsEffectOnlyWhenReady() {
        val store = SharedBridgeTeamStore()
        val events = eventList(store)
        toReadyStore(store, events)

        val result = store.openTeamConversation("chief")
        assertNull(result.snapshotJson, "state 未变时不应发 snapshot")
        assertNotNull(result.effectJson)
        val effects = wireJson.decodeFromString<List<SharedTeamEffect>>(result.effectJson!!)
        assertEquals(1, effects.size)
        assertEquals(SharedTeamEffect.KIND_OPEN_CONVERSATION, effects.single().kind)
        assertEquals("s-chief", effects.single().sessionId)

        // 点未知目标：state 变化（lastError）+ 无 effect。
        val denied = store.openTeamConversation("ghost")
        assertNull(denied.effectJson)
        assertNotNull(denied.snapshotJson)
        val error = wireJson.parseToJsonElement(denied.snapshotJson!!).jsonObject["lastError"]!!.jsonObject
        assertEquals("TARGET_DENIED", error["code"]!!.jsonPrimitive.content)
        assertEquals("wire", error["source"]!!.jsonPrimitive.content)
    }

    @Test
    fun unauthorizedRefreshFailureClearsSessionAndEmitsClearEffect() {
        val store = SharedBridgeTeamStore()
        val events = eventList(store)
        toReadyStore(store, events)
        assertNotNull(store.currentAccessToken())

        val result = store.refreshFailed("UNAUTHORIZED", "token 失效")
        val snapshot = wireJson.parseToJsonElement(result.snapshotJson!!).jsonObject
        assertEquals("NEEDS_PAIRING", snapshot["phase"]!!.jsonPrimitive.content)
        val effects = wireJson.decodeFromString<List<SharedTeamEffect>>(result.effectJson!!)
        assertEquals(SharedTeamEffect.KIND_SESSION_CLEAR, effects.single().kind)
        assertNull(store.currentAccessToken(), "UNAUTHORIZED 后必须清空 token")
    }

    @Test
    fun networkFailureKeepsSessionAndData() {
        val store = SharedBridgeTeamStore()
        toReadyStore(store)

        val result = store.refreshFailed("timeout", "请求超时")
        val snapshot = wireJson.parseToJsonElement(result.snapshotJson!!).jsonObject
        assertEquals("OFFLINE", snapshot["phase"]!!.jsonPrimitive.content)
        assertEquals("timeout", snapshot["lastError"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertNull(result.effectJson, "非身份失效不清会话")
        assertNotNull(store.currentAccessToken())
    }

    @Test
    fun sessionLostEmitsClearEffectAndResetsPhase() {
        val store = SharedBridgeTeamStore()
        val events = eventList(store)
        toReadyStore(store, events)

        val result = store.sessionLost("server_changed", "服务器身份变化")
        val snapshot = wireJson.parseToJsonElement(result.snapshotJson!!).jsonObject
        assertEquals("NEEDS_PAIRING", snapshot["phase"]!!.jsonPrimitive.content)
        val effects = wireJson.decodeFromString<List<SharedTeamEffect>>(result.effectJson!!)
        assertEquals(SharedTeamEffect.KIND_SESSION_CLEAR, effects.single().kind)
        assertNull(store.currentAccessToken())
    }

    @Test
    fun identityChangeOnTokenRefreshKicksBackToPairing() {
        val store = SharedBridgeTeamStore()
        val events = eventList(store)
        toReadyStore(store, events)

        store.beginTokenRefresh()
        val result = store.tokenRefreshSucceeded(
            fixtureSessionJson(accessToken = "acc-2", serverId = "computer-OTHER")
        )
        val snapshot = wireJson.parseToJsonElement(result.snapshotJson!!).jsonObject
        assertEquals("NEEDS_PAIRING", snapshot["phase"]!!.jsonPrimitive.content)
        assertEquals("server_changed", snapshot["lastError"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertNull(store.currentAccessToken())
    }
}

class BridgeTokenMachineTest {

    @Test
    fun restoreWithoutStoredSessionNeedsPairing() {
        val machine = BridgeTokenMachine(FakeSessionStore())
        val outcome = machine.restore()
        assertTrue(outcome is BridgeTokenOutcome.NeedsPairing)
        assertNull(machine.accessToken())
    }

    @Test
    fun restoreWithCorruptJsonClearsStore() {
        val store = FakeSessionStore().apply { stored = "{broken" }
        val machine = BridgeTokenMachine(store)
        val outcome = machine.restore()
        assertTrue(outcome is BridgeTokenOutcome.NeedsPairing)
        assertNull(store.stored)
    }

    @Test
    fun bindThenRestoreRoundTripsThroughStore() {
        val store = FakeSessionStore()
        val machine = BridgeTokenMachine(store)
        machine.bindFromPairing(fixtureSessionJson())
        assertEquals("acc-1", machine.accessToken())
        assertEquals(fixtureSessionJson(), store.stored)

        val second = BridgeTokenMachine(store)
        val restored = second.restore()
        assertTrue(restored is BridgeTokenOutcome.Restored)
        assertEquals("acc-1", (restored as BridgeTokenOutcome.Restored).session.accessToken)
    }

    @Test
    fun refreshIsSingleFlight() {
        val machine = BridgeTokenMachine(FakeSessionStore())
        machine.bindFromPairing(fixtureSessionJson())
        assertTrue(machine.beginRefresh())
        assertFalse(machine.beginRefresh(), "刷新进行中不得重复发起（single-flight）")
        machine.refreshSucceeded(fixtureSessionJson(accessToken = "acc-2"))
        assertTrue(machine.beginRefresh(), "刷新结束后可以再次发起")
    }

    @Test
    fun beginRefreshWithoutSessionIsRejected() {
        val machine = BridgeTokenMachine(FakeSessionStore())
        assertFalse(machine.beginRefresh())
    }

    @Test
    fun refreshRejectedUnauthorizedIsPermanent() {
        val store = FakeSessionStore()
        val machine = BridgeTokenMachine(store)
        machine.bindFromPairing(fixtureSessionJson())
        assertTrue(machine.beginRefresh())

        val outcome = machine.refreshRejected("UNAUTHORIZED")
        assertTrue(outcome is BridgeTokenOutcome.NeedsPairing)
        assertNull(store.stored, "permanent 失效必须清 Keychain")
        assertNull(machine.accessToken())
    }

    @Test
    fun refreshRejectedNetworkErrorKeepsSession() {
        val store = FakeSessionStore()
        val machine = BridgeTokenMachine(store)
        machine.bindFromPairing(fixtureSessionJson())
        machine.beginRefresh()

        val outcome = machine.refreshRejected("timeout")
        assertTrue(outcome is BridgeTokenOutcome.RefreshRejected)
        assertEquals("acc-1", machine.accessToken())
        assertEquals(fixtureSessionJson(), store.stored)
    }

    @Test
    fun refreshSucceededServerIdChangeClearsEverything() {
        val store = FakeSessionStore()
        val machine = BridgeTokenMachine(store)
        machine.bindFromPairing(fixtureSessionJson(serverId = "computer-1"))
        machine.beginRefresh()

        val outcome = machine.refreshSucceeded(fixtureSessionJson(serverId = "computer-2"))
        assertTrue(outcome is BridgeTokenOutcome.NeedsPairing)
        assertEquals(
            BridgeClientKnownCode.SERVER_CHANGED.name,
            (outcome as BridgeTokenOutcome.NeedsPairing).error.knownClientCode
        )
        assertNull(store.stored)
    }

    @Test
    fun refreshSucceededUpdatesTokenAndPersists() {
        val store = FakeSessionStore()
        val machine = BridgeTokenMachine(store)
        machine.bindFromPairing(fixtureSessionJson())
        machine.beginRefresh()

        val updated = fixtureSessionJson(accessToken = "acc-9")
        val outcome = machine.refreshSucceeded(updated)
        assertTrue(outcome is BridgeTokenOutcome.Refreshed)
        assertEquals("acc-9", machine.accessToken())
        assertEquals(updated, store.stored)
    }
}
