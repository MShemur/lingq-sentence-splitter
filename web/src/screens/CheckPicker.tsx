// "Уже на LingQ" on the import screen: pick a course / lessons (or paste a link) for the split checker.
import { useEffect, useState } from 'preact/hooks'
import { courses, coursesError, errorText, loadCourses, loadRecent, recent } from '../app'
import * as api from '../lingq'
import type { Course, Lesson } from '../lingq'
import { Icon } from '../icons'
import { IconButton, ListItem, Loading, Sheet } from '../ui'
import { checkLessons, openLink } from '../check'

function Picker({ lang, onClose }: { lang: string; onClose: () => void }) {
  const [course, setCourse] = useState<Course | null>(null)
  const [lessons, setLessons] = useState<Lesson[] | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const [sel, setSel] = useState<Set<number>>(new Set())
  useEffect(() => { void loadCourses(); void loadRecent() }, [])

  const open = (c: Course) => {
    setCourse(c); setLessons(null); setErr(null); setSel(new Set())
    api.lessons(lang, c.id).then((r) => setLessons(r.lessons), (e) => setErr(errorText(e)))
  }
  const start = (list: Lesson[], label: string) => { onClose(); checkLessons(lang, list, label) }

  if (course) {
    const all = !!lessons?.length && sel.size === lessons.length
    return (
      <Sheet onClose={onClose}>
        <div class="row">
          <IconButton icon="back" label="К курсам" onClick={() => setCourse(null)} />
          <h3 class="sheet-title grow">{course.title || `Курс ${course.id}`}</h3>
        </div>
        {err ? <p class="error-text">{err}</p> : !lessons ? <Loading /> : (
          <div class="pick-list">
            <label class="check-row">
              <input type="checkbox" checked={all} onChange={() => setSel(all ? new Set() : new Set(lessons.map((l) => l.id)))} />
              <span>Все уроки курса ({lessons.length})</span>
            </label>
            {lessons.map((l) => (
              <label key={l.id} class="check-row">
                <input type="checkbox" checked={sel.has(l.id)} onChange={() => {
                  const n = new Set(sel)
                  if (!n.delete(l.id)) n.add(l.id)
                  setSel(n)
                }} />
                <span>{l.title || `Урок ${l.id}`}</span>
              </label>
            ))}
            <button class="btn filled wide" disabled={!sel.size} onClick={() =>
              start(lessons.filter((l) => sel.has(l.id)), sel.size === lessons.length ? (course.title || `Курс ${course.id}`) : `${course.title}: ${sel.size} ур.`)}>
              Проверить{sel.size ? ` (${sel.size})` : ''}
            </button>
          </div>
        )}
      </Sheet>
    )
  }
  const list = courses.value, rec = recent.value
  return (
    <Sheet onClose={onClose}>
      <h3 class="sheet-title">Выберите курс или урок</h3>
      {!!rec?.length && (
        <>
          <h3 class="section-title">Недавние уроки</h3>
          {rec.map((l) => (
            <ListItem key={l.id} headline={l.title || `Урок ${l.id}`} supporting={l.courseTitle || undefined}
              onClick={() => start([l], l.title || `Урок ${l.id}`)} />
          ))}
        </>
      )}
      <h3 class="section-title">Курсы</h3>
      {coursesError.value ? <p class="error-text">Курсы не загрузились: {coursesError.value}</p>
        : !list ? <Loading />
        : list.map((c) => <ListItem key={c.id} headline={c.title || `Курс ${c.id}`} trailing={<Icon name="chevron" />} onClick={() => open(c)} />)}
    </Sheet>
  )
}

/** Block of the import screen: button with the picker, and a link field. */
export function CheckBlock({ lang }: { lang: string }) {
  const [pick, setPick] = useState(false)
  const [link, setLink] = useState('')
  const [busy, setBusy] = useState(false)
  return (
    <div class="check-block">
      <h3 class="section-title">Уже на LingQ</h3>
      <p class="hint">Исправить разбивку на предложения в уже загруженных уроках (сокращения, «19. Jahrhundert»). Перед записью сохраняется копия.</p>
      <button class="btn tonal wide" onClick={() => setPick(true)}><Icon name="check" size={18} />Выбрать курс или урок</button>
      <div class="link-row">
        <label class="field">
          <span>Ссылка на урок или курс</span>
          <input value={link} placeholder="https://www.lingq.com/…" onInput={(e) => setLink((e.target as HTMLInputElement).value)} />
        </label>
        <button class="btn outlined" disabled={!link.trim() || busy} onClick={() => {
          setBusy(true)
          void openLink(link, lang).finally(() => setBusy(false))
        }}>Проверить</button>
      </div>
      {pick && <Picker lang={lang} onClose={() => setPick(false)} />}
    </div>
  )
}
