import { useEffect } from 'react'
import Lenis from 'lenis'

/**
 * Eases mouse-wheel scrolling, the way the desktop app's library does. Everything else stays native:
 * touchpads (which already scroll smoothly), touch, the keyboard, the scrollbar and find-in-page.
 * Nothing is installed when the reader asks for reduced motion.
 */
export function useSmoothWheel() {
  useEffect(() => {
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)')
    let lenis: Lenis | undefined
    let lastFineWheel = 0

    const start = () => {
      lenis = new Lenis({
        autoRaf: true,
        anchors: true,
        lerp: 0.12,
        wheelMultiplier: 1,
        // Returning false hands the event back to the browser. A touchpad sends a stream of small pixel deltas;
        // a wheel sends notches (whole lines, or 50px and more), so only those are eased.
        virtualScroll: ({ event }) => {
          if (!(event instanceof WheelEvent) || event.ctrlKey) return false
          const notch = event.deltaMode !== 0 || (Math.abs(event.deltaY) >= 50 && event.deltaX === 0)
          const now = performance.now()
          if (!notch) lastFineWheel = now
          // A fast touchpad fling can pass 50px mid-gesture; keep the whole gesture native.
          return notch && now - lastFineWheel > 200
        },
      })
    }
    const sync = () => {
      lenis?.destroy()
      lenis = undefined
      if (!reduced.matches) start()
    }
    sync()
    reduced.addEventListener('change', sync)
    return () => {
      reduced.removeEventListener('change', sync)
      lenis?.destroy()
    }
  }, [])
}
