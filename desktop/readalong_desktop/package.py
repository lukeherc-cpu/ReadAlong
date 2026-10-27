"""Finding books the way the phone does, and writing the .readalong file it imports.

The phone (DesktopTranscriptImporter) checks every source file by size and SHA-256, wants each
file's chunks back to back from 0 to its length in pieces of at most 30 s, and word times in
seconds from the start of each chunk.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import tempfile
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

# Same lists as the phone's AudioImporter.
AUDIO = {"mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "wav", "mp4"}
DISC = re.compile(r"(?i)^(cd|disc|disk|part)\s*[-_ ]?\d+.*")
PART = re.compile(r"(?i)\b(part|pt|disc|disk|cd)\s*[-_.]?\s*\d+")
CHUNK_MS = 30_000
FORMAT = "readalong-transcript"


def is_audio(path: Path) -> bool:
    return path.is_file() and path.suffix.lower().lstrip(".") in AUDIO


def natural_key(name: str):
    return [int(part) if part.isdigit() else part.lower() for part in re.split(r"(\d+)", name)]


@dataclass
class Book:
    name: str
    files: list[Path]
    output: Path
    seconds: float = 0.0  # Filled in by measure().

    @property
    def done(self) -> bool:
        """An up-to-date transcript already sits beside the audio."""
        return self.output.exists() and self.output.stat().st_mtime >= max(f.stat().st_mtime for f in self.files)


def _children(folder: Path) -> list[Path]:
    try:
        return sorted(folder.iterdir(), key=lambda p: natural_key(p.name))
    except OSError:
        return []


def _all_audio(folder: Path) -> list[Path]:
    found = [p for p in folder.rglob("*") if is_audio(p)]
    return sorted(found, key=lambda p: natural_key(str(p.relative_to(folder))))


def _single_file_books(audio: list[Path]) -> list[Book] | None:
    """Several .m4b files side by side are separate books, unless they're "Part 1", "Part 2"."""
    if len(audio) < 2 or not all(p.suffix.lower() == ".m4b" for p in audio):
        return None
    if any(PART.search(p.name) for p in audio):
        return None
    return [Book(p.stem, [p], p.with_suffix(".readalong")) for p in audio]


def book_from_folder(folder: Path) -> Book | None:
    audio = _all_audio(folder)
    return Book(folder.name, audio, folder / f"{folder.name}.readalong") if audio else None


def find_books(path: Path) -> list[Book]:
    """A file, a book folder, or a library folder laid out like the phone's library scan."""
    path = path.expanduser().resolve()
    if path.is_file():
        return [Book(path.stem, [path], path.with_suffix(".readalong"))] if is_audio(path) else []
    if not path.is_dir():
        return []
    children = _children(path)
    loose = [p for p in children if is_audio(p)]
    subfolders = [p for p in children if p.is_dir()]
    # Only CD1/CD2-style folders (with or without loose files): one book.
    if subfolders and all(DISC.match(p.name) for p in subfolders):
        book = book_from_folder(path)
        return [book] if book else []
    # Loose files at the top are one book (or one book per .m4b); subfolders are searched as a library.
    books: list[Book] = _single_file_books(loose) or ([Book(path.name, loose, path / f"{path.name}.readalong")] if loose else [])
    shelves = [(p, 1) for p in subfolders]
    while shelves:
        folder, depth = shelves.pop(0)
        inside = _children(folder)
        audio = [p for p in inside if is_audio(p)]
        folders = [p for p in inside if p.is_dir()]
        discs = bool(folders) and all(DISC.match(p.name) for p in folders)
        if audio or discs or depth >= 4 or not folders:
            singles = _single_file_books(audio) if not folders else None
            if singles:
                books += singles
            elif (book := book_from_folder(folder)) is not None:
                books.append(book)
        else:
            shelves += [(p, depth + 1) for p in folders]
    return books


def sha256(path: Path, progress=None) -> tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        while block := source.read(1 << 20):
            digest.update(block)
            size += len(block)
            if progress:
                progress(len(block))
    return size, digest.hexdigest()


@dataclass
class Word:
    word: str
    start: float  # Seconds from the start of the file.
    end: float
    probability: float | None = None


@dataclass
class FileResult:
    path: Path
    duration_ms: int
    words: list[Word] = field(default_factory=list)


def chunks(result: FileResult) -> list[dict]:
    """Cuts one file's words into the phone's 30 s chunks; each word goes where it starts."""
    out = []
    ordered = sorted(result.words, key=lambda w: w.start)
    index = 0
    for start in range(0, result.duration_ms, CHUNK_MS):
        end = min(start + CHUNK_MS, result.duration_ms)
        length = (end - start) / 1000
        words, previous = [], 0.0
        while index < len(ordered) and ordered[index].start * 1000 < end:
            w = ordered[index]
            index += 1
            text = w.word.strip()
            if not text:
                continue
            a = max(previous, max(0.0, w.start - start / 1000))
            if a >= length:
                continue
            b = min(max(a, w.end - start / 1000), length + 0.2)
            entry = {"word": text, "start": round(a, 3), "end": round(b, 3)}
            if w.probability is not None:
                entry["probability"] = round(min(1.0, max(0.0, float(w.probability))), 3)
            words.append(entry)
            previous = entry["start"]
        out.append({"startMs": start, "endMs": end,
                    "result": {"text": " ".join(w["word"] for w in words), "words": words}})
    return out


def write_package(destination: Path, model_id: str, engine: str, files: list[tuple[FileResult, int, str]]) -> None:
    """Writes manifest.json and chunks.jsonl, the only two entries the phone accepts."""
    sources, count = [], 0
    with tempfile.TemporaryDirectory() as scratch:
        lines = Path(scratch) / "chunks.jsonl"
        with lines.open("w", encoding="utf-8") as out:
            for index, (result, size, digest) in enumerate(files):
                sources.append({"name": result.path.name, "size": size, "sha256": digest, "durationMs": result.duration_ms})
                for chunk in chunks(result):
                    out.write(json.dumps({"file": index, **chunk}, ensure_ascii=False, separators=(",", ":")) + "\n")
                    count += 1
        manifest = {"format": FORMAT, "version": 1, "complete": True, "model": model_id, "engine": engine,
                    "chunks": count, "sources": sources}
        partial = destination.with_name(destination.name + ".partial")
        with zipfile.ZipFile(partial, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=1))
            archive.write(lines, "chunks.jsonl")
        os.replace(partial, destination)
