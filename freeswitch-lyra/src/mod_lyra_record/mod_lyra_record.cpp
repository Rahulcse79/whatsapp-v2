// mod_lyra_record — server-side recording policy for Lyra-to-Lyra calls.
//
// This module contains NO audio code. Decoding Lyra to PCM and writing a WAV is done
// by FreeSWITCH's own, battle-tested machinery: mod_lyra provides the codec, and
// switch_ivr_record_session_event() (a core media bug + mod_sndfile) does the mixing,
// buffering, WAV finalisation, directory creation and hangup-safe teardown. What this
// module owns is the *policy*: read the config, decide whether a given call should be
// recorded, compute a safe path, check the disk, start the recorder, and account for
// what happened. See docs/02-hld.md §2.4.
//
// It exposes:
//   * dialplan app  `lyra_record`            arm recording on the current call
//   * dialplan app  `lyra_record_stop`       stop it
//   * api           `lyra_recording status [json] | reload | enabled`
//
// When enabled=false, `lyra_record` returns immediately having touched nothing — the
// dialplan is written so it also skips keeping FreeSWITCH in the media path, so a
// disabled deployment is byte-for-byte the call it is today. See docs/04-configuration.md.

#include <switch.h>

#include <sys/statvfs.h>

#include <atomic>
#include <cstring>
#include <ctime>
#include <memory>
#include <mutex>
#include <string>

#include "common/params.h"
#include "record_config.h"
#include "record_path.h"

SWITCH_BEGIN_EXTERN_C
SWITCH_MODULE_LOAD_FUNCTION(mod_lyra_record_load);
SWITCH_MODULE_SHUTDOWN_FUNCTION(mod_lyra_record_shutdown);
SWITCH_MODULE_DEFINITION(mod_lyra_record, mod_lyra_record_load, mod_lyra_record_shutdown, NULL);
SWITCH_END_EXTERN_C

namespace {

using coralx::record::RecordConfig;
using coralx::record::Track;

constexpr const char* kConfigFile = "lyra_recording.conf";
constexpr const char* kCodecName = "lyra";
// The key under which the started bug is stored on the channel, so stop/hangup find it.
constexpr const char* kBugName = "lyra_record";

struct Metrics {
    std::atomic<std::uint64_t> active{0};        // currently running
    std::atomic<std::uint64_t> started{0};
    std::atomic<std::uint64_t> completed{0};
    std::atomic<std::uint64_t> skippedDisabled{0};
    std::atomic<std::uint64_t> skippedNotLyra{0};
    std::atomic<std::uint64_t> failedPath{0};
    std::atomic<std::uint64_t> failedDisk{0};
    std::atomic<std::uint64_t> failedStart{0};
    std::atomic<std::uint64_t> reloads{0};
    std::atomic<std::uint64_t> reloadFailures{0};
};

struct ModuleState {
    std::mutex mutex;
    std::shared_ptr<const RecordConfig> config;
    Metrics metrics;
};

ModuleState& state() {
    static ModuleState s;
    return s;
}

std::shared_ptr<const RecordConfig> currentConfig() {
    std::lock_guard<std::mutex> lock(state().mutex);
    return state().config;
}

void bump(std::atomic<std::uint64_t>& c, std::uint64_t n = 1) { c.fetch_add(n, std::memory_order_relaxed); }

// Free megabytes on the filesystem holding `path` (an existing ancestor is fine).
// -1 when it cannot be determined — treated as "do not block", with a warning.
long long freeMegabytes(const std::string& path) {
    struct statvfs vfs;
    std::string probe = path;
    // Walk up to the nearest existing directory so statvfs has something to stat even
    // before the date subdirectory is created.
    while (!probe.empty() && statvfs(probe.c_str(), &vfs) != 0) {
        auto slash = probe.find_last_of('/');
        if (slash == std::string::npos || slash == 0) return -1;
        probe.erase(slash);
    }
    if (probe.empty()) return -1;
    const unsigned long long bytes = static_cast<unsigned long long>(vfs.f_bavail) * vfs.f_frsize;
    return static_cast<long long>(bytes / (1024ULL * 1024ULL));
}

// True when this channel's negotiated read codec is Lyra. The whole feature is
// "Lyra-to-Lyra", so a call that ended up on PCMU (e.g. one leg fell back) is not
// recorded by this module — that is the requested scope, not a limitation.
bool readCodecIsLyra(switch_core_session_t* session) {
    switch_codec_t* codec = switch_core_session_get_read_codec(session);
    if (codec && codec->implementation && !zstr(codec->implementation->iananame)) {
        return strcasecmp(codec->implementation->iananame, kCodecName) == 0;
    }
    // Media may not be up yet at execute_on_answer time on some paths; fall back to
    // the negotiated-codec channel variable mod_sofia sets.
    const char* name = switch_channel_get_variable(switch_core_session_get_channel(session), "rtp_use_codec_name");
    return !zstr(name) && strcasecmp(name, kCodecName) == 0;
}

// Arm recording on `session`. Returns true when a recorder was started. Never hangs
// up the call and never throws — every failure logs and returns false.
bool startRecording(switch_core_session_t* session, const char* overridePath) {
    auto cfg = currentConfig();
    switch_channel_t* channel = switch_core_session_get_channel(session);

    if (!cfg || !cfg->enabled) {
        bump(state().metrics.skippedDisabled);
        switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_DEBUG, "Lyra recording disabled; not recording\n");
        return false;
    }
    if (!readCodecIsLyra(session)) {
        bump(state().metrics.skippedNotLyra);
        switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_INFO,
                          "Lyra recording: call is not on the Lyra codec; not recording\n");
        return false;
    }
    if (switch_channel_get_private(channel, kBugName)) {
        switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_DEBUG, "Lyra recording already active on this channel\n");
        return true;
    }

    // Compute the path (unless the dialplan passed an explicit one, still forced
    // inside the root). Values from SIP are untrusted; record_path sanitises them.
    std::string path;
    if (!zstr(overridePath)) {
        // An operator-supplied filename: still confined to the root, still sanitised.
        std::time_t now = switch_epoch_time_now(nullptr);
        std::tm tm{};
        localtime_r(&now, &tm);
        auto in = coralx::record::makeInputs(tm, now, "", "", switch_core_session_get_uuid(session), "");
        std::string why;
        auto built = coralx::record::buildRecordingPath(cfg->recordingRoot, "", overridePath, cfg->format, in, &why);
        if (!built) {
            bump(state().metrics.failedPath);
            switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_ERROR, "Lyra recording path rejected: %s\n", why.c_str());
            return false;
        }
        path = *built;
    } else {
        const char* caller = switch_channel_get_variable(channel, "caller_id_number");
        const char* callee = switch_channel_get_variable(channel, "destination_number");
        const char* domain = switch_channel_get_variable(channel, "domain_name");
        std::time_t now = switch_epoch_time_now(nullptr);
        std::tm tm{};
        localtime_r(&now, &tm);
        auto in = coralx::record::makeInputs(tm, now, caller ? caller : "", callee ? callee : "",
                                             switch_core_session_get_uuid(session), domain ? domain : "");
        std::string why;
        auto built = coralx::record::buildRecordingPath(cfg->recordingRoot, cfg->directoryPattern,
                                                        cfg->filenamePattern, cfg->format, in, &why);
        if (!built) {
            bump(state().metrics.failedPath);
            switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_ERROR, "Lyra recording path rejected: %s\n", why.c_str());
            return false;
        }
        path = *built;
    }

    if (cfg->minFreeMb > 0) {
        const long long freeMb = freeMegabytes(path);
        if (freeMb < 0) {
            switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_WARNING,
                              "Lyra recording: could not determine free space for %s; proceeding\n", path.c_str());
        } else if (freeMb < cfg->minFreeMb) {
            bump(state().metrics.failedDisk);
            switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_ERROR,
                              "Lyra recording: only %lld MB free under the recording root (need %lld); not recording\n",
                              freeMb, cfg->minFreeMb);
            return false;
        }
    }

    // Per-recording variables consumed by switch_ivr_record_session_event. Set on the
    // channel so the bug picks them up; cleared behaviour lives entirely here.
    // RECORD_HANGUP_ON_ERROR is deliberately NOT set: a write failure stops the
    // recording, never the call.
    if (cfg->track == Track::Stereo) {
        switch_channel_set_variable(channel, "RECORD_STEREO", "true");
    } else {
        switch_channel_set_variable(channel, "RECORD_STEREO", "false");
    }
    if (cfg->sampleRate > 0) {
        switch_channel_set_variable_printf(channel, "record_sample_rate", "%d", cfg->sampleRate);
    } else {
        switch_channel_set_variable(channel, "record_sample_rate", nullptr);
    }
    switch_channel_set_variable(channel, "RECORD_READ_ONLY", "false");
    switch_channel_set_variable(channel, "RECORD_WRITE_ONLY", "false");
    switch_channel_set_variable(channel, "record_post_process_exec_api", nullptr);

    const uint32_t limit = cfg->maxSeconds > 0 ? static_cast<uint32_t>(cfg->maxSeconds) : 0;
    // The bug is keyed on the file path; store the same key under kBugName so stop can
    // find and stop exactly this recording.
    switch_status_t st = switch_ivr_record_session_event(session, path.c_str(), limit, nullptr, nullptr);
    if (st != SWITCH_STATUS_SUCCESS) {
        bump(state().metrics.failedStart);
        switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_ERROR,
                          "Lyra recording could not start for %s (status %d); call continues unrecorded\n", path.c_str(), st);
        return false;
    }
    switch_channel_set_variable(channel, "lyra_recording_file", path.c_str());
    switch_channel_set_private(channel, kBugName, switch_core_strdup(switch_core_session_get_pool(session), path.c_str()));
    bump(state().metrics.started);
    bump(state().metrics.active);
    switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_INFO,
                      "Lyra recording started: %s (%s, %d ch)\n", path.c_str(),
                      cfg->format.c_str(), cfg->channels());
    return true;
}

void stopRecording(switch_core_session_t* session) {
    switch_channel_t* channel = switch_core_session_get_channel(session);
    const char* path = static_cast<const char*>(switch_channel_get_private(channel, kBugName));
    if (zstr(path)) return;
    // Just stop the bug; the RECORD_STOP event (recordStopHandler) does the accounting,
    // so start/stop and hangup all decrement `active` through one path.
    switch_ivr_stop_record_session(session, path);
    switch_channel_set_private(channel, kBugName, nullptr);
}

// Fired by the core when ANY recording finalises — on explicit stop and on hangup alike.
// We count only our own (path under the configured root), so `active`/`completed` are
// accurate however a call ends. This is the one place `active` is decremented.
void recordStopHandler(switch_event_t* event) {
    if (!event) return;
    const char* file = switch_event_get_header(event, "Record-File-Path");
    if (zstr(file)) return;
    // Ours iff the channel carried the marker variable we set in startRecording. This is
    // precise (RECORD_STOP fires for every module's recordings) and robust to a config
    // reload changing the root between a recording's start and stop.
    const char* marker = switch_event_get_header(event, "variable_lyra_recording_file");
    if (zstr(marker) || !file || std::strcmp(marker, file) != 0) return;
    if (state().metrics.active.load(std::memory_order_relaxed) > 0) {
        state().metrics.active.fetch_sub(1, std::memory_order_relaxed);
    }
    bump(state().metrics.completed);
    const char* cause = switch_event_get_header(event, "Record-Completion-Cause");
    switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO, "Lyra recording finalized: %s%s%s\n",
                      file, zstr(cause) ? "" : " cause=", zstr(cause) ? "" : cause);
}

// ------------------------------------------------------------------ dialplan apps

SWITCH_STANDARD_APP(lyra_record_app) {
    // `data` is an optional explicit filename (relative to the root); usually empty so
    // the configured pattern is used.
    startRecording(session, zstr(data) ? nullptr : data);
}

SWITCH_STANDARD_APP(lyra_record_stop_app) {
    (void)data;
    stopRecording(session);
}

// ------------------------------------------------------------------ configuration

std::shared_ptr<const RecordConfig> loadConfig(const char* reason) {
    coralx::config::Params params;
    switch_xml_t xml = nullptr, cfg = nullptr;
    if ((xml = switch_xml_open_cfg(kConfigFile, &cfg, nullptr))) {
        if (switch_xml_t settings = switch_xml_child(cfg, "settings")) {
            for (switch_xml_t p = switch_xml_child(settings, "param"); p; p = p->next) {
                const char* name = switch_xml_attr_soft(p, "name");
                const char* value = switch_xml_attr_soft(p, "value");
                if (!zstr(name)) params.set(name, value ? value : "");
            }
        }
        switch_xml_free(xml);
    } else {
        switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_NOTICE, "Lyra recording: no %s.xml; recording stays disabled\n", kConfigFile);
    }

    auto parsed = coralx::record::parseRecordConfig(params);
    for (const auto& w : parsed.warnings) {
        switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_WARNING, "Lyra recording config (%s): %s\n", reason, w.c_str());
    }
    for (const auto& e : parsed.errors) {
        switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_ERROR, "Lyra recording config (%s): %s\n", reason, e.c_str());
    }
    if (!parsed.ok()) return nullptr;

    // If enabled, make sure the root exists (or can be created) now, so the first call
    // does not discover a broken configuration.
    if (parsed.value->enabled) {
        if (switch_directory_exists(parsed.value->recordingRoot.c_str(), nullptr) != SWITCH_STATUS_SUCCESS) {
            if (switch_dir_make_recursive(parsed.value->recordingRoot.c_str(), SWITCH_DEFAULT_DIR_PERMS, nullptr) != SWITCH_STATUS_SUCCESS) {
                switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_ERROR,
                                  "Lyra recording config (%s): recording-path '%s' does not exist and cannot be created\n",
                                  reason, parsed.value->recordingRoot.c_str());
                return nullptr;
            }
            switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO, "Lyra recording: created recording root %s\n",
                              parsed.value->recordingRoot.c_str());
        }
    }
    return std::make_shared<const RecordConfig>(std::move(*parsed.value));
}

void writeStatus(switch_stream_handle_t* stream, bool json) {
    auto cfg = currentConfig();
    const Metrics& m = state().metrics;
    const auto v = [](const std::atomic<std::uint64_t>& c) { return static_cast<double>(c.load(std::memory_order_relaxed)); };
    if (json) {
        cJSON* root = cJSON_CreateObject();
        cJSON* c = cJSON_AddObjectToObject(root, "config");
        if (cfg) {
            cJSON_AddBoolToObject(c, "enabled", cfg->enabled);
            cJSON_AddStringToObject(c, "recording_path", cfg->recordingRoot.c_str());
            cJSON_AddStringToObject(c, "format", cfg->format.c_str());
            cJSON_AddStringToObject(c, "track", cfg->track == Track::Stereo ? "stereo" : "mixed");
            cJSON_AddNumberToObject(c, "sample_rate", cfg->sampleRate);
            cJSON_AddStringToObject(c, "directory_pattern", cfg->directoryPattern.c_str());
            cJSON_AddStringToObject(c, "filename_pattern", cfg->filenamePattern.c_str());
            cJSON_AddNumberToObject(c, "min_free_mb", static_cast<double>(cfg->minFreeMb));
            cJSON_AddNumberToObject(c, "max_seconds", cfg->maxSeconds);
        }
        cJSON* mm = cJSON_AddObjectToObject(root, "metrics");
        cJSON_AddNumberToObject(mm, "active", v(m.active));
        cJSON_AddNumberToObject(mm, "started", v(m.started));
        cJSON_AddNumberToObject(mm, "completed", v(m.completed));
        cJSON_AddNumberToObject(mm, "skipped_disabled", v(m.skippedDisabled));
        cJSON_AddNumberToObject(mm, "skipped_not_lyra", v(m.skippedNotLyra));
        cJSON_AddNumberToObject(mm, "failed_path", v(m.failedPath));
        cJSON_AddNumberToObject(mm, "failed_disk", v(m.failedDisk));
        cJSON_AddNumberToObject(mm, "failed_start", v(m.failedStart));
        cJSON_AddNumberToObject(mm, "reloads", v(m.reloads));
        cJSON_AddNumberToObject(mm, "reload_failures", v(m.reloadFailures));
        char* text = cJSON_PrintUnformatted(root);
        stream->write_function(stream, "%s\n", text ? text : "{}");
        switch_safe_free(text);
        cJSON_Delete(root);
        return;
    }
    if (cfg) {
        stream->write_function(stream, "Lyra recording: %s\n", cfg->enabled ? "ENABLED" : "disabled");
        stream->write_function(stream, "  recording-path    %s\n", cfg->recordingRoot.c_str());
        stream->write_function(stream, "  format            %s\n", cfg->format.c_str());
        stream->write_function(stream, "  track             %s (%d ch)\n", cfg->track == Track::Stereo ? "stereo" : "mixed", cfg->channels());
        stream->write_function(stream, "  sample-rate       %d%s\n", cfg->sampleRate, cfg->sampleRate == 0 ? " (follow call)" : "");
        stream->write_function(stream, "  directory-pattern %s\n", cfg->directoryPattern.c_str());
        stream->write_function(stream, "  filename-pattern  %s\n", cfg->filenamePattern.c_str());
        stream->write_function(stream, "  min-free-mb       %lld\n", cfg->minFreeMb);
        stream->write_function(stream, "  max-seconds       %d%s\n", cfg->maxSeconds, cfg->maxSeconds == 0 ? " (unlimited)" : "");
    }
    stream->write_function(stream, "  active %llu, started %llu, completed %llu\n",
                           (unsigned long long)m.active.load(), (unsigned long long)m.started.load(), (unsigned long long)m.completed.load());
    stream->write_function(stream, "  skipped: disabled %llu, not-lyra %llu\n",
                           (unsigned long long)m.skippedDisabled.load(), (unsigned long long)m.skippedNotLyra.load());
    stream->write_function(stream, "  failures: path %llu, disk %llu, start %llu\n",
                           (unsigned long long)m.failedPath.load(), (unsigned long long)m.failedDisk.load(), (unsigned long long)m.failedStart.load());
    stream->write_function(stream, "  reloads %llu (failed %llu)\n",
                           (unsigned long long)m.reloads.load(), (unsigned long long)m.reloadFailures.load());
}

#define LYRA_REC_SYNTAX "lyra_recording status [json] | lyra_recording reload | lyra_recording enabled"

SWITCH_STANDARD_API(lyra_recording_api) {
    (void)session;
    std::string command = cmd ? coralx::config::trim(cmd) : "";
    std::string arg;
    if (const auto sp = command.find(' '); sp != std::string::npos) {
        arg = coralx::config::trim(command.substr(sp + 1));
        command = command.substr(0, sp);
    }
    if (command.empty() || command == "status") {
        writeStatus(stream, arg == "json");
        return SWITCH_STATUS_SUCCESS;
    }
    if (command == "enabled") {
        // A one-word answer the dialplan reads to decide whether to stay in the media
        // path. Cheap and side-effect-free.
        auto cfg = currentConfig();
        stream->write_function(stream, "%s", (cfg && cfg->enabled) ? "true" : "false");
        return SWITCH_STATUS_SUCCESS;
    }
    if (command == "reload") {
        auto fresh = loadConfig("reload");
        if (!fresh) {
            bump(state().metrics.reloadFailures);
            stream->write_function(stream, "-ERR configuration rejected; previous configuration kept (see log)\n");
            return SWITCH_STATUS_SUCCESS;
        }
        {
            std::lock_guard<std::mutex> lock(state().mutex);
            state().config = fresh;
        }
        bump(state().metrics.reloads);
        switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO, "Lyra recording configuration reloaded (enabled=%s, root=%s)\n",
                          fresh->enabled ? "true" : "false", fresh->recordingRoot.c_str());
        stream->write_function(stream, "+OK reloaded (enabled=%s)\n", fresh->enabled ? "true" : "false");
        return SWITCH_STATUS_SUCCESS;
    }
    stream->write_function(stream, "-USAGE: %s\n", LYRA_REC_SYNTAX);
    return SWITCH_STATUS_SUCCESS;
}

}  // namespace

// ------------------------------------------------------------------ module entry points

SWITCH_MODULE_LOAD_FUNCTION(mod_lyra_record_load) {
    // A rejected config at load is not fatal: the module loads DISABLED so a bad file
    // never stops FreeSWITCH, and `lyra_recording reload` can fix it live.
    auto cfg = loadConfig("load");
    if (!cfg) {
        cfg = std::make_shared<const RecordConfig>();  // enabled=false
        switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_WARNING,
                          "mod_lyra_record loaded DISABLED because %s.xml is invalid; fix it and run `lyra_recording reload`\n", kConfigFile);
    }
    {
        std::lock_guard<std::mutex> lock(state().mutex);
        state().config = cfg;
    }

    *module_interface = switch_loadable_module_create_module_interface(pool, modname);

    switch_application_interface_t* app = nullptr;
    SWITCH_ADD_APP(app, "lyra_record", "Record a Lyra-to-Lyra call",
                   "Start server-side recording of the current Lyra call (no-op unless enabled in lyra_recording.conf).",
                   lyra_record_app, "[<filename>]", SAF_NONE);
    SWITCH_ADD_APP(app, "lyra_record_stop", "Stop Lyra recording", "Stop the recording started by lyra_record.",
                   lyra_record_stop_app, "", SAF_NONE);

    // Accurate active/completed accounting: the core fires RECORD_STOP on stop and hangup.
    if (switch_event_bind(modname, SWITCH_EVENT_RECORD_STOP, SWITCH_EVENT_SUBCLASS_ANY,
                          recordStopHandler, nullptr) != SWITCH_STATUS_SUCCESS) {
        switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_WARNING,
                          "mod_lyra_record: could not bind RECORD_STOP; active/completed counters will be approximate\n");
    }

    switch_api_interface_t* api = nullptr;
    SWITCH_ADD_API(api, "lyra_recording", "Lyra recording status, metrics and reload", lyra_recording_api, LYRA_REC_SYNTAX);
    switch_console_set_complete("add lyra_recording status");
    switch_console_set_complete("add lyra_recording status json");
    switch_console_set_complete("add lyra_recording reload");
    switch_console_set_complete("add lyra_recording enabled");

    switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO, "mod_lyra_record loaded (recording %s)\n",
                      cfg->enabled ? "ENABLED" : "disabled");
    return SWITCH_STATUS_SUCCESS;
}

SWITCH_MODULE_SHUTDOWN_FUNCTION(mod_lyra_record_shutdown) {
    switch_event_unbind_callback(recordStopHandler);
    std::lock_guard<std::mutex> lock(state().mutex);
    state().config.reset();
    return SWITCH_STATUS_SUCCESS;
}
