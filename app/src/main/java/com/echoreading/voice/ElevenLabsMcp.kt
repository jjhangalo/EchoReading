package com.echoreading.voice

import android.content.Context
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

data class ElevenLabsVoice(
    val id: String,
    val name: String,
    val category: String = "",
    val availableForTiers: Set<String> = emptySet(),
    val freeUsersAllowed: Boolean? = null,
    val isOwner: Boolean = false,
)
data class ElevenLabsVoiceCatalog(val voices: List<ElevenLabsVoice>, val fromAccount: Boolean)
data class ElevenLabsQuota(val tier: String, val used: Long, val limit: Long)

/** Small Streamable HTTP MCP client for the OAuth-protected ElevenLabs host. */
object ElevenLabsMcp {
    private val nextId = AtomicInteger(1)

    class Reader internal constructor(private val generate: suspend (String, File) -> Long) {
        suspend fun synthesize(text: String, output: File): Long = generate(text, output)
    }

    suspend fun voices(context: Context, search: String = ""): Result<ElevenLabsVoiceCatalog> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!NetworkAccess.isAvailable(context)) {
                    return@runCatching ElevenLabsVoiceCatalog(emptyList(), fromAccount = false)
                }
                val token = ElevenLabsAuth.accessToken(context)
                    ?: error("Inicie sessão na ElevenLabs para ver as vozes.")
                val tier = runCatching { apiGet("/v1/user/subscription", token).optString("tier") }
                    .getOrNull()?.ifBlank { null } ?: "free"
                val accountVoices = runCatching { accountVoices(token, search) }.getOrNull()
                if (accountVoices != null) {
                    val allowed = availableVoices(accountVoices, tier)
                    if (allowed.isNotEmpty()) ElevenLabsVoiceCatalog(allowed, fromAccount = true)
                    else ElevenLabsVoiceCatalog(availableVoices(publicVoices(search), tier), fromAccount = false)
                } else {
                    val listed = runCatching {
                        val session = connect(context)
                        val tool = session.tool("search_voices", "list_voices", "search_voice_library", "voices_search")
                            ?: return@runCatching null
                        val arguments = JSONObject()
                        if (search.isNotBlank()) {
                            listOf("search", "query", "name").firstOrNull { session.accepts(tool, it) }
                                ?.let { arguments.put(it, search) }
                        }
                        parseVoices(session.call(tool, arguments))
                    }.getOrNull()
                    if (listed != null) {
                        val allowed = availableVoices(filterVoices(listed, search), tier)
                        if (allowed.isNotEmpty()) ElevenLabsVoiceCatalog(allowed, fromAccount = true)
                        else ElevenLabsVoiceCatalog(availableVoices(publicVoices(search), tier), fromAccount = false)
                    } else {
                        ElevenLabsVoiceCatalog(availableVoices(publicVoices(search), tier), fromAccount = false)
                    }
                }
            }
        }

    private fun accountVoices(token: String, search: String): List<ElevenLabsVoice> {
        val found = linkedMapOf<String, ElevenLabsVoice>()
        var pageToken: String? = null
        repeat(3) {
            val query = buildString {
                append("?page_size=100")
                if (search.isNotBlank()) append("&search=${URLEncoder.encode(search, "UTF-8")}")
                pageToken?.let { append("&next_page_token=${URLEncoder.encode(it, "UTF-8")}") }
            }
            val response = apiGet("/v2/voices$query", token)
            parseVoices(response).forEach { found[it.id] = it }
            pageToken = response.optString("next_page_token").takeIf { it.isNotBlank() }
            if (pageToken == null || !response.optBoolean("has_more")) return found.values.toList()
        }
        return found.values.toList()
    }

    private fun apiGet(path: String, token: String): JSONObject {
        val connection = URL("https://api.us.elevenlabs.io$path").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (connection.responseCode !in 200..299) throw IOException("ElevenLabs HTTP ${connection.responseCode}")
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun publicVoices(search: String): List<ElevenLabsVoice> {
        val connection = URL("https://api.elevenlabs.io/v1/voices").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            if (connection.responseCode !in 200..299) {
                throw IOException("Catálogo ElevenLabs HTTP ${connection.responseCode}")
            }
            val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            return filterVoices(parseVoices(response), search)
        } finally {
            connection.disconnect()
        }
    }

    internal fun filterVoices(voices: List<ElevenLabsVoice>, search: String): List<ElevenLabsVoice> =
        if (search.isBlank()) voices else voices.filter { it.name.contains(search.trim(), ignoreCase = true) }

    internal fun availableVoices(voices: List<ElevenLabsVoice>, tier: String): List<ElevenLabsVoice> =
        voices.filter { voice ->
            val plan = tier.lowercase()
            val permittedByTier = voice.availableForTiers.isEmpty() || "all" in voice.availableForTiers ||
                plan in voice.availableForTiers
            val permittedOnFree = plan != "free" || voice.isOwner ||
                (voice.category == "premade" && voice.freeUsersAllowed != false)
            permittedByTier && permittedOnFree
        }

    suspend fun quota(context: Context): Result<ElevenLabsQuota> = withContext(Dispatchers.IO) {
        runCatching {
            val token = ElevenLabsAuth.accessToken(context)
                ?: error("Inicie sessão na ElevenLabs para ver os limites.")
            runCatching { apiGet("/v1/user/subscription", token) }
                .getOrNull()?.let(::parseQuota)?.let { return@runCatching it }
            val session = connect(context)
            val tool = session.tool("check_subscription", "get_user_subscription", "subscription")
                ?: error("A ElevenLabs não disponibiliza os limites da conta nesta ligação.")
            parseQuota(session.call(tool, JSONObject()))
                ?: error("Não foi possível interpretar os limites da conta.")
        }
    }

    suspend fun openReader(context: Context, voiceId: String, speed: Float): Reader = withContext(Dispatchers.IO) {
        val session = connect(context)
        val tool = session.speechTool()
        Reader { text, output ->
            withContext(Dispatchers.IO) {
                if (tool == null) {
                    val token = ElevenLabsAuth.accessToken(context)
                        ?: error("A sessão ElevenLabs expirou. Inicie sessão novamente.")
                    directSpeech(token, voiceId, text, output)
                } else {
                    val voiceParameter = when {
                        session.accepts(tool, "voice_id") -> "voice_id"
                        session.accepts(tool, "voiceId") -> "voiceId"
                        else -> "voice_name"
                    }
                    val voice = if (voiceParameter == "voice_name") {
                        ElevenLabsAuth.selectedVoiceName(context)
                            ?: error("Nome da voz ElevenLabs em falta.")
                    } else voiceId
                    val arguments = JSONObject().put("text", text).put(voiceParameter, voice)
                    if (session.accepts(tool, "model_id")) arguments.put("model_id", "eleven_multilingual_v2")
                    if (session.accepts(tool, "output_format")) arguments.put("output_format", "mp3_44100_128")
                    if (session.accepts(tool, "speed")) arguments.put("speed", speed.coerceIn(0.7f, 1.2f).toDouble())
                    val result = session.call(tool, arguments)
                    val downloadUrl = findAudioUrl(result)
                        ?: error("A ElevenLabs não devolveu um ficheiro de áudio.")
                    download(downloadUrl, output)
                    audioDuration(output)
                }
            }
        }
    }

    private suspend fun directSpeech(token: String, voiceId: String, text: String, output: File): Long {
        require(voiceId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Identificador de voz inválido." }
        val connection = URL("https://api.us.elevenlabs.io/v1/text-to-speech/$voiceId?output_format=mp3_44100_128")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "audio/mpeg")
            val body = JSONObject().put("text", text).put("model_id", "eleven_multilingual_v2")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText().take(2048) }.orEmpty()
                val message = runCatching {
                    JSONObject(detail).optJSONObject("detail")?.optString("message")
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Pedido recusado."
                throw IOException("ElevenLabs HTTP ${connection.responseCode}: $message")
            }
            writeAudio(connection, output)
            return audioDuration(output)
        } finally {
            connection.disconnect()
        }
    }

    private fun audioDuration(output: File): Long = MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(output.absolutePath)
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        if (duration <= 0L) throw IOException("Duração de áudio inválida.")
        duration
    }

    private suspend fun connect(context: Context): Session {
        val token = ElevenLabsAuth.accessToken(context)
            ?: error("Inicie sessão na ElevenLabs antes de utilizar este provedor.")
        val session = Session(token)
        session.initialize()
        return session
    }

    private class Session(private val token: String) {
        private var sessionId: String? = null
        private var tools: Map<String, JSONObject> = emptyMap()

        fun initialize() {
            val params = JSONObject()
                .put("protocolVersion", "2025-06-18")
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "Eco Leitura").put("version", "2.3.0"))
            request("initialize", params)
            request("notifications/initialized", JSONObject(), notification = true)
            val discovered = linkedMapOf<String, JSONObject>()
            val seenCursors = mutableSetOf<String>()
            var cursor: String? = null
            do {
                val arguments = JSONObject()
                cursor?.let { arguments.put("cursor", it) }
                val result = request("tools/list", arguments).optJSONObject("result") ?: JSONObject()
                val listing = result.optJSONArray("tools") ?: JSONArray()
                for (index in 0 until listing.length()) {
                    listing.optJSONObject(index)?.let { tool ->
                        discovered[tool.optString("name")] = tool
                    }
                }
                cursor = result.optString("nextCursor").takeIf { it.isNotBlank() }
            } while (cursor?.let { seenCursors.add(it) } == true && discovered.size < 500)
            tools = discovered
        }

        fun tool(vararg preferred: String): String? = preferred.firstNotNullOfOrNull { wanted ->
            tools.keys.firstOrNull { name ->
                val normalized = name.lowercase().replace('-', '_')
                normalized == wanted || normalized.endsWith(".$wanted") || normalized.endsWith("_$wanted")
            }
        }

        fun accepts(tool: String, parameter: String): Boolean =
            tools[tool]?.optJSONObject("inputSchema")?.optJSONObject("properties")?.has(parameter) == true

        fun speechTool(): String? = listOf("generate_speech", "convert_text_to_speech", "text_to_speech")
            .firstNotNullOfOrNull { name ->
                tool(name)?.takeIf { candidate -> supportsSpeechTool(tools.getValue(candidate)) }
            }

        fun call(name: String, arguments: JSONObject): JSONObject {
            val response = request("tools/call", JSONObject().put("name", name).put("arguments", arguments))
            val result = response.optJSONObject("result") ?: error("Resposta MCP inválida.")
            toolError(result)?.let { throw IOException(it) }
            return result
        }

        private fun request(method: String, params: JSONObject, notification: Boolean = false): JSONObject {
            val body = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)
            if (!notification) body.put("id", nextId.getAndIncrement())
            val connection = URL(ElevenLabsAuth.RESOURCE).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 20_000
                connection.readTimeout = 60_000
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json, text/event-stream")
                connection.setRequestProperty("MCP-Protocol-Version", "2025-06-18")
                sessionId?.let { connection.setRequestProperty("Mcp-Session-Id", it) }
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                if (connection.responseCode !in 200..299) {
                    throw IOException("ElevenLabs MCP HTTP ${connection.responseCode}")
                }
                connection.getHeaderField("Mcp-Session-Id")?.let { sessionId = it }
                if (notification || connection.responseCode == 202) return JSONObject()
                if (connection.contentType?.contains("text/event-stream") == true) {
                    connection.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.startsWith("data:")) {
                                val event = runCatching { JSONObject(line.removePrefix("data:").trim()) }.getOrNull()
                                if (event != null && (event.has("result") || event.has("error"))) {
                                    return checkedResponse(event)
                                }
                            }
                        }
                    }
                    throw IOException("Resposta MCP vazia.")
                }
                return checkedResponse(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
            } finally {
                connection.disconnect()
            }
        }

        private fun checkedResponse(response: JSONObject): JSONObject {
            val error = response.optJSONObject("error") ?: return response
            throw IOException("ElevenLabs MCP: ${error.optString("message", "Erro desconhecido").take(160)}")
        }
    }

    internal fun supportsSpeechTool(tool: JSONObject): Boolean {
        val properties = tool.optJSONObject("inputSchema")?.optJSONObject("properties") ?: return false
        return properties.has("text") && listOf("voice_id", "voiceId", "voice_name")
            .any(properties::has)
    }

    internal fun toolError(result: JSONObject): String? {
        if (!result.optBoolean("isError")) return null
        val content = result.optJSONArray("content")
        val detail = (0 until (content?.length() ?: 0)).firstNotNullOfOrNull { index ->
            content?.optJSONObject(index)?.optString("text")?.takeIf { it.isNotBlank() }
        } ?: result.optJSONObject("structuredContent")?.optString("message")
        return detail?.trim()?.take(300)?.takeIf { it.isNotBlank() }
            ?: "A ElevenLabs recusou a operação."
    }

    internal fun parseVoices(result: JSONObject): List<ElevenLabsVoice> {
        val found = linkedMapOf<String, ElevenLabsVoice>()
        fun visit(value: Any?, depth: Int) {
            if (depth > 6) return
            when (value) {
                is JSONObject -> {
                    val id = value.optString("voice_id").ifBlank { value.optString("id") }
                    val name = value.optString("name")
                    if (id.isNotBlank() && name.isNotBlank()) {
                        val tiers = value.optJSONArray("available_for_tiers")
                        val allowed = (0 until (tiers?.length() ?: 0)).mapNotNull { index ->
                            tiers?.optString(index)?.lowercase()?.takeIf(String::isNotBlank)
                        }.toSet()
                        val sharing = value.optJSONObject("sharing")
                        found[id] = ElevenLabsVoice(
                            id = id,
                            name = name,
                            category = value.optString("category").lowercase(),
                            availableForTiers = allowed,
                            freeUsersAllowed = sharing?.takeIf { it.has("free_users_allowed") }
                                ?.optBoolean("free_users_allowed"),
                            isOwner = value.optBoolean("is_owner"),
                        )
                    }
                    value.keys().forEach { visit(value.opt(it), depth + 1) }
                }
                is JSONArray -> (0 until value.length()).forEach { visit(value.opt(it), depth + 1) }
                is String -> if (value.trimStart().startsWith("[") || value.trimStart().startsWith("{")) {
                    runCatching { JSONTokener(value).nextValue() }.getOrNull()?.let { visit(it, depth + 1) }
                }
            }
        }
        visit(result, 0)
        return found.values.toList()
    }

    internal fun parseQuota(result: JSONObject): ElevenLabsQuota? {
        val values = sequenceOf(result.optJSONObject("structuredContent"), result)
            .filterNotNull().plus((result.optJSONArray("content") ?: JSONArray()).let { content ->
                (0 until content.length()).mapNotNull { index ->
                    content.optJSONObject(index)?.optString("text")?.let { text ->
                        runCatching { JSONObject(text) }.getOrNull()
                    }
                }.asSequence()
            })
        return values.firstNotNullOfOrNull { item ->
            val subscription = item.optJSONObject("subscription") ?: item
            val used = subscription.optLong("character_count", -1)
            val limit = subscription.optLong("character_limit", -1)
            if (used < 0 || limit <= 0) null
            else ElevenLabsQuota(subscription.optString("tier", "Conta"), used, limit)
        }
    }

    internal fun findAudioUrl(result: JSONObject): String? {
        fun find(value: Any?, depth: Int): String? {
            if (depth > 6) return null
            return when (value) {
                is JSONObject -> {
                    listOf("audio_url", "download_url", "url").firstNotNullOfOrNull { key ->
                        value.optString(key).takeIf { it.startsWith("https://") }
                    } ?: value.keys().asSequence().firstNotNullOfOrNull { find(value.opt(it), depth + 1) }
                }
                is JSONArray -> (0 until value.length()).firstNotNullOfOrNull { find(value.opt(it), depth + 1) }
                is String -> Regex("https://[^\\s\\]\\)\\>\"']+").find(value)?.value
                else -> null
            }
        }
        return find(result, 0)
    }

    private suspend fun download(url: String, output: File) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            if (connection.responseCode !in 200..299) throw IOException("Áudio HTTP ${connection.responseCode}")
            writeAudio(connection, output)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun writeAudio(connection: HttpURLConnection, output: File) {
        var complete = false
        try {
            val contentType = connection.contentType.orEmpty().lowercase()
            if (!contentType.startsWith("audio/") && !contentType.startsWith("application/octet-stream")) {
                throw IOException("Resposta sem áudio.")
            }
            output.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                output.outputStream().use { destination ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 25L * 1024 * 1024) throw IOException("Áudio demasiado grande.")
                        destination.write(buffer, 0, count)
                    }
                }
            }
            if (output.length() == 0L) throw IOException("Áudio vazio.")
            complete = true
        } finally {
            if (!complete) output.delete()
        }
    }
}
