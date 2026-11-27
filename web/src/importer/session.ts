// Upload of a file / pasted text to a LingQ course, with text cleaning and the sentence split fixed right away.
// Port of Android ImportSession (photos / OCR left out: not available on the web).
import { signal, type Signal } from '@preact/signals'
import * as api from '../lingq'
import type { Course, Lesson } from '../lingq'
import * as gemini from '../gemini'
import { courses, geminiFailed, say } from '../app'
import { lang, prefs, prompt, useGemini } from '../settings'
import { addReports, chunks, cleanText, countWords, EMPTY_REPORT, LINGQ_MAX_WORDS, markdownHeadings, reportText, spaceQuotes, splitLong, splitSentences } from './cleaner'
import { fix, type Change } from './fixer'
import { readFile } from './sources'
import { fetchArticle, parseMarked } from './article'
import { openPdf, pagesLabel, type PdfDoc } from './pdf'
import * as pdfBook from '../pdfbook'
import * as bookPics from '../bookpics'
import { genderNote, takeGenderMark } from '../prompts'
import { saveBackup } from './backups'
import { clock, cutMp3, decodePcm, mp3Frames, playerDuration, RATE, wav, type Mp3 } from './audio'
import { align, fromSegments, matchText, sttKeyFor, sttModel, sttProvider, transcribe, type TSeg, type TWord } from './stt'
import { go } from '../app'
import { log } from '../applog'

/** An audio file waiting for the choice of its part. */
export interface AudioPick { file: Blob; name: string; durationMs: number; mp3: Mp3 | null; transcript: TSeg[] | null
  /** The podcast's own text without times, if any. */
  text: string | null
}

export class ImportItem {
  title: Signal<string>
  text: Signal<string>
  selected: Signal<boolean>
  /** Titles of the chapters merged into this one (shown as «объединено: …»). */
  merged: string[]
  constructor(title: string, text: string, selected: boolean, readonly pics: bookPics.BookPic[] = [], merged: string[] = []) {
    this.title = signal(title); this.text = signal(text); this.selected = signal(selected); this.merged = merged
  }
  /** A copy with its own signals (for the undo snapshot). */
  copy() { return new ImportItem(this.title.value, this.text.value, this.selected.value, this.pics, this.merged) }
}

/** One chapter of a merge plan: the chapters [idx] (in order) become one, with the title of chapter [target]. */
export interface MergeSeg { idx: number[]; target: number }

/** The merge plan rule: chapters under 300 words chain into the next one (a receiver still small moves on);
 * the last small one goes into the previous chapter (of its run). Excluded chapters stay separate: they cut the
 * list into runs, and each run is planned alone. Returns one segment per resulting chapter. */
export function planSmall(words: number[], excluded: ReadonlySet<number>): MergeSeg[] {
  const out: MergeSeg[] = []
  let run: number[] = []
  const flush = () => { out.push(...planRun(run, words)); run = [] }
  words.forEach((_, k) => {
    if (excluded.has(k)) { flush(); out.push({ idx: [k], target: k }); return }
    run.push(k)
  })
  flush()
  return out
}

function planRun(run: number[], words: number[]): MergeSeg[] {
  const segs: MergeSeg[] = []
  const count = (ks: number[]) => ks.reduce((n, k) => n + words[k], 0)
  let carry: number[] = [] // small chapters waiting to join the next one
  for (const k of run) {
    const cur = [...carry, k]
    carry = []
    if (count(cur) < 300) carry = cur
    else segs.push({ idx: cur, target: k })
  }
  if (carry.length) {
    const prev = segs[segs.length - 1]
    if (prev) segs[segs.length - 1] = { idx: [...prev.idx, ...carry], target: prev.target }
    else segs.push({ idx: carry, target: carry[carry.length - 1] })
  }
  return segs
}

/** The row button «Объединить со следующей»: chapter i takes chapter i+1 (unless that one is excluded). */
export function planPair(i: number, excluded: ReadonlySet<number>): MergeSeg[] {
  return excluded.has(i + 1) ? [{ idx: [i], target: i }] : [{ idx: [i, i + 1], target: i }]
}

/** A lesson whose split was fixed after the upload (for the report). */
export interface LessonFix { id: number; title: string; changes: Change[] }

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

/** Until the page is on screen again (the iPad pauses a page in the background and its requests fail). */
const visible = () => document.visibilityState === 'visible' ? Promise.resolve()
  : new Promise<void>((r) => document.addEventListener('visibilitychange', function f() {
    if (document.visibilityState === 'visible') { document.removeEventListener('visibilitychange', f); r() }
  }))

/** One AI clean request, tried up to 3 times (it broke off at 30 % when the iPad left the page). */
async function cleanPieceRetry(lang: string, piece: string): Promise<string> {
  for (let a = 0; ; a++) {
    try { return await gemini.cleanPiece(prompt.clean(), lang, piece) } catch (e) {
      if (a >= 2) throw e
      log('import', `Чистка ИИ: кусок не прошёл (${(e as Error).message}), ещё раз`)
      await visible(); await sleep(2000)
    }
  }
}

/** The import screen keeps its session: leaving it while text is there (or the AI is cleaning) and coming back
 * shows the same text and the same running cleaning. */
let lastImport: ImportSession | null = null
export function importSession(): ImportSession {
  const s = lastImport
  if (s && (s.cleaning.value || s.running.value || (!s.finished.value && (s.pasteText.value.trim() || s.items.value.length)))) return s
  return (lastImport = new ImportSession())
}

/** The course the import used last ("importTo.<lang>", as Android). */
export function lastImportCourse(lang: string): number | null {
  try { return Number(localStorage.getItem(`importTo.${lang}`)) || null } catch { return null }
}
export function rememberImportCourse(lang: string, id: number) {
  try { localStorage.setItem(`importTo.${lang}`, String(id)) } catch { /* ignore */ }
}

/**
 * One lesson to upload; [audio] its audio, [words] the recognised words with their times. [rest]: text that does
 * not fit into one LingQ import (more than LINGQ_MAX_WORDS words): it is added to the same lesson afterwards, so a
 * long podcast stays one lesson with one audio.
 */
interface Job { title: string; text: string; rest: string; audio: Blob | null; file: string; words: TWord[]
  /** EPUB pictures whose paragraph is in this lesson. */
  pics?: bookPics.BookPic[]
}

/** The pictures of [pics] whose paragraph (after them, else before them) is in [text]; [taken] = already given to a lesson. */
function picsOf(text: string, pics: bookPics.BookPic[], taken: Set<bookPics.BookPic>): bookPics.BookPic[] {
  const keys = text.split(/\n\s*\n/).map(bookPics.paraKey).filter(Boolean)
  const has = (a: string | null) => a != null && keys.some((k) => bookPics.sameKey(k, a))
  const out = pics.filter((p) => !taken.has(p) && (has(p.before) || has(p.after)))
  out.forEach((p) => taken.add(p))
  return out
}

/** Adds the paragraphs of [rest] after the lesson's own (sentence objects shaped like LingQ's). */
async function extendLesson(l: string, id: number, rest: string) {
  const o = await api.editorObj(l, id)
  const paras: any[] = o?.paragraphs ?? []
  const lastP = paras[paras.length - 1]
  const tmpl = lastP?.sentences?.[0]
  if (!lastP || !tmpl) throw new Error('урок пустой, текст не дописан')
  let idx = paras.reduce((n, p) => n + (p.sentences?.length ?? 0), 0)
  for (const pt of rest.split(/\n\s*\n/)) {
    const ss = splitSentences(pt.trim())
    if (!ss.length) continue
    paras.push({
      ...lastP,
      ...('index' in lastP ? { index: paras.length + 1 } : {}),
      sentences: ss.map((t) => ({
        ...tmpl, text: t, cleanText: t, translations: [], phrases: [], notes: [], index: ++idx,
        ...('timestamp' in tmpl ? { timestamp: [null, null] } : {}),
      })),
    })
  }
  await api.saveParagraphs(l, id, paras)
  log('import', `Урок ${id}: дописан до ${idx} предложений (длиннее одного импорта LingQ)`)
}

export class ImportSession {
  readonly k = 'import'
  sourceName = signal('')
  items = signal<ImportItem[]>([])
  isBook = signal(false)
  loading = signal(false)
  status = signal('')
  pasteMode = signal(false)
  pasteTitle = signal('')
  pasteText = signal('')
  course = signal<Course | null>(null)
  newCourse = signal(false)
  newCourseTitle = signal('')
  autoFix = signal(true)
  /** The AI sets the level (A1–C2) of every new lesson; remembered. */
  /** Kept in settings (pref splitWords). */
  splitWords = prefs.splitWords
  running = signal(false)
  finished = signal(false)
  progress = signal(0)
  log = signal<string[]>([])
  error = signal<string | null>(null)
  fixes = signal<LessonFix[]>([])
  /** Lessons made by the last upload, in order (for "Открыть урок"). */
  uploaded = signal<Lesson[]>([])
  /** What happened to the podcast's own text, for the result line ('' = not tried). */
  textNote = signal('')

  /** After an upload: back to an empty form for the next lesson. */
  newImport() {
    this.finished.value = false; this.log.value = []; this.fixes.value = []; this.uploaded.value = []; this.error.value = null; this.textNote.value = ''
    this.pasteText.value = ''; this.pasteTitle.value = ''; this.items.value = []; this.dropAudio(); this.cover.value = null
    this.pdfBook = null; this.pdfBookPages = []; this.pdfBookKinds = null
  }
  lang = lang()

  // --- source ---

  /** EPUB pictures of the single text in the form. */
  private singlePics: bookPics.BookPic[] = []

  private setSingle(title: string, text: string, pics: bookPics.BookPic[] = []) {
    this.singlePics = pics
    this.pasteMode.value = true; this.isBook.value = false; this.items.value = []
    this.pasteTitle.value = title; this.pasteText.value = text
  }

  private startLoad(msg: string) {
    this.dropAudio(); this.cover.value = null; this.pdfBook = null; this.pdfBookPages = []; this.pdfBookKinds = null
    this.finished.value = false; this.log.value = []; this.fixes.value = []; this.uploaded.value = []
    this.loading.value = true; this.status.value = msg; this.error.value = null
    this.cleanNote.value = null; this.before = null; this.canUndo.value = false
  }

  paste(text: string) {
    const marked = parseMarked(text)
    // a link alone: the article is fetched (not pasted as the text of the link)
    if (!marked && /^https?:\/\/\S+$/i.test(text.trim())) { void this.loadUrl(text.trim()); return }
    this.startLoad(''); this.loading.value = false
    // text copied by the bookmarklet from Safari: its title comes with it
    if (marked) this.setSingle(marked.title || this.pasteTitle.value, marked.text)
    else this.setSingle(this.pasteTitle.value, text)
  }

  /** An article by its link: the app's /article page gives the site's text (a refused site says so). */
  async loadUrl(url: string) {
    this.startLoad('Загружаю статью…')
    try {
      const a = await fetchArticle(url)
      this.setSingle(a.title || 'Статья', a.text)
    } catch (e) {
      this.error.value = (e as Error).message
    }
    this.loading.value = false; this.status.value = ''
  }

  /** An empty field to type or paste into (when the clipboard cannot be read). */
  typeIn() { this.setSingle(this.pasteTitle.value, this.pasteText.value, this.singlePics) }

  async loadFile(file: File) {
    this.startLoad('Читаю файл…')
    this.pasteMode.value = false
    try {
      const { name, texts, pdf, cover } = await readFile(file, (s) => { this.status.value = s })
      this.sourceName.value = name
      if (cover) this.setCover(cover, name)
      if (pdf) { this.pdfPick.value?.close(); this.pdfPick.value = pdf; this.loading.value = false; this.status.value = ''; return }
      if (texts.length === 1) this.setSingle(texts[0].title || name, texts[0].text, texts[0].pics)
      else {
        this.isBook.value = true
        // short EPUB parts (cover, title page, imprint) are not preselected
        this.items.value = texts.map((t) => new ImportItem(t.title || name, t.text, countWords(t.text) >= 100, t.pics))
      }
    } catch (e) {
      this.error.value = `Не удалось прочитать файл: ${(e as Error).message}`
    }
    this.loading.value = false; this.status.value = ''
  }

  // --- chapters: merging (EPUB and other multi-part sources) ---

  /** Applies a merge plan (only the segments of 2+ chapters). Each one becomes a chapter with the title of its target,
   * the texts and pictures in order, and «объединено» = the titles of the others. The list before goes to undo.
   * Returns how many chapters went away. */
  applyMerge(segs: MergeSeg[]): number {
    const src = this.items.value
    const groups = segs.filter((g) => g.idx.length > 1)
    const n = groups.reduce((a, g) => a + g.idx.length - 1, 0)
    if (!n) return 0
    const start = new Map<number, MergeSeg>()
    const inside = new Set<number>()
    for (const g of groups) { start.set(g.idx[0], g); g.idx.slice(1).forEach((k) => inside.add(k)) }
    const out: ImportItem[] = []
    src.forEach((_, k) => {
      if (inside.has(k)) return
      const g = start.get(k)
      if (!g) { out.push(src[k]); return }
      const parts = g.idx.map((j) => src[j])
      const merged = parts.flatMap((p, x) => (g.idx[x] === g.target ? p.merged : [p.title.value, ...p.merged]))
      out.push(new ImportItem(src[g.target].title.value, parts.map((p) => p.text.value).join('\n\n'), true, parts.flatMap((p) => p.pics), merged))
    })
    this.before = { kind: 'merge', items: src.map((it) => it.copy()) }
    this.canUndo.value = true
    this.items.value = out
    say(`Объединено глав: ${n}`)
    return n
  }

  // --- PDF: which pages ---

  /** A PDF of several pages waiting for the page choice (the picker sheet is open). */
  pdfPick = signal<PdfDoc | null>(null)

  cancelPdf() { this.pdfPick.value?.close(); this.pdfPick.value = null; this.pickBook = null }

  // --- PDF book: the PDF kept in this browser, its pages shown in the reader with the words over them ---

  /** The PDF book being picked (its doc id), or null for the plain PDF import. */
  private pickBook: string | null = null
  /** The page picker is open for a PDF book (it then offers the captions switch for scans). */
  get pickingBook() { return this.pickBook != null }
  /** The text in the form comes from this PDF book (doc id) and these pages: the uploaded lessons are registered. */
  private pdfBook: string | null = null
  private pdfBookPages: number[] = []
  /** Block kinds of scanned pages that went into the text (pdfbook TEXT_KINDS), null = all words. */
  private pdfBookKinds: string | null = null
  /** The text in the form comes from a PDF book (for the hint on the form). */
  get isPdfBook() { return this.pdfBook != null }

  async loadPdfBook(file: File) {
    this.startLoad('Открываю PDF…')
    this.pasteMode.value = false; this.items.value = []
    try {
      const bytes = new Uint8Array(await file.arrayBuffer())
      if (String.fromCharCode(...bytes.subarray(0, 4)) !== '%PDF') throw new Error('это не PDF')
      this.status.value = 'Сохраняю PDF…'
      const doc = await pdfBook.importFile(bytes)
      const pdf = await openPdf(bytes.slice())
      this.sourceName.value = file.name.replace(/\.[^.]+$/, '')
      this.pdfPick.value?.close()
      this.pickBook = doc
      if (pdf.pages > 1) { this.pdfPick.value = pdf; this.loading.value = false; this.status.value = ''; return }
      this.pdfPick.value = pdf
      await this.importPdfPages([0])
      return
    } catch (e) {
      this.error.value = `Не удалось прочитать PDF: ${(e as Error).message}`
    }
    this.loading.value = false; this.status.value = ''
  }

  /** PDF book: the chosen pages are recognised with word boxes (once per file) and their text goes into the form. */
  private async readPdfBook(pdf: PdfDoc, doc: string, list: number[]) {
    const r = await pdfBook.recognise(doc, pdf.raw, list, (st) => { this.status.value = st }, this.lang)
    // scanned pages read with Gemini: captions and boxes go into the text after their paragraph (page numbers not),
    // or stay out when the switch is off
    const skipped = r.pages.reduce((k, p) => k + p.words.filter((w) => w.kind && !pdfBook.TEXT_KINDS.includes(w.kind)).length, 0)
    const kinds = !skipped ? null : prefs.pdfCaptions.value === '1' ? pdfBook.ALL_KINDS : pdfBook.TEXT_KINDS
    const text = pdfBook.text(r.pages, kinds)
    const name = this.sourceName.value
    const title = list.length === pdf.pages ? name : `${name}, стр. ${pagesLabel(list)}`
    // a long book is not put into the text field (too slow to edit there)
    if (text.length > 30_000) {
      this.pasteMode.value = false; this.isBook.value = false
      this.items.value = [new ImportItem(title, text, true)]
    } else this.setSingle(title, text)
    this.pdfBook = doc; this.pdfBookPages = list; this.pdfBookKinds = kinds
    log('import', `PDF-книга ${doc}: ${list.length} из ${pdf.pages} стр., слов ${countWords(text)}, сканов: ${r.ocr} (Gemini: ${r.ordered})${kinds === pdfBook.TEXT_KINDS ? `, подписи и прочее не в тексте: ${skipped} слов` : ''}`)
    const notes: string[] = []
    if (r.scans.length) notes.push(`${r.scans.length === 1 ? 'Страницу' : 'Страницы'} ${pagesLabel(r.scans)} (скан) не удалось распознать — нужна связь с интернетом`)
    if (r.ocr > r.ordered) {
      notes.push(prefs.geminiKey.value.trim()
        ? 'Порядок чтения части сканов — без Gemini (ответ не подошёл)'
        : 'Сканы распознаны без Gemini: колонки и подписи могут идти не по порядку (ключ Gemini — ⚙ Настройки → Перевод и ИИ)')
    }
    if (kinds === pdfBook.TEXT_KINDS) notes.push(`Подписи к фото, врезки и номера страниц не вошли в текст (${skipped} сл.)`)
    if (notes.length) say(notes.join('. '))
    if (!text.trim()) this.error.value = 'На выбранных страницах не нашлось текста'
  }

  /** Reads pages [list] (0-based) of the PDF being picked; the title gets ", стр. 3–5, 8" unless all were taken. */
  async importPdfPages(list: number[]) {
    const pdf = this.pdfPick.value
    if (!pdf || !list.length) return
    this.pdfPick.value = null
    const book = this.pickBook
    this.pickBook = null
    if (book) {
      this.startLoad('Распознаю PDF…')
      try { await this.readPdfBook(pdf, book, list) } catch (e) {
        log('import', `PDF-книга ✗ ${(e as Error).message}`)
        this.error.value = `Не удалось распознать PDF: ${(e as Error).message}`
      } finally { pdf.close() }
      this.loading.value = false; this.status.value = ''
      return
    }
    this.startLoad('Читаю PDF…')
    try {
      const { text, scans } = await pdf.text(list, (s) => { this.status.value = s })
      const name = this.sourceName.value
      const all = list.length === pdf.pages
      this.setSingle(all ? name : `${name}, стр. ${pagesLabel(list)}`, text)
      if (scans.length) say(`${scans.length === 1 ? 'Страница' : 'Страницы'} ${pagesLabel(scans)} — скан, без текста: пропущено`)
    } catch (e) {
      this.error.value = `Не удалось прочитать PDF: ${(e as Error).message}`
    } finally { pdf.close() }
    this.loading.value = false; this.status.value = ''
  }

  // --- audio: a part is cut, recognised and uploaded with the lesson ---

  /** Audio file waiting for the choice of its part (the sheet is open). */
  audioPick = signal<AudioPick | null>(null)
  /** Audio that goes into the lesson (the cut part or the whole file), its file name, and the recognised words. */
  audio = signal<Blob | null>(null)
  audioName = signal('')
  private audioWords: TWord[] = []

  dropAudio() { this.audio.value = null; this.audioName.value = ''; this.audioWords = [] }

  /** Cover picture of the book / podcast; put on the course when [coverToCourse]. */
  cover = signal<Blob | null>(null)
  coverToCourse = signal(true)

  setCover(c: Blob | null, title: string) {
    this.cover.value = c
    if (c && !this.newCourseTitle.value.trim() && title.trim()) this.newCourseTitle.value = title
  }

  /** [name] = file name (podcast episode); [transcript] / [text] = the podcast's own (timed / plain), if any. */
  async pickAudio(file: Blob, name = (file as File).name ?? 'audio', transcript: TSeg[] | null = null, text: string | null = null) {
    const prov = sttProvider(prefs.sttProvider.value)
    if (!transcript && !sttKeyFor(prov)) { say(`Задайте ключ распознавания (${prov.name})`); go({ k: 'settingsSpeech' }); return }
    this.startLoad('Открываю аудио…')
    try {
      const mp3 = /\.mp3$/i.test(name) || file.type === 'audio/mpeg' ? await mp3Frames(file) : null
      const durationMs = mp3 ? Math.round(mp3.duration * 1000) : await playerDuration(file)
      this.audioPick.value = { file, name, durationMs, mp3, transcript, text }
    } catch (e) {
      this.error.value = `Не удалось открыть аудио: ${(e as Error).message}`
    }
    this.loading.value = false; this.status.value = ''
  }

  /** Recognises [fromMs, toMs) of the picked audio (toMs null = to the end). */
  async transcribePart(fromMs: number, toMs: number | null, useTranscript = false, useText = false) {
    const pk = this.audioPick.value
    if (!pk) return
    const prov = sttProvider(prefs.sttProvider.value)
    const ready = useTranscript ? pk.transcript : null
    if (!ready && !sttKeyFor(prov)) { say(`Задайте ключ распознавания (${prov.name})`); go({ k: 'settingsSpeech' }); return }
    this.audioPick.value = null
    const keepCover = this.cover.value
    this.startLoad('Вырезаю кусок…')
    this.cover.value = keepCover
    this.pasteMode.value = false; this.items.value = []
    try {
      const base = pk.name.replace(/\.[^.]+$/, '')
      const dur = pk.durationMs
      const end = toMs ?? (dur > 0 ? dur : null)
      const whole = fromMs <= 0 && (end == null || (dur > 0 && end >= dur))
      let blob: Blob = pk.file, name = pk.name, mp3 = pk.mp3, pcm: Int16Array | null = null
      if (!whole) {
        if (pk.mp3) {
          blob = cutMp3(pk.file, pk.mp3, fromMs / 1000, end != null ? end / 1000 : Infinity)
          name = 'part.mp3'
          mp3 = await mp3Frames(blob)
        } else {
          // other formats: decoded and written as 16 kHz WAV
          this.status.value = 'Готовлю аудио…'
          const all = await decodePcm(pk.file)
          const a = Math.min(all.length, Math.round((fromMs * RATE) / 1000))
          const b = end == null ? all.length : Math.max(a, Math.min(all.length, Math.round((end * RATE) / 1000)))
          if (b <= a) throw new Error('в выбранном куске нет звука')
          pcm = all.slice(a, b)
          blob = wav(pcm, 0, pcm.length)
          name = 'part.wav'
          mp3 = null
        }
      }
      const title = whole ? base : `${base}, ${clock(fromMs)}–${end != null ? clock(end) : 'конец'}`
      let t = ready ? fromSegments(ready, fromMs, end)
        : await transcribe({ blob, name, mp3, pcm }, this.lang, prov, sttKeyFor(prov), sttModel(prov.id), (st) => { this.status.value = st })
      this.textNote.value = ready ? 'Взята расшифровка подкаста.' : ''
      if (!ready && useText && pk.text) {
        const m = matchText(pk.text, t, whole)
        if (m) { t = m; log('stt', `Текст подкаста: ${m.text.length} знаков, время из распознавания`); this.textNote.value = 'Взят текст подкаста, время из распознавания.' }
        else { log('stt', 'Текст подкаста не совпал с распознанным, беру распознанный'); this.textNote.value = 'Текст подкаста не совпал с речью — взят распознанный.' }
      }
      this.setSingle(title, t.text)
      this.audio.value = blob
      this.audioName.value = whole ? pk.name : `${title}.${name.split('.').pop()}`
      this.audioWords = t.words
      if (!t.text.trim()) this.error.value = 'В аудио не нашлось речи'
    } catch (e) {
      log('stt', `Распознавание ✗ ${(e as Error).message}`)
      this.error.value = `Не удалось распознать: ${(e as Error).message}`
    }
    this.loading.value = false; this.status.value = ''
  }

  /** Sets the time of every sentence of a fresh lesson from the recognised words; returns a note for the log. */
  private async setTimes(l: string, id: number, words: TWord[]): Promise<string> {
    const state = await api.audioState(l, id)
    if (state === 'none') return 'LingQ не принял аудио'
    const paras = await api.editor(l, id)
    const sents: any[] = paras.flatMap((p: any) => p.sentences ?? [])
    if (!sents.length || !words.length) return 'аудио есть'
    const times = align(sents.map((x) => String(x.text ?? '')), words)
    sents.forEach((x, i) => { x.timestamp = [Math.round(times[i][0] * 100) / 100, Math.round(times[i][1] * 100) / 100] })
    await api.saveParagraphs(l, id, paras)
    log('import', `Урок ${id}: время для ${sents.length} предложений (аудио: ${state})`)
    return 'аудио и время предложений'
  }

  // --- cleaning ---

  cleaning = signal(false)
  cleanProgress = signal(0)
  cleanNote = signal<string | null>(null)
  canUndo = signal(false)
  /** Undo of the last step: the texts before a clean, or the whole chapter list before a merge. */
  private before: { kind: 'clean'; text: string; texts: string[] } | { kind: 'merge'; items: ImportItem[] } | null = null

  private snapshot() {
    this.before = { kind: 'clean', text: this.pasteText.value, texts: this.items.value.map((i) => i.text.value) }
    this.canUndo.value = true
  }

  undoClean() {
    const b = this.before
    if (!b) return
    if (b.kind === 'merge') this.items.value = b.items
    else {
      this.pasteText.value = b.text
      this.items.value.forEach((it, i) => { if (b.texts[i] != null) it.text.value = b.texts[i] })
    }
    this.before = null; this.canUndo.value = false; this.cleanNote.value = null
  }

  /** The texts to clean, each with a setter. */
  private targets(): [string, (v: string) => void][] {
    if (this.pasteMode.value) return [[this.pasteText.value, (v) => { this.pasteText.value = v }]]
    return this.items.value.filter((i) => i.selected.value).map((i) => [i.text.value, (v: string) => { i.text.value = v }])
  }

  /** Quick clean on the device (rules, no AI). */
  cleanQuick() {
    if (this.before?.kind !== 'clean') this.snapshot()
    let r = EMPTY_REPORT
    for (const [t, set] of this.targets()) { const [c, rep] = cleanText(t); r = addReports(r, rep); set(c) }
    this.cleanNote.value = reportText(r)
  }

  /** Clean with AI (light model), piece by piece; the quick clean runs first and stays if AI fails. */
  async cleanAi() {
    if (!useGemini()) { say('Для чистки с ИИ включите Gemini или другой ИИ: Настройки → Перевод и ИИ'); return }
    if (this.before?.kind !== 'clean') this.snapshot()
    this.cleaning.value = true; this.cleanProgress.value = 0; this.cleanNote.value = null
    const jobs = this.targets().map(([t, set]) => [cleanText(t)[0], set] as const)
    for (const [t, set] of jobs) set(t)
    try {
      const total = Math.max(1, jobs.reduce((n, [t]) => n + chunks(t).length, 0))
      let done = 0
      for (const [t, set] of jobs) {
        const out: string[] = []
        let gender = false
        for (const piece of chunks(t)) {
          const [r, g] = takeGenderMark(await cleanPieceRetry(this.lang, piece))
          done++; this.cleanProgress.value = done / total
          out.push(r || piece); gender ||= g
        }
        if (gender) out.push(genderNote(this.lang))
        set(spaceQuotes(out.join('\n\n')))
      }
      this.cleanNote.value = 'ИИ почистил текст. Пролистайте и проверьте; если что-то пропало — «Вернуть как было».'
    } catch (e) {
      this.cleanNote.value = `ИИ не справился (${String((e as Error).message).slice(0, 120)}). Оставлена быстрая очистка.`
      geminiFailed(e, () => { this.undoClean(); void this.cleanAi() }, true)
    }
    this.cleaning.value = false
  }

  // --- upload ---

  private selectedTexts(): [string, string, bookPics.BookPic[]][] {
    if (this.pasteMode.value) return this.pasteText.value.trim() ? [[this.pasteTitle.value.trim() || 'Текст', this.pasteText.value, this.singlePics]] : []
    return this.items.value.filter((i) => i.selected.value)
      .map((i, n) => [this.isBook.value ? `${n + 1}: ${i.title.value}` : i.title.value, i.text.value, i.pics])
  }

  selectedCount() { return this.pasteMode.value ? (this.pasteText.value.trim() ? 1 : 0) : this.items.value.filter((i) => i.selected.value).length }

  private line(i: number, msg: string) { const l = [...this.log.value]; l[i] = msg; this.log.value = l }

  async run() {
    const createTitle = this.newCourseTitle.value.trim()
    if (this.newCourse.value && !createTitle) { say('Введите название нового курса'); return }
    if (!this.newCourse.value && !this.course.value) { say('Выберите курс'); return }
    const l = this.lang
    const audio = this.pasteMode.value ? this.audio.value : null
    const audioFile = this.audioName.value.replace(/[^\p{L}\p{N}._ -]/gu, '_')
    // books and files: parts of the chosen size, never above LingQ's own limit (its parts would not be fixed)
    const max = Math.min(Number(this.splitWords.value) || LINGQ_MAX_WORDS, LINGQ_MAX_WORDS)
    let jobs: Job[]
    if (audio) {
      // audio (podcast, recording): one lesson, however long; what does not fit into one import is added afterwards
      const [title, text] = this.selectedTexts()[0] ?? ['', '']
      const parts = splitLong(text, LINGQ_MAX_WORDS - 1000)
      jobs = [{ title, text: parts[0] ?? '', rest: parts.slice(1).join('\n\n'), audio, file: audioFile, words: this.audioWords }]
    } else {
      jobs = this.selectedTexts().flatMap(([title, text, pics]) => {
        const parts = splitLong(text, max)
        const taken = new Set<bookPics.BookPic>()
        return (parts.length === 1 ? [[title, parts[0]]] : parts.map((p, i) => [`${title} (${i + 1})`, p]))
          .map(([t, x]) => ({ title: t, text: x, rest: '', audio: null, file: '', words: [], pics: pics.length ? picsOf(x, pics, taken) : [] }))
      })
    }
    if (!jobs.length || !jobs[0].text.trim()) { say('Нечего загружать'); return }
    // PDF book: the pages each lesson covers (the PDF reader shows them)
    const book = audio ? null : this.pdfBook
    let bookPages: (number[] | null)[] = []
    if (book) {
      try {
        const all = (await Promise.all(this.pdfBookPages.map((i) => pdfBook.load(book, i)))).filter((p): p is pdfBook.PdfPage => !!p)
        bookPages = pdfBook.ranges(all, jobs.map((j) => j.text), this.pdfBookKinds)
      } catch (e) { log('import', `PDF-книга: страницы уроков ✗ ${(e as Error).message}`) }
    }
    this.running.value = true; this.finished.value = false; this.error.value = null
    this.log.value = []; this.fixes.value = []; this.uploaded.value = []; this.progress.value = 0
    try {
      let c = this.course.value
      if (this.newCourse.value) {
        this.log.value = [`➕ Курс «${createTitle}»`]
        c = await api.createCourse(l, createTitle, this.coverToCourse.value ? this.cover.value : null)
        this.log.value = [`✅ Курс «${c.title}» создан`]
        courses.value = [c, ...(courses.value ?? [])]
        this.course.value = c
        this.newCourse.value = false
        rememberImportCourse(l, c.id)
      } else if (this.cover.value && this.coverToCourse.value) {
        try {
          await api.setCourseImage(l, c!.id, this.cover.value)
          this.log.value = [...this.log.value, `🖼 Обложка курса «${c!.title}» обновлена`]
        } catch (e) {
          log('import', `Обложка курса ✗ ${(e as Error).message}`)
          this.log.value = [...this.log.value, `⚠ Обложку не удалось поставить: ${(e as Error).message}`]
        }
      }
      const course = c!
      // uploads go one after another (keeps the lesson order); LingQ then processes each lesson on its own,
      // so every lesson is waited for and fixed in parallel (at most 4 at a time)
      const base = this.log.value.length
      this.log.value = [...this.log.value, ...jobs.map((j) => `· ${j.title} · в очереди`)]
      let done = 0, failed = 0
      const found: (LessonFix | null)[] = jobs.map(() => null)
      const step = () => { done++; this.progress.value = done / jobs.length }
      let active = 0
      const waiting: (() => void)[] = []
      const acquire = () => (active < 4 ? (active++, Promise.resolve()) : new Promise<void>((r) => waiting.push(() => { active++; r() })))
      const release = () => { active--; waiting.shift()?.() }
      const fixing: Promise<void>[] = []
      for (let i = 0; i < jobs.length; i++) {
        const { title, text, rest, audio, file: audioFile, words } = jobs[i]
        this.line(base + i, `⬆ ${title}`)
        let id: number
        try {
          id = audio ? await api.importLessonAudio(l, course.id, title, text, audio, audioFile) : await api.importLesson(l, course.id, title, text)
        } catch (e) {
          this.line(base + i, `❌ ${(e as Error).message} · ${title}`); failed++; step(); continue
        }
        this.uploaded.value = [...this.uploaded.value, { id, title }]
        const pics = jobs[i].pics ?? []
        if (pics.length) {
          await bookPics.save(l, id, pics).then(() => log('import', `Урок ${id}: картинок ${pics.length}`),
            (e) => log('import', `Картинки урока ${id} ✗ ${(e as Error).message}`))
        }
        if (book) {
          await pdfBook.register(l, id, book, bookPages[i] ?? this.pdfBookPages, this.pdfBookKinds)
            .catch((e) => log('import', `PDF-книга: урок ${id} ✗ ${(e as Error).message}`))
        }
        // the split is always fixed and, with an AI, the level always set (no switches: the user wants both)
        const wantLevel = useGemini()
        if (!this.autoFix.value && !wantLevel && !audio) { this.line(base + i, `✅ ${title}`); step(); continue }
        this.line(base + i, `⏳ LingQ обрабатывает · ${title}`)
        fixing.push((async () => {
          await acquire()
          try {
            if (rest) { await api.waitProcessed(l, id); await extendLesson(l, id, rest) }
            const r = this.autoFix.value ? await this.fixImported(l, id, title) : (await api.waitProcessed(l, id), null)
            found[i] = r
            let au = ''
            if (audio) {
              try { au = ' · ' + await this.setTimes(l, id, words) } catch (e) {
                log('import', `Время предложений урока ${id} ✗ ${(e as Error).message}`)
                au = ' · время предложений не записано'
              }
            }
            const lv = wantLevel ? await this.setLevel(l, id, text) : ''
            const fx = this.autoFix.value ? (r ? `✅ исправлено ${r.changes.length}` : '✅ в порядке') : '✅'
            this.line(base + i, `${fx}${au}${lv} · ${title}`)
          } catch (e) {
            failed++; this.line(base + i, `❌ ${(e as Error).message} · ${title}`)
          } finally { release(); step() }
        })())
        await sleep(300)
      }
      await Promise.all(fixing)
      this.fixes.value = found.filter((x): x is LessonFix => !!x)
      this.finished.value = true
      say(failed === 0 ? `Загружено уроков: ${jobs.length}` : `Загружено: ${jobs.length - failed}, ошибок: ${failed}`)
    } catch (e) {
      this.error.value = (e as Error).message
    }
    this.running.value = false
  }

  /** The AI's level for a new lesson (after LingQ processed it); " · уровень B1" for the log, '' when it failed. */
  private async setLevel(l: string, id: number, text: string): Promise<string> {
    try {
      const [n] = await gemini.level(prompt.level(), l, text)
      // LingQ may still be busy with the fresh lesson: check the level stuck, set it again once if not
      for (let attempt = 0; ; attempt++) {
        await api.setLevel(l, id, n)
        await sleep(1500)
        const got = (await api.lessonLevel(l, id)).toLowerCase()
        if (!got || got === api.LINGQ_LEVELS[n - 1].toLowerCase()) break
        log('import', `Урок ${id}: уровень ${api.CEFR[n - 1]} не сохранился (LingQ: ${got}), попытка ${attempt + 1}`)
        if (attempt >= 1) return ` · уровень ${api.CEFR[n - 1]} не сохранился на LingQ`
        await sleep(4000)
      }
      log('import', `Урок ${id}: уровень ${api.levelName(n)}`)
      return ` · уровень ${api.CEFR[n - 1]}`
    } catch (e) {
      log('import', `Урок ${id}: уровень не определён ✗ ${(e as Error).message}`)
      geminiFailed(e, () => {}, true)
      return ' · уровень не определён'
    }
  }

  /** Waits until LingQ finished processing a fresh lesson, fixes its split and checks the fix stayed. */
  private async fixImported(l: string, id: number, title: string): Promise<LessonFix | null> {
    await api.waitProcessed(l, id)
    let result: LessonFix | null = null
    for (let attempt = 0; attempt < 3; attempt++) {
      const paras = await api.editor(l, id)
      const fixed = structuredClone(paras)
      const heads = attempt === 0 ? markdownHeadings(fixed) : 0 // "## Title" from the AI cleaning
      const changes = fix(fixed)
      if (!changes.length && !heads) break // nothing (left) to fix
      if (!result) await saveBackup(l, id, title, paras)
      await api.saveParagraphs(l, id, fixed)
      if (!result) result = { id, title, changes }
      await sleep(5000) // then re-check that LingQ kept it
    }
    return result
  }
}
