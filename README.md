# Hubitat Samsung TV Remote

Hubitat driver for Samsung Tizen TVs (2016 and later): power, remote keys, apps and presets over the
TV's local websocket API, with an optional SmartThings connection for data the local API does not
provide.

Based on [Dave Gutheinz's Samsung TV Remote](https://github.com/DaveGut/HubitatActive/tree/master/SamsungTvRemote),
with his agreement; the changes below are on top of his 2.3.9b. Thank you, Dave, for the driver.
Support thread: [[BETA] Samsung TV Remote (2016 and later TVs)](https://community.hubitat.com/t/beta-samsung-tv-remote-2016-and-later-tvs/166829).

## Install

- **Hubitat Package Manager:** install from the manifest URL
  `https://raw.githubusercontent.com/hubitrep/SamsungTvRemote/main/packageManifest.json`.
- **Manually:** Drivers Code → New Driver → Import, with
  `https://raw.githubusercontent.com/hubitrep/SamsungTvRemote/main/SamsungTVRemote.groovy`.

Then follow Dave's [installation guide](Docs/Installation.pdf).

## Moving from Dave's version

The driver keeps its name (`Samsung TV Remote`) and namespace (`davegut`), so existing devices keep
working and nothing has to be recreated.

- **Installed with HPM:**
  1. **Package Manager Settings** → **Un-Match a Package** → **Remove a Matched Package**: pick
     "Samsung TV Remote" (Dave's package).
  2. **Match Up**: pick "Samsung TV Remote (hubitrep)".
  3. **Repair**: pick "Samsung TV Remote (hubitrep)" to download the code.
- **Installed by hand:** open the driver in Drivers Code, Import from the URL above, and Save.
- **Installed from Dave's bundle:** import the driver as above. The `davegut` libraries the bundle
  added to Libraries Code are no longer used by this driver.

Each TV device updates itself at its next power poll; its settings are kept.

## Tester

The optional **Samsung TV Remote Tester** app runs the driver through a set of tests on your TV and
writes a report to paste in the support thread. See [tests/README.md](tests/README.md). With HPM, add
it to an installed package with **Modify**.

## Changes

### 2.4.3 (beta)

- Frame TVs: `artModeStatus` is read over the art channel at configure, after each power change and
  after `artMode`, and `artMode` sets art mode there instead of sending a blind `KEY_POWER`. Before,
  the driver never opened the art channel, so `artModeStatus` stayed `none`. `KEY_POWER` remains the
  fallback for a Frame that doesn't answer on it.
- A late close report from a replaced socket no longer marks the new one closed.
- Refresh also polls the TV's power state; before, it only refreshed SmartThings attributes.
- While the TV shows its "allow" dialog for a new connection, keys wait up to 30 s for the TV's
  answer instead of going out after 3 s and being lost.
- New preference: trace logging of the TV's messages (token masked) for 30 minutes. The tester in
  [`tests/`](tests/README.md) reads it.

### 2.4.2

- With SmartThings, `inputSource` reads and sets the input through `samsungvd.mediaInputSource`
  when the TV reports it (2018 and later TVs leave the standard `mediaInputSource` empty).
- The switch logs at info when it changes, including when the TV is turned on or off with its own
  remote.
- `on()` resends Wake-on-LAN at 10 s and 20 s until the TV answers; on Wi-Fi it often misses
  the first packet. With SmartThings, it also sends SmartThings `switch on` at 17 s if the TV
  still hasn't answered.
- `setInputSource` is a command on the device page (same as `sourceSetST`).
- Saving preferences with the TV off now warns that the saved TV settings were kept, so a wrong IP
  address shows up.
- A code update is also applied at hub start and on `refresh`, not only at the next power poll.
- `appOpenByName` and app presets no longer report a failed launch when the app opened. The TV
  answers `true`, which the driver couldn't parse, so `appName` and `appId` were never set and
  `presetCreate` couldn't save an app.

### 2.4.1

- The device's Data section shows the TV's model number (`modelName`), platform (`platform`) and
  REST API version (`apiVersion`), filled the first time the TV answers. Include them when asking
  for help.
- `powerStateReported` says whether the TV reports `PowerState`. When it is `false`, power state
  comes from the websocket. The poll updates it, so a firmware update that drops the field shows up.

### 2.4.0

- The socket counts as ready when the TV sends `ms.channel.connect`, not when the connection opens.
  Keys queued before that are sent then; a burst of keys opens one connection, not one per key.
  Keys queued more than 15 s earlier are dropped instead of sent late.
- Power detection without `PowerState` waits for that confirmation too, and treats
  `ms.channel.timeOut` as asleep, as both Home Assistant integrations do.
- Socket state is kept in memory, so after a hub reboot or code update the driver no longer
  believes a socket is still open. New `Initialize` capability resets it at hub start.
- Power-off: the regular-TV socket closes only after the POWER key is out; the Frame held press
  starts once the socket is ready. A power-off that never reached the TV no longer fires later.
- The TV Startup Display mode runs only after the `on` command, not when the switch comes back on
  after a missed poll.
- `on` clears an earlier socket failure and checks the TV 10 s and 20 s later.
- The Wake-on-LAN packets no longer wait 10 s for a reply that never comes, which queued later
  hub commands from the device behind them.
- The switch event is sent on every poll, so the device's last activity stays current.
- Presets, fast forward/back and art mode at power-on no longer pause the driver; their steps are
  scheduled instead, so they act on the TV's reply rather than on the values from before.
- The app-code scan no longer stops power polling, and a bad reply no longer ends the scan.
- With SmartThings, `mute` and `unmute` set the state instead of toggling it. Without it the
  driver can't read the mute state, so both still send the mute key.
- SmartThings: commands no longer block the driver. An expired key (HTTP 401) is reported once and
  SmartThings polling stops until a new key is saved; channel changes then fall back to remote keys.
- Frame TVs: `artMode` and `artModeStatus` are always declared, and the art channel connects
  without the token, which 2024+ Frames reject.
- Info logging defaults to on when the preference has never been saved.

The Frame and SmartThings changes are untested on hardware.

### 2.3.10

- Power state works on TV firmware that no longer reports `PowerState` in `/api/v2/`: the driver
  uses the remote websocket instead, as Home Assistant's `samsungtv` integration does.
- After power-off the socket is closed and the switch is held off for one poll interval plus 5 s,
  so a TV that is still shutting down does not read as on. Turning the TV on ends the hold.
- Power-on by Wake-on-LAN only, sent to ports 9 and 7. `KEY_POWER` is a toggle and could switch an
  on TV off.
- Power-off: a single key press on regular TVs; on a Frame TV, a held press once the socket is open.
- A websocket failure overrides a stale REST "on".
- Key sends go through a queue that is sent in order once the socket opens. Before, only the last
  key was kept, so multi-digit channel numbers lost digits.
- When the TV rejects the pairing token, the driver clears it so the next connection asks for
  permission on the TV again.
- The art-mode socket is no longer opened on TVs that are not Frames.
- Key payloads include `Option: "false"`, as other Samsung TV clients send.
- New preference: close the websocket after an idle period (default: never). On a TV that doesn't
  report `PowerState`, the next power poll reopens it, so the setting has no lasting effect there.
- With the TV off, a code update or Save Preferences no longer logs an error once the device is set
  up (the saved TV settings are kept), and no warning about the app-code scan unless one was asked for.
- After a code update the driver re-initializes once on its next poll.
- Fixes: `appData` NPE, the app-launch request body, and four preset defects.
- New command `getInstalledApps`, a diagnostic that asks the TV for its app list and logs the reply.
  Some TVs do not answer it.

## Dave's documentation

- [Preferences and states](Docs/Prefs-States.pdf)
- [Commands](Docs/Commands.pdf)
- [Button interface](Docs/ButtonInterface.pdf)
- [Apps and presets](Docs/AppsAndPresets.pdf)
- [Samsung app list](Docs/SamsungAppList.pdf) (reference only)
- [Samsung remote key list](Docs/SamsungKeyList.pdf) (reference only)

## License

Apache License 2.0; see [LICENSE](LICENSE). Copyright Dave Gutheinz; changes copyright hubitrep.
Files changed in this fork say so in their header.
