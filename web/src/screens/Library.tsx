// Library: courses of the studied language as covers (with a "continue" row) -> lessons as covers -> reader.
// Port of Android Library.kt.
import { useEffect, useState } from 'preact/hooks'
import type { JSX } from 'preact'
import {
  back, courses, coursesError, courseWords, ensureCourseWords, go, loadCourses, loadRecent, LessonsScreen, recent, wordsKey,
} from '../app'
import { lang, prefs } from '../settings'
import { ErrorBox, IconButton, Loading, TopBar } from '../ui'
import type { Course, Lesson } from '../lingq'
import { ReaderSession } from '../reader/session'
import { percent, positionOf } from '../progress'

function plural(n: number, one: string, few: string, many: string) {
  const m10 = n % 10, m100 = n % 100
  return m10 === 1 && m100 !== 11 ? one : m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14) ? few : many
}

/** 12345 -> "12 345 слов". */
const wordsText = (n: number) => `${n.toLocaleString('ru-RU')} ${plural(n, 'слово', 'слова', 'слов')}`

/** Cover picture; while it loads (or without one) a colour gradient with the first letter. */
function Cover({ url, title, ratio = '1', class: cls = '', children }: {
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
        {read > 0 && <span class="cover-progress"><span style={{ width: Math.min(100, read * 100) + '%' }} /></span>}
      </Cover>
      <span class="tile-title">{title}</span>
      {line && <span class="tile-sub">{line}</span>}
    </button>
  )
}

export function CoursesScreen() {
  const [q, setQ] = useState('')
  const l = lang()
  useEffect(() => { if (prefs.apiKey.value.trim()) { void loadCourses(); void loadRecent() } }, [l, prefs.apiKey.value])
  const list = courses.value
  const err = coursesError.value
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
          </>
        }
      />
      <div class="screen-body scroll">
        <div class="content library">
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
                : !list ? <Loading />
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
    </div>
  )
}

export function LessonsView({ s }: { s: LessonsScreen }) {
  useEffect(() => { if (!s.lessons.value) void s.load() }, [s])
  const list = s.lessons.value
  const err = s.error.value
  return (
    <div class="screen">
      <TopBar nav={<IconButton icon="back" label="Назад" onClick={back} />} title={s.title.value} />
      <div class="screen-body scroll">
        <div class="content library">
          <div class="course-head">
            <Cover url={s.course.imageUrl} title={s.title.value} class="course-head-cover" />
            <div>
              <div class="course-head-title">{s.title.value}</div>
              {list && <div class="course-head-sub">{list.length} {plural(list.length, 'урок', 'урока', 'уроков')}</div>}
            </div>
          </div>
          {err ? <ErrorBox text={err} retry={() => void s.load()} />
            : !list ? <Loading />
            : (
              <div class="tile-grid">
                {list.map((x) => <LessonTile key={x.id} l={x} langCode={s.lang} sub={x.words ? wordsText(x.words) : ''} />)}
              </div>
            )}
          {list && !list.length && <p class="empty">В курсе нет уроков.</p>}
        </div>
      </div>
    </div>
  )
}
