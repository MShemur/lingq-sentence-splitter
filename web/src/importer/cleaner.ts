// Quick clean of copied text (no AI) and splitting long texts. Port of Android Cleaner.kt (TextCleaner) and
// Sources.splitLong.

export const countWords = (t: string) => (t.match(/\S+/g) ?? []).length

/** What the quick clean did, for the note under the text. */
export interface CleanReport { removedLines: number; joinedHyphens: number; joinedLines: number; removedMarks: number }

export const addReports = (a: CleanReport, b: CleanReport): CleanReport => ({
  removedLines: a.removedLines + b.removedLines, joinedHyphens: a.joinedHyphens + b.joinedHyphens,
  joinedLines: a.joinedLines + b.joinedLines, removedMarks: a.removedMarks + b.removedMarks,
})

export const EMPTY_REPORT: CleanReport = { removedLines: 0, joinedHyphens: 0, joinedLines: 0, removedMarks: 0 }

export function reportText(r: CleanReport): string {
  const parts = [
    r.removedLines > 0 && `убрано строк: ${r.removedLines}`,
    r.joinedHyphens > 0 && `склеено переносов: ${r.joinedHyphens}`,
    r.joinedLines > 0 && `соединено разорванных строк: ${r.joinedLines}`,
    r.removedMarks > 0 && `убрано сносок и ссылок: ${r.removedMarks}`,
  ].filter(Boolean) as string[]
  if (!parts.length) return 'Мусора не нашлось'
  const s = parts.join(', ')
  return s[0].toUpperCase() + s.slice(1)
}

const PAGE_NUMBER = new RegExp(
  String.raw`^\s*(?:[-–—]?\s*\d{1,4}\s*[-–—]?|(?:Seite|S\.|Page|p\.|Стр\.?|страница)\s*\d{1,4}(?:\s*(?:von|of|из|/)\s*\d{1,4})?|\d{1,4}\s*/\s*\d{1,4})\s*$`,
  'i',
)
const URL_LINE = /^\s*(?:https?:\/\/|www\.)\S+\s*$/i
const URL = /\bhttps?:\/\/\S+/g
const JUNK = new RegExp(
  String.raw`^\s*(?:teilen|share|drucken|print|anzeige|werbung|advertisement|weiterlesen|mehr lesen|read more|` +
    String.raw`zum artikel|artikel teilen|kommentare?|comments?|newsletter|abonnieren|subscribe|cookie\S*|` +
    String.raw`facebook|twitter|x|whatsapp|telegram|e-?mail|link kopieren|copy link|nach oben|top|menü|menu|` +
    String.raw`поделиться|реклама|читать далее|подписаться|комментарии)\s*[:.!]?\s*$`,
  'i',
)
const SUPERSCRIPT = /[¹²³⁴⁵⁶⁷⁸⁹⁰]+/g
const BRACKET_REF = /\[\d{1,3}(?:[,–-]\d{1,3})*\]/g
const HYPHEN_BREAK = /(\p{L})[-¬]\n[ \t]*(\p{Ll})/gu
const TERMINAL = /[.!?…:;"»“”)\]]$/
const isLower = (c: string) => c !== c.toUpperCase() && c === c.toLowerCase()

/**
 * Page numbers, repeated headers/footers, footnote marks, links and invisible characters go away; words broken by a
 * hyphen at the line end and lines broken inside a sentence are joined. Conservative: the text is never rewritten.
 */
export function cleanText(src: string): [string, CleanReport] {
  let t = src.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
    .replace(/­/g, '').replace(/[​-‍﻿]/g, '')
    .replace(/ /g, ' ').replace(/\t/g, ' ')
  let marks = 0
  t = t.replace(SUPERSCRIPT, () => { marks++; return '' })
  t = t.replace(BRACKET_REF, () => { marks++; return '' })

  // lines that are not text: page numbers, bare links, share/print buttons, repeated headers and footers
  const lines = t.split('\n').map((l) => l.trimEnd())
  const counts = new Map<string, number>()
  for (const l of lines) { const s = l.trim(); if (s && s.length < 80) counts.set(s, (counts.get(s) ?? 0) + 1) }
  let removed = 0
  const kept = lines.filter((l) => {
    const s = l.trim()
    const junk = !!s && (PAGE_NUMBER.test(s) || URL_LINE.test(s) || JUNK.test(s) || (counts.get(s) ?? 0) >= 3)
    if (junk) removed++
    return !junk
  })
  t = kept.join('\n')
  t = t.replace(URL, () => { marks++; return '' })

  // words broken at the line end: "Kup-\nferstich" -> "Kupferstich"
  let hyph = 0
  t = t.replace(HYPHEN_BREAK, (_, a: string, b: string) => { hyph++; return a + b })

  // lines broken inside a sentence (PDF): join when the line is "full" and does not end a sentence
  const ls = t.split('\n')
  const lens = ls.map((l) => l.trim().length).filter((n) => n > 0).sort((a, b) => a - b)
  const full = lens.length ? lens[Math.min(Math.floor(lens.length * 0.8), lens.length - 1)] : 0
  let joined = 0
  let out = ''
  ls.forEach((raw, i) => {
    const line = raw.trim().replace(/ {2,}/g, ' ')
    out += line
    if (i === ls.length - 1) return
    const next = ls[i + 1].trim()
    const wraps = !!line && !!next && !TERMINAL.test(line) && (isLower(next[0]) || (full >= 40 && line.length >= full * 0.6))
    if (wraps) { out += ' '; joined++ } else out += '\n'
  })
  t = out.replace(/\n{3,}/g, '\n\n').trim()
  return [t, { removedLines: removed, joinedHyphens: hyph, joinedLines: joined, removedMarks: marks }]
}

/** Splits a long text at paragraph boundaries into parts of about [maxWords] words. */
export function splitLong(text: string, maxWords: number): string[] {
  if (maxWords <= 0 || countWords(text) <= maxWords) return [text]
  const paras = text.split(/\n\s*\n/).map((p) => p.trim()).filter(Boolean)
  const parts: string[] = []
  let cur = ''
  let words = 0
  for (const p of paras) {
    const pw = countWords(p)
    if (words > 0 && words + pw > maxWords) { parts.push(cur.trim()); cur = ''; words = 0 }
    cur += p + '\n\n'
    words += pw
  }
  if (cur.trim()) parts.push(cur.trim())
  return parts
}

/**
 * An import longer than this many words is split by LingQ into several lessons (checked 11.11.2026: 40 000 characters
 * became 2 lessons of ~6000 words). A sentence list saved with PATCH afterwards is not split (80 000 characters stayed
 * one lesson with its audio).
 */
export const LINGQ_MAX_WORDS = 6000

/** Sentences of a paragraph (LingQ's own split is not available for text added later; the fixer merges after it). */
export function splitSentences(p: string): string[] {
  return (p.match(/[^.!?…]+(?:[.!?…]+["»“”')\]]*|$)\s*/g) ?? []).map((x) => x.trim()).filter(Boolean)
}

/** Pieces of ~1800 words for AI cleaning. */
export const chunks = (text: string, maxWords = 1800) => splitLong(text, maxWords)
