import { useEffect, useState, type ReactNode } from 'react'
import { DOWNLOADS, LIBATION, LIBRISYNC, REPO } from '../links'
import { DownloadIcon, Logo } from './ui'

/**
 * The page the Play Store app's "Import from Audible" guide opens. The Play app itself never signs in to Audible
 * or links to a converter; this page, which we can change at any time, does.
 */
type Path = 'computer' | 'android' | 'other'

const TABS: { id: Path; title: string; blurb: string }[] = [
  { id: 'computer', title: 'ReadAlong for computer', blurb: 'Easiest. Books and transcripts come to your phone over Wi-Fi.' },
  { id: 'android', title: 'LibriSync on Android', blurb: 'No computer needed.' },
  { id: 'other', title: 'Another converter', blurb: 'Libation, or files you already have.' },
]

function Step({ n, title, children }: { n: number; title: ReactNode; children?: ReactNode }) {
  return (
    <li className="flex gap-4">
      <span className="grid size-8 flex-none place-items-center rounded-full bg-deep text-sm font-bold text-pale ring-1 ring-pale/25">{n}</span>
      <div className="pt-0.5">
        <p className="font-semibold text-white">{title}</p>
        {children && <div className="mt-1 text-[0.95rem] leading-relaxed text-dim">{children}</div>}
      </div>
    </li>
  )
}

function Button({ href, children, quiet = false }: { href: string; children: ReactNode; quiet?: boolean }) {
  return (
    <a
      href={href}
      className={`inline-flex h-11 items-center gap-2 rounded-full px-5 text-sm font-bold transition-transform duration-300 hover:scale-[1.02] active:scale-[0.98] ${quiet ? 'bg-white/10 text-white hover:bg-white/14' : 'bg-pale text-deep'}`}
    >
      <DownloadIcon />
      {children}
    </a>
  )
}

const B = ({ children }: { children: ReactNode }) => <b className="font-semibold text-fog">{children}</b>

function Computer() {
  return (
    <>
      <p className="text-fog">ReadAlong for computer signs in to Audible for you, downloads the books you own and makes their transcripts, which is much faster on a computer than on a phone. Then your phone copies them over Wi-Fi.</p>
      <div className="mt-6 flex flex-wrap gap-3">
        <Button href={DOWNLOADS.windowsMsi}>Windows</Button>
        <Button href={DOWNLOADS.macArm} quiet>Mac (Apple silicon)</Button>
        <Button href={DOWNLOADS.macIntel} quiet>Mac (Intel)</Button>
        <Button href={DOWNLOADS.linuxFlatpak} quiet>Linux</Button>
      </div>
      <ol className="mt-8 space-y-6">
        <Step n={1} title="Install ReadAlong on your computer and open it">If Windows or macOS asks, allow ReadAlong on private networks so your phone can reach it.</Step>
        <Step n={2} title={<>Choose <B>Import from Audible</B> and sign in</>}>Pick your Audible country, then sign in with your Amazon account. Your password goes only to Amazon.</Step>
        <Step n={3} title="Tick the books you want and press Download">They're saved in the Audible Audiobooks folder and transcribed one after another. You can leave it running.</Step>
        <Step n={4} title={<>Link your phone: <B>Settings › Phone › Link phone</B></>}>On your phone, open ReadAlong, go to <B>Settings › Link a computer</B> and scan the code. Keep both on the same Wi-Fi.</Step>
        <Step n={5} title="Tick the Audible Audiobooks folder to sync">Your phone copies the books, with their transcripts, whenever both are on the same Wi-Fi. Books you buy later come across on their own.</Step>
      </ol>
    </>
  )
}

function Android() {
  return (
    <>
      <p className="text-fog">LibriSync is a free, open-source Android app that downloads the Audible books you own as ordinary audio files. It isn't on the Play Store, so Android asks once before installing it.</p>
      <div className="mt-6 flex flex-wrap gap-3">
        <Button href={LIBRISYNC}>Get LibriSync</Button>
      </div>
      <ol className="mt-8 space-y-6">
        <Step n={1} title="Download the latest LibriSync">On the page that opens, under <B>Assets</B>, tap the file that ends in <B>.apk</B>.</Step>
        <Step n={2} title={<>Open the download and tap <B>Install</B></>}>If Android says <i>"For your security, your phone isn't allowed to install unknown apps from this source"</i>, tap <B>Settings</B>, turn on <B>Allow from this source</B>, then go back and tap <B>Install</B>.</Step>
        <Step n={3} title="Open LibriSync and sign in with your Amazon account">Your password goes only to Amazon.</Step>
        <Step n={4} title="Download the books you want">Wi-Fi is quicker. Larger books can take a few minutes each.</Step>
        <Step n={5} title={<>Save them as <B>M4B</B> in the <B>Audiobooks</B> folder</>}>Any folder works, but ReadAlong looks there first.</Step>
        <Step n={6} title="Go back to ReadAlong">It finds the new books, adds them and starts their transcripts. It keeps watching the folder, so books you get later show up on their own.</Step>
      </ol>
    </>
  )
}

function Other() {
  return (
    <>
      <p className="text-fog">Any tool that saves your Audible books as M4B or MP3 works. Libation is free and runs on Windows, Mac and Linux.</p>
      <div className="mt-6 flex flex-wrap gap-3">
        <Button href={LIBATION}>Get Libation</Button>
      </div>
      <ol className="mt-8 space-y-6">
        <Step n={1} title="Install Libation and add your Audible account">Sign in with your Amazon account when it asks.</Step>
        <Step n={2} title="Let it import your library, then choose Liberate">In its settings, choose <B>M4B</B> with chapters. That keeps chapters and covers.</Step>
        <Step n={3} title="Copy the files to your phone">With a USB cable into the <B>Audiobooks</B> folder, or through Google Drive or any cloud folder.</Step>
        <Step n={4} title="Open ReadAlong and continue the Audible guide">It finds the books, adds them, and keeps watching the folder for more.</Step>
      </ol>
    </>
  )
}

function detect(): Path {
  return /Android/i.test(navigator.userAgent) ? 'android' : 'computer'
}

export default function AudibleGuide() {
  const [path, setPath] = useState<Path>('computer')
  useEffect(() => {
    const fromHash = location.hash.slice(1) as Path
    setPath(TABS.some((t) => t.id === fromHash) ? fromHash : detect())
  }, [])
  const choose = (p: Path) => {
    setPath(p)
    history.replaceState(null, '', `#${p}`)
  }

  return (
    <div className="min-h-screen bg-night text-mist">
      <header className="mx-auto flex h-16 max-w-3xl items-center px-5 sm:px-8">
        <a href="../" className="flex items-center gap-2.5 text-[1.05rem] font-extrabold tracking-tight">
          <Logo size={30} />
          ReadAlong
        </a>
      </header>

      <main className="mx-auto max-w-3xl px-5 pb-20 sm:px-8">
        <h1 className="display mt-8 text-[clamp(2rem,6vw,3.2rem)]">Bring your Audible books into ReadAlong.</h1>
        <p className="mt-4 text-lg leading-relaxed text-fog">
          Books downloaded in the Audible app only play there. These are three ways to get playable copies of the books you own. Pick one.
        </p>

        <div role="tablist" aria-label="Ways to import" className="mt-8 grid gap-3 sm:grid-cols-3">
          {TABS.map((t) => {
            const on = t.id === path
            return (
              <button
                key={t.id}
                role="tab"
                aria-selected={on}
                onClick={() => choose(t.id)}
                className={`rounded-2xl p-4 text-left ring-1 transition-colors ${on ? 'bg-deep ring-pale/45' : 'bg-slate ring-white/6 hover:bg-raised'}`}
              >
                <span className="block font-bold text-white">{t.title}</span>
                <span className="mt-1 block text-sm leading-snug text-dim">{t.blurb}</span>
              </button>
            )
          })}
        </div>

        <section role="tabpanel" className="mt-6 rounded-3xl bg-slate p-6 ring-1 ring-white/6 sm:p-8">
          {path === 'computer' && <Computer />}
          {path === 'android' && <Android />}
          {path === 'other' && <Other />}
        </section>

        <section className="mt-12">
          <h2 className="text-2xl font-extrabold tracking-tight">If something goes wrong</h2>
          <dl className="mt-5 space-y-5 text-[0.95rem] leading-relaxed">
            <div>
              <dt className="font-semibold text-white">ReadAlong says some files are locked (.aax or .aaxc)</dt>
              <dd className="mt-1 text-dim">Those came straight from the Audible app and only play there. Get them again with one of the ways above.</dd>
            </div>
            <div>
              <dt className="font-semibold text-white">The books don't show up</dt>
              <dd className="mt-1 text-dim">In ReadAlong, open the Audible guide again and choose <B>Choose another folder</B>, then pick the folder the files are in.</dd>
            </div>
            <div>
              <dt className="font-semibold text-white">My phone can't find my computer</dt>
              <dd className="mt-1 text-dim">Check that ReadAlong is open on the computer and both are on the same Wi-Fi. On a work or guest network, use <B>Enter its address</B> under Link a computer.</dd>
            </div>
          </dl>
          <p className="mt-10 text-sm leading-relaxed text-dim">
            Only use these with audiobooks you've bought, for your own listening. ReadAlong, LibriSync and Libation are not made by, or affiliated with, Audible or Amazon.
            Questions? <a className="font-semibold text-pale hover:underline" href={`${REPO}/issues`}>Ask on GitHub</a>.
          </p>
        </section>
      </main>
    </div>
  )
}
