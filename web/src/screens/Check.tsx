// Report of the split checker: what would be merged per lesson, "Применить" writes it to LingQ (backup first).
import { useState } from 'preact/hooks'
import { back } from '../app'
import { Dialog, IconButton, TopBar } from '../ui'
import { head, tail, type Change } from '../importer/fixer'
import type { CheckSession } from '../check'
import { useGemini } from '../settings'

function ChangeRow({ c }: { c: Change }) {
  return (
    <div class="chk-change">
      {c.kind === 'merge' ? (
        <><span>{tail(c.a)}</span><b class="chk-join">‖</b><span>{head(c.b ?? '')}</span></>
      ) : c.kind === 'dup' ? <span>убран повтор: {head(c.a, 80)}</span> : <span>исправлены символы: {head(c.a, 80)}</span>}
    </div>
  )
}

export function CheckScreen({ s }: { s: CheckSession }) {
  const [confirm, setConfirm] = useState(false)
  const running = s.running.value, applying = s.applying.value, done = s.done.value
  const results = s.results.value, saved = s.savedIds.value
  const err = s.error.value
  const n = s.changeCount()
  const busy = running || applying
  const p = running ? s.progress.value : saved.length / Math.max(1, results.length)
  return (
    <div class="screen">
      <TopBar nav={<IconButton icon="back" label="Закрыть" onClick={back} />} title={s.label.value || 'Исправить разбивку'} />
      <div class="screen-body scroll">
        <div class="content page chk">
          <label class="check-row">
            <input type="checkbox" checked={s.useAi.value} disabled={busy} onChange={(e) => {
              const el = e.target as HTMLInputElement
              s.setAi(el.checked)
              el.checked = s.useAi.value // no key: the box stays off
            }} />
            <span>Проверить с ИИ</span>
          </label>
          {!useGemini() && <p class="hint">ИИ не включён: Настройки → Перевод и ИИ.</p>}
          {busy && (
            <>
              <div class="bar-progress"><div style={{ width: p * 100 + '%' }} /></div>
              <div class="hint">{s.status.value}</div>
            </>
          )}
          {err && <p class="error-text">Ошибка: {err}</p>}
          <h3 class="section-title chk-sum">
            Проверено уроков: {s.checked.value} из {s.total} · с правками: {results.length} · правок: {n}
          </h3>
          {results.length > 0 && !done && (
            <p class="hint">‖ — место склейки: слева конец одного куска, справа начало следующего. Проверьте, что это части одного предложения.</p>
          )}
          {!running && !err && results.length === 0 && <p class="chk-ok">Исправлять нечего ✅</p>}
          {results.map((r) => (
            <div key={r.lesson.id} class="chk-lesson">
              <div class="chk-head">
                <span class="grow">{r.lesson.title || `Урок ${r.lesson.id}`}</span>
                <span>{saved.includes(r.lesson.id) ? '✅' : r.changes.length}</span>
              </div>
              {r.aiMerges > 0 && <div class="hint">из них найдено ИИ: {r.aiMerges}</div>}
              {r.changes.map((c, i) => <ChangeRow key={i} c={c} />)}
            </div>
          ))}
          {!running && results.length > 0 && (
            done ? <p>Записано ✅ Копии уроков до правки — в «Резервных копиях».</p> : (
              <button class="btn filled wide" disabled={applying} onClick={() => setConfirm(true)}>
                Применить: {n} правок в {results.length} уроках
              </button>
            )
          )}
          {done && <button class="btn outlined wide" onClick={back}>Закрыть</button>}
        </div>
      </div>
      {confirm && (
        <Dialog title="Записать в LingQ?" onClose={() => setConfirm(false)} actions={
          <>
            <button class="btn text" onClick={() => setConfirm(false)}>Отмена</button>
            <button class="btn text" onClick={() => { setConfirm(false); void s.apply() }}>Записать</button>
          </>
        }>
          <p>Будет изменено уроков: {results.length}. Перед записью сохраняется копия каждого урока.</p>
        </Dialog>
      )}
    </div>
  )
}
