# 4. Configuration

Two files, both in `autoload_configs/`. Nothing operational is hardcoded; the tables in
`03-lld.md §3.4` are the authority on ranges and defaults.

## 4.1 `lyra.conf.xml` (mod_lyra)

The codec itself. Most deployments only ever set `model-path`.

```xml
<configuration name="lyra.conf" description="Lyra codec">
  <settings>
    <param name="model-path"      value="/usr/local/freeswitch/share/freeswitch/lyra/model_coeffs"/>
    <param name="default-bitrate" value="3200"/>   <!-- 3200 | 6000 | 9200 -->
    <param name="dtx"             value="false"/>
    <param name="sample-rates"    value="16000"/>
    <param name="ptime"           value="20"/>
  </settings>
</configuration>
```

## 4.2 `lyra_recording.conf.xml` (mod_lyra_record)

The recording policy. `enabled=false` is the shipped default and means the module does
nothing.

```xml
<configuration name="lyra_recording.conf" description="Lyra recording">
  <settings>
    <param name="enabled"           value="true"/>
    <param name="recording-path"    value="/usr/local/freeswitch/var/lib/freeswitch/recordings/lyra"/>
    <param name="format"            value="wav"/>
    <param name="track"             value="stereo"/>   <!-- stereo | mixed -->
    <param name="sample-rate"       value="16000"/>    <!-- 0 = follow the call -->
    <param name="directory-pattern" value="{date}"/>
    <param name="filename-pattern"  value="{time}_{caller}_{callee}_{uuid}"/>
    <param name="min-free-mb"       value="100"/>
    <param name="max-seconds"       value="0"/>
  </settings>
</configuration>
```

With the defaults, a call from 1000 to 1001 on 2026-09-19 at 19:25:30 records to:

```
/usr/local/freeswitch/var/lib/freeswitch/recordings/lyra/20260919/192530_1000_1001_550e8400-e29b-41d4-a716-446655440000.wav
```

## 4.3 Turning recording on or off at runtime

No restart. After editing either file:

```bash
/usr/local/freeswitch/bin/fs_cli -x "reloadxml"
/usr/local/freeswitch/bin/fs_cli -x "lyra_recording reload"   # applies lyra_recording.conf
/usr/local/freeswitch/bin/fs_cli -x "lyra reload"             # applies model-path / dtx of lyra.conf
```

`lyra_recording reload` validates the whole file, creates the recording root if missing, and
**only then** swaps it in; a bad file is rejected and the previous configuration stays live
(`lyra_recording status` shows `reload_failures`). Calls already in progress keep the
configuration they started with; the next call uses the new one.

`lyra reload` re-applies `model-path` and `dtx` for codecs created from then on.
`default-bitrate`, `ptime` and `sample-rates` are baked into the registered codec
implementations at load, so changing those needs `reload mod_lyra` (FreeSWITCH refuses that
while any Lyra call is active) or a restart — the command tells you when that is the case.

## 4.4 The dialplan side — why config alone is not enough

Recording needs FreeSWITCH **in the media path** for the calls to be recorded. Today the
dialplan bypasses media for audio calls, so the server never sees the Lyra RTP. The merged
`01_coralx_push_wake.xml` / `coralx.xml` in `conf/dialplan/` read `${lyra_recording(enabled)}`
and, only when it is `true`, drop the bypass, force Lyra end to end, and arm the recorder.
When it is `false` they bypass exactly as before. This is the one change you apply by hand —
see `06-build-and-deploy.md §6.5`. Nothing about a **disabled** deployment changes.

## 4.5 Codec preferences (deployment note)

For FreeSWITCH to negotiate Lyra when it is in the media path, `lyra` must be in the effective
codec list for the leg. The merged dialplan forces this per call with
`absolute_codec_string=lyra@16000h@20i,PCMU,PCMA`, so no profile edit is required for the
recorded path. If you prefer to negotiate rather than force, add `lyra` to the sofia
profile's `inbound-codec-prefs`/`outbound-codec-prefs` and drop the `absolute_codec_string`
line. Keep `PCMU` in the account's list so a non-Lyra endpoint can still connect.
