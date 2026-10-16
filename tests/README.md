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
python3 -m venv .venv && .venv/bin/pip install aiohttp
openssl req -x509 -newkey rsa:2048 -nodes -keyout tv.key -out tv.pem -days 365 -subj "/CN=mocktv"
.venv/bin/python tests/mocktv.py tv.pem tv.key <workdir>
```

Run it on a machine on the hub's subnet (Wake-on-LAN is a broadcast) and set the device's TV IP to
that machine. The token it issues persists in `<workdir>/token.txt`.

- **Power.** `KEY_POWER` puts it in standby; a Wake-on-LAN packet for its MAC boots it in
  `MOCKTV_WAKE` seconds (default 5). In standby it doesn't answer REST or websockets, like a TV
  that has dropped off the network.
- **Pairing.** A wrong or missing token on 8002 shows the "allow" dialog, accepted after 2 s; the
  reply carries a new token.
- **Apps.** YouTube and Netflix answer the app status, launch and close calls; other ids get 404.
- **Control.** `GET /mock/state`, `/mock/power?to=on|standby`, `/mock/quirks?set=a,b` and
  `/mock/unpair` on port 8001 change it between test cases.

`MOCKTV_QUIRKS` (or `/mock/quirks`) acts out differences between models:

| Quirk | Behaviour |
|---|---|
| `frame` | Frame TV: the art channel answers `get_artmode_status` and `set_artmode_status` and broadcasts `art_mode_changed`; a short `KEY_POWER` toggles art mode, a press held 2.5 s or more turns it off |
| `frame2024` | With `frame`: a token on the art channel stalls the handshake into `ms.channel.timeOut`, and the `set_artmode_status` reply trails the broadcast by 10 s |
| `frame2025` | With `frame`: REST reports `PowerState: standby` while art mode is on |
| `nopowerstate` | The REST device info has no `PowerState` |
| `restinstandby` | REST answers in standby with `PowerState: standby`; the remote socket opens, then gets `ms.channel.timeOut` |
| `notoken` | No token auth: `TokenAuthSupport` false, 8002 refused |
| `deny` | The allow dialog is refused: `ms.channel.unauthorized`, then close |
| `noapplist` | `ed.installedApp.get` is never answered |
