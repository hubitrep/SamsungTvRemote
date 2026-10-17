// Copyright 2026 hubitrep
// SPDX-License-Identifier: Apache-2.0
//
// Unit test for the Samsung TV Remote driver's pure discovery helpers: extracts them from
// SamsungTVRemote.groovy by brace matching and runs them under plain Groovy.
//
// Run: groovy tests/test-driver-helpers.groovy   (no hub)

File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
File src = new File(scriptFile.parentFile.parentFile, 'SamsungTVRemote.groovy')
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
    'List<String> ipv4List(def v) {',
    'Map airplayCandidate(Map r) {',
    'List<Map> airplayCandidates(List results) {',
    'String normMac(String mac) {',
    'boolean sameTv(String a, String b) {',
    'boolean isTvChange(String oldMac, String newMac) {',
    'List<Map> uniqueTvs(List<Map> found) {',
    'String tvLabel(String restName, String ip) {',
    'boolean searchDue(Long last, long nowMs, long minMs) {',
    'boolean repairWanted(String switchValue, Long lastSearch, long nowMs) {',
    'String mdnsTraceLine(Map r) {',
]
def h = new GroovyShell().parse(SIGNATURES.collect { extract(text, it) }.join("\n") + "\nreturn this\n").run()

int pass = 0, fail = 0
def check = { String name, boolean cond ->
    if (cond) { pass++; println "  [PASS] ${name}" } else { fail++; println "  [FAIL] ${name}" }
}

Map tv = [name: "Living room", port: 47941, ip4Addresses: ["192.0.2.10"], ip6Addresses: [],
          textRecords: [manufacturer: "Samsung", model: "UTU7000", deviceid: "02:00:00:00:00:AA", serialNumber: "X"]]
Map tvLower = [name: "Den", ip4Addresses: "192.0.2.11, 192.0.2.12", textRecords: [manufacturer: "samsung electronics"]]
Map dup = tv + [ip4Addresses: ["192.0.2.10"]]
Map bar = [name: "Samsung Soundbar Q60", ip4Addresses: ["192.0.2.13"], textRecords: [manufacturer: "Samsung"]]
Map apple = [name: "HomePod", ip4Addresses: ["192.0.2.14"], textRecords: [manufacturer: "Apple Inc.", model: "AudioAccessory5,1"]]
Map noV4 = [name: "Bedroom", ip4Addresses: [], textRecords: [manufacturer: "Samsung"]]

println 'ipv4List'
check 'list as is', h.ipv4List(["192.0.2.1"]) == ["192.0.2.1"]
check 'string split and trimmed', h.ipv4List("192.0.2.1, 192.0.2.2") == ["192.0.2.1", "192.0.2.2"]
check 'else empty', h.ipv4List(null) == [] && h.ipv4List(5) == [] && h.ipv4List("") == []

println 'airplayCandidate'
check 'TV', h.airplayCandidate(tv) == [name: "Living room", ip: "192.0.2.10", model: "UTU7000"]
check 'lower-case manufacturer, first IPv4', h.airplayCandidate(tvLower).ip == "192.0.2.11"      // focus 1
check 'soundbar, other maker, no IPv4, empty', [bar, apple, noV4, [:]].every { h.airplayCandidate(it) == null }

println 'airplayCandidates'
check 'one per ip, first wins', h.airplayCandidates([tv, dup, tvLower, bar, apple])*.ip == ["192.0.2.10", "192.0.2.11"]
check 'null', h.airplayCandidates(null) == []

println 'normMac / sameTv / isTvChange'
check 'normalised', h.normMac("8c:ea:48:00:00:01") == "8CEA48000001" && h.normMac("8CEA48000001") == "8CEA48000001"
check 'invalid is null', [null, "", "none", "8c:ea:48"].every { h.normMac(it) == null }          // focus 2
check 'sameTv', h.sameTv("8c:ea:48:00:00:01", "8CEA48000001") && !h.sameTv("none", "none") && !h.sameTv(null, null)
check 'isTvChange', h.isTvChange("8CEA48000001", "8CEA48000002") && !h.isTvChange(null, "8CEA48000002")  // focus 5
check 'same TV, other format', !h.isTvChange("8CEA48000001", "8c:ea:48:00:00:01")

println 'uniqueTvs'
List<Map> two = [[ip: "192.0.2.10", mac: "8c:ea:48:00:00:01"], [ip: "192.0.2.20", mac: "8CEA48000001"]]
check 'same MAC at two IPs, first wins', h.uniqueTvs(two) == [two[0]]
check 'different MACs kept', h.uniqueTvs([two[0], [ip: "192.0.2.30", mac: "8CEA48000002"]]).size() == 2
check 'null', h.uniqueTvs(null) == []

println 'tvLabel'
check 'prefix removed, ip appended', h.tvLabel("[TV] Living room", "192.0.2.10") == "Living room (192.0.2.10)"
check 'same name told apart by ip', h.tvLabel("[TV] Living room", "192.0.2.11") != h.tvLabel("[TV] Living room", "192.0.2.10")  // focus 4

println 'searchDue / repairWanted'
check 'searchDue', h.searchDue(null, 1000L, 300000L) && !h.searchDue(1000L, 2000L, 300000L) && h.searchDue(1000L, 301000L, 300000L)
check 'repair only when on', h.repairWanted("on", null, 1000L) && !h.repairWanted("off", null, 1000L)   // focus 3
check 'repair rate limited', !h.repairWanted("on", 1000L, 2000L)

println 'mdnsTraceLine'
check 'line', h.mdnsTraceLine(tv) == "mdns: name=Living room ip=192.0.2.10 manufacturer=Samsung model=UTU7000 keys=deviceid,manufacturer,model,serialNumber"
check 'no TXT values beyond those', !h.mdnsTraceLine(tv).contains("02:00:00:00:00:AA")
check 'missing fields', h.mdnsTraceLine([name: "X"]) == "mdns: name=X ip=- manufacturer=- model=- keys="

println "\n${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)
