import { useEffect, useRef, useState, type ReactNode } from 'react'
import { motion, useInView, useReducedMotion } from 'motion/react'
import { media } from '../links'

const INK = '#496B8A'
const PAPER = '#F7FAFD'

/** The app's PageTurnEasing, CubicBezierEasing(.42, 0, .18, 1). */
function pageTurnEasing(x: number) {
  const bez = (t: number, p1: number, p2: number) => 3 * (1 - t) ** 2 * t * p1 + 3 * (1 - t) * t ** 2 * p2 + t ** 3
  let lo = 0
  let hi = 1
  for (let i = 0; i < 24; i++) {
    const mid = (lo + hi) / 2
    if (bez(mid, 0.42, 0.18) < x) lo = mid
    else hi = mid
  }
  return bez((lo + hi) / 2, 0, 1)
}

/** The ReadAlong mark. With `turn`, one page turns over once, as in the app's opening animation. */
export function Logo({ size = 32, turn = false }: { size?: number; turn?: boolean }) {
  const reduce = useReducedMotion()
  const [progress, setProgress] = useState(0)

  useEffect(() => {
    if (!turn || reduce) return
    let frame = 0
    const began = performance.now() + 500
    const tick = (now: number) => {
      const t = Math.min(1, Math.max(0, (now - began) / 1100))
      setProgress(t >= 1 ? 0 : pageTurnEasing(t))
      if (t < 1) frame = requestAnimationFrame(tick)
    }
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [turn, reduce])

  const angle = Math.PI * progress
  const cos = Math.cos(angle)
  const sin = Math.sin(angle)
  const pt = (x: number, y: number) => {
    const reach = (x - 54) / 19
    return `${54 + (x - 54) * cos - 3.2 * sin * reach * reach},${58 + (y - 58) * (1 + 0.14 * sin * reach)}`
  }
  const sheet = `M${pt(55, 49)} C${pt(59, 44)} ${pt(65, 41)} ${pt(73, 42)} L${pt(73, 74)} C${pt(67, 73)} ${pt(61, 75)} ${pt(55, 80)} Z`

  return (
    <svg width={size} height={size} viewBox="8 8 92 92" aria-hidden="true">
      <path fill="none" stroke={INK} strokeWidth={4.5} strokeLinecap="round" d="M24,47 C28,11 80,11 84,47" />
      <path
        fill={INK}
        d="M23,49 C12,49 12,73 23,73 L26,73 L26,49 Z M85,49 C96,49 96,73 85,73 L82,73 L82,49 Z M30,48 Q28,48 28,51 L28,78 Q28,81 31,81 C40,79 47,81 52,86 Q54,88 56,86 C61,81 68,79 77,81 Q80,81 80,78 L80,51 Q80,48 78,48 Q76,48 76,51 L76,76 C66,75 59,79 54,83 C49,79 42,75 32,76 L32,51 Q32,48 30,48 Z"
      />
      <path fill={PAPER} d="M35,42 C43,41 49,44 53,49 L53,80 C47,75 41,73 35,74 Z M73,42 C65,41 59,44 55,49 L55,80 C61,75 67,73 73,74 Z" />
      {progress > 0 && progress < 1 && <path d={sheet} fill={cos >= 0 ? '#E9EEF4' : '#EEF2F6'} stroke={INK} strokeWidth={1} strokeLinejoin="round" />}
    </svg>
  )
}

/** Fades content up as it enters the viewport, once. Motion's reduced-motion setting drops the movement. */
export function Reveal({ children, delay = 0, className, y = 28 }: { children: ReactNode; delay?: number; className?: string; y?: number }) {
  return (
    <motion.div
      className={className}
      initial={{ opacity: 0, y }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: true, margin: '0px 0px -12% 0px' }}
      transition={{ duration: 0.8, delay, ease: [0.22, 1, 0.36, 1] }}
    >
      {children}
    </motion.div>
  )
}

/** A phone body around a screen. The screen area matches the recordings' 1080 x 2190. */
export function Phone({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <div className={`rounded-[13%/6.2%] bg-[#07090a] p-[2.6%] shadow-[0_40px_80px_-30px_rgba(0,0,0,0.9)] ring-1 ring-white/10 ${className}`}>
      <div className="relative aspect-[1080/2190] overflow-hidden rounded-[11%/5.3%] bg-pitch">{children}</div>
    </div>
  )
}

/** A screen recording of the app. Loads and plays only while on screen (and `active`), and never autoplays under reduced motion. */
export function Clip({ name, label, active = true, eager = false }: { name: string; label: string; active?: boolean; eager?: boolean }) {
  const ref = useRef<HTMLVideoElement>(null)
  const reduce = useReducedMotion()
  const inView = useInView(ref, { amount: 0.35 })
  const playing = active && inView && !reduce

  useEffect(() => {
    const video = ref.current
    if (!video) return
    if (playing) video.play().catch(() => {})
    else video.pause()
  }, [playing])

  return (
    <video
      ref={ref}
      className="absolute inset-0 h-full w-full object-cover"
      src={media(`${name}.mp4`)}
      poster={media(`${name}-poster.webp`)}
      muted
      loop
      playsInline
      preload={eager ? 'metadata' : 'none'}
      controls={!!reduce}
      aria-label={label}
    />
  )
}

/** A screenshot of the app. */
export function Shot({ name, alt, eager = false }: { name: string; alt: string; eager?: boolean }) {
  return (
    <img
      className="absolute inset-0 h-full w-full object-cover"
      src={media(`${name}.webp`)}
      alt={alt}
      width={720}
      height={1460}
      loading={eager ? 'eager' : 'lazy'}
      decoding="async"
    />
  )
}

export function DownloadIcon({ className = 'size-4' }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M12 4v11m0 0-4.5-4.5M12 15l4.5-4.5M5 20h14" />
    </svg>
  )
}

export function GitHubIcon({ className = 'size-5' }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M12 2C6.48 2 2 6.58 2 12.25c0 4.53 2.87 8.37 6.84 9.73.5.1.68-.22.68-.49 0-.24-.01-.88-.01-1.73-2.78.62-3.37-1.37-3.37-1.37-.46-1.19-1.11-1.5-1.11-1.5-.91-.64.07-.62.07-.62 1 .07 1.53 1.06 1.53 1.06.9 1.57 2.35 1.12 2.92.85.09-.67.35-1.12.63-1.38-2.22-.26-4.56-1.14-4.56-5.07 0-1.12.39-2.03 1.03-2.75-.1-.26-.45-1.3.1-2.71 0 0 .84-.28 2.75 1.05a9.3 9.3 0 0 1 5 0c1.91-1.33 2.75-1.05 2.75-1.05.55 1.41.2 2.45.1 2.71.64.72 1.03 1.63 1.03 2.75 0 3.94-2.34 4.81-4.57 5.06.36.32.68.94.68 1.9 0 1.37-.01 2.48-.01 2.82 0 .27.18.6.69.49A10.2 10.2 0 0 0 22 12.25C22 6.58 17.52 2 12 2Z" />
    </svg>
  )
}
