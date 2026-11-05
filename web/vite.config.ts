import { defineConfig, loadEnv } from 'vite'
import preact from '@preact/preset-vite'
import { readFileSync } from 'node:fs'

// Web version v1.N: raised in package.json with every change of web/ (like Android's v1.<build number>)
const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf-8'))

// Dev server: /api is forwarded to LingQ like the Pages Function does in production.
// LINGQ_API_KEY from the environment replaces the key "dev" entered in the app (local testing only).
export default defineConfig(({ mode }) => {
  const env = { ...process.env, ...loadEnv(mode, process.cwd(), '') }
  return {
    plugins: [preact()],
    define: {
      // shown in Settings, so it is clear which deploy the iPad runs
      __VERSION__: JSON.stringify(pkg.version.split('.').slice(0, 2).join('.')),
      __BUILD__: JSON.stringify(new Date().toISOString().slice(0, 16).replace('T', ' ') + ' UTC'),
    },
    server: {
      proxy: {
        '/api': {
          target: 'https://www.lingq.com',
          changeOrigin: true,
          configure: (proxy) => {
            proxy.on('proxyReq', (req) => {
              req.setHeader('User-Agent', 'lingq-lesson-fixer/1.0')
              req.removeHeader('origin')
              req.removeHeader('referer')
              req.removeHeader('cookie')
              const auth = req.getHeader('authorization')
              if ((!auth || auth === 'Token dev') && env.LINGQ_API_KEY) req.setHeader('Authorization', `Token ${env.LINGQ_API_KEY}`)
            })
          },
        },
      },
    },
  }
})
