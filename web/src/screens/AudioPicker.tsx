// "Какую часть распознать?": start and length (10 min by default) or the whole file. Port of Android AudioPartPicker.
import { useState } from 'preact/hooks'
import type { AudioPick, ImportSession } from '../importer/session'
import { clock, parseClock } from '../importer/audio'
import { sttCost, sttModel, sttProvider } from '../importer/stt'
import { prefs } from '../settings'
import { Chip, Sheet } from '../ui'

export function AudioPicker({ s, pk }: { s: ImportSession; pk: AudioPick }) {
  const [start, setStart] = useState('0:00')
  const [len, setLen] = useState<number | null>(10) // minutes; null = to the end
  const [useTr, setUseTr] = useState(!!pk.transcript)
  const from = parseClock(start)
  const dur = pk.durationMs
  const to = len != null ? (dur > 0 ? Math.min((from ?? 0) + len * 60_000, dur) : (from ?? 0) + len * 60_000) : dur > 0 ? dur : null
  const bad = from == null || (dur > 0 && from >= dur)
  const prov = sttProvider(prefs.sttProvider.value)
  const price = (ms: number | null) => (ms && ms > 0 ? sttCost(prov, sttModel(prov.id), ms / 60_000) : null)
  const partMs = to != null ? to - (from ?? 0) : dur > 0 ? dur - (from ?? 0) : null
  const cost = useTr ? 'бесплатно, готовая расшифровка' : price(partMs) ? `${prov.name}: ${price(partMs)}` : prov.name
  const wholeCost = useTr ? 'бесплатно' : price(dur)
  return (
    <Sheet onClose={() => { s.audioPick.value = null }}>
      <div class="sheet-title">Какую часть распознать?</div>
      <div class="audio-pick">
        <p class="hint">{pk.name}{dur > 0 ? ` · ${clock(dur)}` : ''}</p>
        <label class="field">
          <span>Начало, мин:сек</span>
          <input value={start} inputMode="numeric" onInput={(e) => setStart((e.target as HTMLInputElement).value)} />
        </label>
        {bad && <div class="error-text">{from == null ? 'Например 12:30 или 1:05:00' : 'Это дальше конца записи'}</div>}
        {pk.transcript && (
          <label class="switch-row">
            <input type="checkbox" class="switch" checked={useTr} onChange={(e) => setUseTr((e.target as HTMLInputElement).checked)} />
            <span>Готовая расшифровка подкаста<br /><span class="hint">{useTr ? 'Бесплатно, без распознавания' : 'Выключено: распознает сервис из настроек'}</span></span>
          </label>
        )}
        <h3 class="section-title">Сколько взять</h3>
        <div class="chips">
          {[5, 10, 15, 20, 30].map((m) => <Chip key={m} label={`${m} мин`} selected={len === m} onClick={() => setLen(m)} />)}
          <Chip label="До конца" selected={len == null} onClick={() => setLen(null)} />
        </div>
        {!bad && (
          <p class="hint">
            Будет распознан кусок {clock(from ?? 0)}–{to != null ? clock(to) : 'конец'}
            {to != null ? ` (${Math.max(1, Math.floor((to - (from ?? 0)) / 60_000))} мин)` : ''}. Он же загрузится в урок как аудио.
          </p>
        )}
        {!bad && <div class="audio-cost">{cost}</div>}
        <button class="btn filled wide" disabled={bad} onClick={() => void s.transcribePart(from ?? 0, len == null ? null : to, useTr)}>{useTr ? 'Взять кусок' : 'Распознать'}</button>
        <button class="btn text wide" onClick={() => void s.transcribePart(0, null, useTr)}>Весь файл{dur > 0 ? ` (${clock(dur)}${wholeCost ? `, ${wholeCost}` : ''})` : ''}</button>
      </div>
    </Sheet>
  )
}
