package com.echoreading

import com.echoreading.reader.ReaderPlaybackService
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderPlaybackNotificationTest {

    private val fallback = "Eco Leitura"

    @Test
    fun emptyTextReturnsFallback() {
        assertEquals(fallback, ReaderPlaybackService.formatTitle("", fallback))
    }

    @Test
    fun blankWhitespaceTextReturnsFallback() {
        assertEquals(fallback, ReaderPlaybackService.formatTitle("   \n\t  ", fallback))
    }

    @Test
    fun shortTextReturnsDirectly() {
        val text = "Era uma vez um gato das botas."
        assertEquals(text, ReaderPlaybackService.formatTitle(text, fallback))
    }

    @Test
    fun collapsesMultipleWhitespacesAndNewlines() {
        val multiline = "Primeira linha.\n\nSegunda linha.\t  Terceira linha."
        val expected = "Primeira linha. Segunda linha. Terceira linha."
        assertEquals(expected, ReaderPlaybackService.formatTitle(multiline, fallback))
    }

    @Test
    fun exactlyEightyCharactersHasNoEllipsis() {
        val eightyChars = "A".repeat(80)
        assertEquals(80, eightyChars.length)
        assertEquals(eightyChars, ReaderPlaybackService.formatTitle(eightyChars, fallback))
    }

    @Test
    fun eightyOneCharactersAppendsEllipsis() {
        val eightyOneChars = "B".repeat(81)
        val expected = "B".repeat(80) + "…"
        val result = ReaderPlaybackService.formatTitle(eightyOneChars, fallback)
        assertEquals(expected, result)
        assertEquals(81, result.length) // 80 chars + 1 unicode ellipsis char
    }

    @Test
    fun longPortugueseTextTruncatesAtEightyWithEllipsis() {
        val longText = "A inteligência artificial transformou a forma como interagimos com a tecnologia moderna no dia a dia dos cidadãos comuns."
        val result = ReaderPlaybackService.formatTitle(longText, fallback)
        assertEquals(longText.take(80) + "…", result)
    }

    @Test
    fun constantsAreCorrectAndDistinct() {
        // Notification IDs must be distinct and positive
        org.junit.Assert.assertNotEquals(
            ReaderPlaybackService.NOTIFICATION_ID.toLong(),
            ReaderPlaybackService.STATUS_NOTIFICATION_ID.toLong(),
        )
        org.junit.Assert.assertTrue(ReaderPlaybackService.NOTIFICATION_ID > 0)
        org.junit.Assert.assertTrue(ReaderPlaybackService.STATUS_NOTIFICATION_ID > 0)

        // Notification Channel IDs must be distinct and non-empty
        org.junit.Assert.assertNotEquals(
            ReaderPlaybackService.CHANNEL_ID,
            ReaderPlaybackService.STATUS_CHANNEL_ID,
        )
        org.junit.Assert.assertTrue(ReaderPlaybackService.CHANNEL_ID.isNotBlank())
        org.junit.Assert.assertTrue(ReaderPlaybackService.STATUS_CHANNEL_ID.isNotBlank())

        // Check actions
        assertEquals("com.echoreading.READ", ReaderPlaybackService.ACTION_READ)
        assertEquals("com.echoreading.PLAY", ReaderPlaybackService.ACTION_PLAY)
        assertEquals("com.echoreading.PAUSE", ReaderPlaybackService.ACTION_PAUSE)
        assertEquals("com.echoreading.STOP", ReaderPlaybackService.ACTION_STOP)
        assertEquals("com.echoreading.BACK_10", ReaderPlaybackService.ACTION_BACK)
        assertEquals("com.echoreading.FORWARD_10", ReaderPlaybackService.ACTION_FORWARD)
        assertEquals("com.echoreading.RESET", ReaderPlaybackService.ACTION_RESET)
    }

    @Test
    fun accentedAndSpecialCharactersPreserved() {
        val accented = "Atenção: à noite, o João leu café & chá no sótão com rapidez!"
        val result = ReaderPlaybackService.formatTitle(accented, fallback)
        assertEquals(accented, result)
    }

    @Test
    fun textWithLeadingAndTrailingWhitespacesTrimmed() {
        val raw = "  \n\t  Texto com espaços nas pontas.   \t \n "
        val expected = "Texto com espaços nas pontas."
        assertEquals(expected, ReaderPlaybackService.formatTitle(raw, fallback))
    }
}
