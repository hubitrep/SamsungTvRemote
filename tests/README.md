# Tests

## Samsung TV Remote Tester

`SamsungTVRemoteTester.groovy` is an app that runs the driver through a fixed set of tests against a
real TV and writes a report to paste in a forum post: what the TV sent (the driver's trace lines),
what the driver made of it (the device's events), and where either differs from what the driver
expects. Install it, select the Samsung TV Remote device, and press Run. The tests press HOME, launch
an app if the device knows its code, switch a Frame to art mode, and turn the TV off and on.

| # | Test | Checks |
|---|---|---|
| 1 | Device info | the `/api/v2/` reply: status, time, `PowerState` |
| 2 | Remote socket | the handshake reply, its time, the key sent after it |
| 3 | Key burst | three keys with the socket closed: one connect, three keys |
| 4 | Power off | how the TV goes away; the switch goes off and stays off |
| 5 | Power on | Wake-on-LAN sends, time to the first answer, the switch |
| 6 | Standby check | TVs without `PowerState`: the probe gets `ms.channel.timeOut` |
| 7 | Art mode | Frame TVs: art channel handshake, status reply, setting it there |
| 8 | Apps | launch and close by REST, and the app events |
| 9 | App list | whether the TV answers `ed.installedApp.get` |
| 10 | Discovery | whether the TV announces `_airplay._tcp` and the driver's `findTvs` finds it at its address (2016-2017 TVs are expected not to) |

For the maintainer, every report also carries the TV's model fields from `/api/v2/`, the protocol
lines of the handshake, art and app-list replies (masked), any event the driver doesn't handle
(`otherMessages`, which makes the test differ), and at the end a mock profile built from all of it
(see [TV profiles](#tv-profiles)).

- `test-tester-helpers.groovy`: unit tests for its pure helpers, no hub needed
  (`groovy tests/test-tester-helpers.groovy`).
- `test-live-tv.sh [@hub]`: runs it through its endpoints (turn on Script access in the app) and
  prints `[PASS]`/`[FAIL]`/`[WARN]` per test; exit 0, 1 on a driver difference, 2 if it couldn't run.
  It reads hub addresses from `.hubitat.json` in the current directory, or `HUBITAT_CONFIG`.

## Mock Samsung TV

`mocktv.py` acts as a Samsung Tizen TV so the driver can be exercised on a hub without one: REST
and websocket on 8001, secure websocket on 8002, Wake-on-LAN on UDP 9 and 7. Its replies follow
messages captured from real TVs: the fixtures in
[samsung-tv-ws-api](https://github.com/xchwarze/samsung-tv-ws-api), the decompiled Frame notes in
[ha-samsungtv-smart](https://github.com/TheFab21/ha-samsungtv-smart), and a 2020 TU7000.

```sh
python3 -m venv .venv && .venv/bin/pip install aiohttp zeroconf
openssl req -x509 -newkey rsa:2048 -nodes -keyout tv.key -out tv.pem -days 365 -subj "/CN=mocktv"
.venv/bin/python tests/mocktv.py tv.pem tv.key <workdir>
```

Run it on a machine on the hub's subnet (Wake-on-LAN is a broadcast) and set the device's TV IP to
that machine. The token it issues persists in `<workdir>/token.txt`.

- **Discovery.** With `MOCKTV_AIRPLAY_NAME=<name>` set, it announces `<name>._airplay._tcp` over mDNS
  (port 47941, `manufacturer=Samsung`, `model` from the profile's `airplay.model`, default `UMOCK`)
  while on, and withdraws it in standby and on exit. It announces the address of the default-route
  interface; `MOCKTV_IP` overrides it. The hub must be on the same LAN as the mock. The TXT `deviceid`
  is the MAC with its last byte changed, on purpose: a real TV's isn't its MAC, so a driver that
  used it would fail here too. Without the variable `zeroconf` isn't imported.
- **Power.** `KEY_POWER` puts it in standby; a Wake-on-LAN packet for its MAC boots it. Whether it
  still answers REST and sockets in standby depends on the profile.
- **Pairing.** A wrong or missing token on 8002 shows the "allow" dialog, accepted after 2 s; the
  reply carries a new token.
- **Apps.** YouTube and Netflix answer the app status, launch and close calls; other ids get 404.
- **Control.** `GET /mock/state`, `/mock/power?to=on|standby`, `/mock/profile?name=<profile>` and
  `/mock/unpair` on port 8001 change it between test cases.

### TV profiles

`MOCKTV_PROFILE=<file.json>` makes the mock act like a given TV. A profile uses the field names of
the tester's report, and the report ends with a ready-made profile ("mock profile (for the
maintainer)"), so a report from a new TV becomes a file in `mock-profiles/`. `default.json` fills in
what a profile leaves out; in `deviceInfo` and `isSupport` a profile lists only the keys that differ,
and `null` removes one. Running the tester against the mock with a profile taken from a report gives
back the same report, timings aside.

| Key | Report field or meaning |
|---|---|
| `deviceInfo`, `isSupport`, `apiVersion` | test 1: the `/api/v2/` reply (model fields only). `FrameTVSupport`, `TokenAuthSupport` and the presence of `PowerState` decide how the mock behaves |
| `handshake` | test 2: `ms.channel.connect`, or `ms.channel.unauthorized` when the allow dialog is refused |
| `connectMs` | test 2: delay before `ms.channel.connect` |
| `otherMessages` | events the TV sends that the driver doesn't handle; the mock sends them after a connect (`art:<name>` on the art channel) |
| `wentAway` | test 4: `REST stopped`, or anything else for a TV that keeps answering REST in standby |
| `restAfterOffS` | test 4: seconds REST keeps answering after the power-off |
| `probeReply` | test 6: what a socket gets in standby when REST still answers (`ms.channel.timeOut`, or no answer) |
| `artReady`, `statusReply`, `setReply`, `powerStateInArt` | test 7: art channel sends `ms.channel.ready`; art state at start; `set_artmode_status` reply before or after the broadcast (`not seen`: after); `PowerState` while showing art |
| `launchMs`, `closeBody` | test 8: time to answer an app launch; body of the close reply |
| `answered`, `apps` | test 9: whether `ed.installedApp.get` is answered, and with how many apps |
| `bootS` | mock only: seconds from Wake-on-LAN to on (test 5's `firstAnswerMs` is an upper bound) |
| `artTokenStalls` | mock only: a token on the art channel stalls the handshake into `ms.channel.timeOut` |

| Profile | TV | Source |
|---|---|---|
| `default.json` | 2020 TU7000 | tester report and a direct `/api/v2/` read |
| `frame2024.json` | 2024 Frame: late set reply, `PowerState` on in art, token stalls the art channel | ha-samsungtv-smart notes; platform string made up |
| `frame2025.json` | 2025 Frame: `PowerState` standby while showing art | ha-samsungtv-smart release notes; platform string made up |
| `qn88b2022.json` | 2022 QN88B: reports `PowerState` standby while REST still answers after off | tester report |
| `nopowerstate.json` | no `PowerState`, REST answers in standby | constructed |
| `notoken.json` | no token auth, plain `ws` on 8001 | constructed |
| `refused.json` | the allow dialog refused | constructed |
