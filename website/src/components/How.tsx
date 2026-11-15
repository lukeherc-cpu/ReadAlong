import { Reveal } from './ui'

const STEPS = [
  {
    title: 'Add your audiobooks',
    body: 'Tap + and choose a folder. ReadAlong shows what it found, such as “14 audiobooks found”, before anything is added. You can start listening right away.',
  },
  {
    title: 'Create the ReadAlong',
    body: 'A speech model of about 17 MB, Cactus Whistle, transcribes the recording on the device itself. Pause it whenever you like. It picks up where it stopped.',
  },
  {
    title: 'Tap the cover',
    body: 'The cover turns over into the text and the highlight follows the narrator. Text appears as each section finishes, so there is no waiting for the whole book.',
  },
]

export function How() {
  return (
    <section id="how" className="relative border-t border-white/6 bg-pitch py-24 sm:py-32" aria-labelledby="how-title">
      <div className="mx-auto max-w-6xl px-5 sm:px-8">
        <Reveal className="max-w-2xl">
          <h2 id="how-title" className="display text-[clamp(2.2rem,5vw,3.75rem)]">
            From a folder of audio to a book you can read.
          </h2>
        </Reveal>

        <ol className="mt-16 grid gap-12 md:grid-cols-3 md:gap-10">
          {STEPS.map((step, i) => (
            <li key={step.title}>
              <Reveal delay={i * 0.12}>
                <p className="font-book text-6xl leading-none text-pale/90 italic">{i + 1}</p>
                <div className="mt-6 h-px bg-gradient-to-r from-white/25 to-transparent" />
                <h3 className="mt-6 text-xl font-bold tracking-tight">{step.title}</h3>
                <p className="mt-3 leading-relaxed text-fog">{step.body}</p>
              </Reveal>
            </li>
          ))}
        </ol>

        <Reveal className="mt-16 rounded-3xl bg-slate p-7 ring-1 ring-white/6 sm:p-10 md:flex md:items-center md:gap-12">
          <h3 className="shrink-0 text-2xl font-extrabold tracking-tight md:max-w-xs">Already own the EPUB?</h3>
          <p className="mt-3 leading-relaxed text-fog md:mt-0">
            Put it in the book’s folder, or choose <strong className="font-semibold text-mist">Add my EPUB</strong> in the player. ReadAlong matches the published text to the
            narration and keeps its paragraphs, emphasis and fonts, so you follow the author’s words instead of the speech model’s. If a passage
            lands in the wrong place, you can set a sync point yourself.
          </p>
        </Reveal>
      </div>
    </section>
  )
}
