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
from readalong_desktop.package import OUTPUTS_NAME, Word, find_books, place, scan, sha256
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
    return lambda path, seconds=600: original(path, 45)


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


def test_scan_tells_books_from_libraries(tmp_path):
    def touch(relative):
        p = tmp_path / relative
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(b"x")
    touch("Library/A/01.mp3"); touch("Library/B/CD1/x.mp3"); touch("Library/B/CD2/y.mp3")
    touch("Solo/01.mp3"); touch("Solo/02.mp3")
    touch("Discs/CD1/a.mp3"); touch("Discs/CD2/b.mp3")
    touch("file.m4b")
    assert scan(tmp_path / "Solo")[0] == "book"
    assert scan(tmp_path / "Discs")[0] == "book"
    assert scan(tmp_path / "file.m4b")[0] == "file"
    kind, books = scan(tmp_path / "Library")
    assert kind == "library" and sorted(b.name for b in books) == ["A", "B"]
    assert all(b.shelf == "Library" for b in books)


def test_outputs_folder(tmp_path, monkeypatch):
    for name in ("Shelf/Dune/01.mp3", "Other/Dune/01.mp3", "Shelf/Emma/01.mp3"):
        (tmp_path / name).parent.mkdir(parents=True, exist_ok=True)
        tone(tmp_path / name, 3)
    books = find_books(tmp_path / "Shelf") + find_books(tmp_path / "Other")
    outputs = tmp_path / OUTPUTS_NAME
    place(books, outputs)
    names = sorted(b.output.name for b in books)
    assert names == ["Dune (Other).readalong", "Dune.readalong", "Emma.readalong"]
    assert all(b.output.parent == outputs for b in books)
    monkeypatch.setattr(hw, "settings_dir", lambda: tmp_path)
    out = transcribe_book(books[0], FakeEngine(), "small", hw.Hardware("cpu", "test", 8))
    assert out.parent == outputs and out.exists() and books[0].done
    check_like_phone(out, books[0].files, [round(audio.length_seconds(f) * 1000) for f in books[0].files])
    place(books, None)
    assert books[0].output == books[0].beside and not books[0].done  # Saving beside the book now: none there yet.


def test_whisper_cpp_words(monkeypatch):
    from readalong_desktop import engines, vulkan
    result = {"result": {"language": "en"}, "transcription": [
        {"offsets": {"from": 0, "to": 320}, "text": " The"},
        {"offsets": {"from": 320, "to": 700}, "text": " quick"},
        {"offsets": {"from": 700, "to": 700}, "text": " "},
        {"offsets": {"from": 700, "to": 1200}, "text": " fox."}]}
    monkeypatch.setattr(vulkan, "run", lambda *a, **k: result)
    engine = engines.WhisperCpp.__new__(engines.WhisperCpp)
    engine.vulkan, engine.cli, engine.model, engine.threads = vulkan, Path("cli"), Path("model"), 4
    words, language = engine.transcribe(np.zeros(16000, np.float32), 600.0, None)
    assert language == "en" and [w.word for w in words] == [" The", " quick", " fox."]
    assert words[0].start == 600.0 and words[-1].end == 601.2


def test_vulkan_picks_the_graphics_card():
    from readalong_desktop import vulkan
    found = [{"name": "AMD Radeon(TM) Graphics", "type": 1, "vram_gb": 0.5},
             {"name": "AMD Radeon RX 6700 XT", "type": 2, "vram_gb": 12.0},
             {"name": "llvmpipe", "type": 4, "vram_gb": 0}]
    assert vulkan.best(found)["name"] == "AMD Radeon RX 6700 XT"
    assert vulkan.best([found[0], found[2]]) is None
    assert vulkan.best([found[2]], any_kind=True)["name"] == "llvmpipe"
    machine = hw.Hardware("vulkan", "x", 12, "AMD Radeon RX 6700 XT", 12.0)
    rows = hw.choices(machine, 36_000)
    assert [r["seconds"] for r in rows] == sorted(r["seconds"] for r in rows)
    assert next(r for r in rows if r["recommended"])["model"].id == "large-v3-turbo"
    assert "RX 6700 XT" in machine.describe()
