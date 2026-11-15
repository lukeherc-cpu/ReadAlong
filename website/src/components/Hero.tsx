import { useRef } from 'react'
import { motion, useReducedMotion, useScroll, useTransform } from 'motion/react'
import { REPO } from '../links'
import { Clip, DownloadIcon, GitHubIcon, Phone } from './ui'

const rise = {
  hidden: { opacity: 0, y: 26 },
  show: (i: number) => ({ opacity: 1, y: 0, transition: { duration: 0.9, delay: 0.15 + i * 0.09, ease: [0.22, 1, 0.36, 1] as const } }),
}

export function Hero() {
  const ref = useRef<HTMLElement>(null)
  const reduce = useReducedMotion()
  const { scrollYProgress } = useScroll({ target: ref, offset: ['start start', 'end start'] })
  // The phone drifts up more slowly than the page and the copy lets go first.
  const phoneY = useTransform(scrollYProgress, [0, 1], [0, reduce ? 0 : 110])
  const copyY = useTransform(scrollYProgress, [0, 1], [0, reduce ? 0 : -50])
  const copyOpacity = useTransform(scrollYProgress, [0, 0.7], [1, reduce ? 1 : 0])
  const glowY = useTransform(scrollYProgress, [0, 1], [0, reduce ? 0 : 220])

  return (
    <section ref={ref} id="top" className="relative overflow-clip pt-28 pb-20 sm:pt-36 lg:pb-28">
      {/* The blue accent's dark tone, the color the app opens on. */}
      <motion.div
        aria-hidden="true"
        className="pointer-events-none absolute -top-40 left-1/2 h-[46rem] w-[72rem] max-w-none -translate-x-1/2 rounded-[50%] bg-deep opacity-70 blur-[110px]"
        style={{ y: glowY }}
      />
      <div className="relative mx-auto grid max-w-6xl items-center gap-14 px-5 sm:px-8 lg:grid-cols-[1.1fr_0.9fr] lg:gap-8">
        <motion.div style={{ y: copyY, opacity: copyOpacity }} initial="hidden" animate="show">
          <motion.p variants={rise} custom={0} className="mb-6 text-sm font-semibold text-pale">
            Audiobook player for Android, Windows, Linux and macOS
          </motion.p>
          <motion.h1 variants={rise} custom={1} className="display text-[clamp(2.75rem,6vw,4.25rem)]">
            Hear the book.
            <br />
            See every word.
          </motion.h1>
          <motion.p variants={rise} custom={2} className="mt-7 max-w-xl text-lg leading-relaxed text-fog sm:text-xl">
            ReadAlong plays your own audiobooks and lights up each sentence as the narrator reads it. The text is made on your device, or matched to
            an EPUB you already own.
          </motion.p>
          <motion.div variants={rise} custom={3} className="mt-9 flex flex-wrap items-center gap-3">
            <a
              className="flex h-13 items-center gap-2.5 rounded-full bg-pale px-7 text-base font-bold text-deep transition-transform duration-300 hover:scale-[1.03] active:scale-[0.98]"
              href="#download"
            >
              <DownloadIcon className="size-5" />
              Download free
            </a>
            <a className="flex h-13 items-center gap-2.5 rounded-full px-6 text-base font-semibold text-mist ring-1 ring-white/15 transition-colors hover:bg-white/6" href={REPO}>
              <GitHubIcon />
              View the source
            </a>
          </motion.div>
          <motion.p variants={rise} custom={4} className="mt-6 text-sm text-dim">
            No account. No uploads. Open source under GPLv3.
          </motion.p>
        </motion.div>

        <motion.div
          className="mx-auto w-[min(74vw,19.5rem)] lg:w-[21rem]"
          style={{ y: phoneY }}
          initial={{ opacity: 0, y: 60, scale: 0.96 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          transition={{ duration: 1.2, delay: 0.25, ease: [0.22, 1, 0.36, 1] }}
        >
          <Phone>
            <Clip name="hero" eager label="ReadAlong playing a book: the cover turns over into the text, and the sentence being read is highlighted." />
          </Phone>
        </motion.div>
      </div>
    </section>
  )
}
