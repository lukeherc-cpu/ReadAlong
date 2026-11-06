# Two fixes to whisper.cpp's attention-based word times (DTW), which ReadAlong turns on for every model. Upstream
# they end the whole process with abort() (exit 134) instead of failing one window:
#
#   1. The median filter needs more than 7 audio frames (140 ms). A window whose decoded text ends almost where it
#      starts (a stray timestamp pair, a hallucination on a pause) has fewer, and WHISPER_ASSERT(filter_width <
#      a->ne[2]) aborts. Patched: such a window keeps whisper's own token times (t_dtw stays -1, as without DTW).
#   2. The extra decoder pass DTW makes can fail (the GPU runs out of memory or the driver drops the work), and that
#      is WHISPER_ASSERT(0). Patched: it logs the error and leaves that window's words without DTW times.
#
# Applied to the checkout in place and idempotent. A whisper.cpp version where the code moved stops the build, so a
# newer whisper.cpp can't quietly ship without the fix.
set(_readalong_whisper_src "${WHISPER_CPP}/src/whisper.cpp")
file(READ "${_readalong_whisper_src}" _readalong_source)
set(_readalong_changed FALSE)

set(_readalong_short_old "                    whisper_exp_compute_token_level_timestamps_dtw(\n                            ctx, state, params, result_all.size() - n_segments, n_segments, seek, n_frames, 7, params.n_threads);")
set(_readalong_short_new "                    if (n_frames / 2 > 7) /* readalong: too short for the median filter */\n                    whisper_exp_compute_token_level_timestamps_dtw(\n                            ctx, state, params, result_all.size() - n_segments, n_segments, seek, n_frames, 7, params.n_threads);")
string(FIND "${_readalong_source}" "readalong: too short for the median filter" _readalong_found)
if(_readalong_found EQUAL -1)
    string(FIND "${_readalong_source}" "${_readalong_short_old}" _readalong_found)
    if(_readalong_found EQUAL -1)
        message(FATAL_ERROR "patch_whisper.cmake: the DTW call in ${_readalong_whisper_src} has changed; update the patch.")
    endif()
    string(REPLACE "${_readalong_short_old}" "${_readalong_short_new}" _readalong_source "${_readalong_source}")
    set(_readalong_changed TRUE)
endif()

set(_readalong_decoder_old "        WHISPER_LOG_INFO(\"DECODER FAILED\\n\");\n        WHISPER_ASSERT(0);")
set(_readalong_decoder_new "        WHISPER_LOG_ERROR(\"%s: decoder failed, keeping this window's words without DTW times\\n\", __func__); /* readalong */\n        ggml_free(gctx);\n        return;")
string(FIND "${_readalong_source}" "keeping this window's words without DTW times" _readalong_found)
if(_readalong_found EQUAL -1)
    string(FIND "${_readalong_source}" "${_readalong_decoder_old}" _readalong_found)
    if(_readalong_found EQUAL -1)
        message(FATAL_ERROR "patch_whisper.cmake: the DTW decoder check in ${_readalong_whisper_src} has changed; update the patch.")
    endif()
    string(REPLACE "${_readalong_decoder_old}" "${_readalong_decoder_new}" _readalong_source "${_readalong_source}")
    set(_readalong_changed TRUE)
endif()

if(_readalong_changed)
    file(WRITE "${_readalong_whisper_src}" "${_readalong_source}")
    message(STATUS "Patched whisper.cpp's DTW word times so they can't abort the transcriber")
endif()
