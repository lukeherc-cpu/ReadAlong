import { MotionConfig } from 'motion/react'
import { Nav } from './components/Nav'
import { Hero } from './components/Hero'
import { Demo } from './components/Demo'
import { Features } from './components/Features'
import { How } from './components/How'
import { Download } from './components/Download'
import { Footer } from './components/Footer'
import { useSmoothWheel } from './useSmoothWheel'

export default function App() {
  useSmoothWheel()
  return (
    // "user": people who ask for reduced motion get fades only, with no movement.
    <MotionConfig reducedMotion="user">
      {/* The page sets its own background and text color here, so a host page's defaults (such as a light preview frame) can't leak in. */}
      <div className="min-h-screen bg-night text-mist">
      <a href="#try" className="sr-only focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:z-[60] focus:rounded-full focus:bg-pale focus:px-4 focus:py-2 focus:font-semibold focus:text-deep">
        Skip to the demo
      </a>
      <Nav />
      <main>
        <Hero />
        <Demo />
        <Features />
        <How />
        <Download />
      </main>
      <Footer />
      </div>
    </MotionConfig>
  )
}
