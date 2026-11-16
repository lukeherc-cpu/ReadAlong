import type { ReactNode } from 'react'
import { BOOKS, type Book } from '../books'
import { media } from '../links'
import { Controls, Icon, ReaderUI, Screen, playerBackground, useLoop } from './kit'
import { Phone } from './ui'

const PP = 'It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.'

function PhoneScene({ children, innerRef }: { children: ReactNode; innerRef: React.Ref<HTMLDivElement> }) {
  return (
    <div ref={innerRef} className="mx-auto w-[min(70vw,17.5rem)]" aria-hidden="true">
      <Phone>{children}</Phone>
    </div>
  )
}

/** A fingertip landing on the screen. */
function Touch({ at, t, x, y }: { at: number; t: number; x: string; y: string }) {
  const p = (t - at) / 0.5
  if (p < 0 || p > 1) return null
  return <div className="absolute rounded-full bg-white" style={{ left: x, top: y, width: '3.4em', height: '3.4em', transform: `translate(-50%,-50%) scale(${0.5 + p * 0.9})`, opacity: 0.5 * (1 - p) }} />
}

/** The phone writing a book's text: "No text yet", a tap on Make a ReadAlong, then the words arriving. */
export function MakeScene() {
  const [ref, t] = useLoop<HTMLDivElement>(11, 7)
  const book = BOOKS.pride
  const made = t >= 1.9
  const typed = PP.slice(0, Math.floor(Math.min(1, Math.max(0, (t - 2.3) / 6.2)) * PP.length))
  const pct = Math.max(1, Math.round(Math.min(1, (t - 1.9) / 8) * 7))
  return (
    <PhoneScene innerRef={ref}>
      <Screen style={{ background: playerBackground(book) }}>
        <div className="mx-auto rounded-full" style={{ width: '2.6em', height: '.3em', background: 'rgba(255,255,255,.45)', marginTop: '1.5em' }} />
        <div style={{ padding: '1.5em 1.7em 0' }}>
          <div className="relative aspect-square overflow-hidden" style={{ borderRadius: '1.1em', background: book.theme.card, padding: '1.5em' }}>
            {!made ? (
              <div className="text-center" style={{ paddingTop: '2.2em' }}>
                <div style={{ fontSize: '1.5em', fontWeight: 800 }}>No text yet</div>
                <div style={{ fontSize: '1em', marginTop: '.5em', color: 'rgba(255,255,255,.85)' }}>Make one on this phone, offline.</div>
                <div className="mx-auto grid place-items-center rounded-full" style={{ marginTop: '1.6em', width: '14em', height: '3.1em', background: book.theme.cream, color: '#2A2220', fontWeight: 700, fontSize: '1.05em' }}>Make a ReadAlong</div>
                <div className="mx-auto grid place-items-center rounded-full" style={{ marginTop: '.8em', width: '14.7em', height: '3em', border: `.12em solid ${book.theme.cream}`, fontWeight: 600 }}>Add my EPUB</div>
              </div>
            ) : (
              <>
                <div className="flex justify-between" style={{ fontWeight: 700, fontSize: '.95em' }}><span>Making ReadAlong</span><span style={{ color: book.theme.cream }}>{pct}%</span></div>
                <div className="rounded-full" style={{ height: '.3em', background: 'rgba(255,255,255,.2)', margin: '.7em 0 1.1em' }}>
                  <div className="h-full rounded-full" style={{ width: `${pct}%`, background: book.theme.cream }} />
                </div>
                <div className="font-book" style={{ fontSize: '1.32em', lineHeight: 1.55 }}>
                  {typed}<span style={{ color: book.theme.cream, opacity: Math.floor(t * 3) % 2 }}>▍</span>
                </div>
              </>
            )}
            <Touch at={1.4} t={t} x="50%" y="52%" />
          </div>
          <Controls book={book} playing progress={0.04} />
        </div>
      </Screen>
    </PhoneScene>
  )
}

const HEARD = ['it is a truth universally acknowledged', 'that a single man in possession of a good fortune', 'must be in want of a wife']
const PRINTED = ['It is a truth universally acknowledged,', 'that a single man in possession of a good fortune,', 'must be in want of a wife.']

/** What the speech model heard, above the same words as the EPUB prints them, lighting up together. */
export function EpubScene() {
  const [ref, t] = useLoop<HTMLDivElement>(6.6, 2.5)
  const active = Math.min(2, Math.floor(t / 2.2))
  const lit = (i: number) => ({ color: i === active ? '#fff' : 'rgba(255,255,255,.62)', fontWeight: i === active ? 600 : 400, transition: 'color 300ms' })
  return (
    <div ref={ref} className="mx-auto max-w-md space-y-4" aria-hidden="true">
      <div className="rounded-3xl bg-slate p-6 ring-1 ring-white/10 sm:p-7">
        <p className="text-sm font-bold text-pale">Heard in the audio</p>
        <p className="mt-3 text-lg leading-relaxed">{HEARD.map((s, i) => <span key={i} style={lit(i)}>{s} </span>)}</p>
      </div>
      <div className="rounded-3xl p-6 ring-1 ring-white/10 sm:p-7" style={{ background: BOOKS.pride.theme.page }}>
        <p className="text-sm font-bold" style={{ color: BOOKS.pride.theme.cream }}>In your EPUB</p>
        <p className="mt-4 text-center font-book text-base italic text-white/85">Chapter I</p>
        <p className="mt-3 font-book text-xl leading-relaxed">{PRINTED.map((s, i) => <span key={i} style={lit(i)}>{s} </span>)}</p>
      </div>
    </div>
  )
}

const QUERY = 'perfectly normal'
const ELSEWHERE: Book = {
  ...BOOKS.potter,
  lines: ['Mr. Dursley was the director of a firm called Grunnings, which made drills.', 'He was a big, beefy man with hardly any neck,', 'although he did have a very large mustache.'],
  more: [],
}

/** Search in Harry Potter: type a half-remembered phrase, tap the result, and the reader jumps to that line and reads on. */
export function SearchScene() {
  const [ref, t] = useLoop<HTMLDivElement>(11.5, 8)
  const book = BOOKS.potter
  const searching = t >= 1 && t < 5.1
  const jumped = t >= 5.1
  const typed = QUERY.slice(0, Math.floor(Math.min(1, Math.max(0, (t - 1.5) / 1.7)) * QUERY.length))
  const found = t >= 3.3
  return (
    <PhoneScene innerRef={ref}>
      <ReaderUI book={jumped ? book : ELSEWHERE} active={jumped ? (t > 8.3 ? 2 : 1) : 1}>
        <div className="absolute inset-0" style={{ background: book.theme.page, opacity: searching ? 1 : 0, transition: 'opacity 350ms', pointerEvents: 'none' }}>
          <div className="flex items-center" style={{ margin: '1.2em 1.3em 0', gap: '.8em' }}>
            <Icon name="back" />
            <div className="flex flex-1 items-center rounded-full" style={{ height: '2.9em', padding: '0 1.1em', border: `.12em solid ${book.theme.cream}`, fontSize: '1.05em', fontWeight: 600 }}>
              {typed || <span style={{ color: 'rgba(255,255,255,.7)' }}>Search for a word</span>}
              <span style={{ width: '.12em', height: '1.3em', background: book.theme.cream, marginLeft: '.1em', opacity: Math.floor(t * 3) % 2 }} />
              <span className="ml-auto"><Icon name="close" size="1.2em" /></span>
            </div>
          </div>
          <div style={{ margin: '1.6em 1.6em 0', opacity: found ? 1 : 0, transition: 'opacity 300ms' }}>
            <div style={{ fontSize: '.85em', fontWeight: 700, color: book.theme.cream }}>1 result</div>
            <div className="relative" style={{ marginTop: '.7em', padding: '1.1em 1.2em', borderRadius: '1em', background: 'rgba(255,255,255,.1)' }}>
              <div style={{ fontSize: '.8em', fontWeight: 700, color: 'rgba(255,255,255,.85)' }}>Chapter 1 – The Boy Who Lived</div>
              <div className="font-book" style={{ fontSize: '1.2em', lineHeight: 1.5, marginTop: '.4em' }}>
                …were proud to say that they were <b style={{ color: book.theme.cream }}>perfectly normal</b>, thank you very much.
              </div>
              <Touch at={4.4} t={t} x="50%" y="60%" />
            </div>
          </div>
        </div>
      </ReaderUI>
    </PhoneScene>
  )
}

/** The switch at the top of the reader: listening with the highlight, then a plain page for reading. */
export function ReadScene() {
  const [ref, t] = useLoop<HTMLDivElement>(9, 6)
  const read = t >= 4.5
  return (
    <PhoneScene innerRef={ref}>
      <ReaderUI book={BOOKS.hobbit} active={Math.min(3, Math.floor(t / 1.3))} mode={read ? 'read' : 'listen'}>
        <Touch at={4.2} t={t} x="44%" y="2.2em" />
      </ReaderUI>
    </PhoneScene>
  )
}

const FONTS: [string, string][] = [
  ['Literata', 'var(--font-book)'],
  ['Lora', 'Lora, serif'],
  ['Merriweather', 'Merriweather, serif'],
  ['EB Garamond', '"EB Garamond", serif'],
  ['Your own font', 'Caveat, cursive'],
]

/** The same page in one reading font after another. */
export function FontScene() {
  const [ref, t] = useLoop<HTMLDivElement>(FONTS.length * 2, 0.5)
  const [name, family] = FONTS[Math.floor(t / 2) % FONTS.length]
  return (
    <PhoneScene innerRef={ref}>
      <ReaderUI book={BOOKS.hobbit} active={0} font={family}>
        <div className="absolute inset-x-0 flex justify-center" style={{ bottom: '7.4em' }}>
          <span className="rounded-full" style={{ padding: '.5em 1.2em', background: BOOKS.hobbit.theme.cream, color: '#2A2220', fontWeight: 700, fontSize: '.95em' }}>{name}</span>
        </div>
      </ReaderUI>
    </PhoneScene>
  )
}

const SHELF: [string, string, string][] = [['dune', 'Dune', 'Frank Herbert'], ['hobbit', 'The Hobbit', 'J.R.R. Tolkien'], ['1984', '1984', 'George Orwell'], ['hunger', 'The Hunger Games', 'Suzanne Collins']]

/** An Audible library, each book downloading in turn and landing in ReadAlong. */
export function AudibleScene() {
  const [ref, t] = useLoop<HTMLDivElement>(9, 7)
  return (
    <div ref={ref} className="mx-auto max-w-md overflow-hidden rounded-3xl bg-slate ring-1 ring-white/10" aria-hidden="true">
      <div className="flex items-baseline gap-3 border-b border-white/10 px-6 py-5">
        <span className="text-2xl font-bold tracking-tight text-[#F8991C]">audible</span>
        <span className="ml-auto text-sm font-semibold text-fog">Your library</span>
      </div>
      {SHELF.map(([cover, title, author], i) => {
        const start = 1 + i * 0.9
        const p = Math.min(1, Math.max(0, (t - start) / 2.6))
        return (
          <div key={cover} className="flex items-center gap-4 border-t border-white/8 px-6 py-4 first:border-t-0">
            <img src={media(`cover-${cover}.webp`)} alt="" width={56} height={56} loading="lazy" className="size-14 rounded-lg object-cover" />
            <div className="min-w-0 flex-1">
              <div className="truncate font-bold">{title}</div>
              <div className="truncate text-sm text-fog">{author}</div>
              <div className="mt-2 h-1 overflow-hidden rounded-full bg-white/12" style={{ opacity: p > 0 && p < 1 ? 1 : 0 }}>
                <div className="h-full bg-pale" style={{ width: `${p * 100}%` }} />
              </div>
            </div>
            <div className="w-24 text-right text-sm font-bold text-pale">{p >= 1 ? 'In library' : p > 0 ? `${Math.round(p * 100)}%` : 'Download'}</div>
          </div>
        )
      })}
    </div>
  )
}

/** The desktop app: its library, with the transcription settings in front. */
export function DesktopScene() {
  const frame = 'overflow-hidden rounded-xl shadow-[0_30px_70px_-25px_rgba(0,0,0,.9)] ring-1'
  const bar = (title: string, light: boolean) => (
    <div className={`flex h-7 items-center gap-1.5 px-3 ${light ? 'bg-paper' : 'bg-night'}`}>
      {[0, 1, 2].map((dot) => <span key={dot} className={`size-2 rounded-full ${light ? 'bg-black/20' : 'bg-white/25'}`} />)}
      <span className={`ml-2 text-[0.7rem] font-semibold ${light ? 'text-ink' : 'text-fog'}`}>{title}</span>
    </div>
  )
  return (
    <div className="relative pb-[16%]">
      <div className={`${frame} w-[92%] ring-white/10`}>
        {bar('ReadAlong', true)}
        <img src={media('desktop-library.webp')} alt="The desktop library, a grid of audiobook covers grouped into Current, Not started and Finished." width={1360} height={860} loading="lazy" decoding="async" className="block w-full" />
      </div>
      <div className={`${frame} absolute right-0 bottom-0 w-[58%] ring-white/20`}>
        {bar('Transcription', false)}
        <img src={media('desktop-transcription.webp')} alt="Desktop transcription settings, with a choice of graphics card or processor and of speech models." width={1360} height={1000} loading="lazy" decoding="async" className="block w-full" />
      </div>
    </div>
  )
}
