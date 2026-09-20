// Settings for mod_lyra_record, read from autoload_configs/lyra_recording.conf.xml.
//
// All parsing is pure (no FreeSWITCH types) so it is unit-tested with a plain map.
// The path/filename logic that turns these settings + a call's data into a file on
// disk lives in record_path.h and is tested the same way — this file is only the
// settings themselves.
#pragma once

#include <string>
#include <vector>

#include "common/params.h"

namespace coralx::record {

// What is written toward each direction of the call. STEREO keeps them separate
// (caller left, callee right) — the most useful default for a two-party call and
// the one thing a post-hoc analysis cannot recover from a mono mix.
enum class Track { Mixed, Stereo };

struct RecordConfig {
    bool enabled = false;
    // Absolute directory that every recording must stay inside. No default is
    // invented: when enabled and unset, the module refuses to arm (an empty root
    // would drop files in the CWD).
    std::string recordingRoot;
    std::string format = "wav";           // any extension mod_sndfile handles
    Track track = Track::Stereo;
    int sampleRate = 16000;               // 0 = follow the call's rate
    // Subdirectory under the root, tokens expanded (see record_path.h). Empty = none.
    std::string directoryPattern = "{date}";
    std::string filenamePattern = "{time}_{caller}_{callee}_{uuid}";
    // Stop recording (and busy no call) if free space under the root drops below this.
    long long minFreeMb = 100;
    // Cap on a single recording; 0 = no limit. Guards a stuck call from filling a disk.
    int maxSeconds = 0;

    int channels() const { return track == Track::Stereo ? 2 : 1; }
};

config::Parsed<RecordConfig> parseRecordConfig(const config::Params& params);

}  // namespace coralx::record
