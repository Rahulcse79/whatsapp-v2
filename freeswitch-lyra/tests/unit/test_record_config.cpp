#include "check.h"
#include "mod_lyra_record/record_config.h"

using namespace coralx::record;
using coralx::config::Params;

TEST(record_config_default_is_disabled) {
    Params p;
    auto r = parseRecordConfig(p);
    CHECK(r.ok());
    CHECK_EQ(r.value->enabled, false);
    // Disabled + no path is fine: nothing will be recorded.
    CHECK_EQ(r.value->format, std::string("wav"));
    CHECK_EQ(r.value->track, Track::Stereo);
    CHECK_EQ(r.value->channels(), 2);
    CHECK_EQ(r.value->sampleRate, 16000);
    CHECK_EQ(r.value->minFreeMb, 100LL);
}

TEST(record_config_enabled_requires_absolute_path) {
    Params p;
    p.set("enabled", "true");
    CHECK(!parseRecordConfig(p).ok());  // no path
    Params p2;
    p2.set("enabled", "true");
    p2.set("recording-path", "relative");
    CHECK(!parseRecordConfig(p2).ok());  // not absolute
    Params p3;
    p3.set("enabled", "true");
    p3.set("recording-path", "/var/rec/lyra");
    CHECK(parseRecordConfig(p3).ok());
}

TEST(record_config_valid_full) {
    Params p;
    p.set("enabled", "true");
    p.set("recording-path", "/var/rec/lyra");
    p.set("format", "wav");
    p.set("track", "mixed");
    p.set("sample-rate", "8000");
    p.set("directory-pattern", "{date}/{callee}");
    p.set("filename-pattern", "{time}_{uuid}");
    p.set("min-free-mb", "250");
    p.set("max-seconds", "3600");
    auto r = parseRecordConfig(p);
    CHECK(r.ok());
    CHECK_EQ(r.value->track, Track::Mixed);
    CHECK_EQ(r.value->channels(), 1);
    CHECK_EQ(r.value->sampleRate, 8000);
    CHECK_EQ(r.value->minFreeMb, 250LL);
    CHECK_EQ(r.value->maxSeconds, 3600);
}

TEST(record_config_accepts_alternate_path_spelling) {
    Params p;
    p.set("enabled", "true");
    p.set("lyra-recording-path", "/var/rec/lyra");  // the requested spelling
    CHECK(parseRecordConfig(p).ok());
}

TEST(record_config_sample_rate_zero_follows_call) {
    Params p;
    p.set("sample-rate", "0");
    auto r = parseRecordConfig(p);
    CHECK(r.ok());
    CHECK_EQ(r.value->sampleRate, 0);
}

TEST(record_config_rejects_bad_values) {
    {
        Params p; p.set("track", "quad");
        CHECK(!parseRecordConfig(p).ok());
    }
    {
        Params p; p.set("sample-rate", "12345");
        CHECK(!parseRecordConfig(p).ok());
    }
    {
        Params p; p.set("format", "wav.evil/../x");
        CHECK(!parseRecordConfig(p).ok());
    }
    {
        Params p; p.set("min-free-mb", "-5");
        CHECK(!parseRecordConfig(p).ok());
    }
    {
        Params p; p.set("max-seconds", "999999");
        CHECK(!parseRecordConfig(p).ok());
    }
    {
        Params p; p.set("enabled", "sometimes");
        CHECK(!parseRecordConfig(p).ok());
    }
}

RUN_ALL_TESTS_MAIN()
