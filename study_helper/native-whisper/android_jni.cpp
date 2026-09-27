#include <jni.h>
#include "study_whisper.h"
extern "C" {
JNIEXPORT jlong JNICALL Java_com_example_study_1helper_jaderun_DeviceWhisper_create(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(study_stt_create());
}
JNIEXPORT void JNICALL Java_com_example_study_1helper_jaderun_DeviceWhisper_cancel(JNIEnv *, jobject, jlong job) {
    study_stt_cancel(reinterpret_cast<study_stt *>(job));
}
JNIEXPORT jint JNICALL Java_com_example_study_1helper_jaderun_DeviceWhisper_progress(JNIEnv *, jobject, jlong job) {
    return study_stt_progress(reinterpret_cast<study_stt *>(job));
}
JNIEXPORT void JNICALL Java_com_example_study_1helper_jaderun_DeviceWhisper_destroy(JNIEnv *, jobject, jlong job) {
    study_stt_destroy(reinterpret_cast<study_stt *>(job));
}
JNIEXPORT jint JNICALL Java_com_example_study_1helper_jaderun_DeviceWhisper_run(JNIEnv * env, jobject, jlong job, jstring model, jstring pcm, jstring output) {
    const char * m = env->GetStringUTFChars(model, nullptr);
    const char * p = env->GetStringUTFChars(pcm, nullptr);
    const char * o = env->GetStringUTFChars(output, nullptr);
    const int result = study_stt_run(reinterpret_cast<study_stt *>(job), m, p, o, 0);
    env->ReleaseStringUTFChars(model, m); env->ReleaseStringUTFChars(pcm, p); env->ReleaseStringUTFChars(output, o);
    return result;
}
}
