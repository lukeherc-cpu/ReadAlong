import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react'
import { useInView, useReducedMotion } from 'motion/react'
import type { Book } from '../books'

/** The app's own icons (res/drawable/ic_*.xml). */
const ICONS = {
  rewind: ['s', 'M8,5 L3,9 L8,13 M3,9 L12,9 C22,9 22,21 12,21 L7,21'],
  forward: ['s', 'M16,5 L21,9 L16,13 M21,9 L12,9 C2,9 2,21 12,21 L17,21'],
  speed: ['s', 'M4.2,17 A8.5,8.5 0 1 1 19.8,17 M12,14 L16,8.5'],
  sleep: ['s', 'M19,15 C11,17 6,10 10,3 C0,6 1,21 12,21 C16,21 19,19 21,16 Z'],
  bookmark: ['s', 'M6,3 L18,3 L18,21 L12,17 L6,21 Z'],
  chapters: ['s', 'M3,5 L4,5 M8,5 L21,5 M3,12 L4,12 M8,12 L21,12 M3,19 L4,19 M8,19 L21,19'],
  fullscreen: ['f', 'M3,3h6v2H5v4H3zM15,3h6v6h-2V5h-4zM3,15h2v4h4v2H3zM19,15h2v6h-6v-2h4z'],
  play: ['f', 'M8,4 L20,12 L8,20 Z'],
  pause: ['s', 'M7,5 L7,19 M17,5 L17,19'],
  back: ['s', 'M14,5 L7,12 L14,19 M7,12 L21,12'],
  search: ['s', 'M15,15 L21,21 M17,10 A7,7 0,1 0,3 10 A7,7 0,1 0,17 10'],
  more: ['s', 'M5,12 L5.1,12 M12,12 L12.1,12 M19,12 L19.1,12'],
  text: ['s', 'M2,19 L7.5,5 L13,19 M4.4,13.5 L10.6,13.5 M21,12 L21,19 M21,16 A2.75,2.75 0,1 1,15.5 16 A2.75,2.75 0,1 1,21 16'],
  prev: ['f', 'M6,6h2v12H6z M18,6v12l-8,-6z'],
  next: ['f', 'M16,6h2v12h-2z M6,6v12l8,-6z'],
  headphones: ['s', 'M4,14 L4,12 A8,8 0 0,1 20,12 L20,14 M4.5,14 h1 a1.5,1.5 0 0 1 1.5,1.5 v3 a1.5,1.5 0 0 1 -1.5,1.5 h-1 a1.5,1.5 0 0 1 -1.5,-1.5 v-3 a1.5,1.5 0 0 1 1.5,-1.5 Z M18.5,14 h1 a1.5,1.5 0 0 1 1.5,1.5 v3 a1.5,1.5 0 0 1 -1.5,1.5 h-1 a1.5,1.5 0 0 1 -1.5,-1.5 v-3 a1.5,1.5 0 0 1 1.5,-1.5 Z'],
  book: ['s', 'M12,7 C10,5 7,4.5 3,5 L3,18 C7,17.5 10,18 12,20 C14,18 17,17.5 21,18 L21,5 C17,4.5 14,5 12,7 Z M12,7 L12,20'],
  close: ['s', 'M6,6 L18,18 M18,6 L6,18'],
} as const
export type IconName = keyof typeof ICONS

export function Icon({ name, size = '1.5em', color = 'currentColor' }: { name: IconName; size?: string; color?: string }) {
  const [kind, d] = ICONS[name]
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} aria-hidden="true" style={{ flex: 'none', display: 'block' }}
      fill={kind === 'f' ? color : 'none'} stroke={kind === 's' ? color : 'none'} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <path d={d} />
    </svg>
  )
}

/**
 * An app screen that scales with its box: 1em is 1/`cols` of the width, so the same layout fills a phone or a window.
 */
export function Screen({ children, cols = 24, style, className = '' }: { children: ReactNode; cols?: number; style?: CSSProperties; className?: string }) {
  return (
    <div className={`absolute inset-0 overflow-hidden text-white select-none ${className}`} style={{ containerType: 'inline-size', ...style }}>
      <div className="absolute inset-0" style={{ fontSize: `${100 / cols}cqw`, lineHeight: 1.3 }}>
        {children}
      </div>
    </div>
  )
}

/** A control that is a real button when it does something, and plain decoration when it doesn't. */
export function Tap({ onTap, label, children, className = '', style }: { onTap?: () => void; label?: string; children: ReactNode; className?: string; style?: CSSProperties }) {
  if (!onTap) return <div className={className} style={style}>{children}</div>
  return (
    <button type="button" aria-label={label} onClick={(e) => { e.stopPropagation(); onTap() }} className={`cursor-pointer transition-transform duration-150 active:scale-95 ${className}`} style={style}>
      {children}
    </button>
  )
}

/** The narrated text: the sentence being read is white and the rest is dimmed, as in the app. */
export function Narration({ book, active, onLine, plain = false }: { book: Book; active: number; onLine?: (i: number) => void; plain?: boolean }) {
  return (
    <>
      <p>
        {book.lines.map((line, i) => (
          <span key={i} onClick={onLine ? (e) => { e.stopPropagation(); onLine(i) } : undefined}
            className={`transition-colors duration-300 ${onLine ? 'cursor-pointer' : ''}`}
            style={{ color: plain ? 'inherit' : i === active ? '#fff' : 'rgba(255,255,255,.62)', fontWeight: !plain && i === active ? 600 : 400 }}>
            {line}{' '}
          </span>
        ))}
      </p>
      {book.more.map((para, i) => (
        <p key={i} style={{ marginTop: '1em', color: plain ? 'inherit' : 'rgba(255,255,255,.62)' }}>{para}</p>
      ))}
    </>
  )
}

export type PlayerProps = {
  book: Book
  flipped: boolean
  active: number
  playing: boolean
  progress: number
  elapsed?: string
  total?: string
  onCover?: () => void
  onPlay?: () => void
  onFullscreen?: () => void
  onSkip?: (seconds: number) => void
  onSeek?: (fraction: number) => void
  onLine?: (i: number) => void
}

/** The cover, which turns over into the text card. */
export function FlipCover({ book, flipped, active, onCover, onFullscreen, onLine }: Pick<PlayerProps, 'book' | 'flipped' | 'active' | 'onCover' | 'onFullscreen' | 'onLine'>) {
  return (
    <div className="relative aspect-square w-full" style={{ perspective: '60em' }}>
      <div className="absolute inset-0 transition-transform duration-700 ease-[cubic-bezier(.42,0,.18,1)]" style={{ transformStyle: 'preserve-3d', transform: flipped ? 'rotateY(180deg)' : 'none' }}>
        <Tap onTap={onCover} label="Turn the cover over to show the text" className="absolute inset-0 block overflow-hidden" style={{ backfaceVisibility: 'hidden', borderRadius: '1.1em', boxShadow: '0 .8em 2em rgba(0,0,0,.35)' }}>
          <img src={book.cover} alt={`${book.title} cover`} className="h-full w-full object-cover" draggable={false} />
        </Tap>
        <div className="absolute inset-0 overflow-hidden" onClick={onCover}
          style={{ backfaceVisibility: 'hidden', transform: 'rotateY(180deg)', borderRadius: '1.1em', background: book.theme.card, cursor: onCover ? 'pointer' : undefined, boxShadow: '0 .8em 2em rgba(0,0,0,.35)' }}>
          <div className="font-book" style={{ padding: '2.6em 1.5em 1.5em', fontSize: '1.32em', lineHeight: 1.55 }}>
            <Narration book={book} active={active} onLine={onLine} />
          </div>
          <div className="absolute inset-x-0 bottom-0" style={{ height: '4em', background: `linear-gradient(transparent, ${book.theme.card})` }} />
          <Tap onTap={onFullscreen ? () => onFullscreen() : undefined} label="Open the text full screen" className="absolute" style={{ top: '.9em', right: '.9em', padding: '.3em' }}>
            <Icon name="fullscreen" size="1.5em" />
          </Tap>
        </div>
      </div>
    </div>
  )
}

/** Title, slider and transport controls. */
export function Controls({ book, playing, progress, elapsed, total, onPlay, onSkip, onSeek }: Omit<PlayerProps, 'flipped' | 'active'>) {
  const cream = book.theme.cream
  return (
    <div>
      <div style={{ fontSize: '1.7em', fontWeight: 800, letterSpacing: '-.02em', marginTop: '.75em' }} className="truncate">{book.title}</div>
      <div style={{ fontSize: '.95em', fontWeight: 600, color: 'rgba(255,255,255,.8)', marginTop: '.35em' }} className="truncate">{book.chapter}</div>
      <div role={onSeek ? 'slider' : undefined} aria-label={onSeek ? 'Position' : undefined} aria-valuenow={onSeek ? Math.round(progress * 100) : undefined}
        className="relative" style={{ height: '1.6em', marginTop: '1.3em', cursor: onSeek ? 'pointer' : undefined }}
        onClick={onSeek ? (e) => { const r = e.currentTarget.getBoundingClientRect(); onSeek(Math.min(1, Math.max(0, (e.clientX - r.left) / r.width))) } : undefined}>
        <div className="absolute inset-x-0 top-1/2 -translate-y-1/2 rounded-full" style={{ height: '.28em', background: 'rgba(255,255,255,.22)' }} />
        <div className="absolute left-0 top-1/2 -translate-y-1/2 rounded-full" style={{ height: '.28em', width: `${progress * 100}%`, background: cream }} />
        <div className="absolute top-1/2 rounded-full" style={{ width: '1em', height: '1em', left: `${progress * 100}%`, transform: 'translate(-50%,-50%)', background: cream }} />
      </div>
      <div className="flex justify-between" style={{ fontSize: '.78em', fontWeight: 700, color: 'rgba(255,255,255,.85)', marginTop: '.5em' }}>
        <span>{elapsed ?? '0:24'}</span><span>{book.left}</span><span>{total ?? book.total}</span>
      </div>
      <div className="flex items-center justify-evenly" style={{ marginTop: '1.6em' }}>
        <Tap onTap={onSkip ? () => onSkip(-20) : undefined} label="Back 20 seconds" className="flex flex-col items-center" style={{ fontSize: '.8em', fontWeight: 700, gap: '.2em' }}><Icon name="rewind" size="2.2em" />20</Tap>
        <Tap onTap={onPlay} label={playing ? 'Pause' : 'Play'} className="grid place-items-center rounded-full" style={{ width: '5em', height: '5em', background: cream }}>
          <Icon name={playing ? 'pause' : 'play'} size="2.1em" color="#2A2220" />
        </Tap>
        <Tap onTap={onSkip ? () => onSkip(30) : undefined} label="Forward 30 seconds" className="flex flex-col items-center" style={{ fontSize: '.8em', fontWeight: 700, gap: '.2em' }}><Icon name="forward" size="2.2em" />30</Tap>
      </div>
      <div className="flex justify-evenly" style={{ marginTop: '1.7em', fontSize: '.74em', fontWeight: 700 }}>
        {([['speed', '1.0×'], ['sleep', 'Sleep'], ['bookmark', 'Bookmark'], ['chapters', 'Chapters']] as const).map(([icon, label]) => (
          <div key={label} className="flex flex-col items-center" style={{ gap: '.45em' }}><Icon name={icon} size="2.1em" />{label}</div>
        ))}
      </div>
    </div>
  )
}

export const playerBackground = (book: Book) => `linear-gradient(${book.theme.tint} 0%, #14171a 78%)`

/** The phone's player screen. */
export function PlayerUI(props: PlayerProps) {
  return (
    <Screen style={{ background: playerBackground(props.book), transition: 'background 600ms' }}>
      <div className="mx-auto rounded-full" style={{ width: '2.6em', height: '.3em', background: 'rgba(255,255,255,.45)', marginTop: '1.5em' }} />
      <div className="absolute" style={{ top: '.9em', right: '1.4em' }}><Icon name="more" /></div>
      <div style={{ padding: '1.5em 1.7em 0' }}>
        <FlipCover {...props} />
        <Controls {...props} />
      </div>
    </Screen>
  )
}

export type ReaderProps = {
  book: Book
  active: number
  playing?: boolean
  mode?: 'listen' | 'read'
  font?: string
  cols?: number
  onBack?: () => void
  onPlay?: () => void
  onLine?: (i: number) => void
  onMode?: (mode: 'listen' | 'read') => void
  children?: ReactNode
}

/** The full-screen reader. In "read" mode it is a plain page with no highlight and no controls. */
export function ReaderUI({ book, active, playing = true, mode = 'listen', font, cols = 24, onBack, onPlay, onLine, onMode, children }: ReaderProps) {
  const read = mode === 'read'
  const ink = read ? '#1F2428' : '#fff'
  const wide = cols > 30
  return (
    <Screen cols={cols} style={{ background: read ? '#fff' : book.theme.page, color: ink, transition: 'background 500ms, color 500ms' }}>
      <div className="flex items-center" style={{ padding: wide ? '1em 1.6em' : '1.2em 1.3em', gap: '1.1em', color: ink }}>
        <Tap onTap={onBack} label="Close the full-screen reader"><Icon name="back" /></Tap>
        <Icon name="chapters" />
        <div className="mx-auto flex rounded-full" style={{ padding: '.25em', background: read ? 'rgba(0,0,0,.08)' : 'rgba(255,255,255,.14)' }}>
          {(['read', 'listen'] as const).map((m) => (
            <Tap key={m} onTap={onMode ? () => onMode(m) : undefined} label={m === 'read' ? 'Read without audio' : 'Listen and read'} className="grid place-items-center rounded-full transition-colors duration-300"
              style={{ width: '3em', height: '2em', background: mode === m ? (read ? '#1F2428' : book.theme.cream) : 'transparent' }}>
              <Icon name={m === 'read' ? 'book' : 'headphones'} size="1.25em" color={mode === m ? (read ? '#fff' : '#2A2220') : ink} />
            </Tap>
          ))}
        </div>
        <Icon name="search" />
        <Icon name="text" />
      </div>
      <div className="mx-auto" style={{ maxWidth: wide ? '34em' : undefined, padding: wide ? '1em 0 0' : '1.2em 1.9em 0', fontFamily: font ?? 'var(--font-book)', fontSize: wide ? '1.25em' : '1.5em', lineHeight: 1.65, transition: 'opacity 250ms' }}>
        <Narration book={book} active={active} onLine={read ? undefined : onLine} plain={read} />
      </div>
      {!read && (
        <div className="absolute inset-x-0 bottom-0 flex items-center justify-center" style={{ gap: wide ? '2.2em' : '1.5em', padding: '3em 0 1.6em', background: `linear-gradient(transparent, ${book.theme.page} 45%)` }}>
          <Icon name="prev" size="1.7em" />
          <span className="flex items-center" style={{ gap: '.2em', fontSize: '.8em', fontWeight: 700 }}><Icon name="rewind" size="2em" />5s</span>
          <Tap onTap={onPlay} label={playing ? 'Pause' : 'Play'} className="grid place-items-center rounded-full" style={{ width: '4em', height: '4em', background: book.theme.cream }}>
            <Icon name={playing ? 'pause' : 'play'} size="1.8em" color="#2A2220" />
          </Tap>
          <span className="flex items-center" style={{ gap: '.2em', fontSize: '.8em', fontWeight: 700 }}>5s<Icon name="forward" size="2em" /></span>
          <Icon name="next" size="1.7em" />
        </div>
      )}
      {children}
    </Screen>
  )
}

/**
 * A looping clock for the small animated scenes: seconds since the loop began, running only while the element is on screen.
 * Under reduced motion it stays at `still`, a moment that shows the feature without moving.
 */
export function useLoop<T extends Element>(period: number, still: number) {
  const ref = useRef<T>(null)
  const reduce = useReducedMotion()
  const inView = useInView(ref, { amount: 0.4 })
  const [t, setT] = useState(still)
  useEffect(() => {
    if (reduce || !inView) return
    let frame = 0
    let last = 0
    const began = performance.now()
    const tick = (now: number) => {
      if (now - last > 40) { last = now; setT(((now - began) / 1000) % period) }
      frame = requestAnimationFrame(tick)
    }
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [reduce, inView, period])
  return [ref, reduce ? still : t] as const
}
