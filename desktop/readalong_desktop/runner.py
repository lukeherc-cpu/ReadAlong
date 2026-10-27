"""Transcribes one book and writes its .readalong file beside the audio."""
from __future__ import annotations

import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from . import audio, hardware as hw
from .engines import Engine
from .package import Book, FileResult, sha256, write_package


@dataclass
class Progress:
    book: str
    done_seconds: float
    total_seconds: float
    status: str

    @property
    def fraction(self) -> float:
        return min(1.0, self.done_seconds / self.total_seconds) if self.total_seconds else 0.0


def measure(books: list[Book]) -> float:
    for book in books:
        if not book.seconds:
            book.seconds = sum(audio.length_seconds(f) for f in book.files)
    return sum(b.seconds for b in books)


def transcribe_book(book: Book, engine: Engine, model: str, machine: hw.Hardware, language: str | None = None,
                    report: Callable[[Progress], None] = lambda p: None, cancelled: Callable[[], bool] = lambda: False) -> Path:
    total = book.seconds or measure([book])
    done = 0.0
    began = time.monotonic()
    results = []
    for number, path in enumerate(book.files, 1):
        label = f"File {number} of {len(book.files)}" if len(book.files) > 1 else "Transcribing"
        report(Progress(book.name, done, total, f"{label}: checking the file…"))
        size, digest = sha256(path)
        words, samples = [], 0
        for offset, window in audio.windows(path):
            if cancelled():
                raise InterruptedError("Stopped.")
            found, detected = engine.transcribe(window, offset, language)
            language = language or detected  # Keep the first detected language for the whole book.
            words += found
            samples += len(window)
            report(Progress(book.name, done + samples / audio.RATE, total, f"{label}…"))
        duration_ms = samples * 1000 // audio.RATE
        if duration_ms <= 0:
            raise ValueError(f"{path.name} has no audio.")
        results.append((FileResult(path, duration_ms, words), size, digest))
        done += duration_ms / 1000
    write_package(book.output, f"desktop:{engine.backend}:{model}", f"Whisper {model}", results)
    elapsed = time.monotonic() - began
    if elapsed > 30 and done > 0:
        hw.remember_speed(machine, model, done / elapsed)
    report(Progress(book.name, total, total, f"Done in {hw.duration_text(elapsed)}. Saved {book.output.name}"))
    return book.output
