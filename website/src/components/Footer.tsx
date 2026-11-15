import { REPO } from '../links'
import { Logo } from './ui'

export function Footer() {
  return (
    <footer className="border-t border-white/8">
      <div className="mx-auto flex max-w-6xl flex-col gap-5 px-5 py-10 text-sm text-fog sm:flex-row sm:items-center sm:px-8">
        <a href="#top" className="flex items-center gap-2.5 text-base font-extrabold tracking-tight text-white">
          <Logo size={28} />
          ReadAlong
        </a>
        <p className="sm:ml-4">Open source under GPLv3.</p>
        <ul className="flex gap-6 font-semibold text-white sm:ml-auto">
          <li><a className="hover:text-pale" href={REPO}>GitHub</a></li>
          <li><a className="hover:text-pale" href={`${REPO}/issues`}>Report a problem</a></li>
        </ul>
      </div>
    </footer>
  )
}
