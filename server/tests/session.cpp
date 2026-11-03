#include "onplay/stt.hpp"
#include "onplay/wave_reader.hpp"
#include <filesystem>
#include <limits>
#include <stdexcept>
#include <vector>

template<class Error, class Fn> void rejects(Fn fn) {
  try { fn(); } catch (const Error&) { return; }
  throw std::runtime_error("Expected rejection");
}
int main(int argc, char** argv) {
  if (argc != 5) return 2;
  onplay::ModelConfig model{argv[1], argv[2], argv[3], argv[4]};
  rejects<std::invalid_argument>([&] { onplay::StreamingSTT s(model, {}); });
  auto bad = model;
  bad.encoder = "/missing-onplay-model.onnx";
  rejects<std::invalid_argument>([&] { onplay::StreamingSTT s(bad, [](auto&){}); });
  bad = model;
  bad.speaker_model = "/missing-onplay-speaker.onnx";
  rejects<std::invalid_argument>([&] { onplay::StreamingSTT s(bad, [](auto&){}); });
  int results = 0;
  onplay::StreamingSTT s(model, [&](auto&){ ++results; });
  const float nan[] = {std::numeric_limits<float>::quiet_NaN()};
  const float loud[] = {1.1f};
  rejects<std::invalid_argument>([&] { s.acceptAudio(nan); });
  rejects<std::invalid_argument>([&] { s.acceptAudio(loud); });
  std::vector<float> oversized(16001);
  rejects<std::invalid_argument>([&] { s.acceptAudio(oversized); });
  std::vector<float> silence(1600);
  for (int i = 0; i < 40; ++i) s.acceptAudio(silence);
  s.finish();
  s.finish();
  rejects<std::logic_error>([&] { s.acceptAudio(silence); });
  if (results) throw std::runtime_error("Silence generated transcript");
  {
    model.low_latency = true;
    int finals = 0;
    onplay::StreamingSTT fast(model, [&](const auto& transcript) {
      if (transcript.final) ++finals;
    });
    // Continuous repeated speech must finalize before finish(), so translation can start.
    for (int repeat = 0; repeat < 4; ++repeat) {
      onplay::WaveReader audio("models/vi.wav");
      std::vector<float> chunk(1600);
      while (const auto count = audio.next(chunk)) fast.acceptAudio({chunk.data(), count});
    }
    if (finals < 2) throw std::runtime_error("Low-latency speech did not produce bounded final segments");
    fast.finish();
    model.low_latency = false;
  }
  if (std::filesystem::is_regular_file("models/speakers/embedding.onnx")) {
    model.speaker_model = "models/speakers/embedding.onnx";
    int labelled = 0;
    onplay::StreamingSTT live(model, [&](const auto& transcript) {
      if (!transcript.final && transcript.speaker > 0) ++labelled;
    });
    onplay::WaveReader audio("models/vi.wav");
    std::vector<float> chunk(1600);
    while (const auto count = audio.next(chunk)) live.acceptAudio({chunk.data(), count});
    if (!labelled) throw std::runtime_error("Expected live speaker label before finish");
    live.finish();
    live.finish();
  }
}
