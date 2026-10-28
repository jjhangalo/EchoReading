package com.echoreading.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.echoreading.R
import kotlin.math.roundToInt

/**
 * Shared primary text surface used by the reader and the transcription screen.
 *
 * The [editable] flag controls whether the text can be modified: the reader
 * field is editable while no playback is active, and the transcription result
 * is always read-only. [readOnlyAsText] lets a read-only instance render its
 * content as selectable, auto-growing text instead of a fixed-height field;
 * the transcription result uses this so long transcripts are not clipped.
 *
 * The reader uses [expandable] for its playback/editing surface. The collapsed
 * field starts at [fieldHeight] and grows with its content up to
 * [maxFieldHeight]. If the content no longer fits, a down-arrow action appears
 * in the header and opens a full-screen editor/dialog; closing it returns to
 * the same auto-grown height rather than the initial height.
 *
 * When a read-only reader is playing, [readOnlyText] can be supplied with the
 * styled (visual) text and [followOffset] with the current visible character
 * offset. That surface is selectable and manually scrollable, and follows the
 * spoken line only when it reaches the lower part of the viewport, leaving
 * following context visible.
 */
@Composable
fun AppTextCard(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    title: String,
    icon: ImageVector,
    placeholder: String,
    modifier: Modifier = Modifier,
    editable: Boolean = true,
    readOnlyAsText: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
    fieldHeight: Dp = 160.dp,
    maxFieldHeight: Dp = fieldHeight * 1.25f,
    expandable: Boolean = false,
    readOnlyText: AnnotatedString? = null,
    followOffset: Int? = null,
    headerActions: (@Composable RowScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(20.dp)
    val effectiveMaxHeight = maxOf(fieldHeight, maxFieldHeight)
    var expanded by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 2.dp, shape = shape),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val textMeasurer = rememberTextMeasurer()
            val measuredText = remember(value.text, visualTransformation) {
                visualTransformation.filter(AnnotatedString(value.text)).text
            }
            val availableWidthPx = with(density) {
                (maxWidth - 64.dp).coerceAtLeast(0.dp).toPx().toInt()
            }.coerceAtLeast(1)
            val maxContentHeightPx = with(density) {
                (effectiveMaxHeight - 32.dp).coerceAtLeast(0.dp).toPx()
            }
            val hasOverflow = expandable && remember(
                measuredText,
                textStyle,
                availableWidthPx,
                maxContentHeightPx,
            ) {
                measuredText.isNotEmpty() && textMeasurer.measure(
                    text = measuredText,
                    style = textStyle,
                    constraints = Constraints(maxWidth = availableWidthPx),
                ).size.height > maxContentHeightPx
            }
            val showExpandAction = expandable && (hasOverflow || expanded)

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (headerActions != null || showExpandAction) {
                        Row(
                            modifier = Modifier.padding(start = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (showExpandAction) {
                                AppTextAction(
                                    icon = Icons.Default.KeyboardArrowDown,
                                    contentDescription = stringResource(R.string.reading_text_expand),
                                    onClick = { expanded = true },
                                )
                            }
                            headerActions?.invoke(this)
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    when {
                        !editable && readOnlyAsText -> ReadOnlyGrowText(
                            text = value.text,
                            placeholder = placeholder,
                            textStyle = textStyle,
                            minHeight = fieldHeight,
                        )

                        !editable && readOnlyText != null -> ReadOnlyFollowText(
                            text = readOnlyText,
                            placeholder = placeholder,
                            textStyle = textStyle,
                            followOffset = followOffset,
                            minHeight = fieldHeight,
                            maxHeight = effectiveMaxHeight,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        else -> TextField(
                            value = value,
                            onValueChange = { if (editable) onValueChange(it) },
                            placeholder = {
                                Text(
                                    text = placeholder,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = fieldHeight, max = effectiveMaxHeight),
                            readOnly = !editable,
                            visualTransformation = visualTransformation,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                            ),
                            textStyle = textStyle,
                        )
                    }
                }

                if (footer != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        content = footer,
                    )
                }
            }
        }
    }

    if (expanded) {
        ExpandedTextDialog(
            value = value,
            onValueChange = onValueChange,
            title = title,
            icon = icon,
            placeholder = placeholder,
            editable = editable,
            readOnlyAsText = readOnlyAsText,
            visualTransformation = visualTransformation,
            textStyle = textStyle,
            readOnlyText = readOnlyText,
            followOffset = followOffset,
            headerActions = headerActions,
            footer = footer,
            onDismiss = { expanded = false },
        )
    }
}

@Composable
private fun ReadOnlyGrowText(
    text: String,
    placeholder: String,
    textStyle: TextStyle,
    minHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .padding(16.dp),
    ) {
        SelectionContainer {
            Text(
                text = text.ifEmpty { placeholder },
                style = textStyle,
                color = if (text.isEmpty()) {
                    MaterialTheme.colorScheme.outline
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

/**
 * Styled, selectable read-only text with independent scrolling.
 *
 * [followOffset] is a visible (transformed) character offset. When that line
 * reaches the lower part of the viewport, the surface scrolls it to roughly a
 * third from the top. If the user has scrolled forward so the spoken line is
 * above the viewport, it does not pull the view back, so looking ahead during
 * playback is possible.
 */
@Composable
private fun ReadOnlyFollowText(
    text: AnnotatedString,
    placeholder: String,
    textStyle: TextStyle,
    followOffset: Int?,
    modifier: Modifier = Modifier,
    minHeight: Dp = 0.dp,
    maxHeight: Dp = Dp.Unspecified,
    fillHeight: Boolean = false,
) {
    val scrollState = rememberScrollState()
    var viewportHeight by remember { mutableIntStateOf(0) }
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val contentTopPaddingPx = with(density) { 16.dp.toPx() }
    val heightModifier = if (fillHeight) {
        Modifier.fillMaxSize()
    } else {
        Modifier.heightIn(min = minHeight, max = maxHeight)
    }

    Box(
        modifier = modifier
            .then(heightModifier)
            .onSizeChanged { viewportHeight = it.height },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            SelectionContainer {
                Text(
                    text = text.takeIf { it.isNotEmpty() } ?: AnnotatedString(placeholder),
                    style = textStyle,
                    color = if (text.isEmpty()) {
                        MaterialTheme.colorScheme.outline
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    onTextLayout = { textLayout = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            }
        }
    }

    LaunchedEffect(followOffset, textLayout, viewportHeight, scrollState.maxValue) {
        val layout = textLayout ?: return@LaunchedEffect
        val offset = followOffset ?: return@LaunchedEffect
        val line = layout.getLineForOffset(
            offset.coerceIn(0, layout.layoutInput.text.length),
        )
        val target = followScrollTarget(
            lineTop = layout.getLineTop(line) + contentTopPaddingPx,
            lineBottom = layout.getLineBottom(line) + contentTopPaddingPx,
            currentScroll = scrollState.value,
            viewportHeight = viewportHeight,
            maxScroll = scrollState.maxValue,
        )
        if (target != null && target != scrollState.value) {
            scrollState.animateScrollTo(target)
        }
    }
}

@Composable
private fun ExpandedTextDialog(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    title: String,
    icon: ImageVector,
    placeholder: String,
    editable: Boolean,
    readOnlyAsText: Boolean,
    visualTransformation: VisualTransformation,
    textStyle: TextStyle,
    readOnlyText: AnnotatedString?,
    followOffset: Int?,
    headerActions: (@Composable RowScope.() -> Unit)?,
    footer: (@Composable ColumnScope.() -> Unit)?,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppTextAction(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.reading_text_back),
                        onClick = onDismiss,
                    )
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (headerActions != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            content = headerActions,
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    when {
                        !editable && readOnlyAsText -> ReadOnlyFollowText(
                            text = AnnotatedString(value.text),
                            placeholder = placeholder,
                            textStyle = textStyle,
                            followOffset = null,
                            modifier = Modifier.fillMaxSize(),
                            fillHeight = true,
                        )

                        !editable && readOnlyText != null -> ReadOnlyFollowText(
                            text = readOnlyText,
                            placeholder = placeholder,
                            textStyle = textStyle,
                            followOffset = followOffset,
                            modifier = Modifier.fillMaxSize(),
                            fillHeight = true,
                        )

                        else -> TextField(
                            value = value,
                            onValueChange = { if (editable) onValueChange(it) },
                            placeholder = {
                                Text(
                                    text = placeholder,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            },
                            modifier = Modifier.fillMaxSize(),
                            readOnly = !editable,
                            visualTransformation = visualTransformation,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                            ),
                            textStyle = textStyle,
                        )
                    }
                }

                if (footer != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        content = footer,
                    )
                }
            }
        }
    }
}

/**
 * Returns the scroll position needed to bring a spoken line into the reading
 * area, or null when the line is already comfortably visible or is above the
 * viewport (the user is looking ahead and should not be pulled back).
 */
internal fun followScrollTarget(
    lineTop: Float,
    lineBottom: Float,
    currentScroll: Int,
    viewportHeight: Int,
    maxScroll: Int,
): Int? {
    if (viewportHeight <= 0) return null
    val visibleBottom = currentScroll + viewportHeight
    val bottomMargin = (viewportHeight * 0.2f).roundToInt()
    if (lineBottom <= visibleBottom - bottomMargin) return null
    if (lineTop < currentScroll) return null
    val target = (lineTop - viewportHeight / 3f).roundToInt()
        .coerceIn(0, maxScroll.coerceAtLeast(0))
    return target.takeIf { it != currentScroll }
}

/**
 * Compact action used in the shared text-card header and footer.
 *
 * With a label it renders the same lavender pill used by the reader's "Colar"
 * button; without one it renders the matching circular icon button.
 */
@Composable
fun AppTextAction(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    contentDescription: String? = null,
    enabled: Boolean = true,
) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    }
    val actionModifier = if (label == null) modifier.size(32.dp) else modifier

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = actionModifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        if (label == null) {
            Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = tint,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = tint,
                )
            }
        }
    }
}

@Composable
fun AppTextStatPill(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
