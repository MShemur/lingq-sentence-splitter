// Pictures of an EPUB book, kept in this browser (LingQ lessons have no pictures in the text). Each picture is tied to
// the paragraph that follows it in the book (or, at a chapter end, the one before it) by the first letters of that
// paragraph, so it finds its place again in the lesson after cleaning and LingQ's own processing. The caption is
// shown under the picture only: it is not lesson text (not translated). Only on the device that imported the book.
import type { Sent } from './reader/session'

/** A picture read from the book; [before] / [after]: the key of the paragraph after / before it (null = none). */
export interface BookPic { data: Blob; w: number; h: number; cap: string; before: string | null; after: string | null }

/** A picture placed in a lesson: [url] an object URL. */
export interface ShownPic { url: string; w: number; h: number; cap: string }

/** Pictures before a paragraph (by its first sentence id) and after the last one (by its last sentence id). */
export interface PicPlaces { before: Map<number, ShownPic[]>; after: Map<number, ShownPic[]> }

export const NO_PICS: PicPlaces = { before: new Map(), after: new Map() }

/**
 * A picture that is a page illustration (a full page, a plate): it is stretched to the column / page as large as
 * fits, beyond its pixel size. Smaller ones (icons, small diagrams, ornaments, ~200 px) keep their natural size.
 * Tune here: the threshold is the shorter side of the picture in pixels.
 */
export const isBigPic = (w: number, h: number): boolean => Math.min(w, h) >= 300

/** A paragraph's anchor: its first letters and digits, lower case (stars, spaces, punctuation and hyphens dropped). */
export function paraKey(text: string): string {
  return (text.toLowerCase().match(/[\p{L}\p{N}]/gu) ?? []).slice(0, 40).join('')
}

/** Whether a paragraph with key [k] is the one anchored by [anchor] (prefix match: cleaning may change its end). */
export function sameKey(k: string, anchor: string): boolean {
  if (!k || !anchor) return false
  const n = Math.min(24, anchor.length)
  if (k.length >= n) return k.startsWith(anchor.slice(0, n))
  return k.length >= 12 && anchor.startsWith(k)
}

// --- storage ---

let dbp: Promise<IDBDatabase> | null = null
function db(): Promise<IDBDatabase> {
  dbp ??= new Promise((ok, fail) => {
    const r = indexedDB.open('satzloop-pics', 1)
    r.onupgradeneeded = () => { r.result.createObjectStore('files') }
    r.onsuccess = () => ok(r.result)
    r.onerror = () => { dbp = null; fail(r.error) }
  })
  return dbp
}

async function idb<T>(mode: IDBTransactionMode, f: (s: IDBObjectStore) => IDBRequest): Promise<T> {
  const d = await db()
  return new Promise((ok, fail) => {
    const req = f(d.transaction('files', mode).objectStore('files'))
    req.onsuccess = () => ok(req.result as T)
    req.onerror = () => fail(req.error)
  })
}
const lessonKey = (lang: string, id: number) => `${lang}_${id}`

/** Keeps the pictures of a fresh lesson (replaces what an earlier lesson with this id had). */
export async function save(lang: string, lesson: number, pics: BookPic[]) {
  if (pics.length) await idb('readwrite', (s) => s.put(pics, lessonKey(lang, lesson)))
}

export async function load(lang: string, lesson: number): Promise<BookPic[] | null> {
  try { return (await idb<BookPic[] | undefined>('readonly', (s) => s.get(lessonKey(lang, lesson)))) ?? null } catch { return null }
}

const urls = new WeakMap<Blob, string>()
const urlOf = (b: Blob) => { let u = urls.get(b); if (!u) { u = URL.createObjectURL(b); urls.set(b, u) } return u }

/** Where each picture goes in the lesson; a picture whose paragraph is not found is left out. */
export function place(pics: BookPic[], paras: Sent[][]): PicPlaces {
  const out: PicPlaces = { before: new Map(), after: new Map() }
  if (!pics.length || !paras.length) return out
  const keys = paras.map((p) => paraKey(p.map((s) => s.plain).join(' ')))
  const add = (m: Map<number, ShownPic[]>, i: number, p: BookPic, last = false) => {
    const id = last ? paras[i][paras[i].length - 1].id : paras[i][0].id
    m.set(id, [...(m.get(id) ?? []), { url: urlOf(p.data), w: p.w, h: p.h, cap: p.cap }])
  }
  for (const p of pics) {
    const b = p.before != null ? keys.findIndex((k) => sameKey(k, p.before!)) : -1
    if (b >= 0) { add(out.before, b, p); continue }
    const a = p.after != null ? keys.findIndex((k) => sameKey(k, p.after!)) : -1
    if (a < 0) continue
    if (a + 1 < paras.length) add(out.before, a + 1, p)
    else add(out.after, a, p, true)
  }
  return out
}
