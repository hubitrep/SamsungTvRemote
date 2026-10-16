# Mock Samsung Tizen TV for rehearsing the Hubitat driver.
# Serves REST and ws on 8001, wss on 8002, and listens for Wake-on-LAN on UDP 9 and 7.
# Message shapes follow replies captured from real TVs: the fixtures and art-channel code of
# xchwarze/samsung-tv-ws-api, the decompiled Frame notes in TheFab21/ha-samsungtv-smart, and a
# 2020 TU7000. No code is taken from either project.
#
# usage: mocktv.py <cert.pem> <key.pem> <workdir>
#   MOCKTV_QUIRKS=frame,nopowerstate,...   (see QUIRKS below)
#   control: GET http://<mock>:8001/mock/state, /mock/power?to=on|standby, /mock/quirks?set=a,b
import asyncio, json, os, random, ssl, sys, time, uuid
from aiohttp import web, WSMsgType

CERT, KEY, WORK = sys.argv[1], sys.argv[2], sys.argv[3]
QUIRKS_HELP = {
    "frame": "Frame TV: art channel answers, a short KEY_POWER toggles art mode, a held one turns off",
    "frame2024": "with frame: a token on the art channel stalls the handshake into ms.channel.timeOut, "
                 "and the set_artmode_status reply trails the art_mode_changed broadcast by 10 s",
    "nopowerstate": "REST device info has no PowerState",
    "restinstandby": "REST answers in standby (PowerState standby); the remote socket opens, then times out",
    "notoken": "no token auth: TokenAuthSupport false, wss refused",
    "deny": "the on-screen allow is refused: ms.channel.unauthorized, then close",
    "noapplist": "ed.installedApp.get is never answered",
}
WAKE_S = float(os.environ.get("MOCKTV_WAKE", "5"))
MAC = os.environ.get("MOCKTV_MAC", "f4:7b:09:00:00:01")
APPS = {"111299001912": ("YouTube", "2.1.498"), "3201907018807": ("Netflix", "5.2.59020")}

tv = {
    "power": "on", "art": "off", "app": None, "quirks": set(),
    "token": None, "booting": False,
}
TOKEN_FILE = os.path.join(WORK, "token.txt")
if os.path.exists(TOKEN_FILE):
    tv["token"] = open(TOKEN_FILE).read().strip() or None
sockets = set()        # (ws, channel)

def q(name):
    return name in tv["quirks"]

def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)

def set_quirks(spec):
    names = {s for s in spec.split(",") if s}
    unknown = names - QUIRKS_HELP.keys()
    if unknown:
        raise ValueError(f"unknown quirks: {sorted(unknown)}")
    tv["quirks"] = names
    log("quirks:", sorted(names) or "none")

def device_info(request):
    host = request.host.split(":")[0]
    frame, token = str(q("frame")).lower(), str(not q("notoken")).lower()
    dev = {
        "FrameTVSupport": frame, "GamePadSupport": "true", "ImeSyncedSupport": "true",
        "Language": "en_US", "OS": "Tizen", "TokenAuthSupport": token, "VoiceSupport": "false",
        "WallScreenRatio": "0", "WallService": "false", "countryCode": "CA",
        "description": "Samsung DTV RCR", "developerIP": "0.0.0.0", "developerMode": "0",
        "duid": "uuid:7e7ef6f4-0000-4000-8000-000000000001", "firmwareVersion": "Unknown",
        "id": "uuid:7e7ef6f4-0000-4000-8000-000000000001", "ip": host,
        "model": "22_PONTUSM_FTV" if q("frame") else "20_KANTSU2_UHD_BASIC",
        "modelName": "QN55LS03BAFXZC" if q("frame") else "UN50TU7000FXZC",
        "name": "[TV] Mock", "networkType": "wireless", "resolution": "3840x2160",
        "smartHubAgreement": "true", "ssid": "", "type": "Samsung SmartTV",
        "udn": "uuid:7e7ef6f4-0000-4000-8000-000000000001", "wifiMac": MAC,
    }
    if not q("nopowerstate"):
        dev["PowerState"] = tv["power"]
    support = {"FrameTVSupport": frame, "TokenAuthSupport": token, "remote_available": "true",
               "remote_voiceControl": "false"}
    return {"device": dev, "id": dev["id"], "isSupport": json.dumps(support) + "\n",
            "name": dev["name"], "remote": "1.0", "type": "Samsung SmartTV",
            "uri": f"http://{host}:8001/api/v2/", "version": "2.0.25"}

async def asleep():
    # an off TV doesn't answer; hold the request until the client gives up
    await asyncio.sleep(120)
    raise web.HTTPServiceUnavailable()

def awake_for_rest():
    return tv["power"] == "on" or (tv["power"] == "standby" and q("restinstandby"))

async def rest_info(request):
    if not awake_for_rest():
        log("rest: GET /api/v2/ (asleep, no answer)")
        await asleep()
    info = device_info(request)
    log("rest: GET /api/v2/ -> PowerState", info["device"].get("PowerState", "(absent)"))
    return web.json_response(info)

async def rest_app(request):
    if tv["power"] != "on":
        await asleep()
    app_id = request.match_info["app"]
    if app_id not in APPS:
        log("rest:", request.method, app_id, "-> 404")
        return web.json_response({"code": 404, "message": "Not found error.", "status": 404}, status=404)
    name, ver = APPS[app_id]
    if request.method == "POST":
        tv["app"] = app_id
        body = "true"
    elif request.method == "DELETE":
        if tv["app"] == app_id:
            tv["app"] = None
        body = "true"
    else:
        running = tv["app"] == app_id
        body = json.dumps({"id": app_id, "name": name, "running": running, "version": ver,
                           "visible": running})
    log("rest:", request.method, app_id, "->", body)
    return web.Response(text=body, content_type="application/json")

async def send(ws, channel, msg):
    text = json.dumps(msg)
    log(f"tx {channel}:", text[:300])
    await ws.send_str(text)

async def broadcast_art(inner):
    for ws, ch in list(sockets):
        if ch == "com.samsung.art-app" and not ws.closed:
            await send(ws, ch, d2d(inner))

def d2d(inner):
    return {"data": json.dumps(inner), "event": "d2d_service_message",
            "from": "7e7ef6f4-0000-4000-8000-0000000000aa"}

async def go_standby(reason):
    log("power: standby", f"({reason})")
    tv["power"], tv["app"] = "standby", None
    if q("frame"):
        tv["art"] = "off"
    for ws, ch in list(sockets):
        await ws.close()

async def boot():
    if tv["power"] == "on" or tv["booting"]:
        return
    tv["booting"] = True
    log(f"wol: booting, on in {WAKE_S:g} s")
    await asyncio.sleep(WAKE_S)
    tv["power"], tv["booting"] = "on", False
    log("power: on")

async def channel(request):
    name = request.match_info["channel"]
    secure = request.secure
    params = request.query
    if secure and q("notoken"):
        raise web.HTTPNotFound()
    if tv["power"] != "on" and not (tv["power"] == "standby" and q("restinstandby")):
        await asleep()
    ws = web.WebSocketResponse(autoping=True)
    await ws.prepare(request)
    log(f"ws open {name} secure={secure} token={'yes' if params.get('token') else 'no'}")
    try:
        if not await handshake(ws, name, secure, params):
            await ws.close()
            return ws
        sockets.add((ws, name))
        async for msg in ws:
            if msg.type != WSMsgType.TEXT:
                continue
            log(f"rx {name}:", msg.data[:300])
            try:
                await handle(ws, name, json.loads(msg.data))
            except (ValueError, KeyError) as e:
                await send(ws, name, {"data": {"message": f"bad request: {e}"}, "event": "ms.error"})
    finally:
        sockets.discard((ws, name))
        log(f"ws closed {name}")
    return ws

async def handshake(ws, name, secure, params):
    if tv["power"] != "on":
        await asyncio.sleep(1)
        await send(ws, name, {"event": "ms.channel.timeOut"})
        return False
    if name == "com.samsung.art-app":
        if q("frame2024") and params.get("token"):
            await asyncio.sleep(5)
            await send(ws, name, {"event": "ms.channel.timeOut"})
            return False
        await send(ws, name, {"data": {"clients": [], "id": str(uuid.uuid4())}, "event": "ms.channel.connect"})
        if q("frame"):
            await send(ws, name, {"data": {}, "event": "ms.channel.ready"})
        return True
    data = {"clients": [], "id": str(uuid.uuid4())}
    if secure and not q("notoken"):
        given = params.get("token")
        if not tv["token"] or given != tv["token"]:
            log("remote: unknown token, showing the allow dialog")
            await asyncio.sleep(2)
            if q("deny"):
                await send(ws, name, {"event": "ms.channel.unauthorized"})
                return False
            tv["token"] = str(random.randint(10000000, 99999999))
            open(TOKEN_FILE, "w").write(tv["token"])
        data["token"] = tv["token"]
    await send(ws, name, {"data": data, "event": "ms.channel.connect"})
    return True

async def handle(ws, name, msg):
    method, params = msg.get("method"), msg.get("params", {})
    if name == "samsung.remote.control" and method == "ms.remote.control":
        await key(params.get("Cmd"), params.get("DataOfCmd"))
    elif method == "ms.channel.emit" and params.get("event") == "ed.installedApp.get":
        if not q("noapplist"):
            apps = [{"appId": i, "app_type": 2, "icon": f"/opt/share/webappservice/apps_icon/FirstScreen/{i}/250x250.png",
                     "is_lock": 0, "name": n} for i, (n, _) in APPS.items()]
            await send(ws, name, {"data": {"data": apps}, "event": "ed.installedApp.get", "from": "host"})
    elif name == "com.samsung.art-app" and params.get("event") == "art_app_request":
        if q("frame"):
            await art(ws, name, params["data"])
    else:
        await send(ws, name, {"data": {"message": f"unrecognized method value : {method}"}, "event": "ms.error"})

power_press = {"at": None}

async def key(cmd, code):
    if code != "KEY_POWER":
        return
    if not q("frame"):
        if cmd in ("Click", "Press"):
            await asyncio.sleep(1)
            await go_standby("KEY_POWER")
        return
    if cmd == "Press":
        power_press["at"] = time.monotonic()
    elif cmd == "Release" and power_press["at"] is not None:
        held = time.monotonic() - power_press["at"]
        power_press["at"] = None
        # the TV's own threshold is unknown; the driver holds for 3 s
        if held >= 2.5:
            await go_standby(f"KEY_POWER held {held:.1f} s")
        else:
            await toggle_art()
    elif cmd == "Click":
        await toggle_art()

async def toggle_art():
    tv["art"] = "off" if tv["art"] == "on" else "on"
    log("art mode:", tv["art"])
    await broadcast_art({"event": "art_mode_changed", "status": tv["art"]})

async def art(ws, name, raw):
    req = json.loads(raw)
    # without a request_id the art app echoes the whole request string in its place
    rid = req.get("request_id", raw)
    request = req.get("request")
    if request == "get_artmode_status":
        await send(ws, name, d2d({"event": "artmode_status", "value": tv["art"], "id": req.get("id"), "request_id": rid}))
    elif request == "set_artmode_status":
        value = req.get("value")
        changed = value != tv["art"]
        tv["art"] = value
        log("art mode:", value)
        reply = {"event": "set_artmode_status", "status": value, "id": req.get("id"), "request_id": rid}
        if q("frame2024"):
            if changed:
                await broadcast_art({"event": "art_mode_changed", "status": value})
            await asyncio.sleep(10)
            await send(ws, name, d2d(reply))
        else:
            await send(ws, name, d2d(reply))
            if changed:
                await broadcast_art({"event": "art_mode_changed", "status": value})
    else:
        await send(ws, name, d2d({"event": "error", "request_id": rid, "request_data": raw, "error_code": "-7"}))

async def mock_state(request):
    return web.json_response({k: (sorted(v) if isinstance(v, set) else v) for k, v in tv.items() if k != "token"}
                             | {"paired": tv["token"] is not None, "sockets": sorted(ch for _, ch in sockets)})

async def mock_power(request):
    to = request.query.get("to")
    if to == "on":
        tv["power"] = "on"
        log("power: on (control)")
    elif to == "standby":
        await go_standby("control")
    else:
        raise web.HTTPBadRequest(text="to=on|standby")
    return await mock_state(request)

async def mock_quirks(request):
    try:
        set_quirks(request.query.get("set", ""))
    except ValueError as e:
        raise web.HTTPBadRequest(text=str(e))
    return await mock_state(request)

async def mock_unpair(request):
    tv["token"] = None
    if os.path.exists(TOKEN_FILE):
        os.remove(TOKEN_FILE)
    log("token: forgotten")
    return await mock_state(request)

class Wol(asyncio.DatagramProtocol):
    def datagram_received(self, data, addr):
        mac = bytes.fromhex(MAC.replace(":", ""))
        if data[:6] == b"\xff" * 6 and data[6:6 + 16 * 6] == mac * 16:
            log("wol: magic packet from", addr[0])
            asyncio.ensure_future(boot())

async def main():
    set_quirks(os.environ.get("MOCKTV_QUIRKS", ""))
    app = web.Application()
    app.router.add_get("/api/v2/", rest_info)
    app.router.add_route("*", "/api/v2/applications/{app}", rest_app)
    app.router.add_get("/api/v2/channels/{channel}", channel)
    app.router.add_get("/mock/state", mock_state)
    app.router.add_get("/mock/power", mock_power)
    app.router.add_get("/mock/quirks", mock_quirks)
    app.router.add_get("/mock/unpair", mock_unpair)
    runner = web.AppRunner(app, access_log=None)
    await runner.setup()
    sslctx = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH)
    sslctx.load_cert_chain(CERT, KEY)
    await web.TCPSite(runner, "0.0.0.0", 8001).start()
    await web.TCPSite(runner, "0.0.0.0", 8002, ssl_context=sslctx).start()
    loop = asyncio.get_running_loop()
    for port in (9, 7):
        await loop.create_datagram_endpoint(Wol, local_addr=("0.0.0.0", port), allow_broadcast=True)
    log(f"mock TV up: REST/ws 8001, wss 8002, WoL 9/7, MAC {MAC}")
    await asyncio.Event().wait()

if __name__ == "__main__":
    asyncio.run(main())
