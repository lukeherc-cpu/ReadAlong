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
    """transcribe(samples, offset seconds, language) -> (words with file times, language)."""
    backend = ""
    device = ""

    def transcribe(self, samples: np.ndarray, offset: float, language: str | None) -> tuple[list[Word], str | None]:
        raise NotImplementedError


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
        common = dict(language=language, word_timestamps=True, beam_size=5)
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


class WhisperCpp(Engine):
    backend = "whisper.cpp"
    device = "vulkan"

    def __init__(self, model: str, hardware: Hardware, log=print):
        from . import vulkan
        self.vulkan = vulkan
        self.cli = vulkan.cli_path()
        if self.cli is None:
            raise RuntimeError("whisper.cpp isn't bundled with this build.")
        self.model = vulkan.model_file(model)
        self.threads = min(8, hardware.cores)

    def transcribe(self, samples, offset, language):
        from .audio import RATE
        result = self.vulkan.run(self.cli, self.model, samples, RATE, language, self.threads)
        words = []
        for segment in result.get("transcription", []):
            text = segment.get("text", "")
            times = segment.get("offsets", {})
            if not text.strip() or text.strip().startswith("[_"):
                continue
            words.append(Word(text, offset + times.get("from", 0) / 1000, offset + times.get("to", 0) / 1000, None))
        return words, result.get("result", {}).get("language")


def load(model: str, hardware: Hardware, log=print) -> Engine:
    if hardware.accelerator == "metal":
        return Mlx(model, hardware, log)
    if hardware.accelerator == "vulkan":
        try:
            return WhisperCpp(model, hardware, log)
        except Exception as failure:
            log(f"Couldn't use the GPU ({failure}). Using the CPU instead; it's slower.")
    return FasterWhisper(model, hardware, log)
