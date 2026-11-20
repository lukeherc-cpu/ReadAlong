import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '@fontsource-variable/plus-jakarta-sans'
import './index.css'
import AudibleGuide from './components/AudibleGuide'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AudibleGuide />
  </StrictMode>,
)
