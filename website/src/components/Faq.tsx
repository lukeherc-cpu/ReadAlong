import { REPO } from '../links'
import { Logo, Reveal } from './ui'

const QUESTIONS = [
  ['Do I need an EPUB?', 'No. ReadAlong can create the text from the audiobook alone. An EPUB is useful when you want the original edition’s wording and formatting.'],
  ['Can I listen before the text is ready?', 'Yes. Adding a book makes its audio available straight away. Creating the text is a separate step, and finished sections can be read while later ones are still being transcribed.'],
  ['Is my audio sent anywhere?', 'No. Audio and EPUB text are not uploaded for transcription or matching. An internet connection is used to download the speech model, and if you choose to search for cover artwork online.'],
  ['What happens if I pause transcription?', 'Completed work stays saved. Resume from the player, the queue or the notification. A book set to transcribe only while charging waits for power instead of losing its progress.'],
  ['Will deleting a book erase my files?', 'No. Deleting a book removes its data from ReadAlong. Your original audio and EPUB files stay in their folders.'],
  ['Does it work with audiobook subscription services?', 'ReadAlong opens local, DRM-free files. The GitHub download and the desktop apps can also sign in to Audible and import the audiobooks you own as M4B files. Other services aren’t supported.'],
]

export function Faq() {
  return (
    <section id="questions" className="relative bg-paper pb-24 text-ink sm:pb-32" aria-labelledby="questions-title">
      <div className="mx-auto grid max-w-6xl gap-10 px-5 sm:px-8 lg:grid-cols-[0.8fr_1.2fr] lg:gap-16">
        <Reveal>
          <h2 id="questions-title" className="display text-[clamp(2.2rem,5vw,3.25rem)]">
            Common questions.
          </h2>
          <p className="mt-5 leading-relaxed text-ink/70">
            The full guide lives in the{' '}
            <a className="font-semibold text-blue underline decoration-blue/30 underline-offset-4 hover:decoration-blue" href={`${REPO}#readme`}>
              README on GitHub
            </a>
            .
          </p>
        </Reveal>
        <Reveal delay={0.1} className="divide-y divide-ink/10 border-y border-ink/10">
          {QUESTIONS.map(([question, answer]) => (
            <details key={question} className="group">
              <summary className="flex cursor-pointer list-none items-center justify-between gap-6 py-5 text-lg font-bold tracking-tight [&::-webkit-details-marker]:hidden">
                {question}
                <svg className="size-5 shrink-0 text-blue transition-transform duration-300 group-open:rotate-45" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" aria-hidden="true">
                  <path d="M12 5v14M5 12h14" />
                </svg>
              </summary>
              <p className="max-w-2xl pb-6 leading-relaxed text-ink/70">{answer}</p>
            </details>
          ))}
        </Reveal>
      </div>
    </section>
  )
}

export function Footer() {
  return (
    <footer className="bg-paper text-ink">
      <div className="mx-auto flex max-w-6xl flex-col gap-6 border-t border-ink/10 px-5 py-10 text-sm text-ink/60 sm:flex-row sm:items-center sm:px-8">
        <a href="#top" className="flex items-center gap-2.5 text-base font-extrabold tracking-tight text-ink">
          <Logo size={28} />
          ReadAlong
        </a>
        <p className="sm:ml-4">Open source under GPLv3, with the Needle Runtime Linking Exception.</p>
        <ul className="flex gap-6 font-semibold text-ink/80 sm:ml-auto">
          <li>
            <a className="hover:text-blue" href={REPO}>
              GitHub
            </a>
          </li>
          <li>
            <a className="hover:text-blue" href={`${REPO}/releases`}>
              Releases
            </a>
          </li>
          <li>
            <a className="hover:text-blue" href={`${REPO}/issues`}>
              Report a problem
            </a>
          </li>
        </ul>
      </div>
    </footer>
  )
}
