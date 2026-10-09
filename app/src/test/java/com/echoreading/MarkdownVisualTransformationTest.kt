package com.echoreading

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import com.echoreading.reader.MarkdownText
import com.echoreading.ui.component.MarkdownVisualTransformation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownVisualTransformationTest {
    @Test
    fun closingAsteriskStylesInsideEditorAndBackspaceRestoresRawMarker() {
        val completedSource = "*bom dia*"
        val completed = MarkdownVisualTransformation(
            MarkdownText.parse(completedSource), Color.Black, Color.Transparent,
        ).filter(AnnotatedString(completedSource))

        assertEquals("bom dia", completed.text.text)
        assertTrue(completed.text.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        val deleteAt = completed.offsetMapping.transformedToOriginal(completed.text.length) - 1
        val undoneSource = completedSource.removeRange(deleteAt, deleteAt + 1)
        val undone = MarkdownVisualTransformation(
            MarkdownText.parse(undoneSource), Color.Black, Color.Transparent,
        ).filter(AnnotatedString(undoneSource))

        assertEquals("*bom dia", undone.text.text)
        assertTrue(undone.text.spanStyles.isEmpty())
    }
}
