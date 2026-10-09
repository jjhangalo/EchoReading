package com.echoreading

import com.echoreading.reader.MarkdownBlockKind
import com.echoreading.reader.MarkdownMark
import com.echoreading.reader.MarkdownText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTextTest {
    @Test
    fun plainTextKeepsLiteralPunctuation() {
        val source = "A versão #1 usa foo_bar_baz e 2 * 3."
        val document = MarkdownText.parse(source)

        assertFalse(document.isMarkdown)
        assertEquals(source, document.spokenText)
        assertEquals(0, document.readingChunks().single().start)
    }

    @Test
    fun markdownStylingAndSpeechShareTheSameContent() {
        val source = """
            # Título
            Olá **mundo**, *amigo* e [site](https://exemplo.pt).
            - Primeiro item
            > Uma citação
            ```kotlin
            val x = 1
            println(x)
            ```
        """.trimIndent()
        val document = MarkdownText.parse(source)

        assertTrue(document.isMarkdown)
        assertEquals(
            "Título\nOlá mundo, amigo e site.\nPrimeiro item\nUma citação\nval x = 1\nprintln(x)",
            document.spokenText,
        )
        assertEquals(
            "Título\nOlá mundo, amigo e site.\n• Primeiro item\n│ Uma citação\nval x = 1\nprintln(x)",
            document.visualText,
        )
        assertEquals(MarkdownBlockKind.HEADING, document.visualRanges.first().kind)
        assertEquals(MarkdownBlockKind.CODE, document.visualRanges.last().kind)
        assertTrue(document.visualSpans.isNotEmpty())
        assertTrue(document.visualSpans.all { it.start >= 0 && it.end <= document.visualText.length })
        assertTrue(document.visualRanges.all { it.start >= 0 && it.end <= document.visualText.length })
        assertFalse(document.spokenText.contains("https://"))
    }

    @Test
    fun readingChunksKeepOffsetsInOriginalMarkdown() {
        val source = "# Título\n" + "**Palavra** e mais texto. ".repeat(5)
        val document = MarkdownText.parse(source)
        val chunks = document.readingChunks(40)

        assertTrue(chunks.size > 1)
        assertEquals(source.indexOf("Título"), chunks.first().start)
        assertTrue(chunks.all { it.start in source.indices && source[it.start].isLetter() })
        assertTrue(chunks.none { it.text.contains("**") || it.text.contains('#') })
    }

    @Test
    fun escapedAndUnmatchedMarkersStayLiteral() {
        val source = "Texto \\*literal\\* e *sem fecho."
        val document = MarkdownText.parse(source)

        assertTrue(document.isMarkdown)
        assertEquals("Texto *literal* e *sem fecho.", document.spokenText)
    }

    @Test
    fun setextHeadingAndRuleDoNotSpeakTheirMarkers() {
        val document = MarkdownText.parse("Capítulo\n========\n\n---\nFim")

        assertTrue(document.isMarkdown)
        assertEquals("Capítulo\n\nFim", document.spokenText)
        assertEquals(MarkdownBlockKind.HEADING, document.visualRanges.first().kind)
        assertEquals(MarkdownBlockKind.RULE, document.visualRanges[1].kind)
        assertTrue(MarkdownText.parse("---").spokenText.isBlank())
    }

    @Test
    fun linkDestinationWithParenthesesIsNotSpoken() {
        val document = MarkdownText.parse("Leia [o guia](https://exemplo.pt/guia_(novo)) agora.")

        assertEquals("Leia o guia agora.", document.spokenText)
    }

    @Test
    fun numberedListAndEqualsHeadingAreDetected() {
        val document = MarkdownText.parse("Secção\n=======\n1. Um\n2. Dois")

        assertTrue(document.isMarkdown)
        assertEquals("Secção\nUm\nDois", document.spokenText)
        assertEquals("Secção\n1. Um\n2. Dois", document.visualText)
        assertEquals(MarkdownBlockKind.NUMBERED, document.visualRanges.last().kind)
    }

    @Test
    fun wrappedParagraphKeepsItsLineBreakInTheEditor() {
        val document = MarkdownText.parse("**Início** da frase\ncontinuação da frase")

        assertEquals("Início da frase\ncontinuação da frase", document.visualText)
        assertEquals("Início da frase\ncontinuação da frase", document.spokenText)
    }

    @Test
    fun closingAsteriskStylesTextAndBackspaceRevealsOpeningAsterisk() {
        val incomplete = MarkdownText.parse("*bom dia")
        val complete = MarkdownText.parse("*bom dia*")
        val deleteAt = complete.visualToOriginal(complete.visualText.length) - 1
        val afterBackspace = MarkdownText.parse(complete.sourceText.removeRange(deleteAt, deleteAt + 1))

        assertFalse(incomplete.isMarkdown)
        assertEquals("*bom dia", incomplete.visualText)
        assertTrue(complete.isMarkdown)
        assertEquals("bom dia", complete.visualText)
        assertTrue(complete.visualSpans.any { it.mark == MarkdownMark.ITALIC })
        assertEquals(9, complete.visualToOriginal(complete.visualText.length))
        assertEquals(7, complete.originalToVisual(9))
        assertEquals("*bom dia", afterBackspace.visualText)
        assertFalse(afterBackspace.isMarkdown)
    }

    @Test
    fun visualOffsetsStayOrderedAcrossBlockMarkers() {
        val source = "# Título\n- **Item**\n> Citação\n---\nFim"
        val document = MarkdownText.parse(source)

        assertTrue((0..source.length).map(document::originalToVisual).zipWithNext().all { (a, b) -> a <= b })
        assertTrue((0..document.visualText.length).map(document::visualToOriginal).zipWithNext().all { (a, b) -> a <= b })
        assertEquals(source.length, document.visualToOriginal(document.visualText.length))
        assertEquals(document.visualText.length, document.originalToVisual(source.length))
        val indented = MarkdownText.parse("  - item")
        assertEquals("  • item", indented.visualText)
        assertEquals("item", indented.spokenText)
    }

    @Test
    fun unfinishedCodeFenceRemainsVisibleUntilClosed() {
        val unfinished = MarkdownText.parse("```kotlin\nval x = 1")
        val finished = MarkdownText.parse("```kotlin\nval x = 1\n```")

        assertFalse(unfinished.isMarkdown)
        assertEquals("```kotlin\nval x = 1", unfinished.visualText)
        assertTrue(finished.isMarkdown)
        assertEquals("val x = 1", finished.visualText)
        assertEquals("val x = 1", finished.spokenText)
    }

    @Test
    fun emptyHeadingMarkerRemainsVisibleWhileTyping() {
        assertEquals("# ", MarkdownText.parse("# ").visualText)
        assertFalse(MarkdownText.parse("# ").isMarkdown)
        assertEquals("Título", MarkdownText.parse("# Título").visualText)
    }

    @Test
    fun completeInlineFormatsApplyInsideTheSameEditor() {
        val document = MarkdownText.parse("**forte** _leve_ ~~riscado~~ `código` [link](destino)")

        assertEquals("forte leve riscado código link", document.visualText)
        assertEquals(
            setOf(MarkdownMark.BOLD, MarkdownMark.ITALIC, MarkdownMark.STRIKE,
                MarkdownMark.CODE, MarkdownMark.LINK),
            document.visualSpans.map { it.mark }.toSet(),
        )
    }
}
