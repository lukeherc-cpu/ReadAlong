import { useRef } from 'react'
import { motion, useReducedMotion, useScroll, useTransform } from 'motion/react'
import { Phone, Reveal, Shot } from './ui'

// The app's ten accent colors (AccentPalettes in Theme.kt).
const ACCENTS = [
  ['Blue', '#496B8A'],
  ['Teal', '#287A78'],
  ['Green', '#477A52'],
  ['Amber', '#8A6B25'],
  ['Orange', '#A76535'],
  ['Coral', '#9A4F4A'],
  ['Rose', '#955376'],
  ['Purple', '#72558C'],
  ['Indigo', '#565F94'],
  ['Slate', '#666B73'],
]

const FONTS = ['Literata', 'Lora', 'Merriweather', 'EB Garamond', 'Libre Baskerville', 'Source Serif 4', 'Bitter', 'Open Sans', 'Lato', 'Montserrat', 'Jakarta']

const SHEETS = [
  ['chapters', 'Chapters', 'The chapter list, open over the player.'],
  ['speed', 'Speed', 'Playback speed choices from 0.5× upward.'],
  ['sleep', 'Sleep timer', 'Sleep timer choices from 5 to 60 minutes.'],
  ['bookmarks', 'Bookmarks', 'A saved bookmark with its chapter and time.'],
]

const card = 'rounded-3xl bg-slate ring-1 ring-white/6'

export function Details() {
  const pair = useRef<HTMLDivElement>(null)
  const reduce = useReducedMotion()
  const { scrollYProgress } = useScroll({ target: pair, offset: ['start end', 'end start'] })
  const near = useTransform(scrollYProgress, [0, 1], reduce ? [0, 0] : [50, -50])
  const far = useTransform(scrollYProgress, [0, 1], reduce ? [0, 0] : [-20, 20])

  return (
    <section className="relative py-24 sm:py-32" aria-labelledby="details-title">
      <div className="mx-auto max-w-6xl px-5 sm:px-8">
        <Reveal className="max-w-2xl">
          <h2 id="details-title" className="display text-[clamp(2.2rem,5vw,3.75rem)]">
            Yours, and nobody else’s business.
          </h2>
          <p className="mt-5 text-lg leading-relaxed text-fog">
            ReadAlong has no catalogue, no store and no sign-up. It works with the files you already have, and it keeps what it makes on your device.
          </p>
        </Reveal>

        <div className="mt-14 grid gap-5 lg:grid-cols-3">
          {[
            ['Nothing is uploaded', 'Audio and book text are processed on the device. Transcription and EPUB matching never send them anywhere.'],
            ['Works offline', 'Once the speech model has downloaded, listening, transcription and reading need no connection.'],
            ['No account, no per-book cost', 'There is nothing to sign in to and nothing to pay per book. The app is free and its source is open.'],
          ].map(([title, body], i) => (
            <Reveal key={title} delay={i * 0.08} className={`${card} p-7 sm:p-8`}>
              <h3 className="text-xl font-bold tracking-tight">{title}</h3>
              <p className="mt-3 leading-relaxed text-fog">{body}</p>
            </Reveal>
          ))}
        </div>

        <div className="mt-5 grid gap-5 lg:grid-cols-[1.15fr_0.85fr]">
          <Reveal className={`${card} overflow-hidden`}>
            <div className="p-7 sm:p-10">
              <h3 className="display text-3xl sm:text-4xl">Colors follow the cover</h3>
              <p className="mt-4 max-w-md leading-relaxed text-fog">
                The player and the text preview pick up the book’s own palette, as a blurred cover or a solid color drawn from it. Prefer one look
                everywhere? Choose an accent color instead.
              </p>
            </div>
            <div ref={pair} className="relative mx-auto flex h-[22rem] max-w-md justify-center gap-5 overflow-hidden px-6 sm:h-[26rem]">
              <motion.div className="w-1/2 max-w-[13rem] pt-10" style={{ y: near }}>
                <Phone>
                  <Shot name="player-cover-fourth-wing" alt="The player in warm gold tones matching the book’s cover." />
                </Phone>
              </motion.div>
              <motion.div className="w-1/2 max-w-[13rem] pt-4" style={{ y: far }}>
                <Phone>
                  <Shot name="player-text" alt="The text preview in deep blue tones matching a different book’s cover." />
                </Phone>
              </motion.div>
            </div>
          </Reveal>

          <div className="grid gap-5">
            <Reveal delay={0.08} className={`${card} p-7 sm:p-8`}>
              <h3 className="text-xl font-bold tracking-tight">Ten accent colors, light or dark</h3>
              <ul className="mt-5 flex flex-wrap gap-2.5" aria-label="Accent colors">
                {ACCENTS.map(([name, hex]) => (
                  <li key={name} title={name} className="size-7 rounded-full ring-1 ring-white/15" style={{ backgroundColor: hex }}>
                    <span className="sr-only">{name}</span>
                  </li>
                ))}
              </ul>
              <p className="mt-5 leading-relaxed text-fog">The color carries through the library, the selection highlights and the launcher icon.</p>
            </Reveal>
            <Reveal delay={0.16} className={`${card} p-7 sm:p-8`}>
              <h3 className="text-xl font-bold tracking-tight">Set the page the way you read</h3>
              <p className="mt-4 font-book text-[1.05rem] leading-loose text-fog">{FONTS.join(' · ')}</p>
              <p className="mt-4 leading-relaxed text-fog">Or keep the EPUB’s own fonts, or import a TTF or OTF file. Size, line spacing and margins are yours to set.</p>
            </Reveal>
          </div>
        </div>

        <ul className="mt-5 grid grid-cols-2 gap-5 lg:grid-cols-4">
          {SHEETS.map(([name, label, alt], i) => (
            <li key={name}>
              <Reveal delay={i * 0.07} className={`${card} overflow-hidden`}>
                <div className="relative mx-auto mt-6 aspect-[4/5] w-[78%] overflow-hidden rounded-t-[1.4rem] ring-1 ring-white/10">
                  <img className="absolute inset-x-0 top-[-31%] w-full" src={`${import.meta.env.BASE_URL}media/${name}.webp`} alt={alt} width={720} height={1460} loading="lazy" decoding="async" />
                </div>
                <p className="border-t border-white/6 px-5 py-4 text-sm font-semibold">{label}</p>
              </Reveal>
            </li>
          ))}
        </ul>
      </div>
    </section>
  )
}
