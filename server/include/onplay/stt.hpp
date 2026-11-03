#pragma once
#include <functional>
#include <memory>
#include <span>
#include <string>

namespace onplay {
struct ModelConfig {
  std::string encoder, decoder, joiner, tokens;
  int threads = 2;
  std::string speaker_model{};
  bool low_latency = false;
};
struct Transcript {
  std::string text;
  bool final;
  int speaker = 0; // Provisional session-local label; 0 means unknown or disabled.
};
// Single worker thread only. Callback runs synchronously; do not re-enter the engine.
// Input is mono float PCM at 16 kHz. Inference must run outside the audio callback.
class StreamingSTT {
 public:
  using Listener = std::function<void(const Transcript&)>;
  StreamingSTT(const ModelConfig&, Listener);
  ~StreamingSTT();
  StreamingSTT(const StreamingSTT&) = delete;
  StreamingSTT& operator=(const StreamingSTT&) = delete;
  void acceptAudio(std::span<const float> samples);
  void finish(); // Idempotent. Create a new session to accept audio again.
 private:
  struct Impl;
  std::unique_ptr<Impl> impl_;
};
}
