#!/usr/bin/env bash
# Lays out ReadAlong's Flatpak repository as the static site GitHub Pages serves:
#   repo/                  the signed OSTree repository (flatpak-builder --repo, then build-update-repo)
#   ReadAlong.flatpakref   one-click install; adds the repository as the "readalong" remote
#   readalong.flatpakrepo  the repository alone, for `flatpak remote-add`
#   index.html             how to install
# Usage: flatpak-site.sh <repo dir> <public key file (binary)> <repo URL> <output dir>
set -euo pipefail
repo=$1 key=$2 url=$3 out=$4
app=io.github.lukeherc_cpu.ReadAlong
home=https://github.com/lukeherc-cpu/ReadAlong
key64=$(base64 -w0 "$key")

rm -rf "$out"
mkdir -p "$out"
cp -a "$repo" "$out/repo"
# Pages would otherwise run Jekyll over the site.
touch "$out/.nojekyll"

cat > "$out/ReadAlong.flatpakref" <<EOF
[Flatpak Ref]
Name=$app
Branch=master
Title=ReadAlong
Url=$url
SuggestRemoteName=readalong
Homepage=$home
IsRuntime=false
RuntimeRepo=https://dl.flathub.org/repo/flathub.flatpakrepo
GPGKey=$key64
EOF

cat > "$out/readalong.flatpakrepo" <<EOF
[Flatpak Repo]
Title=ReadAlong
Url=$url
Homepage=$home
GPGKey=$key64
EOF

cat > "$out/index.html" <<'EOF'
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>ReadAlong for Linux</title>
<style>
  body { font: 16px/1.6 system-ui, sans-serif; max-width: 40rem; margin: 3rem auto; padding: 0 1rem; color: #1b1f24; background: #f6f8fa; }
  code { font-family: ui-monospace, monospace; background: #e6ebf1; padding: .1rem .3rem; border-radius: 4px; }
  pre { background: #e6ebf1; padding: .8rem 1rem; border-radius: 6px; overflow-x: auto; }
  a.button { display: inline-block; background: #456886; color: #fff; padding: .6rem 1.1rem; border-radius: 999px; text-decoration: none; font-weight: 600; }
  @media (prefers-color-scheme: dark) { body { background: #111418; color: #e6edf3; } code, pre { background: #1f262e; } }
</style>
</head>
<body>
<h1>ReadAlong for Linux</h1>
<p>An audiobook player that shows the text as the narrator reads it.</p>
<p><a class="button" href="ReadAlong.flatpakref">Install ReadAlong</a></p>
<p>Opening the link installs ReadAlong with your software centre. Or, in a terminal:</p>
<pre>flatpak install --user https://lukeherc-cpu.github.io/ReadAlong/ReadAlong.flatpakref</pre>
<p>New versions then arrive through your software centre, or with <code>flatpak update</code>.</p>
<p><a href="https://github.com/lukeherc-cpu/ReadAlong">Source code and other downloads</a></p>
</body>
</html>
EOF
