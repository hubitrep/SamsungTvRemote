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
    'Map evalPowerOff(List<Map> lines, List<Map> events, Map steps, Long startMs, TimeZone tz, boolean alreadyOff, List<Map> readings = []) {',
    'String readingValue(Map info) {',
    'Map summarizeReadings(List<Map> readings) {',
    'Map evalPowerOn(List<Map> lines, List<Map> events, Map steps, Long startMs, TimeZone tz, List<Map> readings = [],',
    'Object pictureMs(Map prompt, Long startMs) {',
    'String normalizeMac(String mac) {',
    'boolean macsMatch(String a, String b) {',
    'boolean needsAllow(String tokenSupport, Object token) {',
    'Map promptSpec(String kind, boolean frame = false) {',
    'Map newPrompt(String kind, String id, int test, long nowMs, int deadlineS, boolean frame = false) {',
    'String promptOutcome(Map prompt, long nowMs) {',
    'Map answerPrompt(Map prompt, String id, String choice, long nowMs) {',
    'Map publicPrompt(Map prompt, long nowMs) {',
    'Map promptFields(int test, Map prompts) {',
    'Map statusRun(Map run, Map names) {',
    'Map evalStandby(List<Map> lines, List<Map> events) {',
    'Map evalArt(List<Map> lines, List<Map> events) {',
    'Map evalApps(List<Map> lines, List<Map> events, Map steps) {',
    'Map deviceInfoFields(Map info, List keys) {',
    'String otherMessages(List<Map> lines, List known, List knownArt) {',
    'List<String> sampleLines(int test, List<Map> lines) {',
    'Map evalAppList(List<Map> lines) {',
    'Map evalDiscovery(List<Map> lines, String ip) {',
    'Integer modelYear(String model) {',
    'Map discoveryExpect(Integer year) {',
    'Map stStatusFields(String msg) {',
    'List<Map> stStatuses(List<Map> lines, TimeZone tz) {',
    'String stProblem(List<Map> lines) {',
    'List<String> stModes(Object raw) {',
    'Map stPlan(int test, Map cur, Map st) {',
    'Map evalStStatus(List<Map> lines, Map steps, Map st, String appName, TimeZone tz) {',
    'Map evalStSet(List<Map> lines, List<Map> events, Map steps, Map plan, TimeZone tz) {',
    'Map evalAppsSt(List<Map> lines, List<Map> events, Map steps, TimeZone tz) {',
    'boolean runStalled(Map run, long nowMs) {',
    'Map splitDiffs(List diffs, Map expect) {',
    'String verdictOf(Map r) {',
    'String verdictText(Map r) {',
    'List<Map> testRows(Map run, Map names) {',
    'List<String> differences(Map fields, Map expect) {',
    'String formatReport(Map header, List<Map> results) {',
    'Map profileFields(List<Map> results) {',
    'int pollGap(int timeoutS, Long lastReadMs) {',
    'Map padCommand(String name, String key = null) {',
    'boolean runStartTrace(Map padTrace, Object current) {',
    'List<Map> padLogAppend(List log, Map entry, int cap) {',
    'List<String> padEntryLines(List<Map> lines, String untilTs, int maxLines = 15) {',
    'List<Map> padLogView(List log, int n) {',
    'String padEntryText(Map e, TimeZone tz) {',
    'String padSection(List log, long fromMs, long toMs, TimeZone tz) {',
]
String IMPORTS = "import groovy.json.JsonOutput\nimport groovy.json.JsonSlurper\nimport java.text.SimpleDateFormat\n"
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
check 'DNI mac', h.maskLine('dni:F47B09123456 x') == 'dni:<mac> x'
check 'app code kept', h.maskLine('appCode:111299001912') == 'appCode:111299001912'

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
check 'refused: key not judged', r3.handshake == 'ms.channel.unauthorized' && !r3.containsKey('keySent')

List<Map> three = []
[['20:00:00.000', '20:00:01.500'], ['20:00:10.000', '20:00:11.458'], ['20:00:20.000', '20:00:26.618']].each { o, r ->
    three << line(o, 'ws remote: status: open') << line(r, 'rx remote: {"data": {}, "event": "ms.channel.connect"}')
}
Map r4 = h.evalRemoteSocket(three, [], utc)
check 'three connects: slowest and all', r4.connectMs == 6618L && r4.connectMsAll == [1500L, 1458L, 6618L] && r4.handshake == 'ms.channel.connect'
check 'an open with no answer is skipped', h.evalRemoteSocket([line('1:00:00.000', 'ws remote: status: open')], [], utc).with { it.connectMs == null && it.connectMsAll == [] }
check 'one connect keeps connectMs', r2.connectMsAll == [2047L]

println 'evalBurst'
List<Map> kb = [line('1:00:00.000', 'ws remote: status: open')] + (1..3).collect { line('1:00:01.000', 'tx remote: {"DataOfCmd":"KEY_HOME"}') }
check 'one open, three keys', h.evalBurst(kb) == [opens: 1, keysSent: 3]
List<Map> kr = kb + (1..3).collect { line('1:00:02.000', 'rx remote: {"data":{"message":"No Authorized"},"event":"ms.error"}') } +
               (1..3).collect { line('1:00:07.000', 'tx remote: {"DataOfCmd":"KEY_HOME"}') }
check 'refused before the connect, sent again: three keys', h.evalBurst(kr) == [opens: 1, keysSent: 3]

println 'evalPowerOff'
long t0 = h.tsMs('2026-10-15 20:31:20.000', utc)
List<Map> evOff = [[name: 'switch', value: 'off', at: 2000L], [name: 'switch', value: 'off', at: 12000L]]
Map po = h.evalPowerOff([line('20:31:23.000', 'rest: status=408 ms=6000 PowerState=- keys=')], evOff, [off: [ok: true, ms: 1200L]], t0, utc, false)
check 'off, no flap, REST stopped', po.offEvent && po.noFlap && po.offMs == 1200L && po.wentAway == 'REST stopped' && po.restAfterOffS == 0L
Map po2 = h.evalPowerOff([line('20:31:23.000', 'rest: status=200 ms=30 PowerState=standby keys=OS')],
                         evOff + [[name: 'switch', value: 'on', at: 13000L]], [off: [ok: true, ms: 1200L]], t0, utc, false)
check 'flap and standby seen', !po2.noFlap && po2.wentAway == 'PowerState standby' && po2.restAfterOffS == 3L

Map po3 = h.evalPowerOff([], evOff, [off: [ok: true, ms: 300L]], t0, utc, true)
check 'already off: no offMs', po3.alreadyOff == true && !po3.containsKey('offMs')

println 'evalPowerOn'
long on0 = h.tsMs('2026-10-15 20:26:36.496', utc)
Map pn = h.evalPowerOn([line('20:26:36.500', 'wol: sent'), line('20:26:46.500', 'wol: sent'),
                        line('20:26:56.726', 'rest: status=200 ms=40 PowerState=on keys=OS')],
                       [[name: 'switch', value: 'on', at: on0 + 20179L]], [:], on0, utc)
check 'on, sends, first answer', pn.onEvent && pn.onMs == 20179L && pn.wolSends == 2 && pn.firstAnswerMs == 20230L
check 'an on event before the call is not this on', h.evalPowerOn([], [[name: 'switch', value: 'on', at: on0 - 1L]], [:], on0, utc).onEvent == false

def rd = { long ms, String v -> [ms: ms, v: v] }
List<Map> okReadings = [rd(0, 'none'), rd(5000, 'standby'), rd(10000, 'on'), rd(15000, 'on')]
Map pr = h.evalPowerOn([], [[name: 'switch', value: 'on', at: on0 + 12000L]], [:], on0, utc, okReadings, 9000L, 60, 30000L)
check 'REST on time, picture, readings text', pr.restOnMs == 10000L && pr.pictureMs == 9000L && pr.readings == '0s:none 5s:standby 10s:on 15s:on'
check 'in step: neither lags', pr.powerStateLags == false && pr.switchLags == false
Map pl = h.evalPowerOn([], [[name: 'switch', value: 'on', at: on0 + 12000L]], [:], on0, utc, [rd(0, 'standby'), rd(30000, 'on')], 5000L, 60, 40000L)
check 'picture 25 s before REST: PowerState lags', pl.powerStateLags == true
check 'picture 14.9 s before REST: not yet a lag', h.evalPowerOn([], [], [:], on0, utc, [rd(20000, 'on')], 5100L).powerStateLags == false
check 'no picture answer: no lag judged', h.evalPowerOn([], [], [:], on0, utc, [rd(20000, 'on')], 'no answer').powerStateLags == false &&
    h.evalPowerOn([], [], [:], on0, utc, [rd(20000, 'on')], 'never').powerStateLags == false
check 'no question asked: no pictureMs field', !h.evalPowerOn([], [], [:], on0, utc, [rd(20000, 'on')]).containsKey('pictureMs')
// REST said on at 10 s; the switch may take a poll (60 s) plus 10 s more: until 80 s.
check 'switch on at 70 s: within the limit', h.evalPowerOn([], [[name: 'switch', value: 'on', at: on0 + 70000L]], [:], on0, utc, [rd(10000, 'on')], null, 60, 90000L).switchLags == false
check 'switch on at 85 s: lags', h.evalPowerOn([], [[name: 'switch', value: 'on', at: on0 + 85000L]], [:], on0, utc, [rd(10000, 'on')], null, 60, 90000L).switchLags == true
check 'no switch on after the limit: lags', h.evalPowerOn([], [], [:], on0, utc, [rd(10000, 'on')], null, 60, 120000L).switchLags == true
check 'no switch on, watch ended before the limit: not judged', h.evalPowerOn([], [], [:], on0, utc, [rd(100000L, 'on')], null, 60, 120000L).switchLags == false
check 'REST never on: switch not judged, restOnMs null', h.evalPowerOn([], [], [:], on0, utc, [rd(0, 'standby')], null, 60, 120000L).with { it.switchLags == false && it.containsKey('restOnMs') && it.restOnMs == null }
check 'TV without PowerState: no restOnMs field', !h.evalPowerOn([], [[name: 'switch', value: 'on', at: on0 + 1000L]], [:], on0, utc, [rd(0, 'absent'), rd(5000, 'absent')]).containsKey('restOnMs')
check 'differences: restOnMs over 45 s, driver lag',
    h.differences([onEvent: true, restOnMs: 50000L, powerStateLags: false, switchLags: true],
                  [onEvent: [eq: true, driver: true], restOnMs: [max: 45000, optional: true], powerStateLags: [eq: false], switchLags: [eq: false, driver: true]]) == ['restOnMs', 'switchLags']

println 'pictureMs'
check 'seconds from on()', h.pictureMs([choice: 'on', atMs: 5000L], 2000L) == 3000L
check 'never, no answer, not asked', h.pictureMs([choice: 'never', atMs: 1L], 0L) == 'never' && h.pictureMs([choice: null], 0L) == 'no answer' && h.pictureMs(null, 0L) == null

println 'readings'
check 'value: no answer, PowerState, absent', h.readingValue([status: 'no answer']) == 'none' && h.readingValue([status: 200, device: [PowerState: 'Standby']]) == 'standby' &&
    h.readingValue([status: 200, device: [OS: 'Tizen']]) == 'absent' && h.readingValue([status: 200]) == 'absent'
Map sm = h.summarizeReadings([rd(0, 'on'), rd(5100, 'standby'), rd(9900, 'standby'), rd(15000, 'none'), rd(20000, 'none')])
check 'summary: first standby, first none, last answer', sm.standbyS == 5 && sm.stoppedS == 15 && sm.lastAnswerS == 10 &&
    sm.text == '0s:on 5s:standby 10s:standby 15s:none 20s:none' && sm.onMs == 0L
check 'summary of nothing', h.summarizeReadings([]) == [text: '', standbyS: null, stoppedS: null, lastAnswerS: null, onMs: null]
Map pr1 = h.evalPowerOff([], evOff, [off: [ok: true, ms: 1200L]], t0, utc, false, [rd(4000, 'standby'), rd(9000, 'none'), rd(14000, 'none')])
check 'off with readings: standby then stopped', pr1.wentAway == 'REST stopped, PowerState standby' && pr1.restStandbyS == 4 && pr1.restStoppedS == 9 && pr1.restAfterOffS == 4L && pr1.readings == '4s:standby 9s:none 14s:none'
check 'off with readings, never stopped', h.evalPowerOff([], evOff, [off: [ok: true, ms: 1L]], t0, utc, false, [rd(4000, 'on'), rd(9000, 'on')]).with { it.wentAway == 'still answering' && it.restStoppedS == null && it.restAfterOffS == 9L }

println 'mac'
check 'normalize', h.normalizeMac('f4:7b:09:12:34:56') == 'F47B09123456' && h.normalizeMac('F4-7B-09-12-34-56') == 'F47B09123456' && h.normalizeMac('F47B09123456') == 'F47B09123456'
check 'normalize refuses', h.normalizeMac(null) == null && h.normalizeMac('') == null && h.normalizeMac('f4:7b:09') == null && h.normalizeMac('zz:7b:09:12:34:56') == null
check 'match across notations', h.macsMatch('F47B09123456', 'f4:7b:09:12:34:56')
check 'mismatch or missing is false', !h.macsMatch('F47B09123456', 'f4:7b:09:12:34:57') && !h.macsMatch(null, null) && !h.macsMatch('F47B09123456', null)

println 'needsAllow'
check 'no token', h.needsAllow('true', null) && h.needsAllow('true', '') && h.needsAllow('true', '12345678')
check 'has a token, or no token auth', !h.needsAllow('true', '87654321') && !h.needsAllow('false', null) && !h.needsAllow(null, null)

println 'prompts'
Map pp = h.newPrompt('picture', 'p7', 10, 1000L, 120)
check 'picture prompt', pp.id == 'p7' && pp.test == 10 && pp.text == 'Click when the picture is on.' && pp.deadlineMs == 121000L && (pp.choices*.id) == ['on', 'never'] && pp.answer == null
check 'every kind has a spec', ['allow', 'picture', 'art', 'app', 'airplay', 'home', 'screenOff'].every { h.promptSpec(it).choices }
check 'open, expired', h.promptOutcome(pp, 120999L) == 'open' && h.promptOutcome(pp, 121000L) == 'expired'
Map ans = h.answerPrompt(pp, 'p7', 'on', 5000L)
check 'answer stored', ans.ok && ans.prompt.answer == [choice: 'on', atMs: 5000L] && h.promptOutcome(ans.prompt, 6000L) == 'answered'
check 'stale id', h.answerPrompt(pp, 'p6', 'on', 5000L) == [ok: false, error: 'stale prompt'] && h.answerPrompt(null, 'p7', 'on', 5000L) == [ok: false, error: 'stale prompt']
check 'second answer is stale', h.answerPrompt(ans.prompt, 'p7', 'never', 6000L).error == 'stale prompt'
check 'answer after the deadline is stale', h.answerPrompt(pp, 'p7', 'on', 121000L).error == 'stale prompt'
check 'unknown choice', h.answerPrompt(pp, 'p7', 'maybe', 5000L).error == 'unknown choice'
check 'page sees open prompts only, without the answer', h.publicPrompt(pp, 5000L).keySet().containsAll(['id', 'test', 'kind', 'text', 'choices', 'askedAtMs', 'deadlineMs']) &&
    !h.publicPrompt(pp, 5000L).containsKey('answer') && h.publicPrompt(ans.prompt, 6000L) == null && h.publicPrompt(pp, 200000L) == null && h.publicPrompt(null, 1L) == null
check 'fields: art, app, airplay, allow',
    h.promptFields(7, [art: [choice: 'art']]) == [artSeen: 'yes'] && h.promptFields(7, [art: [choice: 'noart']]) == [artSeen: 'no'] &&
    h.promptFields(4, [app: [choice: 'opened']]) == [appSeen: 'yes'] && h.promptFields(4, [app: [choice: null]]) == [appSeen: 'no answer'] &&
    h.promptFields(6, [airplay: [choice: 'unknown']]) == [airplayOn: 'unknown'] && h.promptFields(2, [allow: [choice: 'done']]) == [allowPrompt: 'done']
check 'fields: not asked', h.promptFields(6, [:]) == [:] && h.promptFields(10, [picture: [choice: 'on']]) == [:]

check 'home prompt', h.promptSpec('home').text == 'Did the TV go to its home screen?' && (h.promptSpec('home').choices*.id) == ['yes', 'no'] &&
    (h.promptSpec('home').choices*.label) == ['Home screen showed', 'Nothing happened']
check 'screenOff prompt: art only on a Frame', (h.promptSpec('screenOff').choices*.id) == ['off', 'on'] && (h.promptSpec('screenOff', true).choices*.id) == ['off', 'on', 'art'] &&
    (h.promptSpec('screenOff', true).choices*.label) == ['Screen went off', 'Still on', 'Shows art'] && h.promptSpec('screenOff').text == 'Did the screen go off?'
check 'newPrompt carries the Frame flag', (h.newPrompt('screenOff', 'p9', 4, 0L, 30, true).choices*.id).contains('art') && !(h.newPrompt('screenOff', 'p9', 4, 0L, 30).choices*.id).contains('art') &&
    h.newPrompt('screenOff', 'p9', 4, 1000L, 30).deadlineMs == 31000L
check 'fields: home, screenOff',
    h.promptFields(2, [home: [choice: 'yes']]) == [homeSeen: 'yes'] && h.promptFields(2, [home: [choice: 'no']]) == [homeSeen: 'no'] && h.promptFields(2, [home: [choice: null]]) == [homeSeen: 'no answer'] &&
    h.promptFields(2, [allow: [choice: 'done'], home: [choice: 'yes']]) == [allowPrompt: 'done', homeSeen: 'yes'] &&
    h.promptFields(8, [screenOff: [choice: 'off']]) == [screenOff: 'off'] && h.promptFields(8, [screenOff: [choice: 'on']]) == [screenOff: 'on'] &&
    h.promptFields(8, [screenOff: [choice: 'art']]) == [screenOff: 'art'] && h.promptFields(8, [screenOff: [choice: null]]) == [screenOff: 'no answer']
check 'fields: not asked when nobody was there',
    h.promptFields(2, [allow: [notAsked: true], home: [notAsked: true]]) == [allowPrompt: 'not asked', homeSeen: 'not asked'] &&
    h.promptFields(8, [screenOff: [notAsked: true]]) == [screenOff: 'not asked'] && h.promptFields(7, [art: [notAsked: true]]) == [artSeen: 'not asked'] &&
    h.promptFields(4, [app: [notAsked: true]]) == [appSeen: 'not asked'] && h.promptFields(6, [airplay: [notAsked: true]]) == [airplayOn: 'not asked']
check 'pictureMs not asked', h.pictureMs([notAsked: true], 0L) == 'not asked'

println 'statusRun'
Map sr = h.statusRun([status: 'running', startedAt: 99L, tests: [0, 1, 10], idx: 2, step: 0,
                      results: [[test: 1, diffs: [], driverDiffs: [], tvDiffs: []]],
                      cur: [test: 10, step: 'watch', label: 'Waiting for the TV to report on', startedAtMs: 100L, deadlineMs: 120100L]], [1: 'Device info', 10: 'Power on'])
check 'rows with the step label', sr.rows*.state == ['ok', 'running'] && sr.rows[1].detail == 'Waiting for the TV to report on' && sr.startedAtMs == 99L
check 'current step', sr.current == [test: 10, name: 'Power on', step: 'watch', label: 'Waiting for the TV to report on', startedAtMs: 100L, deadlineMs: 120100L]
Map sr2 = h.statusRun([status: 'running', tests: [0, 1], idx: 1, step: 0, results: [], cur: [test: 0, step: 'poll', label: 'old']], [1: 'Device info'])
check 'a step of the previous test is not current', sr2.current.label == 'Starting' && sr2.current.deadlineMs == null && sr2.rows[0].detail == 'step 1'
check 'finished run has no current step', h.statusRun([status: 'done', tests: [0, 1], idx: 2, results: []], [:]).current == null
check 'no run', h.statusRun(null, [:]) == [status: null, reason: null, startedAtMs: null, rows: [], current: null]

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
check 'ready, status, event, set over art', a.artReady && a.statusReply == 'on' && a.statusEventMatches && a.setVia == 'art channel' && a.setReply == 'none'
String setRep = 'rx frameArt: {"data": "{\\"event\\": \\"set_artmode_status\\", \\"status\\": \\"on\\"}", "event": "d2d_service_message"}'
String cast = 'rx frameArt: {"data": "{\\"event\\": \\"art_mode_changed\\", \\"status\\": \\"on\\"}", "event": "d2d_service_message"}'
check 'set reply before broadcast', h.evalArt([line('1:00:00.000', setRep), line('1:00:00.100', cast)], []).setReply == 'before broadcast'
check 'set reply after broadcast', h.evalArt([line('1:00:00.000', cast), line('1:00:10.000', setRep)], []).setReply == 'after broadcast'
check 'broadcast without a reply', h.evalArt([line('1:00:00.000', cast)], []).setReply == 'not seen'
check 'compact JSON', h.evalArt([line('1:00:00.000', compact)], [[name: 'artModeStatus', value: 'off', at: 1L]]).statusReply == 'off'
Map a2 = h.evalArt([line('1:00:00.000', status), line('1:00:01.000', 'tx remote: {"DataOfCmd":"KEY_POWER"}')], [])
check 'no event for the reply; blind key', !a2.statusEventMatches && a2.setVia == 'power key' && !a2.artReady

println 'evalApps'
Map ap = h.evalApps([line('1:00:00.000', 'app: POST 111299001912 status=200 body=true'),
                     line('1:00:06.000', 'app: DELETE 111299001912 status=200 body=true')],
                    [[name: 'appName', value: 'YouTube', at: 1L], [name: 'appName', value: ' ', at: 2L]], [launch: [ok: true, ms: 6100L]])
check 'launch and close', ap == [launchStatus: 200, launchMs: 6100L, closeStatus: 200, closeBody: 'true', appEvent: true, closeEvent: true]

println 'evalAppList'
Map al = h.evalAppList([line('1:00:00.000', 'rx remote: {"data": {"data": [{"appId": "1", "name": "YouTube"}, {"appId": "2", "name": "Deezer"}]}, "event": "ed.installedApp.get", "from": "host"}')])
check 'answered with a count', al == [answered: true, apps: 2]
check 'not answered', h.evalAppList([]) == [answered: false, apps: null]

println 'evalDiscovery'
String mline = 'mdns: name=[TV] Living ip=192.0.2.10 manufacturer=Samsung model=UMOCK keys=deviceid,manufacturer,model,serialNumber'
Map dfound = h.evalDiscovery([line('1:00:00.000', mline), line('1:00:07.000', 'Found Living TV at 192.0.2.10', 'info')], '192.0.2.10')
check 'found: model and keys from the line', dfound == [result: 'found', model: 'UMOCK', keys: 'deviceid,manufacturer,model,serialNumber']
Map dother = h.evalDiscovery([line('1:00:00.000', 'mdns: name=Other ip=192.0.2.11 manufacturer=Samsung model=Q60 keys=a,b')], '192.0.2.10')
check 'other: only another TV announced', dother.result == 'other' && !dother.model
check 'other wins over the first line only when the IP matches',
    h.evalDiscovery([line('1:00:00.000', 'mdns: name=Other ip=192.0.2.11 manufacturer=Samsung model=Q60 keys=a'), line('1:00:00.100', mline)], '192.0.2.10').model == 'UMOCK'
check 'none: warning and no mdns line', h.evalDiscovery([line('1:00:07.000', 'No Samsung TV answered. The TV must be on', 'warn')], '192.0.2.10').result == 'none'
check 'unsupported: neither', h.evalDiscovery([line('1:00:00.000', 'rest: status=200 ms=5 PowerState=on keys=OS')], '192.0.2.10').result == 'unsupported'
check 'no model or keys on the line', h.evalDiscovery([line('1:00:00.000', 'mdns: name=X ip=192.0.2.10 manufacturer=- model=- keys=')], '192.0.2.10') == [result: 'found', model: '-', keys: '']
check 'ip must match whole address', h.evalDiscovery([line('1:00:00.000', 'mdns: name=X ip=192.0.2.100 manufacturer=Samsung model=Q keys=a')], '192.0.2.10').result == 'other'

println 'modelYear'
check 'year from the prefix', h.modelYear('20_KANTSU2_UHD_BASIC') == 2020 && h.modelYear('17_NIKE_UHD') == 2017
check 'unreadable', h.modelYear(null) == null && h.modelYear('') == null && h.modelYear('QN65Q60') == null

println 'discoveryExpect'
check '2016-2017: none expected', h.discoveryExpect(2017) == [result: [eq: 'none']]
check '2018 on: found expected', h.discoveryExpect(2018) == [result: [eq: 'found']] && h.discoveryExpect(2020) == [result: [eq: 'found']]
check 'unknown year: no expectation', h.discoveryExpect(null) == [:]
check 'differences follow it', h.differences([result: 'none'], h.discoveryExpect(2017)) == [] && h.differences([result: 'none'], h.discoveryExpect(2020)) == ['result']

println 'discovery in the report'
check 'name masked in the trace', h.traceLines([line('1:00:00.000', mline)]) == ['1:00:00.000 mdns: name=<name> ip=<ip> manufacturer=Samsung model=UMOCK keys=deviceid,manufacturer,model,serialNumber']
check 'mock profile gets airplay model', h.profileFields([[test: 6, observed: [result: 'found', model: 'UMOCK', keys: 'a']]]) == [airplay: [model: 'UMOCK'], otherMessages: '']
check 'no airplay when not found', h.profileFields([[test: 6, observed: [result: 'none', model: null, keys: null]]]) == [:]

println 'SmartThings: status line'
String ST1 = '[method:statusParse, volume:24, mute:unmuted, inputSource:HDMI1, tvChannel: , tvChannelName:sNUyBbfvHf.SpectrumTV, pictureMode:AI Optimized, soundMode:Adaptive Sound+]'
Map sf = h.stStatusFields(ST1)
check 'every field', sf == [volume: '24', mute: 'unmuted', inputSource: 'HDMI1', tvChannel: ' ', tvChannelName: 'sNUyBbfvHf.SpectrumTV',
                            pictureMode: 'AI Optimized', soundMode: 'Adaptive Sound+']
check 'TV off: nothing', h.stStatusFields('[method:statusParse]') == [:]
check 'null value', h.stStatusFields('[method:statusParse, volume:5, mute:muted, inputSource:null, tvChannel:12, tvChannelName:News, One, pictureMode:Movie, soundMode:Standard]') ==
    [volume: '5', mute: 'muted', inputSource: null, tvChannel: '12', tvChannelName: 'News, One', pictureMode: 'Movie', soundMode: 'Standard']
def stLine = { String ts, int vol, String pic = 'Movie' -> line(ts, "[method:statusParse, volume:${vol}, mute:unmuted, inputSource:HDMI1, tvChannel:12, tvChannelName:News, pictureMode:${pic}, soundMode:Standard]".toString(), 'debug') }
def at = { String ts -> h.tsMs("2026-10-15 ${ts}".toString(), utc) }

println 'SmartThings: plan'
Map stState = [pictureModes: ['Dynamic', 'Standard', 'Movie'], soundModes: '["Standard","Game"]']
check 'volume down one', h.stPlan(12, [volume: 24], stState) ==
    [attr: 'volume', from: '24', to: '23', set: [cmd: 'setVolume', args: ['23']], restore: [cmd: 'setVolume', args: ['24']]]
check 'volume 0 goes up', h.stPlan(12, [volume: '0'], stState).to == '1'
check 'no volume: skipped', h.stPlan(12, [volume: null], stState).skip != null && h.stPlan(13, [volume: null], stState).skip != null
check 'volume keys: down then up', h.stPlan(13, [volume: 24], stState) ==
    [attr: 'volume', from: '24', to: '23', set: [cmd: 'volumeDown', args: []], restore: [cmd: 'volumeUp', args: []]]
check 'volume keys at 0: up then down', h.stPlan(13, [volume: 0], stState).with { it.to == '1' && it.set.cmd == 'volumeUp' && it.restore.cmd == 'volumeDown' }
check 'mute switched over', h.stPlan(14, [mute: 'unmuted'], stState) ==
    [attr: 'mute', from: 'unmuted', to: 'muted', set: [cmd: 'mute', args: []], restore: [cmd: 'unmute', args: []]]
check 'already muted: unmute first', h.stPlan(14, [mute: 'muted'], stState).with { it.to == 'unmuted' && it.set.cmd == 'unmute' && it.restore.cmd == 'mute' }
check 'no mute: skipped', h.stPlan(14, [mute: null], stState).skip != null
check 'next picture mode by toggle, wrapping, set back', h.stPlan(15, [pictureMode: 'Movie'], stState) ==
    [attr: 'pictureMode', from: 'Movie', to: 'Dynamic', set: [cmd: 'togglePictureMode', args: []], restore: [cmd: 'setPictureMode', args: ['Movie']]]
check 'sound modes from JSON text', h.stPlan(16, [soundMode: 'Standard'], stState) ==
    [attr: 'soundMode', from: 'Standard', to: 'Game', set: [cmd: 'toggleSoundMode', args: []], restore: [cmd: 'setSoundMode', args: ['Standard']]]
check 'one mode: skipped', h.stPlan(15, [pictureMode: 'Movie'], [pictureModes: ['Movie']]).skip.contains('picture')
check 'current mode not listed: skipped', h.stPlan(16, [soundMode: 'Odd'], stState).skip.contains('sound')
check 'input changed by key, set back', h.stPlan(17, [inputSource: 'HDMI1'], stState) ==
    [attr: 'inputSource', from: 'HDMI1', to: null, set: [cmd: 'sourceToggle', args: []], restore: [cmd: 'setInputSource', args: ['HDMI1']]]
check 'no input: skipped', h.stPlan(17, [inputSource: null], stState).skip != null
check 'channel up, set back', h.stPlan(18, [tvChannel: '12'], stState) ==
    [attr: 'tvChannel', from: '12', to: null, set: [cmd: 'channelUp', args: []], restore: [cmd: 'setTvChannel', args: ['12']]]
check 'an app showing: no channel test', h.stPlan(18, [tvChannel: ' '], stState).skip.contains('channel')

println 'SmartThings: status test'
Map ss = h.evalStStatus([line('1:00:02.000', 'asyncGet: [parse: distResp, reason: statusParse]', 'debug'), line('1:00:03.000', ST1, 'debug')],
                        [status: [ok: true, ms: 3000L]], [inputSourceCap: 'samsungvd.mediaInputSource', pictureModeCap: 'samsungvd.pictureMode',
                         soundModeCap: 'samsungvd.soundMode', pictureModes: ['AI Customization', 'AI Optimized'], soundModes: ['Standard', 'Game']], ' ', utc)
check 'answered and on', ss.answered == true && ss.reportsOn == true && ss.statusMs == 3000L && ss.problem == null
check 'capabilities and modes', ss.inputCap == 'samsungvd.mediaInputSource' && ss.pictureModes == 'AI Customization|AI Optimized' && ss.soundModes == 'Standard|Game'
check 'app showing, not named', ss.appCode == 'sNUyBbfvHf.SpectrumTV' && ss.appNamed == false && h.differences(ss, [appNamed: [eq: true, optional: true]]) == ['appNamed']
Map ssCh = h.evalStStatus([stLine('1:00:03.000', 24)], [status: [ok: true, ms: 10L]], [:], ' ', utc)
check 'a channel: no app fields', !ssCh.containsKey('appNamed') && h.differences(ssCh, [appNamed: [eq: true, optional: true]]) == []
Map ssOff = h.evalStStatus([line('1:00:03.000', '[method:statusParse]', 'debug')], [status: [ok: true, ms: 10L]], [:], ' ', utc)
check 'SmartThings says off', ssOff.answered == true && ssOff.reportsOn == false
Map ss401 = h.evalStStatus([line('1:00:03.000', 'SmartThings rejected the API key (HTTP 401). Tokens created after 30 Dec 2024 expire after 24 hours.', 'error')],
                           [status: [ok: true, ms: 10L]], [:], ' ', utc)
check 'rejected key', ss401.answered == false && ss401.statusMs == null && ss401.problem.startsWith('SmartThings rejected')

check 'key rejected before the run', h.evalStStatus([], [status: [ok: false]], [stAuthFailed: true], ' ', utc).problem.contains('rejected')

println 'SmartThings: set tests'
Map volPlan = [attr: 'volume', from: '24', to: '23']
Map stSteps = [set: [ok: true, ms: 2500L, at: at('1:00:00.000')], check: [ok: true, ms: 1500L, at: at('1:00:10.000')], restore: [ok: true, ms: 2000L, at: at('1:00:20.000')]]
Map expSet = [commandStatus: [eq: 200], valueSet: [eq: true], statusLags: [eq: false], event: [eq: true, driver: true, optional: true], restored: [eq: true]]
Map vs = h.evalStSet([stLine('1:00:02.500', 23), stLine('1:00:11.500', 23), stLine('1:00:22.000', 24)], [[name: 'volume', value: '23', at: 1L]], stSteps, volPlan, utc)
check 'set, read back, restored', vs.commandStatus == 200 && vs.setMs == 2500L && vs.firstStatus == '23' && vs.laterStatus == '23' && vs.valueSet &&
    !vs.statusLags && vs.event == true && vs.restored == true && h.differences(vs, expSet) == []
Map vl = h.evalStSet([stLine('1:00:02.500', 24), stLine('1:00:11.500', 23), stLine('1:00:22.000', 24)], [[name: 'volume', value: '23', at: 1L]], stSteps, volPlan, utc)
check 'first status late', vl.valueSet && vl.statusLags && h.differences(vl, expSet) == ['statusLags']
Map vn = h.evalStSet([stLine('1:00:02.500', 24), stLine('1:00:11.500', 24), stLine('1:00:22.000', 24)], [[name: 'volume', value: '24', at: 1L]], stSteps, volPlan, utc)
check 'never set: no event judged', vn.valueSet == false && !vn.containsKey('event') && h.differences(vn, expSet) == ['valueSet'] && h.splitDiffs(['valueSet'], expSet).driverDiffs == []
Map ve = h.evalStSet([stLine('1:00:02.500', 23), stLine('1:00:11.500', 23), stLine('1:00:22.000', 24)], [[name: 'volume', value: '24', at: 1L]], stSteps, volPlan, utc)
check 'no event: the driver differs', ve.event == false && h.splitDiffs(h.differences(ve, expSet), expSet).driverDiffs == ['event']
Map vf = h.evalStSet([line('1:00:01.000', 'deviceCommand: [status: FAILED, httpCode: 422, capability: audioVolume]', 'warn')], [], stSteps, volPlan, utc)
check 'command refused', vf.commandStatus == 422 && vf.setMs == null && vf.valueSet == false && vf.restored == false
check 'key rejected', h.evalStSet([line('1:00:01.000', 'SmartThings rejected the API key (HTTP 401).', 'error')], [], stSteps, volPlan, utc).commandStatus == 401
check 'no line at all', h.evalStSet([], [], [set: [ok: false, at: at('1:00:00.000')]], volPlan, utc).commandStatus == 'no answer'
check 'a failed restore does not change the command status',
    h.evalStSet([stLine('1:00:02.500', 23), stLine('1:00:11.500', 23), line('1:00:21.000', 'deviceCommand: [status: FAILED, httpCode: 409, capability: audioVolume]', 'warn')],
                [[name: 'volume', value: '23', at: 1L]], stSteps, volPlan, utc).with { it.commandStatus == 200 && it.restored == false }
def inLine = { String ts, String input, String ch = '12' -> line(ts, "[method:statusParse, volume:24, mute:unmuted, inputSource:${input}, tvChannel:${ch}, tvChannelName:News, pictureMode:Movie, soundMode:Standard]".toString(), 'debug') }
Map inPlan = [attr: 'inputSource', from: 'HDMI1', to: null]
Map vi = h.evalStSet([inLine('1:00:04.000', 'HDMI1'), inLine('1:00:11.500', 'HDMI2'), inLine('1:00:22.000', 'HDMI1')], [[name: 'inputSource', value: 'HDMI2', at: 1L]], stSteps, inPlan, utc)
check 'input: any other value counts, late first status', vi.to == 'HDMI2' && vi.valueSet && vi.statusLags && vi.event == true && vi.restored == true
Map vi2 = h.evalStSet([inLine('1:00:04.000', 'HDMI1'), inLine('1:00:11.500', 'HDMI1'), inLine('1:00:22.000', 'HDMI1')], [], stSteps, inPlan, utc)
check 'input never changed', vi2.to == null && vi2.valueSet == false && !vi2.statusLags && !vi2.containsKey('event') && h.differences(vi2, expSet) == ['valueSet']
Map vi3 = h.evalStSet([inLine('1:00:04.000', 'HDMI2'), inLine('1:00:11.500', 'HDMI2'), inLine('1:00:22.000', 'HDMI2')], [[name: 'inputSource', value: 'HDMI2', at: 1L]], stSteps, inPlan, utc)
check 'input not put back', vi3.valueSet && vi3.restored == false && h.differences(vi3, expSet) == ['restored']
check 'picture mode', h.evalStSet([stLine('1:00:02.500', 24, 'Dynamic'), stLine('1:00:11.500', 24, 'Dynamic'), stLine('1:00:22.000', 24, 'Movie')],
    [[name: 'pictureMode', value: 'Dynamic', at: 1L]], stSteps, [attr: 'pictureMode', from: 'Movie', to: 'Dynamic'], utc).with { it.valueSet && it.event && it.restored }
check 'problem line masked', h.stProblem([line('1:00:01.000', 'distResp: [status:ERROR, httpCode:500, errorMsg:host 192.168.1.9]', 'warn')]) == 'distResp: [status:ERROR, httpCode:500, errorMsg:host <ip>]'
check 'trace keeps SmartThings debug lines, drops other debug',
    h.traceLines([stLine('1:00:02.500', 23), line('1:00:03.000', 'push: button = 1', 'debug'), line('1:00:04.000', 'deviceCommand: [component:main]', 'debug')]).size() == 2

check 'channel up and back', h.evalStSet([inLine('1:00:04.000', 'HDMI1', '13'), inLine('1:00:11.500', 'HDMI1', '13'), inLine('1:00:22.000', 'HDMI1', '12')],
    [[name: 'tvChannel', value: '13', at: 1L]], stSteps, [attr: 'tvChannel', from: '12', to: null], utc).with { it.to == '13' && it.valueSet && !it.statusLags && it.event && it.restored }
check 'channel gone blank is not a change', h.evalStSet([inLine('1:00:04.000', 'HDMI1', ' ')], [], stSteps, [attr: 'tvChannel', from: '12', to: null], utc).valueSet == false
def muteLine = { String ts, String m -> line(ts, "[method:statusParse, volume:24, mute:${m}, inputSource:HDMI1, tvChannel:12, tvChannelName:News, pictureMode:Movie, soundMode:Standard]".toString(), 'debug') }
check 'mute', h.evalStSet([muteLine('1:00:02.500', 'muted'), muteLine('1:00:11.500', 'muted'), muteLine('1:00:22.000', 'unmuted')],
    [[name: 'mute', value: 'muted', at: 1L]], stSteps, [attr: 'mute', from: 'unmuted', to: 'muted'], utc).with { it.valueSet && it.event && it.restored && !it.statusLags }

println 'SmartThings: apps test'
def appLine = { String ts, String ch, String name -> line(ts, "[method:statusParse, volume:24, mute:unmuted, inputSource:HDMI1, tvChannel:${ch}, tvChannelName:${name}, pictureMode:Movie, soundMode:Standard]".toString(), 'debug') }
Map appSteps = [launch: [ok: true, ms: 900L, at: at('1:00:00.000')], close: [ok: true, ms: 500L, at: at('1:00:30.000')]]
Map expApp = [stAppReported: [eq: true, optional: true], nameKept: [eq: true, driver: true, optional: true], stClearsApp: [eq: true, optional: true]]
Map as1 = h.evalAppsSt([appLine('1:00:11.000', ' ', '9Ur5IzDKqV.TizenYouTube'), appLine('1:00:36.000', '12', 'News')],
    [[name: 'appName', value: 'YouTube', at: at('1:00:01.000')], [name: 'appName', value: ' ', at: at('1:00:30.500')]], appSteps, utc)
check 'app reported, name kept, cleared after close', as1 == [stAppCode: '9Ur5IzDKqV.TizenYouTube', stAppReported: true, nameKept: true,
    stAppAfterClose: 'none', stClearsApp: true] && h.differences(as1, expApp) == []
Map as2 = h.evalAppsSt([appLine('1:00:11.000', ' ', 'abc.Unknown'), appLine('1:00:36.000', ' ', 'abc.Unknown')],
    [[name: 'appName', value: 'YouTube', at: at('1:00:01.000')], [name: 'appName', value: ' ', at: at('1:00:14.000')]], appSteps, utc)
check 'name blanked by the status: the driver differs', as2.nameKept == false && as2.stClearsApp == false && as2.stAppAfterClose == 'abc.Unknown' &&
    h.splitDiffs(h.differences(as2, expApp), expApp) == [driverDiffs: ['nameKept'], tvDiffs: ['stClearsApp']]
Map as3 = h.evalAppsSt([appLine('1:00:11.000', '12', 'News')], [[name: 'appName', value: 'YouTube', at: at('1:00:01.000')]], appSteps, utc)
check 'SmartThings still shows a channel', as3.stAppReported == false && as3.stAppCode == null && !as3.containsKey('stClearsApp')
check 'no status at all: nothing judged', h.evalAppsSt([], [], appSteps, utc) == [:] && h.differences([:], expApp) == []

println 'SmartThings: wake in the power-on test'
check 'stWake only when the driver asked SmartThings',
    h.evalPowerOn([line('1:00:17.000', 'stPowerOn: [reason: no answer to Wake-on-LAN]', 'info')], [], [:], at('1:00:00.000'), utc).stWake == true &&
    !h.evalPowerOn([], [], [:], at('1:00:00.000'), utc).containsKey('stWake')

println 'deviceInfoFields'
Map fi = h.deviceInfoFields([device: [OS: 'Tizen', duid: 'uuid:x', wifiMac: 'f4:7b:09:12:34:56', PowerState: 'on'], version: '2.0.25',
                             isSupport: '{"remote_voiceControl":"false"}\n'], ['OS', 'PowerState', 'VoiceSupport'])
check 'allowlisted values only', fi.deviceInfo == [OS: 'Tizen', PowerState: 'on']
check 'isSupport parsed, version, all key names', fi.isSupport == [remote_voiceControl: 'false'] && fi.apiVersion == '2.0.25' && fi.deviceKeys == 'OS,PowerState,duid,wifiMac'
check 'no reply', h.deviceInfoFields([status: 'no answer'], ['OS']).deviceInfo == [:]

println 'otherMessages'
List known = ['ms.channel.connect', 'd2d_service_message', 'ed.installedApp.get']
List kart = ['artmode_status']
List<Map> om = [line('1:00:00.000', 'rx remote: {"data": {"clients": []}, "event": "ms.channel.connect"}'),
                line('1:00:01.000', 'rx remote: {"data": {"message": "x"}, "event": "ms.error"}'),
                line('1:00:02.000', 'rx remote: {"data": {}, "event": "ed.edenTV.update"}'),
                line('1:00:03.000', status),
                line('1:00:04.000', 'rx frameArt: {"data": "{\\"event\\": \\"image_selected\\"}", "event": "d2d_service_message"}'),
                line('1:00:05.000', 'rx remote: {"data": {"message": "y"}, "event": "ms.error"}')]
check 'unknown outer and art events, once each', h.otherMessages(om, known, kart) == 'ms.error,ed.edenTV.update,art:image_selected'
check 'none', h.otherMessages(om.take(1), known, kart) == ''
check 'No Authorized is handled', h.otherMessages([line('1:00:00.000', 'rx remote: {"data":{"message":"No Authorized"},"event":"ms.error"}')], known, kart) == ''

println 'sampleLines'
check 'handshake kept and masked', h.sampleLines(2, rs) == ['20:57:57.708 rx remote: {"data": {"clients": [], "token":"<token>"}, "event": "ms.channel.connect"}']
check 'no samples for key burst', h.sampleLines(3, kb) == []

println 'differences and verdicts'
Map expect = [a: [eq: true], b: [max: 100, driver: true], c: [ne: null]]
List diffs = h.differences([a: false, b: 200, c: null], expect)
check 'all three differ', diffs == ['a', 'b', 'c']
check 'optional rule skips an unmeasured field', h.differences([a: 1], [k: [eq: true, optional: true]]) == [] && h.differences([k: false], [k: [eq: true, optional: true]]) == ['k']
check 'none rule', h.differences([o: ''], [o: [none: true]]) == [] && h.differences([:], [o: [none: true]]) == [] && h.differences([o: 'ms.error'], [o: [none: true]]) == ['o']
Map split = h.splitDiffs(diffs, expect)
check 'split', split.driverDiffs == ['b'] && split.tvDiffs == ['a', 'c']
check 'verdict driver', h.verdictOf([diffs: diffs] + split) == 'driver'
check 'verdict text', h.verdictText([diffs: diffs] + split) == 'driver differs (b); TV differs (a, c)'
check 'skipped', h.verdictText([skipped: 'not a Frame TV']) == 'skipped: not a Frame TV'
check 'as expected', h.verdictText([diffs: [], driverDiffs: [], tvDiffs: []]) == 'as expected'
check 'discovery heard another device', h.verdictText([test: 6, observed: [result: 'other'], diffs: ['result'], driverDiffs: [], tvDiffs: ['result']]) ==
    "TV differs (result); a Samsung device that isn't this TV answered"

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
                            [[test: 5, name: 'App list', observed: [answered: false, apps: null], diffs: ['answered'], driverDiffs: [], tvDiffs: ['answered'],
                              trace: ['20:58:00.000 tx remote: {}']]])
check 'setup line', rep.contains('TV settings: Power On with Mobile unknown · IP Remote unknown') &&
    h.formatReport([appVersion: '0.4.0', status: 'done', powerOnWithMobile: 'yes', ipRemote: 'no'], []).contains('TV settings: Power On with Mobile yes · IP Remote no')
check 'questions on by default, off when unattended', rep.contains('IP Remote unknown · questions on') &&
    h.formatReport([appVersion: '0.4.0', status: 'done', attended: 'yes'], []).contains('· questions on') &&
    h.formatReport([appVersion: '0.4.0', status: 'done', attended: 'no'], []).contains('IP Remote unknown · questions off')
check 'header', rep.contains('TV: UN50TU7000FXZC · platform 20_KANTSU2_UHD_BASIC') && rep.contains('SmartThings setting off')
check 'test line and trace', rep.contains('5. App list — TV differs (answered)') && rep.contains('     20:58:00.000 tx remote: {}')

println 'profileFields'
Map pf = h.profileFields([[test: 1, observed: [deviceInfo: [OS: 'Tizen'], apiVersion: '2.0.25', restMs: 20]],
                          [test: 2, observed: [handshake: 'ms.channel.connect', connectMs: 1352, otherMessages: 'ms.error']],
                          [test: 7, skipped: 'not a Frame TV'],
                          [test: 5, observed: [answered: false, apps: null, otherMessages: 'ms.error,ed.edenTV.update']]])
check 'profile keys only, otherMessages merged', pf == [deviceInfo: [OS: 'Tizen'], apiVersion: '2.0.25', handshake: 'ms.channel.connect',
    connectMs: 1352, answered: false, apps: null, otherMessages: 'ms.error,ed.edenTV.update']
String rep2 = h.formatReport([appVersion: '0.2.0', status: 'done'], [[test: 1, name: 'Device info', observed: [deviceInfo: [OS: 'Tizen']], diffs: []]])
check 'maps as JSON, profile block', rep2.contains('deviceInfo={"OS":"Tizen"}') && rep2.contains('mock profile (for the maintainer):') && rep2.contains('"OS": "Tizen"')

println 'padCommand'
check 'a driver command passes by its name', h.padCommand('channelUp') == [ok: true, method: 'channelUp', args: []] && h.padCommand('Return').method == 'Return' && h.padCommand('on').ok
check 'the id is case-sensitive and must be listed', !h.padCommand('CHANNELUP').ok && !h.padCommand('HOME').ok && !h.padCommand(null).ok && !h.padCommand('').ok && !h.padCommand('deviceRefresh').ok && !h.padCommand('sendKey ').ok
check 'sendKey upper-cases and drops a leading KEY_', h.padCommand('sendKey', 'KEY_info') == [ok: true, method: 'sendKey', args: [[type: 'STRING', value: 'INFO']]] && h.padCommand('sendKey', 'vol_up').args[0].value == 'VOL_UP'
check 'sendKey refuses odd names', ['', 'KEY_', null, 'A B', 'A;B', 'A-B', 'A' * 31, 'A\n'].every { !h.padCommand('sendKey', it).ok } && h.padCommand('sendKey', 'A' * 30).ok && h.padCommand('sendKey', 'x y').error == 'unknown key'

println 'pad log'
check 'append keeps the order', h.padLogAppend([[atMs: 1L]], [atMs: 2L], 50)*.atMs == [1L, 2L] && h.padLogAppend(null, [atMs: 1L], 50)*.atMs == [1L]
List<Map> big = (1..50).collect { [atMs: (long) it] }
check 'append drops the oldest beyond the cap', h.padLogAppend(big, [atMs: 51L], 50).with { it.size() == 50 && it[0].atMs == 2L && it[-1].atMs == 51L } && h.padLogAppend(big.take(10), [atMs: 99L], 50).size() == 11
check 'a run restores the value from before the pad', h.runStartTrace([active: true, prev: false], true) == false && h.runStartTrace([active: true, prev: true], true) == true &&
    h.runStartTrace(null, true) == true && h.runStartTrace([:], false) == false && h.runStartTrace(null, null) == false
List<Map> pl1 = [line('3:00:00.100', 'tx remote: {"DataOfCmd":"KEY_HOME"}'), line('3:00:00.200', 'setPowerState: [switch: on]', 'info'),
                 line('3:00:03.900', 'rest: status=200 ms=30 PowerState=on keys=A,B', 'trace'), line('3:00:04.500', 'ws remote: failure: x 192.0.2.10', 'warn')]
check 'entry lines: trace/warn/error only, before the cut-off, masked', h.padEntryLines(pl1, '2026-10-15 3:00:04.000') ==
    ['3:00:00.100 tx remote: {"DataOfCmd":"KEY_HOME"}', '3:00:03.900 rest: status=200 ms=30 PowerState=on'] &&
    h.padEntryLines(pl1, '2026-10-15 3:00:09.000')[-1] == '3:00:04.500 ws remote: failure: x <ip>'
check 'entry lines: cut to 15 lines, and by size', h.padEntryLines((1..30).collect { line("3:00:${10 + it}.000", 'tx remote: x') }, '2027').size() == 15 &&
    h.padEntryLines((1..30).collect { line("3:00:${10 + it}.000", 'tx remote: ' + ('y' * 400)) }, '2027').with { it.size() <= 10 && it.every { s -> s.length() <= 200 } } &&
    h.padEntryLines((1..30).collect { line("3:00:${10 + it}.000", 'tx remote: x') }, '2027', 4).size() == 4
List<Map> plog = [[atMs: 1000L, since: 's', button: 'home', key: null, lines: ['a']], [atMs: 2000L, since: 's', button: 'sendKey', key: 'KEY_INFO', lines: []],
                  [atMs: 3000L, since: 's', button: 'mute', key: null]]
check 'view: last n, the page fields only, newest last', h.padLogView(plog, 2)*.atMs == [2000L, 3000L] && h.padLogView(plog, 20).size() == 3 &&
    h.padLogView(plog, 20)[2] == [atMs: 3000L, button: 'mute', key: null, lines: []] && !h.padLogView(plog, 20)[0].containsKey('since') && h.padLogView(null, 20) == []
check 'entry text: time, button, key, indented lines', h.padEntryText(plog[0], utc) == '00:00:01 home\n   a' && h.padEntryText(plog[1], utc) == '00:00:02 sendKey KEY_INFO'
check 'manual keys section: presses after the previous report up to this one', h.padSection(plog, 1000L, 2000L, utc) == 'Manual keys (remote pad presses since the previous report)\n00:00:02 sendKey KEY_INFO' &&
    h.padSection(plog, 0L, 5000L, utc).split('\n').size() == 5 && h.padSection(plog, 0L, 5000L, utc).contains('00:00:01 home\n   a')
check 'no section without presses', h.padSection(plog, 3000L, 9000L, utc) == '' && h.padSection(null, 0L, 9L, utc) == '' && h.padSection([], 0L, 9L, utc) == ''
String repPad = h.formatReport([appVersion: '0.4.0', status: 'done', manualKeys: h.padSection(plog, 0L, 5000L, utc)], [[test: 1, name: 'Device info', observed: [deviceInfo: [OS: 'Tizen']], diffs: []]])
check 'report: section after the tests, before the profile', repPad.indexOf('1. Device info') < repPad.indexOf('Manual keys (remote pad') && repPad.indexOf('Manual keys (remote pad') < repPad.indexOf('mock profile (for the maintainer):')
check 'report: no section when no keys', !h.formatReport([appVersion: '0.4.0', status: 'done', manualKeys: ''], []).contains('Manual keys') && !h.formatReport([appVersion: '0.4.0', status: 'done'], []).contains('Manual keys')

println 'pollGap'
check 'short wait: 1 s', h.pollGap(15, 400L) == 1
check 'slow read sets the gap', h.pollGap(15, 3200L) == 4
check 'long wait: at least 3 s', h.pollGap(90, 400L) == 3

println "\n${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)
