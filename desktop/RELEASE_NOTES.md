Transcribe audiobooks on your computer, then let the ReadAlong phone app import the text. A computer is much faster than a phone, and a GPU faster still.

**Downloads**
- **Windows**: `ReadAlong-Desktop-Windows.zip`. Unzip it and run `ReadAlong Desktop.exe`. If Windows SmartScreen warns you, choose More info → Run anyway.
- **Mac (Apple Silicon, M1 or newer)**: `ReadAlong-Desktop-macOS-AppleSilicon.zip`. Unzip it, then right-click **ReadAlong Desktop** → Open the first time; the app isn't signed.
- **Linux (x86-64)**: `ReadAlong-Desktop-Linux.tar.gz`. Extract it and run `ReadAlong Desktop/ReadAlong Desktop`.

**How to use it**
1. Add a book folder, a single audiobook file, or your whole library folder.
2. Pick a model. The list is sorted by how soon each model would finish on your hardware. Speeds are estimates until the app has finished a book on your computer; after that it uses the measured speed.
3. Press Start. Each book gets a `.readalong` file beside its audio. If your phone's library folder syncs with that folder, ReadAlong imports it on the next scan. Otherwise, copy the file into the book's folder on the phone.

**Hardware**: NVIDIA GPUs on Windows and Linux (the CUDA libraries are included; you need a recent NVIDIA driver), and the Apple GPU on M-series Macs. Everything else runs on the CPU. Models download once on first use, between 75 MB and 3 GB.

The phone needs a ReadAlong version that accepts desktop Whisper transcripts. Older versions say "unsupported speech model".

Command line: run the app with `--help` for options, such as `--list-models`, `--model large-v3-turbo` and `--language en`.
