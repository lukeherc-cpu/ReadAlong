import { useEffect, useRef, useState } from 'react'
import { motion, useInView } from 'motion/react'
import { Clip, Phone, Reveal, Shot } from './ui'

type Step = {
  id: string
  title: string
  body: string
  points: string[]
  /** A dark tone taken from the book on screen, the way the app colors its player. */
  tint: string
  clip?: string
  shot?: string
  alt: string
}

const STEPS: Step[] = [
  {
    id: 'library',
    title: 'Your shelf, built from your own files',
    body: 'Point ReadAlong at a folder holding one book or your whole collection. It looks inside, tells you what it found, and keeps the shelf up to date when you add more.',
    points: ['MP3, M4B, M4A, FLAC, Opus, OGG, AAC and WAV', 'Chapter files and disc folders grouped into one book', 'Search, sort, and switch between covers and a list'],
    tint: '#182a3b',
    clip: 'library',
    alt: 'Scrolling through the library, a grid of audiobook covers grouped by listening progress.',
  },
  {
    id: 'player',
    title: 'A player that dresses for the book',
    body: 'The player takes its colors from the cover, so every book feels like its own place. The controls are the ones you expect, and audio carries on with the screen off.',
    points: ['Speed, chapters, bookmarks and a sleep timer', 'Rewind and forward by the seconds you choose', 'Mini player and notification controls'],
    tint: '#4a1c14',
    shot: 'player',
    alt: 'The player showing a book cover, the chapter, a progress slider and playback controls, colored to match the cover.',
  },
  {
    id: 'flip',
    title: 'Tap the cover and it turns into the page',
    body: 'The cover flips over to show the text, with the sentence being read picked out as the narrator goes. Flip it back whenever you want the artwork again.',
    points: ['The highlight moves with the narration', 'A compact preview that stays inside the player'],
    tint: '#4a1626',
    clip: 'flip',
    alt: 'Tapping the cover in the player flips it over to reveal the text, with the current sentence highlighted.',
  },
  {
    id: 'reader',
    title: 'Open it up and read along full screen',
    body: 'Expand the preview into a reader. Tap any sentence to listen from there. Scroll ahead or back to browse, and one button returns you to the narration.',
    points: ['Tap a sentence to hear it', 'Previous and next sentence controls', 'Font, size, spacing, margins, and light, dark or sepia'],
    tint: '#4a1626',
    clip: 'reader',
    alt: 'The full-screen reader, where a highlight moves from sentence to sentence as the audiobook plays.',
  },
  {
    id: 'search',
    title: 'Find the line you half remember',
    body: 'Search the text for a word or phrase, open a result to see it in context, and start listening from that passage.',
    points: ['Search across the whole book', 'Jump by chapter from the contents'],
    tint: '#3a3817',
    clip: 'search',
    alt: 'Searching the book text for a phrase and jumping to the matching passage.',
  },
  {
    id: 'readonly',
    title: 'Or put the headphones down and read',
    body: 'A switch at the top of the reader turns it into a plain reading page with no highlight and no controls. Your place carries over both ways, and each mode keeps its own look.',
    points: ['Switch between listening and reading at the same passage', 'With your own EPUB, read before the audio is matched'],
    tint: '#2a2f36',
    clip: 'readonly',
    alt: 'Switching the reader between listening with the text and reading without audio.',
  },
  {
    id: 'queue',
    title: 'Line up the books you want text for',
    body: 'Transcription runs on the device, one book at a time, and the next starts by itself. Keep listening while it works. Finished sections are readable straight away.',
    points: ['Pause, resume and reorder', 'Only transcribe while charging, if you prefer', 'Progress is saved along the way'],
    tint: '#182a3b',
    shot: 'queue',
    alt: 'The transcription queue, showing one book being transcribed at 42 percent and two more waiting.',
  },
]

function Screen({ step, active }: { step: Step; active?: boolean }) {
  return step.clip ? <Clip name={step.clip} label={step.alt} active={active} /> : <Shot name={step.shot!} alt={step.alt} />
}

function StepCopy({ step, index, onActive }: { step: Step; index: number; onActive: (i: number) => void }) {
  const ref = useRef<HTMLDivElement>(null)
  // The step crossing the middle of the viewport is the one on the phone.
  const centred = useInView(ref, { margin: '-50% 0px -50% 0px' })
  useEffect(() => {
    if (centred) onActive(index)
  }, [centred, index, onActive])

  return (
    <div ref={ref} className="flex flex-col justify-center py-14 lg:min-h-[80vh] lg:py-0">
      <Reveal>
        <p className="mb-4 font-book text-lg text-pale italic">
          {index + 1} <span className="text-dim">of {STEPS.length}</span>
        </p>
        <h3 className="display max-w-md text-[clamp(1.9rem,3.6vw,2.75rem)]">{step.title}</h3>
        <p className="mt-5 max-w-md text-lg leading-relaxed text-fog">{step.body}</p>
        <ul className="mt-6 max-w-md space-y-2.5 text-[0.95rem] text-fog">
          {step.points.map((point) => (
            <li key={point} className="flex gap-3">
              <span aria-hidden="true" className="mt-[0.7em] h-px w-4 shrink-0 bg-pale/60" />
              {point}
            </li>
          ))}
        </ul>
      </Reveal>
      {/* Below the desktop layout each step carries its own phone. */}
      <Reveal className="mx-auto mt-10 w-[min(70vw,17rem)] lg:hidden" delay={0.1}>
        <Phone>
          <Screen step={step} />
        </Phone>
      </Reveal>
    </div>
  )
}

export function Tour() {
  const [active, setActive] = useState(0)

  return (
    <section id="features" className="relative py-24 sm:py-32" aria-labelledby="features-title">
      <div className="mx-auto max-w-6xl px-5 sm:px-8">
        <Reveal className="max-w-2xl">
          <h2 id="features-title" className="display text-[clamp(2.2rem,5vw,3.75rem)]">
            One app for the listening and the reading.
          </h2>
          <p className="mt-5 text-lg leading-relaxed text-fog">Everything below is recorded from the app itself.</p>
        </Reveal>

        <div className="mt-6 grid lg:mt-0 lg:grid-cols-2 lg:gap-16">
          <div>
            {STEPS.map((step, i) => (
              <StepCopy key={step.id} step={step} index={i} onActive={setActive} />
            ))}
          </div>

          <div className="hidden lg:block" aria-hidden="true">
            <div className="sticky top-0 flex h-screen items-center justify-center">
              {/* A wash of the current book's color behind the phone. */}
              <motion.div
                className="absolute size-[34rem] rounded-full opacity-80 blur-[90px]"
                animate={{ backgroundColor: STEPS[active].tint }}
                transition={{ duration: 0.9 }}
              />
              <div className="relative w-[min(19.5rem,38vh)]">
                <Phone>
                  {STEPS.map((step, i) => (
                    <motion.div
                      key={step.id}
                      className="absolute inset-0"
                      initial={false}
                      animate={{ opacity: i === active ? 1 : 0, scale: i === active ? 1 : 1.04 }}
                      transition={{ duration: 0.6, ease: [0.22, 1, 0.36, 1] }}
                    >
                      <Screen step={step} active={i === active} />
                    </motion.div>
                  ))}
                </Phone>
                <div className="absolute top-1/2 -right-10 flex -translate-y-1/2 flex-col gap-2.5">
                  {STEPS.map((step, i) => (
                    <span key={step.id} className={`w-1 rounded-full transition-all duration-500 ${i === active ? 'h-7 bg-pale' : 'h-2 bg-white/20'}`} />
                  ))}
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}
