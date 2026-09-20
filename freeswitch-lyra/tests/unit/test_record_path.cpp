#include "check.h"
#include "mod_lyra_record/record_path.h"

using namespace coralx::record;

static PathInputs sample() {
    std::tm tm{};
    tm.tm_year = 126;  // 2026
    tm.tm_mon = 8;     // September
    tm.tm_mday = 19;
    tm.tm_hour = 19;
    tm.tm_min = 25;
    tm.tm_sec = 30;
    return makeInputs(tm, 1789000000, "1000", "1001", "550e8400-e29b-41d4-a716-446655440000", "coral.example");
}

TEST(sanitizeAtom_keeps_safe_chars) {
    CHECK_EQ(sanitizeAtom("1000"), std::string("1000"));
    CHECK_EQ(sanitizeAtom("a.b-c_d"), std::string("a.b-c_d"));
}

TEST(sanitizeAtom_neutralizes_traversal) {
    // ".." must never survive — dot runs collapse and leading dots are stripped.
    CHECK(sanitizeAtom("..") == "unknown");
    CHECK(sanitizeAtom("../../etc/passwd") != std::string("../../etc/passwd"));
    CHECK(sanitizeAtom("../../etc/passwd").find('/') == std::string::npos);
    CHECK(sanitizeAtom("../../etc/passwd").find("..") == std::string::npos);
    CHECK(sanitizeAtom(".") == "unknown");
    CHECK(sanitizeAtom("") == "unknown");
    CHECK(sanitizeAtom("", "x") == "x");
}

TEST(sanitizeAtom_replaces_separators_and_specials) {
    CHECK(sanitizeAtom("a/b").find('/') == std::string::npos);
    CHECK(sanitizeAtom("a\\b").find('\\') == std::string::npos);
    CHECK(sanitizeAtom("a b").find(' ') == std::string::npos);
    CHECK(sanitizeAtom("a;b|c").find(';') == std::string::npos);
    // A SIP user like "1000@host" keeps only the safe atom characters.
    CHECK_EQ(sanitizeAtom("1000@host"), std::string("1000_host"));
}

TEST(sanitizeAtom_caps_length) {
    std::string big(500, 'a');
    CHECK(sanitizeAtom(big).size() <= 64);
}

TEST(expandFilename_expands_known_tokens) {
    auto in = sample();
    CHECK_EQ(expandFilename("{time}_{caller}_{callee}_{uuid}", in),
             std::string("192530_1000_1001_550e8400-e29b-41d4-a716-446655440000"));
    CHECK_EQ(expandFilename("{date}", in), std::string("20260919"));
    CHECK_EQ(expandFilename("{epoch}", in), std::string("1789000000"));
}

TEST(expandFilename_is_flat_no_slashes) {
    auto in = sample();
    in.caller = "../evil";
    // Even a malicious caller cannot introduce a path separator into the filename.
    CHECK(expandFilename("{caller}", in).find('/') == std::string::npos);
    CHECK(expandFilename("{caller}", in).find("..") == std::string::npos);
}

TEST(expandDirectory_allows_nesting_but_sanitizes_each_segment) {
    auto in = sample();
    CHECK_EQ(expandDirectory("{date}/{callee}", in), std::string("20260919/1001"));
    in.callee = "../../root";
    // Slashes from a token VALUE are neutralised; only literal pattern slashes nest.
    std::string d = expandDirectory("{date}/{callee}", in);
    CHECK(d.rfind("20260919/", 0) == 0);
    CHECK(d.find("..") == std::string::npos);
}

TEST(lexicalNormalize_resolves_dots) {
    CHECK_EQ(lexicalNormalize("/a/b/../c"), std::string("/a/c"));
    CHECK_EQ(lexicalNormalize("/a/./b//c"), std::string("/a/b/c"));
    CHECK_EQ(lexicalNormalize("/a/../.."), std::string("/"));  // cannot rise above root
    CHECK_EQ(lexicalNormalize("/a/b/"), std::string("/a/b"));
}

TEST(isInside_is_true_only_for_descendants) {
    CHECK(isInside("/rec", "/rec"));
    CHECK(isInside("/rec", "/rec/a/b.wav"));
    CHECK(isInside("/rec/", "/rec/a"));
    CHECK(!isInside("/rec", "/rec2/a"));
    CHECK(!isInside("/rec", "/other"));
    CHECK(!isInside("/rec", "/"));
}

TEST(buildRecordingPath_happy_path) {
    std::string why;
    auto p = buildRecordingPath("/var/rec/lyra", "{date}", "{time}_{caller}_{callee}_{uuid}", "wav", sample(), &why);
    CHECK(p.has_value());
    CHECK_EQ(*p, std::string("/var/rec/lyra/20260919/192530_1000_1001_550e8400-e29b-41d4-a716-446655440000.wav"));
}

TEST(buildRecordingPath_confines_malicious_values_to_root) {
    auto in = sample();
    in.caller = "../../../../etc/cron.d/x";
    in.callee = "..";
    in.uuid = "a/b/c";
    std::string why;
    auto p = buildRecordingPath("/var/rec/lyra", "{date}", "{caller}_{callee}_{uuid}", "wav", in, &why);
    CHECK(p.has_value());
    CHECK(isInside("/var/rec/lyra", *p));
    CHECK(p->find("..") == std::string::npos);
    CHECK(p->rfind("/var/rec/lyra/", 0) == 0);
}

TEST(buildRecordingPath_rejects_relative_root) {
    std::string why;
    auto p = buildRecordingPath("rec", "", "{uuid}", "wav", sample(), &why);
    CHECK(!p.has_value());
    CHECK(!why.empty());
}

TEST(buildRecordingPath_absolute_override_cannot_escape) {
    // A directory pattern that tries to climb out is normalised back inside.
    std::string why;
    auto p = buildRecordingPath("/var/rec/lyra", "../../../../tmp", "{uuid}", "wav", sample(), &why);
    // Each segment is sanitized (".." -> dropped), so the directory collapses to nothing
    // and the file lands directly in the root.
    CHECK(p.has_value());
    CHECK(isInside("/var/rec/lyra", *p));
}

RUN_ALL_TESTS_MAIN()
