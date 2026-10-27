"""CI smoke test: checks a real .readalong made by the built app, like the phone would, and that it heard the words."""
import json
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from readalong_desktop import audio  # noqa: E402
from tests.test_package import check_like_phone  # noqa: E402

package, folder, expected = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3].lower()
files = sorted(p for p in folder.iterdir() if p.suffix.lower() in (".wav", ".mp3", ".m4b"))
check_like_phone(package, files, [round(audio.length_seconds(f) * 1000) for f in files])
with zipfile.ZipFile(package) as archive:
    text = " ".join(json.loads(line)["result"]["text"] for line in archive.read("chunks.jsonl").decode().splitlines())
    print(json.loads(archive.read("manifest.json"))["model"], "|", text[:300])
assert expected in text.lower(), f"expected '{expected}' in the transcript"
print("OK")
