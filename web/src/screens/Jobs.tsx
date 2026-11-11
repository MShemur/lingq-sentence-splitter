// Podcast episode → LingQ in the background: the sheet that picks the destination (while the episode downloads) and
// the "Актуальные загрузки" row in the library.
import { useEffect, useState } from 'preact/hooks'
import { go, loadCourses, say, visibleCourses } from '../app'
import { ReaderSession } from '../reader/session'
import { prefs } from '../settings'
import { sttCost, sttKeyFor, sttModel, sttProvider } from '../importer/stt'
import { closeJob, bgJobs, type BgJob } from '../importer/jobs'
import { Chip, IconButton, Sheet } from '../ui'
import { Icon } from '../icons'

const LENGTHS: [string, number | null][] = [['Весь выпуск', null], ['10 мин', 10], ['20 мин', 20], ['30 мин', 30]]

/** Where the episode goes; the download is already running underneath. */
export function EpisodeSheet({ j, onClose }: { j: BgJob; onClose: () => void }) {
  useEffect(() => { void loadCourses() }, [])
  const pod = j.pod
  const list = visibleCourses.value ?? []
  const same = list.find((c) => c.title.trim().toLowerCase() === (pod?.title ?? '').trim().toLowerCase())
  const [course, setCourse] = useState<string>(same ? String(same.id) : 'new')
  const [newTitle, setNewTitle] = useState(pod?.title ?? '')
  const [len, setLen] = useState<number | null>(null)
  const hasTr = !!j.ep.transcript, hasText = !hasTr && !!j.ep.textUrl
  const [useTr, setUseTr] = useState(hasTr)
  const [useText, setUseText] = useState(hasText)
  const [cover, setCover] = useState(!same)
  useEffect(() => { if (same && course === 'new') setCourse(String(same.id)) }, [same?.id])
  const prov = sttProvider(prefs.sttProvider.value)
  const needKey = !(hasTr && useTr) && !sttKeyFor(prov)
  const min = len ?? (j.ep.duration > 0 ? j.ep.duration / 60 : 0)
  const price = useTr && hasTr ? 'бесплатно, готовая расшифровка' : min > 0 ? `${prov.name}: ${sttCost(prov, sttModel(prov.id), min) ?? ''}` : prov.name
  const pct = Math.round(j.progress.value * 100)
  const ok = course !== 'new' || newTitle.trim()
  const go_ = () => {
    const c = course === 'new' ? null : list.find((x) => String(x.id) === course) ?? null
    void j.run({ course: c, newTitle: newTitle.trim(), fromMs: 0, lenMin: len, useTranscript: useTr, useText, cover })
    say('Загружается в фоне — ход виден в библиотеке')
    onClose()
  }
  const cancel = () => { closeJob(j); onClose() }
  return (
    <Sheet onClose={cancel}>
      <div class="ep-sheet">
        <h2 class="sheet-title">В LingQ</h2>
        <div class="pod-title">{j.title}</div>
        <div class="row-status"><span class="spinner-sm" />{j.status.value}{pct > 0 && pct < 100 ? ` · ${pct} %` : ''}</div>
        <label class="field">
          <span>Курс</span>
          <select value={course} onChange={(e) => setCourse((e.target as HTMLSelectElement).value)}>
            <option value="new">➕ Новый курс…</option>
            {list.map((c) => <option key={c.id} value={String(c.id)}>{c.title || `Курс ${c.id}`}</option>)}
          </select>
        </label>
        {course === 'new' && (
          <label class="field">
            <span>Название нового курса</span>
            <input value={newTitle} onInput={(e) => setNewTitle((e.target as HTMLInputElement).value)} />
          </label>
        )}
        <div class="chips">
          {LENGTHS.map(([t, v]) => <Chip key={t} label={t} selected={len === v} onClick={() => setLen(v)} />)}
        </div>
        {hasTr && (
          <label class="switch-row">
            <input type="checkbox" class="switch" checked={useTr} onChange={(e) => setUseTr((e.target as HTMLInputElement).checked)} />
            <span>Готовая расшифровка подкаста<br /><span class="hint">{useTr ? 'Бесплатно, без распознавания' : 'Распознает сервис из настроек'}</span></span>
          </label>
        )}
        {hasText && (
          <label class="switch-row">
            <input type="checkbox" class="switch" checked={useText} onChange={(e) => setUseText((e.target as HTMLInputElement).checked)} />
            <span>Текст подкаста<br /><span class="hint">{useText ? 'Текст от авторов, время предложений даст распознавание' : 'И текст, и время из распознавания'}</span></span>
          </label>
        )}
        {pod?.image && (
          <label class="check-row">
            <input type="checkbox" checked={cover} onChange={(e) => setCover((e.target as HTMLInputElement).checked)} />
            <span>Обложка подкаста для курса</span>
          </label>
        )}
        <div class="audio-cost">{price}</div>
        {needKey && (
          <p class="error-text small">
            Нужен ключ распознавания ({prov.name}).{' '}
            <button class="btn text" onClick={() => { cancel(); go({ k: 'settingsSpeech' }) }}>Настроить</button>
          </p>
        )}
        <button class="btn filled wide" disabled={!ok || needKey} onClick={go_}>Загрузить в фоне</button>
        <button class="btn text wide" onClick={cancel}>Отмена</button>
        <p class="hint">Расшифровку показывать не буду: урок сам появится в курсе. Пока он готовится, можно читать и слушать.</p>
      </div>
    </Sheet>
  )
}

/** Library: imports running in the background; finished ones open their lesson, errors stay until closed. */
export function JobsRow() {
  const list = bgJobs.value.filter((j) => j.target.value)
  if (!list.length) return null
  return (
    <>
      <h2 class="section-title">Актуальные загрузки</h2>
      <div class="jobs">
        {list.map((j) => {
          const err = j.error.value, done = j.done.value
          const ls = j.lessons.value
          const open = done && ls.length ? () => go({ k: 'reader', s: new ReaderSession(j.lang, ls[0]) }) : undefined
          return (
            <div key={j.id} class={'job' + (err ? ' failed' : done ? ' done' : '')}>
              <button class="job-main" disabled={!open} onClick={open}>
                <span class="job-title">{j.title}</span>
                <span class={err ? 'error-text small' : 'hint'}>{err ? `Ошибка: ${err}` : j.status.value}</span>
                {!err && !done && <span class="bar-progress"><span style={{ width: j.progress.value * 100 + '%' }} /></span>}
              </button>
              {done && !!ls.length && <Icon name="chevron" />}
              <IconButton icon="close" label={done || err ? 'Убрать' : 'Остановить'} size={20} onClick={() => closeJob(j)} />
            </div>
          )
        })}
      </div>
    </>
  )
}
