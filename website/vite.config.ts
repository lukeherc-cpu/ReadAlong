import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// The deploy workflow passes the real Pages address (it differs with a custom domain or a renamed repository).
const siteUrl = (process.env.SITE_URL || 'https://readalongapp.github.io/ReadAlong').replace(/\/+$/, '') + '/'

/** Fills in the absolute URLs that search engines and link previews need, and writes robots.txt and a sitemap. */
function seo(): Plugin {
  return {
    name: 'readalong-seo',
    transformIndexHtml: (html) => html.replaceAll('__SITE_URL__', siteUrl),
    generateBundle() {
      this.emitFile({ type: 'asset', fileName: 'robots.txt', source: `User-agent: *\nAllow: /\n\nSitemap: ${siteUrl}sitemap.xml\n` })
      this.emitFile({
        type: 'asset',
        fileName: 'sitemap.xml',
        source: `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n  <url><loc>${siteUrl}</loc></url>\n</urlset>\n`,
      })
    },
  }
}

export default defineConfig({
  // Relative asset paths, so the site works under /ReadAlong/, a custom domain, or any other base path.
  base: './',
  plugins: [react(), tailwindcss(), seo()],
  // The landing page, plus the Audible guide that the Play Store app links to.
  build: { rollupOptions: { input: { main: 'index.html', audible: 'audible/index.html' } } },
})
