#pragma once
#ifdef __cplusplus
extern "C" {
#endif
typedef struct study_stt study_stt;
study_stt * study_stt_create(void);
void study_stt_cancel(study_stt * job);
int study_stt_progress(study_stt * job);
// Input: mono 16kHz float32 PCM. Output: UTF-8 text. 0 success, 1 cancelled, -1 failed.
int study_stt_run(study_stt * job, const char * model, const char * pcm, const char * output, int gpu);
void study_stt_destroy(study_stt * job);
#ifdef __cplusplus
}
#endif
