#include "lyra_adapter.h"

#include <algorithm>
#include <cstring>
#include <vector>

// Library headers in the layout pjsip/lyra/CMakeLists.txt installs (the same one
// pjproject's lyra.cpp compiles against): lyra_encoder.h/lyra_decoder.h at the
// prefix root, lyra/*.h beside them, absl and ghc::filesystem under include/.
#include "absl/types/span.h"
#include "include/ghc/filesystem.hpp"
#include "lyra/lyra_config.h"
#include "lyra_decoder.h"
#include "lyra_encoder.h"

namespace coralx::lyra {

using chromemedia::codec::LyraDecoder;
using chromemedia::codec::LyraEncoder;

std::optional<int> bitrateForFrameBytes(std::size_t bytes) noexcept {
    for (int b : kSupportedBitrates) {
        if (bytesPerFrame(b) == bytes) return b;
    }
    return std::nullopt;
}

std::string libraryVersion() {
    return chromemedia::codec::GetVersionString();
}

std::optional<std::string> validateModelPath(const std::string& modelPath) {
    if (modelPath.empty()) return "model path is empty";
    std::error_code ec;
    const ghc::filesystem::path dir(modelPath);
    if (!ghc::filesystem::is_directory(dir, ec) || ec) {
        return "model path is not a directory: " + modelPath;
    }
    // AreParamsSupported checks the three .tflite assets and parses lyra_config.binarypb
    // (the identifier must match the library's minor version) — the exact checks the
    // library performs in Create(), surfaced as a message instead of a nullptr.
    const absl::Status st = chromemedia::codec::AreParamsSupported(kNativeSampleRate, kChannels, dir);
    if (!st.ok()) return std::string(st.message());
    return std::nullopt;
}

std::optional<std::string> probe(const std::string& modelPath, int sampleRate) {
    if (!isSupportedSampleRate(sampleRate)) {
        return "unsupported sample rate " + std::to_string(sampleRate);
    }
    if (auto err = validateModelPath(modelPath)) return err;

    // The constants this file pins must be the library's, or every size computed
    // from them is wrong. Checked once, here, instead of trusted.
    if (chromemedia::codec::kFrameRate != kFrameRateHz) {
        return "library frame rate " + std::to_string(chromemedia::codec::kFrameRate) +
               " differs from the expected " + std::to_string(kFrameRateHz);
    }
    if (chromemedia::codec::kNumChannels != kChannels) {
        return "library channel count differs from the expected " + std::to_string(kChannels);
    }
    for (int b : kSupportedBitrates) {
        const int bits = chromemedia::codec::BitrateToNumQuantizedBits(b);
        if (bits < 0 || static_cast<std::size_t>(chromemedia::codec::GetPacketSize(bits)) != bytesPerFrame(b)) {
            return "library packet size for " + std::to_string(b) + " bit/s differs from the expected " +
                   std::to_string(bytesPerFrame(b));
        }
    }

    auto enc = Encoder::create(sampleRate, kSupportedBitrates.front(), false, modelPath);
    if (!enc) return "encoder could not be created from " + modelPath;
    auto dec = Decoder::create(sampleRate, modelPath);
    if (!dec) return "decoder could not be created from " + modelPath;

    const std::size_t n = samplesPerFrame(sampleRate);
    std::vector<int16_t> pcm(n, 0);
    std::array<std::uint8_t, kMaxFrameBytes> packet{};
    const auto bytes = enc->encodeFrame(pcm.data(), n, packet.data(), packet.size());
    if (!bytes || *bytes != bytesPerFrame(kSupportedBitrates.front())) {
        return "encoder produced an unexpected packet size";
    }
    if (!dec->decodeFrame(packet.data(), *bytes, pcm.data(), n)) {
        return "decoder rejected the encoder's own packet";
    }
    return std::nullopt;
}

// ------------------------------------------------------------------ Encoder

struct Encoder::Impl {
    std::unique_ptr<LyraEncoder> lyra;
};

Encoder::Encoder(std::unique_ptr<Impl> impl, int sampleRate, int bitrate) noexcept
    : impl_(std::move(impl)), sampleRate_(sampleRate), bitrate_(bitrate) {}

Encoder::~Encoder() = default;

std::unique_ptr<Encoder> Encoder::create(int sampleRate, int bitrate, bool dtx,
                                         const std::string& modelPath) noexcept {
    if (!isSupportedSampleRate(sampleRate) || !isSupportedBitrate(bitrate)) return nullptr;
    try {
        auto lyra = LyraEncoder::Create(sampleRate, kChannels, bitrate, dtx, ghc::filesystem::path(modelPath));
        if (!lyra) return nullptr;
        auto impl = std::make_unique<Impl>();
        impl->lyra = std::move(lyra);
        return std::unique_ptr<Encoder>(new Encoder(std::move(impl), sampleRate, bitrate));
    } catch (...) {
        return nullptr;
    }
}

std::optional<std::size_t> Encoder::encodeFrame(const int16_t* pcm, std::size_t samples,
                                                std::uint8_t* out, std::size_t outCapacity) noexcept {
    if (!pcm || !out || samples != samplesPerFrame()) return std::nullopt;
    try {
        // The library returns a freshly allocated vector per frame; that allocation is
        // the library's API, not ours, and is bounded (<= 23 bytes).
        auto encoded = impl_->lyra->Encode(absl::MakeConstSpan(pcm, samples));
        if (!encoded) return std::nullopt;
        if (encoded->size() > outCapacity) return std::nullopt;
        if (!encoded->empty()) std::memcpy(out, encoded->data(), encoded->size());
        return encoded->size();
    } catch (...) {
        return std::nullopt;
    }
}

bool Encoder::setBitrate(int bitrate) noexcept {
    if (!isSupportedBitrate(bitrate)) return false;
    try {
        if (!impl_->lyra->set_bitrate(bitrate)) return false;
    } catch (...) {
        return false;
    }
    bitrate_ = bitrate;
    return true;
}

// ------------------------------------------------------------------ Decoder

struct Decoder::Impl {
    std::unique_ptr<LyraDecoder> lyra;
};

Decoder::Decoder(std::unique_ptr<Impl> impl, int sampleRate) noexcept
    : impl_(std::move(impl)), sampleRate_(sampleRate) {}

Decoder::~Decoder() = default;

std::unique_ptr<Decoder> Decoder::create(int sampleRate, const std::string& modelPath) noexcept {
    if (!isSupportedSampleRate(sampleRate)) return nullptr;
    try {
        auto lyra = LyraDecoder::Create(sampleRate, kChannels, ghc::filesystem::path(modelPath));
        if (!lyra) return nullptr;
        auto impl = std::make_unique<Impl>();
        impl->lyra = std::move(lyra);
        return std::unique_ptr<Decoder>(new Decoder(std::move(impl), sampleRate));
    } catch (...) {
        return nullptr;
    }
}

bool Decoder::generate(int16_t* pcm, std::size_t samples) noexcept {
    // DecodeSamples may return fewer samples than asked (it works in internal hops),
    // so loop until the frame is full — the same loop pjmedia's lyra.cpp runs.
    std::size_t done = 0;
    try {
        while (done < samples) {
            auto chunk = impl_->lyra->DecodeSamples(static_cast<int>(samples - done));
            if (!chunk || chunk->empty()) {
                std::memset(pcm + done, 0, (samples - done) * sizeof(int16_t));
                return false;
            }
            const std::size_t n = std::min(chunk->size(), samples - done);
            std::memcpy(pcm + done, chunk->data(), n * sizeof(int16_t));
            done += n;
        }
        return true;
    } catch (...) {
        std::memset(pcm + done, 0, (samples - done) * sizeof(int16_t));
        return false;
    }
}

bool Decoder::decodeFrame(const std::uint8_t* packet, std::size_t bytes,
                          int16_t* pcm, std::size_t samples) noexcept {
    if (!pcm || samples != samplesPerFrame()) return false;
    bool accepted = false;
    if (packet && bitrateForFrameBytes(bytes)) {
        try {
            accepted = impl_->lyra->SetEncodedPacket(absl::MakeConstSpan(packet, bytes));
        } catch (...) {
            accepted = false;
        }
    }
    // A rejected packet is treated as a lost one: the generative model keeps
    // producing plausible audio from its state, and the caller learns it happened.
    const bool generated = generate(pcm, samples);
    return accepted && generated;
}

bool Decoder::conceal(int16_t* pcm, std::size_t samples) noexcept {
    if (!pcm || samples != samplesPerFrame()) return false;
    return generate(pcm, samples);
}

}  // namespace coralx::lyra
