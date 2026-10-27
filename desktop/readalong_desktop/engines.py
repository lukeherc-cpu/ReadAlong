"""Whisper on NVIDIA (faster-whisper / CUDA), AMD and Intel (whisper.cpp / Vulkan), Apple Silicon (mlx-whisper / Metal)
or any CPU (faster-whisper)."""
from __future__ import annotations

import numpy as np

from .hardware import Hardware
from .package import Word

MLX_REPOS = {
    "tiny": "mlx-community/whisper-tiny-mlx",
    "base": "mlx-community/whisper-base-mlx",
    "small": "mlx-community/whisper-small-mlx",
    "medium": "mlx-community/whisper-medium-mlx",
    "large-v3-turbo": "mlx-community/whisper-large-v3-turbo",
    "large-v3": "mlx-community/whisper-large-v3-mlx",
}


class Engine:
    """transcribe(samples, offset seconds, language) -> (words with file times, language).

    An engine with streams > 1 is given batches of windows from several threads at once (transcribe_batch).
    """
    backend = ""
    device = ""
    streams = 1

    def transcribe(self, samples: np.ndarray, offset: float, language: str | None) -> tuple[list[Word], str | None]:
        raise NotImplementedError

    def transcribe_batch(self, items: list[tuple[np.ndarray, float]], language: str | None,
                         cancelled=lambda: False) -> list[tuple[list[Word], str | None]]:
        return [self.transcribe(samples, offset, language) for samples, offset in items]


class FasterWhisper(Engine):
    backend = "faster-whisper"

    def __init__(self, model: str, hardware: Hardware, log=print):
        from faster_whisper import BatchedInferencePipeline, WhisperModel
        self.batched = None
        if hardware.accelerator == "cuda":
            small_card = bool(hardware.vram_gb and hardware.vram_gb < 5)
            try:
                self.model = WhisperModel(model, device="cuda", compute_type="int8_float16" if small_card else "float16")
                self.batched = BatchedInferencePipeline(self.model)
                self.device = "cuda"
                return
            except Exception as failure:  # Driver too old, or the CUDA libraries didn't load.
                log(f"Couldn't use the NVIDIA GPU ({failure}). Using the CPU instead; it's slower.")
        self.model = WhisperModel(model, device="cpu", compute_type="int8", cpu_threads=hardware.cores)
        self.device = "cpu"

    def transcribe(self, samples, offset, language):
        common = dict(language=language, word_timestamps=True, beam_size=1)  # Greedy: much faster, nearly as accurate.
        if self.batched is not None:
            segments, info = self.batched.transcribe(samples, batch_size=16, **common)
        else:
            segments, info = self.model.transcribe(samples, vad_filter=True, condition_on_previous_text=False, **common)
        words = [Word(w.word, offset + w.start, offset + w.end, w.probability)
                 for segment in segments for w in (segment.words or [])]
        return words, info.language


class Mlx(Engine):
    backend = "mlx-whisper"
    device = "metal"

    def __init__(self, model: str, hardware: Hardware, log=print):
        import mlx_whisper
        self.mlx = mlx_whisper
        self.repo = MLX_REPOS[model]

    def transcribe(self, samples, offset, language):
        result = self.mlx.transcribe(samples, path_or_hf_repo=self.repo, word_timestamps=True, language=language,
                                     condition_on_previous_text=False, verbose=None)
        words = [Word(w["word"], offset + w["start"], offset + w["end"], w.get("probability"))
                 for segment in result.get("segments", []) for w in segment.get("words", [])]
        return words, result.get("language")


# GPU memory one whisper-cli needs for each model (weights in f16 plus working buffers), in GB.
STREAM_MEMORY = {"tiny": 0.4, "base": 0.5, "small": 1.0, "medium": 2.2, "large-v3-turbo": 2.4, "large-v3": 4.2}


class WhisperCpp(Engine):
    """whisper.cpp on a Vulkan GPU. One whisper-cli decodes one stream and leaves the card mostly idle between
    its small decoder steps, so several run side by side, as many as the card's memory holds (up to 4)."""
    backend = "whisper.cpp"
    device = "vulkan"
    batch_seconds = 1200  # Audio per whisper-cli run, so loading the model is a small part of each run.

    def __init__(self, model: str, hardware: Hardware, log=print):
        from . import vulkan
        self.vulkan = vulkan
        self.log = log
        self.cli = vulkan.cli_path()
        if self.cli is None:
            raise RuntimeError("whisper.cpp isn't bundled with this build.")
        self.model = vulkan.model_file(model)
        vram = hardware.vram_gb or 4.0
        self.streams = max(1, min(4, int((vram - 0.6) / STREAM_MEMORY[model])))
        self.threads = max(2, min(4, hardware.cores // self.streams))
        self.warned = False

    def transcribe(self, samples, offset, language):
        return self.transcribe_batch([(samples, offset)], language)[0]

    def transcribe_batch(self, items, language, cancelled=lambda: False):
        from .audio import RATE
        results, on_gpu = self.vulkan.run(self.cli, self.model, [samples for samples, _ in items], RATE, language,
                                          self.threads, cancelled)
        if not on_gpu and not self.warned:
            self.warned = True
            self.log("whisper.cpp couldn't start on the GPU and is using the CPU, which is much slower.")
        out = []
        for (_, offset), result in zip(items, results):
            words = []
            for segment in result.get("transcription", []):
                text = segment.get("text", "")
                times = segment.get("offsets", {})
                if not text.strip() or text.strip().startswith("[_"):
                    continue
                words.append(Word(text, offset + times.get("from", 0) / 1000, offset + times.get("to", 0) / 1000, None))
            out.append((words, result.get("result", {}).get("language")))
        return out


def load(model: str, hardware: Hardware, log=print) -> Engine:
    if hardware.accelerator == "metal":
        return Mlx(model, hardware, log)
    if hardware.accelerator == "vulkan":
        try:
            return WhisperCpp(model, hardware, log)
        except Exception as failure:
            log(f"Couldn't use the GPU ({failure}). Using the CPU instead; it's slower.")
    return FasterWhisper(model, hardware, log)
