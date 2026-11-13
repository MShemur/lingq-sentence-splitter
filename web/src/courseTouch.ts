// When a course was last opened on this device (its lesson list): "Мои курсы" puts the latest on top. Synced as
// "courseT": {"<lang>_<courseId>": epochMs}, merged by max (Android Storage.kt CourseTouch).
import { signal } from '@preact/signals'

const KEY = 'courseT'

function load(): Record<string, number> {
  try { return JSON.parse(localStorage.getItem(KEY) ?? '{}') } catch { return {} }
}

export const courseT = signal<Record<string, number>>(load())

function save(v: Record<string, number>) {
  courseT.value = v
  try { localStorage.setItem(KEY, JSON.stringify(v)) } catch { /* full */ }
}

export function touchCourse(lang: string, id: number) { save({ ...courseT.value, [`${lang}_${id}`]: Date.now() }) }

export function exportCourseT(): Record<string, number> { return { ...courseT.value } }

/** Times from the other device: the later one wins. */
export function importCourseT(o: Record<string, unknown>) {
  const cur = courseT.value
  let changed = false
  const out = { ...cur }
  for (const [k, v] of Object.entries(o)) {
    const t = Number(v) || 0
    if (t > (out[k] ?? 0)) { out[k] = t; changed = true }
  }
  if (changed) save(out)
}
