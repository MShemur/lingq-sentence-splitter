// Small Material 3 style building blocks.
import type { ComponentChildren, JSX } from 'preact'
import { useEffect, useState } from 'preact/hooks'
import { Icon, type IconName } from './icons'

export function IconButton(p: { icon: IconName; label: string; onClick: () => void; size?: number; class?: string; disabled?: boolean }) {
  return (
    <button class={'icon-btn ' + (p.class ?? '')} aria-label={p.label} title={p.label} onClick={p.onClick} disabled={p.disabled}>
      <Icon name={p.icon} size={p.size ?? 24} />
    </button>
  )
}

export function TopBar(p: { title?: ComponentChildren; nav?: ComponentChildren; actions?: ComponentChildren; center?: boolean }) {
  return (
    <header class={'top-bar' + (p.center ? ' center' : '')}>
      <div class="top-bar-nav">{p.nav}</div>
      <div class="top-bar-title">{p.title}</div>
      <div class="top-bar-actions">{p.actions}</div>
    </header>
  )
}

export function ListItem(p: {
  headline: ComponentChildren
  supporting?: ComponentChildren
  leading?: ComponentChildren
  trailing?: ComponentChildren
  onClick?: () => void
  class?: string
}) {
  const Tag = p.onClick ? 'button' : 'div'
  return (
    <Tag class={'list-item ' + (p.class ?? '')} onClick={p.onClick}>
      {p.leading && <div class="li-leading">{p.leading}</div>}
      <div class="li-body">
        <div class="li-headline">{p.headline}</div>
        {p.supporting && <div class="li-supporting">{p.supporting}</div>}
      </div>
      {p.trailing && <div class="li-trailing">{p.trailing}</div>}
    </Tag>
  )
}

export const Spinner = ({ size = 32 }: { size?: number }) => (
  <span class="spinner" style={{ width: size + 'px', height: size + 'px', borderWidth: Math.max(2, size / 12) + 'px' }} />
)

export const Loading = () => <div class="loading"><Spinner /></div>

export const LinearProgress = () => <div class="linear-progress"><div /></div>

export const ProgressPill = ({ p }: { p: number }) => (
  <div class="progress-pill"><div style={{ width: Math.min(100, Math.max(0, p * 100)) + '%' }} /></div>
)

export function ErrorBox({ text, retry }: { text: string; retry: () => void }) {
  return (
    <div class="error-box">
      <p>Ошибка: {text}</p>
      <button class="btn outlined" onClick={retry}>Повторить</button>
    </div>
  )
}

/** Closes on Escape. */
function useEscape(onClose: () => void) {
  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', h)
    return () => window.removeEventListener('keydown', h)
  }, [onClose])
}

export function Dialog(p: { title: ComponentChildren; children: ComponentChildren; actions: ComponentChildren; onClose: () => void }) {
  useEscape(p.onClose)
  return (
    <div class="scrim" onClick={p.onClose}>
      <div class="dialog" role="dialog" onClick={(e) => e.stopPropagation()}>
        <div class="dialog-title">{p.title}</div>
        <div class="dialog-body">{p.children}</div>
        <div class="dialog-actions">{p.actions}</div>
      </div>
    </div>
  )
}

export function Sheet(p: { children: ComponentChildren; onClose: () => void }) {
  useEscape(p.onClose)
  return (
    <div class="scrim sheet-scrim" onClick={p.onClose}>
      <div class="sheet" role="dialog" onClick={(e) => e.stopPropagation()}>
        <div class="sheet-handle" />
        {p.children}
      </div>
    </div>
  )
}

/** Dropdown menu: a transparent layer closes it on any outside tap. */
export function Menu(p: { children: ComponentChildren; onClose: () => void; class?: string }) {
  useEscape(p.onClose)
  return (
    <>
      <div class="menu-layer" onClick={p.onClose} />
      <div class={'menu ' + (p.class ?? '')} role="menu">{p.children}</div>
    </>
  )
}

export function MenuItem(p: { icon?: IconName; children: ComponentChildren; onClick: () => void }) {
  return (
    <button class="menu-item" role="menuitem" onClick={p.onClick}>
      {p.icon ? <Icon name={p.icon} size={20} /> : null}
      <span>{p.children}</span>
    </button>
  )
}

export function Chip(p: { label: ComponentChildren; icon?: IconName; selected?: boolean; onClick: () => void }) {
  return (
    <button class={'chip' + (p.selected ? ' selected' : '')} onClick={p.onClick}>
      {p.selected ? <Icon name="check" size={16} /> : p.icon ? <Icon name={p.icon} size={18} /> : null}
      <span>{p.label}</span>
    </button>
  )
}

/** The cross inside a search field: empties it (shown only while there is text). */
export function ClearX(p: { value: string; onClear: () => void }) {
  return p.value ? (
    <button class="clear-x" type="button" aria-label="Очистить" onClick={p.onClear}><Icon name="close" size={18} /></button>
  ) : null
}

/**
 * A key / password field. It is a small login form (hidden user name = the field's label), so the browser's password
 * manager (Google in Chrome, iCloud Keychain in Safari) can offer to save the key on "Сохранить в менеджер паролей"
 * and to fill it in later from its suggestions above the keyboard.
 */
export function SecretField(p: { label: string; value: string; onInput: (v: string) => void }) {
  const [show, setShow] = useState(false)
  const [copied, setCopied] = useState(false)
  const [saved, setSaved] = useState(false)
  const copy = () => {
    navigator.clipboard?.writeText(p.value).then(() => { setCopied(true); setTimeout(() => setCopied(false), 1500) }, () => { /* no clipboard */ })
  }
  const user = `SatzLoop: ${p.label}`
  return (
    <form class="field" method="post" action="#" autocomplete="on"
      onSubmit={(e) => { e.preventDefault(); setSaved(true); setTimeout(() => setSaved(false), 8000) }}>
      <label class="field-label" for={'k-' + user}>{p.label}</label>
      <input class="pm-user" type="text" name="username" autocomplete="username" value={user} readOnly tabIndex={-1} aria-hidden="true" />
      <div class="field-row">
        <input id={'k-' + user} name="password"
          type={show ? 'text' : 'password'} value={p.value} autocomplete="current-password" autocapitalize="off" spellcheck={false}
          onInput={(e) => p.onInput((e.target as HTMLInputElement).value)}
        />
        {!!p.value && <button class="btn text" type="button" onClick={copy}>{copied ? 'Скопировано' : 'Копировать'}</button>}
        <button class="btn text" type="button" onClick={() => setShow(!show)}>{show ? 'Скрыть' : 'Показать'}</button>
      </div>
      {!!p.value && (
        <button class="btn text pm-save" type="submit">{saved ? 'Если браузер спросил — нажмите «Сохранить»' : 'Сохранить в менеджер паролей'}</button>
      )}
    </form>
  )
}

export function TextField(p: {
  label: string
  value: string
  onInput: (v: string) => void
  hint?: string
  multiline?: boolean
  trailing?: ComponentChildren
} & Omit<JSX.HTMLAttributes<HTMLInputElement>, 'value' | 'onInput' | 'label'>) {
  const { label, value, onInput, hint, multiline, trailing, ...rest } = p
  return (
    <label class="field">
      <span class="field-label">{label}</span>
      <div class="field-row">
        {multiline
          ? <textarea value={value} rows={5} onInput={(e) => onInput((e.target as HTMLTextAreaElement).value)} />
          : <input {...rest} value={value} autocapitalize="off" spellcheck={false} onInput={(e) => onInput((e.target as HTMLInputElement).value)} />}
        {trailing}
      </div>
      {hint && <span class="field-hint">{hint}</span>}
    </label>
  )
}
