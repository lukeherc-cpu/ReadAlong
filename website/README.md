# ReadAlong website

The landing page at <https://readalongapp.github.io/ReadAlong/>. It is separate from the Android and desktop
apps: nothing here is part of a Gradle build, and nothing outside this folder is needed to build it.

React, Vite and Tailwind CSS. Motion for React drives the scroll animations and Lenis eases the mouse wheel;
touchpads, touch, the keyboard and reduced-motion settings keep the browser's own scrolling.

```sh
cd website
npm install
npm run dev      # local preview with hot reload
npm run build    # type-check and build into dist/
npm run preview  # serve the built site
```

## Publishing

`.github/workflows/website.yml` builds and deploys to GitHub Pages on every push to `main` that touches
`website/`. It needs **Settings → Pages → Source** set to **GitHub Actions**, once.

Asset paths are relative, so the site works under `/ReadAlong/`, a custom domain or any other base path
without changes. The workflow passes the published address as `SITE_URL` for the canonical link, the sitemap
and the link-preview image.

## Media

The phone and desktop screens on the page are drawn in React (`src/components/kit.tsx`) from the app's own
icons, colors and layout, so they stay sharp at any size and can be tapped in the demo. `src/books.ts` holds
the books they show. `public/media` holds the covers, the desktop screenshots and `pride.mp3`, the narration
clip the demo plays; its phrase timings are the `CUES` in `src/components/Demo.tsx`. `public/og.jpg` is the
1200 × 630 link-preview image.
