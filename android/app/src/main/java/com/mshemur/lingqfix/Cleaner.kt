package com.mshemur.lingqfix

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What the quick clean did, for the note under the text. */
class CleanReport(val removedLines: Int, val joinedHyphens: Int, val joinedLines: Int, val removedMarks: Int) {
    operator fun plus(o: CleanReport) = CleanReport(
        removedLines + o.removedLines, joinedHyphens + o.joinedHyphens, joinedLines + o.joinedLines, removedMarks + o.removedMarks,
    )

    fun text(): String {
        val parts = listOfNotNull(
            removedLines.takeIf { it > 0 }?.let { "убрано строк: $it" },
            joinedHyphens.takeIf { it > 0 }?.let { "склеено переносов: $it" },
            joinedLines.takeIf { it > 0 }?.let { "соединено разорванных строк: $it" },
            removedMarks.takeIf { it > 0 }?.let { "убрано сносок и ссылок: $it" },
        )
        return if (parts.isEmpty()) "Мусора не нашлось" else parts.joinToString(", ").replaceFirstChar { it.uppercase() }
    }
}

/**
 * Quick clean on the phone (no AI): page numbers, repeated headers/footers, footnote marks, links and invisible
 * characters go away; words broken by a hyphen at the line end and lines broken inside a sentence are joined.
 * Conservative: the text itself is never rewritten.
 */
object TextCleaner {
    private val PAGE_NUMBER = Regex(
        """^\s*(?:[-–—]?\s*\d{1,4}\s*[-–—]?|(?:Seite|S\.|Page|p\.|Стр\.?|страница)\s*\d{1,4}(?:\s*(?:von|of|из|/)\s*\d{1,4})?|\d{1,4}\s*/\s*\d{1,4})\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val URL_LINE = Regex("""^\s*(?:https?://|www\.)\S+\s*$""", RegexOption.IGNORE_CASE)
    private val URL = Regex("""\bhttps?://\S+""")
    private val JUNK = Regex(
        """^\s*(?:teilen|share|drucken|print|anzeige|werbung|advertisement|weiterlesen|mehr lesen|read more|""" +
            """zum artikel|artikel teilen|kommentare?|comments?|newsletter|abonnieren|subscribe|cookie\S*|""" +
            """facebook|twitter|x|whatsapp|telegram|e-?mail|link kopieren|copy link|nach oben|top|menü|menu|""" +
            """поделиться|реклама|читать далее|подписаться|комментарии)\s*[:.!]?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val SUPERSCRIPT = Regex("[¹²³⁴⁵⁶⁷⁸⁹⁰]+")
    private val BRACKET_REF = Regex("""\[\d{1,3}(?:[,–-]\d{1,3})*]""")
    private val HYPHEN_BREAK = Regex("""(\p{L})[-¬]\n[ \t]*(\p{Ll})""")
    private val TERMINAL = Regex("""[.!?…:;"»“”)\]]$""")

    fun clean(src: String): Pair<String, CleanReport> {
        var t = src.replace("\r\n", "\n").replace('\r', '\n')
            .replace("­", "").replace(Regex("[​-‍﻿]"), "")
            .replace(' ', ' ').replace('\t', ' ')
        var marks = 0
        t = SUPERSCRIPT.replace(t) { marks++; "" }
        t = BRACKET_REF.replace(t) { marks++; "" }

        // lines that are not text: page numbers, bare links, share/print buttons, repeated headers and footers
        val lines = t.split('\n').map { it.trimEnd() }
        val counts = lines.map { it.trim() }.filter { it.isNotEmpty() && it.length < 80 }.groupingBy { it }.eachCount()
        var removed = 0
        val kept = lines.filter { l ->
            val s = l.trim()
            val junk = s.isNotEmpty() && (PAGE_NUMBER.matches(s) || URL_LINE.matches(s) || JUNK.matches(s) || (counts[s] ?: 0) >= 3)
            if (junk) removed++
            !junk
        }
        t = kept.joinToString("\n")
        t = URL.replace(t) { marks++; "" }

        // words broken at the line end: "Kup-\nferstich" -> "Kupferstich"
        var hyph = 0
        t = HYPHEN_BREAK.replace(t) { hyph++; it.groupValues[1] + it.groupValues[2] }

        // lines broken inside a sentence (PDF, OCR): join when the line is "full" and does not end a sentence
        val ls = t.split('\n')
        val full = ls.map { it.trim().length }.filter { it > 0 }.sorted().let { if (it.isEmpty()) 0 else it[(it.size * 0.8).toInt().coerceAtMost(it.size - 1)] }
        var joined = 0
        val sb = StringBuilder()
        for ((i, raw) in ls.withIndex()) {
            val line = raw.trim().replace(Regex(" {2,}"), " ")
            sb.append(line)
            if (i == ls.lastIndex) break
            val next = ls[i + 1].trim()
            val wraps = line.isNotEmpty() && next.isNotEmpty() && !TERMINAL.containsMatchIn(line) &&
                (next.first().isLowerCase() || (full >= 40 && line.length >= full * 0.6))
            if (wraps) { sb.append(' '); joined++ } else sb.append('\n')
        }
        t = sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
        return t to CleanReport(removed, hyph, joined, marks)
    }

    /** Splits a long text into pieces of about [maxWords] words at paragraph boundaries (for AI cleaning). */
    fun chunks(text: String, maxWords: Int = 1800): List<String> = Sources.splitLong(text, maxWords)
}

/** Text recognition on the phone (Google ML Kit, Latin script): free, offline. */
object Ocr {
    private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun read(img: InputImage): String = suspendCancellableCoroutine { cont ->
        client.process(img)
            .addOnSuccessListener { r ->
                // one text block = one paragraph; its lines are joined later by the cleaner
                cont.resume(r.textBlocks.joinToString("\n\n") { b -> b.lines.joinToString("\n") { it.text } })
            }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
    }

    suspend fun photo(ctx: Context, uri: Uri): String = read(InputImage.fromFilePath(ctx, uri))
}
