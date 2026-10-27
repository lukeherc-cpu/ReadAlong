"""The window: pick books, pick a model from the list sorted by finish time, start."""
from __future__ import annotations

import queue
import sys
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

from . import __version__, hardware as hw
from .cli import LANGUAGES
from .package import Book, find_books
from .runner import measure, transcribe_book


class App:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.books: list[Book] = []
        self.rows: list[dict] = []
        self.events: queue.Queue = queue.Queue()
        self.stop = threading.Event()
        self.machine = None
        root.title(f"ReadAlong Desktop {__version__}")
        root.minsize(760, 560)
        frame = ttk.Frame(root, padding=12)
        frame.pack(fill="both", expand=True)

        self.hardware_label = ttk.Label(frame, text="Checking this computer's hardware…", font=("", 11, "bold"))
        self.hardware_label.pack(anchor="w")

        picks = ttk.Frame(frame)
        picks.pack(fill="x", pady=(10, 4))
        ttk.Label(picks, text="1. Choose audiobooks").pack(side="left")
        ttk.Button(picks, text="Add folder…", command=self.add_folder).pack(side="right")
        ttk.Button(picks, text="Add file…", command=self.add_file).pack(side="right", padx=6)
        ttk.Button(picks, text="Clear", command=self.clear).pack(side="right")
        self.book_list = ttk.Treeview(frame, columns=("length", "status"), height=6)
        self.book_list.heading("#0", text="Book"); self.book_list.heading("length", text="Length"); self.book_list.heading("status", text="Status")
        self.book_list.column("#0", width=430); self.book_list.column("length", width=100, anchor="e"); self.book_list.column("status", width=180)
        self.book_list.pack(fill="both", expand=True)
        self.redo = tk.BooleanVar(value=False)
        ttk.Checkbutton(frame, text="Redo books that already have a transcript", variable=self.redo, command=self.refresh_models).pack(anchor="w", pady=(4, 0))

        ttk.Label(frame, text="2. Choose a model (fastest to finish first)").pack(anchor="w", pady=(10, 4))
        self.model_list = ttk.Treeview(frame, columns=("model", "accuracy", "time", "note"), show="headings", height=6, selectmode="browse")
        for column, title, width in (("model", "Model", 160), ("accuracy", "Accuracy", 110), ("time", "Estimated time", 140), ("note", "", 200)):
            self.model_list.heading(column, text=title); self.model_list.column(column, width=width)
        self.model_list.pack(fill="x")

        options = ttk.Frame(frame)
        options.pack(fill="x", pady=(8, 0))
        ttk.Label(options, text="Language:").pack(side="left")
        self.language = ttk.Combobox(options, values=["Detect"] + LANGUAGES, width=8, state="readonly")
        self.language.set("Detect")
        self.language.pack(side="left", padx=6)
        self.start_button = ttk.Button(options, text="3. Start", command=self.start)
        self.start_button.pack(side="right")
        self.stop_button = ttk.Button(options, text="Stop", command=self.stop.set, state="disabled")
        self.stop_button.pack(side="right", padx=6)

        self.progress = ttk.Progressbar(frame, maximum=1000)
        self.progress.pack(fill="x", pady=(10, 2))
        self.status = ttk.Label(frame, text="Transcripts are saved beside each book as a .readalong file. "
                                            "Copy it to your phone with the audio, or keep it in your synced library folder.", wraplength=720)
        self.status.pack(anchor="w")
        threading.Thread(target=self._detect, daemon=True).start()
        root.after(100, self.pump)

    def _detect(self):
        self.events.put(("hardware", hw.detect()))

    def pump(self):
        try:
            while True:
                kind, value = self.events.get_nowait()
                if kind == "hardware":
                    self.machine = value
                    self.hardware_label.config(text=value.describe())
                    self.refresh_models()
                elif kind == "progress":
                    self.progress["value"] = value.fraction * 1000
                    self.status.config(text=f"{value.book}: {value.status}")
                elif kind == "book":
                    book, text = value
                    if self.book_list.exists(str(id(book))):
                        self.book_list.set(str(id(book)), "status", text)
                elif kind == "finished":
                    self.start_button.config(state="normal"); self.stop_button.config(state="disabled")
                    self.status.config(text=value)
                    self.refresh_models()
                elif kind == "books":
                    self.show_books(value)
        except queue.Empty:
            pass
        self.root.after(150, self.pump)

    def add_folder(self):
        folder = filedialog.askdirectory(title="Choose an audiobook folder or your library folder")
        if folder:
            self._add(Path(folder))

    def add_file(self):
        file = filedialog.askopenfilename(title="Choose an audiobook file",
                                          filetypes=[("Audio", "*.m4b *.m4a *.mp3 *.mp4 *.aac *.ogg *.opus *.flac *.wav"), ("All files", "*")])
        if file:
            self._add(Path(file))

    def _add(self, path: Path):
        self.status.config(text="Looking for audiobooks…")

        def scan():
            found = find_books(path)
            measure(found)
            self.events.put(("books", found))
        threading.Thread(target=scan, daemon=True).start()

    def show_books(self, found: list[Book]):
        known = {tuple(b.files) for b in self.books}
        for book in found:
            if tuple(book.files) in known:
                continue
            self.books.append(book)
            self.book_list.insert("", "end", iid=str(id(book)), text=book.name,
                                  values=(hw.duration_text(book.seconds), "Has transcript" if book.done else "Ready"))
        self.status.config(text=f"{len(self.books)} book(s) chosen." if found else "No audiobooks found there.")
        self.refresh_models()

    def clear(self):
        self.books.clear()
        self.book_list.delete(*self.book_list.get_children())
        self.refresh_models()

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
            note = ("Recommended" if row["recommended"] else "") + (" · measured" if row["measured"] else "")
            time = hw.duration_text(row["seconds"]) + ("" if todo else " per hour")
            item = self.model_list.insert("", "end", values=(row["model"].name, row["model"].accuracy, time, note.strip(" ·")))
            if row["model"].id == chosen_id or (chosen_id is None and row["recommended"]):
                self.model_list.selection_set(item)

    def start(self):
        todo = self.todo()
        selection = self.model_list.selection()
        if not todo:
            messagebox.showinfo("ReadAlong Desktop", "Add an audiobook first (or tick “Redo” for books that already have a transcript).")
            return
        if not selection:
            messagebox.showinfo("ReadAlong Desktop", "Choose a model.")
            return
        model = self.rows[self.model_list.index(selection[0])]["model"].id
        language = None if self.language.get() == "Detect" else self.language.get()
        self.stop.clear()
        self.start_button.config(state="disabled"); self.stop_button.config(state="normal")
        threading.Thread(target=self._work, args=(todo, model, language), daemon=True).start()

    def _work(self, todo: list[Book], model: str, language: str | None):
        from .engines import load
        try:
            self.events.put(("progress", _Note("Loading", f"Loading {model} (downloaded once on first use)…")))
            engine = load(model, self.machine, log=lambda text: self.events.put(("progress", _Note("Note", text))))
        except Exception as failure:
            self.events.put(("finished", f"Couldn't load the model: {failure}"))
            return
        finished = 0
        for book in todo:
            if self.stop.is_set():
                break
            self.events.put(("book", (book, "Transcribing…")))
            try:
                transcribe_book(book, engine, model, self.machine, language,
                                lambda p: self.events.put(("progress", p)), self.stop.is_set)
                finished += 1
                self.events.put(("book", (book, "Done")))
            except InterruptedError:
                self.events.put(("book", (book, "Stopped")))
                break
            except Exception as failure:
                self.events.put(("book", (book, f"Failed: {failure}"[:60])))
        self.events.put(("finished", f"Finished {finished} of {len(todo)} book(s). Transcripts are saved beside each book."))


class _Note:
    def __init__(self, book, status):
        self.book, self.status, self.fraction = book, status, 0.0


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
    root = tk.Tk()
    try:
        if root.tk.call("tk", "windowingsystem") == "x11":
            ttk.Style().theme_use("clam")
    except tk.TclError:
        pass
    App(root)
    root.mainloop()
    return 0
