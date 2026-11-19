import { useEffect } from 'preact/hooks'

/**
 * Book mode: a translation is an absolute box over its word (see `.reader.book .pin`). Two things CSS cannot know:
 * the content-area height of the word's font (--ca, em; the gap above the text is the same as in the usual mode), and,
 * for a word / phrase split over two lines (hyphenation), where the first part is: the pin is centred over it.
 */
export function placePins(root: HTMLElement) {
  for (const pin of root.querySelectorAll<HTMLElement>('.pin')) {
    const host = pin.parentElement
    if (!host || !host.closest('.book')) continue
    const rects = host.getClientRects()
    if (!rects.length) continue
    const first = rects[0]
    const fs = parseFloat(getComputedStyle(host).fontSize)
    if (fs > 0 && first.height > 0) {
      const ca = (first.height / fs).toFixed(3)
      if (pin.style.getPropertyValue('--ca') !== ca) pin.style.setProperty('--ca', ca)
    }
    if (rects.length > 1) {
      // the containing block of a split inline reaches from the first part's top-left to the last part's bottom-right:
      // put the pin's centre at the left edge, see where it lands, move it to the middle of the first part
      pin.style.setProperty('--pin-l', '0px')
      const b = pin.getBoundingClientRect()
      const dx = first.left + first.width / 2 - (b.left + b.width / 2)
      pin.style.setProperty('--pin-l', dx.toFixed(1) + 'px')
    } else if (pin.style.getPropertyValue('--pin-l')) pin.style.removeProperty('--pin-l')
  }
}

/** Keeps placePins applied while the text, its look or the size changes. [look]: what changes the look (font, spacing). */
export function usePinPlace(ref: { current: HTMLElement | null }, on: boolean, look: unknown[]) {
  useEffect(() => {
    const el = ref.current
    if (!el || !on) return
    let raf = 0
    const run = () => { cancelAnimationFrame(raf); raf = requestAnimationFrame(() => placePins(el)) }
    run()
    const mo = new MutationObserver((list) => { if (list.some((m) => m.type === 'childList' || m.type === 'characterData')) run() })
    mo.observe(el, { childList: true, subtree: true, characterData: true })
    const ro = new ResizeObserver(run)
    ro.observe(el)
    document.fonts?.ready.then(run)
    return () => { mo.disconnect(); ro.disconnect(); cancelAnimationFrame(raf) }
  }, [on, ...look])
}
