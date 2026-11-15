import { useRef } from 'react'
import { motion, useReducedMotion, useScroll, useTransform } from 'motion/react'
import { media } from '../links'
import { Reveal } from './ui'

const POINTS = [
  [
    'Transcribes on the graphics card',
    'Vulkan on Windows and Linux, Metal on a Mac, and the processor when there is no card. Books finish several times sooner, and you can choose a larger, more accurate speech model.',
  ],
  [
    'Links to your phone over home Wi-Fi',
    'Scan a QR code once. Books, finished ReadAlongs, covers, EPUBs, bookmarks and your listening position copy across directly, encrypted, with no account and no cloud.',
  ],
  [
    'Does the heavy lifting for the phone',
    'On a book with no text yet, choose to make its ReadAlong on your computer. The phone shows the progress and the text arrives when it is done.',
  ],
]

function Window({ children, title, light = false }: { children: React.ReactNode; title: string; light?: boolean }) {
  return (
    <div className={`overflow-hidden rounded-xl shadow-[0_40px_90px_-30px_rgba(10,25,40,0.55)] ring-1 ${light ? 'bg-paper ring-black/10' : 'bg-night ring-white/12'}`}>
      <div className={`flex h-9 items-center gap-1.5 px-3.5 ${light ? 'border-b border-black/6' : 'border-b border-white/8'}`}>
        {[0, 1, 2].map((dot) => (
          <span key={dot} className={`size-2.5 rounded-full ${light ? 'bg-black/12' : 'bg-white/15'}`} />
        ))}
        <span className={`ml-2 text-xs font-semibold ${light ? 'text-ink/60' : 'text-fog'}`}>{title}</span>
      </div>
      {children}
    </div>
  )
}

export function Desktop() {
  const ref = useRef<HTMLDivElement>(null)
  const reduce = useReducedMotion()
  const { scrollYProgress } = useScroll({ target: ref, offset: ['start end', 'end start'] })
  const back = useTransform(scrollYProgress, [0, 1], reduce ? [0, 0] : [40, -40])
  const front = useTransform(scrollYProgress, [0, 1], reduce ? [0, 0] : [110, -90])

  return (
    // The app's light theme: its page color and ink.
    <section id="desktop" className="relative rounded-t-[2.5rem] bg-paper py-24 text-ink sm:py-32" aria-labelledby="desktop-title">
      <div className="mx-auto max-w-6xl px-5 sm:px-8">
        <Reveal className="max-w-2xl">
          <p className="mb-5 text-sm font-semibold text-blue">ReadAlong for Windows, Linux and macOS</p>
          <h2 id="desktop-title" className="display text-[clamp(2.2rem,5vw,3.75rem)]">
            The same books, on the big screen.
          </h2>
          <p className="mt-5 text-lg leading-relaxed text-ink/70">
            The library, the player and the read-along reader as a desktop app. A book reads the same on both, because both run the same code.
          </p>
        </Reveal>

        <div ref={ref} className="relative mt-14 pb-[14%] sm:mt-20">
          <motion.div style={{ y: back }} className="w-[88%] sm:w-[78%]">
            <Window title="ReadAlong" light>
              <img src={media('desktop-library.webp')} alt="The desktop library in the light theme, with books grouped into Current, Not started and Finished." width={1360} height={860} loading="lazy" decoding="async" className="block w-full" />
            </Window>
          </motion.div>
          <motion.div style={{ y: front }} className="absolute right-0 bottom-0 w-[62%] sm:w-[50%]">
            <Window title="Transcription">
              <img src={media('desktop-transcription.webp')} alt="Desktop transcription settings: run on the graphics card or processor, the audiobook language, and a choice of speech models from Tiny to Large v3 Turbo." width={1360} height={1000} loading="lazy" decoding="async" className="block w-full" />
            </Window>
          </motion.div>
        </div>

        <ul className="mt-16 grid gap-10 md:grid-cols-3">
          {POINTS.map(([title, body], i) => (
            <li key={title}>
              <Reveal delay={i * 0.1}>
                <div className="h-px bg-gradient-to-r from-blue/50 to-transparent" />
                <h3 className="mt-6 text-xl font-bold tracking-tight">{title}</h3>
                <p className="mt-3 leading-relaxed text-ink/70">{body}</p>
              </Reveal>
            </li>
          ))}
        </ul>
      </div>
    </section>
  )
}
