#!/usr/bin/env bash
# Copyright 2026 hubitrep
# SPDX-License-Identifier: Apache-2.0
# TEST-LIVE: needs a reachable hub with the Samsung TV Remote Tester installed, its TV selected,
# and Script access turned on in the app.
#
# Runs the Samsung TV Remote Tester through its endpoints and prints one line per test.
# A test where the driver differs is [FAIL]; one where the TV differs, or a skipped test, is [WARN].
# Exit codes: 0 no driver difference, 1 a driver difference, 2 could not run.
#
# Usage:
#   HUBITAT_CONFIG=path/to/.hubitat.json bash tests/test-live-tv.sh [@hub] [<instance-id>]
#   TV_TESTS=1,2,3 bash tests/test-live-tv.sh            # a subset (the connection check always runs)
# Upper bound: 10 min (about 4 min with the driver polling every 60 s).
#
# The config is JSON: {"default_hub": "name", "hubs": {"name": {"hub_ip": "...", "username": "...", "password": "..."}}};
# username and password only for a hub with hub security.
set -euo pipefail
CONFIG_FILE="${HUBITAT_CONFIG:-.hubitat.json}"
HUB_NAME=""; INSTANCE_ID=""
for arg in "$@"; do
    if [[ "$arg" == @* ]]; then HUB_NAME="${arg#@}"; elif [[ "$arg" =~ ^[0-9]+$ ]]; then INSTANCE_ID="$arg"; fi
done
python3 - "$HUB_NAME" "$INSTANCE_ID" "$CONFIG_FILE" "${TV_TESTS:-}" <<'PYTHON_SCRIPT'
import json, re, sys, time, urllib.error, urllib.parse, urllib.request, http.cookiejar
hub_arg, inst_arg, config_file, tests = sys.argv[1:5]
passed = failed = warnings = 0
def ok(m):
    global passed; passed += 1; print(f"  [PASS] {m}")
def fail(m):
    global failed; failed += 1; print(f"  [FAIL] {m}")
def warn(m):
    global warnings; warnings += 1; print(f"  [WARN] {m}")
def info(m): print(f"  [INFO] {m}")
def done(code):
    print(f"\n{passed} passed, {failed} failed, {warnings} warnings"); sys.exit(code)

try:
    config = json.load(open(config_file))
except Exception as e:
    print(f"cannot read {config_file}: {e}"); sys.exit(2)
hub = config["hubs"].get(hub_arg or config.get("default_hub", ""))
if not hub: print("hub not found in config"); sys.exit(2)
ip = hub["hub_ip"]
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def get(path, timeout=30):
    return json.loads(opener.open(f"http://{ip}{path}", timeout=timeout).read().decode())
try:
    if hub.get("username") and hub.get("password"):
        opener.open(f"http://{ip}/login", urllib.parse.urlencode({"username": hub["username"], "password": hub["password"]}).encode(), timeout=10)
    inst = int(inst_arg) if inst_arg else None
    if not inst:
        def find(entries):
            for e in entries:
                d = e.get("data") or {}
                if isinstance(d, dict) and d.get("type") == "Samsung TV Remote Tester": return d.get("id")
                r = find(e.get("children") or [])
                if r: return r
        inst = find(get("/hub2/appsList").get("apps", []))
    if not inst: print("Samsung TV Remote Tester is not installed"); sys.exit(2)
    page = json.dumps(get(f"/installedapp/configure/json/{inst}"))
    m = re.search(r"access_token=([a-f0-9-]+)", page)
    if not m: print("no access token: turn on Script access in the app"); sys.exit(2)
except SystemExit:
    raise
except Exception as e:
    print(f"hub unreachable: {e}"); sys.exit(2)

token = m.group(1)
base = f"http://{ip}/apps/api/{inst}"
def api(path, timeout=30):
    req = urllib.request.Request(base + path, headers={"Authorization": f"Bearer {token}"})
    try:
        return json.loads(urllib.request.urlopen(req, timeout=timeout).read().decode())
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode() or "{}") | {"_status": e.code}

try:
    r = api("/run" + (f"?tests={tests}" if tests else ""))
    if r.get("_status") == 409: print("a run is already in progress"); sys.exit(2)
    if r.get("error"): print(f"run not started: {r['error']}"); sys.exit(2)
    deadline = time.time() + 10 * 60
    while time.time() < deadline:
        st = api("/status")
        if st.get("status") != "running": break
        time.sleep(5)
    else:
        api("/abort")   # leave nothing running, so the next invocation can start
        print("run did not finish in time; aborted it"); sys.exit(2)
    rep = api("/report")
except SystemExit:
    raise
except Exception as e:
    print(f"lost contact with the hub: {e}"); sys.exit(2)
info(f"run {rep.get('status')}" + (f": {rep.get('reason')}" if rep.get("reason") else ""))
for t in rep.get("results") or []:
    name = f"{t['test']}. {t['name']}"
    obs = " ".join(f"{k}={v}" for k, v in (t.get("observed") or {}).items())
    drv, tvd = t.get("driverDiffs") or [], t.get("tvDiffs") or []
    if t.get("skipped"): warn(f"{name}: skipped: {t['skipped']}")
    elif drv: fail(f"{name}: driver differs ({', '.join(drv)})" + (f"; TV differs ({', '.join(tvd)})" if tvd else "") + f" {obs}")
    elif tvd: warn(f"{name}: TV differs ({', '.join(tvd)}) {obs}")
    else: ok(f"{name} {obs}")
if rep.get("status") != "done": done(2)
done(1 if failed else 0)
PYTHON_SCRIPT
