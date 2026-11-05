// Renders the app icon (same drawing as android/app/src/main/res/drawable/ic_launcher.xml) to PNG files
// without any dependencies: full green square (iOS rounds the corners itself), two white lines and a stitch.
// Run: npm run icons
import { deflateSync } from 'node:zlib'
import { writeFileSync } from 'node:fs'

const GREEN = [0x2e, 0x7d, 0x32]
const WHITE = [0xff, 0xff, 0xff]
const LIGHT = [0xc8, 0xe6, 0xc9]

function capsule(x, y, x1, x2, cy, r) {
  const cx = Math.min(Math.max(x, x1), x2)
  return (x - cx) ** 2 + (y - cy) ** 2 <= r * r
}
function inPoly(x, y, pts) {
  let inside = false
  for (let i = 0, j = pts.length - 1; i < pts.length; j = i++) {
    const [xi, yi] = pts[i], [xj, yj] = pts[j]
    if ((yi > y) !== (yj > y) && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside
  }
  return inside
}
/** Colour at a point of the 108x108 Android viewport. */
function colorAt(x, y) {
  if (inPoly(x, y, [[60, 44], [68, 44], [54, 68], [46, 68]])) return LIGHT
  if (capsule(x, y, 24, 58, 41, 5) || capsule(x, y, 50, 84, 67, 5)) return WHITE
  return GREEN
}

const crcTable = Array.from({ length: 256 }, (_, n) => {
  let c = n
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  return c >>> 0
})
function crc32(buf) {
  let c = 0xffffffff
  for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length)
  const td = Buffer.concat([Buffer.from(type), data])
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td))
  return Buffer.concat([len, td, crc])
}

function png(size) {
  const S = 4 // supersampling per axis
  // the drawing is a bit enlarged: the icon is a square, not a circle
  const scale = 96 / size
  const off = 6
  const raw = Buffer.alloc((size * 3 + 1) * size)
  for (let py = 0; py < size; py++) {
    raw[py * (size * 3 + 1)] = 0
    for (let px = 0; px < size; px++) {
      let r = 0, g = 0, b = 0
      for (let sy = 0; sy < S; sy++) for (let sx = 0; sx < S; sx++) {
        const c = colorAt(off + (px + (sx + 0.5) / S) * scale, off + (py + (sy + 0.5) / S) * scale)
        r += c[0]; g += c[1]; b += c[2]
      }
      const i = py * (size * 3 + 1) + 1 + px * 3
      raw[i] = Math.round(r / S / S); raw[i + 1] = Math.round(g / S / S); raw[i + 2] = Math.round(b / S / S)
    }
  }
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(size, 0); ihdr.writeUInt32BE(size, 4)
  ihdr[8] = 8; ihdr[9] = 2 // 8-bit RGB
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0)),
  ])
}

const out = new URL('../public/', import.meta.url)
for (const [name, size] of [['apple-touch-icon.png', 180], ['icon-192.png', 192], ['icon-512.png', 512]]) {
  writeFileSync(new URL(name, out), png(size))
  console.log(name, size)
}
