#include "sherpa-onnx/c-api/c-api.h"
#include <array>
#include <cstdint>
#include <filesystem>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

template<class T, auto Destroy>
using Handle = std::unique_ptr<const T, decltype(Destroy)>;

int main(int argc, char** argv) {
  try {
    if (argc != 5) throw std::invalid_argument("Expected encoder, decoder, joiner and tokens");
    for (int i = 1; i < argc; ++i)
      if (!std::filesystem::is_regular_file(argv[i])) throw std::invalid_argument("Missing model file");
    SherpaOnnxOnlineRecognizerConfig config{};
    config.feat_config.sample_rate = 16000;
    config.feat_config.feature_dim = 80;
    config.model_config.transducer.encoder = argv[1];
    config.model_config.transducer.decoder = argv[2];
    config.model_config.transducer.joiner = argv[3];
    config.model_config.tokens = argv[4];
    config.model_config.num_threads = 2;
    config.model_config.provider = "cpu";
    config.decoding_method = "greedy_search";
    Handle<SherpaOnnxOnlineRecognizer, SherpaOnnxDestroyOnlineRecognizer> recognizer{
      SherpaOnnxCreateOnlineRecognizer(&config), SherpaOnnxDestroyOnlineRecognizer};
    if (!recognizer) throw std::runtime_error("Cannot initialize recognition model");
    std::cout << "READY\n" << std::flush;
    // Private pipe protocol: uint32 little-endian byte count + PCM16; decimal byte count + UTF-8 reply.
    std::array<unsigned char, 4> header{};
    while (std::cin.read(reinterpret_cast<char*>(header.data()), header.size())) {
      const std::uint32_t bytes = header[0] | (std::uint32_t(header[1]) << 8) |
        (std::uint32_t(header[2]) << 16) | (std::uint32_t(header[3]) << 24);
      if (!bytes || bytes % 2 || bytes > 16000 * 2 * 60)
        throw std::invalid_argument("Expected up to 60 seconds of mono PCM16 at 16 kHz");
      std::vector<unsigned char> pcm(bytes);
      if (!std::cin.read(reinterpret_cast<char*>(pcm.data()), bytes))
        throw std::runtime_error("Incomplete PCM input");
      Handle<SherpaOnnxOnlineStream, SherpaOnnxDestroyOnlineStream> stream{
        SherpaOnnxCreateOnlineStream(recognizer.get()), SherpaOnnxDestroyOnlineStream};
      if (!stream) throw std::runtime_error("Cannot create recognition stream");
      std::vector<float> samples(bytes / 2);
      for (std::size_t i = 0; i < samples.size(); ++i) {
        const int value = pcm[2 * i] | (int(pcm[2 * i + 1]) << 8);
        samples[i] = (value >= 32768 ? value - 65536 : value) / 32768.0f;
      }
      SherpaOnnxOnlineStreamAcceptWaveform(stream.get(), 16000, samples.data(), samples.size());
      const std::array<float, 12800> silence{};
      SherpaOnnxOnlineStreamAcceptWaveform(stream.get(), 16000, silence.data(), silence.size());
      SherpaOnnxOnlineStreamInputFinished(stream.get());
      while (SherpaOnnxIsOnlineStreamReady(recognizer.get(), stream.get()))
        SherpaOnnxDecodeOnlineStream(recognizer.get(), stream.get());
      Handle<SherpaOnnxOnlineRecognizerResult, SherpaOnnxDestroyOnlineRecognizerResult> result{
        SherpaOnnxGetOnlineStreamResult(recognizer.get(), stream.get()), SherpaOnnxDestroyOnlineRecognizerResult};
      if (!result) throw std::runtime_error("Cannot read recognition result");
      const std::string text = result->text ? result->text : "";
      std::cout << text.size() << '\n' << text << std::flush;
    }
    if (std::cin.gcount() || !std::cin.eof()) throw std::runtime_error("Incomplete PCM header");
  } catch (const std::exception& error) {
    std::cerr << "Error: " << error.what() << '\n';
    return 1;
  }
}
