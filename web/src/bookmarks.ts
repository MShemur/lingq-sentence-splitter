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
}

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

const same = (b: Bookmark, lessonId: number, sent: number) => b.lessonId === lessonId && b.sent === sent

export const hasBookmark = (lessonId: number, sent: number) => bookmarks.value.some((b) => same(b, lessonId, sent))

export const lessonBookmarks = (lessonId: number) =>
  bookmarks.value.filter((b) => b.lessonId === lessonId).sort((a, b) => a.sent - b.sent)

/** Adds or removes; returns true when the sentence is bookmarked now. */
export function toggleBookmark(lang: string, lessonId: number, lessonTitle: string, sent: number, text: string, tr = ''): boolean {
  const list = bookmarks.value
  if (list.some((b) => same(b, lessonId, sent))) {
    write(list.filter((b) => !same(b, lessonId, sent)))
    return false
  }
  write([...list, { lang, lessonId, lessonTitle, sent, text, time: Date.now(), tr }])
  return true
}

export function setBookmarkTranslation(b: Bookmark, tr: string) {
  write(bookmarks.value.map((x) => (same(x, b.lessonId, b.sent) ? { ...x, tr } : x)))
}

export function removeBookmark(b: Bookmark) {
  write(bookmarks.value.filter((x) => !same(x, b.lessonId, b.sent)))
}
