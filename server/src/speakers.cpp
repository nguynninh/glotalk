#include "onplay/stt.hpp"
#include "onplay/wave_reader.hpp"
#include "sherpa-onnx/c-api/c-api.h"
#include <cmath>
#include <filesystem>
#include <iostream>
#include <map>
#include <memory>

template<class T, auto Destroy>
using Handle = std::unique_ptr<const T, decltype(Destroy)>;

int main(int argc, char** argv) {
  try {
    if (argc != 9) throw std::invalid_argument(
      "Usage: onplay-speakers ENCODER DECODER JOINER TOKENS SEGMENTATION EMBEDDING AUDIO.wav THREADS");
    std::size_t end = 0;
    const std::string threads_arg = argv[8];
    const int threads = std::stoi(threads_arg, &end);
    if (end != threads_arg.size() || threads < 1 || threads > 16)
      throw std::invalid_argument("threads must be 1..16");
    for (int i = 1; i <= 6; ++i)
      if (!std::filesystem::is_regular_file(argv[i]))
        throw std::invalid_argument("Missing model file: " + std::string(argv[i]));
    onplay::WaveReader wave(argv[7]);
    // shortcut: cap the offline pass at 10 minutes; use windowed diarization for longer recordings.
    if (wave.sampleCount() > 16000 * 600)
      throw std::invalid_argument("Speaker preview supports at most 10 minutes");
    std::vector<float> samples(wave.sampleCount());
    wave.next(samples);
    SherpaOnnxOfflineSpeakerDiarizationConfig config{};
    config.segmentation.pyannote.model = argv[5];
    config.segmentation.num_threads = threads;
    config.segmentation.provider = "cpu";
    config.embedding.model = argv[6];
    config.embedding.num_threads = threads;
    config.embedding.provider = "cpu";
    config.clustering.num_clusters = -1;
    config.clustering.threshold = 0.5f;
    config.min_duration_on = 0.3f;
    config.min_duration_off = 0.5f;
    Handle<SherpaOnnxOfflineSpeakerDiarization, SherpaOnnxDestroyOfflineSpeakerDiarization> diarizer{
      SherpaOnnxCreateOfflineSpeakerDiarization(&config), SherpaOnnxDestroyOfflineSpeakerDiarization};
    if (!diarizer) throw std::runtime_error("Cannot load speaker models");
    if (SherpaOnnxOfflineSpeakerDiarizationGetSampleRate(diarizer.get()) != 16000)
      throw std::runtime_error("Speaker model must accept 16000 Hz");
    Handle<SherpaOnnxOfflineSpeakerDiarizationResult, SherpaOnnxOfflineSpeakerDiarizationDestroyResult> result{
      SherpaOnnxOfflineSpeakerDiarizationProcess(diarizer.get(), samples.data(), static_cast<int>(samples.size())),
      SherpaOnnxOfflineSpeakerDiarizationDestroyResult};
    if (!result) throw std::runtime_error("Speaker diarization failed");
    const int count = SherpaOnnxOfflineSpeakerDiarizationResultGetNumSegments(result.get());
    Handle<SherpaOnnxOfflineSpeakerDiarizationSegment, SherpaOnnxOfflineSpeakerDiarizationDestroySegment> segments{
      count ? SherpaOnnxOfflineSpeakerDiarizationResultSortByStartTime(result.get()) : nullptr,
      SherpaOnnxOfflineSpeakerDiarizationDestroySegment};
    if (count && !segments) throw std::runtime_error("Cannot read speaker segments");
    std::map<int, int> labels;
    for (int i = 0; i < count; ++i) {
      const auto& segment = segments.get()[i];
      if (!std::isfinite(segment.start) || !std::isfinite(segment.end) ||
          segment.start < 0 || segment.end <= segment.start || segment.speaker < 0)
        throw std::runtime_error("Invalid speaker segment");
      auto [label, inserted] = labels.try_emplace(segment.speaker, static_cast<int>(labels.size()) + 1);
      (void)inserted;
      const auto first = std::min(samples.size(), static_cast<std::size_t>(segment.start * 16000));
      const auto last = std::min(samples.size(), static_cast<std::size_t>(std::ceil(segment.end * 16000)));
      std::string text;
      onplay::StreamingSTT engine({argv[1], argv[2], argv[3], argv[4], threads}, [&](const auto& transcript) {
        if (transcript.final) {
          if (!text.empty()) text += ' ';
          text += transcript.text;
        }
      });
      for (auto pos = first; pos < last; pos += std::min<std::size_t>(1600, last - pos))
        engine.acceptAudio({samples.data() + pos, std::min<std::size_t>(1600, last - pos)});
      engine.finish();
      std::cout << "SPEAKER " << segment.start << ' ' << segment.end << ' ' << label->second << ' ' << text << std::endl;
    }
  } catch (const std::exception& error) {
    std::cerr << "Error: " << error.what() << '\n';
    return 1;
  }
}
