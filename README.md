# Hubitat Samsung TV Remote

Hubitat driver for Samsung Tizen TVs (2016 and later): power, remote keys, apps and presets over the
TV's local websocket API, with an optional SmartThings connection for data the local API does not
provide.

This is a maintained fork of [Dave Gutheinz's Samsung TV Remote](https://github.com/DaveGut/HubitatActive/tree/master/SamsungTvRemote),
continued with his agreement. Dave wrote the driver; the changes below are on top of his 2.3.9b.
Support thread: [Samsung Hubitat TV Integration](https://community.hubitat.com/t/samsung-hubitat-tv-integration-2016-and-later/55120).

## Install

- **Hubitat Package Manager:** install from the manifest URL
  `https://raw.githubusercontent.com/hubitrep/SamsungTvRemote/main/packageManifest.json`.
- **Manually:** Drivers Code → New Driver → Import, with
  `https://raw.githubusercontent.com/hubitrep/SamsungTvRemote/main/SamsungTVRemote.groovy`.
- **Bundle:** [SamsungTVRemote.zip](SamsungTVRemote.zip) holds the same code as separate libraries.

Then follow Dave's [installation guide](Docs/Installation.pdf).

## Moving from Dave's version

The driver keeps its name (`Samsung TV Remote`) and namespace (`davegut`), so existing devices keep
working and nothing has to be recreated.

- **Installed with HPM:** in HPM, Unmatch Dave's package, then Match Up and pick
  "Samsung TV Remote (hubitrep)", then Repair it to download the code.
- **Installed by hand:** open the driver in Drivers Code, Import from the URL above, and Save.

After updating, open each TV device and click Save Preferences.

## Changes

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
- `mute` and `unmute` set the state instead of toggling it.
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
- New preference: close the websocket after an idle period (default: never).
- With the TV off, a code update or Save Preferences no longer logs an error once the device is set
  up (the saved TV settings are kept), and no warning about the app-code scan unless one was asked for.
- After a code update the driver re-initializes once on its next poll.
- Fixes: `appData` NPE, the app-launch request body, and four preset defects.
- New command `getInstalledApps`, a diagnostic that asks the TV for its app list and logs the reply.
  Some TVs do not answer it.

## Source layout

`src/` holds the driver core and its libraries, as in Dave's bundle. `python3 flatten.py` builds
`SamsungTVRemote.groovy` (the single-file driver, in the format Hubitat produces for a driver with
`#include`s) and `SamsungTVRemote.zip` from them. Edit `src/`, never the generated files.

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
