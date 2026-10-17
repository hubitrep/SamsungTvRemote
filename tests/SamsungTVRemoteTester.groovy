// Copyright 2026 hubitrep
// SPDX-License-Identifier: Apache-2.0

/*
 Samsung TV Remote Tester: runs the Samsung TV Remote driver through a fixed set of tests against
 your TV and writes a report of where the TV differs from what the driver expects.
 */

definition(
    name: "Samsung TV Remote Tester",
    namespace: "hubitrep",
    author: "hubitrep",
    description: "Tests the Samsung TV Remote driver against your TV and writes a report to paste in the forum",
    category: "Utility",
    // Installed when first opened: an instance never saved with Done drops its runIn calls, and a run would hang.
    installOnOpen: true,
    importUrl: "https://raw.githubusercontent.com/hubitrep/SamsungTvRemote/main/tests/SamsungTVRemoteTester.groovy",
    iconUrl: "",
    iconX2Url: "",
    // One execution at a time: Abort or Done must not interleave with a step that is reading the log.
    singleThreaded: true
)

import groovy.transform.Field
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.text.SimpleDateFormat

@Field static final String CODE_VERSION = "0.2.0"

@Field static final String BUSY = "a run is in progress"

@Field static final Map TEST_NAMES = [
    0: "Connection", 1: "Device info", 2: "Remote socket", 3: "Key burst", 4: "Power off", 5: "Power on",
    6: "Standby check", 7: "Art mode", 8: "Apps", 9: "App list", 10: "Discovery"
]

// Run order: power last, so the TV ends on, and art mode before it, so the power cycle resets art mode.
// Discovery needs the TV on, so it runs before the power tests.
@Field static final List<Integer> ORDER = [1, 2, 3, 8, 9, 10, 7, 4, 6, 5]

// Device info fields that describe the model, not the user; the rest (ids, names, addresses) stay out of the report.
@Field static final List DEVICE_INFO_KEYS = ["FrameTVSupport", "GamePadSupport", "ImeSyncedSupport", "Language", "OS", "PowerState",
    "TokenAuthSupport", "VoiceSupport", "WallScreenRatio", "WallService", "description", "developerMode", "firmwareVersion",
    "model", "modelName", "networkType", "resolution", "smartHubAgreement", "type"]

// Events the driver acts on or deliberately ignores; anything else the TV sends is reported as otherMessages.
@Field static final List KNOWN_EVENTS = ["ms.channel.connect", "ms.channel.timeOut", "ms.channel.unauthorized", "ms.channel.ready",
    "d2d_service_message", "ed.installedApp.get", "ms.channel.clientConnect", "ms.channel.clientDisconnect",
    "ms.remote.touchEnable", "ms.remote.touchDisable"]
@Field static final List KNOWN_ART_EVENTS = ["artmode_status", "art_mode_changed", "set_artmode_status"]

// The device's attributes the run watches, to check what the driver made of the TV's messages.
@Field static final List TV_ATTRIBUTES = ["switch", "wsStatus", "artModeStatus", "appName", "appId"]

// What the driver assumes about a TV, and what it must do in answer. A field outside its rule is a
// difference. Fields marked driver: true judge the driver (its events, its sockets); the others judge
// the TV, where a difference describes the model and is often harmless.
@Field static final Map EXPECT = [
    0: [reachable: [eq: true]],
    1: [restStatus: [eq: 200], restMs: [max: 6000], powerStateReported: [eq: true]],
    2: [handshake: [eq: "ms.channel.connect"], connectMs: [max: 5000], keySent: [eq: true, driver: true, optional: true],
        openEvent: [eq: true, driver: true], otherMessages: [none: true]],
    3: [opens: [eq: 1, driver: true], keysSent: [eq: 3, driver: true], otherMessages: [none: true]],
    4: [offEvent: [eq: true, driver: true], noFlap: [eq: true, driver: true], offMs: [max: 30000, optional: true]],
    5: [onEvent: [eq: true, driver: true], onMs: [max: 45000]],
    6: [probeReply: [eq: "ms.channel.timeOut"], stayedOff: [eq: true, driver: true]],
    7: [artReady: [eq: true], statusReply: [ne: null], statusEventMatches: [eq: true, driver: true],
        setVia: [eq: "art channel", driver: true], otherMessages: [none: true]],
    8: [launchStatus: [eq: 200], closeStatus: [eq: 200], closeBody: [eq: "true"],
        appEvent: [eq: true, driver: true], closeEvent: [eq: true, driver: true]],
    9: [answered: [eq: true], otherMessages: [none: true]],
    10: [:]     // by model year, see discoveryExpect
]


preferences {
    page(name: "mainPage")
}

mappings {
    path("/run")    { action: [GET: "apiRun"] }
    path("/status") { action: [GET: "apiStatus"] }
    path("/report") { action: [GET: "apiReport"] }
    path("/abort")  { action: [GET: "apiAbort"] }
}

Map mainPage() {
    if (scriptAccess) checkOAuth() else dropAccessToken()
    checkStall()
    Map run = state.run as Map
    boolean running = run?.status == "running"
    // The page reloads itself while a run is in progress, so the table follows it.
    dynamicPage(name: "mainPage", title: "Samsung TV Remote Tester", install: true, uninstall: true,
                refreshInterval: running ? 5 : 0) {
        // Locked while a run is in progress: the run and its cleanup act on the device it started with.
        section("Device") {
            if (running) {
                paragraph "Testing <b>${escapeHtml(tv?.displayName ?: '?')}</b>. The device can be changed once the run ends."
            } else {
                input "tv", "device.SamsungTVRemote", title: "Samsung TV Remote device", required: true, submitOnChange: true
            }
        }
        section("Run") {
            paragraph "The tests change what is on the TV: they press HOME, launch YouTube or Netflix if the device knows its app code, " +
                      "switch a Frame to art mode, and turn the TV off and on. A run takes about 4 minutes with the driver's " +
                      "default 60 s polling, less with a shorter interval."
            if (state.startError) paragraph rawHtml: true, message("error", "Cannot start: ${escapeHtml(state.startError as String)}")
            input "btnRun", "button", title: "Run", disabled: running, width: 2,
                  inputClass: "p-button bg-hubitat-primary-green text-white"
            if (running) input "btnAbort", "button", title: "Abort", width: 2, inputClass: "p-button p-button-outlined"
            if (run?.tests) {
                paragraph rawHtml: true, summaryHtml(run)
                paragraph rawHtml: true, statusHtml(run)
            }
        }
        if (state.report) {
            section("Report: copy it into your forum post") {
                paragraph "<textarea readonly rows='30' style='width:100%;font-family:monospace'>${escapeHtml(state.report as String)}</textarea>"
            }
        }
        section("Script access") {
            input "scriptAccess", "bool", title: "Let a script run the tests (turns on OAuth for this app and makes an access token)",
                  defaultValue: false, submitOnChange: true
            if (scriptAccess && state.accessToken) paragraph "<a href='${getFullLocalApiServerUrl()}/report?access_token=${state.accessToken}' target='_blank'>Report as JSON</a>"
        }
        section("Logging") {
            input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
            input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        }
    }
}

void installed() { initialize() }

void uninstalled() {
    if ((state.run as Map)?.status == "running") finishRun("aborted", "the app was removed")
}

void updated() {
    if ((state.run as Map)?.status == "running") finishRun("aborted", "settings saved during the run")
    unsubscribe()
    unschedule()
    initialize()
}

void initialize() {
    checkVersion(false)
    if (scriptAccess) checkOAuth() else dropAccessToken()
    subscribe(location, "systemStart", "onSystemStart")
    if (debugEnable) runIn(1800, "logsOff")
}

void logsOff() {
    logWarn "Debug logging turned off"
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
}

boolean checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return false
    logVer "Version ${CODE_VERSION} (was ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
    return true
}

void onSystemStart(evt) {
    if ((state.run as Map)?.status == "running") finishRun("interrupted", "hub restart")
}

void appButtonHandler(String btn) {
    // Re-initialize now rather than in 1 s: the scheduled updated() would abort the run this press starts.
    if (checkVersion(false)) updated()
    if (btn == "btnRun") {
        String err = startRun([tests: null])
        if (err == BUSY) return              // a second press, or one from a stale page: the run goes on
        state.startError = err
        if (err) logWarn "Cannot start: ${err}"
    } else if (btn == "btnAbort") {
        finishRun("aborted", "aborted by the user")
    }
}

private String escapeHtml(String s) {
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;")
}

private String message(String severity, String html) {
    "<div class='p-message p-message-${severity}' style='margin:0'><div class='p-message-wrapper' style='padding:.6rem 1rem'>" +
        "<span class='p-message-text'>${html}</span></div></div>"
}

private String summaryHtml(Map run) {
    List<Map> rows = testRows(run, TEST_NAMES)
    int tvd = rows.count { it.state == "tv" } as int
    int drv = rows.count { it.state == "driver" } as int
    String when = run.startedAt ? new Date(run.startedAt as Long).format("MMM d HH:mm", location.timeZone) : ""
    switch (run.status) {
        case "running":
            String where = (run.idx as int) == 0 ? "checking the connection" : "test ${run.idx} of ${(run.tests as List).size() - 1}"
            return message("info", "Running since ${when}: ${where}. This page updates every 5 s.")
        case "done":
            if (drv) return message("error", "<b>Run of ${when}: the driver did not do what it should in ${drv} test${drv > 1 ? 's' : ''}.</b> " +
                                    "Please post the report in the forum thread.")
            if (tvd) return message("warn", "<b>Run of ${when}: your TV differs from what the driver assumes in ${tvd} test${tvd > 1 ? 's' : ''}.</b> " +
                                    "That is often harmless; the report tells us about your model.")
            return message("success", "<b>Run of ${when}: every test as expected.</b>")
        default:
            return message("error", "<b>Run of ${when} ${run.status}</b>: ${escapeHtml((run.reason ?: '') as String)}")
    }
}

@Field static final Map ROW_STYLE = [
    ok: ["✓", "text-green-600"], tv: ["⚠", "text-orange-500"], driver: ["✗", "text-red-600"], skipped: ["–", "text-color-secondary"],
    running: ["▶", "text-blue-500"], waiting: ["·", "text-color-secondary"], "not run": ["·", "text-color-secondary"]
]

private String statusHtml(Map run) {
    StringBuilder sb = new StringBuilder("<table style='width:100%;border-collapse:collapse'>")
    for (Map r : testRows(run, TEST_NAMES)) {
        List st = ROW_STYLE[r.state] as List
        sb << "<tr style='border-bottom:1px solid var(--surface-border,#ddd)'>"
        sb << "<td class='${st[1]}' style='width:1.6em;padding:.35rem 0;font-weight:bold;text-align:center'>${st[0]}</td>"
        sb << "<td style='width:1.8em;padding:.35rem 0' class='text-color-secondary'>${r.test}</td>"
        sb << "<td style='padding:.35rem .5rem'>${escapeHtml(r.name as String)}</td>"
        sb << "<td class='${st[1]}' style='padding:.35rem 0'>${escapeHtml(r.detail as String)}</td></tr>"
    }
    sb << "</table><div class='text-color-secondary' style='font-size:.85em;margin-top:.4rem'>" +
          "⚠ TV differs: your TV does something the driver doesn't assume, often harmless. " +
          "✗ driver differs: the driver didn't act on what the TV sent.</div>"
    return sb.toString()
}

private void dropAccessToken() {
    if (!state.accessToken) return
    try { revokeAccessToken() } catch (e) { state.remove("accessToken") }     // throws while OAuth is off
    logCfg "Script access turned off; the access token is revoked"
}


// ── Run control ───────────────────────────────────────────────────────

String startRun(Map opts) {
    checkStall()
    if ((state.run as Map)?.status == "running") return BUSY
    if (!tv) return "select the Samsung TV Remote device"
    if (!tv.getSetting("deviceIp")) return "set the TV IP address on the device first"
    String poll = tv.getSetting("pollInterval") as String
    // Power state comes only from the driver's polls (or SmartThings), so the run needs them on.
    if (poll == "off") return "set the device's Power Polling Interval to a number of seconds; the tests rely on its polls"
    int pollS = (poll ?: "60") as int
    List<Integer> tests = opts.tests ? (opts.tests as List<Integer>) : ORDER
    state.slowestReadMs = 0L
    state.readFailures = 0
    state.report = null
    state.startError = null
    state.run = [status: "running", reason: null, tests: [0] + tests, idx: 0, step: 0, results: [], events: [], startedAt: now(),
                 runSince: nowTs(),
                 pollS: pollS,
                 ctx: [startSwitch: tv.currentValue("switch"), startTrace: tv.getSetting("traceLog") == true,
                       startInfo: tv.getSetting("infoLog") != false, frame: tv.getDataValue("frameTv") == "true",
                       powerState: tv.getDataValue("powerStateReported") != "false"]]
    tv.updateSetting("traceLog", [value: "true", type: "bool"])
    tv.updateSetting("infoLog", [value: "true", type: "bool"])
    // filterEvents: false delivers repeats too: the driver sends switch on every poll.
    TV_ATTRIBUTES.each { subscribe(tv, it as String, "onTvEvent", [filterEvents: false]) }
    logCmd "Run started: tests ${tests.join(', ')}"
    next("runStep", 1, state.run as Map)
    return null
}

// Every run callback is scheduled here, so the run knows when its next step is due and a stall can be told from a wait.
private void next(String handler, int seconds, Map run) {
    run.nextDueMs = now() + seconds * 1000L
    state.run = run
    runIn(seconds, handler)
}

// Ends a run whose next step never came (the hub dropped the callback), so its cleanup still runs.
private void checkStall() {
    Map run = state.run as Map
    if (runStalled(run, now())) {
        logWarn "The run stopped making progress; ending it"
        finishRun("stalled", "its next step never ran")
    }
}

void finishRun(String status, String reason) {
    unschedule("runStep")
    unschedule("pollWait")
    unschedule("sleepDone")
    Map run = state.run as Map
    if (run?.status != "running") return       // a stale Abort after the run ended changes nothing
    run.status = status
    run.reason = reason
    state.run = run
    Map ctx = run.ctx as Map
    try {
        tv.updateSetting("traceLog", [value: ctx.startTrace ? "true" : "false", type: "bool"])
        tv.updateSetting("infoLog", [value: ctx.startInfo ? "true" : "false", type: "bool"])
        if (ctx.startSwitch == "off" && tv.currentValue("switch") == "on") tv.off()
    } catch (e) {
        logError "Cleanup failed: ${e.message}"
    }
    unsubscribe()
    subscribe(location, "systemStart", "onSystemStart")
    // An exception here would roll back this execution's state writes and leave the run "running".
    try {
        state.report = buildReport(state.run as Map)
    } catch (e) {
        logError "Report failed: ${e}"
        state.report = "Samsung TV Remote Tester report\nrun ${status}; the report could not be built: ${e.message}"
    }
    logCmd "Run ${status}${reason ? ': ' + reason : ''}"
}

void onTvEvent(evt) {
    Map run = state.run as Map
    if (run?.status != "running") return
    List ev = (run.events ?: []) as List
    if (ev.size() < 300) ev << [name: evt.name, value: evt.value, at: evt.date.time]
    run.events = ev
    state.run = run
}

// The driver's debugLogOff can turn trace off part-way through a run. Turn it back on and remember the
// test, whose differences may then come from missing lines.
private void keepTrace(Map run) {
    if (tv.getSetting("traceLog") == true) return
    tv.updateSetting("traceLog", [value: "true", type: "bool"])
    int test = run.tests[run.idx] as int
    List off = (run.traceOff ?: []) as List
    if (!(test in off)) off << test
    run.traceOff = off
    state.run = run
    logWarn "Trace logging was off during test ${test}; turned it back on"
}

String buildReport(Map run) {
    String warning = run.status != "done" ? "run ${run.status}: ${run.reason}" : null
    Map header = [appVersion: CODE_VERSION, driverVersion: tv.getDataValue("driverVersion"),
                  firmware: location.hub.firmwareVersionString,
                  modelName: tv.getDataValue("modelName"), platform: tv.getDataValue("platform"),
                  apiVersion: tv.getDataValue("apiVersion"), frameTv: tv.getDataValue("frameTv"),
                  tokenSupport: tv.getDataValue("tokenSupport"), powerStateReported: tv.getDataValue("powerStateReported"),
                  smartThings: tv.getSetting("connectST") == true, pollS: run.pollS,
                  slowestReadMs: state.slowestReadMs, status: run.status, warning: warning]
    return formatReport(header, run.results as List<Map>)
}


// ── Runner ────────────────────────────────────────────────────────────

private int pollSeconds(Map s) { pollGap((s.timeout ?: 0) as int, state.lastReadMs as Long) }

private String nowTs() { new Date().format("yyyy-MM-dd HH:mm:ss.SSS", location.timeZone) }

void runStep() {
    try {
        Map run = state.run as Map
        if (run?.status != "running") return
        keepTrace(run)
        int test = run.tests[run.idx] as int
        if ((run.step as int) == 0) {
            run.testSince = nowTs()
            run.testStartMs = now()
            run.stepResults = [:]
            // A 2025 Frame showing art reports standby, so its switch can read off before off() is sent.
            if (test == 4) run.ctx.alreadyOff = tv.currentValue("switch") == "off"
            String skip = skipReason(test, run)
            if (skip) {
                (run.results as List) << [test: test, name: TEST_NAMES[test], skipped: skip]
                advance(run)
                return
            }
        }
        List<Map> steps = stepsFor(test, run)
        if ((run.step as int) >= steps.size()) { state.run = run; finishTest(); return }
        Map s = steps[run.step as int]
        run.stepSince = s.sinceRun ? run.runSince : (s.sinceTest ? run.testSince : nowTs())
        run.stepStartMs = now()
        state.run = run
        if (s.sleep) { next("sleepDone", s.sleep as int, run); return }
        sendStep(s, run)
        if (!s.wait) { recordStep(s, true, null, null); return }
        next("pollWait", pollSeconds(s), run)
    } catch (e) {
        logError "Step failed: ${e}"
        finishRun("aborted", "error: ${e.message}")
    }
}

private void advance(Map run) {
    run.idx = (run.idx as int) + 1
    run.step = 0
    state.run = run
    if ((run.idx as int) >= (run.tests as List).size()) finishRun("done", null) else next("runStep", 2, run)
}

String skipReason(int test, Map run) {
    Map ctx = run.ctx as Map
    // Keys and the power-off need the remote socket the TV just refused.
    if (ctx.refused && test in [3, 4, 5, 6, 9]) return "the TV refused the remote connection (allow it on the TV, then run again)"
    switch (test) {
        case 6: return ctx.powerState ? "the TV reports PowerState, so the driver doesn't probe" : null
        case 7: return ctx.frame ? null : "not a Frame TV"
        case 8:
            // Any app the device has a code for exercises the same launch and close calls.
            ctx.app = ["YouTube", "Netflix"].find { appCode(it as String) }
            state.run = run
            return ctx.app ? null : "the device has no app code for YouTube or Netflix (turn on its app search, or link SmartThings)"
        default: return null
    }
}

// The driver keeps app names and codes in its state; read only that map, over loopback.
private String appCode(String name) {
    Map st = null
    try {
        httpGet([uri: "http://127.0.0.1:8080", path: "/device/fullJson/${tv.id}", timeout: 20]) { resp -> st = resp.data?.deviceState as Map }
    } catch (e) {
        logWarn "Could not read the device's app codes: ${e.message}"
        return null
    }
    def apps = st?.appData
    if (apps instanceof String) apps = new JsonSlurper().parseText(apps as String)
    Map.Entry hit = (apps instanceof Map) ? (apps as Map).find { (it.key as String).toLowerCase().contains(name.toLowerCase()) } : null
    return hit ? hit.value as String : null
}

void sendStep(Map s, Map run) {
    if (!s.cmd) return
    List args = (s.args ?: []) as List
    switch (s.cmd) {
        case "on": tv.on(); break
        case "off": tv.off(); break
        case "close": tv.close(); break
        case "refresh": tv.refresh(); break
        case "home": tv.home(); break
        case "burst": 3.times { tv.home() }; break
        case "configure": tv.configure(); break
        case "artMode": tv.artMode(); break
        case "appOpenByName": tv.appOpenByName(args[0] as String); break
        case "appClose": tv.appClose(); break
        case "getInstalledApps": tv.getInstalledApps(); break
        case "findTvs": tv.findTvs(); break
        default: throw new IllegalArgumentException("unknown step command ${s.cmd}")
    }
}

void pollWait() {
    try {
        Map run = state.run as Map
        if (run?.status != "running") return
        keepTrace(run)
        Map s = stepsFor(run.tests[run.idx] as int, run)[run.step as int]
        List<Map> lines = readTvLines(run.stepSince as String)
        if (lines == null) {
            if (((state.readFailures ?: 0) as int) >= 3) { finishRun("aborted", "could not read the hub logs"); return }
            next("pollWait", pollSeconds(s), run)
            return
        }
        Map hit = lines.find { (it.msg as String) =~ (s.wait as String) }
        if (hit) { recordStep(s, true, tsMs(hit.ts as String, location.timeZone) - (run.stepStartMs as Long), null); return }
        if (now() - (run.stepStartMs as Long) > ((s.timeout as Long) * 1000L)) { recordStep(s, false, null, "timed out"); return }
        next("pollWait", pollSeconds(s), run)
    } catch (e) {
        logError "Wait failed: ${e}"
        finishRun("aborted", "error: ${e.message}")
    }
}

void sleepDone() {
    Map run = state.run as Map
    if (run?.status != "running") return
    recordStep(stepsFor(run.tests[run.idx] as int, run)[run.step as int], true, null, null)
}

void recordStep(Map s, boolean ok, Long ms, String note) {
    Map run = state.run as Map
    if (run?.status != "running") return
    (run.stepResults as Map)[s.name] = [ok: ok, ms: ms, at: run.stepStartMs, note: note]
    run.step = (run.step as int) + 1
    state.run = run
    next("runStep", Math.max(1, (s.after ?: 1) as int), run)
}

List<Map> readTvLines(String since) {
    long t0 = now()
    List raw = null
    try {
        httpGet([uri: "http://127.0.0.1:8080", path: "/logs/past/json", timeout: 30]) { resp -> raw = resp.data as List }
    } catch (e) {
        state.readFailures = ((state.readFailures ?: 0) as int) + 1
        logWarn "Log read failed: ${e.message}"
        return null
    }
    state.readFailures = 0
    long ms = now() - t0
    state.lastReadMs = ms
    if (ms > ((state.slowestReadMs ?: 0L) as Long)) state.slowestReadMs = ms
    return deviceLines(raw, tv.idAsLong, since)
}

void finishTest() {
    Map run = state.run as Map
    int test = run.tests[run.idx] as int
    Map steps = run.stepResults as Map
    // Device info reuses the poll the connection check already saw.
    List<Map> lines = readTvLines((test == 1 ? run.runSince : run.testSince) as String) ?: []
    run = state.run as Map
    List<Map> events = ((run.events ?: []) as List<Map>).findAll { (it.at as Long) >= (run.testStartMs as Long) }
    if (test == 0) {
        Map f = evalConnection(lines, steps)
        List d = differences(f, EXPECT[0] as Map)
        if (d) {
            (run.results as List) << ([test: 0, name: TEST_NAMES[0], observed: f, diffs: d, trace: traceLines(lines)] + splitDiffs(d, EXPECT[0] as Map))
            state.run = run
            finishRun("aborted", "the TV did not answer")
            return
        }
    } else {
        // Device info and the art test read /api/v2/ directly: the driver's trace has only its key names.
        Map info = (test in [1, 7, 10]) ? fetchDeviceInfo() : null
        run = state.run as Map
        Map r = evaluateTest(test, lines, steps, run, events, info)
        if (test == 1 && info) r.observed = (r.observed as Map) + deviceInfoFields(info, DEVICE_INFO_KEYS)
        if (test == 7 && info) r.observed = (r.observed as Map) + [powerStateInArt: (info.device as Map)?.PowerState ?: (info.status == 200 ? "absent" : "no answer")]
        r.samples = sampleLines(test, lines)
        if (test == 2 && (r.observed as Map)?.handshake == "ms.channel.unauthorized") run.ctx.refused = true
        if (test in ((run.traceOff ?: []) as List)) r.note = "trace logging was off for part of this test, so lines may be missing"
        (run.results as List) << r
    }
    advance(run)
}

private Map fetchDeviceInfo() {
    Map out = [status: null]
    try {
        httpGet([uri: "http://${tv.getSetting('deviceIp')}:8001", path: "/api/v2/", timeout: 6]) { resp ->
            out.status = resp.status
            def d = resp.data
            if (d instanceof Map) { out.device = d.device; out.version = d.version; out.isSupport = d.isSupport }
        }
    } catch (e) {
        out.status = "no answer"
    }
    return out
}

Map evaluateTest(int test, List<Map> lines, Map steps, Map run, List<Map> events, Map info = null) {
    Map r = [test: test, name: TEST_NAMES[test]]
    TimeZone tz = location.timeZone
    Map f
    switch (test) {
        case 1: f = evalDeviceInfo(lines, tv.getDataValue("powerStateReported")); break
        case 2: f = evalRemoteSocket(lines, events, tz); break
        case 3: f = evalBurst(lines); break
        case 4: f = evalPowerOff(lines, events, steps, run.testStartMs as Long, tz, run.ctx.alreadyOff == true); break
        case 5: f = evalPowerOn(lines, events, steps, run.testStartMs as Long, tz); break
        case 6: f = evalStandby(lines, events); break
        case 7: f = evalArt(lines, events); break
        case 8: f = evalApps(lines, events, steps); break
        case 9: f = evalAppList(lines); break
        case 10: f = evalDiscovery(lines, tv.getSetting("deviceIp") as String); break
        default: f = [:]
    }
    String other = otherMessages(lines, KNOWN_EVENTS, KNOWN_ART_EVENTS)
    if (other) f.otherMessages = other
    r.observed = f
    // Discovery is judged by the TV's model year, read from its REST model field.
    Map expect = (test == 10) ? discoveryExpect(modelYear((info?.device as Map)?.model as String)) : (EXPECT[test] as Map)
    r.diffs = differences(f, expect)
    r.putAll(splitDiffs(r.diffs as List, expect))
    if (r.diffs) r.trace = traceLines(lines)
    return r
}

List<Map> stepsFor(int test, Map run) {
    int poll = (run.pollS ?: 60) as int
    switch (test) {
        case 0:
            // A TV that is off gets Wake-on-LAN; it then answers a poll within about 30 s of booting.
            if (run.ctx.startSwitch == "off") return [[name: "powerOn", cmd: "on", wait: '^rest: status=200', timeout: poll + 60, after: 3]]
            // Refresh polls at once; a driver older than that is caught by the scheduled poll.
            return [[name: "poll", cmd: "refresh", wait: '^rest: status=', timeout: poll + 15]]
        case 1: return [[name: "poll", wait: '^rest: status=', timeout: poll + 15, sinceRun: true]]
        case 2: return [[name: "close", cmd: "close", after: 2],
                        [name: "home", cmd: "home", wait: '^rx remote: .*"ms\\.channel\\.(connect|unauthorized|timeOut)"', timeout: 15, after: 2]]
        case 3: return [[name: "close", cmd: "close", after: 2],
                        [name: "burst", cmd: "burst", wait: '^tx remote: .*KEY_HOME', timeout: 15],
                        [name: "settle", sleep: 4]]
        case 4: return [[name: "off", cmd: "off", wait: run.ctx.alreadyOff ? '^off: ' : '^setPowerState: \\[switch: off\\]', timeout: poll + 20],
                        [name: "hold", sleep: poll + 10]]
        case 5: return [[name: "on", cmd: "on", wait: '^setPowerState: \\[switch: on\\]', timeout: Math.max(60, poll + 30), after: 5]]
        case 6: return [[name: "probe", cmd: "refresh", wait: '^rx remote: .*"ms\\.channel\\.timeOut"|^ws remote: (failure|status: open)', timeout: poll + 20],
                        [name: "settle", sleep: 5]]
        case 7: return [[name: "status", cmd: "configure", wait: '^rx frameArt: .*artmode_status', timeout: 20, after: 2],
                        [name: "set", cmd: "artMode", wait: '^rx frameArt: .*(art_mode_changed|set_artmode_status)|^tx remote: .*KEY_POWER|already set', timeout: 20, after: 3]]
        case 8: return [[name: "launch", cmd: "appOpenByName", args: [run.ctx.app ?: "YouTube"], wait: '^app: POST', timeout: 25, after: 5],
                        [name: "close", cmd: "appClose", wait: '^app: DELETE', timeout: 15, after: 2]]
        case 9: return [[name: "list", cmd: "getInstalledApps", wait: '^rx remote: .*ed\\.installedApp\\.get', timeout: 12]]
        // The search starts after 1 s and reports its outcome at 7 s.
        case 10: return [[name: "find", cmd: "findTvs", after: 12]]
        default:
            return []
    }
}


// ── Script endpoints ──────────────────────────────────────────────────

def apiRun() {
    if (checkVersion(false)) updated()     // as in appButtonHandler
    List<Integer> tests = params.tests ? (params.tests as String).tokenize(",").collect { it.trim() as Integer } : null
    String err = startRun([tests: tests])
    if (err) return render(status: err.contains("in progress") ? 409 : 400, contentType: "application/json", data: JsonOutput.toJson([error: err]))
    return render(status: 200, contentType: "application/json", data: JsonOutput.toJson([started: true]))
}

def apiStatus() {
    checkVersion()
    checkStall()
    Map run = (state.run ?: [:]) as Map
    Integer test = run.tests ? (run.tests[run.idx as int] as Integer) : null
    return render(status: 200, contentType: "application/json",
                  data: JsonOutput.toJson([status: run.status, test: test, idx: run.idx, of: (run.tests as List)?.size(), step: run.step]))
}

def apiAbort() {
    checkVersion()
    finishRun("aborted", "aborted by the script")
    return render(status: 200, contentType: "application/json", data: JsonOutput.toJson([status: (state.run as Map)?.status]))
}

def apiReport() {
    checkVersion()
    Map run = (state.run ?: [:]) as Map
    return render(status: 200, contentType: "application/json",
                  data: JsonOutput.toJson([status: run.status, reason: run.reason, report: state.report, results: run.results]))
}


// ── OAuth ─────────────────────────────────────────────────────────────

private boolean autoEnableOAuth() {
    String typeId = app.getAppTypeId()?.toString()
    if (!typeId) { log.error "Could not find app type ID."; return false }

    String internalVer = null
    try {
        httpGet([uri: "http://127.0.0.1:8080", path: "/app/ajax/code", query: [id: typeId], timeout: 15]) { resp ->
            internalVer = resp.data?.version?.toString()
        }
    } catch (e) {
        log.error "Failed to fetch app code version: ${e.message}"
        return false
    }
    if (!internalVer) { log.error "Could not determine app code version."; return false }

    boolean success = false
    try {
        httpPost([
            uri: "http://127.0.0.1:8080",
            path: "/app/edit/update",
            requestContentType: "application/x-www-form-urlencoded",
            body: [
                id: typeId,
                version: internalVer,
                oauthEnabled: "true",
                _action_update: "Update"
            ],
            timeout: 20
        ]) { resp ->
            success = true
        }
    } catch (e) {
        log.error "Failed to enable OAuth: ${e.message}"
    }
    return success
}

private boolean checkOAuth() {
    if (state.accessToken) return true
    try {
        createAccessToken()
        return (state.accessToken != null)
    } catch (e) {
        log.debug "OAuth not enabled yet, attempting auto-enable..."
        if (autoEnableOAuth()) {
            try {
                createAccessToken()
                return (state.accessToken != null)
            } catch (e2) {
                log.error "OAuth enabled but token creation failed: ${e2.message}"
                return false
            }
        }
        return false
    }
}


// ── Pure helpers (extracted and tested by test-tester-helpers.groovy) ─

// One /logs/past/json line: "{ts}\t{LEVEL}\tdev|{id}|{name}|{label}-{version}: {message}". Null unless it is the given device's.
Map parseLogLine(String raw, Long devId) {
    if (raw == null) return null
    String[] parts = raw.split("\t", 3)
    if (parts.length < 3 || parts[0].length() != 23) return null
    String[] src = parts[2].split("\\|", 4)
    if (src.length < 4 || src[0] != "dev" || src[1] != devId.toString()) return null
    String msg = src[3]
    // The driver prefixes each line with its label and version: "<label>-2.4.2: "
    def m = msg =~ /^.*?-\d+(?:\.\d+)+[a-z]?: /
    if (m.find()) msg = msg.substring(m.end())
    return [ts: parts[0], level: parts[1].trim().toLowerCase(), msg: msg]
}

// The buffer is not in time order, so filter by timestamp and sort.
List<Map> deviceLines(List raw, Long devId, String since) {
    List<Map> out = []
    for (Object r : raw) {
        Map m = parseLogLine(r as String, devId)
        if (m != null && (m.ts as String) >= since) out << m
    }
    return out.sort { Map a, Map b -> (a.ts as String) <=> (b.ts as String) }
}

Long tsMs(String ts, TimeZone tz) {
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
    f.setTimeZone(tz)
    return f.parse(ts).time
}

// Masks what identifies the user or their network: IP and MAC addresses, uuids, names the TV reports
// (its own, the remotes connected to it), long hex, and any token the driver didn't already mask.
String maskLine(String s) {
    if (s == null) return null
    String out = s.replaceAll(/\b(?:\d{1,3}\.){3}\d{1,3}\b/, "<ip>")
    out = out.replaceAll(/\b[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}\b/, "<mac>")
    out = out.replaceAll(/\b[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\b/, "<uuid>")
    out = out.replaceAll(/"(name|deviceName|ssid)"\s*:\s*"[^"]*"/, '"$1":"<name>"')
    out = out.replaceAll(/"token"\s*:\s*"?[^",\x7D<]+"?/, '"token":"<token>"')
    out = out.replaceAll(/(?:0x)?[0-9A-Fa-f]{33,}/, "<hex>")
    return out
}

// Protocol lines only (the driver's trace), masked and cut to a readable length.
List<String> traceLines(List<Map> lines) {
    List<String> out = []
    for (Map l : lines) {
        if (l.level != "trace" && !(l.level in ["warn", "error"])) continue
        String msg = l.msg as String
        if (msg.startsWith("rest: ")) msg = msg.replaceAll(' keys=.*', '')
        if (msg.startsWith("mdns: ")) msg = msg.replaceFirst(/^mdns: name=.*? ip=/, "mdns: name=<name> ip=")
        out << "${(l.ts as String).substring(11)} ${maskLine(msg).take(300)}".toString()
        if (out.size() >= 40) break
    }
    return out
}

private Map lastRest(List<Map> lines) {
    Map rest = null
    for (Map l : lines) {
        def m = (l.msg as String) =~ /^rest: status=(\d+) ms=(\S+) PowerState=(\S+) keys=(.*)/
        if (m.find()) rest = [status: m.group(1) as Integer, ms: m.group(2).isNumber() ? (m.group(2) as Long) : null,
                              powerState: m.group(3), keys: m.group(4)]
    }
    return rest
}

private Map firstLine(List<Map> lines, String re) { lines.find { (it.msg as String) =~ re } }

private int countLines(List<Map> lines, String re) { lines.count { (it.msg as String) =~ re } as int }

boolean hasEvent(List<Map> events, String name, String value) {
    events.any { it.name == name && (value == null || it.value == value) }
}

String lastEvent(List<Map> events, String name) {
    List<Map> e = events.findAll { it.name == name }
    return e ? e[-1].value as String : null
}

// Reached: the TV answered a poll (after Wake-on-LAN when it was off). Otherwise the driver's first warning or error.
Map evalConnection(List<Map> lines, Map steps) {
    Map rest = lastRest(lines)
    Map bad = lines.find { it.level in ["warn", "error"] }
    return [reachable: rest?.status == 200, restStatus: rest?.status, wolSent: lines.any { (it.msg as String).startsWith("wol: sent") },
            firstError: bad ? maskLine(bad.msg as String).take(200) : null]
}

Map evalDeviceInfo(List<Map> lines, String powerStateReported) {
    Map rest = lastRest(lines)
    List<String> keys = rest?.keys ? (rest.keys as String).tokenize(",") : []
    return [restStatus: rest?.status, restMs: rest?.ms, powerState: rest?.powerState,
            powerStateReported: rest ? rest.powerState != "absent" : (powerStateReported != "false"),
            keyCount: keys.size(), frameKey: keys.contains("FrameTVSupport"), tokenKey: keys.contains("TokenAuthSupport")]
}

// The remote handshake: what the TV answered to the connect, how fast, and whether the key went out after it.
Map evalRemoteSocket(List<Map> lines, List<Map> events, TimeZone tz) {
    Map open = firstLine(lines, '^ws remote: status: open')
    Map reply = firstLine(lines, '^rx remote: .*"ms\\.channel\\.(connect|unauthorized|timeOut)"')
    String handshake = null
    if (reply) {
        def m = (reply.msg as String) =~ /"(ms\.channel\.(?:connect|unauthorized|timeOut))"/
        if (m.find()) handshake = m.group(1)
    }
    Long ms = (open && reply) ? tsMs(reply.ts as String, tz) - tsMs(open.ts as String, tz) : null
    Map f = [handshake: handshake, connectMs: ms, tokenInReply: reply ? (reply.msg as String).contains('"token"') : null,
             openEvent: hasEvent(events, "wsStatus", "open")]
    // A key can only go out after the TV accepted the connection.
    if (handshake == "ms.channel.connect") f.keySent = firstLine(lines, '^tx remote: .*KEY_HOME') != null
    return f
}

Map evalBurst(List<Map> lines) {
    return [opens: countLines(lines, '^ws remote: status: open'), keysSent: countLines(lines, '^tx remote: .*KEY_HOME')]
}

// How the TV went away after off: REST stopped answering, it reported standby, or its socket timed out.
Map evalPowerOff(List<Map> lines, List<Map> events, Map steps, Long startMs, TimeZone tz, boolean alreadyOff) {
    List<String> how = []
    if (lines.any { (it.msg as String) =~ /^rest: status=(?!200)/ }) how << "REST stopped"
    if (lines.any { (it.msg as String) =~ /^rest: status=200 .*PowerState=standby/ }) how << "PowerState standby"
    if (lines.any { (it.msg as String) =~ /^rx remote: .*ms\.channel\.timeOut/ }) how << "socket timeout"
    // The driver sends switch on every poll, so an off TV still produces off events.
    Map off = events.find { it.name == "switch" && it.value == "off" }
    boolean flap = off != null && events.any { it.name == "switch" && it.value == "on" && (it.at as Long) > (off.at as Long) }
    // The last answer after off, to the poll: how long the TV keeps answering REST once told to turn off.
    Map last = lines.reverse().find { (it.msg as String).startsWith("rest: status=200") }
    Long after = last ? Math.round((tsMs(last.ts as String, tz) - startMs) / 1000.0d) : 0L
    Map f = [offEvent: off != null, noFlap: !flap, wentAway: how ? how.join(", ") : "still answering", restAfterOffS: after]
    if (alreadyOff) f.alreadyOff = true else f.offMs = steps.off?.ok ? steps.off.ms : null
    return f
}

Map evalPowerOn(List<Map> lines, List<Map> events, Map steps, Long startMs, TimeZone tz) {
    Map rest = lines.find { (it.msg as String).startsWith("rest: status=200") }
    return [onEvent: hasEvent(events, "switch", "on"), onMs: steps.on?.ok ? steps.on.ms : null,
            wolSends: countLines(lines, '^wol: sent'), firstAnswerMs: rest ? tsMs(rest.ts as String, tz) - startMs : null]
}

// A TV without PowerState is probed by opening its remote socket; in standby it should answer timeOut.
Map evalStandby(List<Map> lines, List<Map> events) {
    String reply = null
    if (firstLine(lines, '^rx remote: .*ms\\.channel\\.timeOut')) reply = "ms.channel.timeOut"
    else if (firstLine(lines, '^rx remote: .*ms\\.channel\\.connect')) reply = "ms.channel.connect"
    else if (firstLine(lines, '^ws remote: failure')) reply = "connect failed"
    return [probeReply: reply, stayedOff: !hasEvent(events, "switch", "on")]
}

// Art channel: handshake, the status reply and the event from it, and how artMode set it.
Map evalArt(List<Map> lines, List<Map> events) {
    String status = null
    for (Map l : lines) {
        def m = (l.msg as String) =~ /^rx frameArt: .*\\"event\\": ?\\"artmode_status\\".*?\\"value\\": ?\\"(\w+)\\"/
        if (m.find()) { status = m.group(1); break }
    }
    String setVia = "none"
    if (firstLine(lines, '^tx frameArt: .*set_artmode_status')) setVia = "art channel"
    else if (firstLine(lines, '^tx remote: .*KEY_POWER')) setVia = "power key"
    else if (firstLine(lines, 'artMode already set')) setVia = "art channel"      // already on: nothing to send
    // Some Frames send the set reply after the art_mode_changed broadcast, seconds later.
    int setAt = lines.findIndexOf { (it.msg as String) =~ /^rx frameArt: .*set_artmode_status/ }
    int castAt = lines.findIndexOf { (it.msg as String) =~ /^rx frameArt: .*art_mode_changed/ }
    // The driver closes the art socket 2 s after the status, so a reply later than that is "not seen".
    String setReply = setAt >= 0 ? (castAt >= 0 && castAt < setAt ? "after broadcast" : "before broadcast") : (castAt >= 0 ? "not seen" : "none")
    String ev = lastEvent(events.findAll { it.name == "artModeStatus" } as List<Map>, "artModeStatus")
    boolean matches = status == null || events.any { it.name == "artModeStatus" && it.value == status }
    return [artReady: firstLine(lines, '^rx frameArt: .*ms\\.channel\\.ready') != null, statusReply: status,
            statusEventMatches: matches, artModeAfter: ev, setVia: setVia, setReply: setReply]
}

Map evalApps(List<Map> lines, List<Map> events, Map steps) {
    Integer launch = null, close = null
    String closeBody = null
    for (Map l : lines) {
        def p = (l.msg as String) =~ /^app: POST \S+ status=(\d+)/
        if (p.find() && launch == null) launch = p.group(1) as Integer
        def d = (l.msg as String) =~ /^app: DELETE \S+ status=(\d+) body=(.*)/
        if (d.find() && close == null) { close = d.group(1) as Integer; closeBody = d.group(2).trim() }
    }
    // The launch is a synchronous call, so the step time is how long the TV took to answer it.
    return [launchStatus: launch, launchMs: steps.launch?.ok ? steps.launch.ms : null, closeStatus: close, closeBody: closeBody,
            appEvent: events.any { it.name == "appName" && (it.value as String)?.trim() },
            closeEvent: events.any { it.name == "appName" && (it.value as String) == " " }]
}

Map evalAppList(List<Map> lines) {
    Map l = firstLine(lines, '^rx remote: .*ed\\.installedApp\\.get')
    Integer count = null
    if (l) {
        try {
            def j = new JsonSlurper().parseText((l.msg as String).substring("rx remote: ".length()))
            def list = j?.data instanceof Map ? j.data.data : null
            count = list instanceof List ? (list as List).size() : null
        } catch (e) { }
    }
    return [answered: l != null, apps: count]
}

// The driver's mDNS trace lines against this TV's address. Only the model and the TXT key names are kept.
Map evalDiscovery(List<Map> lines, String ip) {
    List<Map> found = lines.findAll { (it.msg as String).startsWith("mdns: ") }
    if (!found) {
        boolean none = lines.any { (it.msg as String).startsWith("No Samsung TV answered") }
        return [result: none ? "none" : "unsupported"]
    }
    for (Map l : found) {
        def m = (l.msg as String) =~ /^mdns: name=.*? ip=(\S+) manufacturer=.*? model=(.*?) keys=(.*)$/
        if (m.find() && m.group(1) == ip) return [result: "found", model: m.group(2), keys: m.group(3)]
    }
    return [result: "other"]
}

// The model year from the REST model field ("20_KANTSU2_UHD_BASIC" is 2020); null when it can't be read.
Integer modelYear(String model) {
    def m = (model ?: "") =~ /^(\d{2})_/
    return m.find() ? 2000 + (m.group(1) as int) : null
}

// TVs from 2018 on announce AirPlay; earlier ones don't. An unknown year sets no expectation.
Map discoveryExpect(Integer year) {
    if (year == null) return [:]
    return [result: [eq: year < 2018 ? "none" : "found"]]
}

// The model-describing part of a direct /api/v2/ read, under the names a mock profile uses.
Map deviceInfoFields(Map info, List keys) {
    Map dev = (info?.device ?: [:]) as Map
    Map kept = [:]
    keys.each { k -> if (dev.containsKey(k)) kept[k] = dev[k] }
    Map support = [:]
    def s = info?.isSupport
    if (s instanceof String) { try { s = new JsonSlurper().parseText((s as String).trim()) } catch (e) { s = null } }
    if (s instanceof Map) support = s as Map
    return [deviceInfo: kept, isSupport: support, apiVersion: info?.version, deviceKeys: dev.keySet().sort().join(",")]
}

// Event names the TV sent that the driver neither acts on nor deliberately ignores; art events as "art:<name>".
String otherMessages(List<Map> lines, List known, List knownArt) {
    Set<String> out = new LinkedHashSet<String>()
    for (Map l : lines) {
        String msg = l.msg as String
        if (!(msg.startsWith("rx remote: ") || msg.startsWith("rx frameArt: "))) continue
        def m = msg =~ /"event":\s*"([^"]+)"\s*[,\x7D]/
        String outer = null
        while (m.find()) outer = m.group(1)            // the outer event comes last in the TV's JSON
        if (outer == null) continue
        if (!(outer in known)) out << outer
        if (outer == "d2d_service_message") {
            def a = msg =~ /\\"event\\": ?\\"(\w+)\\"/
            if (a.find() && !(a.group(1) in knownArt)) out << "art:${a.group(1)}".toString()
        }
    }
    return out.join(",")
}

// The protocol lines a maintainer needs to build a mock profile, kept even when the test passes.
List<String> sampleLines(int test, List<Map> lines) {
    String re = [2: '^rx remote: .*"ms\\.channel\\.(connect|unauthorized|timeOut)"', 7: '^rx frameArt: ',
                 9: '^rx remote: .*ed\\.installedApp\\.get', 6: '^rx remote: '][test]
    if (!re) return []
    List<String> out = []
    for (Map l : lines) {
        if (!((l.msg as String) =~ re)) continue
        out << "${(l.ts as String).substring(11)} ${maskLine(l.msg as String).take(400)}".toString()
        if (out.size() >= 6) break
    }
    return out
}

// Stalled: running, and the next callback is over a minute late. The minute covers a slow step and a busy hub.
boolean runStalled(Map run, long nowMs) {
    return run?.status == "running" && run.nextDueMs != null && nowMs > (run.nextDueMs as Long) + 60000L
}

Map splitDiffs(List diffs, Map expect) {
    List drv = diffs.findAll { (expect[it] as Map)?.driver == true }
    return [driverDiffs: drv, tvDiffs: diffs - drv]
}

String verdictOf(Map r) {
    if (r.skipped) return "skipped"
    if (r.driverDiffs) return "driver"
    if (r.tvDiffs || r.diffs) return "tv"
    return "ok"
}

String verdictText(Map r) {
    if (r.skipped) return "skipped: ${r.skipped}".toString()
    List<String> parts = []
    if (r.driverDiffs) parts << "driver differs (${(r.driverDiffs as List).join(', ')})".toString()
    List tvd = (r.tvDiffs != null ? r.tvDiffs : (r.driverDiffs ? [] : r.diffs)) as List
    if (tvd) parts << "TV differs (${tvd.join(', ')})".toString()
    return parts ? parts.join("; ") : "as expected"
}

// One row per test of a run: done with its verdict, running, waiting, or not run. The precheck shows only when it failed.
List<Map> testRows(Map run, Map names) {
    Map byTest = [:]
    for (Map r : ((run.results ?: []) as List<Map>)) byTest[r.test as Integer] = r
    List tests = (run.tests ?: []) as List
    int idx = (run.idx ?: 0) as int
    List<Map> rows = []
    for (int i = 0; i < tests.size(); i++) {
        Integer t = tests[i] as Integer
        Map r = byTest[t] as Map
        if (t == 0 && r == null) continue
        String st
        String detail = ""
        if (r != null) { st = verdictOf(r); detail = verdictText(r) }
        else if (run.status == "running" && i == idx) { st = "running"; detail = "step ${((run.step ?: 0) as int) + 1}".toString() }
        else if (run.status == "running" && i > idx) st = "waiting"
        else st = "not run"
        rows << [test: t, name: names[t] ?: "Test ${t}".toString(), state: st, detail: detail]
    }
    return rows
}

List<String> differences(Map fields, Map expect) {
    List<String> out = []
    expect?.each { k, rule ->
        if (rule.optional && !fields.containsKey(k)) return     // not measured in this run
        def v = fields[k]
        boolean ok = true
        if (rule.containsKey("eq")) ok = (v == rule.eq)
        else if (rule.containsKey("ne")) ok = (v != rule.ne)
        else if (rule.containsKey("none")) ok = (v == null || v == "")
        else if (rule.containsKey("max")) ok = v != null && (v as Long) <= (rule.max as Long)
        if (!ok) out << (k as String)
    }
    return out
}

String formatReport(Map header, List<Map> results) {
    List<String> out = ["Samsung TV Remote Tester report"]
    if (header.warning) out << "WARNING: ${header.warning}".toString()
    out << "app ${header.appVersion} · driver ${header.driverVersion ?: 'unknown'} · hub firmware ${header.firmware}".toString()
    out << ("TV: ${header.modelName ?: '?'} · platform ${header.platform ?: '?'} · API ${header.apiVersion ?: '?'} · " +
            "Frame ${header.frameTv ?: '?'} · token auth ${header.tokenSupport ?: '?'} · PowerState ${header.powerStateReported ?: '?'} · " +
            "SmartThings setting ${header.smartThings ? 'on' : 'off'}").toString()
    out << "run: ${header.status} · polling ${header.pollS} s · slowest log read ${header.slowestReadMs ?: 0} ms".toString()
    out << "TV differs: the TV does something the driver doesn't assume · driver differs: the driver didn't act on what the TV sent"
    for (Map r : results) {
        out << ""
        List diffs = (r.diffs ?: []) as List
        out << "${r.test}. ${r.name} — ${verdictText(r)}".toString()
        if (r.observed) out << ("   " + (r.observed as Map).collect { k, v -> "${k}=${v instanceof Map ? JsonOutput.toJson(v) : v}" }.join(" "))
        if (r.note) out << "   note: ${r.note}".toString()
        if (r.samples && !(diffs && r.trace)) {
            out << "   lines:"
            (r.samples as List).each { out << "     ${it}".toString() }
        }
        if (diffs && r.trace) {
            out << "   trace:"
            (r.trace as List).each { out << "     ${it}".toString() }
        }
    }
    Map prof = profileFields(results)
    if (prof) {
        out << ""
        out << "mock profile (for the maintainer):"
        out << JsonOutput.prettyPrint(JsonOutput.toJson(prof))
    }
    return out.join("\n")
}

// The report's fields a mock TV profile uses, taken from the tests that ran; otherMessages merged across tests.
Map profileFields(List<Map> results) {
    Map keys = [1: ["deviceInfo", "isSupport", "apiVersion"], 2: ["handshake", "connectMs"], 4: ["wentAway", "restAfterOffS"],
                6: ["probeReply"], 7: ["artReady", "statusReply", "setReply", "powerStateInArt"],
                8: ["launchMs", "closeBody"], 9: ["answered", "apps"]]
    Map prof = [:]
    Set<String> other = new LinkedHashSet<String>()
    for (Map r : results) {
        Map obs = (r.observed ?: [:]) as Map
        ((keys[r.test as Integer] ?: []) as List).each { k -> if (obs.containsKey(k)) prof[k] = obs[k] }
        if (obs.otherMessages) other.addAll((obs.otherMessages as String).tokenize(","))
    }
    Map disc = results.find { it.test == 10 && (it.observed as Map)?.result == "found" }
    if (disc) prof.airplay = [model: (disc.observed as Map).model]
    if (prof) prof.otherMessages = other.join(",")
    return prof
}

// Seconds until the next log read: at least as long as the last read took, so reading the log
// never takes more than about half the hub's time; at least 1 s for short waits, 10 s for long ones.
int pollGap(int timeoutS, Long lastReadMs) {
    int base = timeoutS <= 30 ? 1 : 3
    int read = lastReadMs ? (int) Math.ceil(lastReadMs / 1000.0d) : 0
    return Math.max(base, read)
}

// ── Logging ───────────────────────────────────────────────────────────
private String logp(String e) { "${e} ${app.getLabel()}: " }

void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }
void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
