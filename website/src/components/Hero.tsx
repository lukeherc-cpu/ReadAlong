import { useEffect, useRef, useState } from 'react'
import { AnimatePresence, motion, useInView, useReducedMotion } from 'motion/react'
import { BOOKS } from '../books'
import { REPO } from '../links'
import { PlayerUI } from './kit'
import { DownloadIcon, GitHubIcon, Phone } from './ui'

const CYCLE = [BOOKS.potter, BOOKS.dune, BOOKS.hobbit, BOOKS.orwell, BOOKS.pride]
const PER_BOOK = 5.2

/** The phone beside the headline: each book opens on its cover, turns over into the text, and is read a few sentences in. */
function HeroPhone() {
  const ref = useRef<HTMLDivElement>(null)
  const reduce = useReducedMotion()
  const inView = useInView(ref, { amount: 0.3 })
  const [clock, setClock] = useState(reduce ? 2 : 0)

  useEffect(() => {
    if (reduce || !inView) return
    const id = setInterval(() => setClock((c) => c + 0.1), 100)
    return () => clearInterval(id)
  }, [reduce, inView])

  const index = Math.floor(clock / PER_BOOK) % CYCLE.length
  const t = clock % PER_BOOK
  const book = CYCLE[index]
  const active = Math.min(book.lines.length - 1, Math.max(0, Math.floor((t - 1.5) / 1.15)))

  return (
    <div ref={ref}>
      <Phone>
        <AnimatePresence initial={false}>
          <motion.div key={book.id} className="absolute inset-0" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: 0.7 }}>
            <PlayerUI book={book} flipped={t > 0.9} active={active} playing progress={0.08 + t / 60} />
          </motion.div>
        </AnimatePresence>
      </Phone>
    </div>
  )
}

const rise = {
  hidden: { opacity: 0, y: 26 },
  show: (i: number) => ({ opacity: 1, y: 0, transition: { duration: 0.9, delay: 0.15 + i * 0.09, ease: [0.22, 1, 0.36, 1] as const } }),
}

export function Hero() {
  return (
    <section id="top" className="relative overflow-clip pt-28 pb-20 sm:pt-36 lg:pb-28">
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
