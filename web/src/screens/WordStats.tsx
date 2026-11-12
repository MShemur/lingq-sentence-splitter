// "Слова по урокам": new words per lesson of a course (bars) and unique words so far (line). Port of Android WordStats.kt.
import { useLayoutEffect, useRef, useState } from 'preact/hooks'
import { back, go, loadCourses, visibleCourses } from '../app'
import { lang } from '../settings'
import { IconButton, Spinner, TopBar } from '../ui'
import { loadResult, WordStatsSession, type WsResult } from '../wordstats'

const num = (n: number) => n.toLocaleString('ru-RU')

export function WordStatsScreen({ ws }: { ws: WordStatsSession }) {
  const r = ws.result.value
  const [picked, setPicked] = useState(0)
  const p = r ? Math.min(picked, r.chapters.length - 1) : 0
  return (
    <div class="screen">
      <TopBar nav={<IconButton icon="back" label="Назад" onClick={back} />} title="Слова по урокам" />
      <div class="screen-body scroll">
        <div class="content page wstats">
          <h2 class="section-title">{ws.course.title || `Курс ${ws.course.id}`}</h2>
          {ws.running.value && (
            <>
              <p class="hint">{ws.progress.value}</p>
              <div class="ws-prog"><div style={{ width: `${Math.round(ws.fraction.value * 100)}%` }} /></div>
            </>
          )}
          {ws.error.value && <p class="error-text">Ошибка: {ws.error.value}</p>}
          {!r ? (!ws.running.value && (
            <>
              <p class="hint">
                Приложение загрузит тексты всех уроков курса и посчитает, сколько новых слов в каждом уроке (слова, которых не
                было в предыдущих), и сколько уникальных слов всего. Формы слова считаются как одно: bin, ist, war → sein;
                Häuser → Haus. Считается на устройстве, без ИИ.
              </p>
              <button class="btn filled" onClick={() => void ws.start()}>Посчитать</button>
            </>
          )) : (
            <>
              <Summary r={r} />
              <Chart title="Новые слова по урокам" note={`макс. ${num(Math.max(1, ...r.chapters.map((c) => c.new)))}`} r={r} picked={p} pick={setPicked} kind="bars" />
              <Details r={r} i={p} />
              <Chart title="Всего уникальных слов" note={`после урока ${p + 1}: ${num(r.chapters[p].total)} из ${num(r.unique)}`} r={r} picked={p} pick={setPicked} kind="line" />
              <button class="btn outlined" disabled={ws.running.value} onClick={() => void ws.start()}>Пересчитать</button>
              <p class="hint small">
                Урок курса = глава. Новое слово — начальная форма, которой не было в предыдущих уроках.{' '}
                {r.lemmatized
                  ? 'Слова, которых нет в словаре (имена, редкие слова), считаются как написаны. Словарь форм: lemmatization-lists, Michal Měchura, Open Database License.'
                  : 'Для этого языка словаря форм нет: каждая форма считается отдельным словом.'}
              </p>
            </>
          )}
        </div>
      </div>
    </div>
  )
}

function Summary({ r }: { r: WsResult }) {
  return (
    <div class="ws-summary">
      <div class="stat-label">Уникальных слов</div>
      <div class="stat-big">{num(r.unique)}</div>
      <div>всего слов в тексте {num(r.words)} · уроков {r.chapters.length}</div>
      {r.chapters[0] && <div>в первом уроке {num(r.chapters[0].new)} новых</div>}
      {r.lemmatized && r.unknown > 0 && <div class="stat-small">не найдено в словаре: {num(r.unknown)} (имена, редкие слова)</div>}
      <div class="stat-small ws-when">посчитано {new Date(r.t).toLocaleString('ru-RU', { day: 'numeric', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' })}</div>
    </div>
  )
}

/** One chapter per slot (at least 14 px; the chart scrolls sideways when there are many); tap a slot to pick it. */
function Chart({ title, note, r, picked, pick, kind }: {
  title: string; note: string; r: WsResult; picked: number; pick: (i: number) => void; kind: 'bars' | 'line'
}) {
  const box = useRef<HTMLDivElement>(null)
  const [avail, setAvail] = useState(320)
  useLayoutEffect(() => { if (box.current) setAvail(box.current.clientWidth) }, [])
  const n = r.chapters.length
  const sw = Math.max(avail / n, 14)
  const width = sw * n
  const H = kind === 'bars' ? 160 : 130
  const labelH = kind === 'bars' ? 16 : 0
  const h = H - labelH
  const every = Math.max(1, Math.floor(40 / sw))
  const onClick = (e: MouseEvent) => {
    const x = e.clientX - (e.currentTarget as SVGElement).getBoundingClientRect().left
    pick(Math.min(n - 1, Math.max(0, Math.floor(x / sw))))
  }
  let body
  if (kind === 'bars') {
    const max = Math.max(1, ...r.chapters.map((c) => c.new))
    const w = Math.min(sw * 0.7, 28)
    body = r.chapters.map((c, i) => {
      const bh = c.new <= 0 ? 0 : Math.max((h * c.new) / max, 2)
      const left = sw * i + (sw - w) / 2
      return (
        <g key={i}>
          {bh > 0 && <path d={`M${left},${h} V${h - bh + 4} q0,-4 4,-4 H${left + w - 4} q4,0 4,4 V${h} Z`} class="ws-bar" opacity={i === picked ? 1 : 0.45} />}
          {(i % every === 0 || i === picked) && (
            <text x={sw * i + sw / 2} y={h + 13} text-anchor="middle" class={'ws-lbl' + (i === picked ? ' on' : '')}>{i + 1}</text>
          )}
        </g>
      )
    })
  } else {
    const max = Math.max(1, r.unique)
    const top = 6
    const hh = H - top
    const pt = (i: number) => [sw * i + sw / 2, top + hh - (hh * r.chapters[i].total) / max]
    const line = r.chapters.map((_, i) => `${i ? 'L' : 'M'}${pt(i).join(',')}`).join(' ')
    const [px, py] = pt(picked)
    body = (
      <>
        <path d={`${line} L${pt(n - 1)[0]},${H} L${pt(0)[0]},${H} Z`} class="ws-area" />
        <path d={line} class="ws-line" />
        <circle cx={px} cy={py} r={6} class="ws-dot-bg" />
        <circle cx={px} cy={py} r={4} class="ws-dot" />
      </>
    )
  }
  return (
    <div class="stat-chart">
      <div class="stat-chart-title">{title}</div>
      <div class="stat-small">{note}</div>
      <div class="ws-scroll" ref={box}>
        <svg width={width} height={H} onClick={onClick}>
          {body}
          <rect x={0} y={h - 1} width={width} height={1} class="ws-base" />
        </svg>
      </div>
    </div>
  )
}

function Details({ r, i }: { r: WsResult; i: number }) {
  const c = r.chapters[i]
  const share = c.words > 0 ? Math.floor((c.new * 100) / c.words) : 0
  return (
    <div class="ws-details">
      <div class="ws-title">Урок {i + 1}. {c.title}</div>
      <div class="stat-small">
        новых слов {num(c.new)} · слов в тексте {num(c.words)} · новых на 100 слов: {share} · всего уникальных к этому уроку {num(c.total)}
      </div>
      {!!c.top.length && (
        <>
          <div class="ws-sub">Самые частые новые:</div>
          <div class="chips">{c.top.map((w) => <span key={w} class="chip">{w}</span>)}</div>
        </>
      )}
    </div>
  )
}

/** Library ⋮ → "Слова по урокам курса…": choose the course. */
export function WordStatsPicker() {
  const l = lang()
  const list = visibleCourses.value
  if (!list) void loadCourses()
  return (
    <div class="screen">
      <TopBar nav={<IconButton icon="back" label="Назад" onClick={back} />} title="Слова по урокам" />
      <div class="screen-body scroll">
        <div class="content page">
          <p class="hint">Выберите курс: для каждого урока посчитается, сколько в нём новых слов, и сколько уникальных слов во всём курсе.</p>
          {!list ? <Spinner size={24} /> : list.map((c) => {
            const done = loadResult(l, c.id)
            return (
              <button key={c.id} class="ws-course" onClick={() => go({ k: 'wordStats', ws: new WordStatsSession(l, c) })}>
                <span>{c.title || `Курс ${c.id}`}</span>
                {done && <span class="ws-done">{num(done.unique)} уникальных слов · {done.chapters.length} уроков</span>}
              </button>
            )
          })}
        </div>
      </div>
    </div>
  )
}
