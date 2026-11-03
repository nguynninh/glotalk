#include "onplay/stt.hpp"
#include "onplay/wave_reader.hpp"
#include <algorithm>
#include <chrono>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <thread>

int main(int argc, char** argv) {
  try {
    if (argc < 6) {
      std::cerr << "Usage: onplay-stt ENCODER DECODER JOINER TOKENS AUDIO.wav|- [--realtime] [--threads=N] [--chunk-ms=N] [--speaker-model=PATH] [--low-latency]\n";
      return 2;
    }
    bool realtime = false, low_latency = false;
    int threads = 2, chunk_ms = 100;
    std::string speaker_model;
    auto number = [](const std::string& text) {
      size_t end = 0;
      int value = std::stoi(text, &end);
      if (end != text.size()) throw std::invalid_argument("Invalid numeric option");
      return value;
    };
    for (int i = 6; i < argc; ++i) {
      const std::string option = argv[i];
      if (option == "--realtime") realtime = true;
      else if (option == "--low-latency") low_latency = true;
      else if (option.starts_with("--threads=")) threads = number(option.substr(10));
      else if (option.starts_with("--chunk-ms=")) chunk_ms = number(option.substr(11));
      else if (option.starts_with("--speaker-model=")) {
        speaker_model = option.substr(16);
        if (speaker_model.empty()) throw std::invalid_argument("Speaker model path required");
      }
      else throw std::invalid_argument("Unknown option: " + option);
    }
    if (threads < 1 || threads > 16 || chunk_ms < 10 || chunk_ms > 1000)
      throw std::invalid_argument("threads must be 1..16; chunk-ms must be 10..1000");
    using Clock = std::chrono::steady_clock;
    const bool pcm_stdin = std::string(argv[5]) == "-";
    std::unique_ptr<onplay::WaveReader> wave;
    if (!pcm_stdin) wave = std::make_unique<onplay::WaveReader>(argv[5]);
    auto load = Clock::now();
    onplay::StreamingSTT engine({argv[1], argv[2], argv[3], argv[4], threads, speaker_model, low_latency}, [&](const onplay::Transcript& r) {
      if (!speaker_model.empty())
        std::cout << (r.final ? "FINAL_SPEAKER " : "PARTIAL_SPEAKER ") << r.speaker << ' ' << r.text << std::endl;
      else std::cout << (r.final ? "FINAL   " : "PARTIAL ") << r.text << std::endl;
    });
    std::cerr << "model_load_s=" << std::chrono::duration<double>(Clock::now() - load).count() << '\n';
    std::cout << "READY" << std::endl;
    const auto start = Clock::now();
    double compute = 0;
    std::vector<float> buffer(chunk_ms * 16);
    std::uint64_t pos = 0;
    std::vector<unsigned char> pcm(buffer.size() * 2);
    auto next = [&]() -> std::size_t {
      if (wave) return wave->next(buffer);
      std::cin.read(reinterpret_cast<char*>(pcm.data()), pcm.size());
      const auto bytes = std::cin.gcount();
      if (bytes % 2) throw std::runtime_error("Truncated PCM16 sample");
      for (std::streamsize i = 0; i < bytes / 2; ++i) {
        const int value = pcm[i * 2] | (int(pcm[i * 2 + 1]) << 8);
        buffer[i] = (value >= 32768 ? value - 65536 : value) / 32768.0f;
      }
      return bytes / 2;
    };
    std::uint64_t reported = 0;
    while (const auto count = next()) {
      if (realtime) std::this_thread::sleep_until(start + std::chrono::duration<double>((pos + count) / 16000.0));
      auto tick = Clock::now();
      engine.acceptAudio({buffer.data(), count});
      compute += std::chrono::duration<double>(Clock::now() - tick).count();
      pos += count;
      if (pcm_stdin && pos - reported >= 16000) {
        std::cout << "PROGRESS " << pos / 16000.0 << std::endl;
        reported = pos;
      }
    }
    auto tick = Clock::now();
    engine.finish();
    compute += std::chrono::duration<double>(Clock::now() - tick).count();
    const double audio = pos / 16000.0;
    std::cerr << "audio_s=" << audio << " processing_s=" << compute << " rtf=" << (audio ? compute / audio : 0)
              << " wall_s=" << std::chrono::duration<double>(Clock::now() - start).count() << '\n';
  } catch (const std::exception& e) {
    std::cerr << "Error: " << e.what() << '\n';
    return 1;
  }
}
