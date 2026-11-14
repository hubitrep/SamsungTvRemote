# Tests

## Samsung TV Remote Tester

`SamsungTVRemoteTester.groovy` is an app that runs the driver through a fixed set of tests against a
real TV and writes a report to paste in a forum post: what the TV sent (the driver's trace lines),
what the driver made of it (the device's events), and where either differs from what the driver
expects. Install it, select the Samsung TV Remote device, and click **Open tester**. The tests press
HOME, launch an app if the device knows its code, switch a Frame to art mode, and turn the TV off and on.

The tester page (`samsung-tv-tester-ui.html`, which the app copies from GitHub into the hub's File
Manager and serves at its `/ui.html`) shows each test as it runs, with a countdown to the end of the
step it is waiting on. Where only the person at the TV can tell, it asks, with its own countdown:
choose Allow on the TV, whether the TV went to its home screen after the first Home key (`homeSeen`),
whether the screen went off after Off (`screenOff`: off, on, or art on a Frame), whether the picture
came on (and when), whether art is showing, whether the app opened, whether AirPlay is on. An
unanswered question is reported as "no answer" and the run moves on. Before the run it asks about two
TV settings the TV doesn't report (Power On with Mobile, IP Remote), and has a box, "I'm at the TV and
can answer questions", checked by default. Cleared, no question opens: steps that only wait for an
answer end at once, the Power on watch still reads the TV to its own limit, the report reads "not
asked" for each question, and its settings line ends "questions off" (`questions on` otherwise). The
app takes the same choice as `attended=yes|no` on `/run`. After the run it shows the report with Copy, and the mock profile as a download. Its
remote pad (power, arrows, OK, Back, Home, Exit, menu, guide, source, channels, volume, media keys, Art on a Frame TV or Ambient on another, and a field for any key name) works outside a run; each button calls the driver command of the same name.

**Pad log.** Under the pad, a "Pad log" panel lists each key pressed outside a run: time, button (and the
typed key for Send key), and the driver's trace, warn and error lines from the 4 seconds after the press, or
up to the next press (about 15 lines per press; lines past the sixth sit behind "more"). The first press
turns the driver's trace logging on, and it goes back to what it was after 10 minutes without a press; a
run starts from the value the person had before the pad. The panel has Copy and Clear (`/padlog/clear`);
`/status` carries the last 20 entries as `padLog` (the app keeps 50). The next report adds a "Manual keys"
section with the presses made since the previous report.

| # | Test | Checks |
|---|---|---|
| 1 | Device info | the `/api/v2/` reply: status, time, `PowerState`; whether the device's MAC matches the TV's and the hub's view of its address |
| 2 | Remote socket | the handshake reply, its time over three connects, the key sent after it |
| 3 | Key burst | three keys with the socket closed: one connect, three keys |
| 4 | Apps | launch and close by REST, and the app events |
| 5 | App list | whether the TV answers `ed.installedApp.get` |
| 6 | Discovery | whether the TV announces `_airplay._tcp` and the driver's `findTvs` finds it at its address (2016-2017 TVs are expected not to) |
| 7 | Art mode | Frame TVs: art channel handshake, status reply, setting it there |
| 8 | Power off | how the TV goes away, from `/api/v2/` read every 5 s; the switch goes off and stays off |
| 9 | Standby check | a socket opened with the TV in standby: `ms.channel.timeOut` expected on TVs without `PowerState`, recorded on the others |
| 10 | Power on | Wake-on-LAN sends; the TV's `/api/v2/` read every 5 s for 2 minutes; when the picture came on (asked); the switch |
| 11 | SmartThings status | SmartThings answers the driver's status request and reports the TV on; which input, picture and sound capabilities the TV uses; whether the driver named the app showing, and what the TV answers when asked for that app's name |
| 12 | SmartThings volume | `setVolume` one step down, read back twice, then put back |
| 13 | SmartThings volume keys | the volume-down key, the new volume read back from SmartThings, then the volume-up key |
| 14 | SmartThings mute | mute switched over by `mute` or `unmute`, read back twice, then put back |
| 15 | SmartThings picture mode | `togglePictureMode` to the next mode in SmartThings' list, read back twice, then `setPictureMode` back |
| 16 | SmartThings sound mode | the same for the sound mode |
| 17 | SmartThings input | the input key, the new input read back from SmartThings, then `setInputSource` back to the first one (the picture changes for a few seconds) |
| 18 | SmartThings channel | channel up, the new channel read back, then `setTvChannel` back; skipped unless the TV shows a TV channel |

Tests 11 to 18 run only on a device with **Connect to SmartThings** on and a working key; on any other
device they are skipped. On such a device the Apps test also checks that SmartThings reports the app
launched, that the driver's name for it survives that report, and what SmartThings reports after the
close; the Power on test notes when the driver had to ask SmartThings to wake the TV (`stWake`). The
run turns the driver's debug logging on. Each value is read back once after the command and once after
a second status request, because SmartThings can answer the first with the old value (`statusLags`).

For the maintainer, every report also carries the TV's model fields from `/api/v2/`, the protocol
lines of the handshake, art and app-list replies (masked), any event the driver doesn't handle
(`otherMessages`, which makes the test differ), and at the end a mock profile built from all of it
(see [TV profiles](#tv-profiles)).

- `test-tester-helpers.groovy`: unit tests for its pure helpers, no hub needed
  (`groovy tests/test-tester-helpers.groovy`).
- `test-tester-ui.js`: unit tests for the page's logic, no hub or browser needed
  (`node tests/test-tester-ui.js`).
- `test-live-tv.sh [@hub]`: runs it through its endpoints (open the app once first, which turns OAuth
  on); it answers no questions, so each runs out its time. It prints `[PASS]`/`[FAIL]`/`[WARN]` per test; exit 0, 1 on a driver difference, 2 if it couldn't run.
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
  While on, it also answers SSDP searches for DIAL, and with `"ssdpRemoteReceiver": true` in the
  profile (`ssdp-rcr.json`) for `urn:samsung.com:device:RemoteControlReceiver:1`, which the TU7000
  doesn't answer. The description is at `/mock/rcr.xml` on 8001.
- **Power.** `KEY_POWER` puts it in standby; a Wake-on-LAN packet for its MAC boots it. Whether it
  still answers REST and sockets in standby depends on the profile.
- **Pairing.** A wrong or missing token on 8002 shows the "allow" dialog, accepted after 2 s; the
  reply carries a new token.
- **Apps.** YouTube and Netflix answer the app status, launch and close calls; other ids get 404.
- **Control.** `GET /mock/state`, `/mock/power?to=on|standby`, `/mock/profile?name=<profile>` and
  `/mock/unpair` on port 8001 change it between test cases. `/mock/wol` acts as a received magic
  packet (same path as UDP), for a host that can't bind UDP 9 and 7. `/mock/state` has `pictureOn`
  (on and not booting), `lastKey` and `keys` (the key codes sockets sent).

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
| `connectMs` | test 2: delay before `ms.channel.connect`; a message sent before it gets `ms.error` "No Authorized" |
| `otherMessages` | events the TV sends that the driver doesn't handle; the mock sends them after a connect (`art:<name>` on the art channel) |
| `wentAway` | test 8: `REST stopped`, or anything else for a TV that keeps answering REST in standby |
| `restAfterOffS` | test 8: seconds REST keeps answering after the power-off |
| `probeReply` | test 9: what a socket gets in standby when REST still answers (`ms.channel.timeOut`, or no answer) |
| `artReady`, `statusReply`, `setReply`, `powerStateInArt` | test 7: art channel sends `ms.channel.ready`; art state at start; `set_artmode_status` reply before or after the broadcast (`not seen`: after); `PowerState` while showing art |
| `launchMs`, `closeBody` | test 4: time to answer an app launch; body of the close reply |
| `answered`, `apps` | test 5: whether `ed.installedApp.get` is answered, and with how many apps |
| `onMs` | power-on test: `null` makes the mock ignore Wake-on-LAN (absent: it wakes after `bootS`) |
| `bootS` | mock only: seconds from Wake-on-LAN to on (test 10's `firstAnswerMs` is an upper bound) |
| `wolIgnoredS` | mock only: a Wake-on-LAN packet within this many seconds of going to standby is ignored (absent: never) |
| `standbyAnswerS` | mock only: for this many seconds after an accepted Wake-on-LAN packet REST answers `PowerState` standby, and answers at all even for a TV whose REST stops in standby; then it reports on (absent: no such window) |
| `artTokenStalls` | mock only: a token on the art channel stalls the handshake into `ms.channel.timeOut` |

| Profile | TV | Source |
|---|---|---|
| `default.json` | 2020 TU7000 | tester report and a direct `/api/v2/` read |
| `frame2024.json` | 2024 Frame: late set reply, `PowerState` on in art, token stalls the art channel | ha-samsungtv-smart notes; platform string made up |
| `frame2025.json` | 2025 Frame: `PowerState` standby while showing art | ha-samsungtv-smart release notes; platform string made up |
| `qn88b2022.json` | 2022 QN88B: reports `PowerState` standby while REST still answers after off | tester report |
| `qn95b2022.json` | 2022 QN95B: 6.6 s handshake, REST answers `PowerState` standby for 6 s after off then stops, Wake-on-LAN ignored within 6 s of standby, slow to report on (`standbyAnswerS` and `bootS` are guesses) | tester report and owner posts |
| `nopowerstate.json` | no `PowerState`, REST answers in standby | constructed |
| `notoken.json` | no token auth, plain `ws` on 8001 | constructed |
| `refused.json` | the allow dialog refused | constructed |

## Mock SmartThings

`mocksmartthings.py` stands in for SmartThings so **Samsung TV SmartThings Link** can be tested
without a SmartThings API plan. It serves the OAuth authorize and token endpoints and the device list,
with SmartThings' rules: single-use codes and refresh tokens, the old access token refused once a
refresh succeeds, `400 invalid_grant` for anything else. The account holds one TV and one non-TV device.

```bash
python3 tests/mocksmartthings.py --client-id test-client --client-secret test-secret --port 8099
```

In the app, enter those client values and, under **Advanced**, the mock's address
(`http://<host>:8099`), then sign in: the mock redirects through Hubitat's cloud back to the app.
`--expires-in` shortens the token life, `--deny` makes sign-in fail, `--second-tv` adds a second TV, `POST /mock/revoke` ends the
session, and `GET /mock/state` shows what was called. The driver still calls the real SmartThings,
which refuses the mock's tokens with HTTP 401.
