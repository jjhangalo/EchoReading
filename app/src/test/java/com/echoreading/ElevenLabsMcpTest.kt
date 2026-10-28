package com.echoreading

import com.echoreading.voice.ElevenLabsMcp
import com.echoreading.voice.ElevenLabsQuota
import com.echoreading.voice.ElevenLabsVoice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ElevenLabsMcpTest {
    @Test
    fun readsVoicesFromMcpTextContent() {
        val result = JSONObject().put("content", JSONArray().put(JSONObject()
            .put("type", "text")
            .put("text", """[{"id":"voice-1","name":"Joana"},{"voice_id":"voice-2","name":"Miguel"}]""")))

        assertEquals(
            listOf(ElevenLabsVoice("voice-1", "Joana"), ElevenLabsVoice("voice-2", "Miguel")),
            ElevenLabsMcp.parseVoices(result),
        )
    }

    @Test
    fun readsAndFiltersPublicDefaultVoices() {
        val response = JSONObject("""{"voices":[{"voice_id":"voice-1","name":"Maria - Warm"},{"voice_id":"voice-2","name":"George - Calm"}]}""")

        assertEquals(
            listOf(ElevenLabsVoice("voice-1", "Maria - Warm")),
            ElevenLabsMcp.filterVoices(ElevenLabsMcp.parseVoices(response), "mArIa"),
        )
    }

    @Test
    fun readsUsageWhenSubscriptionToolExists() {
        val result = JSONObject().put("content", JSONArray().put(JSONObject()
            .put("type", "text")
            .put("text", """{"tier":"starter","character_count":2500,"character_limit":10000}""")))

        assertEquals(ElevenLabsQuota("starter", 2500, 10000), ElevenLabsMcp.parseQuota(result))
    }

    @Test
    fun readsShortLivedAudioLink() {
        val result = JSONObject().put("content", JSONArray().put(JSONObject()
            .put("type", "text")
            .put("text", "Áudio: https://cdn.elevenlabs.io/signed/sample.mp3")))

        assertEquals("https://cdn.elevenlabs.io/signed/sample.mp3", ElevenLabsMcp.findAudioUrl(result))
    }

    @Test
    fun displaysProviderReasonForRejectedSpeech() {
        val result = JSONObject().put("isError", true).put("content", JSONArray().put(JSONObject()
            .put("type", "text")
            .put("text", "Voice is unavailable for this account")))

        assertEquals("Voice is unavailable for this account", ElevenLabsMcp.toolError(result))
    }

    @Test
    fun rejectsSpeechToolWhoseSchemaCannotAcceptText() {
        val broken = JSONObject().put("inputSchema", JSONObject().put("properties", JSONObject()
            .put("voice_id", JSONObject())))
        val usable = JSONObject().put("inputSchema", JSONObject().put("properties", JSONObject()
            .put("text", JSONObject()).put("voice_id", JSONObject())))

        assertEquals(false, ElevenLabsMcp.supportsSpeechTool(broken))
        assertEquals(true, ElevenLabsMcp.supportsSpeechTool(usable))
    }

    @Test
    fun hidesVoicesUnavailableOnFreePlan() {
        val voices = listOf(
            ElevenLabsVoice("default", "Default", category = "premade"),
            ElevenLabsVoice("library", "Library", category = "community"),
            ElevenLabsVoice("paid", "Paid", category = "premade", availableForTiers = setOf("starter")),
            ElevenLabsVoice("owned", "My voice", category = "generated", isOwner = true),
            ElevenLabsVoice("unknown", "No eligibility metadata"),
        )

        assertEquals(listOf("default", "owned"),
            ElevenLabsMcp.availableVoices(voices, "free").map { it.id })
        assertEquals(listOf("default", "library", "paid", "owned", "unknown"),
            ElevenLabsMcp.availableVoices(voices, "starter").map { it.id })
    }

    @Test
    fun readsTierRestrictionsFromVoiceMetadata() {
        val response = JSONObject("""{"voices":[{"voice_id":"premium","name":"Premium","category":"professional","available_for_tiers":["creator"],"sharing":{"free_users_allowed":false}}]}""")
        val voice = ElevenLabsMcp.parseVoices(response).single()

        assertEquals(setOf("creator"), voice.availableForTiers)
        assertEquals(false, voice.freeUsersAllowed)
        assertEquals(emptyList<ElevenLabsVoice>(), ElevenLabsMcp.availableVoices(listOf(voice), "free"))
    }
}
