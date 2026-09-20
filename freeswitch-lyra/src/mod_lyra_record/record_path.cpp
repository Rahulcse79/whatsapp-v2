#include "record_path.h"

#include <algorithm>
#include <array>
#include <cctype>
#include <cstdio>
#include <vector>

namespace coralx::record {

namespace {
constexpr std::size_t kMaxAtomLen = 64;

bool safeChar(char c) {
    return std::isalnum(static_cast<unsigned char>(c)) || c == '.' || c == '_' || c == '-';
}
}  // namespace

std::string sanitizeAtom(std::string_view value, std::string_view fallback) {
    std::string out;
    out.reserve(value.size());
    bool lastDot = false;
    for (char c : value) {
        char m = safeChar(c) ? c : '_';
        if (m == '.') {
            if (lastDot) continue;  // collapse "…" so ".." can never survive
            lastDot = true;
        } else {
            lastDot = false;
        }
        out.push_back(m);
        if (out.size() >= kMaxAtomLen) break;
    }
    // Strip leading dots/dashes/underscores so the atom never starts a hidden file
    // or looks like an option, and trim a trailing dot.
    std::size_t b = 0;
    while (b < out.size() && (out[b] == '.' || out[b] == '-')) ++b;
    out.erase(0, b);
    while (!out.empty() && out.back() == '.') out.pop_back();
    if (out.empty() || out == "." || out == "..") return std::string(fallback);
    return out;
}

PathInputs makeInputs(const std::tm& when, std::time_t epoch, std::string caller,
                      std::string callee, std::string uuid, std::string domain) {
    PathInputs in;
    in.caller = std::move(caller);
    in.callee = std::move(callee);
    in.uuid = std::move(uuid);
    in.domain = std::move(domain);
    char date[16], time[16], ep[32];
    std::snprintf(date, sizeof(date), "%04d%02d%02d", when.tm_year + 1900, when.tm_mon + 1, when.tm_mday);
    std::snprintf(time, sizeof(time), "%02d%02d%02d", when.tm_hour, when.tm_min, when.tm_sec);
    std::snprintf(ep, sizeof(ep), "%lld", static_cast<long long>(epoch));
    in.date = date;
    in.time = time;
    in.epoch = ep;
    return in;
}

namespace {
// Expand tokens, calling `emit` for literal runs and `token` for each {name}.
template <typename EmitLiteral, typename EmitToken>
void walkPattern(std::string_view pattern, EmitLiteral emit, EmitToken token) {
    std::size_t i = 0;
    while (i < pattern.size()) {
        if (pattern[i] == '{') {
            std::size_t end = pattern.find('}', i);
            if (end != std::string_view::npos) {
                token(pattern.substr(i + 1, end - i - 1));
                i = end + 1;
                continue;
            }
        }
        emit(pattern[i]);
        ++i;
    }
}

std::string tokenValue(std::string_view name, const PathInputs& in, bool* known) {
    *known = true;
    if (name == "caller") return in.caller;
    if (name == "callee") return in.callee;
    if (name == "uuid") return in.uuid;
    if (name == "domain") return in.domain;
    if (name == "date") return in.date;
    if (name == "time") return in.time;
    if (name == "epoch") return in.epoch;
    *known = false;
    return std::string();
}
}  // namespace

std::string expandFilename(std::string_view pattern, const PathInputs& in) {
    // Build the raw text (tokens replaced by their values), then sanitise the whole
    // thing as one atom so no '/' or '..' from any source reaches the filename.
    std::string raw;
    walkPattern(pattern,
                [&](char c) { raw.push_back(c); },
                [&](std::string_view name) {
                    bool known = false;
                    std::string v = tokenValue(name, in, &known);
                    raw += known ? v : ("{" + std::string(name) + "}");
                });
    return sanitizeAtom(raw, "recording");
}

std::string expandDirectory(std::string_view pattern, const PathInputs& in) {
    // Directories may nest, so '/' is a separator: expand, then sanitise each segment
    // independently. Empty segments (leading/trailing/double slash) are dropped.
    std::string raw;
    walkPattern(pattern,
                [&](char c) { raw.push_back(c); },
                [&](std::string_view name) {
                    bool known = false;
                    std::string v = tokenValue(name, in, &known);
                    raw += known ? v : ("{" + std::string(name) + "}");
                });
    std::string out;
    std::size_t start = 0;
    while (start <= raw.size()) {
        std::size_t slash = raw.find('/', start);
        if (slash == std::string::npos) slash = raw.size();
        std::string seg = raw.substr(start, slash - start);
        if (!seg.empty()) {
            std::string clean = sanitizeAtom(seg, "");
            if (!clean.empty()) {
                if (!out.empty()) out.push_back('/');
                out += clean;
            }
        }
        start = slash + 1;
    }
    return out;
}

std::string lexicalNormalize(const std::string& path) {
    const bool absolute = !path.empty() && path.front() == '/';
    std::vector<std::string> parts;
    std::size_t start = 0;
    while (start <= path.size()) {
        std::size_t slash = path.find('/', start);
        if (slash == std::string::npos) slash = path.size();
        std::string seg = path.substr(start, slash - start);
        if (seg.empty() || seg == ".") {
            // skip
        } else if (seg == "..") {
            if (!parts.empty() && parts.back() != "..") {
                parts.pop_back();
            } else if (!absolute) {
                parts.push_back("..");
            }
            // an absolute path can never rise above "/"
        } else {
            parts.push_back(seg);
        }
        start = slash + 1;
    }
    std::string out = absolute ? "/" : "";
    for (std::size_t i = 0; i < parts.size(); ++i) {
        out += parts[i];
        if (i + 1 < parts.size()) out.push_back('/');
    }
    if (out.empty()) out = ".";
    return out;
}

bool isInside(const std::string& root, const std::string& child) {
    std::string r = root;
    while (r.size() > 1 && r.back() == '/') r.pop_back();
    if (child == r) return true;
    return child.size() > r.size() && child.compare(0, r.size(), r) == 0 && child[r.size()] == '/';
}

std::optional<std::string> buildRecordingPath(const std::string& root,
                                              const std::string& directoryPattern,
                                              const std::string& filenamePattern,
                                              const std::string& ext,
                                              const PathInputs& in,
                                              std::string* why) {
    const auto fail = [&](const std::string& msg) -> std::optional<std::string> {
        if (why) *why = msg;
        return std::nullopt;
    };
    if (root.empty() || root.front() != '/') return fail("recording root is not an absolute path");

    const std::string dir = expandDirectory(directoryPattern, in);
    const std::string file = expandFilename(filenamePattern, in);
    const std::string safeExt = sanitizeAtom(ext, "wav");
    if (file.empty()) return fail("filename pattern produced nothing usable");

    std::string full = root;
    while (full.size() > 1 && full.back() == '/') full.pop_back();
    if (!dir.empty()) full += "/" + dir;
    full += "/" + file + "." + safeExt;

    const std::string normRoot = lexicalNormalize(root);
    const std::string normFull = lexicalNormalize(full);
    if (!isInside(normRoot, normFull)) {
        return fail("computed path '" + normFull + "' escapes the recording root '" + normRoot + "'");
    }
    return normFull;
}

}  // namespace coralx::record
