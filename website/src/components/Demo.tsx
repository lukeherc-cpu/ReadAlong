import { useCallback, useEffect, useRef, useState } from 'react'
import { BOOKS } from '../books'
import { Controls, FlipCover, Icon, Narration, PlayerUI, ReaderUI, Screen, Tap, playerBackground } from './kit'
import { Phone, Reveal } from './ui'

const BOOK = BOOKS.pride
const { src: SRC, cues: CUES, length: LENGTH } = BOOK.clip!

const clock = (seconds: number) => `0:${String(Math.floor(seconds)).padStart(2, '0')}`

/** Plays the Pride and Prejudice clip and reports where the narrator is. */
function useNarrator() {
  const audio = useRef<HTMLAudioElement | null>(null)
  const [playing, setPlaying] = useState(false)
  const [time, setTime] = useState(0)

  const get = useCallback(() => {
    if (!audio.current) {
      const el = new Audio(SRC)
      el.addEventListener('ended', () => { setPlaying(false); setTime(0); el.currentTime = 0 })
      el.addEventListener('pause', () => setPlaying(false))
      el.addEventListener('play', () => setPlaying(true))
      audio.current = el
    }
    return audio.current
  }, [])

  useEffect(() => {
    if (!playing) return
    let frame = 0
    const tick = () => { setTime(audio.current?.currentTime ?? 0); frame = requestAnimationFrame(tick) }
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [playing])

  useEffect(() => () => audio.current?.pause(), [])

  const seek = useCallback((seconds: number, play = false) => {
    const el = get()
    const to = Math.min(LENGTH - 0.05, Math.max(0, seconds))
    el.currentTime = to
    setTime(to)
    if (play) el.play().catch(() => {})
  }, [get])

  return {
    playing,
    time,
    active: CUES.reduce((found, cue, i) => (time >= cue ? i : found), 0),
    toggle: () => { const el = get(); if (el.paused) el.play().catch(() => {}); else el.pause() },
    skip: (by: number) => seek((audio.current?.currentTime ?? 0) + by),
    seekTo: (fraction: number) => seek(fraction * LENGTH),
    line: (i: number) => seek(CUES[i], true),
  }
}

type Narrator = ReturnType<typeof useNarrator>

function PhoneDemo({ n }: { n: Narrator }) {
  const [flipped, setFlipped] = useState(false)
  const [reader, setReader] = useState(false)
  return (
    <div className="mx-auto w-[min(78vw,20rem)]">
      <Phone>
        {reader ? (
          <ReaderUI book={BOOK} active={n.active} playing={n.playing} onBack={() => setReader(false)} onPlay={n.toggle} onLine={n.line} />
        ) : (
          <PlayerUI book={BOOK} flipped={flipped} active={n.active} playing={n.playing} progress={n.time / LENGTH} elapsed={clock(n.time)} total={clock(LENGTH)}
            onCover={() => setFlipped((f) => !f)} onPlay={n.toggle} onFullscreen={() => setReader(true)} onSkip={n.skip} onSeek={n.seekTo} onLine={n.line} />
        )}
      </Phone>
    </div>
  )
}

/** The desktop player: the cover and controls on the left, the text panel on the right, as in the app's wide layout. */
function DesktopDemo({ n }: { n: Narrator }) {
  const [flipped, setFlipped] = useState(false)
  const [reader, setReader] = useState(false)
  return (
    <div className="mx-auto max-w-4xl overflow-hidden rounded-xl bg-night shadow-[0_40px_90px_-30px_rgba(0,0,0,.9)] ring-1 ring-white/15">
      <div className="flex h-9 items-center gap-1.5 border-b border-white/10 px-3.5">
        {[0, 1, 2].map((dot) => <span key={dot} className="size-2.5 rounded-full bg-white/20" />)}
        <span className="ml-2 text-xs font-semibold text-fog">ReadAlong</span>
      </div>
      <div className="relative aspect-[16/10]">
        {reader ? (
          <ReaderUI cols={64} book={BOOK} active={n.active} playing={n.playing} onBack={() => setReader(false)} onPlay={n.toggle} onLine={n.line} />
        ) : (
          <Screen cols={64} style={{ background: playerBackground(BOOK) }}>
            <div className="flex items-center" style={{ padding: '1em 1.6em' }}>
              <Icon name="back" />
              <div className="ml-auto flex items-center" style={{ gap: '1.2em' }}>
                <Tap onTap={() => setReader(true)} label="Open the text full screen"><Icon name="fullscreen" /></Tap>
                <Icon name="more" />
              </div>
            </div>
            <div className="flex" style={{ gap: '2.6em', padding: '0 2.6em', height: '34.4em' }}>
              <div style={{ width: '19em', flex: 'none', fontSize: '.86em' }}>
                <FlipCover book={BOOK} flipped={flipped} active={n.active} onCover={() => setFlipped((f) => !f)} onFullscreen={() => setReader(true)} onLine={n.line} />
                <Controls book={BOOK} playing={n.playing} progress={n.time / LENGTH} elapsed={clock(n.time)} total={clock(LENGTH)} onPlay={n.toggle} onSkip={n.skip} onSeek={n.seekTo} />
              </div>
              <div className="relative flex-1 overflow-hidden" style={{ borderRadius: '1.1em', background: 'rgba(0,0,0,.22)' }}>
                <div className="font-book" style={{ padding: '2em 2.4em', fontSize: '1.3em', lineHeight: 1.7 }}>
                  <Narration book={BOOK} active={n.active} onLine={n.line} />
                </div>
              </div>
            </div>
          </Screen>
        )}
      </div>
    </div>
  )
}

export function Demo() {
  const n = useNarrator()
  const [device, setDevice] = useState<'phone' | 'desktop'>('phone')
  return (
    <section id="try" className="relative border-y border-white/8 bg-pitch py-24 sm:py-32" aria-labelledby="try-title">
      <div className="mx-auto max-w-6xl px-5 sm:px-8">
        <Reveal className="mx-auto max-w-2xl text-center">
          <h2 id="try-title" className="display text-[clamp(2.2rem,5vw,3.5rem)]">Try it.</h2>
          <p className="mt-5 text-lg leading-relaxed text-fog">
            Press play, tap the cover to turn it over, then open the text full screen. Tap any highlighted line to hear it again.
          </p>
          <div className="mt-8 inline-flex rounded-full bg-white/8 p-1 ring-1 ring-white/12" role="group" aria-label="Choose a device">
            {(['phone', 'desktop'] as const).map((d) => (
              <button key={d} type="button" aria-pressed={device === d} onClick={() => setDevice(d)}
                className={`h-10 cursor-pointer rounded-full px-6 text-sm font-bold transition-colors duration-300 ${device === d ? 'bg-pale text-deep' : 'text-white hover:bg-white/8'}`}>
                {d === 'phone' ? 'Phone' : 'Desktop'}
              </button>
            ))}
          </div>
        </Reveal>
        <Reveal className="mt-12" delay={0.1}>
          {device === 'phone' ? <PhoneDemo n={n} /> : <DesktopDemo n={n} />}
        </Reveal>
        <p className="mt-8 text-center text-sm text-fog">The opening line of Pride and Prejudice, by Jane Austen.</p>
      </div>
    </section>
  )
}
