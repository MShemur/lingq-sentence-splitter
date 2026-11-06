package com.mshemur.lingqfix

import org.json.JSONArray

data class Course(val id: Int, val title: String, val imageUrl: String = "")
data class Lesson(
    val id: Int, val title: String, val imageUrl: String = "", val level: String = "",
    val words: Int = 0, val newWords: Int = 0, val courseTitle: String = "",
)
data class Language(val code: String, val title: String)

/** What a LingQ link points to. [lang] is null when the link does not contain it. */
data class LinkTarget(val lang: String?, val courseId: Int?, val lessonId: Int?)

enum class ChangeKind { MERGE, DUP, CLEANUP }

/** MERGE: [a] + [b] become one sentence. DUP: fragment [a] dropped. CLEANUP: characters fixed in [a]. */
data class Change(val kind: ChangeKind, val a: String, val b: String = "")

/** Result of checking one lesson: editor paragraphs before and after the fix. */
class LessonFix(val lesson: Lesson, val original: JSONArray, val fixed: JSONArray, val changes: List<Change>)

/** Plain-text report (to share / send for review). */
fun reportText(header: String, fixes: List<LessonFix>): String = buildString {
    append(header).append("\n")
    append("Уроков с правками: ${fixes.size}, правок: ${fixes.sumOf { it.changes.size }}\n")
    for (f in fixes) {
        append("\n## ${f.lesson.title} (id ${f.lesson.id}) — ${f.changes.size}\n")
        for (c in f.changes) append(
            when (c.kind) {
                ChangeKind.MERGE -> "- ${Fixer.tail(c.a)} ‖ ${Fixer.head(c.b)}"
                ChangeKind.DUP -> "- убран повтор: ${Fixer.head(c.a, 80)}"
                ChangeKind.CLEANUP -> "- исправлены символы: ${Fixer.head(c.a, 80)}"
            }
        ).append("\n")
    }
}

fun String.extractUrl(): String = Regex("https?://\\S+").find(this)?.value ?: this.trim()

/** Same rules as parse_target() in lingq_fix_splits.py. */
fun parseTarget(s: String): LinkTarget? {
    val t = s.extractUrl()
    t.toIntOrNull()?.let { return LinkTarget(null, it, null) }
    val lang = Regex("/learn/([a-z]{2,3}(?:-[a-z]+)?)/").find(t)?.groupValues?.get(1)
    (Regex("/course/(\\d+)").find(t) ?: Regex("/collections?/(\\d+)").find(t))?.let {
        return LinkTarget(lang, it.groupValues[1].toInt(), null)
    }
    Regex("/(?:lesson|reader|lessons|editor)/(\\d+)").find(t)?.let {
        return LinkTarget(lang, null, it.groupValues[1].toInt())
    }
    return null
}

fun countWords(t: String): Int = Regex("\\S+").findAll(t).count()
