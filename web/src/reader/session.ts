// Reader state for one lesson. Port of ReaderSession in Reader.kt.
import { signal, type Signal } from '@preact/signals'
import * as api from '../lingq'
import type { Lesson } from '../lingq'
import * as gemini from '../gemini'
import type { WordMeaning } from '../gemini'
import { prefs, prompt, trLang, useGemini } from '../settings'
import { fill } from '../prompts'
import { go, say, stack, errorText, geminiFailed } from '../app'
import { ChatSession } from '../chat'
import { hasBookmark, toggleBookmark as toggleBm, type Bookmark } from '../bookmarks'

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
      const before = t.text.match(/^\S*/)![0]
      const after = t.text.match(/\S*$/)![0]
      if (before) cur.push({ text: before, isWord: false, index: t.index })
      if (cur.length) out.push(cur)
      cur = []
      if (after) cur.push({ text: after, isWord: false, index: t.index })
    } else cur.push(t)
  }
  if (cur.length) out.push(cur)
  return out
}

/** One sentence of the lesson; [id] is unique in the lesson, [lingqIndex] is LingQ's 1-based index. */
export class Sent {
  readonly tokens: Token[]
  readonly chunks: Token[][]
  constructor(readonly id: number, readonly text: string, readonly translations: Record<string, string>, readonly lingqIndex: number) {
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

// --- helpers for immutable records in signals ---

function put<V>(s: Signal<Record<string, V>>, k: string | number, v: V) { s.value = { ...s.value, [k]: v } }
function drop<V>(s: Signal<Record<string, V>>, k: string | number) {
  if (!(k in s.value)) return
  const c = { ...s.value }
  delete c[k]
  s.value = c
}

// --- session ---

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
  textAnchor: number | null = null
  /** Sentences on the page being read. */
  visibleSents = new Set<number>()

  private sentById = new Map<number, Sent>()
  private hintsCache = new Map<string, string[]>()
  private aiCache = new Map<WordRef, WordMeaning>()
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
      const [l, raw] = await Promise.all([
        this.lesson.title ? Promise.resolve(this.lesson) : api.lesson(this.lang, this.lesson.id),
        api.editor(this.lang, this.lesson.id),
      ])
      const paras = this.parse(raw)
      this.loadNotes()
      this.title.value = l.title
      this.sentences.value = paras.flat()
      if (this.jump.value != null) this.page.value = this.indexOf(this.jump.value)
      this.paragraphs.value = paras
    } catch (e) {
      this.error.value = errorText(e)
    }
  }

  private indexOf(id: number) { return Math.max(0, this.sentences.value.findIndex((s) => s.id === id)) }

  private parse(a: any[]): Sent[][] {
    let id = 0
    const out: Sent[][] = []
    for (const p of a) {
      const para: Sent[] = []
      for (const s of p?.sentences ?? []) {
        const text = String(s.text ?? '').trim()
        if (!text) continue
        const tr: Record<string, string> = {}
        for (const t of s.translations ?? []) {
          const v = String(t.text ?? '').trim()
          if (v && !(t.language in tr)) tr[t.language] = v
        }
        const sent = new Sent(id, text, tr, typeof s.index === 'number' ? s.index : id + 1)
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
    } catch { /* none yet */ }
  }

  private persist() {
    const pins: Record<string, string> = {}
    for (const [k, v] of Object.entries(this.pins.value)) if (v && !v.startsWith('⚠')) pins[k] = v
    try { localStorage.setItem(this.notesKey, JSON.stringify({ pins, words: this.chosen.value })) } catch { /* ignore */ }
  }

  clearPins() { this.pins.value = {}; this.provisional.value = {}; this.persist() }

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
    const t = s.translations[this.target] ??
      (useGemini()
        ? await gemini.sentence(prompt.sentence(), this.lang, this.target, s.text)
        : await api.translate(this.lang, this.target, s.text))
    put(this.sentTr, id, t)
    return t
  }

  ensureSentence(id: number) {
    if (this.sentTr.value[id] || this.loadingSentences.has(id)) return
    this.loadingSentences.add(id)
    this.sentenceTranslation(id)
      .catch((e) => {
        put(this.sentTr, id, '⚠ ' + errorText(e))
        geminiFailed(e, () => { drop(this.sentTr, id); this.ensureSentence(id) })
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
      else put(this.sentTr, id, t.trim())
    } catch (e) {
      say(errorText(e) || 'Ошибка перевода')
      geminiFailed(e, () => void this.retranslate(id))
    }
    drop(this.retranslating, id)
  }

  /** Long press: sentence translation inserted right after the sentence (again: removed). */
  toggleSentence(id: number) {
    this.focusSent = id
    if (this.shown.value[id]) drop(this.shown, id)
    else { put(this.shown, id, true); this.ensureSentence(id) }
  }

  sentenceShown(id: number) { return this.allSentences.value || !!this.shown.value[id] }

  // --- words ---

  word(r: WordRef) { return this.sentById.get(refSent(r))?.tokens[refToken(r)]?.text ?? '' }
  sentence(id: number) { return this.sentById.get(id) }

  private async hints(word: string): Promise<string[]> {
    const c = this.hintsCache.get(word)
    if (c) return c
    let h = await api.hints(this.lang, word, this.target)
    if (!h.length && word !== word.toLowerCase()) h = await api.hints(this.lang, word.toLowerCase(), this.target)
    this.hintsCache.set(word, h)
    return h
  }

  /** Gemini: meaning of the word in this very sentence (cached). */
  private async ai(r: WordRef): Promise<WordMeaning> {
    const c = this.aiCache.get(r)
    if (c) return c
    const s = this.sentById.get(refSent(r))!
    const m = await gemini.word(prompt.word(), this.lang, this.target, this.word(r), s.text)
    this.aiCache.set(r, m)
    return m
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
    this.focusSent = refSent(r)
    if (r in this.pins.value) { drop(this.pins, r); drop(this.provisional, r); this.persist(); return }
    put(this.pins, r, '')
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
      geminiFailed(e, () => { drop(this.pins, r); drop(this.provisional, r); this.tap(r) })
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
          geminiFailed(e, () => { if (this.popup.value === r) void this.openPopup(r) })
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
    const norm = (t: string) => t.toLowerCase().replace(/ё/g, 'е').replace(/[^\p{L}\p{N}]+/gu, ' ').trim()
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

  inSelection(sent: number, token: number) {
    const x = this.sel.value
    return !!x && x.sent === sent && token >= x.from && token <= x.to
  }

  selectStart(r: WordRef) {
    this.selAnchor = refToken(r)
    this.sel.value = { sent: refSent(r), from: this.selAnchor, to: this.selAnchor }
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
  selectEnd() {
    const x = this.sel.value
    const sent = x ? this.sentById.get(x.sent) : undefined
    const words = sent ? sent.tokens.slice(x!.from, x!.to + 1).filter((t) => t.isWord).length : 0
    if (!x || !sent || words < 2) { this.sel.value = null; if (sent) this.toggleSentence(sent.id); return }
    void this.openPhrase(sent.id, x.from, x.to)
  }

  /** Contextual translation of several words together (Gemini, or LingQ's meanings + machine translation). */
  async openPhrase(sentId: number, from: number, to: number) {
    const st = this.sentById.get(sentId)
    if (!st) return
    const text = st.tokens.slice(from, to + 1).map((t) => t.text).join('').trim()
    const p = { sent: sentId, from, to, text }
    this.focusSent = sentId
    this.sel.value = { sent: sentId, from, to }
    this.phrase.value = p
    this.phraseBest.value = this.chosen.value[text.toLowerCase()] ?? null
    this.phraseOptions.value = []
    this.phraseNote.value = ''
    this.phraseError.value = null
    this.phraseLoading.value = true
    try {
      if (useGemini()) {
        const pr = prompt.word() + '\n\nВажно: «{word}» — не одно слово, а словосочетание. ' +
          'Переведи его целиком как единое целое по смыслу в этом предложении; best может быть длиннее трёх слов.'
        const m = await gemini.word(pr, this.lang, this.target, text, st.text)
        if (this.phrase.value !== p) return
        this.phraseBest.value = this.chosen.value[text.toLowerCase()] ?? m.best
        this.phraseOptions.value = this.dedupe([m.best, ...m.options])
        this.phraseNote.value = m.note
      } else {
        const h = await this.hints(text).catch(() => [] as string[])
        const t = await api.translate(this.lang, this.target, text)
        if (this.phrase.value !== p) return
        this.phraseBest.value = this.chosen.value[text.toLowerCase()] ?? t
        this.phraseOptions.value = this.dedupe([t, ...h.slice(0, 8)])
      }
    } catch (e) {
      if (this.phrase.value === p) this.phraseError.value = errorText(e)
      geminiFailed(e, () => { if (this.phrase.value === p) void this.openPhrase(sentId, from, to) })
    }
    if (this.phrase.value === p) this.phraseLoading.value = false
  }

  choosePhrase(meaning: string) {
    const p = this.phrase.value
    const m = meaning.trim()
    if (!p || !m) return
    put(this.chosen, p.text.toLowerCase(), m)
    this.phraseBest.value = m
    this.persist()
  }

  closePhrase() { this.phrase.value = null; this.sel.value = null }

  private dedupe(l: string[]): string[] {
    const seen = new Set<string>()
    const norm = (t: string) => t.toLowerCase().replace(/ё/g, 'е').replace(/[^\p{L}\p{N}]+/gu, ' ').trim()
    return l.map((x) => x.trim()).filter((x) => { const n = norm(x); if (!x || seen.has(n)) return false; seen.add(n); return true })
  }

  // --- chat with Gemini ---

  chatText() {
    const text = (this.paragraphs.value ?? []).map((p) => p.map((s) => s.text).join(' ')).join('\n\n')
    const sys = fill(prompt.chatText(), { lang: this.lang, target: this.target, text })
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
