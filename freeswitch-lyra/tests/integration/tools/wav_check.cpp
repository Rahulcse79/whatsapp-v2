// Validate a recorded WAV: RIFF/WAVE structure, PCM format, channel count, sample
// rate, and duration. Exits 0 and prints one JSON line when it matches the expected
// values (given as flags), non-zero otherwise. Used by run_integration.sh to assert a
// recording is real and playable rather than trusting that a file merely exists.
//
//   wav_check <file> [--rate N] [--channels N] [--min-seconds F] [--max-seconds F]
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

namespace {
std::uint32_t rd32(const unsigned char* p) { return p[0] | (p[1] << 8) | (p[2] << 16) | (std::uint32_t(p[3]) << 24); }
std::uint16_t rd16(const unsigned char* p) { return static_cast<std::uint16_t>(p[0] | (p[1] << 8)); }
}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s <file> [--rate N] [--channels N] [--min-seconds F] [--max-seconds F]\n", argv[0]);
        return 2;
    }
    const char* path = argv[1];
    long wantRate = -1, wantCh = -1;
    double minSec = -1, maxSec = -1;
    for (int i = 2; i < argc; ++i) {
        std::string a = argv[i];
        auto next = [&]() { return (i + 1 < argc) ? argv[++i] : "0"; };
        if (a == "--rate") wantRate = std::atol(next());
        else if (a == "--channels") wantCh = std::atol(next());
        else if (a == "--min-seconds") minSec = std::atof(next());
        else if (a == "--max-seconds") maxSec = std::atof(next());
    }

    std::ifstream f(path, std::ios::binary);
    if (!f) { std::fprintf(stderr, "wav_check: cannot open %s\n", path); return 1; }
    std::vector<unsigned char> b((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    if (b.size() < 44) { std::fprintf(stderr, "wav_check: %s too small (%zu bytes)\n", path, b.size()); return 1; }
    if (std::memcmp(b.data(), "RIFF", 4) != 0 || std::memcmp(b.data() + 8, "WAVE", 4) != 0) {
        std::fprintf(stderr, "wav_check: %s is not a RIFF/WAVE file\n", path);
        return 1;
    }

    // Walk chunks to find fmt and data (a valid WAV may carry LIST/fact chunks).
    std::uint16_t audioFmt = 0, channels = 0, bits = 0;
    std::uint32_t rate = 0, dataBytes = 0;
    bool haveFmt = false, haveData = false;
    std::size_t pos = 12;
    while (pos + 8 <= b.size()) {
        const unsigned char* c = b.data() + pos;
        const std::uint32_t size = rd32(c + 4);
        const char* id = reinterpret_cast<const char*>(c);
        if (std::memcmp(id, "fmt ", 4) == 0 && pos + 8 + 16 <= b.size()) {
            audioFmt = rd16(c + 8);
            channels = rd16(c + 10);
            rate = rd32(c + 12);
            bits = rd16(c + 22);
            haveFmt = true;
        } else if (std::memcmp(id, "data", 4) == 0) {
            dataBytes = size;
            if (pos + 8 + dataBytes > b.size()) dataBytes = static_cast<std::uint32_t>(b.size() - pos - 8);
            haveData = true;
        }
        pos += 8 + size + (size & 1);  // chunks are word-aligned
    }
    if (!haveFmt || !haveData) { std::fprintf(stderr, "wav_check: %s missing fmt/data chunk\n", path); return 1; }

    const double seconds = (channels && bits && rate)
        ? static_cast<double>(dataBytes) / (channels * (bits / 8) * rate) : 0.0;

    std::printf("{\"file\":\"%s\",\"format\":%u,\"channels\":%u,\"rate\":%u,\"bits\":%u,\"data_bytes\":%u,\"seconds\":%.3f}\n",
                path, audioFmt, channels, rate, bits, dataBytes, seconds);

    int rc = 0;
    if (audioFmt != 1 && audioFmt != 0xFFFE) { std::fprintf(stderr, "wav_check: not PCM (fmt=%u)\n", audioFmt); rc = 1; }
    if (wantRate >= 0 && rate != static_cast<std::uint32_t>(wantRate)) { std::fprintf(stderr, "wav_check: rate %u != %ld\n", rate, wantRate); rc = 1; }
    if (wantCh >= 0 && channels != static_cast<std::uint16_t>(wantCh)) { std::fprintf(stderr, "wav_check: channels %u != %ld\n", channels, wantCh); rc = 1; }
    if (minSec >= 0 && seconds < minSec) { std::fprintf(stderr, "wav_check: %.3fs < min %.3fs\n", seconds, minSec); rc = 1; }
    if (maxSec >= 0 && seconds > maxSec) { std::fprintf(stderr, "wav_check: %.3fs > max %.3fs\n", seconds, maxSec); rc = 1; }
    return rc;
}
