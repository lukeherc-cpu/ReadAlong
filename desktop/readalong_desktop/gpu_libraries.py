"""Makes the bundled NVIDIA libraries (cuBLAS, cuDNN from the nvidia-* wheels) findable before CTranslate2 loads.

Without this, CTranslate2 only finds them when CUDA is installed system-wide.
"""
from __future__ import annotations

import ctypes
import os
import sys
from pathlib import Path

_done = False


def _roots() -> list[Path]:
    roots = []
    if getattr(sys, "frozen", False):
        roots.append(Path(getattr(sys, "_MEIPASS", Path(sys.executable).parent)) / "nvidia")
    for entry in sys.path:
        candidate = Path(entry) / "nvidia"
        if candidate.is_dir():
            roots.append(candidate)
    return [root for root in roots if root.is_dir()]


def load() -> None:
    global _done
    if _done or sys.platform == "darwin":
        return
    _done = True
    for root in _roots():
        for package in ("cuda_runtime", "cublas", "cudnn"):
            folder = root / package / ("bin" if sys.platform == "win32" else "lib")
            if not folder.is_dir():
                continue
            if sys.platform == "win32":
                os.add_dll_directory(str(folder))
                os.environ["PATH"] = str(folder) + os.pathsep + os.environ.get("PATH", "")
            else:
                # Load in dependency order so CTranslate2's dlopen by name finds them already loaded.
                names = sorted(p.name for p in folder.glob("lib*.so*"))
                ordered = sorted(names, key=lambda n: (0 if "cudart" in n else 1 if "Lt" in n else 2 if "cublas" in n
                                                       else 3 if n.startswith("libcudnn.") else 4))
                for name in ordered:
                    try:
                        ctypes.CDLL(str(folder / name), mode=ctypes.RTLD_GLOBAL)
                    except OSError:
                        pass


def bundled() -> list[str]:
    """The NVIDIA library packages present, for the build check."""
    found = []
    for root in _roots():
        for package in ("cuda_runtime", "cublas", "cudnn"):
            folder = root / package / ("bin" if sys.platform == "win32" else "lib")
            if folder.is_dir() and any(folder.iterdir()):
                found.append(package)
    return sorted(set(found))
