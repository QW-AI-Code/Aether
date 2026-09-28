package studio.cluvex.aether.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection

/**
 * ROOT-CAUSE FIX for the "digits get shuffled while typing" bug in every
 * technical field (ip:port endpoints, CIDR ranges, URLs).
 *
 * The bug had TWO independent root causes, which is why it reproduced in
 * BOTH the English and the Persian locale:
 *
 * 1. ASYNC STATE ECHO (all locales — the actual reason English scrambled
 *    too): the field used to be driven directly by profile state that
 *    round-trips through DataStore (keystroke -> async save -> flow emits
 *    back). While typing fast, a keystroke was applied on top of a STALE
 *    value that echoed back from disk a moment later, so characters were
 *    dropped/reordered and the cursor jumped ("127.0.0.1" -> "27.0.0.11").
 *    Defense here (in addition to the synchronous state fix in
 *    MainActivity): the field owns a local [TextFieldValue] while focused —
 *    the single source of truth for what the user is typing. External
 *    changes (initial load, "reset settings") are only accepted while the
 *    field is NOT focused, so no async echo can ever clobber a keystroke.
 *
 * 2. BiDi RENDERING (Persian locale): the Unicode BiDi algorithm treats
 *    digits, dots and colons as direction-NEUTRAL, so inside an RTL layout
 *    a value like `162.159.192.1:443` gets visually reordered around the
 *    RTL base direction while typing. ip:port / CIDR / URLs are inherently
 *    LTR, so this field pins the TEXT direction and the alignment of the
 *    value to LTR.
 *
 *    It does NOT pin the LAYOUT direction any more. Until 1.3.1-r2 the whole
 *    field was wrapped in `CompositionLocalProvider(LocalLayoutDirection
 *    provides Ltr)`, and that took the decorations with it: `label`,
 *    `placeholder` and `supportingText` are Persian PROSE, and inside an LTR
 *    paragraph the trailing full stop of a Persian sentence is a neutral
 *    character that resolves to the paragraph direction — so it was rendered
 *    at the RIGHT-hand end of the text and the whole block sat left-aligned
 *    among right-aligned neighbours. That was reported on the MTU helper text
 *    ("هر مقداری بین ۱۲۸۰ و ۹۰۰۰. … کمترش کنید.") and applied to every
 *    technical field in the app.
 *
 *    The value itself is unaffected by that change: what governs the BiDi
 *    reordering of the field's CONTENT is the paragraph direction of the text,
 *    which is [TextDirection.Ltr] from the text style below, not the layout
 *    direction of the box around it. [TextAlign.Left] keeps it visually where
 *    it has always been — `TextAlign.Start` would flip to the right edge under
 *    an RTL layout.
 *
 * Additionally, every character is normalized on input: Persian (۰-۹) and
 * Arabic-Indic (٠-٩) digits become ASCII 0-9, Arabic separators become '.'
 * and ',', and invisible BiDi control marks that some keyboards insert are
 * stripped — so a value typed with a Persian keyboard is still a valid
 * ip:port for the engine.
 *
 * Use this for EVERY technical text input in the app — never a bare
 * [OutlinedTextField] — so the bug cannot come back in any screen.
 */
@Composable
fun LtrOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    /**
     * Added in 1.3.0: a technical field whose value the engine would silently
     * reinterpret needs to be able to say so. The Tor reachability check is the
     * first: the engine parses `host:port` and falls back to port 443 on a bad
     * port, so a typo becomes a DIFFERENT target rather than an error - and that
     * setting exists precisely to tell a working Tor from a broken one.
     */
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Ascii,
    // Added in 1.2.3: lets Zero Trust secrets be masked while still getting the
    // LTR/BiDi handling every technical field in this app needs.
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    // Local, synchronous source of truth while the user is typing.
    var fieldValue by remember { mutableStateOf(TextFieldValue(normalizeTechnicalText(value))) }

    // Accept external value changes (initial profile load, reset-to-defaults)
    // ONLY while the field is not focused. While focused, keystrokes rule —
    // a stale async echo of an older value can never overwrite them.
    if (!isFocused && fieldValue.text != value) {
        fieldValue = TextFieldValue(value, TextRange(value.length))
    }

    OutlinedTextField(
        value = fieldValue,
        onValueChange = { raw ->
            val normalized = normalizeTechnicalValue(raw)
            fieldValue = normalized
            if (normalized.text != value) onValueChange(normalized.text)
        },
        modifier = modifier,
        enabled = enabled,
        singleLine = singleLine,
        label = label,
        placeholder = placeholder,
        supportingText = supportingText,
        isError = isError,
        interactionSource = interactionSource,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = LocalTextStyle.current.copy(
            textDirection = TextDirection.Ltr,
            // Left, not Start: the box now follows the app's layout direction, so
            // Start would put an ip:port at the right edge in Persian.
            textAlign = TextAlign.Left,
        ),
    )
}

/** Maps localized digits/separators to their ASCII equivalents. */
private fun normalizeTechnicalChar(c: Char): Char = when (c) {
    in '\u06F0'..'\u06F9' -> '0' + (c - '\u06F0') // Persian digits ۰..۹
    in '\u0660'..'\u0669' -> '0' + (c - '\u0660') // Arabic-Indic digits ٠..٩
    '\u066B' -> '.' // Arabic decimal separator ٫
    '\u060C' -> ',' // Arabic comma ،
    else -> c
}

/** Invisible BiDi control marks some RTL keyboards insert around neutrals. */
private fun isBidiControl(c: Char): Boolean =
    c == '\u200E' || c == '\u200F' || c == '\u061C' ||
        (c in '\u202A'..'\u202E') || (c in '\u2066'..'\u2069')

private fun normalizeTechnicalText(text: String): String =
    buildString(text.length) {
        for (c in text) if (!isBidiControl(c)) append(normalizeTechnicalChar(c))
    }

/**
 * Normalizes the full [TextFieldValue], keeping the cursor/selection in the
 * right place when invisible control characters are stripped.
 */
private fun normalizeTechnicalValue(value: TextFieldValue): TextFieldValue {
    val text = value.text
    var changed = false
    var selStart = value.selection.start
    var selEnd = value.selection.end
    val sb = StringBuilder(text.length)
    text.forEachIndexed { i, c ->
        if (isBidiControl(c)) {
            changed = true
            if (i < value.selection.start) selStart--
            if (i < value.selection.end) selEnd--
        } else {
            val n = normalizeTechnicalChar(c)
            if (n != c) changed = true
            sb.append(n)
        }
    }
    if (!changed) return value
    val out = sb.toString()
    return TextFieldValue(
        text = out,
        selection = TextRange(
            selStart.coerceIn(0, out.length),
            selEnd.coerceIn(0, out.length),
        ),
    )
}
