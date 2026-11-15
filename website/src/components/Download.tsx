import { useEffect, useState } from 'react'
import { DOWNLOADS, KOFI, RELEASE } from '../links'
import { DownloadIcon, Reveal } from './ui'

type Platform = 'android' | 'windows' | 'linux' | 'mac'

const PLATFORMS: { id: Platform; name: string; needs: string; primary: [string, string]; secondary?: [string, string]; note: string }[] = [
  {
    id: 'android',
    name: 'Android',
    needs: 'Android 8.0 or later, ARM64',
    primary: ['Get the APK', DOWNLOADS.android],
    note: 'Open the release on your phone, download the .apk and allow the install when Android asks.',
  },
  {
    id: 'windows',
    name: 'Windows',
    needs: 'Windows 10 or 11',
    primary: ['Installer (.msi)', DOWNLOADS.windowsMsi],
    secondary: ['Portable zip', DOWNLOADS.windowsZip],
    note: 'If Windows says it protected your PC, choose More info, then Run anyway.',
  },
  {
    id: 'mac',
    name: 'macOS',
    needs: 'macOS 12 or later',
    primary: ['Apple silicon (.dmg)', DOWNLOADS.macArm],
    secondary: ['Intel (.dmg)', DOWNLOADS.macIntel],
    note: 'The app isn’t notarized. The first time, allow it under Privacy & Security with Open Anyway.',
  },
  {
    id: 'linux',
    name: 'Linux',
    needs: 'x86-64, Flatpak or tarball',
    primary: ['Flatpak', DOWNLOADS.linuxFlatpak],
    secondary: ['Portable tarball', DOWNLOADS.linuxTar],
    note: 'The Flatpak needs Flathub set up for the GNOME runtime it uses.',
  },
]

function detect(): Platform | null {
  const ua = navigator.userAgent
  if (/Android/i.test(ua)) return 'android'
  if (/Windows/i.test(ua)) return 'windows'
  if (/Mac OS X|Macintosh/i.test(ua) && !/iPhone|iPad/i.test(ua)) return 'mac'
  if (/Linux|X11/i.test(ua)) return 'linux'
  return null
}

export function Download() {
  const [mine, setMine] = useState<Platform | null>(null)
  useEffect(() => setMine(detect()), [])

  return (
    <section id="download" className="relative bg-paper pb-24 text-ink sm:pb-32" aria-labelledby="download-title">
      <div className="mx-auto max-w-6xl px-5 sm:px-8">
        <div className="rounded-[2rem] bg-night px-6 py-14 text-mist sm:px-12 sm:py-20">
          <Reveal className="max-w-2xl">
            <h2 id="download-title" className="display text-[clamp(2.2rem,5vw,3.75rem)]">
              Download ReadAlong.
            </h2>
            <p className="mt-5 text-lg leading-relaxed text-fog">
              Free, from GitHub Releases. Installing a new version over the old one keeps your library, your positions and your prepared text.
            </p>
          </Reveal>

          <ul className="mt-12 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {PLATFORMS.map((p, i) => {
              const here = p.id === mine
              return (
                <li key={p.id} className="flex">
                  <Reveal delay={i * 0.07} className={`flex w-full flex-col rounded-3xl p-6 ring-1 ${here ? 'bg-deep ring-pale/40' : 'bg-slate ring-white/6'}`}>
                    <div className="flex items-baseline justify-between gap-3">
                      <h3 className="text-2xl font-extrabold tracking-tight">{p.name}</h3>
                      {here && <span className="text-xs font-semibold text-pale">This device</span>}
                    </div>
                    <p className="mt-1 text-sm text-dim">{p.needs}</p>
                    <a
                      className={`mt-6 flex h-12 items-center justify-center gap-2 rounded-full text-sm font-bold transition-transform duration-300 hover:scale-[1.02] active:scale-[0.98] ${here ? 'bg-pale text-deep' : 'bg-white/10 text-white hover:bg-white/14'}`}
                      href={p.primary[1]}
                    >
                      <DownloadIcon />
                      {p.primary[0]}
                    </a>
                    {p.secondary && (
                      <a className="mt-3 text-center text-sm font-semibold text-fog underline decoration-white/25 underline-offset-4 transition-colors hover:text-white" href={p.secondary[1]}>
                        {p.secondary[0]}
                      </a>
                    )}
                    <p className="mt-auto pt-6 text-[0.82rem] leading-relaxed text-dim">{p.note}</p>
                  </Reveal>
                </li>
              )
            })}
          </ul>

          <Reveal className="mt-10 flex flex-wrap items-center gap-x-8 gap-y-3 text-sm text-fog">
            <a className="font-semibold text-pale underline decoration-pale/30 underline-offset-4 hover:decoration-pale" href={RELEASE}>
              All files and checksums on GitHub
            </a>
            <span>
              ReadAlong is free. If you’d like to support it, there is an optional{' '}
              <a className="font-semibold text-mist underline decoration-white/25 underline-offset-4 hover:decoration-white" href={KOFI}>
                tip jar on Ko-fi
              </a>
              . Tips don’t unlock anything.
            </span>
          </Reveal>
        </div>
      </div>
    </section>
  )
}
