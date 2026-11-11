import { render } from 'preact'
import { stack, toast, type Screen } from './app'
import { CoursesScreen, LessonsView } from './screens/Library'
import { ReaderScreen } from './reader/Reader'
import { ChatScreen } from './screens/Chat'
import { BookmarksScreen } from './screens/Bookmarks'
import { SettingsLingq, SettingsLog, SettingsPrompts, SettingsReading, SettingsRoot, SettingsSpeech, SettingsSync, SettingsTranslation } from './screens/Settings'
import { autoSync } from './sync'
import { BackupsScreen, ImportScreen } from './screens/Import'
import { StatsScreen } from './screens/Stats'
import { ModelSwitchDialog } from './screens/ModelSwitch'
import { PlayerScreen } from './screens/Player'
import { useRef } from 'preact/hooks'
import './styles.css'
import { log, logError } from './applog'
import { PodcastFeedScreen, PodcastsScreen } from './screens/Podcasts'
import { ArchiveScreen } from './screens/CourseEdit'

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
    case 'settingsSpeech': return <SettingsSpeech />
    case 'podcasts': return <PodcastsScreen s={screen} />
    case 'podcastFeed': return <PodcastFeedScreen s={screen.s} />
    case 'settingsPrompts': return <SettingsPrompts />
    case 'settingsReading': return <SettingsReading />
    case 'settingsSync': return <SettingsSync />
    case 'log': return <SettingsLog />
    case 'player': return <PlayerScreen />
    case 'import': return <ImportScreen s={screen} />
    case 'backups': return <BackupsScreen />
    case 'stats': return <StatsScreen />
    case 'archive': return <ArchiveScreen />
  }
}

const ids = new WeakMap<Screen, number>()
let nextId = 1
const idOf = (s: Screen) => { let i = ids.get(s); if (!i) ids.set(s, (i = nextId++)); return i }

function App() {
  const list = stack.value
  const t = toast.value
  // transitions: deeper = slide from the right, back = from the left, another tab = cross-fade
  const prev = useRef<{ n: number; top: Screen | null; anim: string }>({ n: list.length, top: list[0], anim: '' })
  const top = list[list.length - 1]
  if (prev.current.top !== top) {
    prev.current = {
      n: list.length, top,
      anim: list.length > prev.current.n ? ' push' : list.length < prev.current.n ? ' pop' : ' fade',
    }
  }
  const anim = prev.current.anim
  // Screens below the top stay mounted (hidden), so the reader keeps its scroll position under a chat.
  return (
    <>
      {list.map((s, i) => (
        <div key={idOf(s)} class={'layer' + (i === list.length - 1 ? anim : ' below')} aria-hidden={i !== list.length - 1}>
          {view(s)}
        </div>
      ))}
      <ModelSwitchDialog />
      {t && <div class={'snackbar' + (top.k === 'player' ? ' high' : '')} key={t.id}>{t.text}</div>}
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
