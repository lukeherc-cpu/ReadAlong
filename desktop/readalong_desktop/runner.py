"""Transcribes one book and writes its .readalong file beside the audio."""
from __future__ import annotations

import time
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from . import audio, hardware as hw
from .engines import Engine
from .package import Book, FileResult, default_outputs, place, sha256, write_package


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
    """Decodes the book window by window and keeps engine.streams batches transcribing at once.

    Batches can mix files (short chapter files share one model load) and finish out of order; each file's
    words are sorted back into place at the end.
    """
    total = book.seconds or measure([book])
    began = time.monotonic()
    files: list[dict] = []
    heard = [0.0]  # Seconds transcribed so far.
    streams = max(1, engine.streams)
    batch_seconds = getattr(engine, "batch_seconds", 0)
    how = f" ({streams} at once)" if streams > 1 else ""

    def status(text: str):
        report(Progress(book.name, heard[0], total, text))

    def windows():
        for number, path in enumerate(book.files, 1):
            label = f"File {number} of {len(book.files)}: " if len(book.files) > 1 else ""
            status(f"{label}reading the file…")
            file_size, read = max(1, path.stat().st_size), [0, 0]

            def reading(block: int):
                read[0] += block
                percent = read[0] * 100 // file_size
                if percent >= read[1] + 10:
                    read[1] = percent
                    status(f"{label}reading the file… {percent}%")
            size, digest = sha256(path, reading)
            files.append({"path": path, "size": size, "digest": digest, "samples": 0, "words": []})
            for offset, samples in audio.windows(path):
                if cancelled():
                    raise InterruptedError("Stopped.")
                files[-1]["samples"] += len(samples)
                yield len(files) - 1, offset, samples

    def finish(batch, results):
        nonlocal language
        for (index, _, samples), (words, detected) in zip(batch, results):
            files[index]["words"] += words
            heard[0] += len(samples) / audio.RATE
            language = language or detected  # Keep the first detected language for the whole book.
        status(f"Transcribed {hw.duration_text(heard[0])} of {hw.duration_text(total)}{how}")

    def run(batch):
        return engine.transcribe_batch([(samples, offset) for _, offset, samples in batch], language, cancelled)

    pool = ThreadPoolExecutor(streams) if streams > 1 else None
    pending: dict = {}
    limit = streams

    def collect(until: int):
        nonlocal limit
        while len(pending) > until:
            finished, _ = wait(list(pending), return_when=FIRST_COMPLETED)
            failed = []
            for future in finished:
                batch = pending.pop(future)
                try:
                    finish(batch, future.result())
                except InterruptedError:
                    raise
                except Exception:
                    failed.append(batch)
            if failed:  # Often the card running out of memory: let the others end, retry alone, then one at a time.
                limit = 1
                for future in list(pending):
                    batch = pending.pop(future)
                    try:
                        finish(batch, future.result())
                    except InterruptedError:
                        raise
                    except Exception:
                        failed.append(batch)
                for batch in failed:
                    finish(batch, run(batch))

    try:
        batch, seconds = [], 0.0
        for item in windows():
            batch.append(item)
            seconds += len(item[2]) / audio.RATE
            if language is None and not heard[0] and pool:
                finish(batch, run(batch))  # The first window alone, to settle the language for the rest.
                batch, seconds = [], 0.0
            elif seconds >= batch_seconds:
                if pool:
                    pending[pool.submit(run, batch)] = batch
                    collect(limit - 1)
                else:
                    finish(batch, run(batch))
                batch, seconds = [], 0.0
        if batch:
            if pool:
                pending[pool.submit(run, batch)] = batch
            else:
                finish(batch, run(batch))
        collect(0)
    finally:
        if pool:
            pool.shutdown(wait=True, cancel_futures=True)

    results = []
    for entry in files:
        duration_ms = entry["samples"] * 1000 // audio.RATE
        if duration_ms <= 0:
            raise ValueError(f"{entry['path'].name} has no audio.")
        words = sorted(entry["words"], key=lambda w: w.start)
        results.append((FileResult(entry["path"], duration_ms, words), entry["size"], entry["digest"]))
    done = sum(r[0].duration_ms for r in results) / 1000
    try:
        write_package(book.output, f"desktop:{engine.backend}:{model}", f"Whisper {model}", results)
    except PermissionError:  # A read-only book folder: save it with the others instead.
        place([book], default_outputs())
        write_package(book.output, f"desktop:{engine.backend}:{model}", f"Whisper {model}", results)
    elapsed = time.monotonic() - began
    if elapsed > 30 and done > 0:
        hw.remember_speed(machine, model, done / elapsed)
    report(Progress(book.name, total, total, f"Done in {hw.duration_text(elapsed)}. Saved {book.output}"))
    return book.output
