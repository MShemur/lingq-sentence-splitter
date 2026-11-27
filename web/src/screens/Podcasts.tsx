// Podcasts: search / subscriptions as a grid of covers, and the episodes of one podcast. Port of Android PodcastsScreen / PodcastFeedScreen.
import { useEffect, useState } from 'preact/hooks'
import { loadTranscript, playEpisode, podEpisodeOf, podKey, podNow } from '../podplay'
import { back, stack } from '../app'
import { MainTabs, MiniPlayer } from './Player'
import { isSub, subs, type Podcast, type PodcastSession } from '../importer/podcasts'
import { ClearX, IconButton, TopBar } from '../ui'
import { Icon } from '../icons'
import { Cover } from './Library'
import './Podcasts.css'

const fmtDate = (t: number) => (t > 0 ? new Date(t).toLocaleDateString('ru-RU', { day: 'numeric', month: 'short', year: 'numeric' }) : '')
const fmtDur = (s: number) => (s <= 0 ? '' : s >= 3600 ? `${Math.floor(s / 3600)} ч ${Math.floor(s / 60) % 60} мин` : `${Math.floor((s + 30) / 60)} мин`)

/** A podcast as a square cover with its title under it; [badge] = new episodes. */
function PodTile({ p, badge, onClick }: { p: Podcast; badge?: number; onClick: () => void }) {
  const title = p.title || p.feed
  return (
    <button class="pc-tile" onClick={onClick} aria-label={title}>
      <span class="pc-cover-wrap">
        <Cover url={p.image} title={title} class="pc-cover" />
        {!!badge && badge > 0 && <span class="pc-badge">{badge}</span>}
      </span>
      <span class="pc-tile-title">{title}</span>
    </button>
  )
}

export function PodcastsScreen({ s }: { s: PodcastSession }) {
  useEffect(() => { s.refreshNew() }, [subs.value.length])
  const res = s.results.value
  const tab = stack.value[0] === s // opened as the bottom tab
  return (
    <div class="screen">
      <TopBar nav={tab ? undefined : <IconButton icon="back" label="Назад" onClick={back} />} title="Подкасты" />
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
                <PodTile key={sub.podcast.feed} p={sub.podcast} badge={s.newCount.value[sub.podcast.feed] ?? 0}
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
