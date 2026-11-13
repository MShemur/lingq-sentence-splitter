// Reader state for one lesson. Port of ReaderSession in Reader.kt.
import { computed, signal, type Signal } from '@preact/signals'
import * as api from '../lingq'
import type { Lesson } from '../lingq'
import * as gemini from '../gemini'
import type { WordMeaning } from '../gemini'
import { prefs, prompt, trLang, useGemini } from '../settings'
import { fill } from '../prompts'
import { go, say, stack, errorText, geminiFailed, lessonOpened } from '../app'
import { ChatSession } from '../chat'
import { hasBookmark, toggleBookmark as toggleBm, type Bookmark } from '../bookmarks'
import { positionOf, savePosition } from '../progress'
import { loadTr, rememberTr, trKey } from '../trcache'
import { countRead } from '../stats'
import { saveBackup } from '../importer/backups'
import { log, logError } from '../applog'
import * as audio from '../audio'
import { addAudioBookmark, audioBookmarks, removeBookmark, type Bookmark as Bm } from '../bookmarks'

// --- model ---

/** A piece of a sentence: a word (tappable) or the punctuation/space between words. */
export interface Token { text: string; isWord: boolean; index: number }

const WORD = /[\p{L}\p{N}]+(?:[-'’][\p{L}\p{N}]+)*/gu

export function tokenize(t: string): Token[] {
  const out: Token[] = []
  let pos = 0
  let i = 0
  for (const m of t.matchAll(WORD)) {
    const start = m.index!
    if (start > pos) out.push({ text: t.slice(pos, start), isWord: false, index: i++ })
    out.push({ text: m[0], isWord: true, index: i++ })
    pos = start + m[0].length
  }
  if (pos < t.length) out.push({ text: t.slice(pos), isWord: false, index: i++ })
  return out
}

/** Groups tokens up to the next space, so "(Abb." or "24)," never break apart. */
function chunks(tokens: Token[]): Token[][] {
  const out: Token[][] = []
  let cur: Token[] = []
  for (const t of tokens) {
    if (!t.isWord && /\s/.test(t.text)) {
      // " – ": the part before the space ends a chunk, the part after starts the next,
      // anything between spaces (a dash) is a chunk of its own
      const parts = t.text.split(/\s+/)
      if (parts[0]) cur.push({ text: parts[0], isWord: false, index: t.index })
      if (cur.length) out.push(cur)
      cur = []
      for (const m of parts.slice(1, -1)) if (m) out.push([{ text: m, isWord: false, index: t.index }])
      const last = parts[parts.length - 1]
      if (parts.length > 1 && last) cur.push({ text: last, isWord: false, index: t.index })
    } else cur.push(t)
  }
  if (cur.length) out.push(cur)
  return out
}

/** One sentence of the lesson; [id] is unique in the lesson, [lingqIndex] is LingQ's 1-based index. */
export class Sent {
  readonly tokens: Token[]
  readonly chunks: Token[][]
  /** In a paragraph with LingQ's style h1..h6: drawn bold. */
  heading = false
  constructor(
    readonly id: number, readonly text: string, readonly translations: Record<string, string>, readonly lingqIndex: number,
    /** Where it sounds in the lesson audio, ms (-1 = unknown). */
    readonly start = -1, readonly end = -1,
  ) {
    this.tokens = tokenize(text)
    this.chunks = chunks(this.tokens)
  }
}

/** Word position "sentence:token". */
export type WordRef = string
export const ref = (sent: number, token: number): WordRef => `${sent}:${token}`
export const refSent = (r: WordRef) => Number(r.split(':')[0])
const refToken = (r: WordRef) => Number(r.split(':')[1])

const isError = (t: string | undefined) => !t || t.startsWith('⚠')

/** Added to the word prompt: German-style verbs whose prefix stands apart ("fing … an"). */
const SEPARABLE = '\n\nУчитывай грамматику всего предложения. Если «{word}» — глагол с отделяемой приставкой ' +
  '(или часть составного глагола), а приставка стоит отдельно дальше в предложении (нем. «Er fing gestern an» → anfangen, ' +
  '«Sie kommt morgen zurück» → zurückkommen), переводи глагол вместе с этой приставкой, а в note укажи инфинитив с приставкой. ' +
  'Если «{word}» — сама отделённая приставка, переводи весь глагол так же.'

/** The sentence in LingQ's paragraphs: by its index, else by the same order as `parse` (non-empty sentences). */
function findSentence(paras: any[], s: Sent): any | null {
  const all: any[] = paras.flatMap((p) => (Array.isArray(p?.sentences) ? p.sentences : []))
  const same = (x: any) => String(x?.text ?? '').trim() === s.text
  const byIndex = all.find((x) => x?.index === s.lingqIndex && same(x))
  if (byIndex) return byIndex
  const nonEmpty = all.filter((x) => String(x?.text ?? '').trim())
  const byOrder = nonEmpty[s.id]
  if (byOrder && same(byOrder)) return byOrder
  return all.find((x) => x?.index === s.lingqIndex) ?? null
}

/** Pinned phrase key "sent:from:to" -> [sent, from, to]. */
const phraseParts = (k: string) => k.split(':').map(Number)

// --- helpers for immutable records in signals ---

function put<V>(s: Signal<Record<string, V>>, k: string | number, v: V) { s.value = { ...s.value, [k]: v } }
function drop<V>(s: Signal<Record<string, V>>, k: string | number) {
  if (!(k in s.value)) return
  const c = { ...s.value }
  delete c[k]
  s.value = c
}

// --- session ---

/** For comparing meanings: lower case, ё = е, letters and digits only. */
const norm = (t: string) => t.toLowerCase().replace(/ё/g, 'е').replace(/[^\p{L}\p{N}]+/gu, ' ').trim()

export class ReaderSession {
  title: Signal<string>
  paragraphs = signal<Sent[][] | null>(null)
  sentences = signal<Sent[]>([])
  error = signal<string | null>(null)

  sentenceMode = signal(false)
  allSentences = signal(false)
  page = signal(0)
  /** Sentence to scroll to (bookmark); consumed by the screens. */
  jump: Signal<number | null>
  /** After sentence mode is collapsed: sentence the text view should show. */
  syncTo = signal<number | null>(null)
  textProgress = signal(0)
  menuOpen = signal(false)
  bookmarksOpen = signal(false)

  /** Sentence translations, and the sentences whose translation is shown. */
  sentTr = signal<Record<number, string>>({})
  shown = signal<Record<number, true>>({})
  /** Translations pinned above words ("" while loading); kept for this lesson. */
  pins = signal<Record<WordRef, string>>({})
  /** Pins that show LingQ's quick guess while Gemini is still thinking. */
  provisional = signal<Record<WordRef, true>>({})
  /** "Временно скрыть переводы": highlights stay, the translations above words are invisible. */
  pinsHidden = signal(false)
  /** While hidden: translations (word refs / phrase keys) shown for a moment after a tap. */
  peek = signal<Record<string, true>>({})
  private peekTimers: Record<string, number> = {}
  /** "Слова с переводом" list is open. */
  wordsOpen = signal(false)
  /** Meaning chosen for a word (lower case) in this lesson. */
  chosen = signal<Record<string, string>>({})
  retranslating = signal<Record<number, true>>({})

  /** Word window (double tap). */
  popup = signal<WordRef | null>(null)
  popupHints = signal<string[] | null>(null)
  popupBest = signal<string | null>(null)
  popupError = signal<string | null>(null)
  popupNote = signal('')
  popupAi = signal<string[]>([])
  popupThinking = signal(false)

  /** Last sentence the user worked with (tapped word, long press); sentence mode opens there. */
  focusSent: number | null = null
  /** Text view pages: the page shown, "3 / 12", the sentence the page starts with (kept when pages are rebuilt). */
  textPage = signal(0)
  pageLabel = signal('')
  /** Pages of the text view (for the page slider). */
  pageCount = signal(0)
  textAnchor: number | null = null
  /** Sentences on the page being read. */
  visibleSents = new Set<number>()

  private sentById = new Map<number, Sent>()
  private hintsCache = new Map<string, Promise<string[]>>()
  private aiCache = new Map<WordRef, Promise<WordMeaning>>()
  private loadingSentences = new Set<number>()
  private notesKey: string

  constructor(readonly lang: string, readonly lesson: Lesson, startSent: number | null = null) {
    this.title = signal(lesson.title)
    this.jump = signal(startSent)
    this.notesKey = `reader.${lang}_${lesson.id}`
  }

  get target() { return trLang() }

  async load() {
    this.error.value = null
    try {
      const [l, o] = await Promise.all([
        this.lesson.title ? Promise.resolve(this.lesson) : api.lesson(this.lang, this.lesson.id),
        api.editorObj(this.lang, this.lesson.id),
      ])
      const paras = this.parse(o?.paragraphs ?? [])
      const au = typeof o?.audio === 'string' && o.audio.startsWith('http') ? o.audio : null
      this.audioUrl.value = au
      if (au && !this.lesson.audio) {
        this.lesson.audio = au
        if (o?.duration) this.lesson.duration = Number(o.duration) || 0
      }
      this.loadNotes()
      this.title.value = l.title
      this.opened(l)
      this.sentences.value = paras.flat()
      void this.applyCachedTr()
      // open where reading stopped last time (a bookmark's sentence wins)
      const start = this.jump.value ?? positionOf(this.lang, this.lesson.id)?.s ?? null
      if (start != null && this.sentById.has(start)) { this.page.value = this.indexOf(start); this.textAnchor = start }
      this.paragraphs.value = paras
      if (this.autoPlay && au) { this.autoPlay = false; this.togglePlay() }
    } catch (e) {
      logError('reader', `Урок ${this.lesson.id} не открылся`, e)
      this.error.value = errorText(e)
    }
  }

  /** AI / machine sentence translations kept on this device from earlier visits (sentence text -> translation). */
  private trMap: Record<string, string> = {}

  private async applyCachedTr() {
    const m = await loadTr(trKey(this.lang, this.lesson.id, this.target))
    this.trMap = { ...m, ...this.trMap }
    const add: Record<number, string> = {}
    for (const s of this.sentences.value) { const t = m[s.text]; if (t && !this.sentTr.value[s.id]) add[s.id] = t }
    if (Object.keys(add).length) this.sentTr.value = { ...this.sentTr.value, ...add }
  }

  private keepTr(text: string, tr: string) {
    if (!tr.trim() || isError(tr) || this.trMap[text] === tr) return
    this.trMap = { ...this.trMap, [text]: tr }
    rememberTr(trKey(this.lang, this.lesson.id, this.target), this.trMap)
  }

  /** For the library's "Продолжить" row (the cover is fetched if missing). */
  private opened(l: Lesson) {
    const lesson = { ...this.lesson, ...l, title: l.title || this.lesson.title }
    lessonOpened(this.lang, lesson)
    if (!lesson.imageUrl) api.lesson(this.lang, lesson.id).then((f) => {
      if (f.imageUrl) lessonOpened(this.lang, { ...lesson, imageUrl: f.imageUrl, courseTitle: lesson.courseTitle || f.courseTitle })
    }, () => {})
  }

  /** Remembers where the reader is: [first] sentence on screen, [last] one (for the percentage read). */
  savePos(first: number, last: number) {
    const n = this.sentences.value.length
    if (!n || !this.sentById.has(first)) return
    const i = this.indexOf(last)
    savePosition(this.lang, this.lesson.id, first, (i + 1) / n)
    // statistics: words of sentences seen for the first time
    const f = this.indexOf(first)
    const list = this.sentences.value
    if (i >= f) countRead(this.lang, this.lesson.id, f, i, (k) => list[k].tokens.filter((t) => t.isWord).length)
  }

  private indexOf(id: number) { return Math.max(0, this.sentences.value.findIndex((s) => s.id === id)) }

  private parse(a: any[]): Sent[][] {
    let id = 0
    const out: Sent[][] = []
    for (const p of a) {
      const para: Sent[] = []
      // LingQ's paragraph style: "h1".."h6" are headings (drawn bold), plain text is "p"
      const heading = /^h[1-6]$/i.test(String(p?.style ?? ''))
      for (const s of p?.sentences ?? []) {
        const text = String(s.text ?? '').trim()
        if (!text) continue
        const tr: Record<string, string> = {}
        for (const t of s.translations ?? []) {
          const v = String(t.text ?? '').trim()
          if (v && !(t.language in tr)) tr[t.language] = v
        }
        const ts = Array.isArray(s.timestamp) ? s.timestamp : null
        const sec = (v: unknown) => (typeof v === 'number' && v >= 0 ? Math.round(v * 1000) : -1)
        const sent = new Sent(id, text, tr, typeof s.index === 'number' ? s.index : id + 1, sec(ts?.[0]), sec(ts?.[1]))
        sent.heading = heading
        id++
        this.sentById.set(sent.id, sent)
        para.push(sent)
      }
      if (para.length) out.push(para)
    }
    return out
  }

  // --- notes kept on this device ---

  private loadNotes() {
    try {
      const o = JSON.parse(localStorage.getItem(this.notesKey) ?? '{}')
      this.pins.value = o.pins ?? {}
      this.chosen.value = o.words ?? {}
      this.phrasePins.value = o.phrases ?? {}
    } catch { /* none yet */ }
  }

  private persist() {
    const pins: Record<string, string> = {}
    for (const [k, v] of Object.entries(this.pins.value)) if (v && !v.startsWith('⚠')) pins[k] = v
    const phrases: Record<string, string> = {}
    for (const [k, v] of Object.entries(this.phrasePins.value)) if (v && !v.startsWith('⚠')) phrases[k] = v
    try { localStorage.setItem(this.notesKey, JSON.stringify({ pins, words: this.chosen.value, phrases, t: Date.now() })) } catch { /* ignore */ }
  }

  /** While translations are hidden: shows the one of [k] (a word or a phrase) for 5 s, then it fades out. */
  peekAt(k: string) {
    put(this.peek, k, true)
    clearTimeout(this.peekTimers[k])
    this.peekTimers[k] = window.setTimeout(() => { drop(this.peek, k); delete this.peekTimers[k] }, 5000)
  }

  /** Words and phrases with a translation above them, in text order (for the list and the chat). */
  pinnedList(): { key: string; sent: number; word: string; tr: string }[] {
    const out: { key: string; sent: number; word: string; tr: string; at: number }[] = []
    const ok = (v: string) => !!v && !v.startsWith('⚠')
    for (const [k, v] of Object.entries(this.pins.value)) {
      if (!ok(v) || this.provisional.value[k]) continue
      out.push({ key: k, sent: refSent(k), word: this.word(k), tr: v, at: this.indexOf(refSent(k)) * 10000 + refToken(k) })
    }
    for (const [k, v] of Object.entries(this.phrasePins.value)) {
      if (!ok(v)) continue
      const [s, f, e] = phraseParts(k)
      const words = (this.sentById.get(s)?.tokens ?? []).filter((t) => t.isWord && t.index >= f && t.index <= e).map((t) => t.text).join(' ')
      out.push({ key: k, sent: s, word: words, tr: v, at: this.indexOf(s) * 10000 + f })
    }
    return out.sort((a, b) => a.at - b.at).map((x) => ({ key: x.key, sent: x.sent, word: x.word, tr: x.tr }))
  }

  clearPins() { this.pins.value = {}; this.provisional.value = {}; this.phrasePins.value = {}; this.persist() }

  changeFont(d: number) {
    const f = Math.min(40, Math.max(13, Number(prefs.readerFont.value) + d))
    prefs.readerFont.value = String(f)
  }

  // --- bookmarks ---

  isBookmarked(id: number) { return hasBookmark(this.lesson.id, id) }

  toggleBookmark(id: number) {
    const s = this.sentById.get(id)
    if (!s) return
    const cur = this.sentTr.value[id]
    const tr = !isError(cur) ? cur : s.translations[this.target] ?? ''
    const on = toggleBm(this.lang, this.lesson.id, this.title.value, id, s.text, tr)
    say(on ? 'Предложение в закладках' : 'Закладка удалена')
  }

  jumpTo(id: number) {
    this.jump.value = id
    this.page.value = this.indexOf(id)
  }

  // --- sentence mode ---

  /** Sentence mode slides up over the text, at the selected word's sentence or the first one on screen. */
  openSentenceMode() {
    if (!this.sentences.value.length) return
    const id = this.focusSent != null && this.visibleSents.has(this.focusSent)
      ? this.focusSent
      : this.visibleSents.size ? Math.min(...this.visibleSents) : null
    this.page.value = id == null ? 0 : this.indexOf(id)
    log('reader', 'Режим по предложениям')
    this.sentenceMode.value = true
  }

  closeSentenceMode() {
    this.sentenceMode.value = false
    const s = this.sentences.value[this.page.value]
    if (s) { this.focusSent = s.id; this.syncTo.value = s.id }
  }

  // --- translations ---

  /** Translation of a sentence: LingQ's stored one if there is one, otherwise machine translation. */
  private async sentenceTranslation(id: number): Promise<string> {
    const cur = this.sentTr.value[id]
    if (!isError(cur)) return cur
    const s = this.sentById.get(id)!
    const stored = s.translations[this.target]
    const t = stored ??
      (useGemini()
        ? await gemini.sentence(prompt.sentence(), this.lang, this.target, s.text)
        : await api.translate(this.lang, this.target, s.text))
    put(this.sentTr, id, t)
    if (stored == null) this.keepTr(s.text, t)
    return t
  }

  ensureSentence(id: number) {
    if (this.sentTr.value[id] || this.loadingSentences.has(id)) return
    this.loadingSentences.add(id)
    this.sentenceTranslation(id)
      .catch((e) => {
        put(this.sentTr, id, '⚠ ' + errorText(e))
        geminiFailed(e, () => { drop(this.sentTr, id); this.ensureSentence(id) }, true)
      })
      .finally(() => this.loadingSentences.delete(id))
  }

  /**
   * "Translate again": Gemini makes a different translation (told the current one is poor);
   * without Gemini LingQ's ChatGPT retranslation is used (it is stored in the lesson on LingQ too).
   */
  async retranslate(id: number) {
    const s = this.sentById.get(id)
    if (!s || this.retranslating.value[id]) return
    put(this.retranslating, id, true)
    try {
      const cur = this.sentTr.value[id]
      const current = !isError(cur) ? cur : s.translations[this.target] ?? ''
      let t: string | undefined
      if (useGemini()) t = await gemini.retranslate(prompt.retranslate(), this.lang, this.target, s.text, current)
      else {
        const r = await api.retranslate(this.lang, this.lesson.id, s.lingqIndex, this.target)
        t = r.get(s.lingqIndex) ?? r.values().next().value
      }
      if (!t?.trim()) say('Переводчик не вернул перевод')
      else { put(this.sentTr, id, t.trim()); this.keepTr(s.text, t.trim()) }
    } catch (e) {
      say(errorText(e) || 'Ошибка перевода')
      geminiFailed(e, () => void this.retranslate(id))
    }
    drop(this.retranslating, id)
  }

  /** Long press: sentence translation inserted right after the sentence (again: removed). */
  toggleSentence(id: number) {
    if (this.player) {
      this.bottomSent.value = this.bottomSent.value === id ? null : id
      if (this.bottomSent.value != null) { this.focusSent = id; this.ensureSentence(id) }
      return
    }
    this.focusSent = id
    if (this.shown.value[id]) drop(this.shown, id)
    else { log('reader', `Перевод предложения ${id}`); put(this.shown, id, true); this.ensureSentence(id) }
  }

  sentenceShown(id: number) { return this.allSentences.value || !!this.shown.value[id] }

  // --- words ---

  word(r: WordRef) { return this.sentById.get(refSent(r))?.tokens[refToken(r)]?.text ?? '' }

  // --- lesson audio (the player tab) ---

  /** MP3 of the lesson on LingQ (null = the lesson has no audio). */
  audioUrl = signal<string | null>(null)
  get audioKey() { return `${this.lang}_${this.lesson.id}` }
  /** This session is the player tab's: a tap seeks, a long press opens the word menu, sentence translation at the bottom. */
  player = false
  /** Start playing as soon as the lesson (and its audio) is loaded. */
  autoPlay = false
  /** Play from here (audio bookmark) instead of where listening stopped; -1 = no. */
  startAt = -1
  bottomSent = signal<number | null>(null)
  /** Long-pressed word in the player text: its small menu. */
  wordMenu = signal<WordRef | null>(null)
  /** Sentence sounding now (bold). */
  playingSent = computed(() => (audio.audioKey.value === this.audioKey ? this.sentenceAt(audio.position.value) : null))
  /** Reader: the sentence has a place in the lesson audio. */
  hasTime(id: number) { return !!this.audioUrl.value && (this.sentById.get(id)?.start ?? -1) >= 0 }

  /** Reader: listen from this sentence in the player tab. */
  listenFrom(id: number) {
    const st = this.sentById.get(id)
    if (!st || st.start < 0) return
    void import('../player').then((p) => p.listenAt(this.lang,
      { ...this.lesson, title: this.title.value, audio: this.audioUrl.value ?? '' }, st.start))
  }

  get hasTimes() { return this.sentences.value.some((x) => x.start >= 0) }

  private ensureAudio(): boolean {
    const url = this.audioUrl.value ?? this.lesson.audio ?? ''
    if (!url) return false
    const at = this.startAt
    this.startAt = -1
    if (audio.audioKey.value !== this.audioKey) {
      audio.load({ key: this.audioKey, url, title: this.title.value || this.lesson.title, image: this.lesson.imageUrl ?? '' },
        at >= 0 ? at : audio.savedPos(this.audioKey))
    } else if (at >= 0) audio.seekTo(at)
    return true
  }

  togglePlay() {
    if (audio.audioKey.value !== this.audioKey) { if (this.ensureAudio()) audio.play() } else audio.toggle()
  }

  /** Sentence sounding at [ms]: the last one that starts before it. */
  sentenceAt(ms: number): number | null {
    let best: Sent | null = null
    for (const x of this.sentences.value) if (x.start >= 0 && x.start <= ms && (!best || x.start >= best.start)) best = x
    return best?.id ?? null
  }

  sentenceStartAt(ms: number) { const id = this.sentenceAt(ms); return id != null ? this.sentById.get(id)!.start : ms }

  private endOf(st: Sent) {
    const next = this.sentences.value.find((x) => x.start > st.start)?.start ?? -1
    return st.end > st.start ? st.end + 150 : next > 0 ? next : st.start + 5000
  }

  /** Audio bookmark at the place sounding now (its sentence start). */
  markAudio() {
    if (!this.ensureAudio()) return
    const pos = audio.position.value
    const id = this.sentenceAt(pos)
    const st = id != null ? this.sentById.get(id) : undefined
    const ms = st && st.start >= 0 ? st.start : pos
    addAudioBookmark(this.lang, this.lesson.id, this.title.value, id ?? -1, st?.text ?? '', ms)
    log('audio', `Аудиозакладка ${audio.fmtTime(ms)}`)
    say(`Закладка ${audio.fmtTime(ms)}`)
  }

  audioMarks() { return audioBookmarks(this.lesson.id) }
  removeMark(b: Bm) { removeBookmark(b) }

  /** Player tab: play from the start of sentence [id]. */
  seekToSentence(id: number) {
    const st = this.sentById.get(id)
    if (!st || st.start < 0 || !this.ensureAudio()) return
    log('audio', `К началу предложения ${id}`)
    audio.seekTo(st.start)
  }

  loopSentence() {
    const st = this.sentById.get(this.sentenceAt(audio.position.value) ?? -1)
      ?? (this.focusSent != null ? this.sentById.get(this.focusSent) : undefined)
    if (!st || st.start < 0 || !this.ensureAudio()) return
    audio.setLoop(st.start - 100, this.endOf(st))
  }

  setLoopA() { audio.setA(this.sentenceStartAt(audio.position.value) - 100) }

  setLoopB() {
    const st = this.sentById.get(this.sentenceAt(audio.position.value) ?? -1)
    audio.setB(st && st.end > st.start ? st.end + 150 : audio.position.value)
  }

  private loopNote(start: boolean) {
    say(audio.looping.value ? `Повтор ${audio.fmtTime(audio.loopA.value)} – ${audio.fmtTime(audio.loopB.value)}`
      : start ? `Начало повтора ${audio.fmtTime(audio.loopA.value)} — теперь выберите конец` : 'Конец повтора задан — выберите начало')
  }

  loopStartAt(id: number) {
    const st = this.sentById.get(id)
    if (!st || st.start < 0 || !this.ensureAudio()) return
    audio.setA(st.start - 100)
    this.loopNote(true)
  }

  loopEndAt(id: number) {
    const st = this.sentById.get(id)
    if (!st || st.start < 0 || !this.ensureAudio()) return
    audio.setB(this.endOf(st))
    this.loopNote(false)
  }

  prevSentence() {
    const pos = audio.position.value
    const cur = this.sentById.get(this.sentenceAt(pos) ?? -1)
    // more than 1.5 s into the sentence: back to its start, else to the one before
    const target = cur && pos - cur.start > 1500 ? cur.start
      : Math.max(0, ...this.sentences.value.filter((x) => x.start >= 0 && x.start < (cur?.start ?? pos)).map((x) => x.start))
    audio.seekTo(target)
  }

  nextSentence() {
    const pos = audio.position.value
    const n = this.sentences.value.filter((x) => x.start > pos + 50).map((x) => x.start)
    if (n.length) audio.seekTo(Math.min(...n))
  }

  // --- level of the lesson by the AI ---

  levelBusy = signal(false)
  /** Result for the dialog: level 1..6 and the reason. */
  levelResult = signal<[number, string] | null>(null)

  async detectLevel() {
    if (this.levelBusy.value) return
    if (!useGemini()) { say('Нужен Gemini или другой ИИ: Настройки → Перевод'); return }
    const text = this.sentences.value.map((x) => x.text).join(' ')
    if (!text.trim()) return
    this.levelBusy.value = true
    say('ИИ оценивает уровень…')
    try {
      const r = await gemini.level(prompt.level(), this.lang, text)
      await api.setLevel(this.lang, this.lesson.id, r[0])
      log('reader', `Уровень урока ${this.lesson.id}: ${api.levelName(r[0])}`)
      this.levelResult.value = r
    } catch (e) {
      logError('reader', `Уровень урока ${this.lesson.id}`, e)
      say(errorText(e) || 'Не удалось определить уровень')
      geminiFailed(e, () => void this.detectLevel(), true)
    }
    this.levelBusy.value = false
  }

  // --- editing a sentence (saved to the lesson on LingQ) ---

  /** Sentence whose edit window is open. */
  editing = signal<number | null>(null)
  editSaving = signal(false)
  editError = signal<string | null>(null)

  startEdit(id: number) { this.editError.value = null; this.editSaving.value = false; this.editing.value = id }

  /** Current translation shown for the sentence (for the edit window). */
  shownTranslation(id: number): string {
    const t = this.sentTr.value[id]
    return t && !t.startsWith('⚠') ? t : this.sentById.get(id)?.translations[this.target] ?? ''
  }

  /**
   * Writes the new text (and translation) of sentence [id] into the lesson on LingQ: the lesson is read again,
   * a backup is kept, only this sentence is changed, the structure is saved as is.
   */
  async saveEdit(id: number, text: string, translation: string) {
    const s = this.sentById.get(id)
    if (!s) return
    const newText = text.trim().replace(/\s+/g, ' ')
    const newTr = translation.trim()
    if (!newText) { this.editError.value = 'Предложение не может быть пустым'; return }
    if (newText === s.text && newTr === this.shownTranslation(id)) { this.editing.value = null; return }
    this.editSaving.value = true; this.editError.value = null
    log('reader', `Правка предложения ${id}`)
    try {
      const paras = await api.editor(this.lang, this.lesson.id)
      const js = findSentence(paras, s)
      if (!js) throw new Error('Предложение не найдено в уроке на LingQ — обновите урок')
      await saveBackup(this.lang, this.lesson.id, this.title.value, paras)
      if (newText !== s.text) { js.text = newText; js.cleanText = newText }
      if (newTr && newTr !== s.translations[this.target]) {
        if (!Array.isArray(js.translations)) js.translations = []
        const own = js.translations.find((t: any) => t?.language === this.target)
        if (own) own.text = newTr
        else js.translations.push({ language: this.target, text: newTr })
      }
      await api.saveParagraphs(this.lang, this.lesson.id, paras)
      this.replaceSentence(s, newText, newTr)
      this.editing.value = null
      say('Предложение сохранено')
    } catch (e) {
      logError('reader', `Правка предложения ${id}`, e)
      this.editError.value = errorText(e)
    }
    this.editSaving.value = false
  }

  /** The edited sentence replaces the old one on screen; its word pins are dropped (the words moved). */
  private replaceSentence(old: Sent, text: string, tr: string) {
    const trs = { ...old.translations }
    if (tr) trs[this.target] = tr
    const n = new Sent(old.id, text, trs, old.lingqIndex, old.start, old.end)
    n.heading = old.heading
    this.sentById.set(n.id, n)
    this.paragraphs.value = this.paragraphs.value?.map((p) => p.map((x) => (x.id === n.id ? n : x))) ?? null
    this.sentences.value = this.sentences.value.map((x) => (x.id === n.id ? n : x))
    if (tr) put(this.sentTr, n.id, tr); else drop(this.sentTr, n.id)
    if (text !== old.text) {
      const mine = (k: string) => k.startsWith(`${n.id}:`)
      this.pins.value = Object.fromEntries(Object.entries(this.pins.value).filter(([k]) => !mine(k)))
      this.provisional.value = Object.fromEntries(Object.entries(this.provisional.value).filter(([k]) => !mine(k)))
      this.phrasePins.value = Object.fromEntries(Object.entries(this.phrasePins.value).filter(([k]) => !mine(k)))
      for (const k of [...this.aiCache.keys()]) if (mine(k)) this.aiCache.delete(k)
      this.persist()
    }
  }
  sentence(id: number) { return this.sentById.get(id) }

  /** LingQ's meanings (one request per word: a tap and the docked window share it). */
  private hints(word: string): Promise<string[]> {
    const c = this.hintsCache.get(word)
    if (c) return c
    const p = (async () => {
      let h = await api.hints(this.lang, word, this.target)
      if (!h.length && word !== word.toLowerCase()) h = await api.hints(this.lang, word.toLowerCase(), this.target)
      return h
    })()
    this.hintsCache.set(word, p)
    p.catch(() => { if (this.hintsCache.get(word) === p) this.hintsCache.delete(word) })
    return p
  }

  /**
   * Gemini: meaning of the word in this very sentence (cached). One request per word place, shared while in flight: a tap pins the word and the docked window opens at once,
   * and two separate requests could answer differently (the pin and the window disagreed).
   */
  private ai(r: WordRef): Promise<WordMeaning> {
    const c = this.aiCache.get(r)
    if (c) return c
    const s = this.sentById.get(refSent(r))!
    const p = gemini.word(prompt.word() + SEPARABLE, this.lang, this.target, this.word(r), s.text)
    this.aiCache.set(r, p)
    p.catch(() => { if (this.aiCache.get(r) === p) this.aiCache.delete(r) })
    return p
  }

  /** First popular meaning whose stem occurs in the sentence translation. */
  private pickByTranslation(hs: string[], tr: string): string | undefined {
    if (!tr.trim()) return undefined
    const trWords = tr.toLowerCase().match(/\p{L}+/gu) ?? []
    const fits = (h: string) => (h.toLowerCase().match(/\p{L}+/gu) ?? []).some((w) =>
      w.length >= 3 && trWords.some((t) => t.startsWith(w.slice(0, Math.max(3, Math.min(w.length - 2, 6))))))
    return hs.slice(0, 10).find(fits)
  }

  /**
   * Instant guess from LingQ's meanings: the one whose stem occurs in a sentence translation that is
   * already at hand (nothing slow is fetched), else the most popular one.
   */
  private quickGuess(r: WordRef, hs: string[]): string | undefined {
    const saved = this.chosen.value[this.word(r).toLowerCase()]
    if (saved) return saved
    const s = this.sentById.get(refSent(r))
    if (!s) return hs[0]
    const tr = this.sentTr.value[s.id] || s.translations[this.target] || ''
    return this.pickByTranslation(hs, tr) ?? hs[0]
  }

  /**
   * The meaning that fits this sentence: the one chosen earlier in this lesson, else Gemini's, else the first
   * popular meaning whose stem occurs in the sentence translation, else the most popular one, else machine translation.
   */
  private async contextual(r: WordRef, hs: string[]): Promise<string> {
    const w = this.word(r)
    const saved = this.chosen.value[w.toLowerCase()]
    if (saved) return saved
    if (useGemini()) {
      const best = await this.ai(r).then((m) => m.best, () => '')
      if (best.trim()) return best
    }
    const tr = await this.sentenceTranslation(refSent(r)).catch(() => '')
    return this.pickByTranslation(hs, tr) ?? hs[0] ?? (await api.translate(this.lang, this.target, w))
  }

  /** One tap: translation pinned above the word (tap again removes it). */
  tap(r: WordRef) {
    log('reader', `Нажатие «${this.word(r)}» (предл. ${refSent(r)})`)
    this.focusSent = refSent(r)
    const pk = this.phraseKeyAt(r)
    const hidden = this.pinsHidden.value
    // translations hidden: a tap on a translated word shows its translation for a moment instead of removing it
    if (pk) { if (hidden) this.peekAt(pk); else { drop(this.phrasePins, pk); this.persist() } return }
    if (r in this.pins.value) { if (hidden) this.peekAt(r); else { drop(this.pins, r); drop(this.provisional, r); this.persist() } return }
    put(this.pins, r, '')
    if (hidden) this.peekAt(r)
    const saved = this.chosen.value[this.word(r).toLowerCase()]
    if (saved) { put(this.pins, r, saved); this.persist(); return }
    if (!useGemini()) {
      this.hints(this.word(r))
        .then((hs) => this.contextual(r, hs))
        .catch(() => '⚠')
        .then((t) => { if (r in this.pins.value) { put(this.pins, r, t); this.persist() } })
      return
    }
    // Gemini: show LingQ's most likely meaning at once (grey), Gemini's answer replaces it (green)
    put(this.provisional, r, true)
    this.hints(this.word(r)).then((hs) => {
      const quick = this.quickGuess(r, hs)
      if (this.provisional.value[r] && quick && r in this.pins.value) put(this.pins, r, quick)
    }, () => {})
    this.ai(r).then((m) => m.best, (e) => {
      geminiFailed(e, () => { drop(this.pins, r); drop(this.provisional, r); this.tap(r) }, true)
      return null
    }).then((best) => {
      if (!(r in this.pins.value)) return // removed meanwhile
      drop(this.provisional, r)
      if (best?.trim()) put(this.pins, r, best)
      else if (!this.pins.value[r]) put(this.pins, r, '⚠')
      this.persist()
    })
  }

  /** Double tap: window with all meanings to choose from. */
  async openPopup(r: WordRef) {
    log('reader', `Окно слова «${this.word(r)}» (предл. ${refSent(r)})`)
    this.focusSent = refSent(r)
    this.popup.value = r
    this.popupHints.value = null
    this.popupBest.value = null
    this.popupError.value = null
    this.popupNote.value = ''
    this.popupAi.value = []
    this.popupThinking.value = false
    try {
      // LingQ's meanings and a quick guess first, Gemini adds its answer when ready
      const h = await this.hints(this.word(r)).catch(() => [] as string[])
      if (this.popup.value !== r) return
      this.popupHints.value = h
      if (useGemini()) {
        this.popupBest.value = this.quickGuess(r, h) ?? null
        this.popupThinking.value = true
        const m = await this.ai(r).catch((e) => {
          geminiFailed(e, () => { if (this.popup.value === r) void this.openPopup(r) }, true)
          return null
        })
        if (this.popup.value !== r) return
        this.popupThinking.value = false
        if (m) {
          this.popupBest.value = this.chosen.value[this.word(r).toLowerCase()] ?? (m.best.trim() || this.popupBest.value)
          this.popupAi.value = [m.best, ...m.options]
          this.popupNote.value = m.note
        }
      } else {
        const best = await this.contextual(r, h)
        if (this.popup.value === r) this.popupBest.value = best
      }
    } catch (e) {
      if (this.popup.value !== r) return
      this.popupThinking.value = false
      this.popupError.value = errorText(e)
    }
  }

  closePopup() { this.popup.value = null }

  /** The user picked [meaning] for the word: keep it for this lesson and pin it above the word. */
  choose(r: WordRef, meaning: string) {
    const m = meaning.trim()
    if (!m) return
    const w = this.word(r).toLowerCase()
    put(this.chosen, w, m)
    // this place and other places where this word is already pinned get the chosen meaning
    const pins = { ...this.pins.value, [r]: m }
    for (const k of Object.keys(pins)) if (this.word(k).toLowerCase() === w) pins[k] = m
    this.pins.value = pins
    drop(this.provisional, r)
    if (prefs.dockPanel.value !== '1') this.popup.value = null
    this.persist()
  }

  unpin(r: WordRef) {
    drop(this.pins, r)
    drop(this.chosen, this.word(r).toLowerCase())
    if (prefs.dockPanel.value !== '1') this.popup.value = null
    this.persist()
  }

  /**
   * Meanings for the window, without duplicates: the chosen one, the contextual one, Gemini's, LingQ's popular
   * meanings, and the parts of the two most popular ones ("уважаемый, почтенный" -> both words too).
   */
  options(r: WordRef): string[] {
    const hs = this.popupHints.value ?? []
    const parts = hs.slice(0, 2).flatMap((h) => h.split(/[,;/|]/).map((x) => x.trim())).filter(Boolean)
    const saved = this.chosen.value[this.word(r).toLowerCase()]
    const all = [saved, this.popupBest.value, ...this.popupAi.value, ...hs.slice(0, 8), ...parts]
    const seen = new Set<string>()
    return all
      .filter((x): x is string => !!x)
      .map((x) => x.trim().replace(/^[. ]+|[. ]+$/g, ''))
      .filter((x) => { const n = norm(x); if (!x || !n || seen.has(n)) return false; seen.add(n); return true })
  }

  // --- several words at once (long press + drag) ---

  /** Selection being dragged / the phrase whose window is open: tokens [from]..[to] of sentence [sent]. */
  sel = signal<{ sent: number; from: number; to: number } | null>(null)
  private selAnchor = -1
  phrase = signal<{ sent: number; from: number; to: number; text: string } | null>(null)
  phraseBest = signal<string | null>(null)
  phraseOptions = signal<string[]>([])
  phraseNote = signal('')
  phraseLoading = signal(false)
  phraseError = signal<string | null>(null)

  /** Translations of several words shown above them as one: "sent:from:to" -> text ("" while loading). */
  phrasePins = signal<Record<string, string>>({})

  /** Phrases pinned in sentence [sent]: token ranges and their translation. */
  phrasesIn(sent: number): { from: number; to: number; text: string }[] {
    const out: { from: number; to: number; text: string }[] = []
    for (const [k, v] of Object.entries(this.phrasePins.value)) {
      const [s, from, to] = phraseParts(k)
      if (s === sent) out.push({ from, to, text: v })
    }
    return out
  }

  private phraseKeyAt(r: WordRef): string | undefined {
    const sent = refSent(r), t = refToken(r)
    return Object.keys(this.phrasePins.value).find((k) => { const [s, f, e] = phraseParts(k); return s === sent && t >= f && t <= e })
  }

  inSelection(sent: number, token: number) {
    const x = this.sel.value
    return !!x && x.sent === sent && token >= x.from && token <= x.to
  }

  /** The sentence translation the long press opened by itself (closed again if the press becomes a phrase). */
  private longShown: number | null = null

  /**
   * Long press started on word [r]. [showTr]: the reader shows the sentence translation at once, while the finger
   * is still down (not after it is lifted and "Перевод предложения" picked in the menu).
   */
  selectStart(r: WordRef, showTr = false) {
    this.selAnchor = refToken(r)
    this.sel.value = { sent: refSent(r), from: this.selAnchor, to: this.selAnchor }
    this.longShown = null
    const id = refSent(r)
    if (showTr && !this.player && !this.sentenceMode.value && !this.sentenceShown(id)) { this.toggleSentence(id); this.longShown = id }
  }

  /** The finger is over word [r]: only words of the same sentence extend the selection. */
  selectTo(r: WordRef) {
    const x = this.sel.value
    if (!x || refSent(r) !== x.sent) return
    const t = refToken(r)
    const from = Math.min(this.selAnchor, t), to = Math.max(this.selAnchor, t)
    if (from !== x.from || to !== x.to) this.sel.value = { sent: x.sent, from, to }
  }

  /** Long press released: several words -> their translation; one word -> sentence translation (as before). */
  /** [menu]: a single long-pressed word opens the small menu (player only; the reader has none). */
  selectEnd(menu = false) {
    const x = this.sel.value
    const sent = x ? this.sentById.get(x.sent) : undefined
    const words = sent ? sent.tokens.slice(x!.from, x!.to + 1).filter((t) => t.isWord).length : 0
    if (!x || !sent || words < 2) {
      this.longShown = null
      this.sel.value = null
      if (sent && menu) this.wordMenu.value = ref(sent.id, x!.from) // a small menu right at the word
      return
    }
    // several words: their translation is wanted, not the sentence's
    if (this.longShown === sent.id && this.shown.value[sent.id]) drop(this.shown, sent.id)
    this.longShown = null
    void this.openPhrase(sent.id, x.from, x.to)
  }

  /** Contextual translation of several words together (Gemini, or LingQ's meanings + machine translation). */
  async openPhrase(sentId: number, from: number, to: number) {
    const st = this.sentById.get(sentId)
    if (!st) return
    const text = st.tokens.slice(from, to + 1).map((t) => t.text).join('').trim()
    const p = { sent: sentId, from, to, text }
    log('reader', `Словосочетание «${text.slice(0, 80)}» (предл. ${sentId})`)
    this.focusSent = sentId
    this.sel.value = { sent: sentId, from, to }
    this.phrase.value = p
    this.phraseBest.value = this.chosen.value[text.toLowerCase()] ?? null
    this.phraseOptions.value = []
    this.phraseNote.value = ''
    this.phraseError.value = null
    this.phraseLoading.value = true
    // the translation goes above the words as one; overlapping phrases of this sentence give way
    const key = `${sentId}:${from}:${to}`
    const pinsNow: Record<string, string> = {}
    for (const [k, v] of Object.entries(this.phrasePins.value)) {
      const [s, f, e] = phraseParts(k)
      if (k === key || s !== sentId || f > to || e < from) pinsNow[k] = v
    }
    pinsNow[key] = this.phraseBest.value ?? pinsNow[key] ?? ''
    this.phrasePins.value = pinsNow
    const pinned = (v: string) => { if (key in this.phrasePins.value) put(this.phrasePins, key, v) }
    try {
      let best: string
      if (useGemini()) {
        const pr = prompt.word() + '\n\nВажно: «{word}» — не одно слово, а словосочетание. ' +
          'Переведи его целиком как единое целое по смыслу в этом предложении; best может быть длиннее трёх слов. ' +
          'Если в нём есть глагол, чья отделяемая приставка стоит дальше в предложении, переводи глагол вместе с приставкой.'
        const m = await gemini.word(pr, this.lang, this.target, text, st.text)
        best = this.chosen.value[text.toLowerCase()] ?? m.best
        if (this.phrase.value === p) {
          this.phraseBest.value = best
          this.phraseOptions.value = this.dedupe([m.best, ...m.options])
          this.phraseNote.value = m.note
        }
      } else {
        const h = await this.hints(text).catch(() => [] as string[])
        const t = await api.translate(this.lang, this.target, text)
        best = this.chosen.value[text.toLowerCase()] ?? t
        if (this.phrase.value === p) {
          this.phraseBest.value = best
          this.phraseOptions.value = this.dedupe([t, ...h.slice(0, 8)])
        }
      }
      // the window may have been closed meanwhile: the words still get their translation
      if (!this.phrasePins.value[key] || this.phrase.value === p) pinned(this.phrase.value === p ? this.phraseBest.value ?? best : best)
      this.persist()
      // the translation is there: the selection colour goes, the phrase keeps its usual highlight
      const sl = this.sel.value
      if (sl && sl.sent === sentId && sl.from === from && sl.to === to) this.sel.value = null
    } catch (e) {
      if (!this.phrasePins.value[key]) drop(this.phrasePins, key)
      if (this.phrase.value === p) this.phraseError.value = errorText(e)
      geminiFailed(e, () => { if (this.phrase.value === p) void this.openPhrase(sentId, from, to) }, true)
    }
    if (this.phrase.value === p) this.phraseLoading.value = false
  }

  choosePhrase(meaning: string) {
    const p = this.phrase.value
    const m = meaning.trim()
    if (!p || !m) return
    put(this.chosen, p.text.toLowerCase(), m)
    this.phraseBest.value = m
    put(this.phrasePins, `${p.sent}:${p.from}:${p.to}`, m)
    this.persist()
  }

  closePhrase() { this.phrase.value = null; this.sel.value = null }

  private dedupe(l: string[]): string[] {
    const seen = new Set<string>()
    return l.map((x) => x.trim()).filter((x) => { const n = norm(x); if (!x || seen.has(n)) return false; seen.add(n); return true })
  }

  // --- chat with Gemini ---

  chatText() {
    const text = (this.paragraphs.value ?? []).map((p) => p.map((s) => s.text).join(' ')).join('\n\n')
    const marked = this.pinnedList()
    const sys = fill(prompt.chatText(), { lang: this.lang, target: this.target, text }) + (marked.length
      ? `\n\nСлова, которые ученик отметил в этом уроке (слово — перевод), учитывай их в ответах:\n${marked.map((m) => `- ${m.word} — ${m.tr}`).join('\n')}`
      : '')
    go({ k: 'chat', c: new ChatSession('Чат о тексте', sys, this.title.value, 'text') })
  }

  /** "Спросить" in the word / phrase window: the chat explains that word (or words) in its sentence. */
  chatWord(sentId: number, word: string) {
    const s = this.sentById.get(sentId)
    if (!s) return
    this.popup.value = null
    this.closePhrase()
    const sys = fill(prompt.chatWord(), { lang: this.lang, target: this.target, word, sentence: s.text })
    go({ k: 'chat', c: new ChatSession(`Чат о слове «${word}»`, sys, `${word}\n\n${s.text}`, 'word') })
  }

  chatSentence(id: number) {
    const s = this.sentById.get(id)
    if (!s) return
    this.popup.value = null
    const sys = fill(prompt.chatSentence(), { lang: this.lang, target: this.target, sentence: s.text })
    go({ k: 'chat', c: new ChatSession('Чат о предложении', sys, s.text) })
  }
}

/** "Go to source" of a bookmark: the open reader of that lesson jumps there, otherwise the lesson opens. */
export function openBookmark(b: Bookmark) {
  const list = stack.value
  const i = list.findLastIndex((s) => s.k === 'reader' && s.s.lesson.id === b.lessonId)
  if (i >= 0) {
    const s = (list[i] as { k: 'reader'; s: ReaderSession }).s
    while (stack.value.length - 1 > i) {
      stack.value = stack.value.slice(0, -1)
    }
    try { history.go(i - (list.length - 1)) } catch { /* ignore */ }
    s.jumpTo(b.sent)
  } else {
    go({ k: 'reader', s: new ReaderSession(b.lang, { id: b.lessonId, title: b.lessonTitle }, b.sent) })
  }
}
