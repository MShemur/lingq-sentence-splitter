package com.mshemur.lingqfix

import org.json.JSONArray
import org.json.JSONObject

/**
 * Merges sentences that LingQ split at abbreviations. Port of lingq_fix_splits.py:
 * keep both in sync (ABBREVIATIONS, SENTENCE_STARTERS, AMBIGUOUS, shouldMerge).
 */
object Fixer {
    // Words that end with a dot but do not end a sentence (case-sensitive, without the dot).
    val ABBREVIATIONS = setOf(
        "Abb", "Abbn", "Taf", "Kat", "Kap", "Bd", "Bde", "Nr", "Inv", "Inv.-Nr", "Sign", "Slg", "Rar",
        "Ink", "fol", "Fol", "Bl", "Dm", "Hrsg", "hrsg", "Aufl", "Anm", "vgl", "Vgl", "s", "S", "Sp",
        "ca", "Ca", "hl", "Hl", "St", "Dr", "Prof", "bzw", "usw", "etc", "evtl", "ggf", "sog", "u",
        "z", "B", "d", "Ä", "J", "Jh", "Jhs", "Jhdt", "Jt", "Jts", "geb", "gest", "verh", "röm", "lat", "griech",
        "dt", "frz", "ital", "mhd", "ahd", "Mio", "Mrd", "Tsd", "v", "Chr", "n", "a", "o", "ff", "f",
        "ders", "Ders", "Hg", "hg", "ebd", "Ebd", "zit", "Zit", "Gr", "A", "Nachf", "Bibl",
        "Mus", "Stadtbibl", "Staatsbibl", "Univ", "Pl", "Abt", "Ms", "Cod", "Cgm", "Clm", "Hs",
    )

    // Words that typically START a new sentence. After an ordinal ("3.") or a letter ("A.") we
    // only merge if the next fragment does NOT begin with one of these.
    val SENTENCE_STARTERS: Set<String> = """
        der die das den dem des ein eine einer eines einem einen und aber doch oder denn sondern so also
        dann da danach daher deshalb deswegen trotzdem außerdem auch noch nun jetzt heute hier dort
        es er sie ich wir ihr man wer was wie wo wann warum wenn als weil dass ob obwohl während nachdem
        bevor seit bis mit in im am an auf aus bei nach von vor zu zum zur für über unter durch gegen ohne
        um dieser diese dieses diesen diesem jener jene jedes jeder jede alle viele einige manche kein keine
        nicht nur schon immer zwar ja nein na naja okay ok gut genau tja mein meine sein seine ihre unser
        dabei damit darauf darum dafür dazu davon hinzu zudem zunächst schließlich später erst sogar
        ungefähr etwa vermutlich wahrscheinlich vielleicht allerdings jedoch insgesamt bereits ebenso
        gleichzeitig inzwischen hingegen demgegenüber dagegen stattdessen somit folglich anschließend
        zuerst zuvor seitdem damals heutzutage offenbar offensichtlich tatsächlich natürlich jedenfalls
        immerhin übrigens einerseits andererseits erstens zweitens drittens abb vgl
    """.trim().split(Regex("\\s+")).toSet()

    // Abbreviations that may also END a sentence ("... aus dem 8. Jh. Da Homer ...").
    val AMBIGUOUS = setOf("Jh", "Jhs", "Jhdt", "Jt", "Jts", "Chr", "usw", "etc", "ff", "f", "ebd", "Ebd", "Aufl")

    private val LAST_TOKEN = Regex("(\\S+)\\.$")
    private val FIRST_WORD = Regex("^[^\\p{L}]*(\\p{L}+)")   // Android regex has no (?U) flag
    private val ORDINAL_OR_LETTER = Regex("\\d{1,2}|[A-ZÄÖÜ]|[IVX]{1,4}|([A-ZÄÖÜ]\\.)+[A-ZÄÖÜ]")
    private val WS = Regex("\\s+")

    fun norm(t: String) = t.replace(WS, " ").trim()

    fun firstWord(t: String): String = FIRST_WORD.find(t)?.groupValues?.get(1)?.lowercase() ?: ""

    private fun startsWithParenOrDigit(n: String) = n.isEmpty() || n[0] in "(0123456789"

    fun endsWithAbbreviation(text: String, nxt: String): Boolean {
        val m = LAST_TOKEN.find(text.trimEnd()) ?: return false
        val tok = m.groupValues[1].trimStart('(', '[', '«', '„', '"', '\'')
        val n = nxt.trimStart()
        if (tok in AMBIGUOUS) return startsWithParenOrDigit(n) || firstWord(n) !in SENTENCE_STARTERS
        if (tok in ABBREVIATIONS) return true
        if (ORDINAL_OR_LETTER.matches(tok)) {
            if (startsWithParenOrDigit(n)) return true   // "Theodosius I. (379–395)", "(Abb. 1. 2)"
            return firstWord(n) !in SENTENCE_STARTERS
        }
        return false
    }

    fun shouldMerge(prev: String, nxt: String): Boolean {
        val p = prev.trimEnd()
        val n = nxt.trimStart()
        if (n.isEmpty()) return false
        if (endsWithAbbreviation(p, n)) return true
        if (n[0] in ")]»,;:*" || n[0].isLowerCase()) return true     // can't start a sentence
        if ((p.endsWith("?") || p.endsWith("!")) && p.count { it == '(' } > p.count { it == ')' }) return true
        return false
    }

    fun joinText(a: String, b: String): String {
        val bb = b.trimStart()
        val sep = if (bb.isEmpty() || bb[0] in ")]»,;:") "" else " "
        return a.trimEnd() + sep + bb
    }

    fun cleanText(t: String): String {
        var s = t.replace('․', '.')                 // lookalike dot -> real dot
        for (ch in "[]*_") s = s.replace("\\" + ch, ch.toString())   // markdown escapes \[sei\]
        return s
    }

    private fun str(o: JSONObject, key: String, fallback: String = ""): String =
        if (o.isNull(key)) fallback else o.optString(key, fallback)

    private fun concat(a: JSONArray?, b: JSONArray?): JSONArray {
        val out = JSONArray()
        for (arr in listOf(a, b)) if (arr != null) for (i in 0 until arr.length()) out.put(arr.get(i))
        return out
    }

    fun mergeSentences(a: JSONObject, b: JSONObject): JSONObject {
        val m = JSONObject(a.toString())
        m.put("text", joinText(str(a, "text"), str(b, "text")))
        m.put("cleanText", joinText(str(a, "cleanText", str(a, "text")), str(b, "cleanText", str(b, "text"))))
        m.put("phrases", concat(a.optJSONArray("phrases"), b.optJSONArray("phrases")))
        m.put("notes", concat(a.optJSONArray("notes"), b.optJSONArray("notes")))
        val tr = LinkedHashMap<String, JSONObject>()
        val all = concat(a.optJSONArray("translations"), b.optJSONArray("translations"))
        for (i in 0 until all.length()) {
            val t = all.optJSONObject(i) ?: continue
            val lang = str(t, "language")
            val existing = tr[lang]
            if (existing != null) existing.put("text", joinText(str(existing, "text"), str(t, "text")))
            else tr[lang] = JSONObject(t.toString())
        }
        m.put("translations", JSONArray(tr.values.toList()))
        val tsA = a.optJSONArray("timestamp")
        val tsB = b.optJSONArray("timestamp")
        val start = if (tsA != null && tsA.length() > 0) tsA.get(0) else JSONObject.NULL
        val end = when {
            tsB != null && tsB.length() > 1 && !tsB.isNull(1) -> tsB.get(1)
            tsA != null && tsA.length() > 1 -> tsA.get(1)
            else -> JSONObject.NULL
        }
        m.put("timestamp", JSONArray().put(start).put(end))
        return m
    }

    /** Fixes editor paragraphs in place and returns the list of changes. */
    fun fix(paragraphs: JSONArray): List<Change> {
        val changes = mutableListOf<Change>()
        for (pi in 0 until paragraphs.length()) {
            val p = paragraphs.optJSONObject(pi) ?: continue
            val sents = p.optJSONArray("sentences") ?: continue
            for (i in 0 until sents.length()) {
                val s = sents.optJSONObject(i) ?: continue
                for (k in listOf("text", "cleanText")) {
                    if (!s.has(k) || s.isNull(k)) continue
                    val v = s.getString(k)
                    val c = cleanText(v)
                    if (c != v) {
                        if (k == "text") changes += Change(ChangeKind.CLEANUP, c)
                        s.put(k, c)
                    }
                }
            }
            val out = mutableListOf<JSONObject>()
            for (i in 0 until sents.length()) {
                val s = sents.optJSONObject(i) ?: continue
                val st = str(s, "text")
                val last = out.lastOrNull()
                val lt = if (last != null) str(last, "text") else ""
                if (last != null && norm(lt).length > 10 && norm(st).startsWith(norm(lt))) {
                    changes += Change(ChangeKind.DUP, lt)        // LingQ duplicated a fragment
                    out[out.size - 1] = s
                } else if (last != null && shouldMerge(lt, st)) {
                    changes += Change(ChangeKind.MERGE, lt, st)
                    out[out.size - 1] = mergeSentences(last, s)
                } else {
                    out += s
                }
            }
            p.put("sentences", JSONArray(out))
        }
        var idx = 0
        for (pi in 0 until paragraphs.length()) {
            val sents = paragraphs.optJSONObject(pi)?.optJSONArray("sentences") ?: continue
            for (i in 0 until sents.length()) sents.optJSONObject(i)?.put("index", ++idx)
        }
        return changes
    }

    fun tail(t: String, n: Int = 55): String { val s = norm(t); return (if (s.length > n) "…" else "") + s.takeLast(n) }
    fun head(t: String, n: Int = 55): String { val s = norm(t); return s.take(n) + (if (s.length > n) "…" else "") }
}
