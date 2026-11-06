// Copies of lessons before the app changed their sentence split, kept 3 days in this browser (IndexedDB: a lesson can
// be larger than localStorage allows). Port of Android Backups.
import * as api from '../lingq'

export const KEEP_DAYS = 3

export interface Backup { key: string; lang: string; id: number; title: string; time: number; paragraphs: unknown[] }

function db(): Promise<IDBDatabase> {
  return new Promise((ok, fail) => {
    const r = indexedDB.open('lingqfix', 1)
    r.onupgradeneeded = () => { r.result.createObjectStore('backups', { keyPath: 'key' }) }
    r.onsuccess = () => ok(r.result)
    r.onerror = () => fail(r.error)
  })
}

async function tx<T>(mode: IDBTransactionMode, f: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const d = await db()
  return new Promise((ok, fail) => {
    const req = f(d.transaction('backups', mode).objectStore('backups'))
    req.onsuccess = () => ok(req.result)
    req.onerror = () => fail(req.error)
  })
}

export async function saveBackup(lang: string, id: number, title: string, paragraphs: unknown[]) {
  const time = Date.now()
  await tx('readwrite', (s) => s.put({ key: `${time}_${id}`, lang, id, title, time, paragraphs: structuredClone(paragraphs) }))
}

/** Copies newest first; older than [KEEP_DAYS] are removed. */
export async function listBackups(): Promise<Backup[]> {
  const all = await tx<Backup[]>('readonly', (s) => s.getAll() as IDBRequest<Backup[]>)
  const limit = Date.now() - KEEP_DAYS * 24 * 3600 * 1000
  for (const b of all) if (b.time < limit) await tx('readwrite', (s) => s.delete(b.key))
  return all.filter((b) => b.time >= limit).sort((a, b) => b.time - a.time)
}

export const deleteAllBackups = () => tx('readwrite', (s) => s.clear())

/** The lesson gets the sentence structure of the copy back. */
export const restoreBackup = (b: Backup) => api.saveParagraphs(b.lang, b.id, b.paragraphs)
