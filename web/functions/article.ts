// Cloudflare Pages Function: /article?url=… downloads a web page so the app can take its article (src/importer/article.ts).
// GET only, public http(s) addresses only (also after each redirect, at most 5 hops), web pages only (HTML), body capped
// at 5 MB. A refusal of the site (403, 429 …) keeps its status. Errors of this function are JSON {code, status} so the
// app can tell them apart. Honest User-Agent, no browser disguise. Stores nothing, logs nothing.

const MAX_BYTES = 5 * 1024 * 1024
const MAX_HOPS = 5
const NO_STORE = 'no-store'

/** A host that is not public: this machine, the local network, link-local, carrier NAT, reserved, names without a dot. */
function privateHost(host: string): boolean {
  // "localhost." with a trailing dot is the same host
  const h = host.toLowerCase().replace(/\.$/, '')
  if (h === 'localhost' || h.endsWith('.localhost') || h.endsWith('.local') || h.endsWith('.internal')) return true
  // IPv6 (in brackets): only global unicast (2000::/3) is public; ::, ::1, ::ffff:x (mapped IPv4), fc00::/7, fe80::/10 are not,
  // and 2002::/16 (6to4) embeds an IPv4 address that may be private
  if (h.startsWith('[')) return !/^\[[23][0-9a-f]{0,3}:/.test(h) || /^\[2002:/.test(h)
  const v4 = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(h)
  if (v4) {
    const a = Number(v4[1]), b = Number(v4[2])
    return a === 0 || a === 10 || a === 127 || a >= 224
      || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168)
      || (a === 100 && b >= 64 && b <= 127)
  }
  return !h.includes('.')
}

/** Own error, as JSON: the app shows a message by the code. */
function fail(status: number, code: string): Response {
  return new Response(JSON.stringify({ code, status }), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': NO_STORE },
  })
}

/** The page's bytes as text: the charset from the content type, else from a <meta charset>, else utf-8. Never throws. */
function decode(bytes: Uint8Array, type: string): string {
  try {
    const head = new TextDecoder('latin1').decode(bytes.subarray(0, 4096))
    const label = /charset=["']?\s*([\w-]+)/i.exec(type)?.[1]
      ?? /<meta[^>]+charset=["']?\s*([\w-]+)/i.exec(head)?.[1]
      ?? 'utf-8'
    return new TextDecoder(label).decode(bytes)
  } catch {
    // an unknown or legacy charset label
    return new TextDecoder('utf-8').decode(bytes)
  }
}

export const onRequest = async (context: { request: Request }): Promise<Response> => {
  try {
    const { request } = context
    if (request.method !== 'GET') return fail(405, 'method')
    let target: URL
    try { target = new URL(new URL(request.url).searchParams.get('url') ?? '') } catch { return fail(400, 'bad-url') }
    if (!/^https?:$/.test(target.protocol)) return fail(400, 'bad-url')
    if (privateHost(target.hostname)) return fail(403, 'private')

    // redirects are followed here, one by one, so every address they lead to is checked
    let url = target
    let r: Response
    try {
      for (let hop = 0; ; hop++) {
        r = await fetch(url.toString(), {
          method: 'GET',
          headers: { 'User-Agent': 'SatzLoop/1.0 (article import by one reader)', Accept: 'text/html' },
          redirect: 'manual',
        })
        const location = r.headers.get('location')
        if (r.status < 300 || r.status > 399 || !location) break
        void r.body?.cancel()
        if (hop >= MAX_HOPS) return fail(502, 'redirect')
        const next = new URL(location, url)
        if (!/^https?:$/.test(next.protocol)) return fail(502, 'redirect')
        if (privateHost(next.hostname)) return fail(403, 'private')
        url = next
      }
    } catch { return fail(502, 'unreachable') }

    if (!r.ok) { void r.body?.cancel(); return fail(r.status >= 400 ? r.status : 502, 'upstream') }
    const type = r.headers.get('content-type') ?? ''
    if (!/text\/html|application\/xhtml\+xml/i.test(type)) { void r.body?.cancel(); return fail(415, 'not-html') }

    // the body, at most MAX_BYTES (decompressed)
    const chunks: Uint8Array[] = []
    let size = 0
    try {
      const reader = r.body?.getReader()
      if (reader) {
        for (;;) {
          const { done, value } = await reader.read()
          if (done) break
          size += value.byteLength
          if (size > MAX_BYTES) { await reader.cancel(); return fail(413, 'too-large') }
          chunks.push(value)
        }
      }
    } catch { return fail(502, 'unreachable') }
    const bytes = new Uint8Array(size)
    let at = 0
    for (const c of chunks) { bytes.set(c, at); at += c.byteLength }

    return new Response(decode(bytes, type), {
      status: 200,
      headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': NO_STORE },
    })
  } catch {
    return fail(502, 'unreachable')
  }
}
