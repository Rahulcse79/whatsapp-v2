// Generate real Lyra RTP payloads from a synthetic tone, so the Python SIP UA can
// stream a genuine Lyra stream through FreeSWITCH without embedding the codec in Python.
// Writes concatenated frames (one per 20 ms) to a raw file; each frame is
// bytesPerFrame(bitrate) bytes, exactly what goes in an RTP packet at 20 ms ptime.
//
//   lyra_gen_frames <model_dir> <out.lyra> [--seconds N] [--bitrate 3200] [--hz 440]
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>
#include <vector>

#include "mod_lyra/lyra_adapter.h"

using namespace coralx::lyra;

int main(int argc, char** argv) {
    if (argc < 3) {
        std::fprintf(stderr, "usage: %s <model_dir> <out.lyra> [--seconds N] [--bitrate B] [--hz F]\n", argv[0]);
        return 2;
    }
    const std::string models = argv[1];
    const std::string out = argv[2];
    int seconds = 10, bitrate = 3200;
    double hz = 440.0;
    for (int i = 3; i < argc; ++i) {
        std::string a = argv[i];
        auto next = [&]() { return (i + 1 < argc) ? argv[++i] : "0"; };
        if (a == "--seconds") seconds = std::atoi(next());
        else if (a == "--bitrate") bitrate = std::atoi(next());
        else if (a == "--hz") hz = std::atof(next());
    }

    auto enc = Encoder::create(16000, bitrate, /*dtx=*/false, models);
    if (!enc) { std::fprintf(stderr, "lyra_gen_frames: encoder create failed (models=%s)\n", models.c_str()); return 1; }

    std::ofstream f(out, std::ios::binary);
    if (!f) { std::fprintf(stderr, "lyra_gen_frames: cannot write %s\n", out.c_str()); return 1; }

    const std::size_t n = samplesPerFrame(16000);
    const int frames = seconds * kFrameRateHz;
    std::vector<int16_t> pcm(n);
    std::vector<std::uint8_t> packet(kMaxFrameBytes);
    std::size_t total = 0;
    for (int fr = 0; fr < frames; ++fr) {
        for (std::size_t i = 0; i < n; ++i) {
            const double t = static_cast<double>(fr) * n + i;
            pcm[i] = static_cast<int16_t>(9000.0 * std::sin(2.0 * M_PI * hz * t / 16000.0));
        }
        auto bytes = enc->encodeFrame(pcm.data(), n, packet.data(), packet.size());
        if (!bytes) { std::fprintf(stderr, "lyra_gen_frames: encode failed at frame %d\n", fr); return 1; }
        f.write(reinterpret_cast<const char*>(packet.data()), static_cast<std::streamsize>(*bytes));
        total += *bytes;
    }
    std::fprintf(stderr, "lyra_gen_frames: wrote %d frames (%zu bytes, %d s at %d bit/s) to %s\n",
                 frames, total, seconds, bitrate, out.c_str());
    return 0;
}
