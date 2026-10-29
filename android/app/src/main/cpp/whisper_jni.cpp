#include <jni.h>
#include <whisper.h>
#include <atomic>
#include <algorithm>
#include <memory>
#include <string>
#include <thread>
#include <vector>

struct Session {
    whisper_context * context;
    std::atomic_bool cancelled{false};
};

static void fail(JNIEnv * env, const char * message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

extern "C" JNIEXPORT jlong JNICALL
Java_vn_onplay_vdubbing_data_audio_LocalWhisper_nativeOpen(JNIEnv * env, jobject, jstring path) {
    whisper_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
    const char * filename = env->GetStringUTFChars(path, nullptr);
    if (!filename) return 0;
    auto params = whisper_context_default_params();
    params.use_gpu = false;
    auto * context = whisper_init_from_file_with_params(filename, params);
    env->ReleaseStringUTFChars(path, filename);
    if (!context) {
        fail(env, "Whisper model could not be loaded");
        return 0;
    }
    return reinterpret_cast<jlong>(new Session{context});
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_vn_onplay_vdubbing_data_audio_LocalWhisper_nativeTranscribe(
        JNIEnv * env, jobject, jlong handle, jfloatArray input, jstring language,
        jbyteArray promptBytes, jstring vadPath) {
    auto * session = reinterpret_cast<Session *>(handle);
    if (!session || session->cancelled.load()) return env->NewByteArray(0);
    const auto length = env->GetArrayLength(input);
    if (length < 1 || length > 16000 * 16) {
        fail(env, "Invalid audio length");
        return nullptr;
    }
    std::vector<float> samples(length);
    env->GetFloatArrayRegion(input, 0, length, samples.data());
    std::string prompt(env->GetArrayLength(promptBytes), '\0');
    env->GetByteArrayRegion(promptBytes, 0, prompt.size(), reinterpret_cast<jbyte *>(prompt.data()));
    const char * lang = env->GetStringUTFChars(language, nullptr);
    const char * vad = env->GetStringUTFChars(vadPath, nullptr);
    if (!lang || !vad || env->ExceptionCheck()) {
        if (lang) env->ReleaseStringUTFChars(language, lang);
        if (vad) env->ReleaseStringUTFChars(vadPath, vad);
        return nullptr;
    }
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = std::max(1u, std::min(4u, std::thread::hardware_concurrency()));
    params.greedy.best_of = 1;
    // Whisper uses 50 encoder frames per second; retain one second of padding.
    params.audio_ctx = std::min(whisper_n_audio_ctx(session->context), (length + 319) / 320 + 50);
    params.translate = false;
    params.language = lang;
    params.no_context = true;
    params.initial_prompt = prompt.c_str();
    params.no_timestamps = true;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.suppress_nst = true;
    params.vad = true;
    params.vad_model_path = vad;
    params.abort_callback = [](void * data) { return static_cast<Session *>(data)->cancelled.load(); };
    params.abort_callback_user_data = session;
    const int status = whisper_full(session->context, params, samples.data(), samples.size());
    env->ReleaseStringUTFChars(language, lang);
    env->ReleaseStringUTFChars(vadPath, vad);
    if (session->cancelled.load()) return env->NewByteArray(0);
    if (status != 0) {
        fail(env, "Whisper transcription failed");
        return nullptr;
    }
    std::string text;
    for (int i = 0; i < whisper_full_n_segments(session->context); ++i) {
        text += whisper_full_get_segment_text(session->context, i);
    }
    auto output = env->NewByteArray(text.size());
    if (output) env->SetByteArrayRegion(output, 0, text.size(), reinterpret_cast<const jbyte *>(text.data()));
    return output;
}

extern "C" JNIEXPORT void JNICALL
Java_vn_onplay_vdubbing_data_audio_LocalWhisper_nativeCancel(JNIEnv *, jobject, jlong handle) {
    if (handle) reinterpret_cast<Session *>(handle)->cancelled.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_vn_onplay_vdubbing_data_audio_LocalWhisper_nativeClose(JNIEnv *, jobject, jlong handle) {
    auto * session = reinterpret_cast<Session *>(handle);
    if (session) {
        whisper_free(session->context);
        delete session;
    }
}
