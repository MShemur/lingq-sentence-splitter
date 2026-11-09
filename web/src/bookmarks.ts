// Sentences the user wants to come back to. Kept in localStorage on this device. Port of BookmarkStore.
import { signal } from '@preact/signals'

/** [sent] = sentence number in the lesson (reader id); [tr] = its translation. */
export interface Bookmark {
  lang: string
  lessonId: number
  lessonTitle: string
  sent: number
  text: string
  time: number
  tr: string
  /** Audio bookmark: position in the lesson audio, ms (missing = a text bookmark). */
  ms?: number
}

export const isAudio = (b: Bookmark) => b.ms != null && b.ms >= 0

const KEY = 'bookmarks'

function read(): Bookmark[] {
  try {
    const a = JSON.parse(localStorage.getItem(KEY) ?? '[]')
    return Array.isArray(a) ? a.map((b) => ({ tr: '', lessonTitle: '', ...b })) : []
  } catch {
    return []
  }
}

export const bookmarks = signal<Bookmark[]>(read())
/** Lists show the full sentences instead of two lines. */
export const bookmarksExpanded = signal(false)

function write(list: Bookmark[]) {
  bookmarks.value = list
  try { localStorage.setItem(KEY, JSON.stringify(list)) } catch { /* ignore */ }
}

/** Sync key of a bookmark: "<lessonId>_<sent>", audio ones "<lessonId>_a<ms>". */
export const bookmarkKey = (b: { lessonId: number; sent: number; ms?: number }) =>
  b.ms != null && b.ms >= 0 ? `${b.lessonId}_a${b.ms}` : `${b.lessonId}_${b.sent}`

/** When bookmarks were removed ("<lessonId>_<sent>" -> ms), so the removal also reaches the other device. */
function readDeleted(): Record<string, number> {
  try { return JSON.parse(localStorage.getItem('bookmarksDeleted') ?? '{}') } catch { return {} }
}
export let bookmarksDeleted = readDeleted()

function writeDeleted(d: Record<string, number>) {
  bookmarksDeleted = d
  try { localStorage.setItem('bookmarksDeleted', JSON.stringify(d)) } catch { /* ignore */ }
}

function deleted(b: Bookmark) { writeDeleted({ ...bookmarksDeleted, [bookmarkKey(b)]: Date.now() }) }

/** Sync: the merged list and deletions replace the local ones. */
export function replaceAllBookmarks(list: Bookmark[], del: Record<string, number>) {
  writeDeleted(del)
  if (JSON.stringify(list) !== JSON.stringify(bookmarks.value)) write(list)
}

const same = (b: Bookmark, lessonId: number, sent: number) => !isAudio(b) && b.lessonId === lessonId && b.sent === sent

export const hasBookmark = (lessonId: number, sent: number) => bookmarks.value.some((b) => same(b, lessonId, sent))

export const lessonBookmarks = (lessonId: number) =>
  bookmarks.value.filter((b) => !isAudio(b) && b.lessonId === lessonId).sort((a, b) => a.sent - b.sent)

/** Audio bookmarks of a lesson, in audio order. */
export const audioBookmarks = (lessonId: number) =>
  bookmarks.value.filter((b) => isAudio(b) && b.lessonId === lessonId).sort((a, b) => a.ms! - b.ms!)

/** A place in the lesson audio (with the sentence sounding there); one per sentence, a second mark moves it. */
export function addAudioBookmark(lang: string, lessonId: number, lessonTitle: string, sent: number, text: string, ms: number) {
  const now = Date.now()
  const old = bookmarks.value.filter((b) => isAudio(b) && b.lessonId === lessonId && sent >= 0 && b.sent === sent)
  const del = { ...bookmarksDeleted }
  for (const b of old) del[bookmarkKey(b)] = now
  const b: Bookmark = { lang, lessonId, lessonTitle, sent, text, time: now, tr: '', ms: Math.round(ms) }
  delete del[bookmarkKey(b)]
  writeDeleted(del)
  write([...bookmarks.value.filter((x) => !old.includes(x) && bookmarkKey(x) !== bookmarkKey(b)), b])
}

/** Adds or removes; returns true when the sentence is bookmarked now. */
export function toggleBookmark(lang: string, lessonId: number, lessonTitle: string, sent: number, text: string, tr = ''): boolean {
  const list = bookmarks.value
  const old = list.find((b) => same(b, lessonId, sent))
  if (old) {
    deleted(old)
    write(list.filter((b) => !same(b, lessonId, sent)))
    return false
  }
  write([...list, { lang, lessonId, lessonTitle, sent, text, time: Date.now(), tr }])
  return true
}

export function setBookmarkTranslation(b: Bookmark, tr: string) {
  write(bookmarks.value.map((x) => (bookmarkKey(x) === bookmarkKey(b) ? { ...x, tr } : x)))
}

export function removeBookmark(b: Bookmark) {
  deleted(b)
  write(bookmarks.value.filter((x) => bookmarkKey(x) !== bookmarkKey(b)))
}
