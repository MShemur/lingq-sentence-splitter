// "Слова по урокам": new words per lesson of a course and unique words in total. German words are reduced to their
// base form offline ("bin", "ist", "war" -> "sein"; "Häusern" -> "Haus"). Port of Android WordStats.kt.
// Dictionary: lemmatization-lists by Michal Měchura (Open Database License), public/lemma-de.dat = gzip of
// "lemma<TAB>form<TAB>form…" per line (the same file as Android's assets/lemma-de.tsv.gz).
import { signal } from '@preact/signals'
import * as api from './lingq'
import type { Course } from './lingq'
import { log, logError } from './applog'
import { tokenize } from './reader/session'

export class Lemmatizer {
  private constructor(private forms: Map<string, string>, private lemmas: Set<string>) {}

  private static cache: Promise<Lemmatizer> | null = null

  /** Only German has a dictionary; null for other languages. */
  static load(lang: string): Promise<Lemmatizer> | null {
    if (lang !== 'de') return null
    Lemmatizer.cache ??= Lemmatizer.read().catch((e) => { Lemmatizer.cache = null; throw e })
    return Lemmatizer.cache
  }

  private static async read(): Promise<Lemmatizer> {
    const r = await fetch('/lemma-de.dat')
    if (!r.ok) throw new Error(`словарь форм не загрузился (HTTP ${r.status})`)
    const buf = new Uint8Array(await r.arrayBuffer())
    // gzip unless the server already unpacked it
    const text = buf[0] === 0x1f && buf[1] === 0x8b
      ? await new Response(new Blob([buf]).stream().pipeThrough(new DecompressionStream('gzip'))).text()
      : new TextDecoder().decode(buf)
    const forms = new Map<string, string>()
    const lemmas = new Set<string>()
    for (const line of text.split('\n')) {
      if (!line) continue
      const p = line.split('\t')
      const lm = p[0].replace(/\r$/, '')
      lemmas.add(lm)
      for (let i = 1; i < p.length; i++) forms.set(p[i].replace(/\r$/, ''), lm)
    }
    log('wordstats', `Словарь форм: ${lemmas.size} начальных форм, ${forms.size} форм`)
    return new Lemmatizer(forms, lemmas)
  }

  private direct(w: string): string | undefined { return this.forms.get(w) ?? (this.lemmas.has(w) ? w : undefined) }

  /** Base form and whether the dictionary knew it (compounds by their last part: "Bahnhofsuhren" -> "Bahnhofsuhr"). */
  lemma(w: string): [string, boolean] {
    const d = this.direct(w)
    if (d) return [d, true]
    const low = w.toLowerCase()
    if (low !== w) { const x = this.direct(low); if (x) return [x, true] }
    // compound word: the longest known ending decides the form
    for (let i = 1; i <= w.length - 4; i++) {
      const tail = w.slice(i)
      const lm = this.direct(tail) ?? this.direct(tail.charAt(0).toUpperCase() + tail.slice(1))
      if (lm) return [w.slice(0, i) + lm.toLowerCase(), true]
    }
    return [w, false]
  }
}

/** One lesson (chapter) of the count. */
export interface WsChapter { id: number; title: string; words: number; new: number; total: number; top: string[] }
export interface WsResult {
  t: number; chapters: WsChapter[]; unique: number; words: number; unknown: number; lemmatized: boolean
  /** Every base form, flat: word, first lesson index, count in the course, known 0/1 (missing in results before v1.47). */
  lemmas?: (string | number)[]
}

/** Words met at most [min] times in the whole course left out: numbers per lesson again, and how many were cut. */
export function without(r: WsResult, min: number): [WsResult, number] {
  const la = r.lemmas ?? []
  if (min <= 0 || !la.length) return [r, 0]
  const keep: { word: string; first: number; count: number; known: boolean }[] = []
  let all = 0
  for (let k = 0; k + 3 < la.length; k += 4) {
    all++
    const x = { word: String(la[k]), first: Number(la[k + 1]), count: Number(la[k + 2]), known: la[k + 3] === 1 }
    if (x.count > min) keep.push(x)
  }
  const byFirst = new Map<number, typeof keep>()
  for (const x of keep) { const g = byFirst.get(x.first); if (g) g.push(x); else byFirst.set(x.first, [x]) }
  let total = 0
  const chapters = r.chapters.map((c, i) => {
    const fresh = byFirst.get(i) ?? []
    total += fresh.length
    return { ...c, new: fresh.length, total, top: [...fresh].sort((a, b) => b.count - a.count).slice(0, 20).map((x) => x.word) }
  })
  return [{ ...r, chapters, unique: keep.length, unknown: keep.filter((x) => !x.known).length }, all - keep.length]
}

const key = (lang: string, course: number) => `wordstats.${lang}_${course}`

/** Results kept per course on this device. */
export function loadResult(lang: string, course: number): WsResult | null {
  try { const s = localStorage.getItem(key(lang, course)); return s ? JSON.parse(s) : null } catch { return null }
}
function saveResult(lang: string, course: number, r: WsResult) {
  try { localStorage.setItem(key(lang, course), JSON.stringify(r)) } catch { /* full */ }
}

const LETTER = /\p{L}/u

export class WordStatsSession {
  result = signal<WsResult | null>(null)
  running = signal(false)
  progress = signal('')
  fraction = signal(0)
  error = signal<string | null>(null)

  constructor(readonly lang: string, readonly course: Course) {
    this.result.value = loadResult(lang, course.id)
  }

  async start() {
    if (this.running.value) return
    this.running.value = true; this.error.value = null; this.fraction.value = 0; this.progress.value = 'Загружаю список уроков…'
    const c = this.course
    log('wordstats', `Подсчёт слов: курс ${c.id} «${c.title}»`)
    try {
      const r = await this.count()
      saveResult(this.lang, c.id, r)
      this.result.value = r
      log('wordstats', `Готово: ${r.chapters.length} уроков, ${r.unique} уникальных слов`)
    } catch (e) {
      this.error.value = (e as Error).message
      logError('wordstats', 'Подсчёт слов не удался', e)
    }
    this.running.value = false
  }

  private async count(): Promise<WsResult> {
    const { lessons } = await api.lessons(this.lang, this.course.id)
    if (!lessons.length) throw new Error('В курсе нет уроков')
    this.progress.value = 'Загружаю словарь…'
    const lem = await Lemmatizer.load(this.lang)
    // lesson texts, three at a time
    const texts: string[] = new Array(lessons.length)
    let next = 0, done = 0
    const worker = async () => {
      while (next < lessons.length) {
        const i = next++
        const paras = await api.editor(this.lang, lessons[i].id)
        texts[i] = paras.map((p: any) => (p.sentences ?? []).map((s: any) => String(s.text ?? '')).join(' ')).join('\n')
        done++
        this.progress.value = `Загружаю тексты: ${done} из ${lessons.length}`
        this.fraction.value = (done * 0.9) / lessons.length
      }
    }
    await Promise.all([worker(), worker(), worker()])
    this.progress.value = 'Считаю…'
    const seen = new Set<string>()
    const unknown = new Set<string>()
    const firstAt = new Map<string, number>()
    const totals = new Map<string, number>()
    const shownAll = new Map<string, string>()
    let allWords = 0
    const out: WsChapter[] = []
    lessons.forEach((l, i) => {
      const counts = new Map<string, number>()
      let words = 0
      // a word the dictionary does not know is counted case-blind ("Und" at a sentence start = "und")
      const shown = new Map<string, string>()
      for (const t of tokenize(texts[i])) {
        if (!t.isWord || !LETTER.test(t.text)) continue
        words++
        const [lm, known] = lem ? lem.lemma(t.text) : [t.text.toLowerCase(), true]
        const k = known ? lm : lm.toLowerCase()
        if (!known) { unknown.add(k); if (!shown.has(k) || lm === k) shown.set(k, lm) }
        counts.set(k, (counts.get(k) ?? 0) + 1)
      }
      const fresh = [...counts.keys()].filter((w) => !seen.has(w))
      fresh.forEach((w) => { seen.add(w); firstAt.set(w, i) })
      counts.forEach((v, w) => totals.set(w, (totals.get(w) ?? 0) + v))
      shown.forEach((v, w) => { if (!shownAll.has(w) || v === w) shownAll.set(w, v) })
      allWords += words
      const top = [...fresh].sort((a, b) => (counts.get(b) ?? 0) - (counts.get(a) ?? 0)).slice(0, 20).map((w) => shown.get(w) ?? w)
      out.push({ id: l.id, title: l.title, words, new: fresh.length, total: seen.size, top })
    })
    this.fraction.value = 1
    const lemmas: (string | number)[] = []
    for (const [w, f] of [...firstAt].sort((a, b) => a[1] - b[1])) lemmas.push(shownAll.get(w) ?? w, f, totals.get(w) ?? 0, unknown.has(w) ? 0 : 1)
    return { t: Date.now(), chapters: out, unique: seen.size, words: allWords, unknown: unknown.size, lemmatized: !!lem, lemmas }
  }
}
