# Mock Samsung Tizen TV for rehearsing the Hubitat driver.
# Serves REST and ws on 8001, wss on 8002, and listens for Wake-on-LAN on UDP 9 and 7.
# Message shapes follow replies captured from real TVs: the fixtures and art-channel code of
# xchwarze/samsung-tv-ws-api, the decompiled Frame notes in TheFab21/ha-samsungtv-smart, and a
# 2020 TU7000. No code is taken from either project.
#
# usage: [MOCKTV_PROFILE=<profile.json>] [MOCKTV_AIRPLAY_NAME=<name>] [MOCKTV_IP=<ipv4>] mocktv.py <cert.pem> <key.pem> <workdir>
# A profile describes a TV with the tester report's field names; mock-profiles/default.json fills
# what it leaves out. Control: GET http://<mock>:8001/mock/state, /mock/power?to=on|standby,
# /mock/profile?name=<file in mock-profiles, without .json>, /mock/unpair.
# With MOCKTV_AIRPLAY_NAME set it announces <name>._airplay._tcp over mDNS while on (needs zeroconf).
import asyncio, json, os, random, socket, ssl, sys, time, uuid
from aiohttp import web, WSMsgType

CERT, KEY, WORK = sys.argv[1], sys.argv[2], sys.argv[3]
PROFILE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mock-profiles")
MAC = os.environ.get("MOCKTV_MAC", "f4:7b:09:00:00:01")
UUID = "7e7ef6f4-0000-4000-8000-000000000001"
APPS = {"111299001912": ("YouTube", "2.1.498"), "3201907018807": ("Netflix", "5.2.59020")}

P = {}
tv = {"power": "on", "art": "off", "app": None, "token": None, "booting": False, "shutdownAt": None, "profile": None}
TOKEN_FILE = os.path.join(WORK, "token.txt")
if os.path.exists(TOKEN_FILE):
    tv["token"] = open(TOKEN_FILE).read().strip() or None
sockets = set()        # (ws, channel)
AIRPLAY_NAME = os.environ.get("MOCKTV_AIRPLAY_NAME")
mdns = {"zc": None, "info": None}

def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)

def load_profile(path):
    global P
    p = json.load(open(os.path.join(PROFILE_DIR, "default.json")))
    if path:
        over = json.load(open(path))
        # deviceInfo and isSupport merge key by key, so a profile lists only what differs; null removes a key
        for k in ("deviceInfo", "isSupport"):
            if k in over:
                p[k] = {kk: vv for kk, vv in {**p[k], **over.pop(k)}.items() if vv is not None}
        p.update(over)
    P = {k: v for k, v in p.items() if not k.startswith("_")}
    tv["art"] = P.get("statusReply") or "off"
    tv["profile"] = os.path.basename(path) if path else "default.json"
    log("profile:", tv["profile"], "-", P["deviceInfo"].get("modelName"))

def frame():
    return P["deviceInfo"].get("FrameTVSupport") == "true"

def token_auth():
    return P["deviceInfo"].get("TokenAuthSupport") == "true"

def reports_power():
    return "PowerState" in P["deviceInfo"]

def device_info(request):
    host = request.host.split(":")[0]
    dev = dict(P["deviceInfo"])
    dev.update({"duid": f"uuid:{UUID}", "id": f"uuid:{UUID}", "udn": f"uuid:{UUID}", "ip": host,
                "wifiMac": MAC, "name": "[TV] Mock", "ssid": "", "developerIP": "0.0.0.0"})
    if reports_power():
        # while shutting down the TU7000 still reports on (its report's wentAway has no "PowerState standby")
        state = "on" if tv["power"] == "on" or shutting_down() else "standby"
        if frame() and tv["art"] == "on" and P.get("powerStateInArt"):
            state = P["powerStateInArt"]
        dev["PowerState"] = state
    return {"device": dev, "id": dev["id"], "isSupport": json.dumps(P["isSupport"]) + "\n",
            "name": dev["name"], "remote": "1.0", "type": "Samsung SmartTV",
            "uri": f"http://{host}:8001/api/v2/", "version": P["apiVersion"]}

async def asleep():
    # an off TV doesn't answer; hold the request until the client gives up
    await asyncio.sleep(120)
    raise web.HTTPServiceUnavailable()

def shutting_down():
    return tv["shutdownAt"] is not None and time.monotonic() - tv["shutdownAt"] < (P.get("restAfterOffS") or 0)

# test 4 wentAway: "REST stopped" means it stops answering; otherwise it answers in standby
def rest_in_standby():
    return "REST stopped" not in (P.get("wentAway") or "REST stopped")

async def rest_info(request):
    if tv["power"] != "on" and not (rest_in_standby() or shutting_down()):
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
        await asyncio.sleep((P.get("launchMs") or 0) / 1000)
        tv["app"] = app_id
        body = "true"
    elif request.method == "DELETE":
        if tv["app"] == app_id:
            tv["app"] = None
        body = P.get("closeBody") or "true"
    else:
        running = tv["app"] == app_id
        body = json.dumps({"id": app_id, "name": name, "running": running, "version": ver, "visible": running})
    log("rest:", request.method, app_id, "->", body)
    return web.Response(text=body, content_type="application/json")

async def send(ws, channel, msg):
    text = json.dumps(msg)
    log(f"tx {channel}:", text[:300])
    await ws.send_str(text)

def d2d(inner):
    return {"data": json.dumps(inner), "event": "d2d_service_message", "from": "7e7ef6f4-0000-4000-8000-0000000000aa"}

async def broadcast_art(inner):
    for ws, ch in list(sockets):
        if ch == "com.samsung.art-app" and not ws.closed:
            await send(ws, ch, d2d(inner))

async def go_standby(reason):
    log("power: standby", f"({reason})")
    await unannounce()
    tv["power"], tv["app"], tv["shutdownAt"] = "standby", None, time.monotonic()
    if frame():
        tv["art"] = "off"
    for ws, ch in list(sockets):
        await ws.close()

async def boot():
    if tv["power"] == "on" or tv["booting"]:
        return
    tv["booting"] = True
    wake = float(P.get("bootS") or 5)
    log(f"wol: booting, on in {wake:g} s")
    await asyncio.sleep(wake)
    tv["power"], tv["booting"], tv["shutdownAt"] = "on", False, None
    log("power: on")
    await announce()

async def channel(request):
    name = request.match_info["channel"]
    secure = request.secure
    params = request.query
    if secure and not token_auth():
        raise web.HTTPNotFound()
    if tv["power"] != "on":
        # in standby a TV that still answers REST accepts the socket, then times out; one that doesn't hangs
        if not (rest_in_standby() and P.get("probeReply") == "ms.channel.timeOut"):
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

def extra_events(art):
    names = [n for n in (P.get("otherMessages") or "").split(",") if n]
    return [n[4:] for n in names if n.startswith("art:")] if art else [n for n in names if not n.startswith("art:")]

async def handshake(ws, name, secure, params):
    if tv["power"] != "on":
        await asyncio.sleep(1)
        await send(ws, name, {"event": "ms.channel.timeOut"})
        return False
    if name == "com.samsung.art-app":
        if P.get("artTokenStalls") and params.get("token"):
            await asyncio.sleep(5)
            await send(ws, name, {"event": "ms.channel.timeOut"})
            return False
        await send(ws, name, {"data": {"clients": [], "id": str(uuid.uuid4())}, "event": "ms.channel.connect"})
        if frame() and P.get("artReady", True):
            await send(ws, name, {"data": {}, "event": "ms.channel.ready"})
        for ev in extra_events(True):
            await send(ws, name, d2d({"event": ev}))
        return True
    data = {"clients": [], "id": str(uuid.uuid4())}
    if secure and token_auth():
        given = params.get("token")
        if not tv["token"] or given != tv["token"]:
            log("remote: unknown token, showing the allow dialog")
            await asyncio.sleep(2)
            if P.get("handshake") == "ms.channel.unauthorized":
                await send(ws, name, {"event": "ms.channel.unauthorized"})
                return False
            tv["token"] = str(random.randint(10000000, 99999999))
            open(TOKEN_FILE, "w").write(tv["token"])
        data["token"] = tv["token"]
    await asyncio.sleep((P.get("connectMs") or 0) / 1000)
    await send(ws, name, {"data": data, "event": "ms.channel.connect"})
    for ev in extra_events(False):
        await send(ws, name, {"data": {}, "event": ev})
    return True

async def handle(ws, name, msg):
    method, params = msg.get("method"), msg.get("params", {})
    if name == "samsung.remote.control" and method == "ms.remote.control":
        await key(params.get("Cmd"), params.get("DataOfCmd"))
    elif method == "ms.channel.emit" and params.get("event") == "ed.installedApp.get":
        if P.get("answered"):
            n = P.get("apps") or len(APPS)
            entries = list(APPS.items()) + [(f"3201{i:09d}", (f"App {i}", "1.0")) for i in range(max(0, n - len(APPS)))]
            apps = [{"appId": i, "app_type": 2, "icon": f"/opt/share/webappservice/apps_icon/FirstScreen/{i}/250x250.png",
                     "is_lock": 0, "name": nm} for i, (nm, _) in entries[:n]]
            await send(ws, name, {"data": {"data": apps}, "event": "ed.installedApp.get", "from": "host"})
    elif name == "com.samsung.art-app" and params.get("event") == "art_app_request":
        if frame():
            await art(ws, name, params["data"])
    else:
        await send(ws, name, {"data": {"message": f"unrecognized method value : {method}"}, "event": "ms.error"})

power_press = {"at": None}

async def key(cmd, code):
    if code != "KEY_POWER":
        return
    if not frame():
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
        # "not seen" in a report: the reply came after the driver closed the socket, so after the broadcast
        if P.get("setReply") in ("after broadcast", "not seen"):
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
    return web.json_response({k: v for k, v in tv.items() if k not in ("token", "shutdownAt")}
                             | {"paired": tv["token"] is not None, "sockets": sorted(ch for _, ch in sockets),
                                "model": P["deviceInfo"].get("modelName")})

async def mock_power(request):
    to = request.query.get("to")
    if to == "on":
        tv["power"], tv["shutdownAt"] = "on", None
        log("power: on (control)")
        await announce()
    elif to == "standby":
        await go_standby("control")
    else:
        raise web.HTTPBadRequest(text="to=on|standby")
    return await mock_state(request)

async def mock_profile(request):
    name = os.path.basename(request.query.get("name", "default"))
    path = os.path.join(PROFILE_DIR, f"{name}.json")
    if not os.path.exists(path):
        raise web.HTTPBadRequest(text=f"no profile {name}")
    load_profile(None if name == "default" else path)
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

def lan_ip():
    # the address of the interface on the default route; connect() on UDP sends nothing
    if os.environ.get("MOCKTV_IP"):
        return os.environ["MOCKTV_IP"]
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.0.2.1", 9))
        return s.getsockname()[0]
    finally:
        s.close()

async def announce():
    # real TVs' TXT deviceid isn't the MAC, so the mock's differs from it in the last byte
    if not AIRPLAY_NAME or mdns["info"]:
        return
    from zeroconf import IPVersion
    from zeroconf.asyncio import AsyncServiceInfo, AsyncZeroconf
    if not mdns["zc"]:
        mdns["zc"] = AsyncZeroconf(ip_version=IPVersion.V4Only)
    parts = MAC.split(":")
    deviceid = ":".join(parts[:-1] + [f"{int(parts[-1], 16) ^ 0xff:02x}"])
    ip = lan_ip()
    model = (P.get("airplay") or {}).get("model", "UMOCK")
    info = AsyncServiceInfo("_airplay._tcp.local.", f"{AIRPLAY_NAME}._airplay._tcp.local.", port=47941,
                            addresses=[socket.inet_aton(ip)],
                            properties={"manufacturer": "Samsung", "model": model, "deviceid": deviceid},
                            server=AIRPLAY_NAME.replace(" ", "-") + ".local.")
    # a name still held on the network gets a suffix, as real devices do
    await mdns["zc"].async_register_service(info, allow_name_change=True)
    mdns["info"] = info
    log(f"mdns: announcing {info.name.split('._airplay')[0]} at {ip}")

async def unannounce(close=False):
    if mdns["info"]:
        await (await mdns["zc"].async_unregister_service(mdns["info"]))
        mdns["info"] = None
        log("mdns: withdrawn")
    if close and mdns["zc"]:
        await mdns["zc"].async_close()
        mdns["zc"] = None

async def main():
    load_profile(os.environ.get("MOCKTV_PROFILE"))
    app = web.Application()
    app.router.add_get("/api/v2/", rest_info)
    app.router.add_route("*", "/api/v2/applications/{app}", rest_app)
    app.router.add_get("/api/v2/channels/{channel}", channel)
    app.router.add_get("/mock/state", mock_state)
    app.router.add_get("/mock/power", mock_power)
    app.router.add_get("/mock/profile", mock_profile)
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
    try:
        if tv["power"] == "on":
            await announce()
        await asyncio.Event().wait()
    finally:
        await unannounce(close=True)

if __name__ == "__main__":
    asyncio.run(main())
