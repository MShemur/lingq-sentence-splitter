// Course menu (edit title / cover, archive, delete), the course archive screen and the delete-lesson confirmation.
import { useEffect, useMemo, useState } from 'preact/hooks'
import {
  archive, archiveCourse, archivedIds, back, courseChanged, courses, forgetCourse, forgetLesson, go, LessonsScreen, loadCourses,
  restoreCourse, say,
} from '../app'
import * as api from '../lingq'
import type { Course } from '../lingq'
import { lang } from '../settings'
import { Dialog, IconButton, Menu, MenuItem, Spinner, TopBar } from '../ui'
import { log } from '../applog'
import { WordStatsSession } from '../wordstats'
import { Cover, plural } from './Library'

/** ⋮ on the course screen. */
export function CourseMenu({ s }: { s: LessonsScreen }) {
  const [open, setOpen] = useState(false)
  const [dlg, setDlg] = useState<'edit' | 'delete' | null>(null)
  const c = courses.value?.find((x) => x.id === s.course.id) ?? s.course
  const archived = archivedIds.value.has(c.id)
  const close = () => setOpen(false)
  return (
    <div class="menu-anchor">
      <IconButton icon="more" label="Ещё" onClick={() => setOpen(true)} />
      {open && (
        <Menu onClose={close}>
          <MenuItem icon="edit" onClick={() => { close(); setDlg('edit') }}>Изменить курс</MenuItem>
          <MenuItem icon="chart" onClick={() => { close(); go({ k: 'wordStats', ws: new WordStatsSession(s.lang, { ...c, title: s.title.value || c.title }) }) }}>Слова по урокам</MenuItem>
          {archived
            ? <MenuItem icon="history" onClick={() => { close(); restoreCourse(s.lang, c.id); say('Курс снова в библиотеке') }}>Вернуть из архива</MenuItem>
            : <MenuItem icon="library" onClick={() => { close(); archiveCourse(s.lang, { ...c, title: s.title.value }); say('Курс убран в архив (⋮ в библиотеке)'); back() }}>В архив</MenuItem>}
          <hr />
          <MenuItem icon="delete" onClick={() => { close(); setDlg('delete') }}>Удалить курс…</MenuItem>
        </Menu>
      )}
      {dlg === 'edit' && <CourseEditDialog s={s} c={c} onClose={() => setDlg(null)} />}
      {dlg === 'delete' && <CourseDeleteDialog s={s} c={c} onClose={() => setDlg(null)} />}
    </div>
  )
}

/** New title and / or cover picture for the course (saved on LingQ). */
function CourseEditDialog({ s, c, onClose }: { s: LessonsScreen; c: Course; onClose: () => void }) {
  const [title, setTitle] = useState(s.title.value)
  const [file, setFile] = useState<File | null>(null)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const url = useMemo(() => (file ? URL.createObjectURL(file) : ''), [file])
  useEffect(() => () => { if (url) URL.revokeObjectURL(url) }, [url])
  const changed = (title.trim() && title.trim() !== s.title.value) || !!file
  const save = async () => {
    setBusy(true); setErr(null)
    try {
      if (title.trim() && title.trim() !== s.title.value) await api.renameCourse(s.lang, c.id, title.trim())
      if (file) await api.setCourseImage(s.lang, c.id, file)
      courseChanged({ ...c, title: title.trim() || c.title })
      log('lingq', `Курс ${c.id} изменён${file ? ' (обложка)' : ''}`)
      if (file) void loadCourses(true)
      say('Курс сохранён')
      onClose()
    } catch (e) {
      setErr((e as Error).message)
    }
    setBusy(false)
  }
  return (
    <Dialog title="Изменить курс" onClose={() => { if (!busy) onClose() }} actions={<>
      <button class="btn text" disabled={busy} onClick={onClose}>Отмена</button>
      <button class="btn text" disabled={busy || !changed} onClick={() => void save()}>{busy ? <Spinner size={18} /> : 'Сохранить'}</button>
    </>}>
      <div class="course-edit">
        <label class="field">
          <span>Название</span>
          <input value={title} disabled={busy} onInput={(e) => setTitle((e.target as HTMLInputElement).value)} />
        </label>
        <div class="course-edit-cover">
          {url ? <img class="cover-thumb big" src={url} alt="" /> : <Cover url={c.imageUrl} title={title || c.title} class="cover-thumb big" />}
          <label class="btn tonal">
            Выбрать обложку
            <input type="file" accept="image/*" hidden disabled={busy}
              onChange={(e) => { const f = (e.target as HTMLInputElement).files?.[0]; if (f) setFile(f) }} />
          </label>
        </div>
        {err && <p class="error-text">{err}</p>}
      </div>
    </Dialog>
  )
}

function CourseDeleteDialog({ s, c, onClose }: { s: LessonsScreen; c: Course; onClose: () => void }) {
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const n = s.lessons.value?.length
  const del = async () => {
    setBusy(true); setErr(null)
    try {
      await api.deleteCourse(s.lang, c.id)
      log('lingq', `Курс ${c.id} удалён`)
      forgetCourse(s.lang, c.id)
      onClose(); back()
      say(`Курс «${s.title.value}» удалён`)
    } catch (e) {
      setErr((e as Error).message); setBusy(false)
    }
  }
  return (
    <Dialog title="Удалить курс?" onClose={() => { if (!busy) onClose() }} actions={<>
      <button class="btn text" disabled={busy} onClick={onClose}>Отмена</button>
      <button class="btn text danger" disabled={busy} onClick={() => void del()}>{busy ? <Spinner size={18} /> : 'Удалить'}</button>
    </>}>
      <p>
        Курс «{s.title.value}»{n != null ? ` и ${n} ${plural(n, 'урок', 'урока', 'уроков')}` : ' и все его уроки'} будут удалены
        на LingQ. Отменить это нельзя.
      </p>
      <p class="hint">Если курс просто мешает, уберите его в архив: он исчезнет из библиотеки, но останется на LingQ.</p>
      {err && <p class="error-text">{err}</p>}
    </Dialog>
  )
}

/** Reader ⋮ → "Удалить урок": confirmation, then the lesson is deleted on LingQ and the reader closes. */
export function LessonDeleteDialog({ l, id, title, onClose }: { l: string; id: number; title: string; onClose: () => void }) {
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const del = async () => {
    setBusy(true); setErr(null)
    try {
      await api.deleteLesson(l, id)
      log('lingq', `Урок ${id} удалён`)
      forgetLesson(l, id)
      onClose(); back()
      say(`Урок «${title}» удалён`)
    } catch (e) {
      setErr((e as Error).message); setBusy(false)
    }
  }
  return (
    <Dialog title="Удалить урок?" onClose={() => { if (!busy) onClose() }} actions={<>
      <button class="btn text" disabled={busy} onClick={onClose}>Отмена</button>
      <button class="btn text danger" disabled={busy} onClick={() => void del()}>{busy ? <Spinner size={18} /> : 'Удалить'}</button>
    </>}>
      <p>Урок «{title}» будет удалён на LingQ вместе с аудио и переводами. Отменить это нельзя.</p>
      {err && <p class="error-text">{err}</p>}
    </Dialog>
  )
}

/** Library ⋮ → "Архив курсов": hidden courses; open one or put it back. */
export function ArchiveScreen() {
  const l = lang()
  const list = archive.value[l] ?? []
  return (
    <div class="screen">
      <TopBar nav={<IconButton icon="back" label="Назад" onClick={back} />} title="Архив курсов" />
      <div class="screen-body scroll">
        <div class="content library">
          {!list.length ? <p class="empty">Архив пуст. Курс убирается сюда через ⋮ на странице курса.</p> : (
            <>
              <p class="hint pad">Эти курсы не видны в библиотеке и в плеере, на LingQ они остались.</p>
              <div class="tile-grid">
                {list.map((c) => (
                  <div class="tile" key={c.id}>
                    <button class="tile-open" onClick={() => go(new LessonsScreen(l, c))}>
                      <Cover url={c.imageUrl} title={c.title || `Курс ${c.id}`} />
                      <span class="tile-title">{c.title || `Курс ${c.id}`}</span>
                    </button>
                    <button class="btn text" onClick={() => { restoreCourse(l, c.id); say('Курс снова в библиотеке') }}>Вернуть</button>
                  </div>
                ))}
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  )
}
