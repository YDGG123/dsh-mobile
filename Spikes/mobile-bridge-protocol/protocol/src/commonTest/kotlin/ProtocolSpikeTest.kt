package spike.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

/**
 * Fixtures are lifted from the real shapes produced by
 * bridge/src/adapter.mjs (state), bridge/src/server.js (info/events) and
 * packages/mobile-sdk/index.js (pairing/session). Field names must not drift.
 */
class ProtocolSpikeTest {

    private val info = """
        {"protocolVersion":1,"serverId":"computer-1","name":"DS Bot",
         "capabilities":{"discovery":true,"pairing":true,"conversations":true,
           "history":true,"stream":true,"send":true,"attachments":false,
           "actions":["models","efforts","update-bot","set-flags","memory-view","schedules"],
           "modelSelection":true,"queue":false,"cancel":false,
           "groups":true,"botProfileEditing":false,"botEditableFields":["model","effort"]},
         "compatibility":{"protocolVersion":1,"missing":[],"message":"ok"}}
    """.trimIndent()

    private val state = """
        {"revision":7,
         "readOnly":null,
         "bots":[
           {"id":"chief","kind":"bot","name":"Chief","role":"Main","sessionId":"s-chief",
            "model":"mock/model","effort":"low","waiting":false,"pinned":true,"hidden":false,
            "parts":["s-chief-2"],"avatar":{"shape":"circle","color":"#123456"}},
           {"id":"coder","kind":"bot","name":"Coder","role":"DEV","sessionId":"s-coder",
            "waiting":true,"parts":[]}
         ],
         "rooms":[
           {"id":"room-1","kind":"room","name":"Our Free Model 开发组","sessionId":"room-1",
            "members":["chief","coder"],"admin":"chief","mode":"admin","pinned":false,"hidden":false,
            "parts":[]}
         ],
         "models":[{"ref":"mock/model","name":"Mock","providerName":"Mock Inc"}],
         "defaultModel":"mock/model",
         "mainBotIds":["chief"],
         "mainBotId":"chief",
         "questions":{},
         "activity":{},
         "work":{"bots":{"coder":{"tool":"bash","said":"building","since":1730000000000}},
                 "rooms":{"room-1":[{"botId":"coder","speaking":true,"tool":"bash"}]},
                 "approvals":[{"id":"ap-1","botId":"coder","roomId":"room-1","answerable":true}],
                 "waiting":{}},
         "memory":true,"browser":false}
    """.trimIndent()

    @Test
    fun parsesServerInfoAndCapabilities() {
        val parsed = parseInfo(info)
        assertEquals(1, parsed.protocolVersion)
        assertEquals("computer-1", parsed.serverId)

        val caps = parseCapabilities(info)
        assertTrue(caps.modelSelection)
        // Bridge ships update-bot but advertises botProfileEditing: false.
        assertTrue(caps.actions.contains("update-bot"))
        assertFalse(caps.queue)
        assertTrue(parseInfo(info).capabilities["botProfileEditing"]!!.jsonPrimitive.boolean == false)
    }

    @Test
    fun parsesTeamStateEveryDocumentedField() {
        val team = parseState(state)
        assertEquals(2, team.bots.size)
        assertEquals(1, team.rooms.size)
        assertEquals(listOf("chief"), team.mainBotIds)
        assertEquals("chief", team.mainBotId)
        assertEquals("mock/model", team.defaultModel)
        assertEquals("Mock Inc", team.models.single().providerName)
        assertTrue(team.memory)

        val chief = team.bots.first()
        assertEquals("s-chief", chief.sessionId)
        assertEquals(listOf("s-chief-2"), chief.parts)
        assertEquals("circle", chief.avatar!!["shape"]!!.jsonPrimitive.content)
        assertTrue(chief.pinned == true)

        val room = team.rooms.single()
        assertEquals(listOf("chief", "coder"), room.members)
        assertEquals("chief", room.admin)
        assertEquals("admin", room.mode)
    }

    @Test
    fun parsesWorkStatusAndApprovals() {
        val work = parseState(state).work
        assertEquals("bash", work.bots.getValue("coder").tool)
        assertEquals(1730000000000L, work.bots.getValue("coder").since)
        assertTrue(work.rooms.getValue("room-1").single().speaking == true)
        assertEquals("ap-1", work.approvals.single().id)
        assertTrue(work.approvals.single().answerable == true)
    }

    @Test
    fun unknownTeamStateKeysAreIgnoredNotFatal() {
        // Forward compatibility: a newer host may add fields the app has not learned.
        val extended = state.replaceFirst("{", """{"futurePanel":{"x":1},"agents":[{"id":"a"}],""")
        assertEquals(2, parseState(extended).bots.size)
    }

    @Test
    fun parsesAllSixEventTypesAndDropsUnknown() {
        val ready = parseEvent("""{"type":"ready","conversationId":"room-1","serverId":"computer-1"}""")
        assertTrue(ready.isKnown)
        assertEquals("computer-1", ready.serverId)

        val reset = parseEvent("""{"type":"reset","conversationId":"room-1","sessionId":"s-chief",
            "eventSeq":41,"cursor":"abc",
            "messages":[{"id":"m1","sessionId":"s-chief","seq":1,"role":"user","text":"hi","createdAt":1730000000000}]}""")
        assertEquals("reset", reset.type)
        assertEquals(41L, reset.eventSeq)
        assertEquals("hi", reset.messages.single().text)

        val delta = parseEvent("""{"type":"delta","conversationId":"room-1","sessionId":"s-chief",
            "attemptId":"a1","text":"partial","reasoning":"thinking","index":3}""")
        assertEquals("a1", delta.attemptId)
        assertEquals(3, delta.index)

        val message = parseEvent("""{"type":"message","conversationId":"room-1","sessionId":"s-chief",
            "message":{"id":"m2","sessionId":"s-chief","seq":2,"role":"assistant","text":"done",
            "tool":{"callId":"c1","name":"bash","state":"completed","durationMs":12}},"replaceAttemptId":"a1"}""")
        assertEquals("bash", message.message!!.tool!!.name)
        assertEquals("a1", message.replaceAttemptId)

        val status = parseEvent("""{"type":"status","conversationId":"room-1","sessionId":"s-chief",
            "busy":false,"phase":"idle","clearAttempt":true,"eventSeq":42}""")
        assertTrue(status.clearAttempt == true)
        assertEquals("idle", status.phase)

        val error = parseEvent("""{"type":"error","error":{"code":"unauthorized","message":"Session expired"}}""")
        assertEquals("unauthorized", error.error!!.code)

        val unknown = parseEvent("""{"type":"telemetry","foo":1}""")
        assertFalse(unknown.isKnown)
    }

    @Test
    fun createdAtAcceptsBothEpochAndIsoString() {
        val epoch = parseEvent("""{"type":"message","message":{"id":"m","sessionId":"s","seq":1,
            "role":"user","text":"x","createdAt":1730000000000}}""")
        assertFalse(epoch.message!!.createdAt!!.isString)

        val iso = parseEvent("""{"type":"message","message":{"id":"m","sessionId":"s","seq":1,
            "role":"user","text":"x","createdAt":"2026-10-21T03:00:00.000Z"}}""")
        assertTrue(iso.message!!.createdAt!!.isString)
    }

    @Test
    fun encodesActionEnvelopeExactlyLikeTheSdk() {
        val payload = buildJsonObject { put("id", "chief"); put("pinned", true) }
        val encoded = encodeAction("set-flags", payload)
        val decoded = spikeJson.parseToJsonElement(encoded).jsonObject
        assertEquals("set-flags", decoded["endpoint"]!!.jsonPrimitive.content)
        assertEquals("chief", decoded["payload"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertTrue(decoded["payload"]!!.jsonObject["pinned"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun encodesWsAuthFrameAndOmitsAfterWhenNull() {
        val withCursor = spikeJson.parseToJsonElement(encodeAuthFrame("tkn", "room-1", 12)).jsonObject
        assertEquals("auth", withCursor["type"]!!.jsonPrimitive.content)
        assertEquals(12, withCursor["after"]!!.jsonPrimitive.int)

        val without = spikeJson.parseToJsonElement(encodeAuthFrame("tkn", "room-1", null)).jsonObject
        assertNull(without["after"])
    }

    @Test
    fun historyPageAndConversationsPageParse() {
        val page = parseHistory("""{"sessionId":"s-chief","cursor":null,
            "messages":[{"id":"m1","sessionId":"s-chief","seq":1,"role":"user","text":"hi"}]}""")
        assertEquals(1, page.messages.size)
        assertNull(page.cursor)

        val conversations = spikeJson.decodeFromString<ConversationsPage>(
            """{"conversations":[{"id":"chief","kind":"bot","name":"Chief","sessionId":"s-chief"}]}"""
        )
        assertEquals("chief", conversations.conversations.single().id)
    }

    @Test
    fun pairingShapesMatchTheSdkContract() {
        val pairing = spikeJson.decodeFromString<Pairing>(
            """{"claimToken":"claim","requestId":"req","status":"pending"}"""
        )
        assertEquals("pending", pairing.status)

        val pendingResult = spikeJson.parseToJsonElement("""{"status":"pending","requestId":"req"}""").jsonObject
        assertNull(pendingResult["accessToken"])

        val session = spikeJson.decodeFromString<Session>(
            """{"accessToken":"a","refreshToken":"r","deviceId":"d","serverId":"s",
                "expiresAt":1730000000000}"""
        )
        assertFalse(session.expiresAtIsString)
    }

    @Test
    fun pinAndOriginValidationMirrorTheSdk() {
        assertNotNull(validateTlsPin("sha256:" + "a".repeat(64)))
        assertNull(validateTlsPin("sha256:" + "A".repeat(64)))
        assertNull(validateTlsPin("sha256:short"))
        assertNull(validateTlsPin(null))

        assertEquals("https://192.168.1.5:7443", validateServerOrigin("https://192.168.1.5:7443"))
        assertEquals("https://host.ts.net", validateServerOrigin("https://host.ts.net/mobile/v1/"))
        assertNull(validateServerOrigin("http://192.168.1.5:7443"))
        assertNotNull(validateServerOrigin("http://127.0.0.1:7443", allowInsecure = true))
    }
}
