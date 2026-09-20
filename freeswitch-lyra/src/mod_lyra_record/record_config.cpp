#include "record_config.h"

#include <set>

namespace coralx::record {

config::Parsed<RecordConfig> parseRecordConfig(const config::Params& params) {
    config::Parsed<RecordConfig> out;
    RecordConfig cfg;

    static const std::set<std::string> known{
        "enabled", "recording_path", "format", "track", "sample_rate",
        "directory_pattern", "filename_pattern", "min_free_mb", "max_seconds"};
    for (const auto& k : params.keys()) {
        if (!known.count(k)) out.warnings.push_back("unknown parameter '" + k + "' ignored");
    }

    if (auto raw = params.get("enabled")) {
        auto b = config::parseBool(*raw);
        if (!b) {
            out.errors.push_back("enabled must be true or false; got '" + *raw + "'");
        } else {
            cfg.enabled = *b;
        }
    }

    // recording_path (accepts the requested lyra_recording_path spelling too, via key
    // normalisation dropping the common prefix is not automatic — support both names).
    std::string root = config::trim(params.get("recording-path").value_or(
        params.get("lyra-recording-path").value_or("")));
    cfg.recordingRoot = root;
    if (cfg.enabled) {
        if (root.empty()) {
            out.errors.push_back("recording-path must be set when enabled=true");
        } else if (root.front() != '/') {
            out.errors.push_back("recording-path must be absolute: '" + root + "'");
        }
    }

    if (auto raw = params.get("format")) {
        std::string f = config::normalizeKey(config::trim(*raw));
        if (f.empty() || f.find('/') != std::string::npos || f.find('.') != std::string::npos) {
            out.errors.push_back("format must be a bare extension like wav or mp3; got '" + *raw + "'");
        } else {
            cfg.format = f;
        }
    }

    if (auto raw = params.get("track")) {
        std::string t = config::normalizeKey(config::trim(*raw));
        if (t == "stereo") {
            cfg.track = Track::Stereo;
        } else if (t == "mixed" || t == "mono") {
            cfg.track = Track::Mixed;
        } else {
            out.errors.push_back("track must be stereo or mixed; got '" + *raw + "'");
        }
    }

    if (auto raw = params.get("sample-rate")) {
        auto n = config::parseInt(*raw);
        // 0 means "follow the call". mod_sndfile handles 8k/16k/32k/48k for WAV.
        static const std::set<long long> ok{0, 8000, 16000, 32000, 48000};
        if (!n || !ok.count(*n)) {
            out.errors.push_back("sample-rate must be 0 (follow the call) or 8000/16000/32000/48000; got '" + *raw + "'");
        } else {
            cfg.sampleRate = static_cast<int>(*n);
        }
    }

    if (auto raw = params.get("directory-pattern")) {
        cfg.directoryPattern = config::trim(*raw);  // validated for tokens/traversal in record_path
    }
    if (auto raw = params.get("filename-pattern")) {
        std::string p = config::trim(*raw);
        if (p.empty()) {
            out.errors.push_back("filename-pattern must not be empty");
        } else {
            cfg.filenamePattern = p;
        }
    }

    if (auto raw = params.get("min-free-mb")) {
        auto n = config::parseInt(*raw);
        if (!n || *n < 0) {
            out.errors.push_back("min-free-mb must be a non-negative integer; got '" + *raw + "'");
        } else {
            cfg.minFreeMb = *n;
        }
    }

    if (auto raw = params.get("max-seconds")) {
        auto n = config::parseInt(*raw);
        if (!n || *n < 0 || *n > 86400) {
            out.errors.push_back("max-seconds must be between 0 and 86400; got '" + *raw + "'");
        } else {
            cfg.maxSeconds = static_cast<int>(*n);
        }
    }

    if (out.errors.empty()) out.value = std::move(cfg);
    return out;
}

}  // namespace coralx::record
