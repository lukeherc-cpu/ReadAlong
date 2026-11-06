# Source

Vendored from https://github.com/heydamir/unslop at commit
a6f121eee5f29c2f74976b75456feae8b66afb3a (`skills/unslop`), by Damir Sokolovsky
(@heydamir), licensed CC BY 4.0 (see LICENSE). Unmodified except that the
Codex-only `agents/openai.yaml` was dropped and this file was added.

## Notes for ReadAlong

The skill is written with web stacks in mind (React, Tailwind, HTML). ReadAlong's
Android UI is Jetpack Compose, so map its advice onto Compose: spacing on the 4.dp
grid, colours through the MaterialTheme colour scheme, Material Symbols / existing
icon set instead of emoji, `fontFeatureSettings = "tnum"` for tabular figures.

Any change made with this skill must keep every existing feature, control and
behaviour working. Audit and propose first; edit only after Luke approves.

The optional `scripts/` (snapshot of a live site, story video) need Playwright and
ffmpeg and are not used for the Android app.
