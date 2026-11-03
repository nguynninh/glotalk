#include "onplay/wave_reader.hpp"
#include <array>
#include <cmath>
#include <stdexcept>
int main(int argc, char** argv) {
  if (argc != 2) return 2;
  onplay::WaveReader wave(argv[1]);
  if (wave.sampleCount() != 40000000) throw std::runtime_error("Wrong sample count");
  std::array<float, 3> samples{};
  if (wave.next(samples) != 3 || samples[0] != 0 || samples[1] != -1 ||
      std::abs(samples[2] - 32767 / 32768.f) > 1e-6) throw std::runtime_error("Wrong PCM conversion");
}
