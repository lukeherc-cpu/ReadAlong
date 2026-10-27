"""readalong-desktop: with no arguments it opens the window; with paths it runs in the terminal."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from . import __version__, hardware as hw
from .package import find_books
from .runner import measure, transcribe_book

LANGUAGES = ["en", "de", "fr", "es", "it", "nl", "pl", "pt", "ru", "ja", "zh", "ko"]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="readalong-desktop", description=
        "Transcribes audiobooks with Whisper on your computer's GPU and writes a .readalong file beside each book. "
        "Copy it to the phone with the audio (or keep it in your synced library folder) and ReadAlong imports it.")
    parser.add_argument("paths", nargs="*", type=Path, help="an audio file, a book folder or a library folder")
    parser.add_argument("--model", help="model id (see --list-models); asks when left out")
    parser.add_argument("--language", help="for example en; detected from the first minutes when left out")
    parser.add_argument("--device", choices=["auto", "cuda", "metal", "cpu"], default="auto")
    parser.add_argument("--list-models", action="store_true", help="show models sorted by finish time and exit")
    parser.add_argument("--force", action="store_true", help="redo books that already have a .readalong file")
    parser.add_argument("--yes", action="store_true", help="use the recommended model without asking")
    parser.add_argument("--version", action="version", version=__version__)
    parser.add_argument("--check", action="store_true", help=argparse.SUPPRESS)  # Build check: everything bundled imports.
    args = parser.parse_args(argv)
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(errors="replace")  # Windows consoles that can't show every title character.
        except Exception:
            pass

    if args.check:
        import importlib
        for module in ("readalong_desktop.gui", "readalong_desktop.engines", "faster_whisper", "ctranslate2", "onnxruntime", "av"):
            importlib.import_module(module)
        if sys.platform == "darwin" and __import__("platform").machine() == "arm64":
            importlib.import_module("mlx_whisper")
        print("Bundle OK")
        return 0
    if not args.paths and not args.list_models:
        from .gui import run
        return run()

    machine = hw.detect(args.device)
    print(machine.describe())
    books = [book for path in args.paths for book in find_books(path)]
    todo = [b for b in books if args.force or not b.done]
    skipped = len(books) - len(todo)
    if args.paths and not books:
        print("No audiobooks found there.")
        return 1
    if skipped:
        print(f"Skipping {skipped} book(s) that already have a transcript (use --force to redo).")
    seconds = measure(todo) if todo else 3600.0
    rows = hw.choices(machine, seconds)
    print(f"\n{'#':>2}  {'Model':<16}{'Accuracy':<11}{'Estimated time':<16}" + (f"for {len(todo)} book(s), {hw.duration_text(seconds)} of audio" if todo else "per hour of audio"))
    for number, row in enumerate(rows, 1):
        note = " (measured)" if row["measured"] else ""
        mark = "  <- recommended" if row["recommended"] else ""
        print(f"{number:>2}  {row['model'].name:<16}{row['model'].accuracy:<11}{hw.duration_text(row['seconds']) + note:<16}{mark}")
    if args.list_models or not todo:
        return 0

    ids = [row["model"].id for row in rows]
    model = args.model
    if model and model not in ids:
        print(f"Unknown model {model}. Choose one of: {', '.join(ids)}")
        return 2
    if not model:
        default = next(i for i, row in enumerate(rows, 1) if row["recommended"])
        if args.yes or not sys.stdin.isatty():
            model = ids[default - 1]
        else:
            answer = input(f"\nModel number [{default}]: ").strip() or str(default)
            if not answer.isdigit() or not 1 <= int(answer) <= len(rows):
                print("No model chosen.")
                return 2
            model = ids[int(answer) - 1]

    from .engines import load
    print(f"\nLoading {model} (downloaded once on first use)…")
    try:
        engine = load(model, machine)
    except Exception as failure:
        print(f"Couldn't load {model}: {failure}")
        return 1
    last = [""]

    def report(progress):
        line = f"\r{progress.book[:40]:<40} {progress.fraction * 100:5.1f}%  {progress.status}"
        if line != last[0]:
            print(line[:150].ljust(150), end="", flush=True)
            last[0] = line

    failures = 0
    for book in todo:
        try:
            transcribe_book(book, engine, model, machine, args.language, report)
            print()
        except KeyboardInterrupt:
            print("\nStopped.")
            return 130
        except Exception as failure:
            failures += 1
            print(f"\n{book.name}: {failure}")
    return 1 if failures else 0
