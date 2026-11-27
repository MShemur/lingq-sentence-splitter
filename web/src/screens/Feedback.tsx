// "Отзыв / задача": a note for Claude, saved as feedback/<stamp>-web.json on the sync branch of the repo (the sync
// token may write there). Claude works on it and writes status / doneIn / reply back into the file; listed here.
// Pictures (max 4, downscaled to 1600 px, JPEG 0.8) go first as feedback/<stamp>-web-<n>.jpg; the note lists them in "images".
// Port of Android Feedback.kt.
import { useEffect, useRef, useState } from 'preact/hooks'
import { back, go, say } from '../app'
import { log, logError, logText } from '../applog'
import { prefs } from '../settings'
import { Icon } from '../icons'
import { IconButton, Spinner, TopBar, VoiceBar } from '../ui'
import { Dictation } from '../voice'

declare const __VERSION__: string

const API = 'https://api.github.com/repos/MShemur/lingq-sentence-splitter'
const BRANCH = 'sync'
const MAX_PICS = 4
const PIC_SIDE = 1600

interface Item { name: string; t: number; device: string; text: string; status: string; doneIn: string; reply: string; images: string[] }
interface Pic { blob: Blob; url: string }

async function gh(method: string, url: string, body?: unknown, raw = false): Promise<[number, string]> {
  const r = await fetch(url, {
    method, cache: 'no-store',
    headers: {
      Authorization: `Bearer ${prefs.ghToken.value.trim()}`,
      Accept: raw ? 'application/vnd.github.raw' : 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28',
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  })
  return [r.status, await r.text()]
}

function fail(code: number, text: string): never {
  let msg = ''
  try { msg = JSON.parse(text).message ?? '' } catch { /* not JSON */ }
  throw new Error(code === 401 ? 'GitHub не принял токен'
    : code === 403 || code === 404 ? `У токена нет права записи (Contents: Read and write) или нет ветки синхронизации — ${msg}`
    : `GitHub: HTTP ${code} ${msg}`)
}

const b64 = (s: string) => { let out = ''; for (const b of new TextEncoder().encode(s)) out += String.fromCharCode(b); return btoa(out) }
const pad = (n: number) => String(n).padStart(2, '0')

/** Base64 of a binary blob (chunked, so big pictures do not overflow the argument list). */
async function b64Blob(blob: Blob): Promise<string> {
  const bytes = new Uint8Array(await blob.arrayBuffer())
  let out = ''
  for (let i = 0; i < bytes.length; i += 0x8000) out += String.fromCharCode(...bytes.subarray(i, i + 0x8000))
  return btoa(out)
}

/** Max 1600 px on the longer side, JPEG 0.8, white background. The browser decodes HEIC where it can. */
async function downscale(file: File): Promise<Blob> {
  const src = URL.createObjectURL(file)
  try {
    const img = new Image()
    img.src = src
    await img.decode()
    const k = Math.min(1, PIC_SIDE / Math.max(img.naturalWidth, img.naturalHeight))
    const c = document.createElement('canvas')
    c.width = Math.max(1, Math.round(img.naturalWidth * k))
    c.height = Math.max(1, Math.round(img.naturalHeight * k))
    const ctx = c.getContext('2d')
    if (!ctx) throw new Error('нет canvas')
    ctx.fillStyle = '#fff'
    ctx.fillRect(0, 0, c.width, c.height)
    ctx.drawImage(img, 0, 0, c.width, c.height)
    const blob = await new Promise<Blob | null>((ok) => c.toBlob(ok, 'image/jpeg', 0.8))
    if (!blob) throw new Error('не удалось сжать картинку')
    return blob
  } finally { URL.revokeObjectURL(src) }
}

async function putFile(path: string, content: string, message: string) {
  const [code, t] = await gh('PUT', `${API}/contents/${path}`, { message, branch: BRANCH, content })
  if (code < 200 || code > 299) fail(code, t)
}

/** Uploads the pictures, then the note. Returns the messages of the pictures that failed (the note is still sent). */
async function send(text: string, withLog: boolean, pics: Blob[]): Promise<string[]> {
  if (!prefs.ghToken.value.trim()) throw new Error('Нет токена GitHub (⚙ Настройки → Синхронизация)')
  const now = new Date()
  const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`
  const id = `${stamp}-web`
  const images: string[] = []
  const failed: string[] = []
  for (let i = 0; i < pics.length; i++) {
    const path = `feedback/${id}-${i + 1}.jpg`
    try {
      await putFile(path, await b64Blob(pics[i]), 'feedback image from web')
      images.push(path)
    } catch (e) {
      logError('feedback', `Картинка ${i + 1} не загрузилась`, e)
      failed.push(`картинка ${i + 1}: ${(e as Error).message}`)
    }
  }
  const o: Record<string, unknown> = { t: now.getTime(), device: 'web', version: `web v${__VERSION__}`, text: text.trim(), status: 'new', images }
  if (withLog) o.log = logText().slice(-60_000)
  await putFile(`feedback/${id}.json`, b64(JSON.stringify(o, null, 2)), 'feedback from web')
  log('feedback', `Отзыв отправлен (${stamp}${images.length ? `, картинок ${images.length}` : ''})`)
  return failed
}

/** The latest notes of both devices, newest first. */
async function list(max = 30): Promise<Item[]> {
  if (!prefs.ghToken.value.trim()) return []
  const [code, t] = await gh('GET', `${API}/contents/feedback?ref=${BRANCH}`)
  if (code === 404) return []
  if (code !== 200) fail(code, t)
  const names = (JSON.parse(t) as { name: string }[]).map((x) => x.name).filter((n) => n.endsWith('.json')).sort().reverse().slice(0, max)
  const out = await Promise.all(names.map(async (n): Promise<Item | null> => {
    const [c, x] = await gh('GET', `${API}/contents/feedback/${encodeURIComponent(n)}?ref=${BRANCH}`, undefined, true)
    if (c !== 200) return null
    try {
      const o = JSON.parse(x)
      return {
        name: n, t: Number(o.t) || 0, device: String(o.device ?? ''), text: String(o.text ?? ''), status: String(o.status ?? 'new'),
        doneIn: String(o.doneIn ?? ''), reply: String(o.reply ?? ''),
        images: Array.isArray(o.images) ? o.images.map(String) : [],
      }
    } catch { return null }
  }))
  return out.filter((x): x is Item => !!x)
}

const STATUS: Record<string, string> = { work: '⏳ в работе', done: '✅ готово', no: '✖ не делаю' }

export function FeedbackScreen() {
  const [text, setText] = useState('')
  const [withLog, setWithLog] = useState(false)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const [items, setItems] = useState<Item[] | null>(null)
  const [pics, setPics] = useState<Pic[]>([])
  const [picBusy, setPicBusy] = useState(false)
  const picInput = useRef<HTMLInputElement>(null)
  const picsRef = useRef<Pic[]>([])
  picsRef.current = pics
  const voice = useRef(new Dictation()).current
  useEffect(() => () => {
    voice.cancel()
    picsRef.current.forEach((p) => URL.revokeObjectURL(p.url))
  }, [])
  const dictate = () => {
    setErr(null)
    void voice.toggle((t) => setText((old) => (old.trim() ? old.replace(/\s*$/, ' ') : '') + t), setErr)
  }
  const reload = () => { setItems(null); list().then(setItems, (e) => { setErr((e as Error).message); setItems([]) }) }
  useEffect(reload, [])
  const noToken = !prefs.ghToken.value.trim()

  const addPics = async (files: File[]) => {
    const room = MAX_PICS - pics.length
    if (room <= 0) { setErr(`Не больше ${MAX_PICS} картинок`); return }
    if (files.length > room) setErr(`Не больше ${MAX_PICS} картинок: взято ${room}`)
    setPicBusy(true)
    const added: Pic[] = []
    for (const f of files.slice(0, room)) {
      try {
        const blob = await downscale(f)
        added.push({ blob, url: URL.createObjectURL(blob) })
      } catch (e) {
        logError('feedback', `Картинка «${f.name}» не открылась`, e)
        setErr(`Картинка «${f.name}» не открылась`)
      }
    }
    setPics((old) => [...old, ...added])
    setPicBusy(false)
  }
  const removePic = (i: number) => {
    const p = pics[i]
    if (p) URL.revokeObjectURL(p.url)
    setPics(pics.filter((_, j) => j !== i))
  }

  const submit = async () => {
    setBusy(true); setErr(null)
    try {
      const failed = await send(text, withLog, pics.map((p) => p.blob))
      setText(''); setWithLog(false)
      pics.forEach((p) => URL.revokeObjectURL(p.url)); setPics([])
      say('Отправлено')
      if (failed.length) setErr(`Отзыв отправлен, но не загрузилась ${failed.join('; ')}`)
      reload()
    } catch (e) {
      logError('feedback', 'Отзыв не отправлен', e); setErr((e as Error).message)
    }
    setBusy(false)
  }
  const fmt = (t: number) => new Date(t).toLocaleString('ru', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })
  return (
    <div class="screen">
      <TopBar nav={<IconButton icon="back" label="Назад" onClick={back} />} title="Отзыв / задача" />
      <div class="screen-body scroll">
        <div class="content page feedback">
          <p class="hint">Напишите, что поправить или добавить. Запись уходит в GitHub; Claude проверяет новые раз в час, делает и отвечает здесь (с номером версии).</p>
          {noToken && (
            <p class="error-text">Нужен токен GitHub из синхронизации. <button class="btn text" onClick={() => go({ k: 'settingsSync' })}>Настроить</button></p>
          )}
          <textarea rows={6} value={text} disabled={busy} placeholder="Например: в плеере кнопка ⏭ слишком близко к краю"
            onInput={(e) => setText((e.target as HTMLTextAreaElement).value)} />
          <div class="fb-voice">
            <button class={'fab-send mic' + (voice.recording.value ? ' on' : '')} type="button" disabled={busy || voice.hearing.value}
              aria-label={voice.recording.value ? 'Закончить диктовку' : 'Надиктовать'} onClick={dictate}>
              {voice.hearing.value ? <Spinner size={20} /> : <Icon name={voice.recording.value ? 'check' : 'mic'} />}
            </button>
            {voice.busy ? <VoiceBar d={voice} hint="нажмите ещё раз — текст добавится к заметке" /> : <span class="hint">Надиктовать: текст добавится к заметке</span>}
          </div>
          <div class="fb-pics">
            <button class="btn text" type="button" disabled={busy || picBusy || pics.length >= MAX_PICS}
              onClick={() => picInput.current?.click()}>📎 Прикрепить картинку</button>
            <span class="hint">{pics.length}/{MAX_PICS}{picBusy ? ' · сжимаю…' : ''}</span>
            <input ref={picInput} type="file" accept="image/*" multiple style={{ display: 'none' }} onChange={(e) => {
              const el = e.target as HTMLInputElement
              const files = Array.from(el.files ?? [])
              el.value = ''
              if (files.length) void addPics(files)
            }} />
          </div>
          {pics.length > 0 && (
            <div class="fb-thumbs">
              {pics.map((p, i) => (
                <div class="fb-thumb" key={p.url}>
                  <img src={p.url} alt={`Картинка ${i + 1}`} />
                  <button class="fb-x" type="button" aria-label="Убрать картинку" disabled={busy} onClick={() => removePic(i)}>✕</button>
                </div>
              ))}
            </div>
          )}
          <label class="check-row">
            <input type="checkbox" checked={withLog} disabled={busy} onChange={(e) => setWithLog((e.target as HTMLInputElement).checked)} />
            <span>Приложить журнал действий (для ошибок)</span>
          </label>
          {err && <p class="error-text">{err}</p>}
          <button class="btn filled wide" disabled={busy || picBusy || !text.trim() || noToken} onClick={() => void submit()}>
            {busy ? <Spinner size={18} /> : 'Отправить'}
          </button>
          <div class="row">
            <h2 class="section-title grow">Отправленные</h2>
            <button class="btn text" onClick={reload}>Обновить</button>
          </div>
          {!items ? <Spinner size={24} /> : !items.length ? <p class="hint">Пока ничего</p> : items.map((f) => (
            <div class="card fb-card" key={f.name}>
              <div class="fb-meta">
                {fmt(f.t)} · {f.device === 'web' ? 'iPad' : 'телефон'} · {(STATUS[f.status] ?? '🕓 ждёт') + (f.status === 'done' && f.doneIn ? ` в ${f.doneIn}` : '')}
                {f.images.length > 0 && ` · 📎 ${f.images.length}`}
              </div>
              <div class="fb-text">{f.text}</div>
              {f.reply && <div class="fb-reply">Claude: {f.reply}</div>}
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
