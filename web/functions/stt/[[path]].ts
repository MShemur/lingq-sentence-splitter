// Cloudflare Pages Function: forwards /stt/<service>/* to a speech recognition service (not all of them send CORS
// headers). Only the services below; the key comes from the app in the request. Stores nothing, logs nothing.

const STT_HOSTS: Record<string, string> = {
  groq: 'https://api.groq.com/openai/v1',
  openai: 'https://api.openai.com/v1',
  deepgram: 'https://api.deepgram.com/v1',
  assemblyai: 'https://api.assemblyai.com/v2',
  elevenlabs: 'https://api.elevenlabs.io/v1',
}
const PASS_HEADERS = ['authorization', 'xi-api-key', 'content-type', 'accept']

export const onRequest = async (context: { request: Request }): Promise<Response> => {
  const { request } = context
  const url = new URL(request.url)
  const m = /^\/stt\/([a-z]+)\/(.*)$/.exec(url.pathname)
  const host = m ? STT_HOSTS[m[1]] : undefined
  if (!m || !host) return new Response('Not found', { status: 404 })

  const headers = new Headers()
  for (const h of PASS_HEADERS) {
    const v = request.headers.get(h)
    if (v) headers.set(h, v)
  }
  const hasBody = !['GET', 'HEAD'].includes(request.method)
  const r = await fetch(`${host}/${m[2]}${url.search}`, {
    method: request.method,
    headers,
    body: hasBody ? request.body : undefined,
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
