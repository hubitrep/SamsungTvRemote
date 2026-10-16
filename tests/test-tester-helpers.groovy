// Copyright 2026 hubitrep
// SPDX-License-Identifier: Apache-2.0
//
// Unit test for the Samsung TV Remote Tester's pure helpers: extracts them from
// SamsungTVRemoteTester.groovy by brace matching and runs them under plain Groovy.
//
// Run: groovy tests/test-tester-helpers.groovy   (about 5 s, no hub)

File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
File src = new File(scriptFile.parentFile, 'SamsungTVRemoteTester.groovy')
assert src.exists() : "source not found: ${src}"
String text = src.text

String extract(String text, String signature) {
    int start = text.indexOf(signature)
    assert start >= 0 : "signature not found: ${signature}"
    int open = text.indexOf('{', start)
    int depth = 0
    for (int i = open; i < text.length(); i++) {
        if (text[i] == '{') depth++
        else if (text[i] == '}') { depth--; if (depth == 0) return text.substring(start, i + 1) }
    }
    throw new IllegalStateException("unbalanced braces for: ${signature}")
}

List<String> SIGNATURES = [
    'Map parseLogLine(String raw, Long devId) {',
    'List<Map> deviceLines(List raw, Long devId, String since) {',
    'Long tsMs(String ts, TimeZone tz) {',
    'String maskLine(String s) {',
    'List<String> traceLines(List<Map> lines) {',
    'private Map lastRest(List<Map> lines) {',
    'private Map firstLine(List<Map> lines, String re) {',
    'private int countLines(List<Map> lines, String re) {',
    'boolean hasEvent(List<Map> events, String name, String value) {',
    'String lastEvent(List<Map> events, String name) {',
    'Map evalConnection(List<Map> lines, Map steps) {',
    'Map evalDeviceInfo(List<Map> lines, String powerStateReported) {',
    'Map evalRemoteSocket(List<Map> lines, List<Map> events, TimeZone tz) {',
    'Map evalBurst(List<Map> lines) {',
    'Map evalPowerOff(List<Map> lines, List<Map> events, Map steps, Long startMs) {',
    'Map evalPowerOn(List<Map> lines, List<Map> events, Map steps, Long startMs, TimeZone tz) {',
    'Map evalStandby(List<Map> lines, List<Map> events) {',
    'Map evalArt(List<Map> lines, List<Map> events) {',
    'Map evalApps(List<Map> lines, List<Map> events) {',
    'Map evalAppList(List<Map> lines) {',
    'boolean runStalled(Map run, long nowMs) {',
    'Map splitDiffs(List diffs, Map expect) {',
    'String verdictOf(Map r) {',
    'String verdictText(Map r) {',
    'List<Map> testRows(Map run, Map names) {',
    'List<String> differences(Map fields, Map expect) {',
    'String formatReport(Map header, List<Map> results) {',
    'int pollGap(int timeoutS, Long lastReadMs) {',
]
String IMPORTS = "import groovy.json.JsonSlurper\nimport java.text.SimpleDateFormat\n"
def h = new GroovyShell().parse(IMPORTS + SIGNATURES.collect { extract(text, it) }.join("\n") + "\nreturn this\n").run()

int pass = 0, fail = 0
def check = { String name, boolean cond ->
    if (cond) { pass++; println "  [PASS] ${name}" } else { fail++; println "  [FAIL] ${name}" }
}

TimeZone utc = TimeZone.getTimeZone('UTC')
String P = "dev|42|Living TV|Living TV-2.4.2: "
def raw = { String ts, String level, String msg -> "2026-10-15 ${ts}\t${level}\t${P}${msg}".toString() }
def line = { String ts, String msg, String level = 'trace' -> [ts: "2026-10-15 ${ts}".toString(), level: level, msg: msg] }

println 'sandbox-blocked property names'
// The hub refuses these as property names at run time (SecurityException), and neither the
// parse-check nor the lint sees it: keep them out of map keys and dot access in the app.
['fields', 'metaClass', 'methods', 'properties', 'class'].each { String n ->
    check "no .${n} or ${n}: in the app", !(text =~ /\.${n}\b(?!\s*\()|[\[,(]\s*${n}\s*:/)
}

println 'parseLogLine'
Map p = h.parseLogLine(raw('20:58:00.099', 'TRACE', 'rest: status=200 ms=27 PowerState=on keys=OS'), 42L)
check 'ts', p.ts == '2026-10-15 20:58:00.099'
check 'level lower-cased', p.level == 'trace'
check 'label and version stripped', p.msg == 'rest: status=200 ms=27 PowerState=on keys=OS'
check 'padded level trimmed', h.parseLogLine("2026-10-15 20:58:00.099\tINFO \t${P}setPowerState: [switch: on]", 42L).msg == 'setPowerState: [switch: on]'
check 'label with a dash', h.parseLogLine("2026-10-15 20:58:00.099\tINFO\tdev|42|TV - den|TV - den-2.4.2: on", 42L).msg == 'on'
check 'other device ignored', h.parseLogLine("2026-10-15 20:58:00.099\tINFO\tdev|7|Lamp|Lamp: on", 42L) == null
check 'garbage ignored', h.parseLogLine('Possible solutions: foo', 42L) == null

println 'deviceLines'
List<Map> d = h.deviceLines([raw('20:58:10.000', 'TRACE', 'b'), raw('20:58:05.000', 'TRACE', 'a'),
                             raw('20:50:00.000', 'TRACE', 'old')], 42L, '2026-10-15 20:55:00.000')
check 'filters since', d.size() == 2
check 'sorted by time', d[0].msg == 'a' && d[1].msg == 'b'

println 'maskLine'
check 'ip', h.maskLine('uri http://192.0.2.10:8001/api/v2/') == 'uri http://<ip>:8001/api/v2/'
check 'mac', h.maskLine('"wifiMac": "f4:7b:09:12:34:56"') == '"wifiMac": "<mac>"'
check 'uuid', h.maskLine('"id": "8dba7c04-359e-41f3-8e34-f0ab84f4191e"') == '"id": "<uuid>"'
check 'names', h.maskLine('{"name": "[TV] Living room", "deviceName": "SGVsbG8="}') == '{"name":"<name>", "deviceName":"<name>"}'
check 'token', h.maskLine('{"token": 12345678}') == '{"token":"<token>"}'
check 'already masked token kept', h.maskLine('{"token":"<token>"}') == '{"token":"<token>"}'
check 'version kept', h.maskLine('"version": "2.0.25"') == '"version": "2.0.25"'

println 'traceLines'
List<String> tl = h.traceLines([line('20:58:00.099', 'rest: status=200 ms=27 PowerState=on keys=A,B,C'),
                                line('20:58:00.200', 'setPowerState: [switch: on]', 'info'),
                                line('20:58:01.000', 'rx remote: {"data": {"id": "8dba7c04-359e-41f3-8e34-f0ab84f4191e"}}')])
check 'info lines left out', tl.size() == 2
check 'rest keys dropped', tl[0] == '20:58:00.099 rest: status=200 ms=27 PowerState=on'
check 'masked', tl[1].contains('<uuid>')

println 'evalConnection'
Map c = h.evalConnection([line('20:58:00.000', 'wol: sent'), line('20:58:30.000', 'rest: status=200 ms=40 PowerState=on keys=OS')], [:])
check 'reachable after wol', c.reachable == true && c.wolSent == true
Map c2 = h.evalConnection([line('20:58:30.000', 'rest: status=408 ms=6003 PowerState=- keys='),
                           line('20:58:31.000', 'configure: no reply from 192.0.2.10', 'warn')], [:])
check 'unreachable with masked error', c2.reachable == false && c2.firstError == 'configure: no reply from <ip>'

println 'evalDeviceInfo'
Map di = h.evalDeviceInfo([line('20:58:00.099', 'rest: status=200 ms=27 PowerState=on keys=FrameTVSupport,OS,PowerState,TokenAuthSupport')], 'true')
check 'status, time, PowerState', di.restStatus == 200 && di.restMs == 27L && di.powerState == 'on' && di.powerStateReported == true
check 'keys', di.keyCount == 4 && di.frameKey && di.tokenKey
Map di2 = h.evalDeviceInfo([line('20:58:00.099', 'rest: status=200 ms=27 PowerState=absent keys=OS')], 'false')
check 'absent PowerState', di2.powerStateReported == false

println 'evalRemoteSocket'
List<Map> rs = [line('20:57:55.661', 'ws remote: status: open'),
                line('20:57:57.708', 'rx remote: {"data": {"clients": [], "token":"<token>"}, "event": "ms.channel.connect"}'),
                line('20:57:57.758', 'tx remote: {"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"KEY_HOME"}}')]
Map r2 = h.evalRemoteSocket(rs, [[name: 'wsStatus', value: 'open', at: 1L]], utc)
check 'handshake and time', r2.handshake == 'ms.channel.connect' && r2.connectMs == 2047L
check 'token and key', r2.tokenInReply == true && r2.keySent == true && r2.openEvent == true
Map r3 = h.evalRemoteSocket([line('20:57:55.661', 'ws remote: status: open'), line('20:57:57.000', 'rx remote: {"event": "ms.channel.unauthorized"}')], [], utc)
check 'refused', r3.handshake == 'ms.channel.unauthorized' && r3.keySent == false

println 'evalBurst'
List<Map> kb = [line('1:00:00.000', 'ws remote: status: open')] + (1..3).collect { line('1:00:01.000', 'tx remote: {"DataOfCmd":"KEY_HOME"}') }
check 'one open, three keys', h.evalBurst(kb) == [opens: 1, keysSent: 3]

println 'evalPowerOff'
long t0 = 1000L
List<Map> evOff = [[name: 'switch', value: 'off', at: 2000L], [name: 'switch', value: 'off', at: 12000L]]
Map po = h.evalPowerOff([line('20:31:23.000', 'rest: status=408 ms=6000 PowerState=- keys=')], evOff, [off: [ok: true, ms: 1200L]], t0)
check 'off, no flap, REST stopped', po.offEvent && po.noFlap && po.offMs == 1200L && po.wentAway == 'REST stopped'
Map po2 = h.evalPowerOff([line('20:31:23.000', 'rest: status=200 ms=30 PowerState=standby keys=OS')],
                         evOff + [[name: 'switch', value: 'on', at: 13000L]], [off: [ok: true, ms: 1200L]], t0)
check 'flap and standby seen', !po2.noFlap && po2.wentAway == 'PowerState standby'

println 'evalPowerOn'
Map pn = h.evalPowerOn([line('20:26:36.500', 'wol: sent'), line('20:26:46.500', 'wol: sent'),
                        line('20:26:56.726', 'rest: status=200 ms=40 PowerState=on keys=OS')],
                       [[name: 'switch', value: 'on', at: 5L]], [on: [ok: true, ms: 20179L]],
                       h.tsMs('2026-10-15 20:26:36.496', utc), utc)
check 'on, sends, first answer', pn.onEvent && pn.onMs == 20179L && pn.wolSends == 2 && pn.firstAnswerMs == 20230L

println 'evalStandby'
check 'timeout reply, stayed off', h.evalStandby([line('20:31:41.326', 'rx remote: {"event": "ms.channel.timeOut"}')], []) ==
    [probeReply: 'ms.channel.timeOut', stayedOff: true]
check 'connect failure', h.evalStandby([line('20:31:41.326', 'ws remote: failure: timeout')], [[name: 'switch', value: 'on', at: 1L]]) ==
    [probeReply: 'connect failed', stayedOff: false]

println 'evalArt'
String status = 'rx frameArt: {"data": "{\\"event\\": \\"artmode_status\\", \\"value\\": \\"on\\", \\"id\\": \\"x\\"}", "event": "d2d_service_message"}'
String compact = 'rx frameArt: {"data":"{\\"event\\":\\"artmode_status\\",\\"value\\":\\"off\\"}","event":"d2d_service_message"}'
List<Map> art = [line('20:57:53.600', 'rx frameArt: {"data": {}, "event": "ms.channel.ready"}'), line('20:57:53.642', status),
                 line('20:58:06.444', 'tx frameArt: {"params":{"data":"{\\"value\\":\\"on\\",\\"request\\":\\"set_artmode_status\\"}"}}')]
Map a = h.evalArt(art, [[name: 'artModeStatus', value: 'on', at: 1L]])
check 'ready, status, event, set over art', a.artReady && a.statusReply == 'on' && a.statusEventMatches && a.setVia == 'art channel'
check 'compact JSON', h.evalArt([line('1:00:00.000', compact)], [[name: 'artModeStatus', value: 'off', at: 1L]]).statusReply == 'off'
Map a2 = h.evalArt([line('1:00:00.000', status), line('1:00:01.000', 'tx remote: {"DataOfCmd":"KEY_POWER"}')], [])
check 'no event for the reply; blind key', !a2.statusEventMatches && a2.setVia == 'power key' && !a2.artReady

println 'evalApps'
Map ap = h.evalApps([line('1:00:00.000', 'app: POST 111299001912 status=200 body=true'),
                     line('1:00:06.000', 'app: DELETE 111299001912 status=200 body=true')],
                    [[name: 'appName', value: 'YouTube', at: 1L], [name: 'appName', value: ' ', at: 2L]])
check 'launch and close', ap == [launchStatus: 200, closeStatus: 200, closeBody: 'true', appEvent: true, closeEvent: true]

println 'evalAppList'
Map al = h.evalAppList([line('1:00:00.000', 'rx remote: {"data": {"data": [{"appId": "1", "name": "YouTube"}, {"appId": "2", "name": "Deezer"}]}, "event": "ed.installedApp.get", "from": "host"}')])
check 'answered with a count', al == [answered: true, apps: 2]
check 'not answered', h.evalAppList([]) == [answered: false, apps: null]

println 'differences and verdicts'
Map expect = [a: [eq: true], b: [max: 100, driver: true], c: [ne: null]]
List diffs = h.differences([a: false, b: 200, c: null], expect)
check 'all three differ', diffs == ['a', 'b', 'c']
Map split = h.splitDiffs(diffs, expect)
check 'split', split.driverDiffs == ['b'] && split.tvDiffs == ['a', 'c']
check 'verdict driver', h.verdictOf([diffs: diffs] + split) == 'driver'
check 'verdict text', h.verdictText([diffs: diffs] + split) == 'driver differs (b); TV differs (a, c)'
check 'skipped', h.verdictText([skipped: 'not a Frame TV']) == 'skipped: not a Frame TV'
check 'as expected', h.verdictText([diffs: [], driverDiffs: [], tvDiffs: []]) == 'as expected'

println 'testRows'
List<Map> rows = h.testRows([status: 'running', tests: [0, 1, 2, 3], idx: 2, step: 1,
                             results: [[test: 1, diffs: [], driverDiffs: [], tvDiffs: []]]], [1: 'Device info', 2: 'Remote socket', 3: 'Key burst'])
check 'precheck hidden, states', rows*.state == ['ok', 'running', 'waiting'] && rows[1].detail == 'step 2'

println 'runStalled'
check 'late by over a minute', h.runStalled([status: 'running', nextDueMs: 1000L], 62000L)
check 'on time', !h.runStalled([status: 'running', nextDueMs: 1000L], 30000L)

println 'formatReport'
String rep = h.formatReport([appVersion: '0.1.0', driverVersion: '2.4.2', firmware: '2.5.2.135', modelName: 'UN50TU7000FXZC',
                             platform: '20_KANTSU2_UHD_BASIC', apiVersion: '2.0.25', frameTv: 'false', tokenSupport: 'true',
                             powerStateReported: 'true', smartThings: false, pollS: 60, slowestReadMs: 120, status: 'done'],
                            [[test: 9, name: 'App list', observed: [answered: false, apps: null], diffs: ['answered'], driverDiffs: [], tvDiffs: ['answered'],
                              trace: ['20:58:00.000 tx remote: {}']]])
check 'header', rep.contains('TV: UN50TU7000FXZC · platform 20_KANTSU2_UHD_BASIC') && rep.contains('SmartThings not linked')
check 'test line and trace', rep.contains('9. App list — TV differs (answered)') && rep.contains('     20:58:00.000 tx remote: {}')

println 'pollGap'
check 'short wait: 1 s', h.pollGap(15, 400L) == 1
check 'slow read sets the gap', h.pollGap(15, 3200L) == 4
check 'long wait: at least 3 s', h.pollGap(90, 400L) == 3

println "\n${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)
