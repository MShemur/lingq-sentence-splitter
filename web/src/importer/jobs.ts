// Background imports ("Актуальные загрузки" in the library): a podcast episode is downloaded, recognised (or its own
// transcript taken) and uploaded to the chosen course while the user goes on with other things. Each job keeps its
// state for the library row; an error stays there until the user closes it.
import { computed, signal, type Signal } from '@preact/signals'
import type { Course, Lesson } from '../lingq'
import { log } from '../applog'
import { ImportSession } from './session'
import { bytes, download, episodeExt, plainText, transcript, type Episode, type Podcast } from './podcasts'
import type { TSeg } from './stt'

export interface JobTarget {
  /** null = a new course named [newTitle]. */
  course: Course | null
  newTitle: string
  /** Part of the episode: from [fromMs], [lenMin] minutes (null = to the end). */
  fromMs: number
  lenMin: number | null
  /** Use the podcast's own timed transcript (free) / its plain text with times from recognition. */
  useTranscript: boolean
  useText: boolean
  cover: boolean
}

export class BgJob {
  readonly id = Math.random().toString(36).slice(2)
  status = signal('Скачиваю…')
  progress = signal(0)
  error = signal<string | null>(null)
  done = signal(false)
  lessons = signal<Lesson[]>([])
  lang: string
  /** Set while the destination is not chosen yet: the download goes on meanwhile. */
  target = signal<JobTarget | null>(null)
  readonly abort = new AbortController()
  private downloaded: Promise<[Blob, TSeg[] | null, string | null, Blob | null]>

  constructor(readonly ep: Episode, readonly pod: Podcast | null, lang: string) {
    this.lang = lang
    this.downloaded = this.fetch()
    this.downloaded.catch(() => { /* reported in run() */ })
  }

  get title() { return this.ep.title || 'Выпуск' }
  get cancelled() { return this.abort.signal.aborted }

  private async fetch(): Promise<[Blob, TSeg[] | null, string | null, Blob | null]> {
    const mb = (n: number) => Math.round(n / 1_000_000)
    const blob = await download(this.ep.audio, (d, t) => {
      this.progress.value = t > 0 ? d / t : 0
      if (!this.target.value || this.status.value.startsWith('Скачиваю')) this.status.value = `Скачиваю: ${mb(d)} из ${t > 0 ? mb(t) : '?'} МБ`
    }, this.abort.signal)
    let tr: TSeg[] | null = null, text: string | null = null
    if (this.ep.transcript) {
      try { tr = await transcript(this.ep.transcript, this.ep.transcriptType) } catch (e) { log('podcast', `Расшифровка ✗ ${(e as Error).message}`) }
      if (!tr?.length) tr = null
    }
    if (!tr && this.ep.textUrl) {
      try { text = await plainText(this.ep.textUrl) } catch (e) { log('podcast', `Текст выпуска ✗ ${(e as Error).message}`) }
      if (text && text.length <= 200) text = null
    }
    const cover = this.pod?.image ? await bytes(this.pod.image) : null
    log('podcast', `«${this.title.slice(0, 60)}»: ${mb(blob.size)} МБ скачано (фоновая загрузка)`)
    if (!this.target.value) this.status.value = `Скачано (${mb(blob.size)} МБ), выберите курс`
    return [blob, tr, text, cover]
  }

  /** The destination was chosen: waits for the download, then recognises and uploads. */
  async run(t: JobTarget) {
    this.target.value = t
    try {
      const [blob, tr, text, cover] = await this.downloaded
      if (this.cancelled) return
      const imp = new ImportSession()
      imp.lang = this.lang
      // the import session's own status lines become this job's status
      const follow = (s: Signal<string>) => s.subscribe((v) => { if (v && !this.done.value && !this.error.value) this.status.value = v })
      const stop = [follow(imp.status)]
      this.status.value = 'Открываю аудио…'
      await imp.pickAudio(blob, `${this.title}.${episodeExt(this.ep)}`, tr, text)
      if (imp.error.value || !imp.audioPick.value) throw new Error(imp.error.value ?? 'аудио не открылось')
      if (t.cover) imp.setCover(cover, this.pod?.title ?? '')
      const dur = imp.audioPick.value.durationMs
      const to = t.lenMin == null ? null : Math.min(dur > 0 ? dur : Infinity, t.fromMs + t.lenMin * 60_000)
      this.status.value = t.useTranscript && tr ? 'Беру расшифровку подкаста…' : 'Распознаю речь…'
      await imp.transcribePart(t.fromMs, to, t.useTranscript && !!tr, t.useText && !!text)
      if (imp.error.value) throw new Error(imp.error.value)
      if (this.cancelled) return
      if (t.course) { imp.course.value = t.course; imp.newCourse.value = false } else { imp.newCourse.value = true; imp.newCourseTitle.value = t.newTitle }
      imp.coverToCourse.value = t.cover && !!imp.cover.value
      this.status.value = 'Загружаю в LingQ…'
      stop.push(imp.log.subscribe((lines) => {
        const last = lines[lines.length - 1]
        if (last && !this.done.value) this.status.value = last.replace(/^[^\p{L}\p{N}]+/u, '').slice(0, 120)
      }), imp.progress.subscribe((p) => { this.progress.value = p }))
      await imp.run()
      stop.forEach((f) => f())
      if (imp.error.value) throw new Error(imp.error.value)
      const bad = imp.log.value.filter((x) => x.startsWith('❌'))
      this.lessons.value = imp.uploaded.value
      if (!imp.uploaded.value.length) throw new Error(bad[0]?.replace(/^❌\s*/, '') ?? 'урок не загрузился')
      this.done.value = true
      this.progress.value = 1
      const n = imp.uploaded.value.length
      this.status.value = `Готово: ${n === 1 ? 'урок' : `${n} урока(ов)`} в курсе «${imp.course.value?.title ?? t.newTitle}»` + (bad.length ? ` · ошибок: ${bad.length}` : '')
      log('podcast', `Фоновая загрузка «${this.title.slice(0, 60)}»: ${this.status.value}`)
    } catch (e) {
      if (this.cancelled) return
      log('podcast', `Фоновая загрузка «${this.title.slice(0, 60)}» ✗ ${(e as Error).message}`)
      this.error.value = (e as Error).message
    }
  }
}

export const bgJobs = signal<BgJob[]>([])
export const runningJobs = computed(() => bgJobs.value.filter((j) => !j.done.value && !j.error.value).length)

/** Starts the download of [ep] at once; the destination is chosen while it runs. */
export function startJob(ep: Episode, pod: Podcast | null, lang: string): BgJob {
  const j = new BgJob(ep, pod, lang)
  bgJobs.value = [j, ...bgJobs.value]
  return j
}

/** The cross on a job: stops it if it still runs, and removes it from the row. */
export function closeJob(j: BgJob) {
  j.abort.abort()
  bgJobs.value = bgJobs.value.filter((x) => x !== j)
}
