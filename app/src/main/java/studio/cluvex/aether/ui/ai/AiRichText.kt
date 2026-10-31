package studio.cluvex.aether.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import studio.cluvex.aether.ai.AiMarkdown
import studio.cluvex.aether.data.LanguagePrefs
import studio.cluvex.aether.ui.theme.AetherBlue
import studio.cluvex.aether.ui.theme.AetherViolet
import studio.cluvex.aether.ui.theme.Navy700
import studio.cluvex.aether.ui.theme.Navy900
import studio.cluvex.aether.ui.theme.OnDark
import studio.cluvex.aether.ui.theme.OnDarkMuted

/**
 * 1.4.0-r4: renders a model answer the way Gemini's own app does.
 *
 * The Markdown is parsed by [AiMarkdown] (pure Kotlin, unit-tested) and drawn
 * here as real structure - headings, bullets with a hanging indent, numbered
 * steps, bold and code without their asterisks and backticks, quotes, tables and
 * code blocks. No Markdown character reaches the screen.
 *
 * ## Direction, per block
 *
 * Each block is laid out in ITS OWN direction, decided by the majority of its
 * letters ([AiMarkdown.isRtl]) and wrapped in a matching [LocalLayoutDirection]:
 * a Persian paragraph is right-aligned with its bullet on the right, an English
 * code block or an English-only answer is left-aligned with its bullet on the
 * left. The answer's overall direction is the tie-breaker for blocks too short or
 * too neutral to decide on their own (a heading that is just "Split tunneling"
 * inside a Persian answer stays on the right with the rest of it). The paragraph
 * direction handed to the BiDi algorithm always matches the layout, which is what
 * stops `#`, `*`, `:` and `?` from migrating to the wrong end of a line.
 *
 * @param text the raw model answer.
 * @param style the body style; headings are derived from it.
 * @param color body text colour.
 */
@Composable
fun AiRichText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = OnDark,
) {
    val context = LocalContext.current
    val uiRtl = remember { LanguagePrefs.isPersian(context) }
    val blocks = remember(text) { AiMarkdown.parse(text) }
    val answerRtl = remember(text, uiRtl) { AiMarkdown.isRtl(text, uiRtl) }
    // Gemini-like reading rhythm: Vazirmatn's natural line box is ~1.56 em, so
    // the Material default (20sp on 14sp) packs Persian lines too tightly.
    val body = style.copy(lineHeight = 1.7.em, color = color)

    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            val gap = when {
                index == 0 -> 0.dp
                block is AiMarkdown.Block.Heading -> 14.dp
                else -> 8.dp
            }
            if (gap > 0.dp) Spacer(Modifier.height(gap))
            RichBlock(block = block, body = body, color = color, answerRtl = answerRtl)
        }
    }
}

@Composable
private fun RichBlock(
    block: AiMarkdown.Block,
    body: TextStyle,
    color: Color,
    answerRtl: Boolean,
) {
    when (block) {
        is AiMarkdown.Block.Heading -> Directional(runsText(block.runs), answerRtl) { rtl ->
            val base = when (block.level) {
                1, 2 -> MaterialTheme.typography.titleMedium
                3 -> MaterialTheme.typography.titleSmall
                else -> MaterialTheme.typography.bodyLarge
            }
            RunText(
                runs = block.runs,
                style = base.copy(
                    fontWeight = FontWeight.Bold,
                    color = color,
                    lineHeight = 1.5.em,
                ),
                rtl = rtl,
            )
        }

        is AiMarkdown.Block.Paragraph -> Directional(runsText(block.runs), answerRtl) { rtl ->
            RunText(runs = block.runs, style = body, rtl = rtl)
        }

        is AiMarkdown.Block.ListBlock -> {
            val all = block.items.joinToString("\n") { runsText(it.runs) }
            Directional(all, answerRtl) { rtl ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    block.items.forEach { item ->
                        Row(modifier = Modifier.padding(start = (item.depth * 18).dp)) {
                            // The marker column has a fixed width so wrapped lines
                            // hang under the text, not under the bullet.
                            Box(
                                modifier = Modifier.widthIn(min = 20.dp),
                                contentAlignment = Alignment.TopStart,
                            ) {
                                Text(
                                    text = if (item.marker != null) {
                                        localDigits(item.marker, rtl) + "."
                                    } else if (item.depth == 0) {
                                        "\u2022"
                                    } else {
                                        "\u25E6"
                                    },
                                    style = body.copy(
                                        fontWeight = if (item.marker != null) FontWeight.SemiBold else FontWeight.Bold,
                                        color = if (item.marker != null) color else AetherViolet,
                                        textDirection = if (rtl) TextDirection.Rtl else TextDirection.Ltr,
                                    ),
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            RunText(
                                runs = item.runs,
                                style = body,
                                rtl = rtl,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        is AiMarkdown.Block.Quote -> Directional(runsText(block.runs), answerRtl) { rtl ->
            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(AetherViolet.copy(alpha = 0.6f)),
                )
                Spacer(Modifier.width(10.dp))
                RunText(
                    runs = block.runs,
                    style = body.copy(color = OnDarkMuted),
                    rtl = rtl,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        is AiMarkdown.Block.Code -> CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Navy900)
                    .border(0.5.dp, Navy700, RoundedCornerShape(12.dp))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    text = block.text,
                    style = body.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = body.fontSize * 0.9f,
                        lineHeight = 1.5.em,
                        textDirection = TextDirection.Ltr,
                    ),
                    softWrap = false,
                )
            }
        }

        is AiMarkdown.Block.Table -> {
            val all = (listOf(block.header) + block.rows)
                .joinToString("\n") { row -> row.joinToString(" ") { runsText(it) } }
            Directional(all, answerRtl) { rtl ->
                val columns = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(0.5.dp, Navy700, RoundedCornerShape(12.dp)),
                ) {
                    TableRow(block.header, columns, body.copy(fontWeight = FontWeight.SemiBold), rtl, header = true)
                    block.rows.forEach { row ->
                        HorizontalDivider(thickness = 0.5.dp, color = Navy700)
                        TableRow(row, columns, body, rtl, header = false)
                    }
                }
            }
        }

        AiMarkdown.Block.Rule -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            thickness = 0.5.dp,
            color = Navy700,
        )
    }
}

@Composable
private fun TableRow(
    cells: List<List<AiMarkdown.Run>>,
    columns: Int,
    style: TextStyle,
    rtl: Boolean,
    header: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (header) Modifier.background(Navy900) else Modifier),
    ) {
        for (c in 0 until columns) {
            RunText(
                runs = cells.getOrNull(c) ?: emptyList(),
                style = style.copy(fontSize = style.fontSize * 0.92f),
                rtl = rtl,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp, vertical = 7.dp),
            )
        }
    }
}

/**
 * Lays [content] out in the direction [sample] reads in.
 *
 * Short or neutral blocks follow the answer ([answerRtl]); only a block that is
 * substantially in the other script flips, so one English sentence in a Persian
 * answer is left-aligned while an English product name used as a heading is not.
 */
@Composable
private fun Directional(
    sample: String,
    answerRtl: Boolean,
    content: @Composable (rtl: Boolean) -> Unit,
) {
    val rtl = remember(sample, answerRtl) {
        val letters = sample.count { it.isLetter() }
        if (letters < 24) {
            // Too little text to overrule the answer's own direction.
            answerRtl
        } else {
            AiMarkdown.isRtl(sample, answerRtl)
        }
    }
    CompositionLocalProvider(
        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        content(rtl)
    }
}

@Composable
private fun RunText(
    runs: List<AiMarkdown.Run>,
    style: TextStyle,
    rtl: Boolean,
    modifier: Modifier = Modifier,
) {
    val annotated = remember(runs) { toAnnotated(runs) }
    Text(
        text = annotated,
        style = style.copy(
            textDirection = if (rtl) TextDirection.Rtl else TextDirection.Ltr,
            textAlign = TextAlign.Start,
        ),
        modifier = modifier,
    )
}

private fun runsText(runs: List<AiMarkdown.Run>): String = runs.joinToString("") { it.text }

private fun toAnnotated(runs: List<AiMarkdown.Run>): AnnotatedString = buildAnnotatedString {
    runs.forEach { run ->
        val span = SpanStyle(
            fontWeight = if (run.bold) FontWeight.Bold else null,
            fontStyle = if (run.italic) FontStyle.Italic else null,
            fontFamily = if (run.code) FontFamily.Monospace else null,
            background = if (run.code) AetherBlue.copy(alpha = 0.14f) else Color.Unspecified,
            color = when {
                run.link != null -> AetherBlue
                run.code -> Color(0xFFBFD3FF)
                else -> Color.Unspecified
            },
            textDecoration = when {
                run.strike -> TextDecoration.LineThrough
                run.link != null -> TextDecoration.Underline
                else -> null
            },
        )
        if (run.code) {
            // Technical tokens (1280, X25519:P-256, ip:port) are LTR islands; the
            // isolate marks keep them intact inside a Persian sentence.
            withStyle(span) {
                append("\u2066")
                append(run.text)
                append("\u2069")
            }
        } else {
            withStyle(span) { append(run.text) }
        }
    }
}

/** Persian digits for list numbers in a right-to-left block, like the rest of the UI. */
private fun localDigits(raw: String, rtl: Boolean): String {
    if (!rtl) {
        return buildString {
            raw.forEach { ch ->
                append(
                    when (ch) {
                        in '\u06F0'..'\u06F9' -> '0' + (ch - '\u06F0')
                        in '\u0660'..'\u0669' -> '0' + (ch - '\u0660')
                        else -> ch
                    },
                )
            }
        }
    }
    return buildString {
        raw.forEach { ch -> append(if (ch in '0'..'9') '\u06F0' + (ch - '0') else ch) }
    }
}
