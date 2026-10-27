Transcribe audiobooks on your computer, then let the ReadAlong phone app import the text. A computer is much faster than a phone, and a graphics card faster still.

**New in 0.2.0**
- AMD and Intel graphics cards now work on Windows and Linux (through Vulkan), as well as NVIDIA.
- Library folders are recognised: each book is listed under its library, and a single book folder or file is listed on its own.
- Choose where transcripts go: beside each book, or all together in a `ReadAlong Desktop outputs` folder (in Documents unless you pick another place).
- Progress for the current book and for all books, with speed and time left.
- A new look to match ReadAlong.

**Downloads**
- **Windows**: `ReadAlong-Desktop-Windows.zip`. Unzip it and run `ReadAlong Desktop.exe`. If Windows SmartScreen warns you, choose More info, then Run anyway.
- **Mac (Apple Silicon, M1 or newer)**: `ReadAlong-Desktop-macOS-AppleSilicon.zip`. Unzip it, then right-click **ReadAlong Desktop** and choose Open the first time; the app isn't signed.
- **Linux (x86-64)**: `ReadAlong-Desktop-Linux.tar.gz`. Extract it and run `ReadAlong Desktop/ReadAlong Desktop`.

**How to use it**
1. Add a book folder, a single audiobook file, or your whole library folder.
2. Pick a model. The list is sorted by how soon each model would finish on your hardware. Speeds are estimates until the app has finished a book on your computer; after that it uses the measured speed.
3. Choose where to save, then press Start. If the transcript is beside the book and your phone's library folder syncs with that folder, ReadAlong imports it on the next scan. Otherwise, copy the `.readalong` file into the book's folder on the phone, or open the book in ReadAlong and import the transcript there.

**Hardware**: NVIDIA graphics cards (CUDA libraries included; needs a recent driver), AMD and Intel graphics cards (Vulkan; needs a current graphics driver) on Windows and Linux, and the Apple GPU on M-series Macs. Everything else runs on the CPU. Models download once on first use, between 75 MB and 3 GB.

The phone needs ReadAlong 0.40.8 or newer to import desktop transcripts. Older versions say "unsupported speech model".

Command line: run the app with `--help` for options, such as `--list-models`, `--model large-v3-turbo`, `--device vulkan`, `--output-folder` and `--language en`.
