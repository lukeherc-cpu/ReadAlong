# ReadAlong for Windows, Linux and macOS

The ReadAlong library, player and read-along reader as a desktop app, with transcription that runs on the
graphics card when there is one: Vulkan on Windows and Linux, Metal on a Mac. One codebase, one design;
Windows ships as an MSI, Linux as a Flatpak, macOS as a `.dmg` for Apple silicon and Intel.

## What it does

- **Library.** Add your audiobook folder (or drop folders and files on the window). Books are found with the
  phone's library rules: a folder per book with CD1/CD2 subfolders, Author/Book shelves, and side-by-side
  `.m4b` files as separate books. Chapters come from the phone's own MP4 and ID3 chapter readers; covers from a
  `cover.jpg`/`folder.jpg` or the artwork in the file. An EPUB beside a book is used for its text.
- **Player.** MP3, M4A, M4B, AAC, OGG, Opus, FLAC and WAV through FFmpeg. Speed from 0.5× to 3× without
  changing pitch, sleep timer, bookmarks, chapters, and a mini player that stays with you in the library.
- **Read along.** The text follows the narration and lights the sentence being read; click any sentence to
  listen from it. Generated text and EPUB matching use the phone's code unchanged (ParagraphFormatter,
  EpubParser, EpubAligner, sentence timing), so a book reads the same on both.
- **Transcription.** whisper.cpp in a separate process. Its Vulkan backend runs on NVIDIA, AMD and Intel
  graphics cards; without one (or if the driver fails) it uses the best CPU code for the processor. A GPU
  that stops responding switches the book to the CPU and keeps the progress. Models (tiny.en to
  large-v3-turbo) download from Hugging Face the first time. Tiny is the default, the smallest and quickest;
  pick a bigger one in Settings for fewer mistakes.
- **Aggressive transcription** (Settings, on by default). Several transcriber processes share one book: two
  on the graphics card (one computes while the other decodes text, so the card stays busy) and one on the
  spare processor cores, or several of about six threads each without a card. Each takes about two minutes
  of audio at a time, with greedy decoding, and results are saved in book order so stopping and resuming
  work as before. Turned off, one transcriber runs with beam search on the GPU.

## Transcripts move between the phone and the PC

Transcripts are stored, from the start, as the `.readalong` package the phone imports
(`DesktopTranscriptImporter`):

- `manifest.json`: `format: "readalong-transcript"`, `version: 1`, `complete`, `model`
  (`desktop:whisper.cpp:<model>`), `engine`, `chunks`, and `sources` (each audio file's name, size, SHA-256
  and length).
- `chunks.jsonl`: one line per unit of at most 30 s, back to back from 0 to each file's length:
  `{"file", "startMs", "endMs", "result": {"text", "words": [{"word", "start", "end", "probability"}]}}`,
  word times in seconds from the start of the chunk.

The app reads its own text through the phone's `WhistleResult`, exactly as the phone reads a package, so
there is no conversion either way. With **Settings → Phone → Save each ReadAlong beside its audiobook** on,
each finished book gets `<book>.readalong` next to its audio, where the phone's library scan finds it. A
`.readalong` found beside a book (from the phone, PR #18's command-line transcriber, or another PC) is
imported when the library is scanned. The phone accepts `desktop:` model ids once the `DesktopTranscriptImporter` change in this branch ships in an Android release.

## Scrolling

The mouse wheel glides like a web browser's (`ui/SmoothWheel.kt`): a notch moves a fixed distance and eases
out over about 200 ms, and notches during a glide add to it. Mac touchpads move without easing.

## Build

Windows needs JDK 21, CMake, Visual Studio 2022 (C++), and the Vulkan SDK for the GPU backend.

```
git clone --branch v1.8.7 https://github.com/ggml-org/whisper.cpp ../whisper.cpp
cmake -S desktop-app/native -B desktop-app/native/build -A x64 -DWHISPER_CPP=../whisper.cpp -DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded
cmake --build desktop-app/native/build --config Release
./gradlew -p desktop-app run                 # run from source
./gradlew -p desktop-app packageReleaseMsi   # installer in desktop-app/build/compose/binaries/main-release/msi
```

`.github/workflows/desktop-windows.yml` does all of this on every push to `desktop-app/`, then transcribes
synthesized speech with the packaged app (on the CPU, and on Mesa's software Vulkan to exercise the GPU path)
and uploads the MSI and a portable zip as the run's **ReadAlong-Windows** artifact.

`ReadAlong.exe --self-test <book folder> --model <ggml model> --device cpu|gpu` runs that check by hand.

**Linux** (`desktop-linux.yml`): `libvulkan-dev glslc spirv-headers`, then the same cmake commands with
`-DCMAKE_BUILD_TYPE=Release`, `./gradlew -p desktop-app createReleaseDistributable`, and
`flatpak-builder` on `packaging/linux/io.github.lukeherc_cpu.ReadAlong.yml`, which wraps that app image on the
GNOME runtime. Data lives in `~/.var/app/io.github.lukeherc_cpu.ReadAlong/data/ReadAlong` in the Flatpak
(`~/.local/share/ReadAlong` outside it).

**macOS** (`desktop-macos.yml`): Xcode's command line tools and CMake; whisper.cpp builds with Metal instead of
Vulkan. The `.app` is signed ad hoc, not notarized, so it must be opened once with **Open Anyway** in
System Settings → Privacy & Security. Data lives in `~/Library/Application Support/ReadAlong`.
