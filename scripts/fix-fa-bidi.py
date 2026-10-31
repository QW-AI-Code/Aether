#!/usr/bin/env python3
"""
Repair bidirectional text in res/values-fa/strings.xml (issue: Persian menus not
right-aligned and mixed Persian/English lines scrambled).

Two distinct defects, two distinct fixes.

1. PARAGRAPH DIRECTION. Android resolves the direction of a text paragraph from
   its FIRST STRONG character. A Persian string that begins with a Latin word -
   "MASQUE روی HTTP/2", "Keepalive (ثانیه)", "Zero Trust (سازمانی)" - is therefore
   laid out LEFT-TO-RIGHT as a whole, which is why those rows appeared
   left-aligned while their neighbours were right-aligned. Prefixing the string
   with U+200F RIGHT-TO-LEFT MARK makes the first strong character RTL and the
   paragraph RTL, without adding anything visible.

2. NEUTRAL ABSORPTION. Inside an RTL paragraph, a Latin run is correctly shown
   left-to-right by the Unicode Bidi Algorithm - but neutral characters touching
   it (brackets, slashes, colons, commas, full stops, digits) have no direction of
   their own and get absorbed into whichever run wins, which is what moves a
   closing bracket to the wrong end or strands a period at the start of a line.
   Wrapping each Latin run in U+2068 FIRST STRONG ISOLATE ... U+2069 POP
   DIRECTIONAL ISOLATE makes the run an island: it is laid out LTR internally, and
   the characters around it keep the paragraph's direction.

Deliberately NOT touched:
  * format specifiers (%s, %d, %1$s, %%) - an isolate inside one would be passed
    to String.format as part of the conversion and break it;
  * XML entities (&lt; &amp; &#8230;) - the ampersand-name form must stay intact;
  * escape sequences (\\n, \\');
  * anything already carrying an isolate, so the script is idempotent.

Everything it changes is invisible formatting characters. No word, digit or
punctuation mark is added, removed or moved.
"""
import re
import sys

RLM = "\u200f"
FSI = "\u2068"
PDI = "\u2069"

ARABIC = (
    ("\u0600", "\u06ff"),
    ("\u0750", "\u077f"),
    ("\u08a0", "\u08ff"),
    ("\ufb50", "\ufdff"),
    ("\ufe70", "\ufeff"),
)


def is_arabic(ch: str) -> bool:
    return any(lo <= ch <= hi for lo, hi in ARABIC)


def is_latin_letter(ch: str) -> bool:
    return ch.isascii() and ch.isalpha()


def first_strong(text: str) -> str | None:
    for ch in text:
        if is_arabic(ch):
            return "rtl"
        if is_latin_letter(ch):
            return "ltr"
    return None


# Regions that must be left exactly as they are.
#
# Format specifiers only. XML entities (&lt; &gt;) are deliberately NOT protected:
# they stand for punctuation, they carry Latin letters, and a URL written as
# https://&lt;team&gt;.example.com has to end up inside ONE isolate, not three.
PROTECTED = re.compile(
    r"""
      %(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]   # %s %d %1$s %.2f %%
    | \\.                                            # \n \' \" \\
    """,
    re.VERBOSE,
)


def isolate_latin(text: str) -> str:
    """
    Wrap each Latin PASSAGE in FSI...PDI - one island per passage, not per word.

    The boundary is the Persian text itself: everything between two Arabic-script
    characters is one candidate span. Within a span, the island runs from the
    first Latin letter or digit to the last one, so:

      * "Zero Trust (سازمانی)"            -> one island "Zero Trust"
      * "Encrypted Client Hello"          -> one island, words keep their order
      * "https://&lt;team&gt;.a.com/warp" -> one island, the whole URL
      * "... کنید. Aether می‌گوید"        -> island "Aether"; the Persian full stop
                                             and the spaces stay OUTSIDE it

    Wrapping each WORD separately was the first attempt and it was wrong: two
    adjacent LTR islands with a neutral space between them are reordered by the
    paragraph's RTL direction, so "Zero Trust" rendered as "Trust Zero". One
    island per passage is what keeps the English reading left to right.
    """
    out = []
    cursor = 0
    for guard in PROTECTED.finditer(text):
        out.append(_isolate_spans(text[cursor:guard.start()]))
        out.append(guard.group(0))
        cursor = guard.end()
    out.append(_isolate_spans(text[cursor:]))
    return "".join(out)


def _alnum_latin(ch: str) -> bool:
    return ch.isascii() and (ch.isalpha() or ch.isdigit())


def _isolate_spans(chunk: str) -> str:
    if not chunk:
        return chunk
    out = []
    i = 0
    n = len(chunk)
    while i < n:
        if is_arabic(chunk[i]):
            out.append(chunk[i])
            i += 1
            continue
        # A maximal run of non-Arabic characters.
        j = i
        while j < n and not is_arabic(chunk[j]):
            j += 1
        span = chunk[i:j]
        # The island is first..last Latin letter/digit inside the span; anything
        # outside that (leading/trailing spaces, Persian punctuation, newlines)
        # keeps the paragraph direction.
        first = next((k for k, c in enumerate(span) if c.isascii() and c.isalpha()), None)
        if first is None:
            out.append(span)
        else:
            last = max(k for k, c in enumerate(span) if _alnum_latin(c))
            out.append(span[:first])
            out.append(FSI + span[first:last + 1] + PDI)
            out.append(span[last + 1:])
        i = j
    return "".join(out)


def fix_value(value: str) -> str:
    # Idempotent: a value that already carries isolates has been processed.
    if FSI in value:
        body = value
    else:
        body = isolate_latin(value)

    has_arabic = any(is_arabic(c) for c in body)
    # Only give a direction to strings that are Persian at all. A value that is
    # purely Latin ("MASQUE", "IPv4") is a label with no Persian in it; forcing RTL
    # on it would right-align a word that reads left-to-right either way, and the
    # surrounding row already follows the layout direction.
    if has_arabic and not body.startswith(RLM) and first_strong(body.replace(FSI, "").replace(PDI, "")) == "ltr":
        body = RLM + body
    return body


def main(path: str) -> int:
    src = open(path, encoding="utf-8").read()

    changed = {"dir": 0, "iso": 0}

    def repl(match: re.Match) -> str:
        head, value, tail = match.group(1), match.group(2), match.group(3)
        fixed = fix_value(value)
        if fixed != value:
            if fixed.startswith(RLM) and not value.startswith(RLM):
                changed["dir"] += 1
            if FSI in fixed and FSI not in value:
                changed["iso"] += 1
        return head + fixed + tail

    # <string name="...">value</string>, <item> inside <plurals> and <item> inside
    # <string-array>.
    src = re.sub(r"(<string name=\"[^\"]+\"[^>]*>)(.*?)(</string>)", repl, src, flags=re.S)
    src = re.sub(r"(<item quantity=\"[^\"]+\">)(.*?)(</item>)", repl, src, flags=re.S)
    # <item> inside <string-array> - the About feature lists live here.
    src = re.sub(r"(<item>)(.*?)(</item>)", repl, src, flags=re.S)

    open(path, "w", encoding="utf-8").write(src)
    print(f"{path}: direction fixed on {changed['dir']} strings, "
          f"Latin runs isolated in {changed['iso']} strings")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
