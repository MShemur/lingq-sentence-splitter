// Upload of a file / pasted text to a LingQ course, with text cleaning and the sentence split fixed right away.
// Port of Android ImportSession (photos / OCR left out: not available on the web).
import { signal, type Signal } from '@preact/signals'
import * as api from '../lingq'
import type { Course } from '../lingq'
import * as gemini from '../gemini'
import { courses, geminiFailed, say } from '../app'
import { lang, prefs, prompt, useGemini } from '../settings'
import { addReports, chunks, cleanText, countWords, EMPTY_REPORT, reportText, splitLong } from './cleaner'
import { fix, type Change } from './fixer'
import { readFile } from './sources'
import { saveBackup } from './backups'

export class ImportItem {
  title: Signal<string>
  text: Signal<string>
  selected: Signal<boolean>
  constructor(title: string, text: string, selected: boolean) {
    this.title = signal(title); this.text = signal(text); this.selected = signal(selected)
  }
}

/** A lesson whose split was fixed after the upload (for the report). */
export interface LessonFix { id: number; title: string; changes: Change[] }

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

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
  aiLevel = signal(localStorage.getItem('import.aiLevel') !== '0')
  splitWords = signal('3000')
  running = signal(false)
  finished = signal(false)
  progress = signal(0)
  log = signal<string[]>([])
  error = signal<string | null>(null)
  fixes = signal<LessonFix[]>([])
  lang = lang()

  // --- source ---

  private setSingle(title: string, text: string) {
    this.pasteMode.value = true; this.isBook.value = false; this.items.value = []
    this.pasteTitle.value = title; this.pasteText.value = text
  }

  private startLoad(msg: string) {
    this.loading.value = true; this.status.value = msg; this.error.value = null
    this.cleanNote.value = null; this.before = null; this.canUndo.value = false
  }

  paste(text: string) {
    this.startLoad(''); this.loading.value = false
    this.setSingle(this.pasteTitle.value, text)
    if (prefs.autoClean.value === '1' && text.trim()) this.cleanQuick()
  }

  /** An empty field to type or paste into (when the clipboard cannot be read). */
  typeIn() { this.setSingle(this.pasteTitle.value, this.pasteText.value) }

  async loadFile(file: File) {
    this.startLoad('Читаю файл…')
    this.pasteMode.value = false
    try {
      const { name, texts } = await readFile(file, (s) => { this.status.value = s })
      this.sourceName.value = name
      if (texts.length === 1) this.setSingle(texts[0].title || name, texts[0].text)
      else {
        this.isBook.value = true
        // short EPUB parts (cover, title page, imprint) are not preselected
        this.items.value = texts.map((t) => new ImportItem(t.title || name, t.text, countWords(t.text) >= 100))
      }
      if (prefs.autoClean.value === '1') this.cleanQuick()
    } catch (e) {
      this.error.value = `Не удалось прочитать файл: ${(e as Error).message}`
    }
    this.loading.value = false; this.status.value = ''
  }

  // --- cleaning ---

  cleaning = signal(false)
  cleanProgress = signal(0)
  cleanNote = signal<string | null>(null)
  canUndo = signal(false)
  private before: [string, string[]] | null = null

  private snapshot() { this.before = [this.pasteText.value, this.items.value.map((i) => i.text.value)]; this.canUndo.value = true }

  undoClean() {
    const b = this.before
    if (!b) return
    this.pasteText.value = b[0]
    this.items.value.forEach((it, i) => { if (b[1][i] != null) it.text.value = b[1][i] })
    this.before = null; this.canUndo.value = false; this.cleanNote.value = null
  }

  /** The texts to clean, each with a setter. */
  private targets(): [string, (v: string) => void][] {
    if (this.pasteMode.value) return [[this.pasteText.value, (v) => { this.pasteText.value = v }]]
    return this.items.value.filter((i) => i.selected.value).map((i) => [i.text.value, (v: string) => { i.text.value = v }])
  }

  /** Quick clean on the device (rules, no AI). */
  cleanQuick() {
    if (!this.before) this.snapshot()
    let r = EMPTY_REPORT
    for (const [t, set] of this.targets()) { const [c, rep] = cleanText(t); r = addReports(r, rep); set(c) }
    this.cleanNote.value = reportText(r)
  }

  /** Clean with AI (light model), piece by piece; the quick clean runs first and stays if AI fails. */
  async cleanAi() {
    if (!useGemini()) { say('Для чистки с ИИ включите Gemini или другой ИИ: Настройки → Перевод'); return }
    if (!this.before) this.snapshot()
    this.cleaning.value = true; this.cleanProgress.value = 0; this.cleanNote.value = null
    const jobs = this.targets().map(([t, set]) => [cleanText(t)[0], set] as const)
    for (const [t, set] of jobs) set(t)
    try {
      const total = Math.max(1, jobs.reduce((n, [t]) => n + chunks(t).length, 0))
      let done = 0
      for (const [t, set] of jobs) {
        const out: string[] = []
        for (const piece of chunks(t)) {
          const r = await gemini.cleanPiece(prompt.clean(), this.lang, piece)
          done++; this.cleanProgress.value = done / total
          out.push(r || piece)
        }
        set(out.join('\n\n'))
      }
      this.cleanNote.value = 'ИИ почистил текст. Пролистайте и проверьте; если что-то пропало — «Вернуть как было».'
    } catch (e) {
      this.cleanNote.value = `ИИ не справился (${String((e as Error).message).slice(0, 120)}). Оставлена быстрая очистка.`
      geminiFailed(e, () => { this.undoClean(); void this.cleanAi() }, true)
    }
    this.cleaning.value = false
  }

  // --- upload ---

  private selectedTexts(): [string, string][] {
    if (this.pasteMode.value) return this.pasteText.value.trim() ? [[this.pasteTitle.value.trim() || 'Текст', this.pasteText.value]] : []
    return this.items.value.filter((i) => i.selected.value)
      .map((i, n) => [this.isBook.value ? `${n + 1}: ${i.title.value}` : i.title.value, i.text.value])
  }

  selectedCount() { return this.pasteMode.value ? (this.pasteText.value.trim() ? 1 : 0) : this.items.value.filter((i) => i.selected.value).length }

  private line(i: number, msg: string) { const l = [...this.log.value]; l[i] = msg; this.log.value = l }

  async run() {
    const createTitle = this.newCourseTitle.value.trim()
    if (this.newCourse.value && !createTitle) { say('Введите название нового курса'); return }
    if (!this.newCourse.value && !this.course.value) { say('Выберите курс'); return }
    const l = this.lang
    const max = Number(this.splitWords.value) || 0
    const jobs = this.selectedTexts().flatMap(([title, text]) => {
      const parts = splitLong(text, max)
      return parts.length === 1 ? [[title, parts[0]] as const] : parts.map((p, i) => [`${title} (${i + 1})`, p] as const)
    })
    if (!jobs.length) { say('Нечего загружать'); return }
    this.running.value = true; this.finished.value = false; this.error.value = null
    this.log.value = []; this.fixes.value = []; this.progress.value = 0
    try {
      let c = this.course.value
      if (this.newCourse.value) {
        this.log.value = [`➕ Курс «${createTitle}»`]
        c = await api.createCourse(l, createTitle)
        this.log.value = [`✅ Курс «${c.title}» создан`]
        courses.value = [c, ...(courses.value ?? [])]
        this.course.value = c
        this.newCourse.value = false
      }
      const course = c!
      // uploads go one after another (keeps the lesson order); LingQ then processes each lesson on its own,
      // so every lesson is waited for and fixed in parallel (at most 4 at a time)
      const base = this.log.value.length
      this.log.value = [...this.log.value, ...jobs.map(([t]) => `· ${t} · в очереди`)]
      let done = 0, failed = 0
      const found: (LessonFix | null)[] = jobs.map(() => null)
      const step = () => { done++; this.progress.value = done / jobs.length }
      let active = 0
      const waiting: (() => void)[] = []
      const acquire = () => (active < 4 ? (active++, Promise.resolve()) : new Promise<void>((r) => waiting.push(() => { active++; r() })))
      const release = () => { active--; waiting.shift()?.() }
      const fixing: Promise<void>[] = []
      for (let i = 0; i < jobs.length; i++) {
        const [title, text] = jobs[i]
        this.line(base + i, `⬆ ${title}`)
        let id: number
        try {
          id = await api.importLesson(l, course.id, title, text)
        } catch (e) {
          this.line(base + i, `❌ ${(e as Error).message} · ${title}`); failed++; step(); continue
        }
        const wantLevel = this.aiLevel.value && useGemini()
        if (!this.autoFix.value && !wantLevel) { this.line(base + i, `✅ ${title}`); step(); continue }
        this.line(base + i, `⏳ LingQ обрабатывает · ${title}`)
        fixing.push((async () => {
          await acquire()
          try {
            const r = this.autoFix.value ? await this.fixImported(l, id, title) : (await api.waitProcessed(l, id), null)
            found[i] = r
            const lv = wantLevel ? await this.setLevel(l, id, text) : ''
            this.line(base + i, `${r ? `✅ исправлено ${r.changes.length}` : '✅ в порядке'}${lv} · ${title}`)
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
      const [n] = await gemini.level(l, text)
      await api.setLevel(l, id, n)
      return ` · уровень ${api.CEFR[n - 1]}`
    } catch (e) {
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
      const changes = fix(fixed)
      if (!changes.length) break // nothing (left) to fix
      if (!result) await saveBackup(l, id, title, paras)
      await api.saveParagraphs(l, id, fixed)
      if (!result) result = { id, title, changes }
      await sleep(5000) // then re-check that LingQ kept it
    }
    return result
  }
}
