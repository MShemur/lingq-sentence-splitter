// Bookmarks: all of them grouped by lesson, and the open lesson's in a dialog. Port of Bookmarks.kt.
import { useMemo, useState } from 'preact/hooks'
import { Icon } from '../icons'
import { Press } from '../press'
import { back, errorText, go, say } from '../app'
import {
  bookmarks, bookmarksExpanded, lessonBookmarks, removeBookmark, setBookmarkTranslation, type Bookmark,
} from '../bookmarks'
import * as api from '../lingq'
import * as gemini from '../gemini'
import { prompt, trLang, useGemini } from '../settings'
import { Dialog, IconButton, LinearProgress, Menu, MenuItem, TopBar } from '../ui'
import { openBookmark, type ReaderSession } from '../reader/session'

const translateBookmark = (b: Bookmark) =>
  useGemini() ? gemini.sentence(prompt.sentence(), b.lang, trLang(), b.text) : api.translate(b.lang, trLang(), b.text)

/** Top-bar button: full sentences / two lines. */
export function ExpandButton() {
  const on = bookmarksExpanded.value
  return (
    <IconButton
      icon={on ? 'unfoldLess' : 'unfoldMore'} label={on ? 'Свернуть предложения' : 'Развернуть предложения'}
      onClick={() => { bookmarksExpanded.value = !on }}
    />
  )
}

/** Tap: show/hide the translation; long press: menu with "go to source" and "delete". */
function BookmarkRow({ b, onOpen }: { b: Bookmark; onOpen: () => void }) {
  const [showTr, setShowTr] = useState(false)
  const [loading, setLoading] = useState(false)
  const [menu, setMenu] = useState(false)
  const full = bookmarksExpanded.value || showTr

  const toggle = async () => {
    const show = !showTr
    setShowTr(show)
    if (show && !b.tr.trim() && !loading) {
      setLoading(true)
      try {
        setBookmarkTranslation(b, (await translateBookmark(b)).trim())
      } catch (e) {
        setShowTr(false)
        say(errorText(e) || 'Ошибка перевода')
      }
      setLoading(false)
    }
  }
  const press = useMemo(() => new Press<number>({ tap: () => void toggle(), long: () => setMenu(true) }), [showTr, loading, b])

  return (
    <div class="bm-row-wrap">
      <div
        class="list-item bm-row" role="button"
        onPointerDown={(e) => press.down(e, 1)} onPointerMove={(e) => press.move(e)}
        onPointerUp={() => press.up()} onPointerCancel={() => press.cancel()}
        onContextMenu={(e) => { e.preventDefault(); setMenu(true) }}
      >
        <div class="li-leading primary"><Icon name="bookmark" /></div>
        <div class="li-body">
          <div class={'li-headline' + (full ? '' : ' clamp2')}>{b.text}</div>
          {showTr && (loading ? <LinearProgress /> : <div class="bm-tr">{b.tr}</div>)}
        </div>
      </div>
      {menu && (
        <Menu onClose={() => setMenu(false)} class="bm-menu">
          <MenuItem icon="forward" onClick={() => { setMenu(false); onOpen() }}>Перейти к источнику</MenuItem>
          <MenuItem icon="delete" onClick={() => { setMenu(false); removeBookmark(b) }}>Удалить закладку</MenuItem>
        </Menu>
      )}
    </div>
  )
}

export function BookmarksScreen() {
  const all = bookmarks.value
  const groups = Object.values(
    all.reduce<Record<number, Bookmark[]>>((acc, b) => { (acc[b.lessonId] ??= []).push(b); return acc }, {}),
  ).sort((a, b) => Math.max(...b.map((x) => x.time)) - Math.max(...a.map((x) => x.time)))
  return (
    <div class="screen">
      <TopBar
        nav={<IconButton icon="back" label="Назад" onClick={back} />}
        title="Закладки"
        actions={all.length ? <ExpandButton /> : null}
      />
      <div class="screen-body scroll">
        <div class="content">
          {!all.length ? (
            <p class="empty">Закладок пока нет. В уроке нажмите на значок закладки у предложения, чтобы вернуться к нему позже.</p>
          ) : (
            <>
              <p class="hint pad">Нажатие — перевод предложения, долгое нажатие — перейти к источнику или удалить.</p>
              {groups.map((g) => (
                <section key={g[0].lessonId}>
                  <h3 class="group-title">{g[0].lessonTitle || `Урок ${g[0].lessonId}`}</h3>
                  {[...g].sort((a, b) => a.sent - b.sent).map((b) => (
                    <BookmarkRow key={`${b.lessonId}:${b.sent}`} b={b} onOpen={() => openBookmark(b)} />
                  ))}
                  <hr />
                </section>
              ))}
            </>
          )}
        </div>
      </div>
    </div>
  )
}

/** Bookmarks of the open lesson: same rows; "go to source" jumps to the sentence. */
export function LessonBookmarksDialog({ s, onClose }: { s: ReaderSession; onClose: () => void }) {
  const list = lessonBookmarks(s.lesson.id)
  return (
    <Dialog
      onClose={onClose}
      title={<div class="row"><span class="grow">Закладки урока</span>{list.length > 0 && <ExpandButton />}</div>}
      actions={
        <>
          <button class="btn text" onClick={() => { onClose(); go({ k: 'bookmarks' }) }}>Все закладки</button>
          <button class="btn text" onClick={onClose}>Закрыть</button>
        </>
      }
    >
      {!list.length ? <p>В этом уроке закладок нет.</p> : (
        <div class="dialog-list">
          {list.map((b) => (
            <BookmarkRow key={b.sent} b={b} onOpen={() => { s.jumpTo(b.sent); onClose() }} />
          ))}
        </div>
      )}
    </Dialog>
  )
}
