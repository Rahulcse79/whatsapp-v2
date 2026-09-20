#include "check.h"
#include "mod_lyra/lyra_codec_config.h"

using namespace coralx::lyra;
using coralx::config::Params;

static const std::string kDefaultModels = "/opt/fs/share/freeswitch/lyra/model_coeffs";

TEST(codec_config_all_defaults) {
    Params p;
    auto r = parseCodecConfig(p, kDefaultModels);
    CHECK(r.ok());
    CHECK_EQ(r.value->modelPath, kDefaultModels);
    CHECK_EQ(r.value->defaultBitrate, 3200);
    CHECK_EQ(r.value->dtx, false);
    CHECK_EQ(r.value->ptimeMs, 20);
    CHECK_EQ(r.value->sampleRates.size(), std::size_t(1));
    CHECK_EQ(r.value->sampleRates[0], 16000);
}

TEST(codec_config_valid_overrides) {
    Params p;
    p.set("model-path", "/models/lyra");
    p.set("default-bitrate", "9200");
    p.set("dtx", "true");
    p.set("sample-rates", "16000,48000");
    p.set("ptime", "40");
    auto r = parseCodecConfig(p, kDefaultModels);
    CHECK(r.ok());
    CHECK_EQ(r.value->modelPath, std::string("/models/lyra"));
    CHECK_EQ(r.value->defaultBitrate, 9200);
    CHECK_EQ(r.value->dtx, true);
    CHECK_EQ(r.value->ptimeMs, 40);
    CHECK_EQ(r.value->framesPerPacket(), 2);
    CHECK_EQ(r.value->sampleRates.size(), std::size_t(2));
}

TEST(codec_config_rejects_bad_bitrate) {
    Params p;
    p.set("default-bitrate", "5000");
    auto r = parseCodecConfig(p, kDefaultModels);
    CHECK(!r.ok());
    CHECK(!r.errors.empty());
}

TEST(codec_config_rejects_unsupported_sample_rate) {
    Params p;
    p.set("sample-rates", "44100");
    auto r = parseCodecConfig(p, kDefaultModels);
    CHECK(!r.ok());
}

TEST(codec_config_rejects_bad_ptime) {
    Params p;
    p.set("ptime", "25");  // not a multiple of 20
    CHECK(!parseCodecConfig(p, kDefaultModels).ok());
    Params p2;
    p2.set("ptime", "200");  // over the cap
    CHECK(!parseCodecConfig(p2, kDefaultModels).ok());
}

TEST(codec_config_rejects_relative_model_path) {
    Params p;
    p.set("model-path", "relative/models");
    CHECK(!parseCodecConfig(p, kDefaultModels).ok());
}

TEST(codec_config_empty_default_is_error) {
    Params p;
    auto r = parseCodecConfig(p, "");
    CHECK(!r.ok());
}

TEST(codec_config_warns_on_unknown_key) {
    Params p;
    p.set("modle-path", "/x");  // typo
    auto r = parseCodecConfig(p, kDefaultModels);
    CHECK(r.ok());  // a typo does not block; the default path is used
    CHECK(!r.warnings.empty());
}

RUN_ALL_TESTS_MAIN()
