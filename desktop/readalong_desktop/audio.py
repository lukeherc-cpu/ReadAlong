"""Streams any audio file as 16 kHz mono float32, a window at a time, so a 50-hour book never sits in memory."""
from __future__ import annotations

from pathlib import Path
from typing import Iterator

import av
import numpy as np

RATE = 16_000


def length_seconds(path: Path) -> float:
    """Container length, for estimates before decoding. 0 when the file doesn't say."""
    try:
        with av.open(str(path)) as container:
            if container.duration:
                return container.duration / av.time_base
            stream = container.streams.audio[0]
            if stream.duration and stream.time_base:
                return float(stream.duration * stream.time_base)
    except Exception:
        pass
    return 0.0


def windows(path: Path, seconds: int = 1200) -> Iterator[tuple[float, np.ndarray]]:
    """Yields (start in seconds, samples). The last window may be short; their lengths add up to the file."""
    size = seconds * RATE
    pending: list[np.ndarray] = []
    buffered = 0
    offset = 0
    with av.open(str(path)) as container:
        stream = container.streams.audio[0]
        resampler = av.AudioResampler(format="flt", layout="mono", rate=RATE)

        def take(frames):
            nonlocal buffered
            for frame in frames:
                data = frame.to_ndarray().reshape(-1).astype(np.float32, copy=False)
                pending.append(data)
                buffered += len(data)

        for frame in container.decode(stream):
            take(resampler.resample(frame))
            while buffered >= size:
                joined = np.concatenate(pending)
                yield offset / RATE, joined[:size]
                rest = joined[size:]
                pending[:] = [rest] if len(rest) else []
                buffered = len(rest)
                offset += size
        take(resampler.resample(None))
    if buffered:
        yield offset / RATE, np.concatenate(pending)
