// Cloudflare Pages Function: /fetch?url=… downloads a podcast feed, episode audio, transcript or cover picture for the
// app (most podcast hosts send no CORS headers). GET only, public http(s) addresses only, and no web pages (HTML is
// refused), so it is no general browsing proxy. Stores nothing, logs nothing.

const PRIVATE = /^(localhost|127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|169\.254\.|0\.|\[?::1\]?$|\[?f[cd][0-9a-f]{2}:)/i

export const onRequest = async (context: { request: Request }): Promise<Response> => {
  const { request } = context
  if (request.method !== 'GET' && request.method !== 'HEAD') return new Response('Method not allowed', { status: 405 })
  let target: URL
  try { target = new URL(new URL(request.url).searchParams.get('url') ?? '') } catch { return new Response('Bad url', { status: 400 }) }
  if (!/^https?:$/.test(target.protocol) || PRIVATE.test(target.hostname)) return new Response('Forbidden', { status: 403 })

  const r = await fetch(target.toString(), {
    method: request.method,
    headers: { 'User-Agent': 'SatzLoop/1.0 (web; podcast import)', Accept: request.headers.get('accept') ?? '*/*' },
    redirect: 'follow',
  })
  const type = r.headers.get('content-type') ?? ''
  // a feed is XML; a page served as HTML is not passed on (keeps this from being a web proxy)
  if (/text\/html/i.test(type)) return new Response('Not a feed or media file', { status: 415 })

  const out = new Headers()
  // the body is passed on decompressed: its length is known only when it was not compressed
  for (const h of r.headers.get('content-encoding') ? ['content-type', 'retry-after'] : ['content-type', 'content-length', 'retry-after']) {
    const v = r.headers.get(h)
    if (v) out.set(h, v)
  }
  out.set('Cache-Control', 'no-store')
  return new Response(r.body, { status: r.status, headers: out })
}
