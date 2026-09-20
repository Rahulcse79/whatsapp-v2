// The one place that knows the Lyra library exists.
//
// Everything above this header (the FreeSWITCH codec glue, the tests) speaks in
// int16 PCM frames and byte packets. Nothing else includes a Lyra, absl, glog or
// TFLite header, so the library can be swapped or bumped behind this file alone.
//
// Facts pinned here come from third_party/lyra/lyra/lyra_config.{h,cc} (v1.3.2):
// 50 frames/s, mono, packet sizes 8/15/23 bytes for 3200/6000/9200 bit/s, sample
// rates 8/16/32/48 kHz. They are asserted against the library at start-up
// (probe()) rather than trusted.
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <string_view>

namespace coralx::lyra {

// Frames per second the codec produces; a frame is 1000/kFrameRateHz ms of audio.
inline constexpr int kFrameRateHz = 50;
inline constexpr int kFrameMs = 1000 / kFrameRateHz;
inline constexpr std::array<int, 3> kSupportedBitrates{3200, 6000, 9200};
inline constexpr std::array<int, 4> kSupportedSampleRates{8000, 16000, 32000, 48000};
inline constexpr int kNativeSampleRate = 16000;
inline constexpr int kChannels = 1;
// The largest packet one frame can produce (9200 bit/s). Buffers sized from this
// never need to grow on the media thread.
inline constexpr std::size_t kMaxFrameBytes = 23;

constexpr bool isSupportedBitrate(int bitrate) noexcept {
    for (int b : kSupportedBitrates) {
        if (b == bitrate) return true;
    }
    return false;
}

constexpr bool isSupportedSampleRate(int rate) noexcept {
    for (int r : kSupportedSampleRates) {
        if (r == rate) return true;
    }
    return false;
}

// Bytes one frame occupies on the wire at `bitrate`: ceil(bitrate / (50 * 8)).
constexpr std::size_t bytesPerFrame(int bitrate) noexcept {
    return static_cast<std::size_t>((bitrate + kFrameRateHz * 8 - 1) / (kFrameRateHz * 8));
}

// PCM samples in one frame at `sampleRate` (320 at 16 kHz).
constexpr std::size_t samplesPerFrame(int sampleRate) noexcept {
    return static_cast<std::size_t>(sampleRate / kFrameRateHz);
}

// The bitrate whose frame is exactly `bytes` long, or nullopt. Lets the decoder
// follow a peer that encodes at a rate other than the one it advertised.
std::optional<int> bitrateForFrameBytes(std::size_t bytes) noexcept;

// The library's own version string ("1.3.2").
std::string libraryVersion();

// Checks that `modelPath` holds every asset the library will open, without
// creating anything expensive. Returns an error message, or nullopt when usable.
std::optional<std::string> validateModelPath(const std::string& modelPath);

// Builds one encoder and one decoder from the models and runs a frame through
// both — the start-up proof that the models load and that the constants above
// match the library. Returns an error message or nullopt.
std::optional<std::string> probe(const std::string& modelPath, int sampleRate);

class Encoder {
public:
    // Fails (nullptr) on an unsupported rate/bitrate or unusable models. Never
    // throws; the library is not exception-safe.
    static std::unique_ptr<Encoder> create(int sampleRate, int bitrate, bool dtx,
                                           const std::string& modelPath) noexcept;
    ~Encoder();
    Encoder(const Encoder&) = delete;
    Encoder& operator=(const Encoder&) = delete;

    // Encodes exactly samplesPerFrame() samples into `out` (capacity >= kMaxFrameBytes).
    // Returns the packet size, 0 when DTX decided to send nothing, or nullopt on error.
    std::optional<std::size_t> encodeFrame(const int16_t* pcm, std::size_t samples,
                                           std::uint8_t* out, std::size_t outCapacity) noexcept;

    bool setBitrate(int bitrate) noexcept;
    int bitrate() const noexcept { return bitrate_; }
    int sampleRate() const noexcept { return sampleRate_; }
    std::size_t samplesPerFrame() const noexcept { return lyra::samplesPerFrame(sampleRate_); }

private:
    struct Impl;
    Encoder(std::unique_ptr<Impl> impl, int sampleRate, int bitrate) noexcept;
    std::unique_ptr<Impl> impl_;
    int sampleRate_;
    int bitrate_;
};

class Decoder {
public:
    static std::unique_ptr<Decoder> create(int sampleRate, const std::string& modelPath) noexcept;
    ~Decoder();
    Decoder(const Decoder&) = delete;
    Decoder& operator=(const Decoder&) = delete;

    // Decodes one packet (`bytes` must be 8, 15 or 23) into exactly samplesPerFrame()
    // samples. On a packet the library rejects the frame is concealed instead and
    // false is returned; `pcm` is always fully written.
    bool decodeFrame(const std::uint8_t* packet, std::size_t bytes,
                     int16_t* pcm, std::size_t samples) noexcept;

    // Packet-loss concealment: generates samplesPerFrame() samples from the model's
    // state (comfort noise after a while). False only if the library fails, in which
    // case `pcm` is zeroed.
    bool conceal(int16_t* pcm, std::size_t samples) noexcept;

    int sampleRate() const noexcept { return sampleRate_; }
    std::size_t samplesPerFrame() const noexcept { return lyra::samplesPerFrame(sampleRate_); }

private:
    struct Impl;
    Decoder(std::unique_ptr<Impl> impl, int sampleRate) noexcept;
    bool generate(int16_t* pcm, std::size_t samples) noexcept;
    std::unique_ptr<Impl> impl_;
    int sampleRate_;
};

}  // namespace coralx::lyra
