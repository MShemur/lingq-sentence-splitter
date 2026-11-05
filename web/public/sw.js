// Service worker: the app opens without network, and updates itself.
// The page (navigation) always comes from the network first, so a new deploy is picked up on the next start;
// hashed files in /assets/ never change and are served from the cache. LingQ/Gemini calls are never cached.
const CACHE = 'lingq-fix-v1'

self.addEventListener('install', () => self.skipWaiting())

self.addEventListener('activate', (e) => {
  e.waitUntil((async () => {
    for (const k of await caches.keys()) if (k !== CACHE) await caches.delete(k)
    await self.clients.claim()
  })())
})

self.addEventListener('fetch', (e) => {
  const req = e.request
  const url = new URL(req.url)
  if (req.method !== 'GET' || url.origin !== location.origin || url.pathname.startsWith('/api/')) return

  if (req.mode === 'navigate') {
    e.respondWith((async () => {
      try {
        const r = await fetch(req, { cache: 'no-store' })
        if (r.ok) (await caches.open(CACHE)).put('/', r.clone())
        return r
      } catch {
        return (await caches.match('/')) || Response.error()
      }
    })())
    return
  }

  e.respondWith((async () => {
    const cache = await caches.open(CACHE)
    const hit = await cache.match(req)
    if (hit && url.pathname.startsWith('/assets/')) return hit
    try {
      const r = await fetch(req)
      if (r.ok) cache.put(req, r.clone())
      return r
    } catch {
      return hit || Response.error()
    }
  })())
})
