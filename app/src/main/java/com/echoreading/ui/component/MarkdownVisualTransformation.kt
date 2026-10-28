package com.echoreading.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.echoreading.reader.MarkdownBlockKind
import com.echoreading.reader.MarkdownDocument
import com.echoreading.reader.MarkdownMark

class MarkdownVisualTransformation(
    private val document: MarkdownDocument,
    private val accent: Color,
    private val codeBackground: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (!document.isMarkdown || text.text != document.sourceText) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val styled = buildAnnotatedString {
            append(document.visualText)
            document.visualRanges.forEach { range ->
                val style = when (range.kind) {
                    MarkdownBlockKind.HEADING -> SpanStyle(
                        fontWeight = FontWeight.Bold,
                        fontSize = when (range.level) { 1 -> 22.sp; 2 -> 19.sp; else -> 17.sp },
                    )
                    MarkdownBlockKind.QUOTE -> SpanStyle(fontStyle = FontStyle.Italic)
                    MarkdownBlockKind.CODE -> SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBackground,
                    )
                    MarkdownBlockKind.RULE -> SpanStyle(color = accent)
                    else -> null
                }
                if (style != null) addStyle(style, range.start, range.end)
            }
            document.visualSpans.forEach { span ->
                addStyle(
                    when (span.mark) {
                        MarkdownMark.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                        MarkdownMark.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                        MarkdownMark.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
                        MarkdownMark.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                        MarkdownMark.LINK -> SpanStyle(color = accent, textDecoration = TextDecoration.Underline)
                    },
                    span.start,
                    span.end,
                )
            }
        }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = document.originalToVisual(offset)
            override fun transformedToOriginal(offset: Int) = document.visualToOriginal(offset)
        }
        return TransformedText(styled, mapping)
    }
}
