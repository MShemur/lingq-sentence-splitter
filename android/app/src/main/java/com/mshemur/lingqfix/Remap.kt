package com.mshemur.lingqfix

// Moves word translations to the new positions after the AI changed a lesson's text (Reader.kt remapNotes).
// Pure functions. Port of web/src/reader/remap.ts: keep both in step.

/** A word of a lesson: its "sentence:token" key and its normalized text. */
data class WordSlot(val key: String, val norm: String)

/** Punctuation (not letter / digit) at the start and the end of a word, as in the web's /^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$/u. */
private val EDGE_PUNCT = Regex("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+\$")

/** For comparing words: lower case, ё = е, no punctuation around the word. */
fun wordNorm(t: String): String = EDGE_PUNCT.replace(t.lowercase().replace('ё', 'е'), "")

/** The word tokens of the sentences, in text order. */
fun wordSlots(sents: List<Sent>): List<WordSlot> {
    val out = mutableListOf<WordSlot>()
    for (s in sents) for (t in s.tokens) if (t.isWord) out += WordSlot("${s.id}:${t.index}", wordNorm(t.text))
    return out
}

/** How far the walk looks ahead for the next equal word. */
private const val REMAP_LOOK = 30

/**
 * Old word key -> new word key, for the words that stay.
 * 1. Walk both streams. Equal words pair up and move on together.
 * 2. Unequal: for d = 1..30 look for the equal word: a[i+d] == b[j] (old words skipped) or b[j+d] == a[i]
 *    (new words inserted), the first hit moves that side. No hit: both sides move one (a changed word).
 * 3. A pair counts only if its previous or next word is also paired in the same direction (x-1 -> y-1 or x+1 -> y+1);
 *    a lone equal word is not an anchor.
 */
fun matchWords(a: List<WordSlot>, b: List<WordSlot>): Map<String, String> {
    val paired = HashMap<Int, Int>() // index in a -> index in b
    var i = 0
    var j = 0
    while (i < a.size && j < b.size) {
        if (a[i].norm == b[j].norm) { paired[i] = j; i++; j++; continue }
        var moved = false
        var d = 1
        while (d <= REMAP_LOOK && !moved) {
            if (i + d < a.size && a[i + d].norm == b[j].norm) { i += d; moved = true }
            else if (j + d < b.size && b[j + d].norm == a[i].norm) { j += d; moved = true }
            d++
        }
        if (!moved) { i++; j++ }
    }
    val out = HashMap<String, String>()
    for ((x, y) in paired) {
        if (paired[x - 1] == y - 1 || paired[x + 1] == y + 1) out[a[x].key] = b[y].key
    }
    return out
}

/** A translation that is kept: not empty and not an error ("⚠"). */
fun trOk(v: String): Boolean = v.isNotEmpty() && !v.startsWith("⚠")

/** Stored notes after the text changed: pins "s:t" -> translation, phrases "s:f:e" -> translation; [kept]/[total] = not errors. */
class RemappedNotes(val pins: Map<String, String>, val phrases: Map<String, String>, val kept: Int, val total: Int)

/**
 * Moves the stored notes with the word map (old key -> new key from [matchWords]); [old] = the old word slots.
 * Pins follow their word. Phrases keep only if both ends land in one new sentence, f <= e, and no old word of the
 * phrase that found its place sits in another sentence. Unmatched notes are dropped. Port of web remapNotes.
 */
fun remapStored(pins: Map<String, String>, phrases: Map<String, String>, map: Map<String, String>, old: List<WordSlot>): RemappedNotes {
    var total = 0
    var kept = 0
    val newPins = LinkedHashMap<String, String>()
    for ((k, v) in pins) {
        if (trOk(v)) total++
        val nk = map[k] ?: continue
        newPins[nk] = v
        if (trOk(v)) kept++
    }
    val newPhrases = LinkedHashMap<String, String>()
    for ((k, v) in phrases) {
        if (trOk(v)) total++
        val q = k.split(':')
        if (q.size != 3) continue
        val s = q[0].toIntOrNull() ?: continue
        val f = q[1].toIntOrNull() ?: continue
        val e = q[2].toIntOrNull() ?: continue
        val a = map["$s:$f"] ?: continue
        val b = map["$s:$e"] ?: continue
        val ap = a.split(':')
        val bp = b.split(':')
        val aSent = ap[0].toInt(); val aTok = ap[1].toInt()
        val bSent = bp[0].toInt(); val bTok = bp[1].toInt()
        if (aSent != bSent || aTok > bTok) continue
        // every old word of the phrase that found its place must be in the same new sentence as the ends
        val split = old.any { slot ->
            val p = slot.key.split(':')
            p[0].toIntOrNull() == s && (p[1].toIntOrNull() ?: -1) in f..e &&
                map[slot.key]?.let { it.substringBefore(':').toInt() != aSent } == true
        }
        if (split) continue
        newPhrases["$aSent:$aTok:$bTok"] = v
        if (trOk(v)) kept++
    }
    return RemappedNotes(newPins, newPhrases, kept, total)
}
