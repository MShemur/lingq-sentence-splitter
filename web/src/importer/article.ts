// A web article as plain text: the extraction runs in the reader's Safari (bookmarklet, copied text with a
// "SatzLoop: <title>" line) or on the app's own page fetch (/article). Both use extractArticle, so the result is the same.
//
// extractArticle is copied into the bookmarklet with Function.prototype.toString: it must stay self-contained
// (no imports, no references to anything outside its own body).

/** Shown when a site does not give its article to the app (refused, or no article found). */
export const ARTICLE_REFUSED = 'Сайт не отдаёт статью приложению. Откройте её в Safari и нажмите закладку SatzLoop'

/** The article's title and text (paragraphs separated by an empty line). Runs in the page or in the app. */
export function extractArticle(doc: Document): { title: string; text: string } {
  const clean = (s: string | null | undefined): string => (s || '').replace(/\s+/g, ' ').trim()

  // title: og:title, else the first h1, else the page title (without a " | Site name" suffix)
  let title = clean(doc.querySelector('meta[property="og:title"]')?.getAttribute('content'))
  let fromPage = !!title
  if (!title) title = clean(doc.querySelector('h1')?.textContent)
  if (!title) { title = clean(doc.title); fromPage = true }
  if (fromPage) {
    const cut = title.replace(/\s+[|–—-]\s+[^|–—-]{1,40}$/, '')
    if (cut.length >= 10) title = cut
  }

  const BLOCK = 'p,h2,h3,h4,li,blockquote'
  const SKIP = 'nav,aside,footer,figure,figcaption,form,button,script,style,noscript,[aria-hidden="true"]'
  // noise by class or id: on the block itself (also paywall) and on its wrappers up to the container (not paywall:
  // a wrapper with that class would empty the whole article)
  const NOISE = /\bcomments?\b|\bkommentare?\b|disqus|share|social|related|newsletter|teaser|advert|promo|byline|caption|breadcrumb|\breferences\b|reflist|navbox|infobox|\btoc\b/i
  const PAYWALL = /paywall/i
  const own = (e: Element) => (e.getAttribute('class') || '') + ' ' + (e.id || '')
  // what is no text of the article: CSS and scripts, footnote marks ([1] of a reference), edit links of headings
  const NOTE = 'style,script,noscript,template,.mw-editsection,sup.reference,a[href^="#cite"],a[role="doc-noteref"]'
  const isNote = (e: Element) => e.matches(NOTE)
    || (e.tagName === 'SUP' && e.querySelectorAll('a').length === 1 && /#(cite|fn|note)/.test(e.querySelector('a')?.getAttribute('href') || ''))
  // the text of [el] as textContent gives it, without the parts above
  const textOf = (el: Element): string => {
    let s = ''
    const walk = (n: Node) => {
      for (const c of Array.from(n.childNodes)) {
        if (c.nodeType === 3) s += c.nodeValue
        else if (c.nodeType === 1 && !isNote(c as Element)) walk(c)
      }
    }
    walk(el)
    return clean(s)
  }

  // the text blocks of [root] in document order, without the noise and the repeats
  const collect = (root: Element): string[] => {
    const out: string[] = []
    let last = ''
    const els = Array.from(root.querySelectorAll(BLOCK))
    for (const el of els) {
      let skip = PAYWALL.test(own(el))
      // the walk stops at the container: its own classes do not count
      for (let a: Element | null = el; a && a !== root && !skip; a = a.parentElement) {
        if (a.matches(SKIP) || NOISE.test(own(a))) skip = true
        // a header inside an article is its lead (kept); a site header is not
        else if (a.tagName === 'HEADER' && !a.parentElement?.closest('article')) skip = true
        // a block inside another block (p in li) is taken with the outer one
        else if (a !== el && a.matches(BLOCK)) skip = true
      }
      if (skip) continue
      const text = textOf(el)
      if (!text) continue
      if (!/^H[234]$/.test(el.tagName)) {
        // too short, or only a link (a teaser)
        if (text.length < 20) continue
        let linked = 0
        for (const x of Array.from(el.querySelectorAll('a'))) linked += textOf(x).length
        if (linked >= text.length * 0.8) continue
      }
      if (text === last) continue
      out.push(text); last = text
    }
    return out
  }
  const total = (xs: string[]) => xs.reduce((n, x) => n + x.length, 0)

  let paras: string[] = []

  if (!paras.length) {
    // the first container with enough text
    for (const sel of ['[itemprop="articleBody"]', 'article', 'main', '[role="main"]']) {
      for (const el of Array.from(doc.querySelectorAll(sel))) {
        const p = collect(el)
        if (total(p) >= 300) { paras = p; break }
      }
      if (paras.length) break
    }
  }
  if (!paras.length) {
    // else the element whose own paragraphs carry the most text
    let best: Element | null = null
    let bestN = 0
    for (const el of Array.from(doc.querySelectorAll('div,section,article,main,td'))) {
      let n = 0
      for (const c of Array.from(el.children)) if (c.tagName === 'P') n += textOf(c).length
      if (n > bestN) { bestN = n; best = el }
    }
    if (best) paras = collect(best)
  }

  // news sites often ship the whole article in JSON-LD (articleBody): taken when the page's own text is much shorter
  let body = ''
  for (const s of Array.from(doc.querySelectorAll('script[type="application/ld+json"]'))) {
    try {
      const j = JSON.parse(s.textContent || '')
      const nodes: any[] = ([] as any[]).concat(j, (j && j['@graph']) || [])
      for (const o of nodes) if (o && typeof o.articleBody === 'string' && o.articleBody.length > body.length) body = o.articleBody
    } catch { /* not JSON */ }
  }
  if (body.length > 500) {
    // the text may carry HTML entities (&amp; &#8222;) and line breaks; an inert document decodes them (no images, no scripts)
    const inert = doc.implementation.createHTMLDocument('')
    const jp: string[] = []
    for (const line of body.split(/\r?\n+/)) {
      // a page with Trusted Types refuses innerHTML: then the line as it is
      let t = ''
      try { inert.body.innerHTML = line; t = clean(inert.body.textContent) } catch { t = clean(line) }
      if (t) jp.push(t)
    }
    if (total(paras) < total(jp) * 0.8) paras = jp
  }

  return { title, text: paras.join('\n\n') }
}

/** A link as the app takes it: "https://" added when there is no scheme; null if it is not an http(s) address. */
export function articleLink(raw: string): string | null {
  let t = raw.trim()
  if (!t) return null
  if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(t)) t = 'https://' + t
  try {
    const u = new URL(t)
    return /^https?:$/.test(u.protocol) && u.hostname.includes('.') ? u.toString() : null
  } catch { return null }
}

/** The bookmarklet's overlay: the title, the word count, a copy button (with a text field as fallback). Self-contained. */
function showArticleBox(r: { title: string; text: string }) {
  const old = document.getElementById('satzloop-box')
  if (old) old.remove()
  const mk = (tag: string, css: string, txt?: string): HTMLElement => {
    const e = document.createElement(tag)
    e.style.cssText = css
    if (txt != null) e.textContent = txt
    return e
  }
  const box = mk('div', 'position:fixed;top:0;left:0;right:0;z-index:2147483647;box-sizing:border-box;max-height:70vh;overflow:auto;padding:12px 14px;background:#fff;color:#111;font:16px/1.4 -apple-system,system-ui,sans-serif;box-shadow:0 2px 14px rgba(0,0,0,.35);border-bottom:3px solid #1a73e8')
  box.id = 'satzloop-box'
  box.appendChild(mk('div', 'font-weight:600;margin-bottom:4px', 'SatzLoop'))
  if (!r.text) {
    box.appendChild(mk('div', 'margin-bottom:8px', 'На этой странице не нашлось статьи.'))
  } else {
    box.appendChild(mk('div', 'font-weight:600;margin-bottom:2px', r.title || 'Без названия'))
    box.appendChild(mk('div', 'color:#555;font-size:14px', 'Слов: ' + r.text.split(/\s+/).length))
    const marked = 'SatzLoop: ' + r.title + '\n\n' + r.text
    const status = mk('div', 'margin-top:8px;font-size:14px;color:#222', '')
    const ta = mk('textarea', 'display:none;width:100%;height:120px;margin-top:8px;font:14px sans-serif') as HTMLTextAreaElement
    ta.value = marked
    const done = () => { status.textContent = 'Скопировано. Вернитесь в SatzLoop и нажмите «Вставить».' }
    const manual = () => {
      ta.style.display = 'block'
      ta.focus(); ta.select(); ta.setSelectionRange(0, marked.length)
      let ok = false
      try { ok = document.execCommand('copy') } catch { /* no */ }
      status.textContent = ok ? 'Скопировано. Вернитесь в SatzLoop и нажмите «Вставить».' : 'Выделите текст в поле ниже и скопируйте.'
    }
    const btn = mk('button', 'font:inherit;font-size:16px;padding:10px 14px;margin-top:8px;border:0;border-radius:8px;background:#1a73e8;color:#fff', 'Скопировать для SatzLoop')
    btn.onclick = () => {
      if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(marked).then(done, manual)
      else manual()
    }
    box.appendChild(btn)
    box.appendChild(status)
    box.appendChild(ta)
  }
  const close = mk('button', 'font:inherit;font-size:16px;padding:10px 14px;margin-top:8px;margin-left:8px;border:1px solid #999;border-radius:8px;background:#fff;color:#111', 'Закрыть')
  close.onclick = () => box.remove()
  box.appendChild(close)
  document.body.appendChild(box)
}

/** The bookmarklet: javascript: URL that extracts the open page's article and shows the overlay. */
export function bookmarkletCode(): string {
  return 'javascript:' + encodeURIComponent(`(function(){(${showArticleBox.toString()})((${extractArticle.toString()})(document))})()`)
}

/** The text copied from the bookmarklet: "SatzLoop: <title>", an empty line, the text. null if it is not such a text. */
export function parseMarked(text: string): { title: string; text: string } | null {
  const t = text.trimStart()
  const m = /^SatzLoop:[ \t]*([^\r\n]*)(?:\r?\n|$)/.exec(t)
  if (!m) return null
  return { title: m[1].trim(), text: t.slice(m[0].length).trim() }
}

/** An article from a link: the app's /article page (the site's own text), parsed here. Throws a Russian message. */
export async function fetchArticle(url: string): Promise<{ title: string; text: string }> {
  let r: Response
  try { r = await fetch('/article?url=' + encodeURIComponent(url)) } catch {
    throw new Error('Нет связи с сервером. Проверьте интернет или откройте статью в Safari и нажмите закладку SatzLoop')
  }
  if (!r.ok) {
    // the function's own errors come as {code}; anything else (or a site's refusal) is the Safari hint
    let code = ''
    try { code = String((await r.json()).code ?? '') } catch { /* not the function's answer */ }
    if (r.status === 413 || code === 'too-large') throw new Error('Страница слишком большая')
    if (code === 'private') throw new Error('Такой адрес нельзя загрузить')
    if (code === 'bad-url') throw new Error('Это не похоже на ссылку')
    if (code === 'not-html') throw new Error('Это не веб-страница с текстом')
    throw new Error(ARTICLE_REFUSED)
  }
  const doc = new DOMParser().parseFromString(await r.text(), 'text/html')
  const a = extractArticle(doc)
  if (a.text.split(/\s+/).filter(Boolean).length < 80) throw new Error(ARTICLE_REFUSED)
  return a
}
