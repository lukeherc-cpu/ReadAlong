"""The .readalong file must pass every check the phone's DesktopTranscriptImporter and WhistleResult make."""
import json
import math
import subprocess
import zipfile
from pathlib import Path

import numpy as np
import pytest

from readalong_desktop import audio, hardware as hw
from readalong_desktop.engines import Engine
from readalong_desktop.package import Word, find_books, sha256
from readalong_desktop.runner import transcribe_book


def check_like_phone(package: Path, files: list[Path], phone_durations: list[int]):
    """A line-by-line port of DesktopTranscriptImporter's validation (plus WhistleResult's word rules)."""
    with zipfile.ZipFile(package) as archive:
        assert sorted(archive.namelist()) == ["chunks.jsonl", "manifest.json"]
        manifest = json.loads(archive.read("manifest.json"))
        lines = archive.read("chunks.jsonl").decode("utf-8").split("\n")
    assert manifest["format"] == "readalong-transcript" and manifest["version"] == 1 and manifest["complete"] is True
    assert manifest["model"].startswith("desktop:")
    sources = manifest["sources"]
    assert len(sources) == len(files)
    hashes = {}
    for f in files:
        size, digest = sha256(f)
        hashes.setdefault(f"{size}:{digest}", []).append(f)
    durations = []
    for i, expected in enumerate(sources):
        assert hashes.get(f"{expected['size']}:{expected['sha256']}"), "belongs to different audio"
        durations.append(expected["durationMs"])
        assert 1 <= durations[i] <= 7 * 24 * 3600_000
        assert abs(durations[i] - phone_durations[i]) <= 1000, "duration does not match"
    ends = [0] * len(sources)
    file_index = count = 0
    assert lines[-1] == ""
    for line in lines[:-1]:
        chunk = json.loads(line)
        if file_index < len(sources) - 1 and ends[file_index] == durations[file_index]:
            file_index += 1
        f = chunk["file"]
        assert f == file_index
        start, end = chunk["startMs"], chunk["endMs"]
        assert start == ends[f] and 1 <= end - start <= 30_000 and end <= durations[f]
        ends[f] = end
        count += 1
        result = chunk["result"]
        assert len(result["text"]) <= 100_000
        words = result["words"]
        assert len(words) <= 5000
        if result["text"].strip():
            assert 1 <= len(words) <= 5000
        previous = 0
        cursor = 0
        for word in words:
            a, b = word["start"], word["end"]
            assert math.isfinite(a) and math.isfinite(b) and 0 <= a <= b <= (end - start) / 1000 + .25
            if "probability" in word:
                assert 0 <= word["probability"] <= 1
            ms = round(a * 1000)
            assert ms >= previous, "out of order"
            previous = ms
            at = result["text"].lower().find(word["word"].strip().lower(), cursor)
            assert at >= cursor, "word list doesn't correspond to its transcript"
            cursor = at + len(word["word"].strip())
    assert count == manifest["chunks"] and ends == durations


class FakeEngine(Engine):
    """A word every 0.7 s, like real speech, crossing every chunk and window boundary."""
    backend = "test"

    def transcribe(self, samples, offset, language):
        length = len(samples) / audio.RATE
        words, t = [], 0.1
        while t < length:
            words.append(Word(f" word{int((offset + t) * 10)}.", offset + t, offset + min(length, t + 0.5), 0.9))
            t += 0.7
        return words, "en"


def tone(path: Path, seconds: float):
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", f"sine=frequency=440:duration={seconds}",
                    "-ac", "1", "-ar", "44100", str(path)], check=True)


@pytest.mark.parametrize("names,lengths", [(["book.m4b"], [95.3]), (["01.mp3", "02.mp3", "03.mp3"], [31.0, 60.0, 12.5])])
def test_package_passes_phone_checks(tmp_path, monkeypatch, names, lengths):
    monkeypatch.setattr(audio, "windows", _small_windows(audio.windows))
    folder = tmp_path / "My Book"
    folder.mkdir()
    files = []
    for name, seconds in zip(names, lengths):
        tone(folder / name, seconds)
        files.append(folder / name)
    books = find_books(folder)
    assert len(books) == (1 if len(names) > 1 else 1)
    book = books[0]
    machine = hw.Hardware("cpu", "test", 8)
    monkeypatch.setattr(hw, "settings_dir", lambda: tmp_path)
    out = transcribe_book(book, FakeEngine(), "small", machine)
    phone = [round(audio.length_seconds(f) * 1000) for f in book.files]
    check_like_phone(out, book.files, phone)
    assert book.done


def _small_windows(original):
    # 45 s windows so the tests cross window boundaries in the middle of chunks.
    return lambda path, seconds=1200: original(path, 45)


def test_library_layouts(tmp_path):
    def touch(relative):
        p = tmp_path / relative
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(b"x")
    touch("Author/Book One/01.mp3"); touch("Author/Book One/02.mp3")
    touch("Author/Series/Book Two/CD1/a.mp3"); touch("Author/Series/Book Two/CD2/b.mp3")
    touch("Singles/One.m4b"); touch("Singles/Two.m4b")
    touch("Parts/Big Part 1.m4b"); touch("Parts/Big Part 2.m4b")
    touch("loose.mp3")
    books = {b.name: b for b in find_books(tmp_path)}
    assert set(books) == {tmp_path.name, "Book One", "Book Two", "One", "Two", "Parts"}
    assert len(books["Book Two"].files) == 2
    assert books["One"].output.name == "One.readalong"
    assert books["Book One"].output == tmp_path / "Author/Book One/Book One.readalong"
    assert len(books[tmp_path.name].files) == 1


def test_models_sorted_fastest_first():
    for machine in (hw.Hardware("cpu", "x", 8), hw.Hardware("cuda", "x", 8, "NVIDIA GeForce RTX 4070", 12.0),
                    hw.Hardware("metal", "Apple M2 Pro", 12, "Apple M2 Pro")):
        rows = hw.choices(machine, 36_000)
        seconds = [r["seconds"] for r in rows]
        assert seconds == sorted(seconds)
        assert sum(r["recommended"] for r in rows) == 1


def test_windows_cover_whole_file(tmp_path):
    tone(tmp_path / "a.wav", 100.5)
    total = sum(len(w) for _, w in audio.windows(tmp_path / "a.wav", 30))
    assert abs(total / audio.RATE - 100.5) < 0.05
    assert all(isinstance(w, np.ndarray) and w.dtype == np.float32 for _, w in audio.windows(tmp_path / "a.wav", 30))
