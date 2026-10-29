"""The window: pick books, pick where transcripts go, pick a model from the list sorted by finish time, start."""
from __future__ import annotations

import json
import os
import queue
import subprocess
import sys
import threading
import time
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

from . import __version__, hardware as hw
from .cli import LANGUAGES
from .package import Book, default_outputs, place, scan
from .runner import measure, transcribe_book

# ReadAlong's dark theme (app/src/main/java/.../ui/theme).
BACKGROUND = "#111416"
CARD = "#1B1E21"
FIELD = "#23272A"
RAISED = "#2C3034"
OUTLINE = "#33373C"
TEXT = "#E7E9EC"
MUTED = "#BFC3C9"
FAINT = "#8A9097"
ACCENT = "#496B8A"
ACCENT_HOVER = "#5A7FA1"
PALE = "#D4E5F3"
SAGE = "#B4D6BF"
WARN = "#E8B4A0"


def _style(root: tk.Tk) -> None:
    style = ttk.Style(root)
    style.theme_use("clam")  # The only built-in theme that takes custom colours everywhere.
    from tkinter import font
    default = font.nametofont("TkDefaultFont")
    family = "Segoe UI" if sys.platform == "win32" else default.actual("family")
    size = 13 if sys.platform == "darwin" else 10
    for name in ("TkDefaultFont", "TkTextFont", "TkHeadingFont", "TkMenuFont"):
        font.nametofont(name).configure(family=family, size=size)
    root.option_add("*TCombobox*Listbox.background", FIELD)
    root.option_add("*TCombobox*Listbox.foreground", TEXT)
    root.option_add("*TCombobox*Listbox.selectBackground", ACCENT)
    root.configure(background=BACKGROUND)
    style.configure(".", background=BACKGROUND, foreground=TEXT, fieldbackground=FIELD, bordercolor=OUTLINE,
                    lightcolor=OUTLINE, darkcolor=OUTLINE, troughcolor=FIELD, focuscolor=ACCENT, font=(family, size))
    style.configure("Card.TFrame", background=CARD)
    style.configure("TLabel", background=BACKGROUND, foreground=TEXT)
    style.configure("Card.TLabel", background=CARD, foreground=TEXT)
    style.configure("Muted.TLabel", background=CARD, foreground=MUTED)
    style.configure("Faint.TLabel", background=CARD, foreground=FAINT)
    style.configure("Title.TLabel", background=BACKGROUND, foreground=TEXT, font=(family, size + 8, "bold"))
    style.configure("Chip.TLabel", background=BACKGROUND, foreground=SAGE, font=(family, size))
    style.configure("Heading.TLabel", background=CARD, foreground=PALE, font=(family, size + 2, "bold"))
    style.configure("Big.TLabel", background=CARD, foreground=TEXT, font=(family, size + 1, "bold"))
    style.configure("TButton", background=RAISED, foreground=TEXT, borderwidth=0, padding=(12, 6), relief="flat")
    style.map("TButton", background=[("disabled", FIELD), ("active", OUTLINE)], foreground=[("disabled", FAINT)])
    style.configure("Accent.TButton", background=ACCENT, foreground="#FFFFFF", padding=(22, 8), font=(family, size + 1, "bold"))
    style.map("Accent.TButton", background=[("disabled", FIELD), ("active", ACCENT_HOVER)], foreground=[("disabled", FAINT)])
    style.configure("TCheckbutton", background=CARD, foreground=MUTED, indicatorbackground=FIELD, indicatorforeground=PALE)
    style.map("TCheckbutton", background=[("active", CARD)], indicatorbackground=[("selected", ACCENT)])
    style.configure("TRadiobutton", background=CARD, foreground=TEXT, indicatorbackground=FIELD, indicatorforeground=PALE)
    style.map("TRadiobutton", background=[("active", CARD)], indicatorbackground=[("selected", ACCENT)])
    style.configure("TCombobox", fieldbackground=FIELD, background=RAISED, foreground=TEXT, arrowcolor=MUTED)
    style.map("TCombobox", fieldbackground=[("readonly", FIELD)], foreground=[("readonly", TEXT)],
              selectbackground=[("readonly", FIELD)], selectforeground=[("readonly", TEXT)])
    style.configure("Treeview", background=FIELD, fieldbackground=FIELD, foreground=TEXT, borderwidth=0,
                    rowheight=default.metrics("linespace") + 8)
    style.map("Treeview", background=[("selected", ACCENT)], foreground=[("selected", "#FFFFFF")])
    style.configure("Treeview.Heading", background=CARD, foreground=FAINT, borderwidth=0, relief="flat")
    style.map("Treeview.Heading", background=[("active", CARD)])
    for name, colour in (("Book", ACCENT), ("All", SAGE)):
        style.configure(f"{name}.Horizontal.TProgressbar", background=colour, troughcolor=FIELD, bordercolor=FIELD,
                        lightcolor=colour, darkcolor=colour, thickness=10)
    style.configure("Vertical.TScrollbar", background=RAISED, darkcolor=RAISED, lightcolor=RAISED, troughcolor=FIELD,
                    bordercolor=FIELD, arrowcolor=MUTED, gripcount=0)
    style.map("Vertical.TScrollbar", background=[("active", OUTLINE)])


def _settings_file() -> Path:
    return hw.settings_dir() / "settings.json"


def _load_settings() -> dict:
    try:
        return json.loads(_settings_file().read_text())
    except Exception:
        return {}


def _short(path: Path, limit: int = 48) -> str:
    text = str(path)
    return text if len(text) <= limit else text[:14] + "…" + text[-(limit - 15):]


def _open(path: Path) -> None:
    if not path.exists():
        path.mkdir(parents=True, exist_ok=True)
    if sys.platform == "win32":
        os.startfile(str(path))  # noqa: S606 (opening a folder the user chose)
    else:
        subprocess.Popen(["open" if sys.platform == "darwin" else "xdg-open", str(path)])


class App:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.books: list[Book] = []
        self.rows: list[dict] = []
        self.events: queue.Queue = queue.Queue()
        self.stop = threading.Event()
        self.machine = None
        self.running = False
        self.run: dict = {}
        settings = _load_settings()
        self.mode = tk.StringVar(value=settings.get("save", "beside"))
        self.outputs = Path(settings.get("outputs") or default_outputs())
        self.redo = tk.BooleanVar(value=False)

        root.title("ReadAlong Desktop")
        scale = max(1.0, root.winfo_fpixels("1i") / 96)  # Windows display scaling.
        width, height = int(920 * scale), min(int(920 * scale), root.winfo_screenheight() - 90)
        root.minsize(min(width, 820), min(height, 760))
        root.geometry(f"{width}x{height}")
        try:
            self.icon = tk.PhotoImage(file=str(Path(__file__).with_name("icon.png")))
            root.iconphoto(True, self.icon)
        except tk.TclError:
            self.icon = None
        _style(root)

        page = ttk.Frame(root, padding=(20, 16))
        page.pack(fill="both", expand=True)
        header = ttk.Frame(page)
        header.pack(fill="x", pady=(0, 12))
        if self.icon:
            self.small_icon = self.icon.subsample(5, 5)
            ttk.Label(header, image=self.small_icon).pack(side="left", padx=(0, 12))
        titles = ttk.Frame(header)
        titles.pack(side="left", fill="x", expand=True)
        ttk.Label(titles, text="ReadAlong Desktop", style="Title.TLabel").pack(anchor="w")
        self.hardware_label = ttk.Label(titles, text="Checking this computer's hardware…", style="Chip.TLabel")
        self.hardware_label.pack(anchor="w")
        ttk.Label(header, text=f"Version {__version__}", foreground=FAINT).pack(side="right", anchor="n")

        # Progress and start, pinned to the bottom so it never scrolls out of view.
        progress = self._card(page, None, None, side="bottom")
        top = ttk.Frame(progress, style="Card.TFrame")
        top.pack(fill="x")
        self.book_title = ttk.Label(top, text="Ready when you are", style="Big.TLabel")
        self.book_title.pack(side="left")
        self.book_percent = ttk.Label(top, text="", style="Muted.TLabel")
        self.book_percent.pack(side="right")
        self.book_bar = ttk.Progressbar(progress, maximum=1000, style="Book.Horizontal.TProgressbar")
        self.book_bar.pack(fill="x", pady=(6, 2))
        self.book_status = ttk.Label(progress, text="Add audiobooks, check the model, then press Start.", style="Faint.TLabel")
        self.book_status.pack(anchor="w")
        overall = ttk.Frame(progress, style="Card.TFrame")
        overall.pack(fill="x", pady=(8, 0))
        self.all_title = ttk.Label(overall, text="All books", style="Muted.TLabel")
        self.all_title.pack(side="left")
        self.all_percent = ttk.Label(overall, text="", style="Muted.TLabel")
        self.all_percent.pack(side="right")
        self.all_bar = ttk.Progressbar(progress, maximum=1000, style="All.Horizontal.TProgressbar")
        self.all_bar.pack(fill="x", pady=(6, 2))
        buttons = ttk.Frame(progress, style="Card.TFrame")
        buttons.pack(fill="x", pady=(8, 0))
        self.start_button = ttk.Button(buttons, text="Start", style="Accent.TButton", command=self.start)
        self.start_button.pack(side="right")
        self.stop_button = ttk.Button(buttons, text="Stop", command=self.request_stop, state="disabled")
        self.stop_button.pack(side="right", padx=8)
        self.stats = ttk.Label(buttons, text="", style="Faint.TLabel")
        self.stats.pack(side="left")

        # 1. Books
        books = self._card(page, "Audiobooks", "Add a book's folder, a single audio file, or your whole library folder.")
        self.books_card = books
        actions = ttk.Frame(books, style="Card.TFrame")
        actions.pack(fill="x", pady=(0, 8))
        ttk.Button(actions, text="Add folder…", command=self.add_folder).pack(side="left")
        ttk.Button(actions, text="Add file…", command=self.add_file).pack(side="left", padx=6)
        self.remove_button = ttk.Button(actions, text="Remove", command=self.remove, state="disabled")
        self.remove_button.pack(side="left")
        self.clear_button = ttk.Button(actions, text="Clear all", command=self.clear, state="disabled")
        self.clear_button.pack(side="left", padx=6)
        ttk.Checkbutton(actions, text="Redo books that have a transcript", variable=self.redo,
                        command=self.refresh_models).pack(side="right")
        self.found_label = ttk.Label(books, text="", style="Muted.TLabel")
        self.found_label.pack(side="bottom", anchor="w", pady=(6, 0))
        holder = ttk.Frame(books, style="Card.TFrame")
        holder.pack(fill="both", expand=True)
        self.book_list = ttk.Treeview(holder, columns=("length", "status"), height=4)
        self.book_list.heading("#0", text="Book", anchor="w")
        self.book_list.heading("length", text="Length", anchor="e")
        self.book_list.heading("status", text="Status", anchor="w")
        self.book_list.column("#0", width=440)
        self.book_list.column("length", width=100, anchor="e", stretch=False)
        self.book_list.column("status", width=200, stretch=False)
        self.book_list.tag_configure("shelf", foreground=PALE)
        self.book_list.tag_configure("done", foreground=SAGE)
        self.book_list.tag_configure("failed", foreground=WARN)
        scroll = ttk.Scrollbar(holder, orient="vertical", command=self.book_list.yview)
        self.book_list.configure(yscrollcommand=scroll.set)
        self.book_list.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")
        self.book_list.bind("<<TreeviewSelect>>", lambda _: self._update_buttons())
        self.book_list.bind("<Delete>", lambda _: self.remove())
        self.book_list.bind("<Double-1>", self._open_selected)

        # 2. Where transcripts go
        save = self._card(page, "Save transcripts", None)
        ttk.Radiobutton(save, text="Beside each book, so ReadAlong finds them when it scans your library folder",
                        variable=self.mode, value="beside", command=self._mode_changed).pack(anchor="w")
        row = ttk.Frame(save, style="Card.TFrame")
        row.pack(fill="x", pady=(4, 0))
        ttk.Radiobutton(row, text="Together in one folder:", variable=self.mode, value="folder",
                        command=self._mode_changed).pack(side="left")
        ttk.Button(row, text="Open", command=self.open_outputs).pack(side="right")
        self.change_button = ttk.Button(row, text="Change…", command=self.change_outputs)
        self.change_button.pack(side="right", padx=6)
        self.outputs_label = ttk.Label(row, text=_short(self.outputs), style="Muted.TLabel")
        self.outputs_label.pack(side="left", padx=6)

        # 3. Model
        models = self._card(page, "Model", "Sorted by how soon it would finish on this computer.")
        heading = models.winfo_children()[0]
        self.language = ttk.Combobox(heading, values=["Detect"] + LANGUAGES, width=9, state="readonly")
        self.language.set("Detect")
        self.language.pack(side="right")
        ttk.Label(heading, text="Language", style="Muted.TLabel").pack(side="right", padx=8)
        self.model_list = ttk.Treeview(models, columns=("model", "accuracy", "time", "download", "note"),
                                       show="headings", height=6, selectmode="browse")
        for column, title, width, anchor in (("model", "Model", 170, "w"), ("accuracy", "Accuracy", 110, "w"),
                                             ("time", "Estimated time", 150, "w"), ("download", "Download", 90, "e"),
                                             ("note", "", 200, "w")):
            self.model_list.heading(column, text=title, anchor=anchor)
            self.model_list.column(column, width=width, anchor=anchor)
        self.model_list.tag_configure("recommended", foreground=SAGE)
        self.model_list.pack(fill="x")
        self.model_list.bind("<<TreeviewSelect>>", lambda _: self._update_buttons())

        # Packed bottom-up so the book list is what shrinks on a short screen.
        for card in (progress, models, save, books):
            card.pack_forget()
        for card in (progress, models, save):
            card.pack(side="bottom", fill="x", pady=(10, 0))
        books.pack(side="top", fill="both", expand=True)

        self._mode_changed(save_settings=False)
        self._update_buttons()
        root.protocol("WM_DELETE_WINDOW", self.close)
        threading.Thread(target=self._detect, daemon=True).start()
        root.after(100, self.pump)
        root.after(1000, self.tick)

    def _card(self, parent, title: str | None, subtitle: str | None, side: str = "top") -> ttk.Frame:
        card = ttk.Frame(parent, style="Card.TFrame", padding=(16, 10))
        card.pack(side=side, fill="both", expand=title == "Audiobooks", pady=(10, 0) if side == "bottom" else (0, 10))
        if title:
            heading = ttk.Frame(card, style="Card.TFrame")
            heading.pack(fill="x", pady=(0, 8))
            ttk.Label(heading, text=title, style="Heading.TLabel").pack(side="left")
            if subtitle:
                ttk.Label(heading, text=subtitle, style="Faint.TLabel").pack(side="left", padx=10)
        return card

    # Hardware and models

    def _detect(self):
        self.events.put(("hardware", hw.detect()))

    def todo(self) -> list[Book]:
        return [b for b in self.books if self.redo.get() or not b.done]

    def refresh_models(self):
        if not self.machine:
            return
        todo = self.todo()
        seconds = sum(b.seconds for b in todo) or 3600.0
        chosen = self.model_list.selection()
        chosen_id = self.rows[self.model_list.index(chosen[0])]["model"].id if chosen and self.rows else None
        self.rows = hw.choices(self.machine, seconds)
        self.model_list.delete(*self.model_list.get_children())
        for row in self.rows:
            note = ("Recommended" if row["recommended"] else "") + (" · measured here" if row["measured"] else "")
            when = hw.duration_text(row["seconds"]) + ("" if todo else " per hour")
            size = row["model"].size_gb
            download = f"{size * 1000:.0f} MB" if size < 1 else f"{size:.1f} GB"
            item = self.model_list.insert("", "end", values=(row["model"].name, row["model"].accuracy, when, download,
                                                             note.strip(" ·")),
                                          tags=("recommended",) if row["recommended"] else ())
            if row["model"].id == chosen_id or (chosen_id is None and row["recommended"]):
                self.model_list.selection_set(item)
        self._update_buttons()

    # Books

    def add_folder(self):
        folder = filedialog.askdirectory(title="Choose an audiobook folder or your library folder")
        if folder:
            self._add(Path(folder))

    def add_file(self):
        file = filedialog.askopenfilename(title="Choose an audiobook file", filetypes=[
            ("Audio", "*.m4b *.m4a *.mp3 *.mp4 *.aac *.ogg *.opus *.flac *.wav"), ("All files", "*")])
        if file:
            self._add(Path(file))

    def _add(self, path: Path):
        self.found_label.config(text="Looking for audiobooks…")

        def work():
            try:
                kind, found = scan(path)
                measure(found)
                self.events.put(("books", (kind, path, found)))
            except Exception as failure:
                self.events.put(("books", ("error", path, failure)))
        threading.Thread(target=work, daemon=True).start()

    def show_books(self, kind: str, path: Path, found):
        if kind == "error":
            self.found_label.config(text=f"Couldn't read {path.name}: {found}")
            return
        known = {tuple(b.files) for b in self.books}
        new = [b for b in found if tuple(b.files) not in known]
        if not found:
            self.found_label.config(text=f"No audiobooks found in {path.name}.")
            return
        place(self.books + new, self.outputs if self.mode.get() == "folder" else None)
        parent = ""
        if kind == "library":
            shelf = f"shelf:{path}"
            if not self.book_list.exists(shelf):
                self.book_list.insert("", "end", iid=shelf, text=f"Library: {path.name}", open=True, tags=("shelf",),
                                      values=("", "Library folder"))
            parent = shelf
        for book in new:
            self.books.append(book)
            self.book_list.insert(parent, "end", iid=str(id(book)), text=book.name,
                                  values=(hw.duration_text(book.seconds), self._state(book)),
                                  tags=("done",) if book.done else ())
        self._update_shelves()
        what = {"library": f"Library folder: {len(found)} book(s)", "book": "Audiobook folder: 1 book",
                "file": "Audio file: 1 book"}[kind]
        self.found_label.config(text=what + (f", {len(found) - len(new)} already listed" if len(new) < len(found) else ""))
        self.refresh_models()

    def _state(self, book: Book) -> str:
        return "Has transcript" if book.done else "Ready"

    def _update_shelves(self):
        for shelf in self.book_list.get_children(""):
            if shelf.startswith("shelf:"):
                children = self.book_list.get_children(shelf)
                if not children:
                    self.book_list.delete(shelf)
                else:
                    total = sum(b.seconds for b in self.books if str(id(b)) in children)
                    self.book_list.item(shelf, values=(hw.duration_text(total), f"Library folder · {len(children)} books"))

    def _book(self, iid: str) -> Book | None:
        return next((b for b in self.books if str(id(b)) == iid), None)

    def remove(self):
        if self.running:
            return
        for iid in self.book_list.selection():
            if not self.book_list.exists(iid):
                continue
            targets = self.book_list.get_children(iid) if iid.startswith("shelf:") else (iid,)
            self.books = [b for b in self.books if str(id(b)) not in targets]
            self.book_list.delete(iid)
        self._update_shelves()
        self.found_label.config(text=f"{len(self.books)} book(s) listed.")
        self.refresh_models()

    def clear(self):
        if self.running:
            return
        self.books.clear()
        self.book_list.delete(*self.book_list.get_children())
        self.found_label.config(text="")
        self.refresh_models()

    def _open_selected(self, _event=None):
        selection = self.book_list.selection()
        book = self._book(selection[0]) if selection else None
        if book:
            _open(book.output.parent if book.output.exists() else book.files[0].parent)

    # Output folder

    def _mode_changed(self, save_settings: bool = True):
        folder = self.mode.get() == "folder"
        self.change_button.config(state="normal" if folder and not self.running else "disabled")
        self.outputs_label.config(foreground=MUTED if folder else FAINT)
        place(self.books, self.outputs if folder else None)
        for book in self.books:
            if self.book_list.exists(str(id(book))):
                self.book_list.set(str(id(book)), "status", self._state(book))
        if save_settings:
            self._save_settings()
            self.refresh_models()

    def change_outputs(self):
        chosen = filedialog.askdirectory(title="Choose where to save transcripts", initialdir=str(self.outputs.parent))
        if chosen:
            chosen = Path(chosen)
            self.outputs = chosen if chosen.name == default_outputs().name else chosen / default_outputs().name
            self.outputs_label.config(text=_short(self.outputs))
            self._mode_changed()

    def open_outputs(self):
        try:
            if self.mode.get() == "folder":
                _open(self.outputs)
            else:
                book = next((b for b in self.books), None)
                if book:
                    _open(book.beside.parent)
                else:
                    messagebox.showinfo("ReadAlong Desktop", "Transcripts are saved beside each book. Add a book first.")
        except Exception as failure:
            messagebox.showerror("ReadAlong Desktop", f"Couldn't open the folder: {failure}")

    def _save_settings(self):
        try:
            _settings_file().write_text(json.dumps({"save": self.mode.get(), "outputs": str(self.outputs)}, indent=1))
        except OSError:
            pass

    # Running

    def _update_buttons(self):
        idle = not self.running
        self.remove_button.config(state="normal" if idle and self.book_list.selection() else "disabled")
        self.clear_button.config(state="normal" if idle and self.books else "disabled")
        ready = idle and self.machine is not None and bool(self.todo()) and bool(self.model_list.selection())
        self.start_button.config(state="normal" if ready else "disabled")
        self.stop_button.config(state="normal" if self.running and not self.stop.is_set() else "disabled")
        if idle and self.machine is not None and self.books and not self.todo():
            self.found_label.config(text="Every book listed already has a transcript. Tick “Redo” to make new ones.")

    def start(self):
        todo = self.todo()
        selection = self.model_list.selection()
        if not todo or not selection:
            return
        row = self.rows[self.model_list.index(selection[0])]
        model = row["model"].id
        language = None if self.language.get() == "Detect" else self.language.get()
        place(self.books, self.outputs if self.mode.get() == "folder" else None)
        self.stop.clear()
        self.running = True
        total = sum(b.seconds for b in todo)
        self.run = {"total": total, "finished": 0.0, "book": None, "book_done": 0.0, "book_total": 0.0,
                    "index": 0, "count": len(todo), "began": None, "speed": row["times"], "measured_from": 0.0}
        self._update_buttons()
        self.change_button.config(state="disabled")
        self.book_title.config(text=f"Loading {row['model'].name}…")
        self.book_status.config(text="The model downloads once, the first time you use it. This can take a few minutes.")
        self.book_bar.config(mode="indeterminate")
        self.book_bar.start(12)
        where = f"in {self.outputs}" if self.mode.get() == "folder" else "beside each book"
        threading.Thread(target=self._work, args=(todo, model, language, where), daemon=True).start()

    def request_stop(self):
        self.stop.set()
        self.book_status.config(text="Stopping after the part being transcribed now…")
        self._update_buttons()

    def _work(self, todo: list[Book], model: str, language: str | None, where: str):
        """Runs on a worker thread: only touches the window through self.events."""
        from .engines import load
        try:
            engine = load(model, self.machine, log=lambda text: self.events.put(("note", text)))
        except Exception as failure:
            self.events.put(("finished", f"Couldn't load the model: {failure}"))
            return
        self.events.put(("loaded", engine.device))
        finished = 0
        for index, book in enumerate(todo):
            if self.stop.is_set():
                break
            self.events.put(("book", (book, index, "Transcribing…")))
            try:
                transcribe_book(book, engine, model, self.machine, language,
                                lambda p: self.events.put(("progress", p)), self.stop.is_set)
                finished += 1
                self.events.put(("book done", (book, "Done")))
            except InterruptedError:
                self.events.put(("book done", (book, "Stopped")))
                break
            except Exception as failure:
                self.events.put(("book done", (book, f"Failed: {failure}"[:80])))
        self.events.put(("finished", f"Finished {finished} of {len(todo)} book(s). Transcripts are saved {where}."))

    def pump(self):
        try:
            while True:
                kind, value = self.events.get_nowait()
                if kind == "hardware":
                    self.machine = value
                    self.hardware_label.config(text=value.describe())
                    self.refresh_models()
                elif kind == "note":
                    self.book_status.config(text=value)
                elif kind == "loaded":
                    self.book_bar.stop()
                    self.book_bar.config(mode="determinate", value=0)
                    self.run["began"] = time.monotonic()
                elif kind == "book":
                    book, index, text = value
                    self.run.update(book=book, index=index, book_done=0.0, book_total=book.seconds)
                    self.book_title.config(text=book.name)
                    self._set_status(book, text)
                elif kind == "progress":
                    self.run["book_done"] = value.done_seconds
                    self.run["book_total"] = value.total_seconds or self.run["book_total"]
                    self.book_status.config(text=value.status)
                    self._draw()
                elif kind == "book done":
                    book, text = value
                    self.run["finished"] += book.seconds if text == "Done" else 0
                    self.run["book_done"] = 0.0
                    self._set_status(book, text, "done" if text == "Done" else "failed" if text.startswith("Failed") else "")
                    self._draw()
                elif kind == "finished":
                    self.running = False
                    self.book_bar.stop()
                    self.book_bar.config(mode="determinate")
                    self.book_title.config(text="Finished" if not self.stop.is_set() else "Stopped")
                    self.book_status.config(text=value)
                    self.stats.config(text=self._elapsed_text())
                    self.change_button.config(state="normal" if self.mode.get() == "folder" else "disabled")
                    self.refresh_models()
                elif kind == "books":
                    self.show_books(*value)
        except queue.Empty:
            pass
        self.root.after(150, self.pump)

    def _set_status(self, book: Book, text: str, tag: str = ""):
        iid = str(id(book))
        if self.book_list.exists(iid):
            self.book_list.set(iid, "status", text)
            self.book_list.item(iid, tags=(tag,) if tag else ())

    def _draw(self):
        run = self.run
        if not run:
            return
        book_fraction = min(1.0, run["book_done"] / run["book_total"]) if run["book_total"] else 0.0
        done = run["finished"] + run["book_done"]
        all_fraction = min(1.0, done / run["total"]) if run["total"] else 0.0
        self.book_bar.config(value=book_fraction * 1000)
        self.book_percent.config(text=f"{book_fraction * 100:.0f}%")
        self.all_bar.config(value=all_fraction * 1000)
        self.all_title.config(text=f"All books · book {run['index'] + 1} of {run['count']}")
        self.all_percent.config(text=f"{all_fraction * 100:.0f}%")

    def _elapsed_text(self) -> str:
        began = self.run.get("began") if self.run else None
        return f"Took {hw.duration_text(time.monotonic() - began)}" if began else ""

    def tick(self):
        """Once a second while running: speed and time left, so the window never looks stuck."""
        run = self.run
        if self.running and run.get("began"):
            elapsed = time.monotonic() - run["began"]
            done = run["finished"] + run["book_done"]
            if done > 0 and elapsed > 15:
                run["speed"] = done / elapsed
            speed = max(run["speed"], 0.01)
            book_left = max(0.0, run["book_total"] - run["book_done"]) / speed
            all_left = max(0.0, run["total"] - done) / speed
            parts = [f"{speed:.0f}× real time" if speed >= 2 else f"{speed:.1f}× real time",
                     f"about {hw.duration_text(book_left)} left for this book"]
            if run["count"] > 1:
                parts.append(f"{hw.duration_text(all_left)} left for all")
            parts.append(f"running {hw.duration_text(elapsed)}")
            self.stats.config(text="  ·  ".join(parts))
        self.root.after(1000, self.tick)

    def close(self):
        if self.running and not messagebox.askyesno("ReadAlong Desktop", "A transcription is running. Stop it and quit?"):
            return
        self.stop.set()
        self.root.destroy()


def _hide_own_console():
    """Double-clicked on Windows: hide the console window that comes with the terminal-capable build."""
    if sys.platform != "win32":
        return
    import ctypes
    kernel = ctypes.windll.kernel32
    processes = (ctypes.c_uint * 2)()
    if kernel.GetConsoleProcessList(processes, 2) == 1:  # Nobody else is using this console.
        ctypes.windll.user32.ShowWindow(kernel.GetConsoleWindow(), 0)


def run() -> int:
    _hide_own_console()
    if sys.platform == "win32":
        try:
            import ctypes
            ctypes.windll.shcore.SetProcessDpiAwareness(1)  # Sharp text on high-DPI screens.
        except Exception:
            pass
    root = tk.Tk()
    App(root)
    root.mainloop()
    return 0
