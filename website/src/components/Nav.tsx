import { useState } from 'react'
import { motion, useMotionValueEvent, useScroll } from 'motion/react'
import { REPO } from '../links'
import { DownloadIcon, GitHubIcon, Logo } from './ui'

const LINKS = [
  ['Try it', '#try'],
  ['Features', '#features'],
  ['How it works', '#how'],
]

export function Nav() {
  const { scrollY } = useScroll()
  const [scrolled, setScrolled] = useState(false)
  useMotionValueEvent(scrollY, 'change', (y) => setScrolled(y > 24))

  return (
    <motion.header
      className={`fixed inset-x-0 top-0 z-50 border-b transition-colors duration-500 ${scrolled ? 'border-white/8 bg-night/85 backdrop-blur-xl' : 'border-transparent'}`}
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      transition={{ duration: 0.8 }}
    >
      <nav className="mx-auto flex h-16 max-w-6xl items-center gap-8 px-5 sm:px-8" aria-label="Main">
        <a href="#top" className="flex items-center gap-2.5 text-[1.05rem] font-extrabold tracking-tight">
          <Logo size={30} turn />
          ReadAlong
        </a>
        <ul className="ml-auto hidden items-center gap-7 text-sm font-medium text-fog md:flex">
          {LINKS.map(([label, href]) => (
            <li key={href}>
              <a className="transition-colors hover:text-white" href={href}>
                {label}
              </a>
            </li>
          ))}
        </ul>
        <div className="ml-auto flex items-center gap-2 md:ml-0">
          <a className="grid size-10 place-items-center rounded-full text-fog transition-colors hover:bg-white/8 hover:text-white" href={REPO} aria-label="ReadAlong on GitHub">
            <GitHubIcon />
          </a>
          <a className="flex h-10 items-center gap-2 rounded-full bg-pale px-4 text-sm font-bold text-deep transition-transform duration-300 hover:scale-[1.03] active:scale-[0.98]" href="#download">
            <DownloadIcon />
            Download
          </a>
        </div>
      </nav>
    </motion.header>
  )
}
