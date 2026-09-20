// Exercises the real Lyra library through the adapter: version, constants, a genuine
// encode→decode round trip, bitrate switching, PLC, and rejection of bad input. Needs
// the model files; their directory is passed as argv[1] (the CMake test wires it to
// third_party/lyra/lyra/model_coeffs).
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "check.h"
#include "mod_lyra/lyra_adapter.h"

using namespace coralx::lyra;

// Set by main() from argv so the TEST bodies can reach it.
static std::string g_models;

TEST(constants_match_the_library) {
    CHECK_EQ(bytesPerFrame(3200), std::size_t(8));
    CHECK_EQ(bytesPerFrame(6000), std::size_t(15));
    CHECK_EQ(bytesPerFrame(9200), std::size_t(23));
    CHECK_EQ(samplesPerFrame(16000), std::size_t(320));
    CHECK_EQ(samplesPerFrame(8000), std::size_t(160));
    CHECK(bitrateForFrameBytes(8).value_or(0) == 3200);
    CHECK(bitrateForFrameBytes(15).value_or(0) == 6000);
    CHECK(bitrateForFrameBytes(23).value_or(0) == 9200);
    CHECK(!bitrateForFrameBytes(9).has_value());
}

TEST(library_version_is_1_3_x) {
    const std::string v = libraryVersion();
    CHECK(v.rfind("1.3", 0) == 0);
}

TEST(validate_model_path_detects_missing) {
    CHECK(validateModelPath("/definitely/not/here").has_value());
    CHECK(!validateModelPath(g_models).has_value());
}

TEST(probe_succeeds_on_real_models) {
    auto err = probe(g_models, 16000);
    if (err) std::fprintf(stderr, "probe error: %s\n", err->c_str());
    CHECK(!err.has_value());
}

TEST(encode_decode_round_trip_produces_audio) {
    auto enc = Encoder::create(16000, 3200, /*dtx=*/false, g_models);
    auto dec = Decoder::create(16000, g_models);
    CHECK(enc != nullptr);
    CHECK(dec != nullptr);
    if (!enc || !dec) return;

    const std::size_t n = 320;
    // A 300 Hz sine — real signal, so DTX (even if on) would not blank it.
    std::vector<int16_t> pcm(n);
    for (std::size_t i = 0; i < n; ++i) {
        pcm[i] = static_cast<int16_t>(8000.0 * std::sin(2.0 * 3.14159265 * 300.0 * i / 16000.0));
    }
    std::vector<std::uint8_t> packet(kMaxFrameBytes);
    // Prime the codec over several frames (the generative model needs context).
    std::vector<int16_t> out(n);
    bool decodedOk = false;
    std::size_t lastBytes = 0;
    for (int frame = 0; frame < 20; ++frame) {
        auto bytes = enc->encodeFrame(pcm.data(), n, packet.data(), packet.size());
        CHECK(bytes.has_value());
        if (!bytes) return;
        lastBytes = *bytes;
        CHECK_EQ(*bytes, bytesPerFrame(3200));
        decodedOk = dec->decodeFrame(packet.data(), *bytes, out.data(), n);
    }
    CHECK_EQ(lastBytes, std::size_t(8));
    CHECK(decodedOk);
    // After priming, the decoder should produce non-silent output for a tone.
    double energy = 0;
    for (int16_t s : out) energy += static_cast<double>(s) * s;
    CHECK(energy > 0.0);
}

TEST(decoder_conceals_lost_frame) {
    auto dec = Decoder::create(16000, g_models);
    CHECK(dec != nullptr);
    if (!dec) return;
    std::vector<int16_t> out(320, 123);
    CHECK(dec->conceal(out.data(), 320));  // fills a whole frame, no crash
}

TEST(decoder_rejects_wrong_frame_size_without_crashing) {
    auto dec = Decoder::create(16000, g_models);
    CHECK(dec != nullptr);
    if (!dec) return;
    std::vector<std::uint8_t> junk(9, 0xAB);  // 9 bytes is not a valid Lyra frame
    std::vector<int16_t> out(320, 0);
    // decodeFrame returns false but still fills the frame (concealment), never aborts.
    CHECK(!dec->decodeFrame(junk.data(), junk.size(), out.data(), 320));
}

TEST(encoder_switches_bitrate) {
    auto enc = Encoder::create(16000, 3200, false, g_models);
    CHECK(enc != nullptr);
    if (!enc) return;
    CHECK(enc->setBitrate(9200));
    CHECK_EQ(enc->bitrate(), 9200);
    std::vector<int16_t> pcm(320, 0);
    std::vector<std::uint8_t> packet(kMaxFrameBytes);
    auto bytes = enc->encodeFrame(pcm.data(), 320, packet.data(), packet.size());
    CHECK(bytes.has_value());
    if (bytes) CHECK_EQ(*bytes, bytesPerFrame(9200));
    CHECK(!enc->setBitrate(5000));  // unsupported, rejected
}

TEST(create_fails_cleanly_on_bad_params) {
    CHECK(Encoder::create(44100, 3200, false, g_models) == nullptr);  // unsupported rate
    CHECK(Encoder::create(16000, 5000, false, g_models) == nullptr);  // unsupported bitrate
    CHECK(Decoder::create(16000, "/no/such/dir") == nullptr);         // missing models
}

int main(int argc, char** argv) {
    g_models = argc > 1 ? argv[1] : "";
    if (g_models.empty()) {
        std::fprintf(stderr, "usage: %s <model_coeffs_dir>\n", argv[0]);
        return 2;
    }
    int run = 0, failed_before = 0;
    for (auto& c : ::check::cases()) {
        failed_before = ::check::failures();
        c.fn();
        ++run;
        std::printf("  %-48s %s\n", c.name.c_str(), ::check::failures() == failed_before ? "ok" : "FAILED");
    }
    std::printf("%d test(s), %d failure(s)\n", run, ::check::failures());
    return ::check::failures() == 0 ? 0 : 1;
}
