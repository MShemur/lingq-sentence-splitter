// Audio for speech recognition: a part of a file (MP3 frames as they are, anything else decoded to 16 kHz WAV),
// pieces of ~8 minutes cut at quiet places. Port of Android AudioCut / AudioPrep (Transcribe.kt).

export const RATE = 16000
/** Samples per piece: 8 min ≈ 15 MB of WAV (Whisper services take up to 25 MB). */
export const PIECE_S = 8 * 60

// --- MP3 frames ---

const BR = {
  v1l1: [0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448],
  v1l2: [0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384],
  v1l3: [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320],
  v2l1: [0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256],
  v2l23: [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160],
}
const SR = [[44100, 48000, 32000], [22050, 24000, 16000], [11025, 12000, 8000]]

/** Length (bytes) and duration (s) of the MPEG audio frame at [i], or null if there is none. */
function frame(b: Uint8Array, i: number): [number, number] | null {
  if (i + 4 > b.length || b[i] !== 0xff || (b[i + 1] & 0xe0) !== 0xe0) return null
  const ver = (b[i + 1] >> 3) & 3 // 0: 2.5, 1: reserved, 2: v2, 3: v1
  const layer = (b[i + 1] >> 1) & 3 // 1: III, 2: II, 3: I
  if (ver === 1 || layer === 0) return null
  const bri = b[i + 2] >> 4, sri = (b[i + 2] >> 2) & 3, pad = (b[i + 2] >> 1) & 1
  if (bri === 0 || bri === 15 || sri === 3) return null
  const v1 = ver === 3
  const sr = SR[v1 ? 0 : ver === 2 ? 1 : 2][sri]
  const br = (v1 ? (layer === 3 ? BR.v1l1 : layer === 2 ? BR.v1l2 : BR.v1l3) : layer === 3 ? BR.v2l1 : BR.v2l23)[bri] * 1000
  if (layer === 3) return [(Math.floor((12 * br) / sr) + pad) * 4, 384 / sr]
  if (layer === 2 || v1) return [Math.floor((144 * br) / sr) + pad, 1152 / sr]
  return [Math.floor((72 * br) / sr) + pad, 576 / sr]
}

/** Where every frame of an MP3 starts (bytes) and when (s); [end] = byte after the last frame. */
export interface Mp3 { offsets: number[]; times: number[]; end: number; duration: number }

/** The frames of an MP3 file; null if it is not one. */
export async function mp3Frames(blob: Blob): Promise<Mp3 | null> {
  const b = new Uint8Array(await blob.arrayBuffer())
  let i = 0
  if (b[0] === 0x49 && b[1] === 0x44 && b[2] === 0x33) { // ID3v2 tag in front
    i = 10 + ((b[6] & 0x7f) << 21 | (b[7] & 0x7f) << 14 | (b[8] & 0x7f) << 7 | (b[9] & 0x7f)) + (b[5] & 0x10 ? 10 : 0)
  }
  const offsets: number[] = [], times: number[] = []
  let t = 0, end = i
  while (i + 4 <= b.length) {
    const f = frame(b, i)
    // a frame counts only when the next one follows right after it (or the file ends)
    if (f && f[0] > 4 && (i + f[0] >= b.length - 128 || frame(b, i + f[0]))) {
      offsets.push(i); times.push(t); t += f[1]; i += f[0]; end = Math.min(i, b.length)
    } else i++
  }
  return offsets.length >= 10 ? { offsets, times, end, duration: t } : null
}

/** Index of the last frame starting at or before [t] s. */
function frameAt(m: Mp3, t: number): number {
  let lo = 0, hi = m.times.length - 1
  while (lo < hi) { const mid = (lo + hi + 1) >> 1; if (m.times[mid] <= t) lo = mid; else hi = mid - 1 }
  return lo
}

/** Bytes of frames [a, b) of [blob] (b = frame count: to the end). */
const frames = (blob: Blob, m: Mp3, a: number, b: number) => blob.slice(m.offsets[a], b < m.offsets.length ? m.offsets[b] : m.end, 'audio/mpeg')

/** [fromS, toS) of an MP3 without re-encoding. */
export function cutMp3(blob: Blob, m: Mp3, fromS: number, toS: number): Blob {
  const a = frameAt(m, fromS)
  const b = toS >= m.duration ? m.offsets.length : Math.max(a + 1, frameAt(m, toS))
  return frames(blob, m, a, b)
}

/** MP3 pieces of ~8 min, each border in the quietest 0.1 s of the 30 s before it; offsets in s. */
export async function mp3Pieces(blob: Blob, m: Mp3): Promise<{ blob: Blob; offset: number }[]> {
  const cuts = [0]
  while (m.duration - m.times[cuts[cuts.length - 1]] > PIECE_S) {
    const target = m.times[cuts[cuts.length - 1]] + PIECE_S
    const a = frameAt(m, target - 30), b = frameAt(m, target)
    let best = b
    try {
      const pcm = await decodePcm(frames(blob, m, a, b + 1))
      const q = quietest(pcm, 0, pcm.length) / RATE + m.times[a]
      best = Math.max(a + 1, frameAt(m, q))
    } catch { /* not decodable: cut at the target */ }
    cuts.push(Math.max(best, cuts[cuts.length - 1] + 1))
  }
  cuts.push(m.offsets.length)
  return cuts.slice(0, -1).map((c, i) => ({ blob: frames(blob, m, c, cuts[i + 1]), offset: m.times[c] }))
}

// --- decoded audio (16 kHz mono, 16-bit) ---

/** Decodes any audio the browser can play into 16 kHz mono samples. */
export async function decodePcm(blob: Blob): Promise<Int16Array> {
  const data = await blob.arrayBuffer()
  let ctx: OfflineAudioContext
  try { ctx = new OfflineAudioContext(1, 1, RATE) } catch { ctx = new OfflineAudioContext(1, 1, 44100) }
  const buf = await new Promise<AudioBuffer>((res, rej) => {
    const p = ctx.decodeAudioData(data, res, rej) as Promise<AudioBuffer> | undefined
    p?.then?.(res, rej)
  })
  const n = buf.length, ch = buf.numberOfChannels
  let mono: Float32Array = new Float32Array(n)
  for (let c = 0; c < ch; c++) { const d = buf.getChannelData(c); for (let k = 0; k < n; k++) mono[k] += d[k] / ch }
  if (buf.sampleRate !== RATE) mono = resample(mono, buf.sampleRate)
  const out = new Int16Array(mono.length)
  for (let k = 0; k < mono.length; k++) out[k] = Math.max(-32768, Math.min(32767, Math.round(mono[k] * 32767)))
  if (!out.length) throw new Error('не удалось прочитать звук')
  return out
}

/** Linear resampling to 16 kHz with a simple low-pass in front (less aliasing). */
function resample(x: Float32Array, src: number): Float32Array {
  const step = src / RATE
  const alpha = src > RATE ? 1 - Math.exp((-2 * Math.PI * 7000) / src) : 1
  let lp1 = 0, lp2 = 0
  const a = new Float32Array(x.length)
  for (let i = 0; i < x.length; i++) { lp1 += alpha * (x[i] - lp1); lp2 += alpha * (lp1 - lp2); a[i] = lp2 }
  const out = new Float32Array(Math.floor((x.length - 1) / step) + 1)
  for (let k = 0; k < out.length; k++) {
    const p = k * step, i = Math.floor(p), f = p - i
    out[k] = a[i] * (1 - f) + (a[i + 1] ?? a[i]) * f
  }
  return out
}

/** Middle (sample index) of the quietest 0.1 s in [from, to). */
function quietest(pcm: Int16Array, from: number, to: number): number {
  const win = RATE / 10
  let best = to, bestE = Infinity
  for (let w = from; w + win <= to; w += win) {
    let e = 0
    for (let k = w; k < w + win; k++) e += Math.abs(pcm[k])
    if (e < bestE) { bestE = e; best = w + win / 2 }
  }
  return best
}

/** Piece borders (samples): about every 8 minutes, in the quietest 0.1 s of the 30 s before. */
export function pcmCuts(pcm: Int16Array): number[] {
  const out = [0]
  const piece = PIECE_S * RATE
  while (pcm.length - out[out.length - 1] > piece) {
    const target = out[out.length - 1] + piece
    out.push(quietest(pcm, target - 30 * RATE, target))
  }
  out.push(pcm.length)
  return out
}

/** Samples [from, to) as a WAV file. */
export function wav(pcm: Int16Array, from: number, to: number): Blob {
  const bytes = (to - from) * 2
  const h = new DataView(new ArrayBuffer(44))
  const str = (o: number, s: string) => { for (let k = 0; k < 4; k++) h.setUint8(o + k, s.charCodeAt(k)) }
  str(0, 'RIFF'); h.setUint32(4, 36 + bytes, true); str(8, 'WAVE')
  str(12, 'fmt '); h.setUint32(16, 16, true); h.setUint16(20, 1, true); h.setUint16(22, 1, true)
  h.setUint32(24, RATE, true); h.setUint32(28, RATE * 2, true); h.setUint16(32, 2, true); h.setUint16(34, 16, true)
  str(36, 'data'); h.setUint32(40, bytes, true)
  return new Blob([h.buffer, pcm.slice(from, to).buffer], { type: 'audio/wav' })
}

/** Length of an audio file in ms (0 = unknown), from the browser's player. */
export function playerDuration(blob: Blob): Promise<number> {
  return new Promise((res) => {
    const a = new Audio()
    const u = URL.createObjectURL(blob)
    const done = (v: number) => { URL.revokeObjectURL(u); clearTimeout(t); res(v) }
    const t = setTimeout(() => done(0), 15000)
    a.preload = 'metadata'
    a.onloadedmetadata = () => done(Number.isFinite(a.duration) ? Math.round(a.duration * 1000) : 0)
    a.onerror = () => done(0)
    a.src = u
  })
}

/** "1:05:30" / "12:30" / "90" (minutes) -> ms; null if it cannot be read. */
export function parseClock(s: string): number | null {
  if (!s.trim()) return 0
  const p = s.trim().split(':').map((x) => x.trim())
  if (p.some((x) => !/^\d+$/.test(x))) return null
  const n = p.map(Number)
  if (n.length === 1) return n[0] * 60_000
  if (n.length === 2) return (n[0] * 60 + n[1]) * 1000
  if (n.length === 3) return ((n[0] * 60 + n[1]) * 60 + n[2]) * 1000
  return null
}

/** ms -> "12:30" or "1:05:30". */
export function clock(ms: number): string {
  const s = Math.floor(ms / 1000)
  const two = (v: number) => String(v).padStart(2, '0')
  return s >= 3600 ? `${Math.floor(s / 3600)}:${two(Math.floor(s / 60) % 60)}:${two(s % 60)}` : `${Math.floor(s / 60)}:${two(s % 60)}`
}
