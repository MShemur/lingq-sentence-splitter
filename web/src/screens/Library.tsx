// Library: courses of the studied language as covers (with a "continue" row) -> lessons as covers -> reader.
// Port of Android Library.kt.
import { useEffect, useState } from 'preact/hooks'
import type { JSX } from 'preact'
import {
  archive, back, courses, coursesError, courseWords, ensureCourseWords, go, loadCourses, loadRecent, LessonsScreen, recent, visibleCourses, wordsKey,
} from '../app'
import { CourseMenu } from './CourseEdit'
import { JobsRow } from './Jobs'
import { lang, prefs } from '../settings'
import { Chip, ErrorBox, IconButton, Loading, Menu, MenuItem, TopBar } from '../ui'
import { naturalCompare } from '../app'
import type { Course, Lesson } from '../lingq'
import { ReaderSession } from '../reader/session'
import { ImportSession } from '../importer/session'
import { Icon } from '../icons'
import { percent, positionOf } from '../progress'
import { MainTabs, MiniPlayer } from './Player'
import { DAILY_GOAL_MIN, dayOf, stats, streak } from '../stats'

export function plural(n: number, one: string, few: string, many: string) {
  const m10 = n % 10, m100 = n % 100
  return m10 === 1 && m100 !== 11 ? one : m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14) ? few : many
}

/** 12345 -> "12 345 слов". */
const wordsText = (n: number) => `${n.toLocaleString('ru-RU')} ${plural(n, 'слово', 'слова', 'слов')}`

/** Cover picture; while it loads (or without one) a colour gradient with the first letter. */
export function Cover({ url, title, ratio = '1', class: cls = '', children }: {
  url?: string; title: string; ratio?: string; class?: string; children?: JSX.Element | false | (JSX.Element | false)[]
}) {
  let h = 0
  for (const ch of title) h = (h * 31 + ch.charCodeAt(0)) >>> 0
  const hue = h % 360
  const bg = `linear-gradient(135deg, hsl(${hue} 40% 62%), hsl(${(hue + 40) % 360} 45% 42%))`
  const letter = [...title.trim()].find((c) => /[\p{L}\p{N}]/u.test(c))?.toUpperCase() ?? '•'
  const [failed, setFailed] = useState(false)
  return (
    <div class={'cover ' + cls} style={{ aspectRatio: ratio, background: bg }}>
      <span class="cover-letter">{letter}</span>
      {url && !failed && <img src={url} alt="" loading="lazy" decoding="async" onError={() => setFailed(true)} />}
      {children}
    </div>
  )
}

function CourseTile({ c, l }: { c: Course; l: string }) {
  useEffect(() => ensureCourseWords(l, c.id), [l, c.id])
  const w = courseWords.value[wordsKey(l, c.id)]
  const title = c.title || `Курс ${c.id}`
  return (
    <button class="tile" onClick={() => go(new LessonsScreen(l, c))}>
      <Cover url={c.imageUrl} title={title} />
      <span class="tile-title">{title}</span>
      {!!w && <span class="tile-sub">{wordsText(w)}</span>}
    </button>
  )
}

/** Lesson: cover with the level and a thin reading-progress bar, title and a short line under it. */
function LessonTile({ l, langCode, sub }: { l: Lesson; langCode: string; sub: string }) {
  const read = positionOf(langCode, l.id)?.p ?? 0
  const title = l.title || `Урок ${l.id}`
  const line = [sub, read > 0 ? percent(read) : ''].filter(Boolean).join(' · ')
  return (
    <button class="tile" onClick={() => go({ k: 'reader', s: new ReaderSession(langCode, l) })}>
      <Cover url={l.imageUrl} title={title} ratio="4 / 3">
        {!!l.level && <span class="cover-level">{l.level}</span>}
        {!!(l.audio || l.duration) && (
          <span class="cover-audio" title="Есть аудио"><Icon name="headphones" size={14} />{l.duration ? ` ${Math.round(l.duration / 60)} мин` : ''}</span>
        )}
        {read > 0 && <span class="cover-progress"><span style={{ width: Math.min(100, read * 100) + '%' }} /></span>}
      </Cover>
      <span class="tile-title">{title}</span>
      {line && <span class="tile-sub">{line}</span>}
    </button>
  )
}

function greeting() {
  const h = new Date().getHours()
  return h < 5 ? 'Доброй ночи' : h < 12 ? 'Доброе утро' : h < 18 ? 'Добрый день' : 'Добрый вечер'
}

/** Top card: greeting, ring "minutes today / goal", words today, days in a row (tap = statistics). */
function TodayCard() {
  void stats.value // re-render when the minutes change
  const [sec, words] = dayOf(new Date())
  const min = Math.floor(sec / 60)
  const days = streak()
  const r = 32, c = 2 * Math.PI * r
  const part = Math.min(1, min / DAILY_GOAL_MIN)
  return (
    <button class="today-card" onClick={() => go({ k: 'stats' })}>
      <span class="ring">
        <svg viewBox="0 0 76 76" width="76" height="76">
          <circle cx="38" cy="38" r={r} class="ring-track" />
          <circle cx="38" cy="38" r={r} class="ring-fill" stroke-dasharray={`${c * part} ${c}`} transform="rotate(-90 38 38)" />
        </svg>
        <span class="ring-num"><b>{min}</b><small>мин</small></span>
      </span>
      <span class="today-text">
        <span class="today-hello">{greeting()}</span>
        <span>{min >= DAILY_GOAL_MIN ? 'Цель на сегодня выполнена' : `Сегодня ${min} из ${DAILY_GOAL_MIN} мин`}{words > 0 ? ` · ${words} слов` : ''}</span>
        {days > 0 && <span class="streak">🔥 {days} {plural(days, 'день', 'дня', 'дней')} подряд</span>}
      </span>
    </button>
  )
}

/** Breathing grey shapes while the covers load. */
const SkeletonTiles = ({ n, ratio = '1' }: { n: number; ratio?: string }) => (
  <>{Array.from({ length: n }, (_, i) => (
    <div class="tile" key={i}><div class="skeleton" style={{ aspectRatio: ratio, borderRadius: '12px' }} /><div class="skeleton line" /></div>
  ))}</>
)

export function CoursesScreen() {
  const [q, setQ] = useState('')
  const l = lang()
  useEffect(() => { if (prefs.apiKey.value.trim()) { void loadCourses(); void loadRecent() } }, [l, prefs.apiKey.value])
  const list = visibleCourses.value
  const err = coursesError.value
  const [menu, setMenu] = useState(false)
  const archived = (archive.value[l] ?? []).length
  const noKey = !prefs.apiKey.value.trim()
  const rec = recent.value ?? []
  return (
    <div class="screen">
      <TopBar
        title={`Библиотека · ${l}`}
        actions={
          <>
            <IconButton icon="bookmark" label="Закладки" onClick={() => go({ k: 'bookmarks' })} />
            <IconButton icon="settings" label="Настройки" onClick={() => go({ k: 'settings' })} />
            <div class="menu-anchor">
              <IconButton icon="more" label="Ещё" onClick={() => setMenu(true)} />
              {menu && (
                <Menu onClose={() => setMenu(false)}>
                  <MenuItem icon="library" onClick={() => { setMenu(false); go({ k: 'archive' }) }}>
                    Архив курсов{archived ? ` (${archived})` : ''}
                  </MenuItem>
                  <MenuItem icon="chat" onClick={() => { setMenu(false); go({ k: 'feedback' }) }}>Отзыв / задача</MenuItem>
                </Menu>
              )}
            </div>
          </>
        }
      />
      <div class="body-wrap">
      <div class="screen-body scroll">
        <div class="content library">
          {!noKey && <TodayCard />}
          {noKey ? (
            <div class="card error-container">
              <span class="grow">Укажите API-ключ LingQ</span>
              <button class="btn text" onClick={() => go({ k: 'settingsLingq' })}>Настроить</button>
            </div>
          ) : (
            <>
              <div class="search-row">
                <input class="search" type="search" placeholder="Поиск курса" value={q} onInput={(e) => setQ((e.target as HTMLInputElement).value)} />
                <IconButton icon="refresh" label="Обновить" onClick={() => { void loadCourses(true); void loadRecent() }} />
              </div>
              {!q.trim() && <JobsRow />}
              {!q.trim() && !recent.value && (
                <>
                  <h2 class="section-title">Продолжить</h2>
                  <div class="tile-row"><SkeletonTiles n={4} ratio="4 / 3" /></div>
                </>
              )}
              {!q.trim() && rec.length > 0 && (
                <>
                  <h2 class="section-title">Продолжить</h2>
                  <div class="tile-row">
                    {rec.map((x) => <LessonTile key={x.id} l={x} langCode={l} sub={x.courseTitle ?? ''} />)}
                  </div>
                </>
              )}
              <h2 class="section-title">Мои курсы</h2>
              {err ? <ErrorBox text={err} retry={() => void loadCourses(true)} />
                : !list ? <div class="tile-grid"><SkeletonTiles n={8} /></div>
                : (
                  <div class="tile-grid">
                    {list.filter((c) => !q.trim() || c.title.toLowerCase().includes(q.trim().toLowerCase()))
                      .map((c) => <CourseTile key={c.id} c={c} l={l} />)}
                  </div>
                )}
              {list && !list.length && <p class="empty">Курсов нет.</p>}
            </>
          )}
        </div>
      </div>
      {!noKey && (
        <button class="fab" onClick={() => go(new ImportSession())}>
          <Icon name="upload" /><span>Загрузить</span>
        </button>
      )}
      </div>
      <MiniPlayer />
      <MainTabs at="courses" />
    </div>
  )
}

export function LessonsView({ s }: { s: LessonsScreen }) {
  useEffect(() => { if (!s.lessons.value) void s.load() }, [s])
  const [sort, setSortState] = useState(() => { try { return Number(localStorage.getItem('lessons.sort')) || 0 } catch { return 0 } })
  const setSort = (i: number) => { setSortState(i); try { localStorage.setItem('lessons.sort', String(i)) } catch { /* ignore */ } }
  const raw = s.lessons.value
  // 0 as in the course, 1 newest first (lesson ids grow with each import), 2 oldest first, 3 by name
  const list = raw && (sort === 1 ? [...raw].sort((a, b) => b.id - a.id) : sort === 2 ? [...raw].sort((a, b) => a.id - b.id)
    : sort === 3 ? [...raw].sort((a, b) => naturalCompare(a.title, b.title)) : raw)
  const err = s.error.value
  return (
    <div class="screen">
      <TopBar nav={<IconButton icon="back" label="Назад" onClick={back} />} title={s.title.value} actions={<CourseMenu s={s} />} />
      <div class="screen-body scroll">
        <div class="content library">
          <div class="course-head">
            <Cover url={(courses.value?.find((c) => c.id === s.course.id) ?? s.course).imageUrl} title={s.title.value} class="course-head-cover" />
            <div>
              <div class="course-head-title">{s.title.value}</div>
              {list && <div class="course-head-sub">{list.length} {plural(list.length, 'урок', 'урока', 'уроков')}</div>}
            </div>
          </div>
          {err ? <ErrorBox text={err} retry={() => void s.load()} />
            : !list ? <div class="tile-grid"><SkeletonTiles n={8} ratio="4 / 3" /></div>
            : (
              <>
              {list.length > 1 && <div class="chip-row pad-x">
                {['Как в курсе', 'Новые сверху', 'Старые сверху', 'По имени'].map((t, i) => (
                  <Chip key={t} label={t} selected={sort === i} onClick={() => setSort(i)} />
                ))}
              </div>}
              <div class="tile-grid">
                {list.map((x) => <LessonTile key={x.id} l={x} langCode={s.lang} sub={x.words ? wordsText(x.words) : ''} />)}
              </div>
              </>
            )}
          {list && !list.length && <p class="empty">В курсе нет уроков.</p>}
        </div>
      </div>
      <MiniPlayer />
    </div>
  )
}
