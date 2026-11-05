// Cloudflare Pages Function: forwards /api/* to LingQ (its API sends no CORS headers, so the browser
// cannot call it directly). Same origin as the site. Stores nothing, logs nothing.

const ALLOWED = /^\/api\/v[23]\//
const PASS_HEADERS = ['authorization', 'content-type', 'accept']

export const onRequest = async (context: { request: Request }): Promise<Response> => {
  const { request } = context
  const url = new URL(request.url)
  if (!ALLOWED.test(url.pathname)) return new Response('Not found', { status: 404 })

  const headers = new Headers()
  for (const h of PASS_HEADERS) {
    const v = request.headers.get(h)
    if (v) headers.set(h, v)
  }
  // Cloudflare in front of LingQ rejects default client signatures (error 1010)
  headers.set('User-Agent', 'lingq-lesson-fixer/1.0')

  const hasBody = !['GET', 'HEAD'].includes(request.method)
  const r = await fetch('https://www.lingq.com' + url.pathname + url.search, {
    method: request.method,
    headers,
    body: hasBody ? await request.arrayBuffer() : undefined,
    redirect: 'manual',
  })

  const out = new Headers()
  for (const h of ['content-type', 'retry-after']) {
    const v = r.headers.get(h)
    if (v) out.set(h, v)
  }
  out.set('Cache-Control', 'no-store')
  return new Response(r.body, { status: r.status, headers: out })
}
