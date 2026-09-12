# 03 — The directory: domain, users, dial-string

The directory is what `mod_sofia` authenticates against and what the `user/` dial
endpoint looks up. It is one domain, `directory/default.xml`, with the users pulled in
from `directory/default/*.xml`.

## `directory/default.xml` — the domain

```xml
<domain name="$${domain}">                       <!-- the Mac's LAN IP, see 01 -->
  <params>
    <param name="dial-string" value="…"/>          <!-- how user/1001@domain becomes a sofia/ dial string; see below -->
    <param name="jsonrpc-allowed-methods" value="verto"/>
  </params>
  <variables>                                     <!-- inherited by every user -->
    <variable name="record_stereo" value="true"/>
    <variable name="default_gateway" value="$${default_provider}"/>
    <variable name="default_areacode" value="$${default_areacode}"/>
    <variable name="transfer_fallback_extension" value="operator"/>
  </variables>
  <groups>                                        <!-- default, sales, billing, support (stock) -->
    <group name="default">
      <users>
        <X-PRE-PROCESS cmd="include" data="default/*.xml"/>
      </users>
    </group>
    …
  </groups>
</domain>
```

Backup of the file before the dial-string edit: `directory/default.xml.bak.20260912-221132`.

## The users — `directory/default/1000.xml` … `1019.xml`

Twenty files. Sixteen are vanilla; `1001`–`1004` carry three extra call-forward variables.
`1001.xml` in full:

```xml
<include>
  <user id="1001">
    <params>
      <param name="password" value="$${default_password}"/>   <!-- vars.xml line 15; the stock value -->
      <param name="vm-password" value="1001"/>
    </params>
    <variables>
      <variable name="toll_allow" value="domestic,international,local"/>
      <variable name="accountcode" value="1001"/>
      <variable name="user_context" value="default"/>          <!-- calls from this user route in context default -->
      <variable name="effective_caller_id_name" value="Extension 1001"/>
      <variable name="effective_caller_id_number" value="1001"/>
      <variable name="outbound_caller_id_name" value="$${outbound_caller_name}"/>
      <variable name="outbound_caller_id_number" value="$${outbound_caller_id}"/>
      <variable name="callgroup" value="techsupport"/>
      <variable name="cfwd_enabled" value="false"/>             <!-- local additions: call-forward experiment -->
      <variable name="cfwd_destination" value=""/>
      <variable name="call_forward_all_destination" value=""/>
    </variables>
  </user>
</include>
```

| Fact | Detail |
|---|---|
| Password | every user shares `$${default_password}` (`vars.xml` line 15). It is the value FreeSWITCH ships with; treat it as public and change it before the server is reachable from anywhere but this LAN |
| `user_context=default` | what puts an authenticated call into `dialplan/default.xml` rather than the profile's `context=public` |
| `effective_caller_id_*` | what the callee's app shows: `"Extension 1001" <1001>` |
| `cfwd_*` | not vanilla; present on 1001–1004 only. The `cfwd_master`/`agent_1003`/`agent_1004` extensions in `dialplan/default.xml` actually read `mod_hash` (`hash(call_forward/1003)`), not these variables — see [04](04-dialplan.md) |
| Reserved | **1018 and 1019 belong to the instrumented suite** (`docs/testing.md`, ADR-005). Do not sign a handset in on them |
| In use (Sept 2026) | 1001 and 1003/1004 on the two Zebra TC15s; 1005/1006 used by scripted SIP tests; 1002 named in the media-bypass dialplan |
| Other files | `brian.xml`, `default.xml`, `example.com.xml`, `skinny-example.xml` — stock examples, no password, unusable for registration |

`vm-password` is the voicemail PIN, only relevant if the voicemail variant of the push
timeout is enabled ([05](05-push-wake.md)).

## The dial-string — where `user/1001@…` becomes a real target

When the dialplan does `bridge user/1001@$${domain}`, the `user` endpoint reads this
user's registration and expands the domain's `dial-string` param. The stock value is:

```
{^^:sip_invite_domain=${dialed_domain}:presence_id=${dialed_user}@${dialed_domain}}${sofia_contact(*/${dialed_user}@${dialed_domain})},${verto_contact(${dialed_user}@${dialed_domain})}
```

`sofia_contact` returns the stored contact as a dial string, e.g.
`sofia/internal/sip:1001@192.168.2.191:46020;ob;fs_nat=yes;fs_path=sip%3A1001%40…` (or
`error/user_not_registered`), and `verto_contact` returns nothing because `mod_verto` is not
loaded.

**On 2026-09-12 the value was wrapped in two `regex()` passes** (the full line is in
`directory/default.xml`, and a copy in `coralx-push-sender/deploy/freeswitch/directory/`):

```
${regex(${regex(${sofia_contact(*/${dialed_user}@${dialed_domain})}|^(.*);pn-provider=[^;]*;pn-param=[^;]*;pn-prid=[^;]*(.*)$|%1%2)}|^(.*)%3Bpn-provider%3D.*$|%1)}
```

Why: a push-registered Coral X handset's Contact carries its RFC 8599 parameters
(`;pn-provider=fcm;pn-param=…;pn-prid=<163-character FCM token>`), and sofia copies the
whole contact into the **Request-URI, the Route (from `fs_path`) and the To** of the INVITE
it sends to the phone. Measured: 1675 bytes with the parameters, **1306 without** — the
larger one is IP-fragmented on any 1500-MTU path. The first pass strips the raw
parameters, the second strips their percent-encoded copy inside `fs_path`. `regex()`
returns its input unchanged when the pattern does not match, so contacts without `pn-*` and
`error/user_not_registered` pass through untouched (all three cases were checked with
`fs_cli -x 'eval …'`). The token itself is read from the registration by the push sender,
not from the dial-string.

How to see what a user will be dialled as, right now:

```bash
fs_cli -x 'eval ${sofia_contact(*/1001@'"$(fs_cli -x 'eval $${domain}')"')}'    # raw stored contact
fs_cli -x 'user_exists id 1001 '"$(fs_cli -x 'eval $${domain}')"                # true/false: is 1001 in the directory
```

## Adding a user

1. Copy `directory/default/1019.xml` to `10NN.xml`, replace every `1019` (four places).
2. `fs_cli -x reloadxml` — the directory is read on demand, no profile restart.
3. `fs_cli -x "user_exists id 10NN <domain>"` → `true`.
4. Routing already covers `^(10[01][0-9])$` (1000–1019) in `dialplan/default/01_local_users_100x.xml`;
   a number outside that range needs a dialplan entry too ([04](04-dialplan.md)).

The password can be set per user (`<param name="password" value="…"/>`) instead of the
shared global; `inbound-reg-force-matching-username=true` on the profile means the auth
username is always the `user id`.
