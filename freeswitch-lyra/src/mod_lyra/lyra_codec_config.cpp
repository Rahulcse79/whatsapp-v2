#include "lyra_codec_config.h"

#include <algorithm>
#include <set>

#include "lyra_adapter.h"

namespace coralx::lyra {

config::Parsed<CodecConfig> parseCodecConfig(const config::Params& params,
                                             const std::string& defaultModelPath) {
    config::Parsed<CodecConfig> out;
    CodecConfig cfg;

    static const std::set<std::string> known{"model_path", "default_bitrate", "dtx", "sample_rates", "ptime"};
    for (const auto& k : params.keys()) {
        if (!known.count(k)) out.warnings.push_back("unknown parameter '" + k + "' ignored");
    }

    cfg.modelPath = config::trim(params.get("model-path").value_or(defaultModelPath));
    if (cfg.modelPath.empty()) {
        out.errors.push_back("model-path is empty and no default is available");
    } else if (cfg.modelPath.front() != '/') {
        out.errors.push_back("model-path must be absolute: '" + cfg.modelPath + "'");
    }

    if (auto raw = params.get("default-bitrate")) {
        auto n = config::parseInt(*raw);
        if (!n || !isSupportedBitrate(static_cast<int>(*n))) {
            out.errors.push_back("default-bitrate must be one of 3200, 6000, 9200; got '" + *raw + "'");
        } else {
            cfg.defaultBitrate = static_cast<int>(*n);
        }
    }

    if (auto raw = params.get("dtx")) {
        auto b = config::parseBool(*raw);
        if (!b) {
            out.errors.push_back("dtx must be true or false; got '" + *raw + "'");
        } else {
            cfg.dtx = *b;
        }
    }

    if (auto raw = params.get("sample-rates")) {
        auto list = config::parseIntList(*raw);
        if (!list || list->empty()) {
            out.errors.push_back("sample-rates must be a comma-separated list of 8000, 16000, 32000, 48000; got '" + *raw + "'");
        } else {
            std::vector<int> rates;
            for (int r : *list) {
                if (!isSupportedSampleRate(r)) {
                    out.errors.push_back("sample-rates: " + std::to_string(r) + " is not supported by Lyra");
                } else if (std::find(rates.begin(), rates.end(), r) == rates.end()) {
                    rates.push_back(r);
                }
            }
            if (!rates.empty()) cfg.sampleRates = std::move(rates);
        }
    }

    if (auto raw = params.get("ptime")) {
        auto n = config::parseInt(*raw);
        if (!n || *n < CodecConfig::kFrameMsForConfig || *n > CodecConfig::kMaxPtimeMs ||
            *n % CodecConfig::kFrameMsForConfig != 0) {
            out.errors.push_back("ptime must be a multiple of 20 between 20 and 120; got '" + *raw + "'");
        } else {
            cfg.ptimeMs = static_cast<int>(*n);
        }
    }

    if (out.errors.empty()) out.value = std::move(cfg);
    return out;
}

}  // namespace coralx::lyra
