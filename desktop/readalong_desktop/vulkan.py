"""Finds Vulkan GPUs (AMD, Intel, NVIDIA) for whisper.cpp, and runs the bundled whisper-cli on them.

Both run as separate programs with the system's libraries. Inside the bundled app the graphics driver often
can't load at all: on Linux the bundle's libstdc++ is older than the one a current Mesa was built against.
"""
from __future__ import annotations

import ctypes
import json
import os
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

import numpy as np

DISCRETE, INTEGRATED, CPU = 2, 1, 4
PROBE_FLAG = "--vulkan-probe"

GGML_FILES = {
    "tiny": "ggml-tiny.bin",
    "base": "ggml-base.bin",
    "small": "ggml-small.bin",
    "medium": "ggml-medium.bin",
    "large-v3-turbo": "ggml-large-v3-turbo.bin",
    "large-v3": "ggml-large-v3.bin",
}


class _CreateInfo(ctypes.Structure):
    _fields_ = [("sType", ctypes.c_uint32), ("pNext", ctypes.c_void_p), ("flags", ctypes.c_uint32),
                ("pApplicationInfo", ctypes.c_void_p), ("enabledLayerCount", ctypes.c_uint32),
                ("ppEnabledLayerNames", ctypes.c_void_p), ("enabledExtensionCount", ctypes.c_uint32),
                ("ppEnabledExtensionNames", ctypes.c_void_p)]


def devices() -> list[dict]:
    """Every Vulkan device: name, type (2 discrete, 1 integrated, 4 software) and its largest GPU memory heap."""
    library = ctypes.CDLL("vulkan-1.dll" if sys.platform == "win32" else "libvulkan.so.1")
    instance = ctypes.c_void_p()
    if library.vkCreateInstance(ctypes.byref(_CreateInfo(sType=1)), None, ctypes.byref(instance)) != 0:
        return []
    found = []
    try:
        count = ctypes.c_uint32()
        library.vkEnumeratePhysicalDevices(instance, ctypes.byref(count), None)
        handles = (ctypes.c_void_p * count.value)()
        library.vkEnumeratePhysicalDevices(instance, ctypes.byref(count), handles)
        for handle in handles[:count.value]:
            # VkPhysicalDeviceProperties: deviceType at byte 16, deviceName[256] at byte 20.
            properties = ctypes.create_string_buffer(4096)
            library.vkGetPhysicalDeviceProperties(ctypes.c_void_p(handle), properties)
            kind = int.from_bytes(properties.raw[16:20], "little")
            name = properties.raw[20:276].split(b"\0", 1)[0].decode(errors="replace")
            # VkPhysicalDeviceMemoryProperties: heap count at byte 260, heaps {u64 size, u32 flags} from 264.
            memory = ctypes.create_string_buffer(1024)
            library.vkGetPhysicalDeviceMemoryProperties(ctypes.c_void_p(handle), memory)
            heaps = int.from_bytes(memory.raw[260:264], "little")
            local = [int.from_bytes(memory.raw[264 + 16 * i:272 + 16 * i], "little") for i in range(min(heaps, 16))
                     if int.from_bytes(memory.raw[272 + 16 * i:276 + 16 * i], "little") & 1]
            found.append({"name": name, "type": kind, "vram_gb": round(max(local, default=0) / 2 ** 30, 1)})
    finally:
        library.vkDestroyInstance(instance, None)
    return found


def probe() -> list[dict]:
    """The Vulkan devices, from the bundled vkprobe program, or from a child Python when run from source."""
    if sys.platform == "darwin":
        return []
    helper = bundled("vkprobe")
    if helper:
        command = [str(helper)]
    elif getattr(sys, "frozen", False):
        command = [sys.executable, PROBE_FLAG]
    else:
        command = [sys.executable, "-m", "readalong_desktop", PROBE_FLAG]
    try:
        output = subprocess.run(command, capture_output=True, text=True, timeout=30, env=system_env() if helper else None,
                                **_no_window()).stdout
        return json.loads(output.strip().splitlines()[-1])
    except Exception:
        return []


def system_env() -> dict:
    """This process's environment without the bundle's library folder, for programs that load graphics drivers."""
    env = os.environ.copy()
    if getattr(sys, "frozen", False) and sys.platform.startswith("linux"):
        original = env.pop("LD_LIBRARY_PATH_ORIG", None)
        env.pop("LD_LIBRARY_PATH", None)
        if original:
            env["LD_LIBRARY_PATH"] = original
    return env


def _no_window() -> dict:
    return {"creationflags": 0x08000000} if sys.platform == "win32" else {}  # CREATE_NO_WINDOW


def print_devices() -> int:
    try:
        print(json.dumps(devices()))
    except Exception:
        print("[]")
    return 0


def best(found: list[dict], any_kind: bool = False) -> dict | None:
    """The GPU whisper.cpp will use: a discrete card (it skips integrated ones when there is one)."""
    usable = [d for d in found if d["type"] == DISCRETE or (any_kind and d["type"] in (INTEGRATED, CPU))]
    return max(usable, key=lambda d: (d["type"] == DISCRETE, d["vram_gb"]), default=None)


def cli_path() -> Path | None:
    """The whisper-cli built with Vulkan."""
    return bundled("whisper-cli")


def bundled(program: str) -> Path | None:
    """A helper program in the app's whisper folder, or in desktop/whisper-bin when run from source."""
    name = program + (".exe" if sys.platform == "win32" else "")
    if getattr(sys, "frozen", False):
        candidate = Path(getattr(sys, "_MEIPASS", Path(sys.executable).parent)) / "whisper" / name
    else:
        candidate = Path(__file__).resolve().parent.parent / "whisper-bin" / name
    if not candidate.is_file():
        return None
    if sys.platform != "win32" and not os.access(candidate, os.X_OK):
        try:
            candidate.chmod(0o755)  # Bundled as data, which may lose the executable bit.
        except OSError:
            return None
    return candidate


def model_file(model: str) -> Path:
    from huggingface_hub import hf_hub_download
    return Path(hf_hub_download("ggerganov/whisper.cpp", GGML_FILES[model]))


def run(cli: Path, model: Path, samples: np.ndarray, rate: int, language: str | None, threads: int) -> dict:
    """whisper-cli on one window of 16 kHz mono audio, one segment per word; returns its JSON."""
    with tempfile.TemporaryDirectory(prefix="readalong-") as folder:
        audio = Path(folder) / "window.wav"
        with wave.open(str(audio), "wb") as out:
            out.setnchannels(1)
            out.setsampwidth(2)
            out.setframerate(rate)
            out.writeframes((np.clip(samples, -1, 1) * 32767).astype("<i2").tobytes())
        base = Path(folder) / "window"
        command = [str(cli), "-m", str(model), "-f", str(audio), "-l", language or "auto", "-t", str(threads),
                   "-ml", "1", "-sow", "-oj", "-of", str(base), "-np"]
        done = subprocess.run(command, capture_output=True, text=True, errors="replace", env=system_env(), **_no_window())
        result = base.with_suffix(".json")
        if done.returncode != 0 or not result.is_file():
            tail = (done.stderr or done.stdout).strip().splitlines()[-3:]
            raise RuntimeError(f"whisper.cpp failed ({done.returncode}): {' '.join(tail)}")
        return json.loads(result.read_text(encoding="utf-8", errors="replace"))
