import { render } from 'preact'
import { stack, toast, type Screen } from './app'
import { CoursesScreen, LessonsView } from './screens/Library'
import { ReaderScreen } from './reader/Reader'
import { ChatScreen } from './screens/Chat'
import { BookmarksScreen } from './screens/Bookmarks'
import { SettingsLingq, SettingsLog, SettingsPrompts, SettingsReading, SettingsRoot, SettingsSync, SettingsTranslation } from './screens/Settings'
import { autoSync } from './sync'
import { BackupsScreen, ImportScreen } from './screens/Import'
import { StatsScreen } from './screens/Stats'
import { ModelSwitchDialog } from './screens/ModelSwitch'
import './styles.css'
import { log, logError } from './applog'

function view(screen: Screen) {
  switch (screen.k) {
    case 'courses': return <CoursesScreen />
    case 'lessons': return <LessonsView s={screen} />
    case 'reader': return <ReaderScreen s={screen.s} />
    case 'chat': return <ChatScreen c={screen.c} />
    case 'bookmarks': return <BookmarksScreen />
    case 'settings': return <SettingsRoot />
    case 'settingsLingq': return <SettingsLingq />
    case 'settingsTranslation': return <SettingsTranslation />
    case 'settingsPrompts': return <SettingsPrompts />
    case 'settingsReading': return <SettingsReading />
    case 'settingsSync': return <SettingsSync />
    case 'log': return <SettingsLog />
    case 'import': return <ImportScreen s={screen} />
    case 'backups': return <BackupsScreen />
    case 'stats': return <StatsScreen />
  }
}

const ids = new WeakMap<Screen, number>()
let nextId = 1
const idOf = (s: Screen) => { let i = ids.get(s); if (!i) ids.set(s, (i = nextId++)); return i }

function App() {
  const list = stack.value
  const t = toast.value
  // Screens below the top stay mounted (hidden), so the reader keeps its scroll position under a chat.
  return (
    <>
      {list.map((s, i) => (
        <div key={idOf(s)} class={'layer' + (i === list.length - 1 ? '' : ' below')} aria-hidden={i !== list.length - 1}>
          {view(s)}
        </div>
      ))}
      <ModelSwitchDialog />
      {t && <div class="snackbar" key={t.id}>{t.text}</div>}
    </>
  )
}

render(<App />, document.getElementById('app')!)

// sync with the phone: on start and when the app is left (at most once a minute)
declare const __VERSION__: string
log('app', `Запуск web ${__VERSION__}`)
window.addEventListener('error', (e) => logError('crash', `${e.filename?.split('/').pop() ?? ''}:${e.lineno}`, e.error ?? e.message))
window.addEventListener('unhandledrejection', (e) => logError('crash', 'Promise', e.reason))
autoSync()
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') autoSync() })

// Offline shell + auto-update: the service worker always asks the network for the page first.
if ('serviceWorker' in navigator && import.meta.env.PROD) {
  window.addEventListener('load', () => { navigator.serviceWorker.register('/sw.js').catch(() => {}) })
}
