// Merges sentences that LingQ split at abbreviations. Port of Android Fixer.kt (itself a port of lingq_fix_splits.py):
// keep all three in sync (ABBREVIATIONS, SENTENCE_STARTERS, AMBIGUOUS, shouldMerge).

/** Words that end with a dot but do not end a sentence (case-sensitive, without the dot). */
export const ABBREVIATIONS = new Set([
  'Abb', 'Abbn', 'Taf', 'Kat', 'Kap', 'Bd', 'Bde', 'Nr', 'Inv', 'Inv.-Nr', 'Sign', 'Slg', 'Rar',
  'Ink', 'fol', 'Fol', 'Bl', 'Dm', 'Hrsg', 'hrsg', 'Aufl', 'Anm', 'vgl', 'Vgl', 's', 'S', 'Sp',
  'ca', 'Ca', 'hl', 'Hl', 'St', 'Dr', 'Prof', 'bzw', 'usw', 'etc', 'evtl', 'ggf', 'sog', 'u',
  'z', 'B', 'd', 'Ä', 'J', 'Jh', 'Jhs', 'Jhdt', 'Jt', 'Jts', 'geb', 'gest', 'verh', 'röm', 'lat', 'griech',
  'dt', 'frz', 'ital', 'mhd', 'ahd', 'Mio', 'Mrd', 'Tsd', 'v', 'Chr', 'n', 'a', 'o', 'ff', 'f',
  'ders', 'Ders', 'Hg', 'hg', 'ebd', 'Ebd', 'zit', 'Zit', 'Gr', 'A', 'Nachf', 'Bibl',
  'Mus', 'Stadtbibl', 'Staatsbibl', 'Univ', 'Pl', 'Abt', 'Ms', 'Cod', 'Cgm', 'Clm', 'Hs',
])

/** Words that typically START a new sentence: after an ordinal ("3.") or a letter ("A.") merge only if the next
 * fragment does NOT begin with one of these. */
export const SENTENCE_STARTERS = new Set(`
  der die das den dem des ein eine einer eines einem einen und aber doch oder denn sondern so also
  dann da danach daher deshalb deswegen trotzdem außerdem auch noch nun jetzt heute hier dort
  es er sie ich wir ihr man wer was wie wo wann warum wenn als weil dass ob obwohl während nachdem
  bevor seit bis mit in im am an auf aus bei nach von vor zu zum zur für über unter durch gegen ohne
  um dieser diese dieses diesen diesem jener jene jedes jeder jede alle viele einige manche kein keine
  nicht nur schon immer zwar ja nein na naja okay ok gut genau tja mein meine sein seine ihre unser
  dabei damit darauf darum dafür dazu davon hinzu zudem zunächst schließlich später erst sogar
  ungefähr etwa vermutlich wahrscheinlich vielleicht allerdings jedoch insgesamt bereits ebenso
  gleichzeitig inzwischen hingegen demgegenüber dagegen stattdessen somit folglich anschließend
  zuerst zuvor seitdem damals heutzutage offenbar offensichtlich tatsächlich natürlich jedenfalls
  immerhin übrigens einerseits andererseits erstens zweitens drittens abb vgl
`.trim().split(/\s+/))

/** Abbreviations that may also END a sentence ("... aus dem 8. Jh. Da Homer ..."). */
export const AMBIGUOUS = new Set(['Jh', 'Jhs', 'Jhdt', 'Jt', 'Jts', 'Chr', 'usw', 'etc', 'ff', 'f', 'ebd', 'Ebd', 'Aufl'])

const LAST_TOKEN = /(\S+)\.$/
const FIRST_WORD = /^[^\p{L}]*(\p{L}+)/u
const ORDINAL_OR_LETTER = /^(?:\d{1,2}|[A-ZÄÖÜ]|[IVX]{1,4}|([A-ZÄÖÜ]\.)+[A-ZÄÖÜ])$/

export const norm = (t: string) => t.replace(/\s+/g, ' ').trim()
export const firstWord = (t: string) => FIRST_WORD.exec(t)?.[1]?.toLowerCase() ?? ''
const startsWithParenOrDigit = (n: string) => !n || '(0123456789'.includes(n[0])
const isLower = (c: string) => c !== c.toUpperCase() && c === c.toLowerCase()

export function endsWithAbbreviation(text: string, nxt: string): boolean {
  const m = LAST_TOKEN.exec(text.trimEnd())
  if (!m) return false
  const tok = m[1].replace(/^[(\[«„"']+/, '')
  const n = nxt.trimStart()
  if (AMBIGUOUS.has(tok)) return startsWithParenOrDigit(n) || !SENTENCE_STARTERS.has(firstWord(n))
  if (ABBREVIATIONS.has(tok)) return true
  if (ORDINAL_OR_LETTER.test(tok)) {
    if (startsWithParenOrDigit(n)) return true // "Theodosius I. (379–395)", "(Abb. 1. 2)"
    return !SENTENCE_STARTERS.has(firstWord(n))
  }
  return false
}

const count = (s: string, c: string) => s.split(c).length - 1

export function shouldMerge(prev: string, nxt: string): boolean {
  const p = prev.trimEnd()
  const n = nxt.trimStart()
  if (!n) return false
  if (endsWithAbbreviation(p, n)) return true
  if (')]»,;:*'.includes(n[0]) || isLower(n[0])) return true // can't start a sentence
  if ((p.endsWith('?') || p.endsWith('!')) && count(p, '(') > count(p, ')')) return true
  return false
}

export function joinText(a: string, b: string): string {
  const bb = b.trimStart()
  const sep = !bb || ')]»,;:'.includes(bb[0]) ? '' : ' '
  return a.trimEnd() + sep + bb
}

export function cleanSentence(t: string): string {
  let s = t.replace(/․/g, '.') // lookalike dot -> real dot
  for (const ch of '[]*_') s = s.split('\\' + ch).join(ch) // markdown escapes \[sei\]
  return s
}

/** MERGE: [a] + [b] become one sentence. DUP: fragment [a] dropped. CLEANUP: characters fixed in [a]. */
export interface Change { kind: 'merge' | 'dup' | 'cleanup'; a: string; b?: string }

type Obj = Record<string, any>
const str = (o: Obj, k: string, fb = '') => (o[k] == null ? fb : String(o[k]))
const arr = (x: unknown): unknown[] => (Array.isArray(x) ? x : [])

function mergeSentences(a: Obj, b: Obj): Obj {
  const m: Obj = structuredClone(a)
  m.text = joinText(str(a, 'text'), str(b, 'text'))
  m.cleanText = joinText(str(a, 'cleanText', str(a, 'text')), str(b, 'cleanText', str(b, 'text')))
  m.phrases = [...arr(a.phrases), ...arr(b.phrases)]
  m.notes = [...arr(a.notes), ...arr(b.notes)]
  const tr = new Map<string, Obj>()
  for (const t of [...arr(a.translations), ...arr(b.translations)] as Obj[]) {
    if (!t || typeof t !== 'object') continue
    const lang = str(t, 'language')
    const ex = tr.get(lang)
    if (ex) ex.text = joinText(str(ex, 'text'), str(t, 'text'))
    else tr.set(lang, structuredClone(t))
  }
  m.translations = [...tr.values()]
  const tsA = arr(a.timestamp), tsB = arr(b.timestamp)
  const start = tsA.length > 0 ? tsA[0] : null
  const end = tsB.length > 1 && tsB[1] != null ? tsB[1] : tsA.length > 1 ? tsA[1] : null
  m.timestamp = [start, end]
  return m
}

/** Fixes editor paragraphs in place and returns the list of changes. */
export function fix(paragraphs: Obj[]): Change[] {
  const changes: Change[] = []
  for (const p of paragraphs) {
    if (!p || !Array.isArray(p.sentences)) continue
    for (const s of p.sentences as Obj[]) {
      if (!s) continue
      for (const k of ['text', 'cleanText']) {
        if (s[k] == null) continue
        const v = String(s[k])
        const c = cleanSentence(v)
        if (c !== v) { if (k === 'text') changes.push({ kind: 'cleanup', a: c }); s[k] = c }
      }
    }
    const out: Obj[] = []
    for (const s of p.sentences as Obj[]) {
      if (!s) continue
      const st = str(s, 'text')
      const last = out[out.length - 1]
      const lt = last ? str(last, 'text') : ''
      if (last && norm(lt).length > 10 && norm(st).startsWith(norm(lt))) {
        changes.push({ kind: 'dup', a: lt }) // LingQ duplicated a fragment
        out[out.length - 1] = s
      } else if (last && shouldMerge(lt, st)) {
        changes.push({ kind: 'merge', a: lt, b: st })
        out[out.length - 1] = mergeSentences(last, s)
      } else out.push(s)
    }
    p.sentences = out
  }
  let idx = 0
  for (const p of paragraphs) for (const s of arr(p?.sentences) as Obj[]) if (s) s.index = ++idx
  return changes
}

export const tail = (t: string, n = 55) => { const s = norm(t); return (s.length > n ? '…' : '') + s.slice(-n) }
export const head = (t: string, n = 55) => { const s = norm(t); return s.slice(0, n) + (s.length > n ? '…' : '') }
