chapters.m4b is an original synthetic 220 Hz sine wave generated for ReadAlong tests.
It contains no narration or third-party content. Two chapters start at 0 and 2000 ms.
Created with FFmpeg using AAC at 32 kbps and five seconds of audio.

speech.wav is original test prose voiced by the local open-source eSpeak NG tool (en-us, 145 words/minute). speech.mp3 and speech.m4b are stereo 44.1 kHz versions made with FFmpeg. Text: The lantern is beside the river. We can cross the bridge in the morning. The garden has three apple trees. No audiobook narration or third-party recording is included.

Phase 11 speech-long.wav repeats the same original speech for 75 seconds to verify real native recognition and WorkManager continuation past the former first-minute boundary:
ffmpeg -stream_loop 10 -i speech.wav -t 75 -ar 16000 -ac 1 speech-long.wav

speech-effects.wav appends the original speech.wav to a five-second synthetic chord prefix (220/277/330 Hz, faded out). It tests that attention-aligned word timestamps reach the narration rather than its preceding music. It is included only in the test APK.
