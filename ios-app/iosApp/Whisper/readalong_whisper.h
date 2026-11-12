// The C face of whisper.cpp that ReadAlong's Swift bridge calls (WhisperBridge.swift).
#ifndef READALONG_WHISPER_H
#define READALONG_WHISPER_H
#ifdef __cplusplus
extern "C" {
#endif
void rw_init(int verbose);
char * rw_devices(void);
char * rw_system_info(void);
void rw_free_string(char * text);
void * rw_load(const char * model_path, int gpu_device, int alignment_heads);
void rw_free(void * handle);
void rw_cancel(void * handle);
int rw_progress(void * handle);
char * rw_transcribe(void * handle, const float * audio, int count, const char * language, int threads, int beams);
#ifdef __cplusplus
}
#endif
#endif
