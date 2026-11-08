"""Fetches cover art from Open Library for the promo capture's titles; writes covers/cover-N.png and titles.txt."""
import io, json, sys, urllib.parse, urllib.request
from PIL import Image
OUT = sys.argv[1]
BOOKS = [("Project Hail Mary", "Andy Weir"), ("Fourth Wing", "Rebecca Yarros"), ("Circe", "Madeline Miller"),
         ("No Country for Old Men", "Cormac McCarthy"), ("Sunrise on the Reaping", "Suzanne Collins"), ("Remarkably Bright Creatures", "Shelby Van Pelt")]
def get(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "ReadAlong-promo/1.0"}), timeout=60).read()
lines = []
for i, (title, author) in enumerate(BOOKS):
    docs = json.loads(get("https://openlibrary.org/search.json?" + urllib.parse.urlencode({"title": title, "author": author, "limit": 5, "fields": "title,cover_i,edition_count"})))["docs"]
    cover = next(d["cover_i"] for d in docs if d.get("cover_i"))
    img = Image.open(io.BytesIO(get(f"https://covers.openlibrary.org/b/id/{cover}-L.jpg"))).convert("RGB")
    # Audiobook covers are square: centre-crop the print cover.
    w, h = img.size; s = min(w, h); img = img.crop(((w - s) // 2, (h - s) // 2, (w - s) // 2 + s, (h - s) // 2 + s))
    img.save(f"{OUT}/covers/cover-{i}.png"); print(title, cover, img.size)
    lines.append(f"{title}|{author}")
open(f"{OUT}/titles.txt", "w").write("\n".join(lines) + "\n")
