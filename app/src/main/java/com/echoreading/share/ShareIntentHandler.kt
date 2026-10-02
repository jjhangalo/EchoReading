package com.echoreading.share

import android.content.Intent
import android.net.Uri
import android.util.Log

object ShareIntentHandler {

    const val ACTION_SEND = "android.intent.action.SEND"
    const val MIME_TYPE_TEXT_PLAIN = "text/plain"
    const val MIME_TYPE_TEXT_WILDCARD = "text/*"
    const val MIME_TYPE_TEXT_PREFIX = "text/"
    const val MIME_TYPE_AUDIO_PREFIX = "audio/"

    /**
     * Requirement R3: Evaluates and extracts plain text from system share sheet intents (ACTION_SEND).
     * Pure validation logic decoupled from Android framework runtime for host JVM testability.
     */
    fun extractText(action: String?, mimeType: String?, extraText: CharSequence?): String? {
        if (action != ACTION_SEND) return null
        if (mimeType == null) return null
        if (mimeType != MIME_TYPE_TEXT_PLAIN &&
            mimeType != MIME_TYPE_TEXT_WILDCARD &&
            !mimeType.startsWith(MIME_TYPE_TEXT_PREFIX)
        ) {
            return null
        }
        val raw = extraText?.toString() ?: return null
        val trimmed = raw.trim()
        return if (trimmed.isEmpty()) null else trimmed
    }

    /**
     * Android framework entrypoint.
     * Defensively extracts text from incoming Intent with BadParcelableException protection
     * and ClipData fallback without dereferencing content URIs.
     */
    fun extractText(intent: Intent?): String? {
        if (intent == null) return null
        return try {
            val action = intent.action
            val mimeType = intent.type
            val extraText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
            extractText(action, mimeType, extraText)
        } catch (e: Exception) {
            Log.w("ShareIntentHandler", "Failed to extract text from untrusted intent", e)
            null
        }
    }

    /**
     * Extrai o Uri de um ficheiro de áudio partilhado via ACTION_SEND.
     */
    fun extractAudioUri(action: String?, mimeType: String?, streamUri: Uri?): Uri? {
        if (action != ACTION_SEND) return null
        if (mimeType == null || !mimeType.startsWith(MIME_TYPE_AUDIO_PREFIX)) return null
        return streamUri
    }

    fun extractAudioUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return try {
            val action = intent.action
            val mimeType = intent.type
            val streamUri = if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            extractAudioUri(action, mimeType, streamUri)
        } catch (e: Exception) {
            Log.w("ShareIntentHandler", "Failed to extract audio URI", e)
            null
        }
    }
}
