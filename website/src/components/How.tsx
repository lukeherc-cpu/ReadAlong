import { useRef, useState } from 'react'
import { useMotionValueEvent, useScroll } from 'motion/react'
import { Reveal } from './ui'

const STEPS: [string, string[]][] = [
  ['Add your audiobooks', ['Tap + and choose a folder.', 'ReadAlong shows what it found before anything is added.', 'You can start listening right away.']],
  ['Create the ReadAlong', ['A 17 MB speech model transcribes the recording on the device itself.', 'Pause it whenever you like.', 'It picks up where it stopped.']],
  ['Tap the cover', ['The cover turns over into the text.', 'The highlight follows the narrator.', 'Text appears as each section finishes, so there is no waiting for the whole book.']],
]
const EPUB = [
  'Put it in the book’s folder, or choose Add my EPUB in the player.',
  'ReadAlong matches the published text to the narration and keeps its paragraphs, emphasis and fonts.',
  'If a passage lands in the wrong place, you can set a sync point yourself.',
]
const TOTAL = STEPS.reduce((n, [, lines]) => n + lines.length, 0) + EPUB.length

/** How it works, read the way the app reads a book: scrolling moves the highlight from one sentence to the next. */
export function How() {
  const ref = useRef<HTMLDivElement>(null)
  const [current, setCurrent] = useState(0)
  const { scrollYProgress } = useScroll({ target: ref, offset: ['start 0.7', 'end 0.55'] })
  useMotionValueEvent(scrollYProgress, 'change', (p) => setCurrent(Math.min(TOTAL - 1, Math.floor(p * TOTAL))))

  let index = 0
  const sentence = (text: string) => {
    const i = index++
    return (
      <span key={i} className={`transition-colors duration-500 ${i === current ? 'text-white' : 'text-[#aab2bb]'}`}>
        {text}{' '}
      </span>
    )
  }

  return (
    <section id="how" className="relative border-y border-white/8 bg-pitch py-24 sm:py-32" aria-labelledby="how-title">
      <div className="mx-auto max-w-6xl px-5 sm:px-8">
        <Reveal>
          <h2 id="how-title" className="display text-[clamp(2.2rem,5vw,3.5rem)]">How it works.</h2>
        </Reveal>
        <div ref={ref}>
          <ol className="mt-14 grid gap-12 md:grid-cols-3 md:gap-10">
            {STEPS.map(([title, lines], i) => (
              <li key={title}>
                <p className="font-book text-6xl leading-none text-pale italic">{i + 1}</p>
                <h3 className="mt-6 text-2xl font-extrabold tracking-tight">{title}</h3>
                <p className="mt-4 font-book text-xl leading-relaxed">{lines.map(sentence)}</p>
              </li>
            ))}
          </ol>
          <div className="mt-16 rounded-3xl bg-slate p-7 ring-1 ring-white/10 sm:p-10 md:flex md:gap-12">
            <h3 className="shrink-0 text-2xl font-extrabold tracking-tight md:max-w-xs">Already own the EPUB?</h3>
            <p className="mt-4 font-book text-xl leading-relaxed md:mt-0">{EPUB.map(sentence)}</p>
          </div>
        </div>
      </div>
    </section>
  )
}
