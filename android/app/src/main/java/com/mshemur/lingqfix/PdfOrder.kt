package com.mshemur.lingqfix

import org.json.JSONArray
import org.json.JSONObject

/*
 * PDF book pages: ML Kit's words put into reading order (port of web/src/ocrorder.ts, web v1.72 / v1.76).
 * ML Kit gives the word boxes; Gemini looks at the page picture and the numbered words and answers the reading order
 * (blocks of word numbers, each a heading / body / caption / other) and corrections of misread words.
 * Kinds: 'b' body, 'h' heading, 'c' caption, 'o' other (page numbers, boxes, labels in pictures); null = body.
 */
object PdfOrder {
    const val PROMPT =
        "The picture is one scanned page of a magazine or book. OCR found the words listed below: each row is " +
            "one printed line as OCR read it, every word has its number in front (\"12 Wort\").\n\n" +
            "Task 1, reading order. Group ALL the numbered words into blocks the way a reader reads this page and list the " +
            "blocks in reading order: the title and the article first (columns one after another; a paragraph that goes on " +
            "in the next column stays one block), then the captions and the rest. Each block has a kind:\n" +
            "- \"heading\": title, subtitle, section heading;\n" +
            "- \"body\": the running text of the article, also the intro / lead paragraph;\n" +
            "- \"caption\": text that belongs to a photo or illustration, photo credits;\n" +
            "- \"other\": page numbers, running headers / footers, ads, labels in pictures and infographics, OCR noise from pictures.\n" +
            "In \"ids\" give the word numbers of the block in reading order; write a run of consecutive numbers as a pair " +
            "[first, last], a single word as a number. Every number appears exactly once.\n\n" +
            "Task 2, corrections. Where OCR misread a word (compare with the picture), give the word as printed: " +
            "\"fix\": {\"17\": \"Straße\"}. A word stays one word (no spaces), punctuation stays attached as printed, a hyphen at " +
            "the end of a line stays. Leave correct words out.\n\n" +
            "Answer JSON only: {\"blocks\":[{\"kind\":\"heading\",\"ids\":[[0,4]]},{\"kind\":\"body\",\"ids\":[[5,120],[131,240]]}," +
            "{\"kind\":\"caption\",\"ids\":[[121,130]]}],\"fix\":{\"17\":\"Straße\"}}\n\nWords:\n{words}"

    /** Gemini's answer, checked: blocks of word indexes in reading order, corrected words by index. */
    class Order(val blocks: List<Pair<Char, List<Int>>>, val fix: Map<Int, String>)

    /** The words for the prompt: one row per OCR line, "12 Wort 13 Wort". */
    fun prompt(ws: List<PdfWord>): String {
        val rows = ArrayList<String>()
        val row = StringBuilder()
        ws.forEachIndexed { i, w ->
            if (i > 0 && w.line != ws[i - 1].line) { rows += row.toString(); row.clear() }
            if (row.isNotEmpty()) row.append(' ')
            row.append(i).append(' ').append(w.t)
        }
        if (row.isNotEmpty()) rows += row.toString()
        return PROMPT.replace("{words}", rows.joinToString("\n"))
    }

    private val KINDS = mapOf(
        "body" to 'b', "text" to 'b', "paragraph" to 'b', "lead" to 'b', "intro" to 'b',
        "heading" to 'h', "title" to 'h', "subtitle" to 'h', "headline" to 'h',
        "caption" to 'c', "other" to 'o',
    )

    /**
     * Gemini's answer for [words], checked; null when it is not usable (no JSON, too many bad numbers, fewer than
     * 85 % of the words placed). Repeated numbers count once; words left out go to a last "other" block.
     */
    fun parse(raw: String, words: List<PdfWord>): Order? {
        val n = words.size
        val o = runCatching { JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)) }.getOrNull() ?: return null
        val arr = o.optJSONArray("blocks") ?: return null
        val seen = BooleanArray(n)
        var bad = 0; var placed = 0
        val blocks = ArrayList<Pair<Char, List<Int>>>()
        fun isId(v: Int) = v in 0 until n
        val range = Regex("^\\s*(\\d+)\\s*[-–]\\s*(\\d+)\\s*$")
        val single = Regex("^\\s*\\d+\\s*$")
        for (bi in 0 until arr.length()) {
            val b = arr.optJSONObject(bi)
            val ids = b?.optJSONArray("ids")
            if (b == null || ids == null) { bad++; continue }
            val kind = KINDS[b.optString("kind").lowercase().trim()] ?: 'o'
            val out = ArrayList<Int>()
            fun add(v: Int) {
                if (!isId(v) || seen[v]) { bad++; return }
                seen[v] = true; out += v; placed++
            }
            for (k in 0 until ids.length()) {
                val e = ids.opt(k)
                var a: Int; var z: Int
                when {
                    e is JSONArray && e.length() == 2 -> {
                        a = (e.opt(0) as? Number)?.toInt() ?: e.optString(0).trim().toIntOrNull() ?: -1
                        z = (e.opt(1) as? Number)?.toInt() ?: e.optString(1).trim().toIntOrNull() ?: -1
                    }
                    e is String && range.matches(e) -> {
                        val m = range.find(e)!!; a = m.groupValues[1].toInt(); z = m.groupValues[2].toInt()
                    }
                    e is Number -> { add(e.toInt()); continue }
                    e is String && single.matches(e) -> { add(e.trim().toInt()); continue }
                    else -> { bad++; continue }
                }
                if (!isId(a) || !isId(z) || z < a) { bad++; continue }
                for (v in a..z) add(v)
            }
            if (out.isNotEmpty()) blocks += kind to out
        }
        if (n == 0 || placed < n * 0.85 || bad > maxOf(3.0, n * 0.05)) return null
        val rest = (0 until n).filter { !seen[it] }
        if (rest.isNotEmpty()) blocks += 'o' to rest
        val fix = HashMap<Int, String>()
        o.optJSONObject("fix")?.let { f ->
            for (k in f.keys()) {
                val id = k.trim().toIntOrNull() ?: continue
                if (!isId(id)) continue
                val v = f.opt(k) as? String ?: continue
                val t = v.trim()
                val was = words[id].t
                if (t.isEmpty() || t.any { it.isWhitespace() } || t.length > was.length * 2 + 4 || t == was) continue
                fix[id] = t
            }
        }
        return Order(blocks, fix)
    }

    /** The text ends a sentence here (". ! ? …" or a closing quote mark). */
    private fun endsSentence(t: String) = t.isNotEmpty() && t.last() in ".!?…»«\"“”„"
    fun isAside(k: Char?) = k == 'c' || k == 'o'

    /**
     * Captions and other blocks between body blocks: one standing where the body text before it has not ended its
     * sentence moves after the next body block that does end one, or before the next heading, or to the page end.
     * Headings and body keep their order. [last]: the last word of each block. New order of block indexes (idempotent).
     */
    fun asideOrder(kinds: List<Char?>, last: List<String>): List<Int> {
        val out = ArrayList<Int>()
        var pending = ArrayList<Int>()
        var open = false
        kinds.forEachIndexed { i, k ->
            if (isAside(k)) { if (open) pending.add(i) else out.add(i); return@forEachIndexed }
            if (k == 'h') { out.addAll(pending); out.add(i); pending = ArrayList(); open = false; return@forEachIndexed }
            out.add(i)
            open = !endsSentence(last.getOrElse(i) { "" })
            if (!open) { out.addAll(pending); pending = ArrayList() }
        }
        out.addAll(pending)
        return out
    }

    /** Word indexes of a page's words (blocks = runs of the same block number) with asides moved as in [asideOrder]. */
    fun asideWordOrder(ws: List<PdfWord>): List<Int> {
        val runs = ArrayList<ArrayList<Int>>()
        ws.forEachIndexed { i, w ->
            if (i == 0 || w.block != ws[i - 1].block) runs.add(ArrayList())
            runs.last().add(i)
        }
        val order = asideOrder(runs.map { ws[it.first()].kind }, runs.map { ws[it.last()].t })
        return order.flatMap { runs[it] }
    }

    /** ML Kit's words in Gemini's [order], with the corrections and kinds; boxes stay. */
    fun arrange(ws: List<PdfWord>, order: Order): List<PdfWord> {
        val lastOf = { b: Pair<Char, List<Int>> -> val id = b.second.last(); order.fix[id] ?: ws[id].t }
        val blocks = asideOrder(order.blocks.map { it.first }, order.blocks.map(lastOf)).map { order.blocks[it] }
        val out = ArrayList<PdfWord>()
        var line = -1
        blocks.forEachIndexed { bi, (kind, ids) ->
            var prev = -1
            for (id in ids) {
                val w = ws[id]
                if (prev < 0 || ws[prev].line != w.line) line++
                out += PdfWord(order.fix[id] ?: w.t, w.x, w.y, w.w, w.h, line, bi, kind.takeIf { it != 'b' })
                prev = id
            }
        }
        return out
    }
}
