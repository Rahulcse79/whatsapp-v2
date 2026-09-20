// Settings for mod_lyra, read from autoload_configs/lyra.conf.xml.
//
//   <configuration name="lyra.conf" description="Lyra codec">
//     <settings>
//       <param name="model-path"      value="/usr/local/freeswitch/share/freeswitch/lyra/model_coeffs"/>
//       <param name="default-bitrate" value="3200"/>      3200 | 6000 | 9200
//       <param name="dtx"             value="false"/>
//       <param name="sample-rates"    value="16000"/>     any of 8000,16000,32000,48000
//       <param name="ptime"           value="20"/>        multiple of 20, <= 120
//     </settings>
//   </configuration>
//
// Parsing is pure (no FreeSWITCH types) so it is unit-tested directly.
#pragma once

#include <string>
#include <vector>

#include "common/params.h"

namespace coralx::lyra {

struct CodecConfig {
    std::string modelPath;
    int defaultBitrate = 3200;
    bool dtx = false;
    std::vector<int> sampleRates{16000};
    int ptimeMs = 20;

    // Frames packed into one RTP packet at ptimeMs.
    int framesPerPacket() const { return ptimeMs / kFrameMsForConfig; }
    static constexpr int kFrameMsForConfig = 20;
    static constexpr int kMaxPtimeMs = 120;
};

// `defaultModelPath` is what FreeSWITCH's install prefix implies when the file
// leaves model-path unset. Errors make the result unusable; warnings do not.
config::Parsed<CodecConfig> parseCodecConfig(const config::Params& params,
                                             const std::string& defaultModelPath);

}  // namespace coralx::lyra
