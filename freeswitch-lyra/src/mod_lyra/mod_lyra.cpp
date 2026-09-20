// mod_lyra — the Lyra codec (Google Lyra v1.3.2) as a FreeSWITCH codec module.
//
// What FreeSWITCH gets from this: a `lyra/16000` audio codec it can negotiate,
// decode and encode. What it does NOT change: a bridge between two legs that both
// negotiated Lyra keeps relaying the encoded frames untouched — the core only calls
// decode() when something (a recording media bug, a conference, a resampler) needs
// PCM, and encode() only when it has PCM to send to a Lyra leg (tones, prompts, the
// mixer). See docs/02-hld.md §2.3.
//
// Threading: FreeSWITCH serialises every call into one codec instance behind
// codec->mutex (switch_core_codec.c: switch_core_codec_encode/decode/destroy), so a
// LyraCodecContext is only ever touched by one thread at a time and needs no lock
// of its own. Module-wide state (the config snapshot, the counters) is a mutex and
// atomics respectively.
//
// Memory: the context is a C++ object created with `new` in init() and deleted in
// destroy() — never pool-allocated, because it owns objects with destructors. The
// encoder and decoder are built lazily on first use, so the legs of a plain relayed
// call (the common case) never pay for a TFLite interpreter they do not need.

#include <switch.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>

#include "common/params.h"
#include "lyra_adapter.h"
#include "lyra_codec_config.h"

SWITCH_BEGIN_EXTERN_C
SWITCH_MODULE_LOAD_FUNCTION(mod_lyra_load);
SWITCH_MODULE_SHUTDOWN_FUNCTION(mod_lyra_shutdown);
SWITCH_MODULE_DEFINITION(mod_lyra, mod_lyra_load, mod_lyra_shutdown, NULL);
SWITCH_END_EXTERN_C

namespace {

using coralx::lyra::CodecConfig;
using coralx::lyra::Decoder;
using coralx::lyra::Encoder;

constexpr const char* kConfigFile = "lyra.conf";
constexpr const char* kCodecName = "lyra";
// A placeholder above 95; the SDP generator hands out the real dynamic payload
// type per call (switch_core_media.c: `smh->ianacodes[i] = payload_space++`).
constexpr switch_payload_t kDynamicPayloadPlaceholder = 96;
// After a failed encoder/decoder creation, try again only every this many frames
// (5 s at 50 frames/s) so a broken model directory does not cost a TFLite load per packet.
constexpr std::uint32_t kCreateRetryFrames = 250;

// Counters exposed by `lyra status`. Relaxed atomics: they are statistics, never
// synchronisation.
struct Metrics {
    std::atomic<std::uint64_t> codecsInitialised{0};
    std::atomic<std::uint64_t> encodersCreated{0};
    std::atomic<std::uint64_t> decodersCreated{0};
    std::atomic<std::uint64_t> createFailures{0};
    std::atomic<std::uint64_t> framesEncoded{0};
    std::atomic<std::uint64_t> framesDecoded{0};
    std::atomic<std::uint64_t> framesConcealed{0};
    std::atomic<std::uint64_t> encodeFailures{0};
    std::atomic<std::uint64_t> decodeFailures{0};
    std::atomic<std::uint64_t> bitrateAdaptations{0};
    std::atomic<std::uint64_t> configReloads{0};
    std::atomic<std::uint64_t> configReloadFailures{0};
};

struct ModuleState {
    std::mutex mutex;
    std::shared_ptr<const CodecConfig> config;  // replaced whole on reload, never mutated
    Metrics metrics;
};

ModuleState& state() {
    static ModuleState s;
    return s;
}

std::shared_ptr<const CodecConfig> currentConfig() {
    std::lock_guard<std::mutex> lock(state().mutex);
    return state().config;
}

void bump(std::atomic<std::uint64_t>& c, std::uint64_t n = 1) { c.fetch_add(n, std::memory_order_relaxed); }

// Logs the 1st, 10th, 100th and then every 1000th occurrence of something that
// can happen 50 times a second. Returns true when this occurrence should be logged.
bool shouldLog(std::uint64_t occurrence) {
    return occurrence == 1 || occurrence == 10 || occurrence == 100 || occurrence % 1000 == 0;
}

// ------------------------------------------------------------------ per-codec context

struct LyraCodecContext {
    std::shared_ptr<const CodecConfig> cfg;
    int sampleRate = 0;
    std::size_t samplesPerFrame = 0;
    std::size_t bytesPerPcmFrame = 0;
    int framesPerPacket = 1;
    int encodeBitrate = 0;  // what the peer asked us to send (its fmtp), or the default
    int decodeBitrate = 0;  // the frame size we expect from the peer; follows what arrives
    bool wantEncode = false;
    bool wantDecode = false;
    std::unique_ptr<Encoder> encoder;
    std::unique_ptr<Decoder> decoder;
    std::uint32_t encoderRetryIn = 0;
    std::uint32_t decoderRetryIn = 0;
    std::uint64_t decodeErrors = 0;  // per instance, for rate-limited logging
    std::uint64_t encodeErrors = 0;
};

#define LYRA_LOG(codec, level, ...)                                                        \
    do {                                                                                   \
        if ((codec)->session) {                                                            \
            switch_log_printf(SWITCH_CHANNEL_SESSION_LOG((codec)->session), level, __VA_ARGS__); \
        } else {                                                                           \
            switch_log_printf(SWITCH_CHANNEL_LOG, level, __VA_ARGS__);                     \
        }                                                                                  \
    } while (0)

LyraCodecContext* contextOf(switch_codec_t* codec) {
    return codec ? static_cast<LyraCodecContext*>(codec->private_info) : nullptr;
}

// `bitrate=6000` (case-insensitive, other parameters ignored). Returns 0 when absent
// or unusable; the caller decides the fallback.
int bitrateFromFmtp(const char* fmtp) {
    if (zstr(fmtp)) return 0;
    std::string s(fmtp);
    for (auto& c : s) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    const std::string key = "bitrate=";
    std::size_t pos = 0;
    while ((pos = s.find(key, pos)) != std::string::npos) {
        // Only at the start or after a separator, so "xbitrate=" does not count.
        if (pos == 0 || s[pos - 1] == ';' || s[pos - 1] == ' ') {
            const std::string val = s.substr(pos + key.size(), s.find_first_of("; ", pos + key.size()) - (pos + key.size()));
            const auto n = coralx::config::parseInt(val);
            return n ? static_cast<int>(*n) : 0;
        }
        pos += key.size();
    }
    return 0;
}

bool ensureEncoder(switch_codec_t* codec, LyraCodecContext& ctx) {
    if (ctx.encoder) return true;
    if (ctx.encoderRetryIn > 0) {
        --ctx.encoderRetryIn;
        return false;
    }
    ctx.encoder = Encoder::create(ctx.sampleRate, ctx.encodeBitrate, ctx.cfg->dtx, ctx.cfg->modelPath);
    if (!ctx.encoder) {
        bump(state().metrics.createFailures);
        ctx.encoderRetryIn = kCreateRetryFrames;
        LYRA_LOG(codec, SWITCH_LOG_ERROR, "Lyra encoder could not be created (%d Hz, %d bit/s, models at %s)\n",
                 ctx.sampleRate, ctx.encodeBitrate, ctx.cfg->modelPath.c_str());
        return false;
    }
    bump(state().metrics.encodersCreated);
    LYRA_LOG(codec, SWITCH_LOG_DEBUG, "Lyra encoder initialized: %d Hz, %d bit/s, dtx=%s\n",
             ctx.sampleRate, ctx.encodeBitrate, ctx.cfg->dtx ? "on" : "off");
    return true;
}

bool ensureDecoder(switch_codec_t* codec, LyraCodecContext& ctx) {
    if (ctx.decoder) return true;
    if (ctx.decoderRetryIn > 0) {
        --ctx.decoderRetryIn;
        return false;
    }
    ctx.decoder = Decoder::create(ctx.sampleRate, ctx.cfg->modelPath);
    if (!ctx.decoder) {
        bump(state().metrics.createFailures);
        ctx.decoderRetryIn = kCreateRetryFrames;
        LYRA_LOG(codec, SWITCH_LOG_ERROR, "Lyra decoder could not be created (%d Hz, models at %s)\n",
                 ctx.sampleRate, ctx.cfg->modelPath.c_str());
        return false;
    }
    bump(state().metrics.decodersCreated);
    LYRA_LOG(codec, SWITCH_LOG_DEBUG, "Lyra decoder initialized: %d Hz, expecting %d bit/s frames\n",
             ctx.sampleRate, ctx.decodeBitrate);
    return true;
}

// ------------------------------------------------------------------ codec callbacks

switch_status_t lyra_init(switch_codec_t* codec, switch_codec_flag_t flags, const switch_codec_settings_t* /*settings*/) {
    auto cfg = currentConfig();
    if (!cfg || !codec || !codec->implementation) {
        switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_ERROR, "Lyra codec init without a configuration\n");
        return SWITCH_STATUS_FALSE;
    }
    const bool encoding = (flags & SWITCH_CODEC_FLAG_ENCODE) != 0;
    const bool decoding = (flags & SWITCH_CODEC_FLAG_DECODE) != 0;
    if (!encoding && !decoding) return SWITCH_STATUS_FALSE;

    auto ctx = std::make_unique<LyraCodecContext>();
    ctx->cfg = cfg;
    ctx->sampleRate = static_cast<int>(codec->implementation->actual_samples_per_second);
    ctx->samplesPerFrame = coralx::lyra::samplesPerFrame(ctx->sampleRate);
    ctx->bytesPerPcmFrame = ctx->samplesPerFrame * sizeof(int16_t);
    ctx->framesPerPacket = codec->implementation->microseconds_per_packet / (coralx::lyra::kFrameMs * 1000);
    if (ctx->framesPerPacket < 1) ctx->framesPerPacket = 1;
    ctx->wantEncode = encoding;
    ctx->wantDecode = decoding;

    // The peer's fmtp says what it wants to receive. That is our encode rate, and —
    // since we decode any frame size — also what we echo back so both directions
    // run at the rate the peer chose (the behaviour pjmedia's lyra.cpp expects).
    int requested = bitrateFromFmtp(codec->fmtp_in);
    if (requested != 0 && !coralx::lyra::isSupportedBitrate(requested)) {
        LYRA_LOG(codec, SWITCH_LOG_WARNING, "Lyra fmtp asks for %d bit/s which is not 3200/6000/9200; using %d\n",
                 requested, cfg->defaultBitrate);
        requested = 0;
    }
    ctx->encodeBitrate = requested ? requested : cfg->defaultBitrate;
    ctx->decodeBitrate = ctx->encodeBitrate;
    codec->fmtp_out = switch_core_sprintf(codec->memory_pool, "bitrate=%d", ctx->encodeBitrate);

    // Lyra conceals loss itself (generative model, then comfort noise); tell the core
    // so it does not layer its generic PLC on top.
    switch_set_flag(codec, SWITCH_CODEC_FLAG_HAS_PLC);

    codec->private_info = ctx.release();
    bump(state().metrics.codecsInitialised);
    LYRA_LOG(codec, SWITCH_LOG_DEBUG, "Lyra codec initialized: %d Hz, %d ms, %d bit/s, %s%s\n",
             contextOf(codec)->sampleRate, codec->implementation->microseconds_per_packet / 1000,
             contextOf(codec)->encodeBitrate, encoding ? "encode " : "", decoding ? "decode" : "");
    return SWITCH_STATUS_SUCCESS;
}

switch_status_t lyra_destroy(switch_codec_t* codec) {
    auto* ctx = contextOf(codec);
    delete ctx;
    if (codec) codec->private_info = nullptr;
    return SWITCH_STATUS_SUCCESS;
}

switch_status_t lyra_encode(switch_codec_t* codec, switch_codec_t* /*other_codec*/,
                            void* decoded_data, uint32_t decoded_data_len, uint32_t /*decoded_rate*/,
                            void* encoded_data, uint32_t* encoded_data_len, uint32_t* /*encoded_rate*/,
                            unsigned int* flag) {
    auto* ctx = contextOf(codec);
    if (!ctx || !ctx->wantEncode || !decoded_data || !encoded_data || !encoded_data_len) {
        if (encoded_data_len) *encoded_data_len = 0;
        return SWITCH_STATUS_FALSE;
    }
    const uint32_t capacity = *encoded_data_len;
    *encoded_data_len = 0;

    if (!ensureEncoder(codec, *ctx)) return SWITCH_STATUS_FALSE;

    if (decoded_data_len == 0 || decoded_data_len % ctx->bytesPerPcmFrame != 0) {
        bump(state().metrics.encodeFailures);
        if (shouldLog(++ctx->encodeErrors)) {
            LYRA_LOG(codec, SWITCH_LOG_WARNING, "Lyra encode: %u PCM bytes is not a multiple of one frame (%zu); dropped (x%llu)\n",
                     decoded_data_len, ctx->bytesPerPcmFrame, static_cast<unsigned long long>(ctx->encodeErrors));
        }
        return SWITCH_STATUS_FALSE;
    }

    const std::size_t frames = decoded_data_len / ctx->bytesPerPcmFrame;
    const auto* pcm = static_cast<const int16_t*>(decoded_data);
    auto* out = static_cast<std::uint8_t*>(encoded_data);
    std::size_t written = 0;
    for (std::size_t f = 0; f < frames; ++f) {
        if (capacity - written < coralx::lyra::kMaxFrameBytes) {
            bump(state().metrics.encodeFailures);
            return SWITCH_STATUS_FALSE;
        }
        const auto n = ctx->encoder->encodeFrame(pcm + f * ctx->samplesPerFrame, ctx->samplesPerFrame,
                                                 out + written, capacity - written);
        if (!n) {
            bump(state().metrics.encodeFailures);
            if (shouldLog(++ctx->encodeErrors)) {
                LYRA_LOG(codec, SWITCH_LOG_WARNING, "Lyra encoder error (x%llu)\n",
                         static_cast<unsigned long long>(ctx->encodeErrors));
            }
            return SWITCH_STATUS_FALSE;
        }
        written += *n;
    }
    bump(state().metrics.framesEncoded, frames);

    if (written == 0) {
        // DTX: the encoder judged every frame to be background noise. Mark the
        // frame as comfort noise so the RTP layer sends nothing; the peer's decoder
        // fills the gap (pjmedia's lyra_codec_recover).
        if (flag) *flag |= SFF_CNG;
        return SWITCH_STATUS_SUCCESS;
    }
    *encoded_data_len = static_cast<uint32_t>(written);
    return SWITCH_STATUS_SUCCESS;
}

switch_status_t lyra_decode(switch_codec_t* codec, switch_codec_t* /*other_codec*/,
                            void* encoded_data, uint32_t encoded_data_len, uint32_t /*encoded_rate*/,
                            void* decoded_data, uint32_t* decoded_data_len, uint32_t* decoded_rate,
                            unsigned int* flag) {
    auto* ctx = contextOf(codec);
    if (!ctx || !ctx->wantDecode || !decoded_data || !decoded_data_len) {
        if (decoded_data_len) *decoded_data_len = 0;
        return SWITCH_STATUS_FALSE;
    }
    const uint32_t capacity = *decoded_data_len;
    *decoded_data_len = 0;
    if (decoded_rate) *decoded_rate = static_cast<uint32_t>(ctx->sampleRate);

    if (!ensureDecoder(codec, *ctx)) return SWITCH_STATUS_FALSE;
    auto* pcm = static_cast<int16_t*>(decoded_data);
    const std::size_t maxFrames = capacity / ctx->bytesPerPcmFrame;
    if (maxFrames == 0) return SWITCH_STATUS_FALSE;

    // A lost packet (the RTP layer raises SFF_PLC) or an empty one: conceal one
    // packet's worth of audio from the model's state.
    const bool lost = (flag && (*flag & SFF_PLC)) || encoded_data_len == 0 || !encoded_data;
    if (lost) {
        const std::size_t frames = std::min<std::size_t>(static_cast<std::size_t>(ctx->framesPerPacket), maxFrames);
        for (std::size_t f = 0; f < frames; ++f) {
            ctx->decoder->conceal(pcm + f * ctx->samplesPerFrame, ctx->samplesPerFrame);
        }
        bump(state().metrics.framesConcealed, frames);
        if (flag) *flag &= ~SFF_PLC;
        *decoded_data_len = static_cast<uint32_t>(frames * ctx->bytesPerPcmFrame);
        return SWITCH_STATUS_SUCCESS;
    }

    // The peer may encode at a different rate than it advertised (or change it
    // mid-call); a packet that is exactly one frame of another supported size is
    // followed rather than dropped.
    std::size_t frameBytes = coralx::lyra::bytesPerFrame(ctx->decodeBitrate);
    if (encoded_data_len % frameBytes != 0) {
        if (const auto other = coralx::lyra::bitrateForFrameBytes(encoded_data_len)) {
            LYRA_LOG(codec, SWITCH_LOG_NOTICE, "Lyra peer switched from %d to %d bit/s; following\n",
                     ctx->decodeBitrate, *other);
            ctx->decodeBitrate = *other;
            frameBytes = encoded_data_len;
            bump(state().metrics.bitrateAdaptations);
        } else {
            // Not a Lyra payload we understand: conceal instead of feeding garbage
            // to the model, and keep the stream continuous.
            bump(state().metrics.decodeFailures);
            if (shouldLog(++ctx->decodeErrors)) {
                LYRA_LOG(codec, SWITCH_LOG_WARNING, "Lyra decode: %u-byte payload is not a whole number of %zu-byte frames; concealed (x%llu)\n",
                         encoded_data_len, frameBytes, static_cast<unsigned long long>(ctx->decodeErrors));
            }
            ctx->decoder->conceal(pcm, ctx->samplesPerFrame);
            bump(state().metrics.framesConcealed);
            *decoded_data_len = static_cast<uint32_t>(ctx->bytesPerPcmFrame);
            return SWITCH_STATUS_SUCCESS;
        }
    }

    const std::size_t frames = std::min(static_cast<std::size_t>(encoded_data_len / frameBytes), maxFrames);
    const auto* in = static_cast<const std::uint8_t*>(encoded_data);
    std::size_t failed = 0;
    for (std::size_t f = 0; f < frames; ++f) {
        if (!ctx->decoder->decodeFrame(in + f * frameBytes, frameBytes, pcm + f * ctx->samplesPerFrame, ctx->samplesPerFrame)) {
            ++failed;
        }
    }
    if (failed) {
        bump(state().metrics.decodeFailures, failed);
        if (shouldLog(ctx->decodeErrors += failed)) {
            LYRA_LOG(codec, SWITCH_LOG_WARNING, "Lyra decoder rejected %zu frame(s); concealed (x%llu)\n",
                     failed, static_cast<unsigned long long>(ctx->decodeErrors));
        }
    }
    bump(state().metrics.framesDecoded, frames - failed);
    bump(state().metrics.framesConcealed, failed);
    *decoded_data_len = static_cast<uint32_t>(frames * ctx->bytesPerPcmFrame);
    return SWITCH_STATUS_SUCCESS;
}

// ------------------------------------------------------------------ configuration

std::string defaultModelPath() {
    // <prefix>/share/freeswitch/lyra/model_coeffs — where scripts/install.sh puts the
    // four model files. data_dir is FreeSWITCH's own name for that share directory.
    const char* data = SWITCH_GLOBAL_dirs.data_dir;
    if (zstr(data)) data = SWITCH_GLOBAL_dirs.base_dir;
    if (zstr(data)) return "";
    return std::string(data) + SWITCH_PATH_SEPARATOR "lyra" SWITCH_PATH_SEPARATOR "model_coeffs";
}

// Reads lyra.conf.xml (missing file = all defaults), validates it, and proves the
// models load. Returns the new configuration or nullptr after logging why.
std::shared_ptr<const CodecConfig> loadConfig(const char* reason) {
    coralx::config::Params params;
    switch_xml_t xml = nullptr, cfg = nullptr;
    if ((xml = switch_xml_open_cfg(kConfigFile, &cfg, nullptr))) {
        if (switch_xml_t settings = switch_xml_child(cfg, "settings")) {
            for (switch_xml_t p = switch_xml_child(settings, "param"); p; p = p->next) {
                const char* name = switch_xml_attr_soft(p, "name");
                const char* value = switch_xml_attr_soft(p, "value");
                if (!zstr(name)) params.set(name, value ? value : "");
            }
        }
        switch_xml_free(xml);
    } else {
        switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_NOTICE, "Lyra: no %s.xml found; using defaults\n", kConfigFile);
    }

    auto parsed = coralx::lyra::parseCodecConfig(params, defaultModelPath());
    for (const auto& w : parsed.warnings) {
        switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_WARNING, "Lyra config (%s): %s\n", reason, w.c_str());
    }
    for (const auto& e : parsed.errors) {
        switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_ERROR, "Lyra config (%s): %s\n", reason, e.c_str());
    }
    if (!parsed.ok()) return nullptr;

    for (int rate : parsed.value->sampleRates) {
        if (auto err = coralx::lyra::probe(parsed.value->modelPath, rate)) {
            switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_ERROR, "Lyra config (%s): models at %s unusable at %d Hz: %s\n",
                              reason, parsed.value->modelPath.c_str(), rate, err->c_str());
            return nullptr;
        }
    }
    return std::make_shared<const CodecConfig>(std::move(*parsed.value));
}

void writeStatus(switch_stream_handle_t* stream, bool json) {
    auto cfg = currentConfig();
    const Metrics& m = state().metrics;
    const auto load = [](const std::atomic<std::uint64_t>& c) { return static_cast<double>(c.load(std::memory_order_relaxed)); };
    if (json) {
        cJSON* root = cJSON_CreateObject();
        cJSON_AddStringToObject(root, "library_version", coralx::lyra::libraryVersion().c_str());
        if (cfg) {
            cJSON* c = cJSON_AddObjectToObject(root, "config");
            cJSON_AddStringToObject(c, "model_path", cfg->modelPath.c_str());
            cJSON_AddNumberToObject(c, "default_bitrate", cfg->defaultBitrate);
            cJSON_AddBoolToObject(c, "dtx", cfg->dtx);
            cJSON_AddNumberToObject(c, "ptime", cfg->ptimeMs);
            cJSON* rates = cJSON_AddArrayToObject(c, "sample_rates");
            for (int r : cfg->sampleRates) cJSON_AddItemToArray(rates, cJSON_CreateNumber(r));
        }
        cJSON* mm = cJSON_AddObjectToObject(root, "metrics");
        cJSON_AddNumberToObject(mm, "codecs_initialised", load(m.codecsInitialised));
        cJSON_AddNumberToObject(mm, "encoders_created", load(m.encodersCreated));
        cJSON_AddNumberToObject(mm, "decoders_created", load(m.decodersCreated));
        cJSON_AddNumberToObject(mm, "create_failures", load(m.createFailures));
        cJSON_AddNumberToObject(mm, "frames_encoded", load(m.framesEncoded));
        cJSON_AddNumberToObject(mm, "frames_decoded", load(m.framesDecoded));
        cJSON_AddNumberToObject(mm, "frames_concealed", load(m.framesConcealed));
        cJSON_AddNumberToObject(mm, "encode_failures", load(m.encodeFailures));
        cJSON_AddNumberToObject(mm, "decode_failures", load(m.decodeFailures));
        cJSON_AddNumberToObject(mm, "bitrate_adaptations", load(m.bitrateAdaptations));
        cJSON_AddNumberToObject(mm, "config_reloads", load(m.configReloads));
        cJSON_AddNumberToObject(mm, "config_reload_failures", load(m.configReloadFailures));
        char* text = cJSON_PrintUnformatted(root);
        stream->write_function(stream, "%s\n", text ? text : "{}");
        switch_safe_free(text);
        cJSON_Delete(root);
        return;
    }
    stream->write_function(stream, "Lyra codec (library %s)\n", coralx::lyra::libraryVersion().c_str());
    if (cfg) {
        stream->write_function(stream, "  model-path        %s\n", cfg->modelPath.c_str());
        stream->write_function(stream, "  default-bitrate   %d\n", cfg->defaultBitrate);
        stream->write_function(stream, "  dtx               %s\n", cfg->dtx ? "true" : "false");
        stream->write_function(stream, "  ptime             %d ms\n", cfg->ptimeMs);
        std::string rates;
        for (int r : cfg->sampleRates) rates += (rates.empty() ? "" : ",") + std::to_string(r);
        stream->write_function(stream, "  sample-rates      %s\n", rates.c_str());
    }
    stream->write_function(stream, "  codecs initialised %llu, encoders %llu, decoders %llu, create failures %llu\n",
                           (unsigned long long)m.codecsInitialised.load(), (unsigned long long)m.encodersCreated.load(),
                           (unsigned long long)m.decodersCreated.load(), (unsigned long long)m.createFailures.load());
    stream->write_function(stream, "  frames encoded %llu, decoded %llu, concealed %llu\n",
                           (unsigned long long)m.framesEncoded.load(), (unsigned long long)m.framesDecoded.load(),
                           (unsigned long long)m.framesConcealed.load());
    stream->write_function(stream, "  encode failures %llu, decode failures %llu, bitrate adaptations %llu\n",
                           (unsigned long long)m.encodeFailures.load(), (unsigned long long)m.decodeFailures.load(),
                           (unsigned long long)m.bitrateAdaptations.load());
    stream->write_function(stream, "  config reloads %llu (failed %llu)\n",
                           (unsigned long long)m.configReloads.load(), (unsigned long long)m.configReloadFailures.load());
}

#define LYRA_API_SYNTAX "lyra status [json] | lyra reload | lyra version"

SWITCH_STANDARD_API(lyra_api) {
    (void)session;
    std::string command = cmd ? coralx::config::trim(cmd) : "";
    std::string arg;
    if (const auto sp = command.find(' '); sp != std::string::npos) {
        arg = coralx::config::trim(command.substr(sp + 1));
        command = command.substr(0, sp);
    }
    if (command.empty() || command == "status") {
        writeStatus(stream, arg == "json");
        return SWITCH_STATUS_SUCCESS;
    }
    if (command == "version") {
        stream->write_function(stream, "%s\n", coralx::lyra::libraryVersion().c_str());
        return SWITCH_STATUS_SUCCESS;
    }
    if (command == "reload") {
        // Re-reads the XML already reloaded by `reloadxml`. model-path and dtx apply
        // to every codec instance created from now on; default-bitrate, ptime and
        // sample-rates are baked into the registered implementations and need
        // `reload mod_lyra` (which FreeSWITCH refuses while the codec is in use).
        auto fresh = loadConfig("reload");
        if (!fresh) {
            bump(state().metrics.configReloadFailures);
            stream->write_function(stream, "-ERR configuration rejected; previous configuration kept (see log)\n");
            return SWITCH_STATUS_SUCCESS;
        }
        auto old = currentConfig();
        {
            std::lock_guard<std::mutex> lock(state().mutex);
            state().config = fresh;
        }
        bump(state().metrics.configReloads);
        const bool needsModuleReload = old && (old->defaultBitrate != fresh->defaultBitrate || old->ptimeMs != fresh->ptimeMs ||
                                               old->sampleRates != fresh->sampleRates);
        switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO, "Lyra configuration reloaded (models at %s)\n", fresh->modelPath.c_str());
        stream->write_function(stream, "+OK reloaded%s\n",
                               needsModuleReload ? " (default-bitrate/ptime/sample-rates changes take effect after `reload mod_lyra`)" : "");
        return SWITCH_STATUS_SUCCESS;
    }
    stream->write_function(stream, "-USAGE: %s\n", LYRA_API_SYNTAX);
    return SWITCH_STATUS_SUCCESS;
}

}  // namespace

// ------------------------------------------------------------------ module entry points

SWITCH_MODULE_LOAD_FUNCTION(mod_lyra_load) {
    auto cfg = loadConfig("load");
    if (!cfg) {
        switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_ERROR,
                          "mod_lyra not loaded: fix %s.xml (model-path must hold lyra_config.binarypb, "
                          "lyragan.tflite, quantizer.tflite, soundstream_encoder.tflite)\n", kConfigFile);
        return SWITCH_STATUS_FALSE;
    }
    {
        std::lock_guard<std::mutex> lock(state().mutex);
        state().config = cfg;
    }

    *module_interface = switch_loadable_module_create_module_interface(pool, modname);

    switch_api_interface_t* api = nullptr;
    SWITCH_ADD_API(api, "lyra", "Lyra codec status, metrics and configuration reload", lyra_api, LYRA_API_SYNTAX);
    switch_console_set_complete("add lyra status");
    switch_console_set_complete("add lyra status json");
    switch_console_set_complete("add lyra reload");
    switch_console_set_complete("add lyra version");

    switch_codec_interface_t* codec_interface = nullptr;
    SWITCH_ADD_CODEC(codec_interface, "Lyra (Google)");

    char* default_fmtp = switch_core_sprintf(pool, "bitrate=%d", cfg->defaultBitrate);
    const int ptimeUs = cfg->ptimeMs * 1000;
    for (int rate : cfg->sampleRates) {
        const uint32_t samplesPerPacket = static_cast<uint32_t>(rate) * static_cast<uint32_t>(cfg->ptimeMs) / 1000;
        switch_core_codec_add_implementation(pool, codec_interface, SWITCH_CODEC_TYPE_AUDIO,
                                             kDynamicPayloadPlaceholder,  /* placeholder; assigned per SDP */
                                             kCodecName,                  /* IANA name */
                                             default_fmtp,                /* fmtp FreeSWITCH offers */
                                             static_cast<uint32_t>(rate), /* samples per second */
                                             static_cast<uint32_t>(rate), /* actual samples per second */
                                             cfg->defaultBitrate,         /* bits per second (informational) */
                                             ptimeUs,                     /* microseconds per packet */
                                             samplesPerPacket,            /* samples per packet */
                                             samplesPerPacket * 2,        /* decoded bytes per packet */
                                             0,                           /* encoded bytes per packet: varies with bitrate */
                                             1,                           /* channels */
                                             cfg->framesPerPacket(),      /* codec frames per packet */
                                             lyra_init, lyra_encode, lyra_decode, lyra_destroy);
        switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO, "Lyra codec registered: %s/%d, %d ms, default %d bit/s, models at %s\n",
                          kCodecName, rate, cfg->ptimeMs, cfg->defaultBitrate, cfg->modelPath.c_str());
    }
    switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO, "mod_lyra loaded (Lyra library %s)\n", coralx::lyra::libraryVersion().c_str());
    return SWITCH_STATUS_SUCCESS;
}

SWITCH_MODULE_SHUTDOWN_FUNCTION(mod_lyra_shutdown) {
    std::lock_guard<std::mutex> lock(state().mutex);
    state().config.reset();
    return SWITCH_STATUS_SUCCESS;
}
