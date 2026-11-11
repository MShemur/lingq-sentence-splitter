// "Отзыв / задача": a note for Claude, saved as feedback/<stamp>-web.json on the sync branch of the repo (the sync
// token may write there). Claude works on it and writes status / doneIn / reply back into the file; listed here.
// Port of Android Feedback.kt.
import { useEffect, useState } from 'preact/hooks'
import { back, go, say } from '../app'
import { log, logError, logText } from '../applog'
import { prefs } from '../settings'
import { IconButton, Spinner, TopBar } from '../ui'

declare const __VERSION__: string

const API = 'https://api.github.com/repos/MShemur/lingq-sentence-splitter'
const BRANCH = 'sync'

interface Item { name: string; t: number; device: string; text: string; status: string; doneIn: string; reply: string }

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

async function send(text: string, withLog: boolean) {
  if (!prefs.ghToken.value.trim()) throw new Error('Нет токена GitHub (⚙ Настройки → Синхронизация)')
  const now = new Date()
  const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`
  const o: Record<string, unknown> = { t: now.getTime(), device: 'web', version: `web v${__VERSION__}`, text: text.trim(), status: 'new' }
  if (withLog) o.log = logText().slice(-60_000)
  const [code, t] = await gh('PUT', `${API}/contents/feedback/${stamp}-web.json`, {
    message: 'feedback from web', branch: BRANCH, content: b64(JSON.stringify(o, null, 2)),
  })
  if (code < 200 || code > 299) fail(code, t)
  log('feedback', `Отзыв отправлен (${stamp})`)
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
      return { name: n, t: Number(o.t) || 0, device: String(o.device ?? ''), text: String(o.text ?? ''), status: String(o.status ?? 'new'), doneIn: String(o.doneIn ?? ''), reply: String(o.reply ?? '') }
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
  const reload = () => { setItems(null); list().then(setItems, (e) => { setErr((e as Error).message); setItems([]) }) }
  useEffect(reload, [])
  const noToken = !prefs.ghToken.value.trim()
  const submit = async () => {
    setBusy(true); setErr(null)
    try { await send(text, withLog); setText(''); setWithLog(false); say('Отправлено'); reload() } catch (e) {
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
          <label class="check-row">
            <input type="checkbox" checked={withLog} disabled={busy} onChange={(e) => setWithLog((e.target as HTMLInputElement).checked)} />
            <span>Приложить журнал действий (для ошибок)</span>
          </label>
          {err && <p class="error-text">{err}</p>}
          <button class="btn filled wide" disabled={busy || !text.trim() || noToken} onClick={() => void submit()}>
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
