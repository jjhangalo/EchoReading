package com.echoreading.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareIntentHandlerTest {

    @Test
    fun extractsPlainTextSuccessfully() {
        val text = "EchoReading leitura partilhada"
        val result = ShareIntentHandler.extractText(
            action = ShareIntentHandler.ACTION_SEND,
            mimeType = "text/plain",
            extraText = text
        )
        assertEquals(text, result)
    }

    @Test
    fun extractsWildcardTextSuccessfully() {
        val text = "Texto com wildcard"
        val result = ShareIntentHandler.extractText(
            action = ShareIntentHandler.ACTION_SEND,
            mimeType = "text/*",
            extraText = text
        )
        assertEquals(text, result)
    }

    @Test
    fun extractsPrefixTextMimeTypeSuccessfully() {
        val text = "Texto com subtipo html"
        val result = ShareIntentHandler.extractText(
            action = ShareIntentHandler.ACTION_SEND,
            mimeType = "text/html",
            extraText = text
        )
        assertEquals(text, result)
    }

    @Test
    fun trimsLeadingAndTrailingWhitespace() {
        val raw = "  \n\t Texto com espaços exteriores \t\n "
        val result = ShareIntentHandler.extractText(
            action = ShareIntentHandler.ACTION_SEND,
            mimeType = "text/plain",
            extraText = raw
        )
        assertEquals("Texto com espaços exteriores", result)
    }

    @Test
    fun rejectsNonSendAction() {
        assertNull(
            ShareIntentHandler.extractText(
                action = "android.intent.action.VIEW",
                mimeType = "text/plain",
                extraText = "Texto"
            )
        )
        assertNull(
            ShareIntentHandler.extractText(
                action = "android.intent.action.PROCESS_TEXT",
                mimeType = "text/plain",
                extraText = "Texto"
            )
        )
        assertNull(
            ShareIntentHandler.extractText(
                action = null,
                mimeType = "text/plain",
                extraText = "Texto"
            )
        )
        assertNull(
            ShareIntentHandler.extractText(
                action = "",
                mimeType = "text/plain",
                extraText = "Texto"
            )
        )
    }

    @Test
    fun rejectsNonTextMimeTypes() {
        assertNull(
            ShareIntentHandler.extractText(
                action = ShareIntentHandler.ACTION_SEND,
                mimeType = "image/png",
                extraText = "Texto"
            )
        )
        assertNull(
            ShareIntentHandler.extractText(
                action = ShareIntentHandler.ACTION_SEND,
                mimeType = "image/jpeg",
                extraText = "Texto"
            )
        )
        assertNull(
            ShareIntentHandler.extractText(
                action = ShareIntentHandler.ACTION_SEND,
                mimeType = "application/pdf",
                extraText = "Texto"
            )
        )
        assertNull(
            ShareIntentHandler.extractText(
                action = ShareIntentHandler.ACTION_SEND,
                mimeType = "video/mp4",
                extraText = "Texto"
            )
        )
        assertNull(
            ShareIntentHandler.extractText(
                action = ShareIntentHandler.ACTION_SEND,
                mimeType = null,
                extraText = "Texto"
            )
        )
        assertNull(
            ShareIntentHandler.extractText(
                action = ShareIntentHandler.ACTION_SEND,
                mimeType = "",
                extraText = "Texto"
            )
        )
    }

    @Test
    fun rejectsNullOrBlankText() {
        assertNull(ShareIntentHandler.extractText(ShareIntentHandler.ACTION_SEND, "text/plain", null))
        assertNull(ShareIntentHandler.extractText(ShareIntentHandler.ACTION_SEND, "text/plain", ""))
        assertNull(ShareIntentHandler.extractText(ShareIntentHandler.ACTION_SEND, "text/plain", "   \t\n  "))
    }

    @Test
    fun handlesPortugueseAndUnicodeCharacters() {
        val pt = "Água, café, sótão e coração: leitura 100% precisa em português!"
        val result = ShareIntentHandler.extractText(
            action = ShareIntentHandler.ACTION_SEND,
            mimeType = "text/plain",
            extraText = pt
        )
        assertEquals(pt, result)
    }

    @Test
    fun handlesUnicodeMathAndSpecialSymbols() {
        val math = "Fórmula: ∑_{i=1}^n x_i × √y = ∞ ⚡"
        val result = ShareIntentHandler.extractText(
            action = ShareIntentHandler.ACTION_SEND,
            mimeType = "text/plain",
            extraText = math
        )
        assertEquals(math, result)
    }

    @Test
    fun handlesLargePayloads() {
        val huge = "EcoLeitura ".repeat(25_000) // ~275,000 chars
        val result = ShareIntentHandler.extractText(
            action = ShareIntentHandler.ACTION_SEND,
            mimeType = "text/plain",
            extraText = huge
        )
        assertEquals(huge.trim(), result)
    }

    @Test
    fun extractTextFromNullIntentReturnsNull() {
        val result = ShareIntentHandler.extractText(null as android.content.Intent?)
        assertNull("Null Intent must return null safely", result)
    }

    @Test
    fun extractTextFromIntentHandlesCorruptedParcelableSafely() {
        val corruptedIntent = object : android.content.Intent() {
            override fun getAction(): String = "android.intent.action.SEND"
            override fun getType(): String = "text/plain"
            override fun getCharSequenceExtra(name: String?): CharSequence {
                throw RuntimeException("Simulated BadParcelableException / ClassNotFoundException")
            }
        }
        val result = ShareIntentHandler.extractText(corruptedIntent)
        assertNull("Corrupted intent throwing during extraction must be caught defensively and return null", result)
    }
}

