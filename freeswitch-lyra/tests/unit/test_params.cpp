#include "check.h"
#include "common/params.h"

using namespace coralx::config;

TEST(normalizeKey_lowercases_and_unifies_separators) {
    CHECK_EQ(normalizeKey("Recording-Path"), std::string("recording_path"));
    CHECK_EQ(normalizeKey("DEFAULT_BITRATE"), std::string("default_bitrate"));
    CHECK_EQ(normalizeKey("dtx"), std::string("dtx"));
}

TEST(parseBool_accepts_freeswitch_vocabulary) {
    CHECK_EQ(parseBool("true").value_or(false), true);
    CHECK_EQ(parseBool("YES").value_or(false), true);
    CHECK_EQ(parseBool("On").value_or(false), true);
    CHECK_EQ(parseBool("enabled").value_or(false), true);
    CHECK_EQ(parseBool("1").value_or(false), true);
    CHECK_EQ(parseBool("false").value_or(true), false);
    CHECK_EQ(parseBool("off").value_or(true), false);
    CHECK_EQ(parseBool("0").value_or(true), false);
    CHECK(!parseBool("maybe").has_value());
    CHECK(!parseBool("").has_value());
}

TEST(parseInt_rejects_trailing_garbage) {
    CHECK_EQ(parseInt("3200").value_or(-1), 3200LL);
    CHECK_EQ(parseInt("  16000 ").value_or(-1), 16000LL);
    CHECK(!parseInt("3200x").has_value());
    CHECK(!parseInt("").has_value());
    CHECK(!parseInt("abc").has_value());
}

TEST(parseIntList_splits_and_validates) {
    auto l = parseIntList("16000, 8000 ,48000");
    CHECK(l.has_value());
    CHECK_EQ(l->size(), std::size_t(3));
    CHECK_EQ((*l)[0], 16000);
    CHECK_EQ((*l)[2], 48000);
    CHECK(!parseIntList("16000, x").has_value());
    CHECK(parseIntList("")->empty());
}

TEST(parseNameList_normalizes_and_drops_empties) {
    auto l = parseNameList("Lyra, PCMU ,,opus");
    CHECK_EQ(l.size(), std::size_t(3));
    CHECK_EQ(l[0], std::string("lyra"));
    CHECK_EQ(l[1], std::string("pcmu"));
    CHECK_EQ(l[2], std::string("opus"));
}

TEST(params_get_is_case_and_separator_insensitive) {
    Params p;
    p.set("Recording-Path", "/x");
    CHECK_EQ(p.get("recording_path").value_or(""), std::string("/x"));
    CHECK_EQ(p.get("RECORDING-PATH").value_or(""), std::string("/x"));
    CHECK(!p.get("nope").has_value());
    CHECK_EQ(p.keys().size(), std::size_t(1));
}

RUN_ALL_TESTS_MAIN()
