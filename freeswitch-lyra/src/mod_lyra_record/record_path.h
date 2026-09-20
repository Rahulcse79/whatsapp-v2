// Turns a call's (untrusted) caller, callee and UUID plus a wall-clock time into a
// safe absolute path under the configured recording root. Pure and FreeSWITCH-free
// so the traversal defences are unit-tested exhaustively (tests/unit/test_record_path.cpp).
//
// The threat: caller/callee arrive from SIP and the UUID, though FreeSWITCH-issued,
// is still treated as untrusted. A value like "../../etc/cron.d/x" or "a/b" or an
// absolute path must never make the file land outside the root. Two independent
// defences, both applied:
//   1. Every token value is sanitised to [A-Za-z0-9._-], with '.' runs collapsed so
//      no ".." can survive, length-capped, and emptied values replaced by a marker.
//   2. After the whole path is built it is lexically normalised and asserted to be a
//      proper descendant of the root; anything else is a hard error (no file).
#pragma once

#include <ctime>
#include <optional>
#include <string>

namespace coralx::record {

// The inputs a filename pattern can reference. Times are pre-split so the builder
// needs no clock and the tests are deterministic.
struct PathInputs {
    std::string caller;
    std::string callee;
    std::string uuid;
    std::string domain;
    std::string date;  // YYYYMMDD
    std::string time;  // HHMMSS
    std::string epoch; // seconds since epoch, as text
};

// Build PathInputs from a UTC/local `tm` and the raw call values (values are not yet
// sanitised — the builder does that).
PathInputs makeInputs(const std::tm& when, std::time_t epoch, std::string caller,
                      std::string callee, std::string uuid, std::string domain);

// Reduce one untrusted value to a filesystem-safe atom: keep [A-Za-z0-9._-], turn
// everything else into '_', collapse any run of '.' to a single '.', strip leading
// dots, cap length. An empty result becomes `fallback`. Never returns "." or "..".
std::string sanitizeAtom(std::string_view value, std::string_view fallback = "unknown");

// Expand {caller} {callee} {uuid} {domain} {date} {time} {epoch} in `pattern`, each
// value sanitised. An unknown {token} is left verbatim but then sanitised as text,
// so a pattern typo can never introduce a slash. A literal '/' in a filename pattern
// is turned into '_'; in a directory pattern it is kept as a separator (each segment
// sanitised) so `{date}` or `{date}/{caller}` can nest.
std::string expandFilename(std::string_view pattern, const PathInputs& in);
std::string expandDirectory(std::string_view pattern, const PathInputs& in);

// The final answer: root + directory + filename + "." + ext, normalised and proven
// to be inside root. nullopt (with *why set) when it would escape or is malformed.
std::optional<std::string> buildRecordingPath(const std::string& root,
                                              const std::string& directoryPattern,
                                              const std::string& filenamePattern,
                                              const std::string& ext,
                                              const PathInputs& in,
                                              std::string* why);

// Lexically normalise an absolute path (resolve '.', '..', '//') without touching the
// filesystem. Exposed for the tests.
std::string lexicalNormalize(const std::string& path);

// True when `child` is `root` itself or a path strictly beneath it, both assumed
// already normalised and absolute.
bool isInside(const std::string& root, const std::string& child);

}  // namespace coralx::record
