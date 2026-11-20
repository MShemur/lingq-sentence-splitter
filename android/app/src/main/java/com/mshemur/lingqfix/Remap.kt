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
