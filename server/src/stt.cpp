#include "onplay/stt.hpp"
#include "sherpa-onnx/c-api/c-api.h"
#include <array>
#include <cmath>
#include <filesystem>
#include <stdexcept>
#include <utility>
#include <vector>

namespace onplay {
namespace {
template<class T, auto Destroy>
using Handle = std::unique_ptr<const T, decltype(Destroy)>;
}
struct StreamingSTT::Impl {
  Handle<SherpaOnnxOnlineRecognizer, SherpaOnnxDestroyOnlineRecognizer> recognizer{nullptr, SherpaOnnxDestroyOnlineRecognizer};
  Handle<SherpaOnnxOnlineStream, SherpaOnnxDestroyOnlineStream> stream{nullptr, SherpaOnnxDestroyOnlineStream};
  Handle<SherpaOnnxSpeakerEmbeddingExtractor, SherpaOnnxDestroySpeakerEmbeddingExtractor> extractor{nullptr, SherpaOnnxDestroySpeakerEmbeddingExtractor};
  Handle<SherpaOnnxSpeakerEmbeddingManager, SherpaOnnxDestroySpeakerEmbeddingManager> speakers{nullptr, SherpaOnnxDestroySpeakerEmbeddingManager};
  std::vector<float> speaker_audio;
  std::size_t since_speaker = 0;
  int speaker = 0, speaker_count = 0;
  Listener listener;
  std::string previous;
  bool finished = false;
  void identify(bool final) {
    if (!extractor || speaker_audio.size() < 16000 || (!final && since_speaker < 32000)) return;
    // shortcut: one label for the recent four seconds; use segmentation for overlapping or rapid turns.
    Handle<SherpaOnnxOnlineStream, SherpaOnnxDestroyOnlineStream> input{
      SherpaOnnxSpeakerEmbeddingExtractorCreateStream(extractor.get()), SherpaOnnxDestroyOnlineStream};
    if (!input) throw std::runtime_error("Cannot create speaker stream");
    SherpaOnnxOnlineStreamAcceptWaveform(input.get(), 16000, speaker_audio.data(), static_cast<int>(speaker_audio.size()));
    SherpaOnnxOnlineStreamInputFinished(input.get());
    if (!SherpaOnnxSpeakerEmbeddingExtractorIsReady(extractor.get(), input.get())) return;
    Handle<float, SherpaOnnxSpeakerEmbeddingExtractorDestroyEmbedding> embedding{
      SherpaOnnxSpeakerEmbeddingExtractorComputeEmbedding(extractor.get(), input.get()),
      SherpaOnnxSpeakerEmbeddingExtractorDestroyEmbedding};
    if (!embedding) throw std::runtime_error("Cannot compute speaker embedding");
    Handle<char, SherpaOnnxSpeakerEmbeddingManagerFreeSearch> match{
      SherpaOnnxSpeakerEmbeddingManagerSearch(speakers.get(), embedding.get(), 0.5f),
      SherpaOnnxSpeakerEmbeddingManagerFreeSearch};
    if (match) speaker = std::stoi(match.get());
    else {
      speaker = ++speaker_count;
      if (!SherpaOnnxSpeakerEmbeddingManagerAdd(speakers.get(), std::to_string(speaker).c_str(), embedding.get()))
        throw std::runtime_error("Cannot register speaker");
    }
    since_speaker = 0;
  }
  void decode(bool final) {
    while (SherpaOnnxIsOnlineStreamReady(recognizer.get(), stream.get()))
      SherpaOnnxDecodeOnlineStream(recognizer.get(), stream.get());
    Handle<SherpaOnnxOnlineRecognizerResult, SherpaOnnxDestroyOnlineRecognizerResult> result{
      SherpaOnnxGetOnlineStreamResult(recognizer.get(), stream.get()), SherpaOnnxDestroyOnlineRecognizerResult};
    if (!result) throw std::runtime_error("Cannot read recognition result");
    const bool endpoint = !final && SherpaOnnxOnlineStreamIsEndpoint(recognizer.get(), stream.get());
    std::string text = result->text ? result->text : "";
    if (!text.empty() && (final || endpoint || text != previous)) {
      identify(final || endpoint);
      previous = text;
      listener({std::move(text), final || endpoint, speaker});
    }
    if (endpoint) {
      SherpaOnnxOnlineStreamReset(recognizer.get(), stream.get());
      previous.clear();
      speaker_audio.clear();
      since_speaker = 0;
      speaker = 0;
    }
  }
};
StreamingSTT::StreamingSTT(const ModelConfig& model, Listener listener) : impl_(std::make_unique<Impl>()) {
  if (!listener || model.threads < 1 || model.threads > 16)
    throw std::invalid_argument("Listener required; threads must be 1..16");
  for (const auto* path : {&model.encoder, &model.decoder, &model.joiner, &model.tokens})
    if (!std::filesystem::is_regular_file(*path)) throw std::invalid_argument("Missing model file: " + *path);
  if (!model.speaker_model.empty()) {
    if (!std::filesystem::is_regular_file(model.speaker_model))
      throw std::invalid_argument("Missing speaker model file: " + model.speaker_model);
    SherpaOnnxSpeakerEmbeddingExtractorConfig speaker_config{};
    speaker_config.model = model.speaker_model.c_str();
    speaker_config.num_threads = model.threads;
    speaker_config.provider = "cpu";
    impl_->extractor.reset(SherpaOnnxCreateSpeakerEmbeddingExtractor(&speaker_config));
    if (!impl_->extractor) throw std::runtime_error("Cannot load speaker model");
    impl_->speakers.reset(SherpaOnnxCreateSpeakerEmbeddingManager(SherpaOnnxSpeakerEmbeddingExtractorDim(impl_->extractor.get())));
    if (!impl_->speakers) throw std::runtime_error("Cannot create speaker manager");
  }
  SherpaOnnxOnlineRecognizerConfig c{};
  c.feat_config.sample_rate = 16000;
  c.feat_config.feature_dim = 80;
  c.model_config.transducer.encoder = model.encoder.c_str();
  c.model_config.transducer.decoder = model.decoder.c_str();
  c.model_config.transducer.joiner = model.joiner.c_str();
  c.model_config.tokens = model.tokens.c_str();
  c.model_config.num_threads = model.threads;
  c.model_config.provider = "cpu";
  c.decoding_method = "greedy_search";
  c.enable_endpoint = 1;
  c.rule1_min_trailing_silence = 2.4f;
  c.rule2_min_trailing_silence = model.low_latency ? 0.7f : 1.2f;
  // Bound translation wait during continuous speech, at the cost of sentence context.
  c.rule3_min_utterance_length = model.low_latency ? 6.0f : 20.0f;
  impl_->recognizer.reset(SherpaOnnxCreateOnlineRecognizer(&c));
  if (!impl_->recognizer) throw std::runtime_error("Cannot initialize streaming model");
  impl_->stream.reset(SherpaOnnxCreateOnlineStream(impl_->recognizer.get()));
  if (!impl_->stream) throw std::runtime_error("Cannot create streaming session");
  impl_->listener = std::move(listener);
}
StreamingSTT::~StreamingSTT() = default;
void StreamingSTT::acceptAudio(std::span<const float> samples) {
  if (impl_->finished) throw std::logic_error("Session already finished");
  if (samples.size() > 16000) throw std::invalid_argument("Audio chunk exceeds one second");
  for (float sample : samples)
    if (!std::isfinite(sample) || sample < -1 || sample > 1)
      throw std::invalid_argument("PCM samples must be finite and within [-1, 1]");
  if (samples.empty()) return;
  if (impl_->extractor) {
    impl_->speaker_audio.insert(impl_->speaker_audio.end(), samples.begin(), samples.end());
    if (impl_->speaker_audio.size() > 64000)
      impl_->speaker_audio.erase(impl_->speaker_audio.begin(), impl_->speaker_audio.end() - 64000);
    impl_->since_speaker += samples.size();
  }
  SherpaOnnxOnlineStreamAcceptWaveform(impl_->stream.get(), 16000, samples.data(), static_cast<int>(samples.size()));
  impl_->decode(false);
}
void StreamingSTT::finish() {
  if (impl_->finished) return;
  impl_->finished = true;
  // Flush encoder lookahead without counting synthetic silence as input duration.
  const std::array<float, 12800> silence{};
  SherpaOnnxOnlineStreamAcceptWaveform(impl_->stream.get(), 16000, silence.data(), silence.size());
  SherpaOnnxOnlineStreamInputFinished(impl_->stream.get());
  impl_->decode(true);
}
}
