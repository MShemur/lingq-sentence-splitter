// App-wide state: screen stack, messages, library cache. Port of AppState.kt (reader part).
import { signal } from '@preact/signals'
import * as api from './lingq'
import type { Course, Lesson } from './lingq'
import { lang, prefs, useGemini } from './settings'
import type { ReaderSession } from './reader/session'
import type { ChatSession } from './chat'

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
  | { k: 'settings' }
  | { k: 'settingsLingq' }
  | { k: 'settingsTranslation' }
  | { k: 'settingsPrompts' }

export const stack = signal<Screen[]>([{ k: 'courses' }])

// Browser history mirrors the stack, so the browser's back button / gesture works too.
try { history.replaceState({ depth: 0 }, '') } catch { /* ignore */ }
window.addEventListener('popstate', (e) => {
  const depth = typeof e.state?.depth === 'number' ? e.state.depth : 0
  if (depth < stack.value.length - 1) stack.value = stack.value.slice(0, depth + 1)
})

export function go(s: Screen) {
  stack.value = [...stack.value, s]
  try { history.pushState({ depth: stack.value.length - 1 }, '') } catch { /* ignore */ }
}

export function back() {
  if (stack.value.length <= 1) return
  if (history.state?.depth === stack.value.length - 1) history.back()
  else stack.value = stack.value.slice(0, -1)
}

/** Replaces the whole stack (e.g. "go to source" from the bookmarks list). */
export function replaceTop(s: Screen) {
  stack.value = [...stack.value.slice(0, -1), s]
}

// --- snackbar ---

export const toast = signal<{ text: string; id: number } | null>(null)
let toastTimer = 0
export function say(text: string) {
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

/** Lessons opened lately, for the "Продолжить" row of the library. */
export const recent = signal<Lesson[] | null>(null)

export async function loadRecent() {
  const l = lang()
  try {
    const list = await api.recentLessons(l)
    if (lang() === l) recent.value = list
  } catch { /* the row is just not shown */ }
}

// --- words per course: sum over its lessons, fetched in the background (3 at a time), kept on the device ---

function loadCourseWords(): Record<string, number> {
  try { return JSON.parse(localStorage.getItem('courseWords') ?? '{}') } catch { return {} }
}
export const courseWords = signal<Record<string, number>>(loadCourseWords())
export const wordsKey = (l: string, id: number) => `${l}_${id}`

function setCourseWords(l: string, id: number, n: number) {
  const k = wordsKey(l, id)
  if (courseWords.value[k] === n) return
  courseWords.value = { ...courseWords.value, [k]: n }
  try { localStorage.setItem('courseWords', JSON.stringify(courseWords.value)) } catch { /* ignore */ }
}

/** Courses counted since the app started (each once per start; opening a course counts it again). */
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
export interface ModelSwitch { model: string; message: string; retry: () => void }
export const modelSwitch = signal<ModelSwitch | null>(null)

/** Limit / overload errors of Gemini (after its own retries and fallback). */
export const isGeminiLimit = (e: unknown) =>
  /HTTP (429|503)|RESOURCE_EXHAUSTED|quota|high demand|overloaded/i.test(errorText(e))

/** Called where a Gemini request failed: on a limit error the user may switch the model and [retry]. */
export function geminiFailed(e: unknown, retry: () => void) {
  if (useGemini() && isGeminiLimit(e) && !modelSwitch.value) modelSwitch.value = { model: prefs.geminiModel.value, message: errorText(e), retry }
}
