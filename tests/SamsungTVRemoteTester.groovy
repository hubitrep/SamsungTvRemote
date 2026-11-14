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
    oauth: true,
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
import java.nio.file.NoSuchFileException
import java.text.SimpleDateFormat

@Field static final String CODE_VERSION = "0.5.0"

@Field static final String BUSY = "a run is in progress"

// The pad turns trace logging on for the driver; it goes back after this many seconds without a press.
@Field static final int PAD_TRACE_S = 600
@Field static final int PAD_LOG_MAX = 50

// The page lives in File Manager; the GitHub copy is fetched when it is missing or for another version.
@Field static final String UI_FILE = "samsung-tv-tester-ui.html"
@Field static final String UI_URL = "https://raw.githubusercontent.com/hubitrep/SamsungTvRemote/main/tests/samsung-tv-tester-ui.html"
@Field static final Map TEST_NAMES = [
    0: "Connection", 1: "Device info", 2: "Remote socket", 3: "Key burst", 4: "Apps", 5: "App list",
    6: "Discovery", 7: "Art mode", 8: "Power off", 9: "Standby check", 10: "Power on",
    11: "SmartThings status", 12: "SmartThings volume", 13: "SmartThings volume keys", 14: "SmartThings mute",
    15: "SmartThings picture mode", 16: "SmartThings sound mode", 17: "SmartThings input", 18: "SmartThings channel"
]

// Run order, and the test numbers follow it: power last, so the TV ends on, and art mode before it, so the
// power cycle resets art mode. Discovery needs the TV on, so it runs before the power tests. The SmartThings
// tests come after the power-on; they are skipped for a device that doesn't use SmartThings.
@Field static final List<Integer> ORDER = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18]

// A SmartThings step ends on the driver's status line, or on the line it logs when SmartThings refuses.
@Field static final String ST_WAIT = '^\\[method:statusParse|^deviceCommand: \\[status: FAILED|^SmartThings rejected the API key|^distResp: '
@Field static final Map ST_WHAT = [12: "volume", 13: "volume", 14: "mute", 15: "picture mode", 16: "sound mode", 17: "input", 18: "channel"]
@Field static final List<Integer> ST_SET_TESTS = [12, 13, 14, 15, 16, 17, 18]


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
@Field static final List TV_ATTRIBUTES = ["switch", "wsStatus", "artModeStatus", "appName", "appId",
                                          "volume", "mute", "pictureMode", "soundMode", "inputSource", "tvChannel"]

// What the driver assumes about a TV, and what it must do in answer. A field outside its rule is a
// difference. Fields marked driver: true judge the driver (its events, its sockets); the others judge
// the TV, where a difference describes the model and is often harmless.
@Field static final Map EXPECT = [
    0: [reachable: [eq: true]],
    1: [restStatus: [eq: 200], restMs: [max: 6000], powerStateReported: [eq: true], macMatchesTv: [eq: true], macMatchesHub: [ne: false]],
    2: [handshake: [eq: "ms.channel.connect"], connectMs: [max: 5000], keySent: [eq: true, driver: true, optional: true],
        openEvent: [eq: true, driver: true], otherMessages: [none: true]],
    3: [opens: [eq: 1, driver: true], keysSent: [eq: 3, driver: true], otherMessages: [none: true]],
    4: [launchStatus: [eq: 200], closeStatus: [eq: 200], closeBody: [eq: "true"],
        appEvent: [eq: true, driver: true], closeEvent: [eq: true, driver: true],
        // With SmartThings: it reports the app, the driver's name for it survives that report, and the app is gone after the close.
        stAppReported: [eq: true, optional: true], nameKept: [eq: true, driver: true, optional: true], stClearsApp: [eq: true, optional: true]],
    5: [answered: [eq: true], otherMessages: [none: true]],
    6: [:],     // by model year, see discoveryExpect
    7: [artReady: [eq: true], statusReply: [ne: null], statusEventMatches: [eq: true, driver: true],
        setVia: [eq: "art channel", driver: true], otherMessages: [none: true]],
    8: [offEvent: [eq: true, driver: true], noFlap: [eq: true, driver: true], offMs: [max: 30000, optional: true]],
    9: [probeReply: [eq: "ms.channel.timeOut"], stayedOff: [eq: true, driver: true]],
    // restOnMs is left out of the fields for a TV with no PowerState; that TV already differs in test 1.
    10: [onEvent: [eq: true, driver: true], restOnMs: [max: 45000, optional: true], powerStateLags: [eq: false],
         switchLags: [eq: false, driver: true]],
    // appNamed is there only while the TV shows an app.
    11: [answered: [eq: true], reportsOn: [eq: true], problem: [none: true], appNamed: [eq: true, optional: true],
         appLookupStatus: [eq: 200, optional: true]],
    // The value is set, read back, and put back. event is there only when SmartThings reported the new value.
    // 12 uses setVolume, 13 the volume keys, 14 mute and unmute, 15 and 16 the toggle and then the set command.
    12: [commandStatus: [eq: 200], valueSet: [eq: true], statusLags: [eq: false], event: [eq: true, driver: true, optional: true], restored: [eq: true]],
    13: [commandStatus: [eq: 200], valueSet: [eq: true], statusLags: [eq: false], event: [eq: true, driver: true, optional: true], restored: [eq: true]],
    14: [commandStatus: [eq: 200], valueSet: [eq: true], statusLags: [eq: false], event: [eq: true, driver: true, optional: true], restored: [eq: true]],
    15: [commandStatus: [eq: 200], valueSet: [eq: true], statusLags: [eq: false], event: [eq: true, driver: true, optional: true], restored: [eq: true]],
    16: [commandStatus: [eq: 200], valueSet: [eq: true], statusLags: [eq: false], event: [eq: true, driver: true, optional: true], restored: [eq: true]],
    // 17 and 18 change the input and the channel with a key, whatever the TV goes to, and set the first one again.
    17: [commandStatus: [eq: 200], valueSet: [eq: true], statusLags: [eq: false], event: [eq: true, driver: true, optional: true], restored: [eq: true]],
    18: [commandStatus: [eq: 200], valueSet: [eq: true], statusLags: [eq: false], event: [eq: true, driver: true, optional: true], restored: [eq: true]]
]


preferences {
    page(name: "mainPage")
}

mappings {
    path("/ui.html") { action: [GET: "apiUi"] }
    path("/run")     { action: [GET: "apiRun"] }
    path("/status")  { action: [GET: "apiStatus"] }
    path("/report")  { action: [GET: "apiReport"] }
    path("/abort")   { action: [GET: "apiAbort"] }
    path("/answer")  { action: [GET: "apiAnswer"] }
    path("/key")     { action: [GET: "apiKey"] }
    path("/padlog/clear") { action: [GET: "apiPadLogClear"] }
}

Map mainPage() {
    checkOAuth()
    checkStall()
    Map run = state.run as Map
    boolean running = run?.status == "running"
    // The page reloads itself while a run is in progress, so the summary follows it.
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
                      "switch a Frame to art mode, and turn the TV off and on. A run takes about 6 minutes with the driver's " +
                      "default 60 s polling, less with a shorter interval, and asks you a few questions about what the TV shows."
            if (state.accessToken) {
                href name: "openTester", title: "Open tester", style: "external", required: false,
                     url: "${getFullLocalApiServerUrl()}/ui.html?access_token=${state.accessToken}",
                     description: "Opens the tester page, where you start the run and answer its questions"
            } else {
                paragraph rawHtml: true, message("error", "OAuth could not be turned on for this app. Enable it in the app's code editor, then reopen this page.")
            }
            if (run?.tests) paragraph rawHtml: true, summaryHtml(run)
        }
        if (state.report) {
            section("Last report") {
                paragraph "<textarea readonly rows='30' style='width:100%;font-family:monospace'>${escapeHtml(state.report as String)}</textarea>"
            }
        }
        section("Logging") {
            input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
            input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        }
    }
}

void installed() { initialize() }

void uninstalled() {
    padTraceRestore()
    if ((state.run as Map)?.status == "running") finishRun("aborted", "the app was removed")
}

void updated() {
    if ((state.run as Map)?.status == "running") finishRun("aborted", "settings saved during the run")
    padTraceRestore()      // unschedule() below drops its timer
    unsubscribe()
    unschedule()
    initialize()
}

void initialize() {
    checkVersion(false)
    checkOAuth()
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
            return message("info", "Running since ${when}: ${where}.")
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
    Map setup = [powerOnWithMobile: opts.powerOnWithMobile ?: "unknown", ipRemote: opts.ipRemote ?: "unknown"]
    for (String k : setup.keySet()) {
        if (!(setup[k] in ["yes", "no", "unknown"])) return "${k} must be yes, no or unknown".toString()
    }
    // Whether anyone is at the TV to answer questions; without them no prompt opens.
    String attended = opts.attended ?: "yes"
    if (!(attended in ["yes", "no"])) return "attended must be yes or no"
    setup.attended = attended
    state.slowestReadMs = 0L
    state.readFailures = 0
    state.report = null
    state.prompt = null
    state.lastTimeout = null
    state.setup = setup
    // The pad may have turned trace on: the run restores what the person had before the pad, not the pad's own setting.
    boolean startTrace = runStartTrace(state.padTrace as Map, tv.getSetting("traceLog"))
    // The driver logs its SmartThings calls at debug; a run on a device that uses SmartThings turns debug logging on.
    // A key SmartThings already rejected makes the driver skip its SmartThings calls, so there is nothing to wait for.
    boolean stRun = tv.getSetting("connectST") == true && tv.getSetting("stApiKey") && tv.getSetting("stDeviceId") &&
                    deviceState()?.stAuthFailed != true
    unschedule("padTraceRestore")
    state.remove("padTrace")
    state.run = [status: "running", reason: null, tests: [0] + tests, idx: 0, step: 0, results: [], events: [], startedAt: now(),
                 runSince: nowTs(),
                 pollS: pollS, setup: setup, prompts: [:],
                 ctx: [startSwitch: tv.currentValue("switch"), startTrace: startTrace,
                       stRun: stRun, startDebug: tv.getSetting("logEnable") == true,
                       startInfo: tv.getSetting("infoLog") != false, frame: tv.getDataValue("frameTv") == "true",
                       powerState: tv.getDataValue("powerStateReported") != "false"]]
    tv.updateSetting("traceLog", [value: "true", type: "bool"])
    tv.updateSetting("infoLog", [value: "true", type: "bool"])
    if (stRun) tv.updateSetting("logEnable", [value: "true", type: "bool"])
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
    unschedule("pollPrompt")
    unschedule("pollWatch")
    Map run = state.run as Map
    if (run?.status != "running") return       // a stale Abort after the run ended changes nothing
    state.prompt = null
    run.status = status
    run.reason = maskLine(reason as String)     // an error text can carry the TV's IP
    state.run = run
    Map ctx = run.ctx as Map
    try {
        tv.updateSetting("traceLog", [value: ctx.startTrace ? "true" : "false", type: "bool"])
        tv.updateSetting("infoLog", [value: ctx.startInfo ? "true" : "false", type: "bool"])
        if (ctx.stRun) tv.updateSetting("logEnable", [value: ctx.startDebug ? "true" : "false", type: "bool"])
        // A run that ended between a SmartThings set and its restore puts the value back.
        Map back = ctx.stRestore as Map
        if (back) deviceCommand(back.cmd as String, ((back.args ?: []) as List).collect { [type: "STRING", value: it as String] })
        if (ctx.startSwitch == "off" && tv.currentValue("switch") == "on") deviceCommand("off")
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
        state.report = "Samsung TV Remote Tester report\nrun ${status}; the report could not be built: ${maskLine(e.message)}"
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
    boolean debugOff = run.ctx.stRun == true && tv.getSetting("logEnable") != true
    if (tv.getSetting("traceLog") == true && !debugOff) return
    tv.updateSetting("traceLog", [value: "true", type: "bool"])
    if (debugOff) tv.updateSetting("logEnable", [value: "true", type: "bool"])
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
                  slowestReadMs: state.slowestReadMs, status: run.status, warning: warning,
                  powerOnWithMobile: (run.setup as Map)?.powerOnWithMobile, ipRemote: (run.setup as Map)?.ipRemote,
                  attended: (run.setup as Map)?.attended ?: "yes"]
    // Pad presses made since the previous report was built.
    long upTo = now()
    header.manualKeys = padSection(state.padLog as List, (state.padReportedMs ?: 0L) as long, upTo, location.timeZone)
    state.padReportedMs = upTo
    return formatReport(header, run.results as List<Map>)
}


// ── Runner ────────────────────────────────────────────────────────────

private int pollSeconds(Map s) { pollGap((s.timeout ?: 0) as int, state.lastReadMs as Long) }

private String tsAt(long ms) { new Date(ms).format("yyyy-MM-dd HH:mm:ss.SSS", location.timeZone) }

private String nowTs() { tsAt(now()) }

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
            run.events = []      // finishTest reads only this test's events; the 300-event cap is for one test
            run.cur = null
            // A 2025 Frame showing art reports standby, so its switch can read off before off() is sent.
            if (test == 8) run.ctx.alreadyOff = tv.currentValue("switch") == "off"
            String skip = skipReason(test, run)
            if (skip) {
                (run.results as List) << [test: test, name: TEST_NAMES[test], skipped: skip]
                advance(run)
                return
            }
            if (test == 2) run.ctx.allow = needsAllow(tv.getDataValue("tokenSupport"), deviceState()?.token)
        }
        List<Map> steps = stepsFor(test, run)
        if ((run.step as int) >= steps.size()) { state.run = run; finishTest(); return }
        Map s = steps[run.step as int]
        run.stepSince = s.sinceRun ? run.runSince : (s.sinceTest ? run.testSince : nowTs())
        run.stepStartMs = now()
        long durS = ((s.timeout ?: s.sleep ?: 0) as long)
        run.cur = [test: test, step: s.name, label: s.label ?: s.name, startedAtMs: run.stepStartMs,
                   deadlineMs: durS ? (run.stepStartMs as long) + durS * 1000L : null]
        state.run = run
        if (s.sleep) { next("sleepDone", s.sleep as int, run); return }
        if (s.onlyIf == "airplay") {
            // The question is for a TV that discovery did not find: the person knows whether it announces AirPlay.
            // The log read is async, like every other one, so the page's calls don't wait behind it.
            run.nextDueMs = now() + 35000L
            state.run = run
            readTvLinesAsync("airplayLines", run.testSince as String, [idx: run.idx, step: run.step])
            return
        }
        startStep(s, test, run)
    } catch (e) {
        logError "Step failed: ${e}"
        finishRun("aborted", "error: ${e.message}")
    }
}

void airplayLines(resp, Map data) {
    try {
        Map run = state.run as Map
        if (run?.status != "running" || (run.idx as int) != (data.idx as int) || (run.step as int) != (data.step as int)) return
        int test = run.tests[run.idx] as int
        String found = evalDiscovery(linesFrom(resp, data) ?: [], tv.getSetting("deviceIp") as String).result
        if (!(found in ["none", "other"])) {
            run.step = (run.step as int) + 1
            next("runStep", 1, run)
            return
        }
        startStep(stepsFor(test, run)[run.step as int], test, run)
    } catch (e) {
        logError "Step failed: ${e}"
        finishRun("aborted", "error: ${e.message}")
    }
}

private void startStep(Map s, int test, Map run) {
    sendStep(s, run)
    if (s.prompt) openPrompt(s, test, run)
    if (s.watch) { pollWatch(); return }
    if (s.prompt && !s.wait) {
        // Nobody to ask: the step ends at once as unanswered, with no deadline to wait out.
        if (!isAttended(run)) { recordStep(s, false, null, "no answer"); return }
        next("pollPrompt", 1, run)
        return
    }
    if (!s.wait) { recordStep(s, true, null, null); return }
    next("pollWait", pollSeconds(s), run)
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
    if (ctx.refused && test in [3, 8, 10, 9, 5]) return "the TV refused the remote connection (allow it on the TV, then run again)"
    switch (test) {
        case 7: return ctx.frame ? null : "not a Frame TV"
        case 4:
            // Any app the device has a code for exercises the same launch and close calls.
            ctx.app = ["YouTube", "Netflix"].find { appCode(it as String) }
            state.run = run
            return ctx.app ? null : "the device has no app code for YouTube or Netflix (turn on its app search, or link SmartThings)"
        case 11: case 12: case 13: case 14: case 15: case 16: case 17: case 18:
            if (tv.getSetting("connectST") != true) return "SmartThings is not turned on for the device"
            if (!tv.getSetting("stApiKey") || !tv.getSetting("stDeviceId")) return "the device has no SmartThings key or no SmartThings TV yet"
            if (tv.currentValue("switch") != "on") return "the TV is not on"
            if (ctx.stDown) return "SmartThings did not answer the device's requests"
            if (ctx.stOff) return "SmartThings reports the TV off"
            if (test == 11) return null
            Map plan = stPlan(test, [volume: tv.currentValue("volume"), mute: tv.currentValue("mute"), pictureMode: tv.currentValue("pictureMode"),
                                     soundMode: tv.currentValue("soundMode"), inputSource: tv.currentValue("inputSource"),
                                     tvChannel: tv.currentValue("tvChannel")], deviceState())
            ctx.stPlan = plan.skip ? null : plan
            state.run = run
            return plan.skip as String
        default: return null
    }
}

// The driver keeps app names, codes and its token in its state; read only that, over loopback.
private Map deviceState() {
    Map st = null
    try {
        httpGet([uri: "http://127.0.0.1:8080", path: "/device/fullJson/${tv.id}", timeout: 20]) { resp -> st = resp.data?.deviceState as Map }
    } catch (e) {
        logWarn "Could not read the device's state: ${e.message}"
    }
    return st
}

private String appCode(String name) {
    Map st = deviceState()
    def apps = st?.appData
    if (apps instanceof String) apps = new JsonSlurper().parseText(apps as String)
    Map.Entry hit = (apps instanceof Map) ? (apps as Map).find { (it.key as String).toLowerCase().contains(name.toLowerCase()) } : null
    return hit ? hit.value as String : null
}

void sendStep(Map s, Map run) {
    if (!s.cmd) return
    List args = (s.args ?: []) as List
    if (s.st) {
        // A SmartThings step: remember how to put the value back until the restore step has sent it.
        Map ctx = run.ctx as Map
        Map plan = (ctx.stPlan ?: [:]) as Map
        if (s.name == "set") ctx.stRestore = plan.restore
        if (s.name == "restore") ctx.remove("stRestore")
        state.run = run
        deviceCommand(s.cmd as String, args.collect { [type: "STRING", value: it as String] })
        return
    }
    switch (s.cmd) {
        case "burst": deviceCommand("home", [], [[method: "home"], [method: "home"]]); break
        case "appOpenByName": deviceCommand("appOpenByName", [[type: "STRING", value: args[0] as String]]); break
        case "on": case "off": case "close": case "refresh": case "home": case "getArtModeStatus": case "artMode":
        case "appClose": case "getInstalledApps": case "findTvs":
            deviceCommand(s.cmd as String); break
        default: throw new IllegalArgumentException("unknown step command ${s.cmd}")
    }
}

// Runs a driver command in a hub request, not in this app's execution. A command waits for the TV
// (a websocket connect to a sleeping TV took over two minutes, an app launch about 7 s), and this
// single-threaded app would hold the page's /status and /answer calls all that time. `then` runs
// after it, in order (the key burst).
void deviceCommand(String method, List args = [], List then = []) {
    asynchttpPost("deviceCommandDone", [uri: "http://127.0.0.1:8080", path: "/device/runmethod",
                  requestContentType: "application/json", contentType: "application/json",
                  body: [id: tv.idAsLong, method: method, args: args], timeout: 180],
                  [method: method, then: then])
}

void deviceCommandDone(resp, Map data) {
    // A timeout only means the command is still running on the hub.
    if (resp.hasError()) logDebug "Command ${data.method}: ${resp.getErrorMessage()}"
    List then = (data.then ?: []) as List
    if (then) {
        Map n = then[0] as Map
        deviceCommand(n.method as String, (n.args ?: []) as List, then.drop(1))
    }
}

void pollWait() {
    try {
        Map run = state.run as Map
        if (run?.status != "running") return
        keepTrace(run)
        // The log read is async: a sync read of 1-3 s holds this single-threaded app, and the page's
        // /status and /answer calls queue behind it.
        run.nextDueMs = now() + 35000L      // the read's own 30 s timeout, so a slow read isn't a stall
        state.run = run
        readTvLinesAsync("pollWaitLines", run.stepSince as String, [idx: run.idx, step: run.step])
    } catch (e) {
        logError "Wait failed: ${e}"
        finishRun("aborted", "error: ${e.message}")
    }
}

void pollWaitLines(resp, Map data) {
    try {
        Map run = state.run as Map
        if (run?.status != "running" || (run.idx as int) != (data.idx as int) || (run.step as int) != (data.step as int)) return
        Map s = stepsFor(run.tests[run.idx] as int, run)[run.step as int]
        List<Map> lines = linesFrom(resp, data)
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

void recordStep(Map s, boolean ok, Long ms, String note, Map extra = [:]) {
    Map run = state.run as Map
    if (run?.status != "running") return
    // A concurrent prompt (screen off) outlives its step; the hold step that follows ends it.
    if (((s.prompt && !s.concurrent) || s.endsPrompt) && state.prompt) closePrompt(s)
    run = state.run as Map
    (run.stepResults as Map)[s.name] = [ok: ok, ms: ms, at: run.stepStartMs, note: note] + extra
    run.step = (run.step as int) + 1
    if (note == "timed out") setLastTimeout(run, s)
    state.run = run
    next("runStep", Math.max(1, (s.after ?: 1) as int), run)
}

private void setLastTimeout(Map run, Map s) {
    state.lastTimeout = [test: run.tests[run.idx] as int, step: s.name, label: s.label ?: s.name, atMs: now()]
}

// ── Prompts ───────────────────────────────────────────────────────────

private boolean isAttended(Map run) { ((run.setup as Map)?.attended ?: "yes") != "no" }

private void openPrompt(Map s, int test, Map run) {
    if (!isAttended(run)) {
        Map prompts = (run.prompts ?: [:]) as Map
        prompts[s.prompt] = [notAsked: true]
        run.prompts = prompts
        state.run = run
        return
    }
    int seq = ((state.promptSeq ?: 0) as int) + 1
    state.promptSeq = seq
    int promptS = (s.promptTimeout ?: s.timeout) as int
    state.prompt = newPrompt(s.prompt as String, "p${seq}".toString(), test, now(), promptS, run.ctx.frame == true)
    logCmd "Question ${seq} for the person at the TV: ${(state.prompt as Map).text}"
}

// Ends the open prompt and keeps what the person answered; an unanswered one past its deadline is a timeout the page shows.
private void closePrompt(Map s) {
    Map p = state.prompt as Map
    if (p == null) return
    Map run = state.run as Map
    Map prompts = (run.prompts ?: [:]) as Map
    prompts[p.kind] = [choice: (p.answer as Map)?.choice, atMs: (p.answer as Map)?.atMs, askedAtMs: p.askedAtMs]
    run.prompts = prompts
    state.run = run
    if (promptOutcome(p, now()) == "expired") setLastTimeout(run, s)
    state.prompt = null
}

// A prompt-only step ends on the answer or the deadline; the page answers through /answer.
void pollPrompt() {
    try {
        Map run = state.run as Map
        if (run?.status != "running") return
        keepTrace(run)
        Map s = stepsFor(run.tests[run.idx] as int, run)[run.step as int]
        Map p = state.prompt as Map
        String outcome = p ? promptOutcome(p, now()) : "expired"
        if (outcome == "open") { next("pollPrompt", 1, run); return }
        boolean answered = outcome == "answered"
        recordStep(s, answered, answered ? (p.answer.atMs as Long) - (p.askedAtMs as Long) : null, answered ? null : "no answer")
    } catch (e) {
        logError "Prompt failed: ${e}"
        finishRun("aborted", "error: ${e.message}")
    }
}

// A watch step reads the TV's REST page every 5 s from a callback. A step with a prompt also ends on that
// prompt: for "on" once the switch is on and the question is over, for every watch at its time limit.
void pollWatch() {
    try {
        Map run = state.run as Map
        if (run?.status != "running") return
        keepTrace(run)
        Map s = stepsFor(run.tests[run.idx] as int, run)[run.step as int]
        Map w = (run.watch && run.watchStep == s.name ? run.watch : [readings: [], lastAtMs: 0L]) as Map
        long base = (s.watch == "hold" ? run.testStartMs : run.stepStartMs) as long
        long t = now()
        // Async: a sync read of a TV that doesn't answer blocks for its whole timeout, and in this
        // single-threaded app the page's /status and /answer calls then queue behind every read.
        if (t - (w.lastAtMs as long) >= 5000L) {
            w.lastAtMs = t
            asynchttpGet("watchReading", [uri: "http://${tv.getSetting('deviceIp')}:8001", path: "/api/v2/", timeout: 4],
                         [ms: t - base, step: s.name])
        }
        run.watch = w
        run.watchStep = s.name
        state.run = run
        Map p = state.prompt as Map
        boolean promptOver = p == null || promptOutcome(p, now()) != "open"
        if (p != null && promptOver) {
            closePrompt(s)
            run = state.run as Map
        }
        long elapsed = now() - (run.stepStartMs as long)
        boolean switchOn = (run.events ?: []).any { it.name == "switch" && it.value == "on" && (it.at as Long) >= (run.stepStartMs as Long) }
        // The hold also waits for its question, which started with the off step and has its own deadline.
        if ((elapsed >= (s.timeout as long) * 1000L && (s.watch != "hold" || promptOver)) || (s.watch == "on" && switchOn && promptOver)) {
            recordStep(s, true, elapsed, null, [readings: w.readings, watchMs: elapsed])
            return
        }
        next("pollWatch", 1, run)
    } catch (e) {
        logError "Watch failed: ${e}"
        finishRun("aborted", "error: ${e.message}")
    }
}

// One watch reading, in the shape fetchDeviceInfo returns; a late reply for an earlier step is dropped.
void watchReading(resp, Map data) {
    Map run = state.run as Map
    if (run?.status != "running" || run.watchStep != data.step || run.watch == null) return
    Map info = [status: "no answer"]
    try {
        if (!resp.hasError()) {
            info.status = resp.status
            def d = resp.json
            if (d instanceof Map) { info.device = d.device; info.version = d.version; info.isSupport = d.isSupport }
        }
    } catch (e) { info = [status: "no answer"] }
    Map w = run.watch as Map
    List readings = (w.readings ?: []) as List
    readings << [ms: data.ms as long, v: readingValue(info)]
    w.readings = readings.sort { it.ms as long }
    run.watch = w
    state.run = run
    logDebug "TV reading at ${(data.ms as long) / 1000}s: ${readings[-1].v}"
}

void readTvLinesAsync(String handler, String since, Map data) {
    asynchttpGet(handler, [uri: "http://127.0.0.1:8080", path: "/logs/past/json", timeout: 30],
                 data + [since: since, sentAt: now()])
}

// The device's lines from an async log read, or null when the read failed.
List<Map> linesFrom(resp, Map data) {
    List raw = null
    try {
        if (!resp.hasError()) raw = resp.json as List
    } catch (e) { raw = null }
    if (raw == null) {
        state.readFailures = ((state.readFailures ?: 0) as int) + 1
        logWarn "Log read failed: ${resp.hasError() ? resp.getErrorMessage() : 'unreadable reply'}"
        return null
    }
    state.readFailures = 0
    long ms = now() - (data.sentAt as long)
    state.lastReadMs = ms
    if (ms > ((state.slowestReadMs ?: 0L) as Long)) state.slowestReadMs = ms
    return deviceLines(raw, tv.idAsLong, data.since as String)
}

void finishTest() {
    Map run = state.run as Map
    int test = run.tests[run.idx] as int
    run.nextDueMs = now() + 35000L
    state.run = run
    // Device info reuses the poll the connection check already saw.
    readTvLinesAsync("finishTestLines", (test == 1 ? run.runSince : run.testSince) as String, [idx: run.idx])
}

void finishTestLines(resp, Map data) {
    Map run = state.run as Map
    if (run?.status != "running" || (run.idx as int) != (data.idx as int)) return
    int test = run.tests[run.idx] as int
    Map steps = run.stepResults as Map
    List<Map> lines = linesFrom(resp, data) ?: []
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
        Map info = (test in [1, 7, 6]) ? fetchDeviceInfo() : null
        run = state.run as Map
        Map r = evaluateTest(test, lines, steps, run, events, info)
        if (test == 1 && info) r.observed = (r.observed as Map) + deviceInfoFields(info, DEVICE_INFO_KEYS)
        if (test == 7 && info) r.observed = (r.observed as Map) + [powerStateInArt: (info.device as Map)?.PowerState ?: (info.status == 200 ? "absent" : "no answer")]
        r.samples = sampleLines(test, lines)
        if (test == 2 && (r.observed as Map)?.handshake == "ms.channel.unauthorized") run.ctx.refused = true
        // Without an answer from SmartThings, or with the TV off there, the tests that set values are skipped.
        Map seen = (r.observed ?: [:]) as Map
        if (test == 11 && seen.answered != true) run.ctx.stDown = true
        if (test == 11 && seen.answered == true && seen.reportsOn != true) run.ctx.stOff = true
        if (test in ST_SET_TESTS && seen.commandStatus == 401) run.ctx.stDown = true
        if (test in ((run.traceOff ?: []) as List)) r.note = "trace logging was off for part of this test, so lines may be missing"
        (run.results as List) << r
    }
    advance(run)
}

// Booleans only: a MAC address never goes into the report. The hub's own lookup may be missing or fail.
private Map macChecks(Map info, String deviceMac, String ip) {
    String hubMatch = "unavailable"
    try {
        String hubMac = getMACFromIP(ip)
        if (hubMac) hubMatch = macsMatch(hubMac, deviceMac).toString()
    } catch (e) {
        logDebug "getMACFromIP failed: ${e.message}"
    }
    return [macMatchesTv: macsMatch(deviceMac, (info?.device as Map)?.wifiMac as String),
            macMatchesHub: hubMatch == "unavailable" ? "unavailable" : hubMatch == "true"]
}

// The driver names an app SmartThings reports by asking the TV for that code; the same request, to see the TV's answer.
private Map appLookup(String code) {
    Map out = [appLookupStatus: "no answer"]
    try {
        httpGet([uri: "http://${tv.getSetting('deviceIp')}:8001", path: "/api/v2/applications/${code}", timeout: 10]) { resp ->
            out.appLookupStatus = resp.status
            if (resp.data instanceof Map) out.appLookupName = resp.data.name
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        out.appLookupStatus = e.statusCode
    } catch (e) {
        logDebug "App lookup failed: ${maskLine(e.message)}"
    }
    return out
}

private Map fetchDeviceInfo(int timeoutS = 6) {
    Map out = [status: null]
    try {
        httpGet([uri: "http://${tv.getSetting('deviceIp')}:8001", path: "/api/v2/", timeout: timeoutS]) { resp ->
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
        case 1:
            f = evalDeviceInfo(lines, tv.getDataValue("powerStateReported"))
            f.putAll(macChecks(info, tv.getDataValue("deviceMac"), tv.getSetting("deviceIp") as String))
            break
        case 2: f = evalRemoteSocket(lines, events, tz); break
        case 3: f = evalBurst(lines); break
        case 4:
            f = evalApps(lines, events, steps)
            if (run.ctx.stRun) f.putAll(evalAppsSt(lines, events, steps, tz))
            break
        case 5: f = evalAppList(lines); break
        case 6: f = evalDiscovery(lines, tv.getSetting("deviceIp") as String); break
        case 7: f = evalArt(lines, events); break
        case 8: f = evalPowerOff(lines, events, steps, run.testStartMs as Long, tz, run.ctx.alreadyOff == true, (steps.hold?.readings ?: []) as List); break
        case 9: f = evalStandby(lines, events); break
        case 10:
            // Times run from the on() call, which is the watch step's start.
            Long onAt = (steps.watch?.at ?: run.testStartMs) as Long
            f = evalPowerOn(lines, events, steps, onAt, tz, (steps.watch?.readings ?: []) as List,
                            pictureMs((run.prompts as Map)?.picture as Map, onAt), (run.pollS ?: 60) as int, steps.watch?.watchMs as Long)
            break
        case 11:
            f = evalStStatus(lines, steps, deviceState(), tv.currentValue("appName") as String, tz)
            if (f.appCode) f.putAll(appLookup(f.appCode as String))
            break
        case 12: case 13: case 14: case 15: case 16: case 17: case 18: f = evalStSet(lines, events, steps, (run.ctx.stPlan ?: [:]) as Map, tz); break
        default: f = [:]
    }
    f.putAll(promptFields(test, (run.prompts ?: [:]) as Map))
    String other = otherMessages(lines, KNOWN_EVENTS, KNOWN_ART_EVENTS)
    if (other) f.otherMessages = other
    r.observed = f
    // Discovery is judged by the TV's model year, read from its REST model field.
    Map expect = (test == 6) ? discoveryExpect(modelYear((info?.device as Map)?.model as String)) : (EXPECT[test] as Map)
    // A TV that reports PowerState is not probed by the driver, so the probe's reply is recorded without a judgement.
    if (test == 9 && run.ctx.powerState) expect = expect.findAll { k, v -> k != "probeReply" }
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
            if (run.ctx.startSwitch == "off") return [[name: "powerOn", label: "Waiting for the TV to wake and answer", cmd: "on", wait: '^rest: status=200', timeout: poll + 60, after: 3]]
            // Refresh polls at once; a driver older than that is caught by the scheduled poll.
            return [[name: "poll", label: "Waiting for the driver's poll of the TV", cmd: "refresh", wait: '^rest: status=', timeout: poll + 15]]
        case 1: return [[name: "poll", label: "Waiting for the driver's poll of the TV", wait: '^rest: status=', timeout: poll + 15, sinceRun: true]]
        case 2:
            // Three connects: the first after a long idle is often the slow one. When the device has no token the
            // first one makes the TV ask for Allow, so it waits for the person.
            boolean allow = run.ctx.allow == true
            List<Map> steps = []
            3.times { int i ->
                steps << [name: "close${i + 1}".toString(), label: "Closing the remote socket", cmd: "close", after: 2]
                Map home = [name: "home${i + 1}".toString(), label: "Waiting for the TV to answer a connect", cmd: "home",
                            wait: '^rx remote: .*"ms\\.channel\\.(connect|unauthorized|timeOut)"', timeout: 15, after: 2]
                if (i == 0 && allow) home += [label: "Waiting for you to choose Allow on the TV", prompt: "allow", timeout: 60]
                steps << home
                // The first home key is the one a person can see work; ask once the handshake is over.
                if (i == 0) steps << [name: "homeSeen", label: "Waiting for your answer", prompt: "home", timeout: 20]
            }
            return steps
        case 3: return [[name: "close", label: "Closing the remote socket", cmd: "close", after: 2],
                        [name: "burst", label: "Sending three keys", cmd: "burst", wait: '^tx remote: .*KEY_HOME', timeout: 15],
                        [name: "settle", label: "Letting the TV settle", sleep: 4]]
        case 4:
            List<Map> apps = [[name: "launch", label: "Launching the app", cmd: "appOpenByName", args: [run.ctx.app ?: "YouTube"], wait: '^app: POST', timeout: 25, after: 5],
                              [name: "app", label: "Waiting for your answer", prompt: "app", timeout: 30]]
            // The driver asks SmartThings for the status 10 s after a launch and 5 s after a close. The wait after the
            // first leaves the driver time to act on the app SmartThings reported.
            if (run.ctx.stRun) apps << [name: "stApp", label: "Waiting for SmartThings to report the app", wait: ST_WAIT, timeout: 30, sinceTest: true, after: 8]
            apps << [name: "close", label: "Closing the app", cmd: "appClose", wait: '^app: DELETE', timeout: 15, after: 2]
            if (run.ctx.stRun) apps << [name: "stClosed", label: "Waiting for SmartThings to report the app closed", wait: ST_WAIT, timeout: 30, sinceTest: false]
            return apps
        case 5: return [[name: "list", label: "Waiting for the TV's app list", cmd: "getInstalledApps", wait: '^rx remote: .*ed\\.installedApp\\.get', timeout: 12]]
        // The search starts after 1 s, blocks up to 6 s, and its outcome (logged last, at info or warn) follows 7 s later.
        case 6: return [[name: "find", label: "Searching the network for the TV", cmd: "findTvs", wait: '^(No Samsung TV answered|Found )', timeout: 20, after: 1],
                         [name: "airplay", label: "Waiting for your answer", prompt: "airplay", onlyIf: "airplay", timeout: 30]]
        // The driver's own art-status request; it is not a device command, and the hub runs it all the same.
        case 7: return [[name: "status", label: "Reading the art mode status", cmd: "getArtModeStatus", wait: '^rx frameArt: .*artmode_status', timeout: 20, after: 2],
                        [name: "set", label: "Setting art mode", cmd: "artMode", wait: '^rx frameArt: .*(art_mode_changed|set_artmode_status)|^tx remote: .*KEY_POWER|already set', timeout: 20, after: 3],
                        [name: "art", label: "Waiting for your answer", prompt: "art", timeout: 30]]
        // The TV's REST page is read every 5 s during the hold: how it goes away is what the driver's power state follows.
        case 8: return [[name: "off", label: "Waiting for the driver to report off", cmd: "off", wait: run.ctx.alreadyOff ? '^off: ' : '^setPowerState: \\[switch: off\\]', timeout: poll + 20,
                         prompt: "screenOff", promptTimeout: 30, concurrent: true],
                        [name: "hold", label: "Watching the TV after power off", watch: "hold", timeout: poll + 10, endsPrompt: true]]
        // The probe is a socket command with the TV in standby, whatever the TV reports for PowerState.
        case 9: return [[name: "probe", label: "Waiting for the TV in standby to answer a connect", cmd: "getInstalledApps",
                         wait: '^rx remote: .*"ms\\.channel\\.timeOut"|^ws remote: (failure|status: open)', timeout: 25],
                        [name: "settle", label: "Letting the TV settle", sleep: 5]]
        // The person says when the picture is on; the watch reads REST and the driver's switch meanwhile.
        case 10: return [[name: "watch", label: "Waiting for the TV to report on", cmd: "on", watch: "on", prompt: "picture", timeout: 120, after: 5]]
        // Refresh makes the driver ask SmartThings for the TV's status; SmartThings can be late after a power-on.
        case 11: return [[name: "settle", label: "Letting SmartThings catch up", sleep: 5],
                         [name: "status", label: "Waiting for SmartThings to report the TV's status", cmd: "refresh", wait: ST_WAIT, timeout: 30]]
        // Set, read again (SmartThings can answer the driver's own read with the old value), put back.
        case 12: case 13: case 14: case 15: case 16: case 17: case 18:
            Map p = (run.ctx.stPlan ?: [:]) as Map
            String what = ST_WHAT[test]
            Map set = (p.set ?: [:]) as Map, back = (p.restore ?: [:]) as Map
            List<Map> st = [[name: "set", label: "Changing the ${what} and waiting for SmartThings".toString(), cmd: set.cmd, args: set.args ?: [], st: true,
                             wait: ST_WAIT, timeout: 30, after: 5]]
            if (p.restore) {
                st << [name: "check", label: "Reading the ${what} again".toString(), cmd: "refresh", wait: ST_WAIT, timeout: 30, after: 2]
                st << [name: "restore", label: "Putting the ${what} back".toString(), cmd: back.cmd, args: back.args ?: [], st: true, wait: ST_WAIT, timeout: 30, after: 2]
            }
            return st
        default:
            return []
    }
}


// ── Script endpoints ──────────────────────────────────────────────────

private def json(Map data) {
    render(status: 200, contentType: "application/json", data: JsonOutput.toJson(data))
}

private Map failure(String text) { [ok: false, error: text] }

def apiUi() {
    checkOAuth()
    String html = null
    try {
        html = readFile(UI_FILE)
    } catch (Exception e) {
        logWarn "Could not read ${UI_FILE}: ${maskLine(e.message)}"
    }
    if (html == null) {
        syncUI()
        return render(status: 200, contentType: "text/html", data: "<html><head><meta http-equiv='refresh' content='10'></head><body>" +
            "Downloading the page from GitHub. This page reloads every 10 seconds. If it never appears, upload " +
            "<code>${UI_FILE}</code> to the hub's File Manager and check the hub logs.</body></html>")
    }
    if (!html.contains("const CODE_VERSION = '${CODE_VERSION}'")) syncUI()
    return render(status: 200, contentType: "text/html", data: html)
}

def apiRun() {
    checkOAuth()
    if (checkVersion(false)) updated()     // a new version re-initializes before it starts a run
    List<Integer> tests = null
    if (params.tests) {
        try {
            tests = (params.tests as String).tokenize(",").collect { it.trim() as Integer }
        } catch (e) {
            return json(failure("tests must be comma-separated test numbers"))
        }
        Integer bad = tests.find { !TEST_NAMES.containsKey(it) || it == 0 }
        if (bad != null) return json(failure("unknown test ${bad}".toString()))
    }
    String err = startRun([tests: tests, powerOnWithMobile: params.powerOnWithMobile, ipRemote: params.ipRemote, attended: params.attended])
    return json(err ? failure(err) : [ok: true])
}

def apiStatus() {
    checkOAuth()
    checkVersion()
    checkStall()
    Map run = (state.run ?: [:]) as Map
    Map prompt = publicPrompt(state.prompt as Map, now())
    return json([appVersion: CODE_VERSION, nowMs: now(),
                 device: [name: tv?.displayName, switch: tv?.currentValue("switch"), wsStatus: tv?.currentValue("wsStatus"), selected: tv != null,
                          frame: tv?.getDataValue("frameTv") == "true"],
                 setup: (state.setup ?: [powerOnWithMobile: "unknown", ipRemote: "unknown", attended: "yes"]),
                 run: statusRun(run, TEST_NAMES), prompt: prompt, lastTimeout: state.lastTimeout, reportReady: state.report != null,
                 padLog: padLogView(state.padLog as List, 20)])
}

def apiAbort() {
    checkOAuth()
    checkVersion()
    finishRun("aborted", "aborted by the user")
    return json([ok: true])
}

def apiAnswer() {
    checkOAuth()
    Map res = answerPrompt(state.prompt as Map, params.id as String, params.choice as String, now())
    if (!res.ok) return json(failure(res.error as String))
    state.prompt = res.prompt
    return json([ok: true])
}

def apiReport() {
    checkOAuth()
    checkVersion()
    Map run = (state.run ?: [:]) as Map
    Map profile = profileFields((run.results ?: []) as List<Map>)
    // results and reason are for tests/test-live-tv.sh
    return json([status: run.status, reason: run.reason, report: state.report, profile: profile ?: null, results: run.results])
}

// The remote pad: one key at a time, never during a run, which owns the TV's sockets.
def apiKey() {
    checkOAuth()
    checkStall()
    if ((state.run as Map)?.status == "running") return json(failure(BUSY))
    if (!tv) return json(failure("select the Samsung TV Remote device"))
    Map cmd = padCommand(params.name as String, params.key as String)
    if (!cmd.ok) return json(failure(cmd.error as String))
    padPressed(params.name as String, cmd)
    deviceCommand(cmd.method as String, cmd.args as List)
    return json([ok: true])
}

def apiPadLogClear() {
    checkOAuth()
    state.padLog = []
    return json([ok: true])
}

// ── Pad log ───────────────────────────────────────────────────────────

// A pad press outside a run: trace logging goes on for the driver (remembering how it was), the press is
// recorded, and 4 s later the driver's lines since it are read and attached to the entry.
private void padPressed(String name, Map cmd) {
    long t = now()
    Map pt = (state.padTrace ?: [:]) as Map
    if (!pt.active) {
        pt = [active: true, prev: tv.getSetting("traceLog") == true]
        tv.updateSetting("traceLog", [value: "true", type: "bool"])
    }
    state.padTrace = pt
    runIn(PAD_TRACE_S, "padTraceRestore")      // re-armed by each press
    String key = name == "sendKey" ? "KEY_" + (((cmd.args as List)[0] as Map).value as String) : null
    state.padLog = padLogAppend(state.padLog as List, [atMs: t, since: tsAt(t), button: name, key: key, lines: []], PAD_LOG_MAX)
    runIn(4, "padCollect", [data: [atMs: t], overwrite: false])
}

void padTraceRestore() {
    Map pt = state.padTrace as Map
    if (!pt?.active) return
    state.remove("padTrace")
    try {
        if (tv) tv.updateSetting("traceLog", [value: pt.prev ? "true" : "false", type: "bool"])
    } catch (e) {
        logWarn "Could not restore trace logging: ${e.message}"
    }
}

void padCollect(Map data) {
    Map e = ((state.padLog ?: []) as List<Map>).find { (it.atMs as long) == (data.atMs as long) }
    if (e == null) return
    readTvLinesAsync("padLines", e.since as String, [atMs: data.atMs])
}

void padLines(resp, Map data) {
    try {
        List<Map> lines = linesFrom(resp, data)
        if (lines == null) return
        List<Map> log = (state.padLog ?: []) as List<Map>
        Map entry = log.find { (it.atMs as long) == (data.atMs as long) }
        if (entry == null) return
        // Up to the next press, or the 4 s window when that comes first.
        Map later = log.find { (it.atMs as long) > (entry.atMs as long) }
        String windowEnd = tsAt((entry.atMs as long) + 4000L)
        String until = later && (later.since as String) < windowEnd ? later.since as String : windowEnd
        entry.lines = padEntryLines(lines, until)
        state.padLog = log
    } catch (ex) {
        logWarn "Pad log read failed: ${ex.message}"
    }
}

// The remote pad's button ids are the driver's command names; "sendKey" carries a key name typed by the user.
Map padCommand(String name, String key = null) {
    List ids = ["on", "off", "arrowUp", "arrowDown", "arrowLeft", "arrowRight", "enter", "Return", "home", "exit",
                "menu", "guide", "sourceSetOSD", "sourceToggle", "numericKeyPad", "channelUp", "channelDown", "channelList",
                "previousChannel", "volumeUp", "volumeDown", "mute", "play", "pause", "stop", "fastBack", "fastForward",
                "artMode", "ambientMode"]
    if (name == "sendKey") {
        String k = (key ?: "").toUpperCase()
        if (k.startsWith("KEY_")) k = k.substring(4)
        if (!(k ==~ /[A-Z0-9_]{1,30}/)) return [ok: false, error: "unknown key"]
        return [ok: true, method: "sendKey", args: [[type: "STRING", value: k]]]
    }
    if (!(name in ids)) return [ok: false, error: "unknown key"]
    return [ok: true, method: name, args: []]
}


// ── Page file ─────────────────────────────────────────────────────────

// Null for a missing file: it throws up to 2.5.2 and from 2.5.3.105, and returns null on 2.5.3.102.
private String readFile(String name) {
    for (int i = 1; ; i++) {
        try {
            byte[] bytes = downloadHubFile(name)
            return bytes == null ? null : new String(bytes, "UTF-8")
        } catch (NoSuchFileException ignored) {
            return null
        } catch (Exception e) {
            if (i >= 3) throw e
            pauseExecution(500)
        }
    }
}

private boolean writeFile(String name, String text) {
    for (int i = 1; i <= 3; i++) {
        try {
            uploadHubFile(name, text.getBytes("UTF-8"))
            return true
        } catch (Exception e) {
            logWarn "Could not write ${name} (${i} of 3): ${maskLine(e.message)}"
            if (i < 3) pauseExecution(500)
        }
    }
    return false
}

void syncUI() {
    if (state.uiSyncAt && now() - (state.uiSyncAt as long) < 3600000L) return
    state.uiSyncAt = now()
    asynchttpGet("syncUICallback", [uri: UI_URL, textParser: true, timeout: 30], [:])
}

void syncUICallback(resp, data) {
    if (resp.hasError() || resp.getStatus() != 200) {
        logWarn "Could not download the page: HTTP ${resp.getStatus()}"
        return
    }
    String html = resp.getData() as String
    // A partial body, or a commit where app and page haven't been changed together yet, is refused.
    if (!html?.contains("const CODE_VERSION = '${CODE_VERSION}'")) {
        logWarn "The page on GitHub does not match version ${CODE_VERSION}"
        return
    }
    if (writeFile(UI_FILE, html)) state.remove("uiSyncAt")
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
    out = out.replaceAll(/\b[0-9A-Fa-f]{2}(?:[:-][0-9A-Fa-f]{2}){5}\b/, "<mac>")
    out = out.replaceAll(/\b[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\b/, "<uuid>")
    out = out.replaceAll(/\b(?=[0-9A-Fa-f]*[A-Fa-f])[0-9A-Fa-f]{12}\b/, "<mac>")    // a DNI; app codes are all digits
    out = out.replaceAll(/"(name|deviceName|ssid)"\s*:\s*"[^"]*"/, '"$1":"<name>"')
    out = out.replaceAll(/"token"\s*:\s*"?[^",\x7D<]+"?/, '"token":"<token>"')
    out = out.replaceAll(/(?:0x)?[0-9A-Fa-f]{33,}/, "<hex>")
    return out
}

// Protocol lines only (the driver's trace), masked and cut to a readable length.
List<String> traceLines(List<Map> lines) {
    List<String> out = []
    for (Map l : lines) {
        String msg = l.msg as String
        // The driver logs its SmartThings calls at debug.
        boolean st = l.level == "debug" && msg =~ /^(deviceCommand: |asyncGet: |\[method:(statusParse|updateAppName)|stAuthFailed: |setSmartThingsKey: )/
        if (l.level != "trace" && !(l.level in ["warn", "error"]) && !st) continue
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
    // Each open is timed to the first answer after it; the slowest of the connects is what the driver has to live with.
    List<Long> all = []
    for (int i = 0; i < lines.size(); i++) {
        if (!((lines[i].msg as String).startsWith("ws remote: status: open"))) continue
        for (int j = i + 1; j < lines.size(); j++) {
            String msg = lines[j].msg as String
            if (msg.startsWith("ws remote: status: open")) break
            if (msg =~ '^rx remote: .*"ms\\.channel\\.(connect|unauthorized|timeOut)"') {
                all << (tsMs(lines[j].ts as String, tz) - tsMs(lines[i].ts as String, tz))
                break
            }
        }
    }
    Map f = [handshake: handshake, connectMs: all ? all.max() : null, connectMsAll: all,
             tokenInReply: reply ? (reply.msg as String).contains('"token"') : null, openEvent: hasEvent(events, "wsStatus", "open")]
    // A key can only go out after the TV accepted the connection.
    if (handshake == "ms.channel.connect") f.keySent = firstLine(lines, '^tx remote: .*KEY_HOME') != null
    return f
}

// Keys the TV took: one it refused before accepting the connection ("No Authorized") is sent again.
Map evalBurst(List<Map> lines) {
    int refused = countLines(lines, '^rx remote: .*"No Authorized"')
    return [opens: countLines(lines, '^ws remote: status: open'), keysSent: countLines(lines, '^tx remote: .*KEY_HOME') - refused]
}

// How the TV went away after off: REST stopped answering, it reported standby, or its socket timed out.
// The readings are the tester's own reads of the TV's REST page during the hold.
Map evalPowerOff(List<Map> lines, List<Map> events, Map steps, Long startMs, TimeZone tz, boolean alreadyOff, List<Map> readings = []) {
    Map rd = summarizeReadings(readings)
    List<String> how = []
    if (lines.any { (it.msg as String) =~ /^rest: status=(?!200)/ } || rd.stoppedS != null) how << "REST stopped"
    if (lines.any { (it.msg as String) =~ /^rest: status=200 .*PowerState=standby/ } || rd.standbyS != null) how << "PowerState standby"
    if (lines.any { (it.msg as String) =~ /^rx remote: .*ms\.channel\.timeOut/ }) how << "socket timeout"
    // The driver sends switch on every poll, so an off TV still produces off events.
    Map off = events.find { it.name == "switch" && it.value == "off" }
    boolean flap = off != null && events.any { it.name == "switch" && it.value == "on" && (it.at as Long) > (off.at as Long) }
    // The last answer after off, to the poll or a reading: how long the TV keeps answering REST once told to turn off.
    Map last = lines.reverse().find { (it.msg as String).startsWith("rest: status=200") }
    Long after = last ? Math.round((tsMs(last.ts as String, tz) - startMs) / 1000.0d) : 0L
    if (rd.lastAnswerS != null && (rd.lastAnswerS as long) > after) after = rd.lastAnswerS as Long
    Map f = [offEvent: off != null, noFlap: !flap, wentAway: how ? how.join(", ") : "still answering", restAfterOffS: after,
             restStandbyS: rd.standbyS, restStoppedS: rd.stoppedS, readings: rd.text]
    if (alreadyOff) f.alreadyOff = true else f.offMs = steps.off?.ok ? steps.off.ms : null
    return f
}

// The TV's REST page read as the PowerState the driver would see: "none" for no answer, "absent" for an answer without it.
String readingValue(Map info) {
    if (info?.status != 200) return "none"
    String ps = (info.device as Map)?.PowerState?.toString()?.trim()?.toLowerCase()
    return ps ?: "absent"
}

// readings: [ms: since the step's base, v: readingValue]. Seconds are rounded; the text is "0s:none 5s:standby 10s:on".
Map summarizeReadings(List<Map> readings) {
    List<String> parts = []
    Integer standbyS = null, stoppedS = null, lastAnswerS = null
    Long onMs = null
    for (Map r : (readings ?: [])) {
        int sec = Math.round((r.ms as long) / 1000.0d) as int
        String v = r.v as String
        parts << "${sec}s:${v}".toString()
        if (v == "standby" && standbyS == null) standbyS = sec
        if (v == "none" && stoppedS == null) stoppedS = sec
        if (v != "none") lastAnswerS = sec
        if (v == "on" && onMs == null) onMs = r.ms as Long
    }
    return [text: parts.join(" "), standbyS: standbyS, stoppedS: stoppedS, lastAnswerS: lastAnswerS, onMs: onMs]
}

// startMs is the on() call. picture is pictureMs(): ms, "never", "no answer", or null when no question was asked.
Map evalPowerOn(List<Map> lines, List<Map> events, Map steps, Long startMs, TimeZone tz, List<Map> readings = [],
                Object picture = null, int pollS = 60, Long watchMs = null) {
    Map rest = lines.find { (it.msg as String).startsWith("rest: status=200") }
    Map rd = summarizeReadings(readings)
    Map on = events.find { it.name == "switch" && it.value == "on" && (it.at as Long) >= startMs }
    Long onMs = on ? (on.at as Long) - startMs : null
    Long restOnMs = rd.onMs as Long
    Long pictureAt = picture instanceof Number ? (picture as Long) : null
    // Late by 15 s or more: the picture was there well before REST said on.
    boolean powerStateLags = restOnMs != null && pictureAt != null && restOnMs - pictureAt >= 15000L
    // REST said on, and the driver's switch followed neither within a poll interval and 10 s nor before the watch ended.
    long limit = (restOnMs ?: 0L) + pollS * 1000L + 10000L
    boolean switchLags = restOnMs != null && (onMs != null ? onMs > limit : (watchMs != null && watchMs > limit))
    Map f = [onEvent: on != null, onMs: onMs]
    // A TV without PowerState never says on in REST; leave the field out rather than judge it.
    boolean noPowerState = restOnMs == null && readings.any { it.v == "absent" } && !readings.any { it.v in ["on", "standby"] }
    if (!noPowerState) f.restOnMs = restOnMs
    if (picture != null) f.pictureMs = picture
    f.putAll([readings: rd.text, powerStateLags: powerStateLags, switchLags: switchLags,
              wolSends: countLines(lines, '^wol: sent'), firstAnswerMs: rest ? tsMs(rest.ts as String, tz) - startMs : null])
    // The driver asks SmartThings to turn the TV on when Wake-on-LAN got no answer in 17 s.
    if (firstLine(lines, '^stPowerOn: ')) f.stWake = true
    return f
}

// Milliseconds from on() to the person's "picture is on"; null when no question was asked.
Object pictureMs(Map prompt, Long startMs) {
    if (prompt == null) return null
    if (prompt.notAsked) return "not asked"
    if (prompt.choice == "on") return (prompt.atMs as Long) - startMs
    if (prompt.choice == "never") return "never"
    return "no answer"
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

// ── SmartThings: pure helpers ──

// The driver's status line, "[method:statusParse, volume:24, mute:unmuted, ...]", as a map. Empty when
// SmartThings reported the TV off: the driver then logs the method alone.
Map stStatusFields(String msg) {
    Map out = [:]
    String alt = "volume|mute|inputSource|tvChannelName|tvChannel|pictureMode|soundMode"
    String body = (msg ?: "").replaceFirst(/^\[method:statusParse(, )?/, "").replaceFirst(/\]$/, "")
    def m = body =~ ("(?:^|, )(" + alt + "):(.*?)(?=, (?:" + alt + "):|\$)")
    while (m.find()) out[m.group(1)] = m.group(2) == "null" ? null : m.group(2)
    return out
}

// Every status line with its time, in order.
List<Map> stStatuses(List<Map> lines, TimeZone tz) {
    return lines.findAll { (it.msg as String).startsWith("[method:statusParse") }
                .collect { [atMs: tsMs(it.ts as String, tz), vals: stStatusFields(it.msg as String)] }
}

// The first line the driver logs when a SmartThings call fails, masked.
String stProblem(List<Map> lines) {
    Map bad = lines.find { (it.msg as String) =~ /^(deviceCommand: \[status: FAILED|SmartThings rejected the API key|distResp: |asyncGet: \[status|<b>Conflict)/ }
    return bad ? maskLine(bad.msg as String).take(200) : null
}

// A mode list from the driver's state: a list, or its JSON text.
List<String> stModes(Object raw) {
    def v = raw
    if (v instanceof String) { try { v = new JsonSlurper().parseText(v as String) } catch (e) { v = null } }
    return v instanceof List ? (v as List).collect { it as String } : []
}

// What a SmartThings test changes and puts back: [attr, from, to, set: [cmd, args], restore: [cmd, args]], or
// [skip: reason]. The volume goes down by one (by setVolume, then by the volume keys), mute is switched over, a
// mode goes to the next in SmartThings' list by the toggle command. The input and the channel are changed with
// a key (the input key, channel up) to whatever the TV goes to (to: null), then set back through SmartThings.
Map stPlan(int test, Map cur, Map st) {
    if (test == 12 || test == 13) {
        String v = cur.volume?.toString()
        if (v == null || !v.isInteger()) return [skip: "SmartThings has not reported a volume"]
        int n = v as int
        boolean down = n > 0
        String to = (down ? n - 1 : 1).toString()
        if (test == 12) return [attr: "volume", from: v, to: to, set: [cmd: "setVolume", args: [to]], restore: [cmd: "setVolume", args: [v]]]
        return [attr: "volume", from: v, to: to, set: [cmd: down ? "volumeDown" : "volumeUp", args: []],
                restore: [cmd: down ? "volumeUp" : "volumeDown", args: []]]
    }
    if (test == 14) {
        String m = cur.mute?.toString()
        if (!(m in ["muted", "unmuted"])) return [skip: "SmartThings has not reported mute"]
        boolean muted = m == "muted"
        return [attr: "mute", from: m, to: muted ? "unmuted" : "muted", set: [cmd: muted ? "unmute" : "mute", args: []],
                restore: [cmd: muted ? "mute" : "unmute", args: []]]
    }
    if (test == 15 || test == 16) {
        boolean pic = test == 15
        String attr = pic ? "pictureMode" : "soundMode"
        String now = cur[attr]?.toString()
        List<String> modes = stModes(pic ? st?.pictureModes : st?.soundModes)
        int at = modes.indexOf(now)
        if (at < 0 || modes.size() < 2) return [skip: "SmartThings lists no other ${pic ? 'picture' : 'sound'} mode to change to".toString()]
        return [attr: attr, from: now, to: modes[(at + 1) % modes.size()], set: [cmd: pic ? "togglePictureMode" : "toggleSoundMode", args: []],
                restore: [cmd: pic ? "setPictureMode" : "setSoundMode", args: [now]]]
    }
    if (test == 17) {
        String input = cur.inputSource?.toString()
        if (!input || input == "null") return [skip: "SmartThings has not reported an input"]
        return [attr: "inputSource", from: input, to: null, set: [cmd: "sourceToggle", args: []], restore: [cmd: "setInputSource", args: [input]]]
    }
    String ch = cur.tvChannel?.toString()?.trim()
    if (!ch || ch == "null") return [skip: "the TV is not showing a TV channel"]
    return [attr: "tvChannel", from: ch, to: null, set: [cmd: "channelUp", args: []], restore: [cmd: "setTvChannel", args: [ch]]]
}

// The Apps test on a device that uses SmartThings: the app SmartThings reported after the launch, whether the
// driver's name for the app was still there once it had acted on that report, and what SmartThings reported
// after the close.
Map evalAppsSt(List<Map> lines, List<Map> events, Map steps, TimeZone tz) {
    List<Map> all = stStatuses(lines, tz)
    Long launchAt = steps.launch?.at as Long, closeAt = steps.close?.at as Long
    def app = { Map s ->
        Map v = (s?.vals ?: [:]) as Map
        return (!(v.tvChannel as String)?.trim() && (v.tvChannelName as String)?.trim()) ? v.tvChannelName as String : null
    }
    Map open = launchAt == null ? null : all.find { (it.atMs as long) >= launchAt && (closeAt == null || (it.atMs as long) < closeAt) }
    Map shut = closeAt == null ? null : all.find { (it.atMs as long) >= closeAt }
    Map f = [:]
    if (open) {
        f.stAppCode = app(open)
        f.stAppReported = f.stAppCode != null
        List<Map> names = events.findAll { it.name == "appName" && (closeAt == null || (it.at as Long) < closeAt) }
        f.nameKept = names ? (names[-1].value as String)?.trim() as boolean : false
    }
    if (shut) {
        f.stAppAfterClose = app(shut) ?: "none"
        f.stClearsApp = app(shut) == null
    }
    return f
}

// SmartThings' answer to the driver's status request, and which of the TV's capabilities the driver uses.
Map evalStStatus(List<Map> lines, Map steps, Map st, String appName, TimeZone tz) {
    List<Map> all = stStatuses(lines, tz)
    Map v = all ? (all[-1].vals as Map) : null
    Map f = [answered: v != null, statusMs: v != null && steps.status?.ok ? steps.status.ms : null,
             reportsOn: v != null && v.volume != null, problem: stProblem(lines)]
    // A key SmartThings rejected earlier: the driver makes no more requests, so no line says why.
    if (!f.problem && v == null && st?.stAuthFailed == true) f.problem = "the driver stopped using SmartThings: the key was rejected (HTTP 401)"
    if (v) {
        f.putAll([inputSource: v.inputSource, pictureMode: v.pictureMode, soundMode: v.soundMode])
        // A blank channel with a dotted name is an app's code; the driver asks the TV for its name.
        if (!(v.tvChannel as String)?.trim() && (v.tvChannelName as String)?.contains(".")) {
            f.appCode = v.tvChannelName
            f.appNamed = (appName ?: "").trim() != ""
        }
    }
    f.putAll([inputCap: st?.inputSourceCap, pictureCap: st?.pictureModeCap, soundCap: st?.soundModeCap,
              pictureModes: stModes(st?.pictureModes).join("|"), soundModes: stModes(st?.soundModes).join("|")])
    return f
}

// One value changed and put back: what SmartThings answered, what its status showed right after the
// command and after a second read, the driver's event, and the value put back. A plan without a target
// (to: null) is met by any other value.
Map evalStSet(List<Map> lines, List<Map> events, Map steps, Map plan, TimeZone tz) {
    String attr = plan.attr as String
    List<Map> all = stStatuses(lines, tz)
    Long setAt = steps.set?.at as Long, checkAt = steps.check?.at as Long, restoreAt = steps.restore?.at as Long
    Long setEnd = checkAt ?: restoreAt
    def seen = { Long from, Long to ->
        if (from == null) return null
        Map hit = all.find { (it.atMs as long) >= from && (to == null || (it.atMs as long) < to) }
        return hit ? (hit.vals as Map)[attr] as String : null
    }
    String first = seen(setAt, setEnd)
    String later = seen(checkAt, restoreAt)
    List<Map> during = lines.findAll { setEnd == null || tsMs(it.ts as String, tz) < setEnd }
    Object status = "no answer"
    Map failed = firstLine(during, '^deviceCommand: \\[status: FAILED')
    if (failed) {
        def m = (failed.msg as String) =~ /httpCode: (\d+)/
        status = m.find() ? (m.group(1) as Integer) : "failed"
    } else if (firstLine(during, '^SmartThings rejected the API key')) status = 401
    else if (first != null) status = 200
    String last = later ?: first
    String to = plan.to != null ? plan.to as String : (last?.trim() && last != plan.from ? last : null)
    def hit = { String v -> to != null && v == to }
    boolean set = hit(last)
    Map f = [from: plan.from, to: to, commandStatus: status, setMs: status == 200 ? steps.set?.ms : null,
             firstStatus: first, laterStatus: later, valueSet: set, statusLags: !hit(first) && hit(later)]
    if (set) f.event = events.any { it.name == attr && (it.value as String) == to }
    f.restored = seen(restoreAt, null) == plan.from
    return f
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
        // the driver resends what the TV refused before it accepted the connection
        if (outer == "ms.error" && msg.contains('"No Authorized"')) continue
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
                 5: '^rx remote: .*ed\\.installedApp\\.get', 9: '^rx remote: '][test]
    if (!re) return []
    List<String> out = []
    for (Map l : lines) {
        if (!((l.msg as String) =~ re)) continue
        out << "${(l.ts as String).substring(11)} ${maskLine(l.msg as String).take(400)}".toString()
        if (out.size() >= 6) break
    }
    return out
}

// 12 upper-case hex digits from any MAC notation; null when the text is not a MAC address.
String normalizeMac(String mac) {
    String hex = (mac ?: "").replaceAll(/[^0-9A-Fa-f]/, "").toUpperCase()
    return hex.length() == 12 && (mac ?: "").replaceAll(/[0-9A-Fa-f:.\-]/, "") == "" ? hex : null
}

// False when either side is missing or not a MAC.
boolean macsMatch(String a, String b) {
    String x = normalizeMac(a)
    return x != null && x == normalizeMac(b)
}

// The TV shows its Allow prompt to a client with no token; the driver keeps a placeholder until the TV sends one.
boolean needsAllow(String tokenSupport, Object token) {
    return tokenSupport == "true" && (token == null || token.toString() in ["", "12345678"])
}

// ── Pad log: pure helpers ──

// A run restores the trace setting the person had before the pad touched it (the pad's remembered value
// while it is active), else the device's current one.
boolean runStartTrace(Map padTrace, Object current) {
    return padTrace?.active ? padTrace.prev == true : current == true
}

// The log with one more entry, the oldest dropped beyond the cap.
List<Map> padLogAppend(List log, Map entry, int cap) {
    List<Map> out = ((log ?: []) + [entry]) as List<Map>
    if (out.size() > cap) out = out.drop(out.size() - cap) as List<Map>
    return out
}

// The driver's trace, warn and error lines of one press, masked: up to the cut-off timestamp, at most
// maxLines and about 2000 characters, so fifty entries stay small in the app's state.
List<String> padEntryLines(List<Map> lines, String untilTs, int maxLines = 15) {
    List<String> out = []
    int chars = 0
    for (String l : traceLines(lines.findAll { (it.ts as String) < untilTs })) {
        String cut = l.take(200)
        if (out.size() >= maxLines || chars + cut.length() > 2000) break
        out << cut
        chars += cut.length()
    }
    return out
}

// The last n entries as the page sees them (newest last).
List<Map> padLogView(List log, int n) {
    List<Map> l = ((log ?: []) as List<Map>)
    if (l.size() > n) l = l.drop(l.size() - n) as List<Map>
    return l.collect { Map e -> [atMs: e.atMs, button: e.button, key: e.key, lines: (e.lines ?: [])] }
}

String padEntryText(Map e, TimeZone tz) {
    List<String> out = ["${new Date(e.atMs as long).format('HH:mm:ss', tz)} ${e.button}${e.key ? ' ' + e.key : ''}".toString()]
    ((e.lines ?: []) as List).each { out << "   ${it}".toString() }
    return out.join("\n")
}

// The report's "Manual keys" section: presses after fromMs up to toMs; empty when there are none.
String padSection(List log, long fromMs, long toMs, TimeZone tz) {
    List<Map> hit = ((log ?: []) as List<Map>).findAll { (it.atMs as long) > fromMs && (it.atMs as long) <= toMs }
    if (!hit) return ""
    List<String> out = ["Manual keys (remote pad presses since the previous report)"]
    hit.each { out << padEntryText(it, tz) }
    return out.join("\n")
}

// ── Prompts: what the person is asked, and the bookkeeping of the answer ──

// kind -> [text, choices]. The page shows the text and one button per choice.
Map promptSpec(String kind, boolean frame = false) {
    switch (kind) {
        case "allow": return [text: "Choose Allow on the TV.", choices: [[id: "done", label: "Done"]]]
        case "picture": return [text: "Click when the picture is on.",
                                choices: [[id: "on", label: "Picture is on"], [id: "never", label: "It didn't come on"]]]
        case "home": return [text: "Did the TV go to its home screen?",
                             choices: [[id: "yes", label: "Home screen showed"], [id: "no", label: "Nothing happened"]]]
        case "screenOff":
            List choices = [[id: "off", label: "Screen went off"], [id: "on", label: "Still on"]]
            if (frame) choices << [id: "art", label: "Shows art"]
            return [text: "Did the screen go off?", choices: choices]
        case "art": return [text: "Is the TV showing art?", choices: [[id: "art", label: "Art is showing"], [id: "noart", label: "No art"]]]
        case "app": return [text: "Did the app open on the TV?", choices: [[id: "opened", label: "The app opened"], [id: "not", label: "It didn't open"]]]
        case "airplay": return [text: "Does the TV offer AirPlay (Settings, General, Apple AirPlay Settings)?",
                                choices: [[id: "yes", label: "Yes"], [id: "no", label: "No"], [id: "unknown", label: "Don't know"]]]
        default: throw new IllegalArgumentException("unknown prompt ${kind}")
    }
}

Map newPrompt(String kind, String id, int test, long nowMs, int deadlineS, boolean frame = false) {
    Map spec = promptSpec(kind, frame)
    return [id: id, test: test, kind: kind, text: spec.text, choices: spec.choices, askedAtMs: nowMs,
            deadlineMs: nowMs + deadlineS * 1000L, answer: null]
}

// "open" until answered or past its deadline.
String promptOutcome(Map prompt, long nowMs) {
    if (prompt.answer != null) return "answered"
    return nowMs >= (prompt.deadlineMs as long) ? "expired" : "open"
}

// An answer for the open prompt only: a stale id, an answered or expired prompt, or an unknown choice is refused.
Map answerPrompt(Map prompt, String id, String choice, long nowMs) {
    if (prompt == null || prompt.id != id || promptOutcome(prompt, nowMs) != "open") return [ok: false, error: "stale prompt"]
    if (!((prompt.choices as List<Map>)*.id).contains(choice)) return [ok: false, error: "unknown choice"]
    return [ok: true, prompt: prompt + [answer: [choice: choice, atMs: nowMs]]]
}

// The prompt as the page sees it: null once answered or past its deadline.
Map publicPrompt(Map prompt, long nowMs) {
    if (prompt == null || promptOutcome(prompt, nowMs) != "open") return null
    return [id: prompt.id, test: prompt.test, kind: prompt.kind, text: prompt.text, choices: prompt.choices,
            askedAtMs: prompt.askedAtMs, deadlineMs: prompt.deadlineMs]
}

// Report fields from the answers of one test: nothing for a question that wasn't asked of this TV, "not asked" for one
// nobody was there to answer, "no answer" for one that was left open.
Map promptFields(int test, Map prompts) {
    List kinds = [2: ["allow", "home"], 8: ["screenOff"], 7: ["art"], 4: ["app"], 6: ["airplay"]][test] ?: []
    Map out = [:]
    for (String kind : kinds) {
        Map p = prompts[kind] as Map
        if (p == null) continue
        String c = p.choice
        boolean skip = p.notAsked == true
        switch (kind) {
            case "allow": out.allowPrompt = skip ? "not asked" : (c ?: "no answer"); break
            case "home": out.homeSeen = skip ? "not asked" : (c == null ? "no answer" : (c == "yes" ? "yes" : "no")); break
            case "screenOff": out.screenOff = skip ? "not asked" : (c ?: "no answer"); break
            case "art": out.artSeen = skip ? "not asked" : (c == null ? "no answer" : (c == "art" ? "yes" : "no")); break
            case "app": out.appSeen = skip ? "not asked" : (c == null ? "no answer" : (c == "opened" ? "yes" : "no")); break
            default: out.airplayOn = skip ? "not asked" : (c ?: "no answer")
        }
    }
    return out
}

// The run for /status: rows, and the step in progress with its deadline for the countdown.
Map statusRun(Map run, Map names) {
    if (!run?.status) return [status: null, reason: null, startedAtMs: null, rows: [], current: null]
    Map current = null
    List tests = (run.tests ?: []) as List
    if (run.status == "running" && (run.idx as int) < tests.size()) {
        Integer t = tests[run.idx as int] as Integer
        Map cur = (run.cur?.test == t ? run.cur : [:]) as Map
        current = [test: t, name: names[t] ?: "Test ${t}".toString(), step: cur.step, label: cur.label ?: "Starting",
                   startedAtMs: cur.startedAtMs, deadlineMs: cur.deadlineMs]
    }
    return [status: run.status, reason: run.reason, startedAtMs: run.startedAt, rows: testRows(run, names), current: current]
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
    // Discovery heard only another device: say so, whatever the model year expects.
    if (r.test == 6 && (r.observed as Map)?.result == "other") parts << "a Samsung device that isn't this TV answered"
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
        else if (run.status == "running" && i == idx) {
            st = "running"
            detail = (run.cur?.test == t && run.cur?.label) ? run.cur.label as String : "step ${((run.step ?: 0) as int) + 1}".toString()
        }
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
    out << "TV settings: Power On with Mobile ${header.powerOnWithMobile ?: 'unknown'} · IP Remote ${header.ipRemote ?: 'unknown'} · questions ${header.attended == 'no' ? 'off' : 'on'}".toString()
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
    if (header.manualKeys) {
        out << ""
        out << (header.manualKeys as String)
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
    Map keys = [1: ["deviceInfo", "isSupport", "apiVersion"], 2: ["handshake", "connectMs"], 8: ["wentAway", "restAfterOffS"],
                9: ["probeReply"], 7: ["artReady", "statusReply", "setReply", "powerStateInArt"],
                4: ["launchMs", "closeBody"], 5: ["answered", "apps"]]
    Map prof = [:]
    Set<String> other = new LinkedHashSet<String>()
    for (Map r : results) {
        Map obs = (r.observed ?: [:]) as Map
        ((keys[r.test as Integer] ?: []) as List).each { k -> if (obs.containsKey(k)) prof[k] = obs[k] }
        if (obs.otherMessages) other.addAll((obs.otherMessages as String).tokenize(","))
    }
    Map disc = results.find { it.test == 6 && (it.observed as Map)?.result == "found" }
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
void logDebug(String m) { if (debugEnable) log.debug logp('🔍') + m }
