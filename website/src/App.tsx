import { MotionConfig } from 'motion/react'
import { Nav } from './components/Nav'
import { Hero } from './components/Hero'
import { Passage } from './components/Passage'
import { Tour } from './components/Tour'
import { How } from './components/How'
import { Details } from './components/Details'
import { Desktop } from './components/Desktop'
import { Download } from './components/Download'
import { Faq, Footer } from './components/Faq'
import { useSmoothWheel } from './useSmoothWheel'

export default function App() {
  useSmoothWheel()
  return (
    // "user": people who ask for reduced motion get fades only, with no movement or parallax.
    <MotionConfig reducedMotion="user">
      <a href="#features" className="sr-only focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:z-[60] focus:rounded-full focus:bg-pale focus:px-4 focus:py-2 focus:font-semibold focus:text-deep">
        Skip to features
      </a>
      <Nav />
      <main>
        <Hero />
        <Passage />
        <Tour />
        <How />
        <Details />
        <Desktop />
        <Download />
        <Faq />
      </main>
      <Footer />
    </MotionConfig>
  )
}
