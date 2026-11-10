// "Загрузка в LingQ": text from the clipboard or a file (TXT, MD, EPUB, PDF), cleaning, upload to a course with the
// sentence split fixed right away; and the backups of lessons before a fix. Port of Android ImportScreen / BackupsScreen.
import { useEffect, useRef, useState } from 'preact/hooks'
import { back, courses, coursesError, go, LessonsScreen, loadCourses, say } from '../app'
import { prefs, useGemini } from '../settings'
import { Icon, type IconName } from '../icons'
import { Dialog, IconButton, ListItem, Loading, TopBar } from '../ui'
import type { ImportSession } from '../importer/session'
import { countWords } from '../importer/cleaner'
import { wordsText } from '../stats'
import { PdfPicker } from './PdfPicker'
import { AudioPicker } from './AudioPicker'
import { head, tail } from '../importer/fixer'
import { deleteAllBackups, KEEP_DAYS, listBackups, restoreBackup, type Backup } from '../importer/backups'

function SourceTile({ icon, label, onClick, disabled }: { icon: IconName; label: string; onClick: () => void; disabled?: boolean }) {
  return (
    <button class="source-tile" onClick={onClick} disabled={disabled}>
      <Icon name={icon} size={26} />
      <span>{label}</span>
    </button>
  )
}

const wordsLabel = wordsText

export function ImportScreen({ s }: { s: ImportSession }) {
  const file = useRef<HTMLInputElement>(null)
  const audioFile = useRef<HTMLInputElement>(null)
  useEffect(() => { void loadCourses() }, [])
  const list = courses.value
  useEffect(() => {
    if (!s.course.value && list?.length) s.course.value = list[0]
    if (list && !list.length) s.newCourse.value = true // no courses yet: offer to create one
  }, [list])
  const running = s.running.value
  const items = s.items.value
  const has = s.pasteMode.value || items.length > 0

  const pasteClipboard = async () => {
    try {
      const t = await navigator.clipboard.readText()
      if (!t.trim()) { s.typeIn(); say('Буфер пуст — вставьте текст в поле'); return }
      s.paste(t)
    } catch {
      // Safari may refuse: an empty field to paste into by hand
      s.typeIn()
      say('Вставьте текст в поле (долгое нажатие → Вставить)')
    }
  }

  const courseValue = s.newCourse.value ? 'new' : String(s.course.value?.id ?? '')
  const n = s.selectedCount()
  return (
    <div class="screen">
      <TopBar nav={<IconButton icon="back" label="Назад" onClick={back} />} title="Загрузка в LingQ" />
      <div class="screen-body scroll">
        <div class="content page import">
          {/* 1. where the text comes from */}
          <div class="source-tiles">
            <SourceTile icon="paste" label="Вставить" onClick={() => void pasteClipboard()} disabled={running} />
            <SourceTile icon="file" label="Файл" onClick={() => file.current?.click()} disabled={running} />
          </div>
          <input ref={file} type="file" hidden accept=".txt,.md,.markdown,.epub,.pdf,text/plain,text/markdown,application/epub+zip,application/pdf"
            onChange={(e) => { const f = (e.target as HTMLInputElement).files?.[0]; if (f) void s.loadFile(f); (e.target as HTMLInputElement).value = '' }} />
          <button class="source-tile wide-tile" onClick={() => audioFile.current?.click()} disabled={running || s.loading.value}>
            <Icon name="mic" size={26} />
            <span>Аудио → текст (подкаст, запись)</span>
          </button>
          <input ref={audioFile} type="file" hidden accept="audio/*,video/mp4,.mp3,.m4a,.ogg,.opus,.wav,.flac,.webm"
            onChange={(e) => { const f = (e.target as HTMLInputElement).files?.[0]; if (f) void s.pickAudio(f); (e.target as HTMLInputElement).value = '' }} />
          {!has && !s.loading.value && (
            <p class="hint">
              Текст из буфера или файл: TXT, MD, EPUB, PDF с текстом. Аудио: выберите кусок (например 10 минут), он
              распознаётся сервисом из настроек и загружается в урок вместе с текстом. Потом очистите текст от мусора и
              загрузите в LingQ.
            </p>
          )}
          {s.loading.value && <div class="row-status"><span class="spinner-sm" />{s.status.value || 'Загрузка…'}</div>}
          {s.pdfPick.value && <PdfPicker s={s} d={s.pdfPick.value} />}
          {s.audioPick.value && <AudioPicker s={s} pk={s.audioPick.value} />}

          {/* 2. the text */}
          {s.pasteMode.value ? (
            <>
              <label class="field">
                <span>Название урока</span>
                <input value={s.pasteTitle.value} onInput={(e) => { s.pasteTitle.value = (e.target as HTMLInputElement).value }} />
              </label>
              {s.audio.value && (
                <div class="audio-row">
                  <Icon name="headphones" size={20} />
                  <span class="grow">{s.audioName.value} — загрузится в урок, у предложений будет время</span>
                  <button class="btn text" onClick={() => s.dropAudio()}>Без аудио</button>
                </div>
              )}
              <label class="field">
                <span>Текст</span>
                <textarea class="import-text" value={s.pasteText.value} onInput={(e) => { s.pasteText.value = (e.target as HTMLTextAreaElement).value }} />
              </label>
              <div class="hint">{wordsLabel(countWords(s.pasteText.value))}</div>
            </>
          ) : items.length > 0 && (
            <>
              <h3 class="section-title">{s.sourceName.value}</h3>
              {items.length > 1 && (
                <div class="row">
                  <button class="btn text" onClick={() => items.forEach((i) => { i.selected.value = true })}>Выбрать все</button>
                  <button class="btn text" onClick={() => items.forEach((i) => { i.selected.value = false })}>Снять все</button>
                </div>
              )}
              {items.map((it, i) => (
                <div key={i} class="import-item">
                  {items.length > 1 && <input type="checkbox" checked={it.selected.value} onChange={(e) => { it.selected.value = (e.target as HTMLInputElement).checked }} />}
                  <div class="grow">
                    <input class="item-title" value={it.title.value} onInput={(e) => { it.title.value = (e.target as HTMLInputElement).value }} />
                    <div class="hint">{wordsLabel(countWords(it.text.value))}</div>
                  </div>
                </div>
              ))}
            </>
          )}

          {/* 3. cleaning: the main step */}
          {has && (
            <div class="clean-card">
              <div class="clean-head"><Icon name="clean" /><span>Очистка текста</span></div>
              <p>Убирает номера страниц, колонтитулы, сноски, ссылки и мусор с сайтов, склеивает переносы и разорванные строки. Сам текст не переписывается.</p>
              <div class="row">
                <button class="btn tonal" onClick={() => s.cleanQuick()} disabled={s.cleaning.value || running}><Icon name="clean" size={18} />Быстро</button>
                <button class="btn filled" onClick={() => void s.cleanAi()} disabled={s.cleaning.value || running || !useGemini()}><Icon name="magic" size={18} />С ИИ</button>
              </div>
              {!useGemini() && <p class="small">Чистка с ИИ: включите Gemini или другой ИИ в Настройки → Перевод.</p>}
              {s.cleaning.value && <div class="bar-progress"><div style={{ width: s.cleanProgress.value * 100 + '%' }} /></div>}
              {s.cleanNote.value && <p>{s.cleanNote.value}</p>}
              {s.canUndo.value && !s.cleaning.value && <button class="btn text" onClick={() => s.undoClean()}>Вернуть как было</button>}
              <label class="switch-row">
                <input type="checkbox" class="switch" checked={prefs.autoClean.value === '1'}
                  onChange={(e) => { prefs.autoClean.value = (e.target as HTMLInputElement).checked ? '1' : '0' }} />
                <span>Быстро чистить сразу при вставке</span>
              </label>
            </div>
          )}

          {/* 4. course and upload */}
          {has && (
            <>
              <hr />
              <label class="field">
                <span>Курс в LingQ (язык: {s.lang})</span>
                <select value={courseValue} disabled={running} onChange={(e) => {
                  const v = (e.target as HTMLSelectElement).value
                  if (v === 'new') {
                    s.newCourse.value = true
                    if (!s.newCourseTitle.value.trim()) s.newCourseTitle.value = s.pasteMode.value ? s.pasteTitle.value : s.sourceName.value
                  } else {
                    s.newCourse.value = false
                    s.course.value = (list ?? []).find((c) => String(c.id) === v) ?? null
                  }
                }}>
                  {!list && <option value="">Загрузка курсов…</option>}
                  <option value="new">➕ Новый курс…</option>
                  {(list ?? []).map((c) => <option key={c.id} value={String(c.id)}>{c.title || `Курс ${c.id}`}</option>)}
                </select>
              </label>
              {s.newCourse.value && (
                <label class="field">
                  <span>Название нового курса</span>
                  <input value={s.newCourseTitle.value} disabled={running} onInput={(e) => { s.newCourseTitle.value = (e.target as HTMLInputElement).value }} />
                </label>
              )}
              {coursesError.value && <p class="error-text">Курсы не загрузились: {coursesError.value}</p>}
              <label class="check-row">
                <input type="checkbox" checked={s.autoFix.value} onChange={(e) => { s.autoFix.value = (e.target as HTMLInputElement).checked }} />
                <span>Сразу исправить разбивку на предложения</span>
              </label>
              {useGemini() && (
                <label class="check-row">
                  <input type="checkbox" checked={s.aiLevel.value} onChange={(e) => {
                    s.aiLevel.value = (e.target as HTMLInputElement).checked
                    try { localStorage.setItem('import.aiLevel', s.aiLevel.value ? '1' : '0') } catch { /* ignore */ }
                  }} />
                  <span>ИИ определит уровень сложности (A1–C2)</span>
                </label>
              )}
              {!(s.pasteMode.value && s.audio.value) && <div class="row split-row">
                <span class="grow">Делить длинные тексты по</span>
                <input class="num" inputMode="numeric" value={s.splitWords.value}
                  onInput={(e) => { s.splitWords.value = (e.target as HTMLInputElement).value.replace(/\D/g, '') }} />
                <span>слов</span>
              </div>}
              <button class="btn filled wide" onClick={() => void s.run()}
                disabled={running || n === 0 || (s.newCourse.value ? !s.newCourseTitle.value.trim() : !s.course.value)}>
                {n === 1 ? 'Загрузить урок' : `Загрузить уроков: ${n}`}
              </button>
            </>
          )}

          {(running || s.log.value.length > 0) && (
            <>
              <div class="bar-progress"><div style={{ width: s.progress.value * 100 + '%' }} /></div>
              {s.log.value.map((l, i) => <div key={i} class="log-line">{l}</div>)}
            </>
          )}
          {s.error.value && <p class="error-text">Ошибка: {s.error.value}</p>}
          {s.finished.value && (
            <>
              <hr />
              <p>
                {!s.fixes.value.length
                  ? 'Готово ✅ Разбивка проверена сразу при загрузке, исправлять было нечего.'
                  : 'Готово ✅ Разбивка исправлена сразу при загрузке. Ниже — что склеено (‖ — место склейки). Копии до правки — в «Резервных копиях» (Настройки).'}
              </p>
              {s.fixes.value.map((f) => (
                <div key={f.id}>
                  <h3 class="section-title">{f.title}</h3>
                  {f.changes.map((c, i) => (
                    <div key={i} class="change-line">
                      {c.kind === 'merge' ? <>{tail(c.a)} <b>‖</b> {head(c.b ?? '')}</>
                        : c.kind === 'dup' ? <>убран повтор: {head(c.a, 80)}</>
                        : <>исправлены символы: {head(c.a, 80)}</>}
                    </div>
                  ))}
                </div>
              ))}
              {s.course.value && (
                <button class="btn outlined" onClick={() => go(new LessonsScreen(s.lang, s.course.value!))}>Открыть курс</button>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  )
}

/** Copies of lessons before a fix; a tap restores one. */
export function BackupsScreen() {
  const [list, setList] = useState<Backup[] | null>(null)
  const [ask, setAsk] = useState<Backup | null>(null)
  const [askAll, setAskAll] = useState(false)
  const reload = () => listBackups().then(setList, (e) => { say(String(e)); setList([]) })
  useEffect(() => { void reload() }, [])
  const fmt = (t: number) => new Date(t).toLocaleString('ru-RU', { dateStyle: 'short', timeStyle: 'short' })
  return (
    <div class="screen">
      <TopBar nav={<IconButton icon="back" label="Назад" onClick={back} />} title="Резервные копии" />
      <div class="screen-body scroll">
        <div class="content">
          {!list ? <Loading /> : !list.length ? (
            <p class="empty">Копий нет. Они появляются, когда приложение меняет разбивку урока, и хранятся {KEEP_DAYS} дня.</p>
          ) : (
            <>
              <div class="row backups-head">
                <span class="grow hint">Копии хранятся {KEEP_DAYS} дня в этом браузере</span>
                <button class="btn outlined" onClick={() => setAskAll(true)}>🗑 Удалить все</button>
              </div>
              {list.map((b) => (
                <ListItem key={b.key} class="divided" headline={b.title || `Урок ${b.id}`}
                  supporting={`${fmt(b.time)} · ${b.lang} · id ${b.id}`} onClick={() => setAsk(b)} />
              ))}
            </>
          )}
        </div>
      </div>
      {ask && (
        <Dialog title="Вернуть урок?" onClose={() => setAsk(null)} actions={
          <>
            <button class="btn text" onClick={() => setAsk(null)}>Отмена</button>
            <button class="btn text" onClick={() => {
              const b = ask
              setAsk(null)
              restoreBackup(b).then(() => say('Урок возвращён'), (e) => say(`Ошибка: ${(e as Error).message}`))
            }}>Вернуть</button>
          </>
        }>
          <p>«{ask.title}» вернётся к версии от {fmt(ask.time)}.</p>
        </Dialog>
      )}
      {askAll && (
        <Dialog title="Удалить все копии?" onClose={() => setAskAll(false)} actions={
          <>
            <button class="btn text" onClick={() => setAskAll(false)}>Отмена</button>
            <button class="btn text" onClick={() => { setAskAll(false); void deleteAllBackups().then(reload).then(() => say('Копии удалены')) }}>Удалить</button>
          </>
        }>
          <p>Вернуть уроки к прежней разбивке после этого будет нельзя.</p>
        </Dialog>
      )}
    </div>
  )
}
