// "Limit reached" window: explains it and lets the user pick another model; the failed request is repeated.
// Port of Android ModelSwitch.kt.
import { useState } from 'preact/hooks'
import { errorText, modelSwitch, say } from '../app'
import * as gemini from '../gemini'
import { prefs } from '../settings'
import { Dialog, Loading } from '../ui'

const NOT_TEXT = /image|tts|embed|live|audio|veo|imagen/i

export function ModelSwitchDialog() {
  const sw = modelSwitch.value
  const [models, setModels] = useState<string[] | null>(null)
  const [picking, setPicking] = useState(false)
  if (!sw) return null
  const close = () => { modelSwitch.value = null; setPicking(false); setModels(null) }
  const pick = async () => {
    setPicking(true)
    try {
      setModels((await gemini.models(prefs.geminiKey.value)).filter((m) => m !== sw.model && !NOT_TEXT.test(m)))
    } catch (e) {
      close()
      say(errorText(e) || 'Не удалось получить список моделей')
    }
  }
  const choose = (m: string) => {
    sw.apply(m)
    close()
    say(`Модель: ${m}`)
    sw.retry()
  }
  return (
    <Dialog
      title={picking ? 'Другая модель Gemini' : 'Gemini: лимит исчерпан'}
      onClose={close}
      actions={
        <>
          <button class="btn text" onClick={close}>Отмена</button>
          {!picking && <button class="btn text" onClick={() => void pick()}>Попробовать другую модель</button>}
        </>
      }
    >
      {!picking ? (
        <p>
          Модель {sw.model} сейчас не отвечает: закончился лимит запросов или она перегружена. Выберите другую
          модель — запрос повторится с ней.
          <br /><br />
          <span class="hint">{sw.message.slice(0, 240)}</span>
        </p>
      ) : !models ? <Loading /> : (
        <div class="dialog-list">
          {models.map((m) => (
            <label key={m} class="radio-row">
              <input type="radio" checked={false} onChange={() => choose(m)} />
              <span>{m}{m.includes('flash') && <span class="hint"> &nbsp;быстрая</span>}</span>
            </label>
          ))}
        </div>
      )}
    </Dialog>
  )
}
