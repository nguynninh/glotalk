#pragma once
#include <algorithm>
#include <array>
#include <cstdint>
#include <fstream>
#include <span>
#include <stdexcept>
#include <string>
#include <vector>

namespace onplay {
// Read PCM WAV incrementally; memory use is independent of recording length.
class WaveReader {
 public:
  explicit WaveReader(const std::string& path) : file_(path, std::ios::binary) {
    if (!file_) throw std::runtime_error("Cannot open WAV");
    file_.seekg(0, std::ios::end);
    const auto length = static_cast<std::uint64_t>(file_.tellg());
    file_.seekg(0);
    std::array<unsigned char, 12> header{};
    read(header.data(), header.size());
    if (std::string(reinterpret_cast<char*>(header.data()), 4) != "RIFF" ||
        std::string(reinterpret_cast<char*>(header.data() + 8), 4) != "WAVE")
      throw std::runtime_error("Expected RIFF WAV");
    bool format = false, data = false;
    std::uint64_t data_offset = 0;
    while (file_ && (!format || !data)) {
      std::array<unsigned char, 8> chunk{};
      read(chunk.data(), chunk.size());
      const auto size = u32(chunk.data() + 4);
      const auto start = static_cast<std::uint64_t>(file_.tellg());
      if (start > length || size > length - start) throw std::runtime_error("Truncated WAV chunk");
      const std::string id(reinterpret_cast<char*>(chunk.data()), 4);
      if (id == "fmt ") {
        std::array<unsigned char, 40> fmt{};
        if (size < 16) throw std::runtime_error("Invalid WAV format");
        read(fmt.data(), std::min<std::uint32_t>(size, fmt.size()));
        const std::array<unsigned char, 16> pcm_guid{1,0,0,0,0,0,16,0,128,0,0,170,0,56,155,113};
        const bool pcm = u16(fmt.data()) == 1 ||
          (u16(fmt.data()) == 65534 && size >= 40 && u16(fmt.data() + 16) >= 22 &&
           u16(fmt.data() + 18) == 16 && std::equal(pcm_guid.begin(), pcm_guid.end(), fmt.begin() + 24));
        if (!pcm || u16(fmt.data() + 2) != 1 || u32(fmt.data() + 4) != 16000 ||
            u16(fmt.data() + 12) != 2 || u16(fmt.data() + 14) != 16)
          throw std::runtime_error("Expected mono PCM16 WAV at 16000 Hz");
        format = true;
      } else if (id == "data") {
        if (!size || size % 2) throw std::runtime_error("Invalid WAV data size");
        remaining_ = size; samples_ = size / 2; data_offset = start; data = true;
      }
      file_.seekg(static_cast<std::streamoff>(start + size + (size & 1)));
    }
    if (!format || !data) throw std::runtime_error("Missing WAV format or data");
    file_.seekg(static_cast<std::streamoff>(data_offset));
  }
  std::uint64_t sampleCount() const { return samples_; }
  std::size_t next(std::span<float> output) {
    const auto count = std::min<std::uint64_t>(output.size(), remaining_ / 2);
    bytes_.resize(count * 2);
    read(bytes_.data(), bytes_.size());
    for (std::size_t i = 0; i < count; ++i) {
      const int value = u16(bytes_.data() + i * 2);
      output[i] = (value >= 32768 ? value - 65536 : value) / 32768.0f;
    }
    remaining_ -= count * 2;
    return count;
  }
 private:
  static unsigned u16(const unsigned char* p) { return p[0] | (unsigned(p[1]) << 8); }
  static std::uint32_t u32(const unsigned char* p) { return u16(p) | (std::uint32_t(u16(p + 2)) << 16); }
  void read(unsigned char* out, std::size_t size) {
    if (size && !file_.read(reinterpret_cast<char*>(out), static_cast<std::streamsize>(size)))
      throw std::runtime_error("Truncated WAV");
  }
  std::ifstream file_;
  std::uint64_t remaining_ = 0, samples_ = 0;
  std::vector<unsigned char> bytes_;
};
}
