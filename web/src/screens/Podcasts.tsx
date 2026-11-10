// Podcasts: search / subscriptions, and the episodes of one podcast. Port of Android PodcastsScreen / PodcastFeedScreen.
import { useEffect } from 'preact/hooks'
import { back } from '../app'
import { isSub, subs, type Podcast, type PodcastSession } from '../importer/podcasts'
import { Chip, IconButton, TopBar } from '../ui'
import { Cover } from './Library'

const SUGGEST_DE = ['Langsam gesprochene Nachrichten', 'Top-Thema mit Vokabeln', 'Easy German', 'Slow German', 'Deutschlandfunk', 'Coffee Break German']

const fmtDate = (t: number) => (t > 0 ? new Date(t).toLocaleDateString('ru-RU', { day: 'numeric', month: 'short', year: 'numeric' }) : '')
const fmtDur = (s: number) => (s <= 0 ? '' : s >= 3600 ? `${Math.floor(s / 3600)} ч ${Math.floor(s / 60) % 60} мин` : `${Math.floor((s + 30) / 60)} мин`)

function PodcastRow({ p, badge, onClick }: { p: Podcast; badge?: string; onClick: () => void }) {
  return (
    <button class="pod-row" onClick={onClick}>
      <Cover url={p.image} title={p.title} class="pod-cover" />
      <span class="grow">
        <span class="pod-title">{p.title || p.feed}</span>
        {p.author && <span class="hint pod-author">{p.author}</span>}
      </span>
      {badge && <span class="pod-badge">{badge}</span>}
    </button>
  )
}

export function PodcastsScreen({ s }: { s: PodcastSession }) {
  useEffect(() => { s.refreshNew() }, [])
  const res = s.results.value
  return (
    <div class="screen">
      <TopBar nav={<IconButton icon="back" label="Назад" onClick={back} />} title="Подкасты" />
      <div class="screen-body scroll">
        <div class="content page">
          <form class="pod-search" onSubmit={(e) => { e.preventDefault(); void s.search() }}>
            <label class="field grow">
              <span>Название подкаста или ссылка на RSS</span>
              <input type="search" enterkeyhint="search" value={s.query.value} autocapitalize="off"
                onInput={(e) => { s.query.value = (e.target as HTMLInputElement).value }} />
            </label>
            <button class="btn tonal" type="submit">Найти</button>
          </form>
          {s.busy.value && <div class="row-status"><span class="spinner-sm" />Ищу…</div>}
          {s.error.value && <p class="error-text">{s.error.value}</p>}
          {!res && s.lang === 'de' && (
            <>
              <p class="hint">Например:</p>
              <div class="chips">
                {SUGGEST_DE.map((q) => (
                  <Chip key={q} label={q} onClick={() => { s.query.value = q; void s.search() }} />
                ))}
              </div>
            </>
          )}
          {res ? (
            <>
              <div class="row">
                <h3 class="section-title grow">Найдено: {res.length}</h3>
                <button class="btn text" onClick={() => { s.results.value = null; s.query.value = '' }}>Мои подкасты</button>
              </div>
              {res.map((p) => <PodcastRow key={p.feed} p={p} onClick={() => s.openFeed(p)} />)}
            </>
          ) : (
            <>
              <h3 class="section-title">Мои подкасты</h3>
              {!subs.value.length && <p class="hint">Пока нет. Найдите подкаст и нажмите «Подписаться» — новые выпуски будут отмечены здесь.</p>}
              {subs.value.map((sub) => {
                const n = s.newCount.value[sub.podcast.feed] ?? 0
                return <PodcastRow key={sub.podcast.feed} p={sub.podcast} badge={n > 0 ? `${n} нов.` : undefined} onClick={() => s.openFeed(sub.podcast)} />
              })}
            </>
          )}
        </div>
      </div>
    </div>
  )
}

export function PodcastFeedScreen({ s }: { s: PodcastSession }) {
  const p = s.podcast.value
  if (!p) return null
  const eps = s.episodes.value
  const dl = s.downloading.value
  const subbed = isSub(p.feed) // reads subs.value: re-renders on (un)subscribe
  return (
    <div class="screen">
      <TopBar nav={<IconButton icon="back" label="Назад" onClick={() => { s.cancel(); back() }} />} title={p.title || 'Подкаст'} />
      <div class="screen-body scroll">
        <div class="content page">
          <div class="pod-head">
            <Cover url={p.image} title={p.title} class="pod-cover-big" />
            <div class="grow">
              <div class="pod-head-title">{p.title}</div>
              {p.author && <p class="hint">{p.author}</p>}
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
          {eps && (
            <>
              <p class="hint">Выпусков: {eps.length}. Нажмите на выпуск: он скачается, потом выберете, какую часть взять.</p>
              {eps.map((ep) => (
                <button key={ep.audio} class="pod-ep" disabled={!!dl} onClick={() => void s.pick(ep)}>
                  {ep.date > s.seenBefore && <span class="pod-new" />}
                  <span class="grow">
                    <span class="pod-title">{ep.title}</span>
                    <span class="pod-meta">
                      {[fmtDate(ep.date), fmtDur(ep.duration), ep.transcript ? 'есть расшифровка' : ''].filter(Boolean).join(' · ')}
                    </span>
                    {ep.description && <span class="hint pod-desc">{ep.description}</span>}
                  </span>
                </button>
              ))}
            </>
          )}
        </div>
      </div>
    </div>
  )
}
