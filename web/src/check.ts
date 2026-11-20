// Split checker for existing lessons ("Исправить разбивку"): dry run over lessons (algorithm of importer/fixer.ts, optionally
// plus the AI), then, after confirmation, a backup and a write per lesson. Port of Android CheckSession.
import { signal } from '@preact/signals'
import * as api from './lingq'
import type { Lesson } from './lingq'
import { errorText, geminiFailed, go, say, sayLong, stack } from './app'
import { fix, norm, type Change } from './importer/fixer'
import { saveBackup } from './importer/backups'
import { useGemini } from './settings'
import * as gemini from './gemini'
import { log, logError } from './applog'
import { player } from './player'
import { matchWords, remapPinned, wordSlots } from './reader/remap'
import { notesKeyOf, parseSentences, type ReaderSession } from './reader/session'

type Obj = Record<string, any>

export interface LessonFix { lesson: Lesson; original: Obj[]; fixed: Obj[]; changes: Change[]; aiMerges: number }

export interface LinkTarget { lang: string | null; courseId: number | null; lessonId: number | null }

/** Same rules as parseTarget in Android Model.kt (and parse_target in lingq_fix_splits.py). */
export function parseTarget(s: string): LinkTarget | null {
  const t = (/https?:\/\/\S+/.exec(s)?.[0] ?? s).trim()
  if (/^\d+$/.test(t)) return { lang: null, courseId: Number(t), lessonId: null }
  const lang = /\/learn\/([a-z]{2,3}(?:-[a-z]+)?)\//.exec(t)?.[1] ?? null
  const c = /\/course\/(\d+)/.exec(t) ?? /\/collections?\/(\d+)/.exec(t)
  if (c) return { lang, courseId: Number(c[1]), lessonId: null }
  const l = /\/(?:lesson|reader|lessons|editor)\/(\d+)/.exec(t)
  if (l) return { lang, courseId: null, lessonId: Number(l[1]) }
  return null
}

const AI_SYSTEM = 'Ты проверяешь разбивку немецкого текста на предложения. Дан нумерованный список фрагментов (номер: текст). ' +
  'Найди места, где фрагмент ошибочно отрезан от предыдущего: предложение продолжается (разрыв после сокращения, ' +
  'порядкового числа вроде «19.» или «400.», инициала, номера и т. п.). Верни JSON {"merge":[номера]} — номера ' +
  'фрагментов, которые нужно приклеить к предыдущему. Если ошибок нет — {"merge":[]}. Текст не меняй и ничего не добавляй.'

const CHUNK = 120

/** Sentences of the paragraphs in the order fix() counts them (global numbering). */
function sentenceTexts(paras: Obj[]): string[] {
  const out: string[] = []
  for (const p of paras) {
    if (!p || !Array.isArray(p.sentences)) continue
    for (const s of p.sentences as Obj[]) if (s) out.push(norm(String(s.text ?? '')))
  }
  return out
}

function parseMerge(raw: string): unknown[] {
  let t = raw.trim().replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/, '')
  try { return JSON.parse(t)?.merge ?? [] } catch { /* find the object in the text */ }
  t = /\{[\s\S]*\}/.exec(t)?.[0] ?? ''
  try { return JSON.parse(t)?.merge ?? [] } catch { return [] }
}

/** 1-based numbers of sentences the AI wants glued to the previous one. */
async function aiMerges(paras: Obj[], progress: (n: number, of: number) => void): Promise<Set<number>> {
  const texts = sentenceTexts(paras)
  const n = texts.length
  const found = new Set<number>()
  const parts = Math.ceil(n / CHUNK)
  for (let start = 0, part = 0; start < n; start += CHUNK, part++) {
    progress(part + 1, parts)
    const end = Math.min(n, start + CHUNK)
    const lines: string[] = []
    // the previous chunk's last sentence goes first as context (its own number, it is not judged here)
    for (let i = Math.max(0, start - 1); i < end; i++) lines.push(`${i + 1}: ${texts[i]}`)
    const raw = await gemini.generate(AI_SYSTEM, [{ user: true, text: lines.join('\n') }], true, true)
    for (const v of parseMerge(raw)) {
      const k = typeof v === 'string' && /^\d+$/.test(v.trim()) ? Number(v) : v
      if (typeof k === 'number' && Number.isInteger(k) && k >= 2 && k <= n && k > start && k <= end) found.add(k)
    }
  }
  return found
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

/** Dry run over some lessons, then (after confirmation) writing the fixes with backups. */
export class CheckSession {
  readonly k = 'check'
  label = signal('')
  running = signal(true)
  applying = signal(false)
  done = signal(false)
  progress = signal(0)
  checked = signal(0)
  status = signal('')
  error = signal<string | null>(null)
  results = signal<LessonFix[]>([])
  savedIds = signal<number[]>([])
  useAi = signal(false)

  private raw = new Map<number, { lesson: Lesson; paragraphs: Obj[] }>()
  private aiCache = new Map<number, Set<number>>()
  private run = 0

  constructor(readonly lang: string, private lessons: Lesson[], label: string) {
    this.label.value = label
  }

  get total() { return this.lessons.length }
  changeCount() { return this.results.value.reduce((a, r) => a + r.changes.length, 0) }

  /** The AI toggle: the dry run starts over (without a key: the usual message, nothing changes). */
  setAi(on: boolean) {
    if (on && !useGemini()) { say('Для проверки с ИИ включите Gemini или другой ИИ: Настройки → Перевод и ИИ'); return }
    if (this.applying.value || this.useAi.value === on) return
    this.useAi.value = on
    void this.start()
  }

  async start() {
    const my = ++this.run
    const ai = this.useAi.value
    this.running.value = true
    this.error.value = null
    this.done.value = false
    this.savedIds.value = []
    this.results.value = []
    this.checked.value = 0
    this.progress.value = 0
    const found: LessonFix[] = []
    try {
      for (let i = 0; i < this.lessons.length; i++) {
        if (my !== this.run) return
        const l0 = this.lessons[i]
        this.status.value = `Проверяю ${i + 1}/${this.lessons.length}: ${l0.title || l0.id}`
        let r = this.raw.get(l0.id)
        if (!r) {
          const lesson = l0.title ? l0 : await api.lesson(this.lang, l0.id)
          r = { lesson: { ...l0, ...lesson, title: lesson.title || l0.title }, paragraphs: await api.editor(this.lang, l0.id) }
          this.raw.set(l0.id, r)
        }
        if (my !== this.run) return
        if (this.lessons.length === 1) this.label.value = r.lesson.title || this.label.value
        const fixed = structuredClone(r.paragraphs) as Obj[]
        let extra: Set<number> | undefined
        if (ai) {
          extra = this.aiCache.get(l0.id)
          if (!extra) {
            extra = await aiMerges(fixed, (p, of) => {
              if (my === this.run) this.status.value = `ИИ проверяет ${i + 1}/${this.lessons.length}: ${r!.lesson.title || l0.id}` + (of > 1 ? ` (часть ${p} из ${of})` : '')
            })
            this.aiCache.set(l0.id, extra)
          }
        }
        if (my !== this.run) return
        const algo = fix(structuredClone(r.paragraphs) as Obj[]).filter((c) => c.kind === 'merge').length
        const changes = fix(fixed, extra && ((g) => extra!.has(g)))
        if (changes.length) {
          found.push({ lesson: r.lesson, original: r.paragraphs, fixed, changes, aiMerges: Math.max(0, changes.filter((c) => c.kind === 'merge').length - algo) })
          this.results.value = [...found]
        }
        this.checked.value = i + 1
        this.progress.value = (i + 1) / this.lessons.length
        await sleep(150)
      }
      this.status.value = ''
    } catch (e) {
      if (my !== this.run) return
      logError('check', 'Проверка разбивки', e)
      this.error.value = errorText(e)
      if (ai) geminiFailed(e, () => { void this.start() }, true)
    }
    if (my === this.run) this.running.value = false
  }

  async apply() {
    if (this.applying.value || this.running.value) return
    this.applying.value = true
    this.error.value = null
    const list = this.results.value
    try {
      for (let i = 0; i < list.length; i++) {
        const r = list[i]
        if (this.savedIds.value.includes(r.lesson.id)) continue
        this.status.value = `Записываю ${i + 1}/${list.length}: ${r.lesson.title}`
        await saveBackup(this.lang, r.lesson.id, r.lesson.title, r.original)
        await api.saveParagraphs(this.lang, r.lesson.id, r.fixed)
        // an open lesson's notes are moved by its reader (reloadReader); the others are moved in storage here
        if (!openSessions(this.lang, r.lesson.id).length) moveStoredNotes(this.lang, r.lesson.id, r.original, r.fixed)
        this.savedIds.value = [...this.savedIds.value, r.lesson.id]
        log('check', `Урок ${r.lesson.id}: склеено ${r.changes.length}`)
        this.reloadReader(r.lesson.id)
        if (i < list.length - 1) await sleep(1000)
      }
      this.done.value = true
      this.status.value = ''
      say('Записано в LingQ ✅')
    } catch (e) {
      logError('check', 'Запись исправленной разбивки', e)
      this.error.value = errorText(e)
    }
    this.applying.value = false
  }

  /** A reader (or the player tab) showing this lesson reads it again (its word translations follow their words). */
  private reloadReader(id: number) {
    for (const s of openSessions(this.lang, id)) void s.reloadAfterSplitFix()
  }
}

/** Reader sessions of this lesson that are open now: reader screens in the stack and the player tab's. */
function openSessions(lang: string, id: number): ReaderSession[] {
  const out: ReaderSession[] = []
  for (const sc of stack.value) if (sc.k === 'reader' && sc.s.lang === lang && sc.s.lesson.id === id) out.push(sc.s)
  const p = player.value
  if (p && p.lang === lang && p.lesson.id === id && !out.includes(p)) out.push(p)
  return out
}

/**
 * The word translations kept on this device for a lesson (localStorage "reader.{lang}_{id}") follow their words to the
 * new places, as in the reader's remapNotes. `words` (the chosen meanings) are kept by word and stay as they are.
 */
function moveStoredNotes(lang: string, id: number, before: Obj[], after: Obj[]) {
  const key = notesKeyOf(lang, id)
  try {
    const raw = localStorage.getItem(key)
    if (!raw) return
    const o = JSON.parse(raw)
    if (!o || typeof o !== 'object') return
    const old = wordSlots(parseSentences(before).flat())
    const map = matchWords(old, wordSlots(parseSentences(after).flat()))
    const r = remapPinned(old, map, o.pins ?? {}, o.phrases ?? {})
    localStorage.setItem(key, JSON.stringify({ ...o, pins: r.pins, phrases: r.phrases, t: Date.now() }))
    log('check', `Урок ${id}: переводы слов перенесены (${r.kept} из ${r.total})`)
  } catch (e) {
    logError('check', `Урок ${id}: переводы слов не перенесены`, e)
  }
}

/** Opens the check screen over [lessons] and starts the dry run. */
export function checkLessons(lang: string, lessons: Lesson[], label: string) {
  const s = new CheckSession(lang, lessons, label)
  go(s)
  void s.start()
}

/** A link to a lesson or a course (as on lingq.com): checks the lesson / all lessons of the course. */
export async function openLink(text: string, defaultLang: string) {
  const t = parseTarget(text)
  if (!t) { say('Не понял ссылку'); return }
  const lang = t.lang ?? defaultLang
  if (t.lessonId != null) { checkLessons(lang, [{ id: t.lessonId, title: '' }], `Урок ${t.lessonId}`); return }
  try {
    const r = await api.lessons(lang, t.courseId!)
    checkLessons(lang, r.lessons, r.title || `Курс ${t.courseId}`)
  } catch (e) { sayLong(errorText(e)) }
}
