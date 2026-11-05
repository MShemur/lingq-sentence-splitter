// Tap / double tap / long press on touch screens (iPad) and with a mouse, built on pointer events.
// Moving the finger (scrolling) cancels the gesture.

export interface PressHandlers<K> {
  tap: (k: K) => void
  /** When set, a single tap waits [DOUBLE_MS] to see whether a second one follows. */
  double?: (k: K) => void
  long?: (k: K) => void
  /** After a long press the finger keeps moving (selection drag) ... */
  longMove?: (e: PointerEvent) => void
  /** ... and is lifted (or the touch was taken over by the browser). */
  longEnd?: () => void
  same?: (a: K, b: K) => boolean
}

const LONG_MS = 480
const DOUBLE_MS = 280
const SLOP = 10

export class Press<K> {
  private key: K | null = null
  private x = 0
  private y = 0
  private longTimer = 0
  private tapTimer = 0
  private longFired = false
  private last: { key: K; time: number } | null = null

  constructor(private h: PressHandlers<K>) {}

  private same(a: K, b: K) { return this.h.same ? this.h.same(a, b) : a === b }

  down(e: PointerEvent, key: K) {
    if (e.pointerType === 'mouse' && e.button !== 0) return
    this.key = key
    this.x = e.clientX
    this.y = e.clientY
    this.longFired = false
    clearTimeout(this.longTimer)
    if (this.h.long) {
      this.longTimer = window.setTimeout(() => {
        if (this.key === null) return
        this.longFired = true
        this.last = null
        clearTimeout(this.tapTimer)
        this.h.long!(this.key)
      }, LONG_MS)
    }
  }

  move(e: PointerEvent) {
    if (this.key === null) return
    if (this.longFired) { this.h.longMove?.(e); return }
    if (Math.abs(e.clientX - this.x) > SLOP || Math.abs(e.clientY - this.y) > SLOP) this.cancel()
  }

  cancel() {
    clearTimeout(this.longTimer)
    const wasLong = this.key !== null && this.longFired
    this.key = null
    if (wasLong) this.h.longEnd?.()
  }

  /** A long press is being held (selection drag in progress). */
  get holding() { return this.key !== null && this.longFired }

  up() {
    clearTimeout(this.longTimer)
    const key = this.key
    this.key = null
    if (key === null) return
    if (this.longFired) { this.h.longEnd?.(); return }
    if (!this.h.double) { this.h.tap(key); return }
    const now = Date.now()
    if (this.last && this.same(this.last.key, key) && now - this.last.time < DOUBLE_MS + 60) {
      clearTimeout(this.tapTimer)
      this.last = null
      this.h.double(key)
      return
    }
    clearTimeout(this.tapTimer)
    this.last = { key, time: now }
    this.tapTimer = window.setTimeout(() => {
      this.last = null
      this.h.tap(key)
    }, DOUBLE_MS)
  }
}
