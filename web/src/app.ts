// App-wide state: screen stack, messages, library cache. Port of AppState.kt (reader part).
import { computed, signal } from '@preact/signals'
import * as api from './lingq'
import type { Course, Lesson } from './lingq'
import { lang, otherAi, prefs, useGemini } from './settings'
import type { ReaderSession } from './reader/session'
import type { ChatSession } from './chat'
import type { ImportSession } from './importer/session'
import type { PodcastSession } from './importer/podcasts'
import { positions } from './progress'
import { log, logError } from './applog'

export class LessonsScreen {
  readonly k = 'lessons'
  title = signal('')
  lessons = signal<Lesson[] | null>(null)
  error = signal<string | null>(null)

  constructor(readonly lang: string, readonly course: Course) {
    this.title.value = course.title || `Курс ${course.id}`
  }

  async load() {
    this.error.value = null
    this.lessons.value = null
    try {
      const r = await api.lessons(this.lang, this.course.id)
      if (r.title) this.title.value = r.title
      this.lessons.value = r.lessons
      setCourseWords(this.lang, this.course.id, r.lessons.reduce((n, l) => n + (l.words ?? 0), 0))
    } catch (e) {
      this.error.value = (e as Error).message
    }
  }
}

export type Screen =
  | { k: 'courses' }
  | LessonsScreen
  | { k: 'reader'; s: ReaderSession }
  | { k: 'chat'; c: ChatSession }
  | { k: 'bookmarks' }
  | { k: 'stats' }
  | { k: 'log' }
  | { k: 'player' }
  | { k: 'settingsReading' }
  | { k: 'settingsSync' }
  | ImportSession
  | { k: 'backups' }
  | { k: 'settings' }
  | { k: 'settingsLingq' }
  | { k: 'settingsTranslation' }
  | { k: 'settingsSpeech' }
  | PodcastSession
  | { k: 'podcastFeed'; s: PodcastSession }
  | { k: 'settingsPrompts' }

export const stack = signal<Screen[]>([{ k: 'courses' }])

// Browser history mirrors the stack, so the browser's back button / gesture works too.
try { history.replaceState({ depth: 0 }, '') } catch { /* ignore */ }
window.addEventListener('popstate', (e) => {
  const depth = typeof e.state?.depth === 'number' ? e.state.depth : 0
  if (depth < stack.value.length - 1) stack.value = stack.value.slice(0, depth + 1)
})

function screenName(s: Screen): string {
  if (s.k === 'reader') { const r = (s as any).s; return `Урок ${r.lang}/${r.lesson.id} «${String(r.lesson.title ?? '').slice(0, 60)}»` }
  return s.k
}

export function go(s: Screen) {
  log('nav', `→ ${screenName(s)}`)
  stack.value = [...stack.value, s]
  try { history.pushState({ depth: stack.value.length - 1 }, '') } catch { /* ignore */ }
}

/** Goes back to the nearest screen matching [pred] (several steps at once); resolves when it is shown. */
export function backTo(pred: (s: Screen) => boolean): Promise<void> {
  const st = stack.value
  let i = st.length - 1
  while (i > 0 && !pred(st[i])) i--
  const n = st.length - 1 - i
  if (n <= 0) return Promise.resolve()
  log('nav', `← ${screenName(st[i])}`)
  if (history.state?.depth !== st.length - 1) { stack.value = st.slice(0, i + 1); return Promise.resolve() }
  return new Promise((res) => {
    const done = () => { window.removeEventListener('popstate', done); clearTimeout(t); setTimeout(res, 0) }
    const t = setTimeout(() => { stack.value = stack.value.slice(0, i + 1); done() }, 1000)
    window.addEventListener('popstate', done)
    history.go(-n)
  })
}

export function back() {
  if (stack.value.length <= 1) return
  log('nav', `← ${screenName(stack.value[stack.value.length - 2])}`)
  if (history.state?.depth === stack.value.length - 1) history.back()
  else stack.value = stack.value.slice(0, -1)
}

// --- snackbar ---

export const toast = signal<{ text: string; id: number } | null>(null)
let toastTimer = 0
export function say(text: string) {
  log('msg', text)
  const id = Date.now()
  toast.value = { text, id }
  clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => { if (toast.value?.id === id) toast.value = null }, 3500)
}

// --- library ---

export const courses = signal<Course[] | null>(null)
export const coursesError = signal<string | null>(null)
let coursesLang = ''

export async function loadCourses(force = false) {
  const l = lang()
  if (!force && courses.value && coursesLang === l) return
  courses.value = null
  coursesError.value = null
  try {
    const list = await api.myCourses(l)
    courses.value = list
    coursesLang = l
  } catch (e) {
    coursesError.value = (e as Error).message
  }
}

// --- "Продолжить" row: LingQ's list + lessons opened here (LingQ does not always notice an opening through the API);
// the lesson opened or read last comes first ---

const recentApi = signal<{ lang: string; list: Lesson[] } | null>(null)
const recentLocal = signal<Record<string, Lesson[]>>(loadRecentLocal())

function loadRecentLocal(): Record<string, Lesson[]> {
  try { return JSON.parse(localStorage.getItem('recent') ?? '{}') } catch { return {} }
}

/** A lesson was opened in the reader: it goes first in the row. */
export function lessonOpened(l: string, lesson: Lesson) {
  const list = recentLocal.value[l] ?? []
  const old = list.find((x) => x.id === lesson.id)
  const now: Lesson = {
    id: lesson.id, title: lesson.title || old?.title || '', imageUrl: lesson.imageUrl || old?.imageUrl || '',
    level: lesson.level || old?.level || '', words: lesson.words || old?.words || 0,
    courseTitle: lesson.courseTitle || old?.courseTitle || '', opened: Date.now(),
    audio: lesson.audio || old?.audio || '', duration: lesson.duration || old?.duration || 0,
  }
  recentLocal.value = { ...recentLocal.value, [l]: [now, ...list.filter((x) => x.id !== lesson.id)].slice(0, 20) }
  try { localStorage.setItem('recent', JSON.stringify(recentLocal.value)) } catch { /* ignore */ }
}

/** Lessons opened lately, newest first (top 10); null until there is anything to show. */
export const recent = computed<Lesson[] | null>(() => {
  const l = lang()
  const remote = recentApi.value?.lang === l ? recentApi.value.list : []
  const local = recentLocal.value[l] ?? []
  if (!remote.length && !local.length) return recentApi.value?.lang === l ? [] : null
  const pos = positions.value
  const byId = new Map<number, Lesson>()
  for (const x of [...remote, ...local]) {
    const a = byId.get(x.id)
    const t = Math.max(a?.opened ?? 0, x.opened ?? 0, pos[`${l}_${x.id}`]?.t ?? 0)
    byId.set(x.id, a ? {
      ...a, title: a.title || x.title, imageUrl: a.imageUrl || x.imageUrl, courseTitle: a.courseTitle || x.courseTitle, opened: t,
      audio: a.audio || x.audio, duration: a.duration || x.duration,
    } : { ...x, opened: t })
  }
  return [...byId.values()].sort((a, b) => (b.opened ?? 0) - (a.opened ?? 0)).slice(0, 10)
})

export async function loadRecent() {
  const l = lang()
  try {
    const list = await api.recentLessons(l)
    if (lang() === l) recentApi.value = { lang: l, list }
  } catch { /* the row shows only lessons opened here */ }
}

// --- words per course: sum over its lessons, fetched in the background (3 at a time), kept on the device ---

function loadCourseWords(): Record<string, number> {
  try { return JSON.parse(localStorage.getItem('courseWords') ?? '{}') } catch { return {} }
}
export const courseWords = signal<Record<string, number>>(loadCourseWords())
export const wordsKey = (l: string, id: number) => `${l}_${id}`

/** When each course was counted (ms): the library recounts a course at most once a day. */
let wordsTime: Record<string, number> = (() => { try { return JSON.parse(localStorage.getItem('courseWordsT') ?? '{}') } catch { return {} } })()
const DAY = 24 * 3600_000

function setCourseWords(l: string, id: number, n: number) {
  const k = wordsKey(l, id)
  wordsTime = { ...wordsTime, [k]: Date.now() }
  try { localStorage.setItem('courseWordsT', JSON.stringify(wordsTime)) } catch { /* ignore */ }
  if (courseWords.value[k] === n) return
  courseWords.value = { ...courseWords.value, [k]: n }
  try { localStorage.setItem('courseWords', JSON.stringify(courseWords.value)) } catch { /* ignore */ }
}

/** Courses queued since the app started (opening a course counts it again anyway, its lessons are loaded). */
const wordsFetched = new Set<string>()
const wordsQueue: (() => Promise<void>)[] = []
let wordsRunning = 0

function pumpWords() {
  while (wordsRunning < 3 && wordsQueue.length) {
    const job = wordsQueue.shift()!
    wordsRunning++
    void job().finally(() => { wordsRunning--; pumpWords() })
  }
}

export function ensureCourseWords(l: string, id: number) {
  const k = wordsKey(l, id)
  if (wordsFetched.has(k)) return
  if (k in courseWords.value && Date.now() - (wordsTime[k] ?? 0) < DAY) return
  wordsFetched.add(k)
  wordsQueue.push(async () => {
    try {
      const r = await api.lessons(l, id)
      setCourseWords(l, id, r.lessons.reduce((n, x) => n + (x.words ?? 0), 0))
    } catch { wordsFetched.delete(k) }
  })
  pumpWords()
}

export const errorText = (e: unknown) => (e instanceof Error ? e.message : String(e))

// --- Gemini limit: offer another model (port of Android ModelSwitch.kt) ---

/** Gemini refused because of limits/overload; [retry] repeats what failed once another model is chosen. */
export interface ModelSwitch { model: string; message: string; retry: () => void; apply: (model: string) => void }
export const modelSwitch = signal<ModelSwitch | null>(null)

/** Limit / overload errors of Gemini (after its own retries and fallback). */
export const isGeminiLimit = (e: unknown) =>
  /HTTP (429|503)|RESOURCE_EXHAUSTED|quota|high demand|overloaded/i.test(errorText(e))

/** Called where a Gemini request failed: on a limit error the user may switch the model and [retry]. */
/** [fast]: the request used the light model (words, phrases, sentences); the choice then replaces that one. */
export function geminiFailed(e: unknown, retry: () => void, fast = false) {
  logError('ai', `Ошибка ИИ (${fast ? 'лёгкая' : 'основная'} модель)`, e)
  if (!useGemini() || !isGeminiLimit(e) || modelSwitch.value) return
  const other = otherAi()
  const main = other ? prefs.aiModel : prefs.geminiModel
  const light = other ? prefs.aiFastModel : prefs.geminiFastModel
  const own = fast && !!light.value.trim()
  modelSwitch.value = {
    model: own ? light.value : main.value, message: errorText(e), retry,
    apply: (m) => { if (fast) light.value = m; else main.value = m },
  }
}
