"""What this computer can run Whisper on, and how fast each model should go on it."""
from __future__ import annotations

import importlib.util
import json
import os
import platform
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

from . import gpu_libraries


@dataclass
class Hardware:
    accelerator: str  # "cuda", "metal" or "cpu"
    cpu: str
    cores: int
    gpu: str | None = None
    vram_gb: float | None = None

    def describe(self) -> str:
        if self.accelerator == "cuda":
            memory = f", {self.vram_gb:.0f} GB" if self.vram_gb else ""
            return f"NVIDIA GPU: {self.gpu or 'detected'}{memory}"
        if self.accelerator == "metal":
            return f"Apple GPU: {self.gpu or self.cpu}"
        return f"CPU: {self.cpu} ({self.cores} threads). No supported GPU found."


def _quiet(command: list[str]) -> str:
    try:
        flags = {"creationflags": 0x08000000} if sys.platform == "win32" else {}  # CREATE_NO_WINDOW
        return subprocess.run(command, capture_output=True, text=True, timeout=10, **flags).stdout.strip()
    except Exception:
        return ""


def _cpu_name() -> str:
    if sys.platform == "darwin":
        return _quiet(["sysctl", "-n", "machdep.cpu.brand_string"]) or platform.processor() or "Mac"
    if sys.platform.startswith("linux"):
        try:
            for line in Path("/proc/cpuinfo").read_text().splitlines():
                if line.lower().startswith("model name"):
                    return line.split(":", 1)[1].strip()
        except OSError:
            pass
    return platform.processor() or platform.machine() or "Unknown CPU"


def _nvidia() -> tuple[str | None, float | None]:
    line = _quiet(["nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader,nounits"]).splitlines()
    if not line:
        return None, None
    name, _, memory = line[0].partition(",")
    try:
        return name.strip(), float(memory) / 1024
    except ValueError:
        return name.strip(), None


def detect(force: str | None = None) -> Hardware:
    cpu, cores = _cpu_name(), os.cpu_count() or 4
    if force in (None, "auto", "metal") and sys.platform == "darwin" and platform.machine() == "arm64" \
            and importlib.util.find_spec("mlx_whisper"):
        return Hardware("metal", cpu, cores, gpu=cpu)
    if force in (None, "auto", "cuda") and sys.platform != "darwin":
        gpu_libraries.load()
        try:
            import ctranslate2
            if ctranslate2.get_cuda_device_count() > 0:
                name, vram = _nvidia()
                return Hardware("cuda", cpu, cores, gpu=name, vram_gb=vram)
        except Exception:
            pass
    return Hardware("cpu", cpu, cores)


@dataclass
class Model:
    id: str
    name: str
    accuracy: str
    size_gb: float  # Download size, roughly.


MODELS = [
    Model("tiny", "Tiny", "Rough", 0.08),
    Model("base", "Base", "Fair", 0.15),
    Model("small", "Small", "Good", 0.5),
    Model("medium", "Medium", "Very good", 1.5),
    Model("large-v3-turbo", "Large v3 Turbo", "Excellent", 1.6),
    Model("large-v3", "Large v3", "Best", 3.1),
]

# Times faster than real time on a typical machine of each kind (mid-range NVIDIA card, base Apple
# M-series chip, 8-thread CPU at int8). Rough; a finished book replaces them with measured speeds.
SPEED = {
    "cuda": {"tiny": 300, "base": 250, "small": 150, "medium": 70, "large-v3-turbo": 110, "large-v3": 45},
    "metal": {"tiny": 120, "base": 90, "small": 45, "medium": 18, "large-v3-turbo": 30, "large-v3": 10},
    "cpu": {"tiny": 45, "base": 28, "small": 10, "medium": 3.5, "large-v3-turbo": 3, "large-v3": 1.5},
}


def settings_dir() -> Path:
    base = Path(os.environ.get("APPDATA") or Path.home() / ".config") if sys.platform != "darwin" \
        else Path.home() / "Library" / "Application Support"
    folder = base / "ReadAlong Desktop"
    folder.mkdir(parents=True, exist_ok=True)
    return folder


def _measured() -> dict:
    try:
        return json.loads((settings_dir() / "speeds.json").read_text())
    except Exception:
        return {}


def remember_speed(hardware: Hardware, model: str, times_real_time: float) -> None:
    speeds = _measured()
    key = f"{hardware.accelerator}:{model}"
    old = speeds.get(key)
    speeds[key] = round(times_real_time if old is None else old * 0.5 + times_real_time * 0.5, 2)
    try:
        (settings_dir() / "speeds.json").write_text(json.dumps(speeds, indent=1))
    except OSError:
        pass


def speed(hardware: Hardware, model: str) -> tuple[float, bool]:
    """(times real time, measured on this computer)."""
    measured = _measured().get(f"{hardware.accelerator}:{model}")
    if measured:
        return float(measured), True
    base = SPEED[hardware.accelerator][model]
    if hardware.accelerator == "cpu":
        base *= min(3.0, max(0.25, hardware.cores / 8))
    elif hardware.accelerator == "metal":
        name = (hardware.gpu or "").lower()
        base *= 2.2 if "max" in name or "ultra" in name else 1.6 if "pro" in name else 1.0
        generation = re.search(r"\bm(\d)", name)
        if generation:
            base *= 1 + 0.15 * (int(generation.group(1)) - 1)
    elif hardware.accelerator == "cuda":
        name = (hardware.gpu or "").lower()
        series = re.search(r"rtx\s*(\d)0", name)
        if series:
            base *= {2: 0.7, 3: 1.0, 4: 1.5, 5: 2.0}.get(int(series.group(1)), 1.0)
        elif "gtx" in name:
            base *= 0.4
        if hardware.vram_gb and hardware.vram_gb < 5 and model.startswith(("medium", "large")):
            base *= 0.7  # Runs in int8 to fit.
    return base, False


def choices(hardware: Hardware, audio_seconds: float) -> list[dict]:
    """Every model this computer can run, fastest finish first, with the recommended one marked."""
    rows = []
    for model in MODELS:
        if hardware.accelerator == "cuda" and hardware.vram_gb and hardware.vram_gb < 3 and model.id.startswith("large"):
            continue
        times, measured = speed(hardware, model.id)
        rows.append({"model": model, "times": times, "measured": measured, "seconds": audio_seconds / times})
    rows.sort(key=lambda row: row["seconds"])
    recommended = {"cuda": "large-v3-turbo", "metal": "large-v3-turbo"}.get(hardware.accelerator,
                                                                           "medium" if hardware.cores >= 16 else "small")
    for row in rows:
        row["recommended"] = row["model"].id == recommended
    return rows


def duration_text(seconds: float) -> str:
    minutes = max(1, round(seconds / 60))
    if minutes < 60:
        return f"{minutes} min"
    return f"{minutes // 60} h {minutes % 60:02d} min"
