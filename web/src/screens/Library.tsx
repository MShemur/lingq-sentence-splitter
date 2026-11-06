// Library: courses of the studied language -> lessons -> reader.
import { useEffect, useState } from 'preact/hooks'
import { Icon } from '../icons'
import { back, courses, coursesError, go, loadCourses, LessonsScreen } from '../app'
import { lang, prefs } from '../settings'
import { ErrorBox, IconButton, ListItem, Loading, ProgressPill, TopBar } from '../ui'
import { ReaderSession } from '../reader/session'
import { percent, positionOf } from '../progress'

export function CoursesScreen() {
  const [q, setQ] = useState('')
  const l = lang()
  useEffect(() => { if (prefs.apiKey.value.trim()) void loadCourses() }, [l, prefs.apiKey.value])
  const list = courses.value
  const err = coursesError.value
  const noKey = !prefs.apiKey.value.trim()
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
        <div class="content">
          {noKey ? (
            <div class="card error-container">
              <span class="grow">Укажите API-ключ LingQ</span>
              <button class="btn text" onClick={() => go({ k: 'settingsLingq' })}>Настроить</button>
            </div>
          ) : (
            <>
              <div class="search-row">
                <input class="search" type="search" placeholder="Поиск курса" value={q} onInput={(e) => setQ((e.target as HTMLInputElement).value)} />
                <IconButton icon="refresh" label="Обновить" onClick={() => void loadCourses(true)} />
              </div>
              {err ? <ErrorBox text={err} retry={() => void loadCourses(true)} />
                : !list ? <Loading />
                : list.filter((c) => !q.trim() || c.title.toLowerCase().includes(q.trim().toLowerCase())).map((c) => (
                  <ListItem
                    key={c.id} headline={c.title || `Курс ${c.id}`} class="divided"
                    trailing={<Icon name="chevron" />}
                    onClick={() => go(new LessonsScreen(l, c))}
                  />
                ))}
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
        <div class="content">
          {err ? <ErrorBox text={err} retry={() => void s.load()} />
            : !list ? <Loading />
            : list.map((l) => {
              const pos = positionOf(s.lang, l.id)
              return (
                <ListItem key={l.id} headline={l.title || `Урок ${l.id}`} class="divided"
                  supporting={pos ? <span class="read-pct"><ProgressPill p={pos.p} /> Прочитано {percent(pos.p)}</span> : undefined}
                  onClick={() => go({ k: 'reader', s: new ReaderSession(s.lang, l) })} />
              )
            })}
          {list && !list.length && <p class="empty">В курсе нет уроков.</p>}
        </div>
      </div>
    </div>
  )
}
