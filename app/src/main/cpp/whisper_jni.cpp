#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_jarvis_app_speech_WhisperEngine_nativeInit(
    JNIEnv *env, jobject thiz, jstring model_path) {

    const char *path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Loading whisper model: %s", path);

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;  // CPU only for compatibility

    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(model_path, path);

    if (ctx == nullptr) {
        LOGE("Failed to initialize whisper context");
        return 0;
    }

    LOGI("Whisper model loaded successfully");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_jarvis_app_speech_WhisperEngine_nativeTranscribe(
    JNIEnv *env, jobject thiz, jlong context_ptr, jfloatArray audio_data) {

    auto *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);
    if (ctx == nullptr) {
        return env->NewStringUTF("");
    }

    jsize n_samples = env->GetArrayLength(audio_data);
    jfloat *samples = env->GetFloatArrayElements(audio_data, nullptr);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime   = false;
    params.print_progress   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.no_timestamps    = true;
    params.single_segment   = true;
    params.language         = "en";
    params.n_threads        = 4;
    params.suppress_blank   = true;
    params.suppress_nst     = true;

    LOGI("Transcribing %d samples", n_samples);

    int result = whisper_full(ctx, params, samples, n_samples);
    env->ReleaseFloatArrayElements(audio_data, samples, JNI_ABORT);

    if (result != 0) {
        LOGE("whisper_full failed with code %d", result);
        return env->NewStringUTF("");
    }

    std::string text;
    int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; i++) {
        const char *segment_text = whisper_full_get_segment_text(ctx, i);
        if (segment_text) {
            text += segment_text;
        }
    }

    // Trim leading/trailing whitespace
    size_t start = text.find_first_not_of(" \t\n\r");
    size_t end = text.find_last_not_of(" \t\n\r");
    if (start != std::string::npos) {
        text = text.substr(start, end - start + 1);
    } else {
        text = "";
    }

    LOGI("Transcription: %s", text.c_str());
    return env->NewStringUTF(text.c_str());
}

JNIEXPORT void JNICALL
Java_com_jarvis_app_speech_WhisperEngine_nativeRelease(
    JNIEnv *env, jobject thiz, jlong context_ptr) {

    auto *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);
    if (ctx != nullptr) {
        whisper_free(ctx);
        LOGI("Whisper context released");
    }
}

} // extern "C"
