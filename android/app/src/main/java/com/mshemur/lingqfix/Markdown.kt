package com.mshemur.lingqfix

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/** Blocks of a Markdown answer (what Gemini writes): enough for chat, not a full CommonMark parser. */
private sealed interface MdBlock {
    class Para(val text: String) : MdBlock
    class Heading(val level: Int, val text: String) : MdBlock
    class Item(val indent: Int, val marker: String, val text: String) : MdBlock
    class Quote(val text: String) : MdBlock
    class Code(val text: String) : MdBlock
    data object Rule : MdBlock
}

private val HEADING = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
private val BULLET = Regex("""^(\s*)[-*+•]\s+(.*)$""")
private val NUMBERED = Regex("""^(\s*)(\d+[.)])\s+(.*)$""")
private val RULE = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")

private fun parseBlocks(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val para = mutableListOf<String>()
    fun flush() { if (para.isNotEmpty()) { out += MdBlock.Para(para.joinToString("\n")); para.clear() } }
    val lines = src.replace("\r\n", "\n").split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        when {
            t.startsWith("```") -> {
                flush()
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) { code += lines[i]; i++ }
                out += MdBlock.Code(code.joinToString("\n"))
            }
            t.isEmpty() -> flush()
            RULE.matches(line) -> { flush(); out += MdBlock.Rule }
            HEADING.matches(t) -> {
                flush()
                val m = HEADING.find(t)!!
                out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
            }
            BULLET.matches(line) -> {
                flush()
                val m = BULLET.find(line)!!
                out += MdBlock.Item(m.groupValues[1].length / 2, "•", m.groupValues[2])
            }
            NUMBERED.matches(line) -> {
                flush()
                val m = NUMBERED.find(line)!!
                out += MdBlock.Item(m.groupValues[1].length / 2, m.groupValues[2], m.groupValues[3])
            }
            t.startsWith(">") -> { flush(); out += MdBlock.Quote(t.trimStart('>', ' ')) }
            else -> para += line
        }
        i++
    }
    flush()
    return out
}

// **bold**, __bold__, *italic*, _italic_, `code`, ~~strike~~, [link](url)
private val INLINE = Regex(
    """\*\*(.+?)\*\*|__(.+?)__|(?<![\p{L}\p{N}*])\*(?!\s)(.+?)(?<!\s)\*(?![\p{L}\p{N}*])""" +
        """|(?<![\p{L}\p{N}_])_(?!\s)(.+?)(?<!\s)_(?![\p{L}\p{N}_])|`([^`]+)`|~~(.+?)~~|\[([^\]]+)]\(([^)\s]+)\)""",
)

private fun inline(text: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    fun walk(t: String) {
        var pos = 0
        for (m in INLINE.findAll(t)) {
            append(t.substring(pos, m.range.first))
            val g = m.groupValues
            when {
                g[1].isNotEmpty() || g[2].isNotEmpty() -> {
                    val p = pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); walk(g[1].ifEmpty { g[2] }); pop(p)
                }
                g[3].isNotEmpty() || g[4].isNotEmpty() -> {
                    val p = pushStyle(SpanStyle(fontStyle = FontStyle.Italic)); walk(g[3].ifEmpty { g[4] }); pop(p)
                }
                g[5].isNotEmpty() -> {
                    val p = pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)); append(g[5]); pop(p)
                }
                g[6].isNotEmpty() -> {
                    val p = pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)); walk(g[6]); pop(p)
                }
                else -> {
                    val p = pushStyle(SpanStyle(textDecoration = TextDecoration.Underline)); walk(g[7]); pop(p)
                }
            }
            pos = m.range.last + 1
        }
        append(t.substring(pos))
    }
    walk(text)
}

/** Shows Gemini's Markdown as formatted text: headings, bold/italic, lists, quotes, code. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    val blocks = remember(text) { parseBlocks(text) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val outline = MaterialTheme.colorScheme.outline
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in blocks) when (b) {
            is MdBlock.Para -> Text(inline(b.text, codeBg), style = style)
            is MdBlock.Heading -> Text(
                inline(b.text, codeBg),
                style = (if (b.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall)
                    .copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(top = 4.dp),
            )
            is MdBlock.Item -> Row(Modifier.padding(start = (b.indent * 16).dp)) {
                Text(b.marker, style = style, modifier = Modifier.width(if (b.marker == "•") 16.dp else 26.dp))
                Text(inline(b.text, codeBg), style = style, modifier = Modifier.weight(1f))
            }
            is MdBlock.Quote -> Row {
                Text("▍", style = style, color = outline)
                Text(inline(b.text, codeBg), style = style.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is MdBlock.Code -> Text(
                b.text, style = style.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().background(codeBg, RoundedCornerShape(8.dp)).padding(10.dp),
            )
            MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
        }
    }
}
