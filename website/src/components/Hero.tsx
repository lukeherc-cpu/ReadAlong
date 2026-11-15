import { useEffect, useRef, useState } from 'react'
import { AnimatePresence, motion, useInView, useReducedMotion } from 'motion/react'
import { BOOKS } from '../books'
import { REPO } from '../links'
import { PlayerUI } from './kit'
import { DownloadIcon, GitHubIcon, Phone } from './ui'

const CYCLE = [BOOKS.potter, BOOKS.dune, BOOKS.hobbit, BOOKS.habits, BOOKS.pride]
/** Each book shows its cover, turns over, then is narrated for the length of its clip. */
const FLIP = 0.7
const LEAD = 1.2
const TAIL = 0.9
const span = (i: number) => LEAD + CYCLE[i].clip!.length + TAIL

function SpeakerIcon({ on }: { on: boolean }) {
  return (
    <svg viewBox="0 0 24 24" className="size-5" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M4 9.5v5h3.5L12 18.5v-13L7.5 9.5H4Z" fill="currentColor" />
      {on ? <path d="M15.5 9a4.2 4.2 0 0 1 0 6M18 6.5a7.8 7.8 0 0 1 0 11" /> : <path d="m16 9.5 5 5m0-5-5 5" />}
    </svg>
  )
}

/**
 * The phone beside the headline: each book opens on its cover, turns over into the text, and is read line by line.
 * It is silent until the visitor turns the sound on; then each book's own narration plays in step with the highlight.
 */
function HeroPhone() {
  const ref = useRef<HTMLDivElement>(null)
  const audio = useRef<HTMLAudioElement | null>(null)
  const reduce = useReducedMotion()
  const inView = useInView(ref, { amount: 0.3 })
  const [sound, setSound] = useState(false)
  const [{ index, t }, setClock] = useState({ index: 0, t: reduce ? LEAD + 0.5 : 0 })

  useEffect(() => {
    if (reduce || !inView) return
    let last = performance.now()
    const id = setInterval(() => {
      const now = performance.now()
      const dt = Math.min(0.25, (now - last) / 1000)
      last = now
      setClock((c) => (c.t + dt >= span(c.index) ? { index: (c.index + 1) % CYCLE.length, t: 0 } : { index: c.index, t: c.t + dt }))
    }, 80)
    return () => clearInterval(id)
  }, [reduce, inView])

  const book = CYCLE[index]
  const clip = book.clip!
  const at = t - LEAD
  const active = clip.cues.reduce((found, cue, i) => (at >= cue ? i : found), 0)

  // Keep the narration in step with the clock: start it when a book's reading begins, stop it between books or when muted or off screen.
  useEffect(() => {
    const el = audio.current
    if (!el) return
    const narrating = sound && inView && !reduce && at >= 0 && at < clip.length
    if (!narrating) {
      if (!el.paused) el.pause()
      return
    }
    if (!el.src.endsWith(clip.src.replace(/^\.\//, ''))) {
      el.src = clip.src
      el.currentTime = at
    } else if (Math.abs(el.currentTime - at) > 0.4) {
      el.currentTime = at
    }
    if (el.paused) el.play().catch(() => {})
  }, [sound, inView, reduce, at, clip])

  useEffect(() => () => audio.current?.pause(), [])

  const toggle = () => {
    if (!audio.current) audio.current = new Audio()
    setSound((s) => !s)
  }

  return (
    <div ref={ref} className="relative">
      <Phone>
        <AnimatePresence initial={false}>
          <motion.div key={book.id} className="absolute inset-0" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: 0.7 }}>
            <PlayerUI book={book} flipped={t > FLIP} active={active} playing progress={0.06 + Math.max(0, at) / 90} />
          </motion.div>
        </AnimatePresence>
      </Phone>
      {!reduce && (
        <button
          type="button"
          onClick={toggle}
          aria-pressed={sound}
          aria-label={sound ? 'Mute the narration' : 'Play the narration out loud'}
          title={sound ? 'Mute' : 'Hear it read'}
          className={`absolute -bottom-16 left-1/2 flex h-11 -translate-x-1/2 cursor-pointer items-center gap-2 rounded-full px-4 text-sm font-bold whitespace-nowrap ring-1 transition-colors duration-300 sm:top-8 sm:bottom-auto sm:-left-5 sm:-translate-x-full ${sound ? 'bg-pale text-deep ring-pale' : 'bg-white/10 text-white ring-white/30 hover:bg-white/16'}`}
        >
          <SpeakerIcon on={sound} />
          {sound ? 'Sound on' : 'Sound off'}
        </button>
      )}
    </div>
  )
}

const rise = {
  hidden: { opacity: 0, y: 26 },
  show: (i: number) => ({ opacity: 1, y: 0, transition: { duration: 0.9, delay: 0.15 + i * 0.09, ease: [0.22, 1, 0.36, 1] as const } }),
}

export function Hero() {
  return (
    <section id="top" className="relative overflow-clip pt-28 pb-32 sm:pt-36 sm:pb-20 lg:pb-28">
      <div aria-hidden="true" className="pointer-events-none absolute -top-40 left-1/2 h-[46rem] w-[72rem] max-w-none -translate-x-1/2 rounded-[50%] bg-deep opacity-70 blur-[110px]" />
      <div className="relative mx-auto grid max-w-6xl items-center gap-14 px-5 sm:px-8 lg:grid-cols-[1.1fr_0.9fr] lg:gap-8">
        <motion.div initial="hidden" animate="show">
          <motion.h1 variants={rise} custom={0} className="display text-[clamp(2.75rem,6vw,4.25rem)]">
            Hear the book.
            <br />
            See every word.
          </motion.h1>
          <motion.p variants={rise} custom={1} className="mt-7 max-w-xl text-lg leading-relaxed text-fog sm:text-xl">
            An audiobook player that highlights each sentence as the narrator reads it. For Android, Windows, macOS and Linux.
          </motion.p>
          <motion.div variants={rise} custom={2} className="mt-9 flex flex-wrap items-center gap-3">
            <a className="flex h-13 items-center gap-2.5 rounded-full bg-pale px-7 text-base font-bold text-deep transition-transform duration-300 hover:scale-[1.03] active:scale-[0.98]" href="#download">
              <DownloadIcon className="size-5" />
              Download free
            </a>
            <a className="flex h-13 items-center gap-2.5 rounded-full px-6 text-base font-semibold text-white ring-1 ring-white/30 transition-colors hover:bg-white/8" href="#try">
              Try it here
            </a>
            <a className="flex h-13 items-center gap-2.5 rounded-full px-4 text-base font-semibold text-white transition-colors hover:bg-white/8" href={REPO}>
              <GitHubIcon />
              Source
            </a>
          </motion.div>
        </motion.div>

        <motion.div className="mx-auto w-[min(74vw,19.5rem)] lg:w-[21rem]" initial={{ opacity: 0, y: 60, scale: 0.96 }} animate={{ opacity: 1, y: 0, scale: 1 }} transition={{ duration: 1.2, delay: 0.25, ease: [0.22, 1, 0.36, 1] }}>
          <HeroPhone />
        </motion.div>
      </div>
    </section>
  )
}
