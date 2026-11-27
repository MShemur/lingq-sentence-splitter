// Podcasts: search / subscriptions as a grid of covers, and the episodes of one podcast. Port of Android PodcastsScreen / PodcastFeedScreen.
import { useEffect, useRef, useState } from 'preact/hooks'
import { loadTranscript, playEpisode, podEpisodeOf, podKey, podNow } from '../podplay'
import { back, stack } from '../app'
import { MainTabs, MiniPlayer } from './Player'
import { addSub, feed, isSub, subs, type Podcast, type PodcastSession } from '../importer/podcasts'
import { parseOpml, subsOpml } from '../importer/podcastSubs'
import { ClearX, IconButton, Menu, MenuItem, TopBar } from '../ui'
import { Icon } from '../icons'
import { Cover } from './Library'
import './Podcasts.css'

const fmtDate = (t: number) => (t > 0 ? new Date(t).toLocaleDateString('ru-RU', { day: 'numeric', month: 'short', year: 'numeric' }) : '')
const fmtDur = (s: number) => (s <= 0 ? '' : s >= 3600 ? `${Math.floor(s / 3600)} ч ${Math.floor(s / 60) % 60} мин` : `${Math.floor((s + 30) / 60)} мин`)

/** A podcast as a square cover with its title under it; [fresh] = a green dot at the corner (new episodes). */
function PodTile({ p, fresh, onClick }: { p: Podcast; fresh?: boolean; onClick: () => void }) {
  const title = p.title || p.feed
  return (
    <button class="pc-tile" onClick={onClick} aria-label={title}>
      <span class="pc-cover-wrap">
        <Cover url={p.image} title={title} class="pc-cover" />
        {!!fresh && <span class="pc-dot" />}
      </span>
      <span class="pc-tile-title">{title}</span>
    </button>
  )
}

/** Subscribes to every feed of the OPML file not yet subscribed (3 at a time); [say] shows the result. */
async function importOpml(file: File, say: (t: string) => void) {
  const items = parseOpml(await file.text())
  const todo = items.filter((it) => !isSub(it.feed))
  if (!items.length) { say('В файле нет подписок'); return }
  if (!todo.length) { say('Все подписки из файла уже есть'); return }
  let added = 0, failed = 0, i = 0
  const worker = async (): Promise<void> => {
    for (let it = todo[i++]; it; it = todo[i++]) {
      try {
        const [info, eps] = await feed(it.feed)
        const p: Podcast = { title: info.title || it.title, author: info.author, feed: it.feed, image: info.image }
        // like a normal subscribe: the episodes that exist now count as seen
        if (!isSub(it.feed)) { addSub(p, Math.max(0, ...eps.map((e) => e.date))); added++ }
      } catch { failed++ }
    }
  }
  await Promise.all([worker(), worker(), worker()])
  say(`Подписок добавлено: ${added}` + (failed ? `. Не открылись: ${failed}` : ''))
}

/** Downloads the subscriptions as an .opml file. */
function exportOpml() {
  const url = URL.createObjectURL(new Blob([subsOpml()], { type: 'text/xml' }))
  const a = document.createElement('a')
  a.href = url
  a.download = 'podcasts.opml'
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 10_000)
}

export function PodcastsScreen({ s }: { s: PodcastSession }) {
  useEffect(() => { s.refreshNew() }, [subs.value.length])
  const [menu, setMenu] = useState(false)
  const [busy, setBusy] = useState(false)
  const [note, setNote] = useState('')
  const fileRef = useRef<HTMLInputElement>(null)
  const res = s.results.value
  const tab = stack.value[0] === s // opened as the bottom tab
  const onFile = (e: Event) => {
    const input = e.target as HTMLInputElement
    const file = input.files?.[0]
    input.value = '' // the same file can be picked again
    if (!file) return
    setBusy(true); setNote('')
    importOpml(file, setNote).catch((err) => setNote(`Не удалось прочитать файл: ${(err as Error).message}`))
      .finally(() => setBusy(false))
  }
  return (
    <div class="screen">
      <TopBar
        nav={tab ? undefined : <IconButton icon="back" label="Назад" onClick={back} />}
        title="Подкасты"
        actions={<IconButton icon="more" label="Ещё" onClick={() => setMenu(true)} />}
      />
      {menu && (
        <Menu onClose={() => setMenu(false)}>
          <MenuItem onClick={() => { setMenu(false); fileRef.current?.click() }}>Импорт подписок (OPML)</MenuItem>
          <MenuItem onClick={() => {
            setMenu(false)
            if (subs.value.length) exportOpml(); else setNote('Подписок пока нет')
          }}>Экспорт подписок (OPML)</MenuItem>
        </Menu>
      )}
      <input ref={fileRef} type="file" accept=".opml,.xml,text/xml,application/xml" hidden onChange={onFile} />
      <div class="screen-body scroll">
        <div class="content page">
          <form class="pod-search" onSubmit={(e) => { e.preventDefault(); void s.search() }}>
            <label class="field grow">
              <span>Поиск</span>
              <div class="clearable">
                <input type="search" enterkeyhint="search" value={s.query.value} autocapitalize="off"
                  onInput={(e) => { s.query.value = (e.target as HTMLInputElement).value }} />
                <ClearX value={s.query.value} onClear={() => { s.query.value = '' }} />
              </div>
            </label>
            <button class="btn tonal" type="submit">Найти</button>
          </form>
          {s.busy.value && <div class="row-status"><span class="spinner-sm" />Ищу…</div>}
          {s.error.value && <p class="error-text">{s.error.value}</p>}
          {busy && <div class="row-status"><span class="spinner-sm" />Подписываюсь на подкасты из файла…</div>}
          {note && !busy && <p class="hint">{note}</p>}
          {res ? (
            <>
              <div class="row">
                <span class="grow hint">Найдено: {res.length}</span>
                <button class="btn text" onClick={() => { s.results.value = null; s.query.value = '' }}>Мои подкасты</button>
              </div>
              <div class="pc-grid">
                {res.map((p) => <PodTile key={p.feed} p={p} onClick={() => s.openFeed(p)} />)}
              </div>
            </>
          ) : subs.value.length ? (
            <div class="pc-grid">
              {subs.value.map((sub) => (
                <PodTile key={sub.podcast.feed} p={sub.podcast} fresh={(s.newCount.value[sub.podcast.feed] ?? 0) > 0}
                  onClick={() => s.openFeed(sub.podcast)} />
              ))}
            </div>
          ) : (
            <p class="hint">Подписок пока нет</p>
          )}
        </div>
      </div>
      {tab && <MiniPlayer />}
      {tab && <MainTabs at="podcasts" />}
    </div>
  )
}

export function PodcastFeedScreen({ s }: { s: PodcastSession }) {
  const p = s.podcast.value
  const eps = s.episodes.value
  // keys of the episodes that have a saved (AI) transcript, read once per list
  const [withTr, setWithTr] = useState<Set<string>>(() => new Set())
  useEffect(() => {
    if (!p || !eps) return
    let alive = true
    void Promise.all(eps.map(async (ep) => {
      const k = podKey(p.feed, ep.guid || ep.audio)
      const v = await loadTranscript(k).catch(() => null)
      return v?.length ? k : ''
    })).then((ks) => { if (alive) setWithTr(new Set(ks.filter(Boolean))) })
    return () => { alive = false }
  }, [p?.feed, eps])
  if (!p) return null
  const dl = s.downloading.value
  const subbed = isSub(p.feed) // reads subs.value: re-renders on (un)subscribe
  const playing = podNow.value?.key
  return (
    <div class="screen">
      <TopBar nav={<IconButton icon="back" label="Назад" onClick={() => { s.cancel(); back() }} />} title={p.title || 'Подкаст'} />
      <div class="screen-body scroll">
        <div class="content page">
          <div class="pc-head">
            <Cover url={p.image} title={p.title} class="pc-head-cover" />
            <div class="pc-head-main">
              <div class="pc-head-title">{p.title}</div>
              {p.author && <div class="pc-head-author">{p.author}</div>}
              {subbed
                ? <button class="btn outlined" onClick={() => s.toggleSub()}>Вы подписаны ✓</button>
                : <button class="btn tonal" onClick={() => s.toggleSub()}>Подписаться</button>}
            </div>
          </div>
          {dl && (
            <div class="pod-dl">
              <div class="pod-title">{dl.title}</div>
              <div class="bar-progress"><div style={{ width: s.progress.value * 100 + '%' }} /></div>
              <div class="row">
                <span class="grow hint">{s.progressText.value}</span>
                <button class="btn text" onClick={() => s.cancel()}>Отмена</button>
              </div>
            </div>
          )}
          {s.feedError.value && <p class="error-text">{s.feedError.value}</p>}
          {!eps && !s.feedError.value && <div class="row-status"><span class="spinner-sm" />Загружаю выпуски…</div>}
          {eps && eps.map((ep) => {
            const key = podKey(p.feed, ep.guid || ep.audio)
            const on = playing === key
            return (
              <button key={ep.audio} class={'pc-ep' + (on ? ' playing' : '')} disabled={!!dl}
                onClick={() => playEpisode(podEpisodeOf(ep, p), { ep, pod: p })}>
                {ep.date > s.seenBefore && <span class="pc-new" />}
                <span class="pc-ep-main">
                  <span class="pc-ep-title">{ep.title}</span>
                  <span class="pc-ep-meta">
                    <span>{[fmtDate(ep.date), fmtDur(ep.duration)].filter(Boolean).join(' · ')}</span>
                    {withTr.has(key) && <span class="pc-ai">AI</span>}
                  </span>
                </span>
                {on && <Icon name="play" size={18} />}
              </button>
            )
          })}
        </div>
      </div>
    </div>
  )
}
