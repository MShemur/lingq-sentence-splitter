// Statistics: today, this week / month / year, streak, minutes per day for two weeks. Port of Android StatsScreen.
import { useState } from 'preact/hooks'
import { back } from '../app'
import { IconButton, TopBar } from '../ui'
import { dayOf, formatTime, plural, since, stats, streak, wordsText } from '../stats'
import { MiniPlayer } from './Player'

const daysText = (n: number) => `${n} ${plural(n, 'день', 'дня', 'дней')}`

export function StatsScreen() {
  void stats.value // re-render on change
  const now = new Date()
  const today = dayOf(now)
  const monday = new Date(now); monday.setDate(now.getDate() - ((now.getDay() + 6) % 7))
  const week = since(monday)
  const month = since(new Date(now.getFullYear(), now.getMonth(), 1))
  const year = since(new Date(now.getFullYear(), 0, 1))
  const st = streak()
  return (
    <div class="screen">
      <TopBar nav={<IconButton icon="back" label="Назад" onClick={back} />} title="Статистика" />
      <div class="screen-body scroll">
        <div class="content page stats">
          <div class="stat-today">
            <div class="stat-label">Сегодня</div>
            <div class="stat-big">{formatTime(today[0])}</div>
            <div class="stat-words">{wordsText(today[1])}</div>
            <div class="stat-streak">{st > 0 ? `Серия: ${daysText(st)} подряд` : 'Почитайте хотя бы минуту — начнётся серия дней'}</div>
          </div>
          <div class="stat-periods">
            <Period title="Неделя" v={week} />
            <Period title="Месяц" v={month} />
            <Period title="Год" v={year} />
          </div>
          <DaysChart now={now} />
          <p class="hint">
            Время считается, пока открыт урок и вы касаетесь экрана; если 3 минуты ничего не происходит, счёт стоит.
            Слова — новые предложения, которые вы впервые увидели в уроке.
          </p>
        </div>
      </div>
    </div>
  )
}

function Period({ title, v }: { title: string; v: [number, number] }) {
  return (
    <div class="stat-period">
      <div class="stat-label">{title}</div>
      <div class="stat-time">{formatTime(v[0])}</div>
      <div class="stat-small">{wordsText(v[1])}</div>
    </div>
  )
}

/** Minutes per day, last 14 days: thin bars rounded at the top on a baseline; tap a day for its numbers. */
function DaysChart({ now }: { now: Date }) {
  const dates = Array.from({ length: 14 }, (_, i) => { const d = new Date(now); d.setDate(now.getDate() - (13 - i)); return d })
  const mins = dates.map((d) => dayOf(d)[0] / 60)
  const max = Math.max(10, ...mins)
  const [picked, setPicked] = useState(dates.length - 1)
  const d = dates[picked]
  const dv = dayOf(d)
  return (
    <div class="stat-chart">
      <div class="stat-chart-title">Минуты чтения за 2 недели</div>
      <div class="stat-small">
        {d.toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' })}: {formatTime(dv[0])} · {wordsText(dv[1])}
      </div>
      <div class="bars">
        {mins.map((m, i) => (
          <button key={i} class={'bar-slot' + (i === picked ? ' picked' : '')} onClick={() => setPicked(i)}
            aria-label={`${dates[i].getDate()}: ${Math.round(m)} мин`}>
            {m > 0 && <span class="bar" style={{ height: `max(3px, ${(m / max) * 100}%)` }} />}
          </button>
        ))}
      </div>
      <div class="bar-labels">
        {dates.map((x, i) => <span key={i}>{i % 2 === 1 || i === dates.length - 1 ? x.getDate() : ''}</span>)}
      </div>
      <MiniPlayer />
    </div>
  )
}
