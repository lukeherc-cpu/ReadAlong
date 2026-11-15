import type { ReactNode } from 'react'
import { AudibleScene, DesktopScene, EpubScene, FontScene, MakeScene, ReadScene, SearchScene } from './Scenes'
import { Reveal } from './ui'

const FEATURES: { id: string; title: string; body: string; scene: ReactNode }[] = [
  { id: 'make', title: 'No ebook? Your phone writes the text.', body: 'Offline, on the phone, at about 16× real time. An 8-hour book takes around 30 minutes.', scene: <MakeScene /> },
  { id: 'epub', title: 'Have the ebook too?', body: 'Add the EPUB and ReadAlong matches the narration to the book’s exact text.', scene: <EpubScene /> },
  { id: 'search', title: 'Remember a line? Search it. Jump to it.', body: 'Search the book, tap a result, and the narrator picks up right there.', scene: <SearchScene /> },
  { id: 'read', title: 'Rather read?', body: 'Switch to a plain page. When you go back to listening, the audio picks up from there.', scene: <ReadScene /> },
  { id: 'fonts', title: 'Make it yours.', body: 'Any font, even your own.', scene: <FontScene /> },
  { id: 'audible', title: 'Bring your Audible books.', body: 'Sign in, pick the books you own, and they download as ordinary audio files.', scene: <AudibleScene /> },
  { id: 'desktop', title: 'Got a computer?', body: 'A full ReadAlong app for Windows, macOS and Linux. It transcribes up to 125× faster with a graphics card, and syncs with your phone.', scene: <DesktopScene /> },
]

export function Features() {
  return (
    <div id="features">
      {FEATURES.map((feature, i) => (
        <section key={feature.id} id={feature.id} className="py-20 sm:py-28" aria-labelledby={`${feature.id}-title`}>
          <div className="mx-auto grid max-w-6xl items-center gap-12 px-5 sm:px-8 lg:grid-cols-2 lg:gap-20">
            <Reveal className={i % 2 ? 'lg:order-2' : ''}>
              <h2 id={`${feature.id}-title`} className="display max-w-lg text-[clamp(2.1rem,4.4vw,3.25rem)]">{feature.title}</h2>
              <p className="mt-5 max-w-md text-xl leading-relaxed text-fog">{feature.body}</p>
            </Reveal>
            <Reveal delay={0.1}>{feature.scene}</Reveal>
          </div>
        </section>
      ))}
    </div>
  )
}
