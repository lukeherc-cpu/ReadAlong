# ReadAlong Desktop

Transcribes audiobooks with Whisper on a computer and writes the `.readalong` transcript package that the phone's `DesktopTranscriptImporter` reads. The phone also picks these files up in library folder scans.

- `readalong_desktop/hardware.py` detects an NVIDIA GPU (CUDA), an Apple Silicon GPU (Metal) or the CPU, and lists the models sorted by estimated finish time. Measured speeds are kept in `speeds.json` in the app's settings folder.
- `readalong_desktop/engines.py` runs faster-whisper (CUDA or CPU) or mlx-whisper (Apple Silicon).
- `readalong_desktop/package.py` finds books with the same rules as the phone's library scan and writes the package.
- `tests/test_package.py` checks the output against a port of the phone importer's validation.

Run from source: `pip install -r requirements.txt`, then `python run.py` opens the window, or `python run.py --help` for the terminal. `.github/workflows/desktop.yml` builds the Windows, macOS and Linux apps and runs a speech test on each. Run it by hand with "publish" ticked to make a `desktop-v<version>` release; it never becomes the repository's latest release.
