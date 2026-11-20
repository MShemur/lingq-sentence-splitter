// Moves word translations to the new positions after the AI changed a lesson's text (reader/session.ts remapNotes).
// Pure functions. The same rule is to be ported to Kotlin (Reader.kt): keep both in step.

/** A word of a lesson: its "sentence:token" key and its normalized text. */
export interface WordSlot { key: string; norm: string }

/** For comparing words: lower case, ё = е, no punctuation around the word. */
export function wordNorm(t: string): string {
  return t.toLowerCase().replace(/ё/g, 'е').replace(/^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$/gu, '')
}

/** The word tokens of the sentences, in text order. */
export function wordSlots(sents: { id: number; tokens: { text: string; isWord: boolean; index: number }[] }[]): WordSlot[] {
  const out: WordSlot[] = []
  for (const s of sents) for (const t of s.tokens) if (t.isWord) out.push({ key: `${s.id}:${t.index}`, norm: wordNorm(t.text) })
  return out
}

/** How far the walk looks ahead for the next equal word. */
const LOOK = 30

/**
 * Old word key -> new word key, for the words that stay.
 * 1. Walk both streams. Equal words pair up and move on together.
 * 2. Unequal: for d = 1..30 look for the equal word: a[i+d] == b[j] (old words skipped) or b[j+d] == a[i]
 *    (new words inserted), the first hit moves that side. No hit: both sides move one (a changed word).
 * 3. A pair counts only if its previous or next word is also paired in the same direction (x-1 -> y-1 or x+1 -> y+1);
 *    a lone equal word is not an anchor.
 */
export function matchWords(a: WordSlot[], b: WordSlot[]): Map<string, string> {
  const pairs = new Map<number, number>() // index in a -> index in b
  let i = 0, j = 0
  while (i < a.length && j < b.length) {
    if (a[i].norm === b[j].norm) { pairs.set(i, j); i++; j++; continue }
    let moved = false
    for (let d = 1; d <= LOOK && !moved; d++) {
      if (i + d < a.length && a[i + d].norm === b[j].norm) { i += d; moved = true }
      else if (j + d < b.length && b[j + d].norm === a[i].norm) { j += d; moved = true }
    }
    if (!moved) { i++; j++ }
  }
  const out = new Map<string, string>()
  for (const [x, y] of pairs) {
    if (pairs.get(x - 1) === y - 1 || pairs.get(x + 1) === y + 1) out.set(a[x].key, b[y].key)
  }
  return out
}
