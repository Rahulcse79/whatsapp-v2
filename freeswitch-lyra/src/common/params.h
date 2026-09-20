// Small, FreeSWITCH-free helpers for turning <param name= value=/> pairs into typed
// settings. Both modules' config parsers are written against this so they can be
// unit-tested with a plain map instead of an XML tree.
#pragma once

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <map>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace coralx::config {

// Parameter names are matched case-insensitively and with '-' and '_' treated as
// the same character, so both `recording-path` (FreeSWITCH house style) and
// `recording_path` (the requested spelling) are accepted.
inline std::string normalizeKey(std::string_view key) {
    std::string out;
    out.reserve(key.size());
    for (char c : key) {
        if (c == '-') c = '_';
        out.push_back(static_cast<char>(std::tolower(static_cast<unsigned char>(c))));
    }
    return out;
}

class Params {
public:
    void set(std::string_view key, std::string value) { values_[normalizeKey(key)] = std::move(value); }

    std::optional<std::string> get(std::string_view key) const {
        auto it = values_.find(normalizeKey(key));
        if (it == values_.end()) return std::nullopt;
        return it->second;
    }

    bool has(std::string_view key) const { return values_.count(normalizeKey(key)) != 0; }

    // Every key that was set — used to warn about names the module does not know,
    // which is how a typo in a config file gets noticed.
    std::vector<std::string> keys() const {
        std::vector<std::string> out;
        out.reserve(values_.size());
        for (const auto& kv : values_) out.push_back(kv.first);
        return out;
    }

private:
    std::map<std::string, std::string> values_;
};

inline std::string trim(std::string_view s) {
    const auto notSpace = [](unsigned char c) { return !std::isspace(c); };
    auto b = std::find_if(s.begin(), s.end(), notSpace);
    auto e = std::find_if(s.rbegin(), s.rend(), notSpace).base();
    return b < e ? std::string(b, e) : std::string();
}

// FreeSWITCH's switch_true() vocabulary: yes/true/on/enabled/1 (case-insensitive).
inline std::optional<bool> parseBool(std::string_view raw) {
    std::string v = normalizeKey(trim(raw));
    if (v == "true" || v == "yes" || v == "on" || v == "enabled" || v == "1") return true;
    if (v == "false" || v == "no" || v == "off" || v == "disabled" || v == "0") return false;
    return std::nullopt;
}

inline std::optional<long long> parseInt(std::string_view raw) {
    std::string v = trim(raw);
    if (v.empty()) return std::nullopt;
    char* end = nullptr;
    errno = 0;
    const long long n = std::strtoll(v.c_str(), &end, 10);
    if (errno != 0 || end == v.c_str() || *end != '\0') return std::nullopt;
    return n;
}

// "16000, 8000" -> {16000, 8000}; any non-integer item makes the whole list invalid.
inline std::optional<std::vector<int>> parseIntList(std::string_view raw) {
    std::vector<int> out;
    std::string item;
    const std::string s(raw);
    std::size_t start = 0;
    while (start <= s.size()) {
        std::size_t comma = s.find(',', start);
        if (comma == std::string::npos) comma = s.size();
        item = trim(std::string_view(s).substr(start, comma - start));
        if (!item.empty()) {
            auto n = parseInt(item);
            if (!n || *n < 0 || *n > 1000000) return std::nullopt;
            out.push_back(static_cast<int>(*n));
        }
        start = comma + 1;
    }
    return out;
}

// "a, b ,c" -> {"a","b","c"} lower-cased; empty items dropped.
inline std::vector<std::string> parseNameList(std::string_view raw) {
    std::vector<std::string> out;
    const std::string s(raw);
    std::size_t start = 0;
    while (start <= s.size()) {
        std::size_t comma = s.find(',', start);
        if (comma == std::string::npos) comma = s.size();
        std::string item = normalizeKey(trim(std::string_view(s).substr(start, comma - start)));
        if (!item.empty()) out.push_back(std::move(item));
        start = comma + 1;
    }
    return out;
}

// What a parser hands back: either a value, or the reasons it could not be one.
// Warnings never block; errors mean "keep the previous configuration".
template <typename T>
struct Parsed {
    std::optional<T> value;
    std::vector<std::string> errors;
    std::vector<std::string> warnings;
    bool ok() const { return value.has_value() && errors.empty(); }
};

}  // namespace coralx::config
