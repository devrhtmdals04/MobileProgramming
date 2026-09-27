#include "study_whisper.h"
#include "whisper.h"
#include <algorithm>
#include <atomic>
#include <cmath>
#include <fstream>
#include <memory>
#include <thread>
#include <vector>

struct study_stt { std::atomic<bool> cancelled{false}; std::atomic<int> progress{0}; };
study_stt * study_stt_create(void) { return new study_stt; }
void study_stt_cancel(study_stt * j) { if (j) j->cancelled = true; }
int study_stt_progress(study_stt * j) { return j ? j->progress.load() : 0; }
void study_stt_destroy(study_stt * j) { delete j; }

int study_stt_run(study_stt * job, const char * model, const char * pcm, const char * output, int gpu) {
    try {
        std::ifstream input(pcm, std::ios::binary | std::ios::ate);
        if (!input || !job) return -1;
        const auto bytes = static_cast<size_t>(input.tellg());
        if (bytes == 0 || bytes % sizeof(float) || bytes > 16000ULL * 7200 * sizeof(float)) return -1;
        input.seekg(0);
        if (job->cancelled) return 1;
        auto cp = whisper_context_default_params();
        cp.use_gpu = gpu != 0;
        std::unique_ptr<whisper_context, decltype(&whisper_free)> ctx(whisper_init_from_file_with_params(model, cp), whisper_free);
        if (!ctx) return -1;
        auto p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        p.language = "ko";
        p.translate = false;
        p.n_threads = std::min(4U, std::max(1U, std::thread::hardware_concurrency()));
        p.print_progress = p.print_realtime = p.print_timestamps = p.print_special = false;
        p.no_context = true;
        p.suppress_nst = true;
        p.abort_callback = [](void * data) { return static_cast<study_stt *>(data)->cancelled.load(); };
        p.abort_callback_user_data = job;
        std::ofstream text(output, std::ios::binary | std::ios::trunc);
        if (!text) return -1;
        // Bound memory even for long lectures; platform decoders also stream to disk.
        std::vector<float> samples(16000 * 60);
        size_t consumed = 0;
        while (consumed < bytes) {
            if (job->cancelled) return 1;
            size_t count = std::min(samples.size(), (bytes - consumed) / sizeof(float));
            input.read(reinterpret_cast<char *>(samples.data()), count * sizeof(float));
            if (!input) return -1;
            double energy = 0;
            for (size_t i = 0; i < count; ++i) {
                if (!std::isfinite(samples[i])) return -1;
                energy += samples[i] * samples[i];
            }
            // Do not hallucinate text from a digitally silent recording.
            if (std::sqrt(energy / count) > 0.0001) {
                struct Progress { study_stt * job; size_t consumed; size_t chunk; size_t total; } progress{job, consumed, count * sizeof(float), bytes};
                p.progress_callback_user_data = &progress;
                p.progress_callback = [](whisper_context *, whisper_state *, int value, void * data) {
                    auto * state = static_cast<Progress *>(data);
                    state->job->progress = static_cast<int>((state->consumed * 100 + state->chunk * value) / state->total);
                };
                if (whisper_full(ctx.get(), p, samples.data(), static_cast<int>(count)) != 0)
                    return job->cancelled ? 1 : -1;
                for (int i = 0; i < whisper_full_n_segments(ctx.get()); ++i) {
                    const char * segment = whisper_full_get_segment_text(ctx.get(), i);
                    if (segment) text << segment << '\n';
                }
            }
            if (!text) return -1;
            consumed += count * sizeof(float);
            job->progress = static_cast<int>(consumed * 100 / bytes);
        }
        text.flush();
        return job->cancelled ? 1 : (text ? 0 : -1);
    } catch (...) { return -1; }
}
