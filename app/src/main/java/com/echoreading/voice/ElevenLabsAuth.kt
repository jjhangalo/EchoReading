package com.echoreading.voice

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** OAuth for ElevenLabs' hosted MCP server. No ElevenLabs API key is stored in the app. */
object ElevenLabsAuth {
    const val CLIENT_ID_URL = "https://jjhangalo.github.io/EchoReading/oauth-client.json"
    const val RESOURCE = "https://api.us.elevenlabs.io/v1/mcp"
    const val ISSUER = "https://api.us.elevenlabs.io"
    const val REDIRECT_URI = "https://jjhangalo.github.io/EchoReading/oauth-callback.html"
    private const val AUTH_URL = "https://elevenlabs.io/app/oauth/authorize"
    private const val TOKEN_URL = "https://api.us.elevenlabs.io/v1/oauth/token"
    private const val REVOKE_URL = "https://api.us.elevenlabs.io/v1/oauth/revoke"
    private const val SETTINGS = "elevenlabs_settings"
    private const val SESSION = "session"
    private const val PENDING = "pending"
    private const val KEY_ALIAS = "eco_reading_elevenlabs_oauth"
    private val random = SecureRandom()
    private val refreshLock = Mutex()

    val revision = MutableStateFlow(0)

    fun isEnabled(context: Context): Boolean = preferences(context).getBoolean("enabled", false)
    fun useForReading(context: Context): Boolean =
        isEnabled(context) && preferences(context).getBoolean("use_for_reading", false) && isConnected(context)
    fun selectedVoiceId(context: Context): String? = preferences(context).getString("voice_id", null)
    fun selectedVoiceName(context: Context): String? = preferences(context).getString("voice_name", null)
    fun maxCharactersPerReading(context: Context): Int =
        preferences(context).getInt("max_characters_per_reading", 5_000).coerceIn(500, 20_000)
    fun isConnected(context: Context): Boolean = secureGet(context, SESSION)?.let { stored ->
        runCatching { JSONObject(stored) }.getOrNull()?.let { session ->
            session.optLong("expires_at") > System.currentTimeMillis() ||
                session.optString("refresh_token").isNotBlank()
        }
    } == true

    fun setMaxCharactersPerReading(context: Context, limit: Int) {
        preferences(context).edit().putInt("max_characters_per_reading", limit.coerceIn(500, 20_000)).apply()
        revision.value++
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean("enabled", enabled)
            .putBoolean("use_for_reading", enabled && useForReading(context)).apply()
        revision.value++
    }

    fun setUseForReading(context: Context, selected: Boolean) {
        preferences(context).edit().putBoolean("use_for_reading", selected && isEnabled(context)).apply()
        revision.value++
    }

    fun selectVoice(context: Context, id: String, name: String) {
        preferences(context).edit().putString("voice_id", id).putString("voice_name", name).apply()
        revision.value++
    }

    suspend fun disconnect(context: Context) = withContext(Dispatchers.IO) {
        val session = secureGet(context, SESSION)?.let { runCatching { JSONObject(it) }.getOrNull() }
        secureRemove(context, SESSION)
        preferences(context).edit().putBoolean("use_for_reading", false).apply()
        revision.value++
        session?.let { stored ->
            listOf("refresh_token", "access_token").forEach { name ->
                stored.optString(name).takeIf { it.isNotBlank() }?.let { token ->
                    runCatching { revoke(token, name) }
                }
            }
        }
        Unit
    }

    fun beginSignIn(context: Context): Result<Uri> = runCatching {
        val state = randomUrlSafe(32)
        val verifier = randomUrlSafe(48)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        securePut(context, PENDING, JSONObject().put("state", state)
            .put("verifier", verifier).put("created_at", System.currentTimeMillis()).toString())
        Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID_URL)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", "text_to_speech")
            .appendQueryParameter("resource", RESOURCE)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
    }

    suspend fun finishSignIn(context: Context, callback: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(callback.scheme == "com.echoreading" && callback.host == "oauth" &&
                callback.path == "/elevenlabs") { "Resposta de autenticação inválida." }
            val pending = secureGet(context, PENDING)?.let(::JSONObject)
                ?: error("A sessão de autenticação expirou.")
            check(System.currentTimeMillis() - pending.getLong("created_at") < 10 * 60_000L)
            val expectedState = pending.getString("state")
            val returnedState = callback.getQueryParameter("state").orEmpty()
            check(MessageDigest.isEqual(expectedState.toByteArray(), returnedState.toByteArray())) {
                "Estado de autenticação inválido."
            }
            check(callback.getQueryParameter("iss") == ISSUER) { "Emissor OAuth inválido." }
            val code = callback.getQueryParameter("code")?.takeIf { it.isNotBlank() && it.length <= 8192 }
                ?: error(callback.getQueryParameter("error") ?: "Código de autenticação em falta.")
            secureRemove(context, PENDING)
            val token = tokenRequest(mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "code_verifier" to pending.getString("verifier"),
                "redirect_uri" to REDIRECT_URI,
                "client_id" to CLIENT_ID_URL,
                "resource" to RESOURCE,
            ))
            saveSession(context, token, null)
            revision.value++
            Unit
        }
    }

    suspend fun accessToken(context: Context): String? = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            val session = secureGet(context, SESSION)?.let(::JSONObject) ?: return@withLock null
            if (session.optLong("expires_at") > System.currentTimeMillis() + 60_000L) {
                return@withLock session.optString("access_token").takeIf { it.isNotBlank() }
            }
            val refreshToken = session.optString("refresh_token").takeIf { it.isNotBlank() }
            if (refreshToken == null) {
                secureRemove(context, SESSION)
                revision.value++
                return@withLock null
            }
            try {
                val token = tokenRequest(mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                    "client_id" to CLIENT_ID_URL,
                    "resource" to RESOURCE,
                ))
                saveSession(context, token, refreshToken)
                revision.value++
                token.optString("access_token").takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun saveSession(context: Context, response: JSONObject, previousRefresh: String?) {
        val accessToken = response.getString("access_token")
        check(accessToken.isNotBlank())
        val session = JSONObject()
            .put("access_token", accessToken)
            .put("refresh_token", response.optString("refresh_token").ifBlank { previousRefresh.orEmpty() })
            .put("expires_at", System.currentTimeMillis() + response.optLong("expires_in", 3600L) * 1000L)
        securePut(context, SESSION, session.toString())
    }

    private fun tokenRequest(fields: Map<String, String>): JSONObject {
        val connection = URL(TOKEN_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val form = fields.entries.joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
            }
            connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) throw IOException("OAuth HTTP ${connection.responseCode}")
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun revoke(token: String, type: String) {
        val connection = URL(REVOKE_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val form = "token=${URLEncoder.encode(token, "UTF-8")}&" +
                "token_type_hint=$type&client_id=${URLEncoder.encode(CLIENT_ID_URL, "UTF-8")}"
            connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun preferences(context: Context) = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
    private fun randomUrlSafe(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let {
        Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun securePut(context: Context, name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(preferences(context).edit().putString(name, encoded).commit()) {
            "Não foi possível guardar a sessão ElevenLabs."
        }
    }

    private fun secureGet(context: Context, name: String): String? {
        val encoded = preferences(context).getString(name, null) ?: return null
        return try {
            val parts = encoded.split(':', limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            secureRemove(context, name)
            null
        }
    }

    private fun secureRemove(context: Context, name: String) {
        preferences(context).edit().remove(name).apply()
    }

    @Synchronized
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }
}
