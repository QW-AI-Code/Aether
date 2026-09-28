package studio.cluvex.aether.ai

/**
 * 1.4.0-r4: the Markdown the model writes, turned into STRUCTURE instead of text.
 *
 * ## Why this exists
 *
 * Up to r3 the chat ran the answer through a tiny inline pass (`**bold**` and
 * `` `code` `` only) and the "what is this setting" sheet printed the model's text
 * verbatim. Gemini answers in Markdown whether it is asked to or not, so the user
 * saw the syntax itself: `### Split tunneling`, `**۱. چیست؟**`, `*  اگر روی OFF…`,
 * a stray `*نکته:`. In a Persian (RTL) paragraph it is worse than noise - the BiDi
 * algorithm treats `#` and `*` as neutral characters and moves them to the far
 * end of the line, so `### Split tunneling` is drawn as `Split tunneling ###`.
 *
 * Stripping the characters with a regex would lose the information they carry
 * (this is a heading, this is a list, this word matters). So the text is parsed
 * into blocks and inline runs here - plain Kotlin, no Compose, unit-tested - and
 * [studio.cluvex.aether.ui.ai.AiRichText] draws them the way Gemini's own app
 * does: real headings, real bullets with hanging indents, bold without asterisks,
 * code in a monospace box, and a text direction chosen per block.
 *
 * ## Contract
 *
 * - Every marker the parser understands is REMOVED from the visible text.
 * - A marker it does not understand but that can only be Markdown debris (an
 *   unmatched `**`, a `*` glued to the front of a word, trailing `###`) is removed
 *   as well. A `*` between two word characters (`WARP*2`) is text and stays.
 * - Nothing here throws: any input, however broken, produces blocks.
 */
object AiMarkdown {

    // ---------------------------------------------------------------- model

    /** One styled run of inline text. */
    data class Run(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val strike: Boolean = false,
        /** Target of a `[text](url)` link, or null. */
        val link: String? = null,
    )

    /** One list entry. [marker] is null for bullets and the number text for ordered lists. */
    data class Item(val runs: List<Run>, val depth: Int, val marker: String?)

    sealed interface Block {
        data class Heading(val level: Int, val runs: List<Run>) : Block
        data class Paragraph(val runs: List<Run>) : Block
        data class ListBlock(val items: List<Item>, val ordered: Boolean) : Block
        data class Quote(val runs: List<Run>) : Block
        data class Code(val text: String, val language: String) : Block
        data class Table(val header: List<List<Run>>, val rows: List<List<List<Run>>>) : Block
        data object Rule : Block
    }

    // ---------------------------------------------------------------- blocks

    private val FENCE = Regex("^\\s{0,3}(```|~~~)\\s*([A-Za-z0-9_+.-]*)\\s*$")
    private val RULE = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val BULLET = Regex("^(\\s*)[-*+\u2022\u25CF\u25AA\u25E6\u2023]\\s+(.*)$")
    private val ORDERED = Regex("^(\\s*)([0-9\u06F0-\u06F9\u0660-\u0669]{1,3})[.)\u066B]\\s+(.*)$")
    private val QUOTE = Regex("^\\s{0,3}>\\s?(.*)$")
    private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    /** Parses [raw] into blocks. Never throws. */
    fun parse(raw: String): List<Block> = runCatching { parseBlocks(raw) }
        .getOrElse { listOf(Block.Paragraph(listOf(Run(raw)))) }

    private fun parseBlocks(raw: String): List<Block> {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = mutableListOf<Block>()
        val paragraph = StringBuilder()
        var i = 0

        fun flushParagraph() {
            if (paragraph.isBlank()) {
                paragraph.setLength(0)
                return
            }
            val text = paragraph.toString().trim('\n')
            paragraph.setLength(0)
            // A line that is nothing but one short bold phrase is how models write
            // a sub-title ("**1. What it is**", "**۲. کاربرد آن چیست؟**"), usually
            // with the body glued to the next line. Gemini's own app shows it as a
            // title, so it becomes a heading and splits the paragraph around it.
            val pending = StringBuilder()
            fun emitPending() {
                if (pending.isNotBlank()) out += Block.Paragraph(inline(pending.toString().trim('\n')))
                pending.setLength(0)
            }
            for (one in text.split('\n')) {
                val t = one.trim()
                if (t.length <= 90 && isWhollyBold(t)) {
                    emitPending()
                    out += Block.Heading(4, inline(t.substring(2, t.length - 2).trim()))
                } else {
                    if (pending.isNotEmpty()) pending.append('\n')
                    pending.append(one)
                }
            }
            emitPending()
        }

        while (i < lines.size) {
            val line = lines[i]

            // Fenced code: taken verbatim up to the closing fence (or the end).
            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                flushParagraph()
                val marker = fence.groupValues[1]
                val language = fence.groupValues[2]
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith(marker)) {
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(lines[i])
                    i++
                }
                i++ // closing fence
                if (body.isNotBlank()) out += Block.Code(body.toString().trimEnd(), language)
                continue
            }

            if (line.isBlank()) {
                flushParagraph()
                i++
                continue
            }

            val head = heading(line)
            if (head != null) {
                flushParagraph()
                out += Block.Heading(head.first, inline(head.second))
                i++
                continue
            }

            if (RULE.matches(line)) {
                flushParagraph()
                out += Block.Rule
                i++
                continue
            }

            // Table: a header row, a separator row, then body rows.
            if (line.trim().startsWith("|") && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1])) {
                flushParagraph()
                val header = cells(line)
                i += 2
                val rows = mutableListOf<List<List<Run>>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    rows += cells(lines[i])
                    i++
                }
                out += Block.Table(header, rows)
                continue
            }

            if (QUOTE.matches(line)) {
                flushParagraph()
                val body = StringBuilder()
                while (i < lines.size) {
                    val m = QUOTE.matchEntire(lines[i]) ?: break
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(m.groupValues[1])
                    i++
                }
                out += Block.Quote(inline(body.toString()))
                continue
            }

            if (BULLET.matches(line) || ORDERED.matches(line)) {
                flushParagraph()
                val items = mutableListOf<Item>()
                val ordered = ORDERED.matches(line)
                val baseIndent = indentOf(line)
                while (i < lines.size) {
                    val current = lines[i]
                    val b = BULLET.matchEntire(current)
                    val o = if (b == null) ORDERED.matchEntire(current) else null
                    if (b == null && o == null) {
                        // An INDENTED non-marker line under an item continues it
                        // (models wrap long bullets that way). A flush-left line is a
                        // new paragraph written without the blank line before it.
                        if (current.isNotBlank() && items.isNotEmpty() &&
                            heading(current) == null && !FENCE.matches(current) &&
                            !RULE.matches(current) && !QUOTE.matches(current) &&
                            indentOf(current) > baseIndent
                        ) {
                            val last = items.removeAt(items.size - 1)
                            items += last.copy(runs = last.runs + Run("\n") + inline(current.trim()))
                            i++
                            continue
                        }
                        break
                    }
                    val indent = (b?.groupValues?.get(1) ?: o!!.groupValues[1]).replace("\t", "    ").length
                    val depth = ((indent - baseIndent).coerceAtLeast(0) / 2).coerceAtMost(2)
                    val text = b?.groupValues?.get(2) ?: o!!.groupValues[3]
                    val marker = o?.groupValues?.get(2)
                    items += Item(inline(text), depth, marker)
                    i++
                }
                out += Block.ListBlock(items, ordered)
                continue
            }

            if (paragraph.isNotEmpty()) paragraph.append('\n')
            paragraph.append(line.trim())
            i++
        }
        flushParagraph()
        return out
    }

    private fun indentOf(line: String): Int =
        line.replace("\t", "    ").takeWhile { it == ' ' }.length

    /** `(level, text)` when [line] is an ATX heading (`## Title`, `###Title`, `Title ###`). */
    private fun heading(line: String): Pair<Int, String>? {
        val t = line.trim()
        if (!t.startsWith('#')) return null
        val hashes = t.takeWhile { it == '#' }.length
        if (hashes > 6) return null
        val rest = t.substring(hashes)
        // "#tag" is a hashtag, not a heading; "###Title" (no space) is a heading
        // the model mistyped, and there is no reading of it as anything else.
        if (rest.isEmpty() || (hashes == 1 && !rest.first().isWhitespace())) return null
        val text = rest.trim().trimEnd('#').trim()
        if (text.isEmpty()) return null
        return hashes to text
    }

    private fun isWhollyBold(s: String): Boolean {
        for (d in listOf("**", "__")) {
            if (s.length > 4 && s.startsWith(d) && s.endsWith(d)) {
                val inner = s.substring(2, s.length - 2)
                if (d !in inner) return true
            }
        }
        return false
    }

    private fun cells(line: String): List<List<Run>> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|")) t = t.substring(0, t.length - 1)
        return t.split('|').map { inline(it.trim()) }
    }

    // ---------------------------------------------------------------- inline

    private data class Style(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val link: String? = null,
    )

    private val LINK = Regex("^\\[([^\\]\\n]+)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)")

    /** Parses inline Markdown in [text] into styled runs, markers removed. */
    fun inline(text: String): List<Run> {
        val out = mutableListOf<Run>()
        runCatching { inlineInto(text, Style(), out) }
            .onFailure {
                out.clear()
                out += Run(text.replace("**", "").replace("__", ""))
            }
        return merge(out)
    }

    private fun inlineInto(s: String, style: Style, out: MutableList<Run>) {
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) {
                out += Run(buf.toString(), style.bold, style.italic, false, style.strike, style.link)
                buf.setLength(0)
            }
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                // Backslash escape: the next character is literal.
                c == '\\' && i + 1 < s.length && s[i + 1] in ESCAPABLE -> {
                    buf.append(s[i + 1])
                    i += 2
                }
                c == '`' -> {
                    val ticks = s.substring(i).takeWhile { it == '`' }.length
                    val close = s.indexOf("`".repeat(ticks), i + ticks)
                    if (close < 0) {
                        // Unmatched backtick: debris, drop it.
                        i += ticks
                    } else {
                        flush()
                        val code = s.substring(i + ticks, close).trim()
                        if (code.isNotEmpty()) out += Run(code, style.bold, style.italic, true, style.strike, style.link)
                        i = close + ticks
                    }
                }
                s.startsWith("**", i) || s.startsWith("__", i) -> {
                    val d = s.substring(i, i + 2)
                    val intraWord = d == "__" && isWordChar(s.getOrNull(i - 1))
                    val close = if (intraWord) -1 else findClose(s, d, i + 2)
                    if (intraWord) {
                        // `snake__case`: text.
                        buf.append(d)
                        i += 2
                    } else if (close < 0) {
                        // An unmatched `**` is never text a person meant to read.
                        i += 2
                    } else {
                        flush()
                        inlineInto(s.substring(i + 2, close), style.copy(bold = true), out)
                        i = close + 2
                    }
                }
                s.startsWith("~~", i) -> {
                    val close = s.indexOf("~~", i + 2)
                    if (close < 0) {
                        i += 2
                    } else {
                        flush()
                        inlineInto(s.substring(i + 2, close), style.copy(strike = true), out)
                        i = close + 2
                    }
                }
                c == '*' || c == '_' -> {
                    val prev = s.getOrNull(i - 1)
                    val next = s.getOrNull(i + 1)
                    val opens = next != null && !next.isWhitespace() && !isWordChar(prev) && next != c
                    val close = if (opens) findSingleClose(s, c, i + 1) else -1
                    when {
                        close > i + 1 -> {
                            flush()
                            inlineInto(s.substring(i + 1, close), style.copy(italic = true), out)
                            i = close + 1
                        }
                        // `WARP*2`, `snake_case`, `2 * 3`: ordinary text.
                        isWordChar(prev) && isWordChar(next) -> {
                            buf.append(c)
                            i++
                        }
                        c == '*' && (next == null || next.isWhitespace()) && (prev == null || prev.isWhitespace()) &&
                            s.substring(0, i).isNotBlank() -> {
                            // A lone " * " between words is a multiplication or a
                            // bullet glued mid-line; keep it only when it is not the
                            // first thing on the line (a leading one is debris).
                            buf.append(c)
                            i++
                        }
                        c == '*' -> i++ // `*نکته:` - a marker with no partner.
                        else -> {
                            buf.append(c)
                            i++
                        }
                    }
                }
                c == '[' -> {
                    val m = LINK.find(s.substring(i))
                    if (m == null) {
                        buf.append(c)
                        i++
                    } else {
                        flush()
                        inlineInto(m.groupValues[1], style.copy(link = m.groupValues[2]), out)
                        i += m.value.length
                    }
                }
                else -> {
                    buf.append(c)
                    i++
                }
            }
        }
        flush()
    }

    /** Index of the closing double delimiter [d] at or after [from], or -1. */
    private fun findClose(s: String, d: String, from: Int): Int {
        var j = s.indexOf(d, from)
        while (j >= 0) {
            // `****` or a closing marker preceded by a space is not a closer.
            if (j > from && !s[j - 1].isWhitespace()) return j
            j = s.indexOf(d, j + 1)
        }
        return -1
    }

    /** Index of a single [c] that can close an emphasis opened before [from], or -1. */
    private fun findSingleClose(s: String, c: Char, from: Int): Int {
        var j = from
        while (j < s.length) {
            if (s[j] == '\n') return -1
            if (s[j] == c &&
                !s[j - 1].isWhitespace() &&
                s.getOrNull(j + 1) != c &&
                s[j - 1] != c &&
                // A closer is not glued to a following word: `WARP*2` inside an
                // emphasis must not end it, and neither may `snake_case`.
                !isWordChar(s.getOrNull(j + 1))
            ) {
                return j
            }
            j++
        }
        return -1
    }

    private fun isWordChar(ch: Char?): Boolean = ch != null && (ch.isLetterOrDigit())

    private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~>"

    private fun merge(runs: List<Run>): List<Run> {
        val out = mutableListOf<Run>()
        for (r in runs) {
            if (r.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.copy(text = "") == r.copy(text = "")) {
                out[out.size - 1] = last.copy(text = last.text + r.text)
            } else {
                out += r
            }
        }
        return out
    }

    // ---------------------------------------------------------------- text

    /**
     * The answer as clean plain text: what the copy button puts on the clipboard.
     * Pasting `### ` and `**` into a messenger is the same bug in a different app.
     */
    fun toPlainText(raw: String): String = buildString {
        parse(raw).forEachIndexed { index, block ->
            if (index > 0) append("\n\n")
            when (block) {
                is Block.Heading -> append(block.runs.joinToString("") { it.text })
                is Block.Paragraph -> append(block.runs.joinToString("") { it.text })
                is Block.Quote -> append(block.runs.joinToString("") { it.text })
                is Block.Code -> append(block.text)
                is Block.Rule -> append("\u2014\u2014\u2014")
                is Block.ListBlock -> block.items.forEachIndexed { n, item ->
                    if (n > 0) append('\n')
                    append("  ".repeat(item.depth))
                    append(if (item.marker != null) "${item.marker}. " else "\u2022 ")
                    append(item.runs.joinToString("") { it.text })
                }
                is Block.Table -> {
                    append(block.header.joinToString(" | ") { c -> c.joinToString("") { it.text } })
                    block.rows.forEach { row ->
                        append('\n')
                        append(row.joinToString(" | ") { c -> c.joinToString("") { it.text } })
                    }
                }
            }
        }
    }

    /** Inline-only cleanup for one-line fields (a change's reason, a summary). */
    fun plainInline(raw: String): String = inline(raw.trim().removePrefix("#").trimStart('#', ' '))
        .joinToString("") { it.text }

    // ---------------------------------------------------------------- direction

    /** Letters of right-to-left scripts (Arabic, Persian, Hebrew, presentation forms). */
    fun isRtlLetter(ch: Char): Boolean {
        val c = ch.code
        return (c in 0x0590..0x08FF || c in 0xFB1D..0xFDFF || c in 0xFE70..0xFEFF) && ch.isLetter()
    }

    /**
     * True when [text] should be laid out right-to-left.
     *
     * Decided by the MAJORITY of strong letters, not by the first one, which is
     * what `TextDirection.Content` does: a Persian sentence that happens to start
     * with "Split tunneling" is still a Persian sentence and must be right-aligned.
     * [fallbackRtl] decides text with no letters at all (numbers, symbols).
     */
    fun isRtl(text: String, fallbackRtl: Boolean): Boolean {
        var rtl = 0
        var ltr = 0
        for (ch in text) {
            if (isRtlLetter(ch)) rtl++ else if (ch.isLetter()) ltr++
        }
        return when {
            rtl == 0 && ltr == 0 -> fallbackRtl
            rtl == 0 -> false
            ltr == 0 -> true
            // English words cost ~1.3x the letters of their Persian counterpart,
            // and technical answers are full of them; a Persian paragraph with a
            // lot of MASQUE / WireGuard / MTU in it is still Persian.
            else -> rtl * 2 >= ltr
        }
    }
}
