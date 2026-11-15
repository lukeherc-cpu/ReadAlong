import { useRef, useState } from 'react'
import { useMotionValueEvent, useScroll } from 'motion/react'
import { Reveal } from './ui'

// Moby-Dick, chapter 1 (public domain).
const SENTENCES = [
  'Call me Ishmael.',
  'Some years ago, never mind how long precisely, having little or no money in my purse, and nothing particular to interest me on shore, I thought I would sail about a little and see the watery part of the world.',
  'It is a way I have of driving off the spleen and regulating the circulation.',
  'Whenever I find myself growing grim about the mouth; whenever it is a damp, drizzly November in my soul; then, I account it high time to get to sea as soon as I can.',
]

/** The site's own read-along: scrolling plays the narrator, and the sentence being "read" lights up as it does in the app. */
export function Passage() {
  const ref = useRef<HTMLParagraphElement>(null)
  const [current, setCurrent] = useState(0)
  const { scrollYProgress } = useScroll({ target: ref, offset: ['start 0.75', 'end 0.4'] })
  useMotionValueEvent(scrollYProgress, 'change', (p) => setCurrent(Math.min(SENTENCES.length - 1, Math.floor(p * SENTENCES.length))))

  return (
    <section className="relative border-y border-white/6 bg-pitch py-24 sm:py-36" aria-labelledby="passage-title">
      <div className="mx-auto max-w-3xl px-5 sm:px-8">
        <Reveal>
          <h2 id="passage-title" className="mb-10 text-sm font-semibold text-pale sm:mb-14">
            Keep scrolling. This is what following along feels like.
          </h2>
        </Reveal>
        <p ref={ref} className="font-book text-[clamp(1.5rem,3.6vw,2.4rem)] leading-[1.5]">
          {SENTENCES.map((sentence, i) => (
            <span key={i} className={`transition-colors duration-500 ${i === current ? 'text-white' : 'text-white/28'}`}>
              {sentence}{' '}
            </span>
          ))}
        </p>
      </div>
    </section>
  )
}
