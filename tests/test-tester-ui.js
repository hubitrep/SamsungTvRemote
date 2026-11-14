#!/usr/bin/env node
// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
// Unit tests of the tester page's pure logic. Run: node tests/test-tester-ui.js
'use strict';
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, 'samsung-tv-tester-ui.html'), 'utf8');
const m = /<script id="logic">([\s\S]*?)<\/script>/.exec(html);
if (!m) { console.log('FAIL extract logic block'); process.exit(1); }
const win = {};
vm.runInNewContext(m[1], { window: win });
const U = win.TesterUI;

let failed = 0, passed = 0;
function check(name, fn) {
  try { fn(); passed++; console.log('ok   ' + name); }
  catch (e) { failed++; console.log('FAIL ' + name + ': ' + e.message); }
}
const eq = assert.strictEqual;
// Objects from the vm context have another prototype; compare as JSON.
const deq = (a, b) => assert.deepStrictEqual(JSON.parse(JSON.stringify(a)), b);

check('CODE_VERSION literal in file', () => assert.ok(html.includes("const CODE_VERSION = '0.5.0'")));
check('CODE_VERSION exported', () => eq(U.CODE_VERSION, '0.5.0'));
check('required element ids present', () => {
  for (const id of ['run', 'abort', 'setup-pom', 'setup-ipr', 'setup-attended', 'rows', 'current-countdown', 'prompt', 'prompt-text', 'prompt-buttons', 'prompt-countdown',
    'timeout-note', 'report', 'copy', 'download-profile', 'pad', 'version-banner', 'pad-log', 'pad-log-copy', 'pad-log-clear']) {
    assert.ok(html.includes('id="' + id + '"'), id);
  }
});
check('pad keys', () => {
  const ids = ['on', 'off', 'arrowUp', 'arrowDown', 'arrowLeft', 'arrowRight', 'enter', 'Return', 'home', 'exit', 'menu', 'guide', 'sourceSetOSD',
    'sourceToggle', 'numericKeyPad', 'channelUp', 'channelDown', 'channelList', 'previousChannel', 'volumeUp', 'volumeDown', 'mute', 'play', 'pause',
    'stop', 'fastBack', 'fastForward', 'artMode', 'ambientMode', 'sendKey'];
  const found = [...html.matchAll(/<button[^>]*data-key="([^"]+)"/g)].map(m => m[1]);
  deq(found.slice().sort(), ids.slice().sort());
  assert.ok(html.includes('id="pad-key"'));
});
check('padShown: art on Frame TVs, ambient on the others', () => {
  eq(U.padShown('frame', true), true); eq(U.padShown('frame', false), false); eq(U.padShown('frame', undefined), false);
  eq(U.padShown('nonframe', true), false); eq(U.padShown('nonframe', false), true); eq(U.padShown('nonframe', undefined), true);
  eq(U.padShown(null, false), true); eq(U.padShown(undefined, true), true);
  assert.ok(/data-key="artMode" data-only="frame"/.test(html) && /data-key="ambientMode" data-only="nonframe"/.test(html));
});
check('no external resources', () => assert.ok(!/(src|href)="https?:/.test(html)));

// countdown
check('formatCountdown basics', () => {
  eq(U.formatCountdown(120000), '2:00'); eq(U.formatCountdown(61000), '1:01'); eq(U.formatCountdown(9000), '0:09');
});
check('formatCountdown rounds up', () => { eq(U.formatCountdown(1), '0:01'); eq(U.formatCountdown(59001), '1:00'); });
check('formatCountdown out of time / bad input', () => {
  eq(U.formatCountdown(0), '0:00'); eq(U.formatCountdown(-5), '0:00'); eq(U.formatCountdown(null), '0:00'); eq(U.formatCountdown(NaN), '0:00');
});
check('clock offset correction', () => {
  const off = U.clockOffset(1000000, 400000);          // hub 600 s ahead
  eq(off, 600000);
  eq(U.remainingMs(1120000, off, 400000), 120000);     // deadline 120 s after hub now
  eq(U.remainingMs(1120000, off, 410000), 110000);
});
check('clock offset keeps the least delayed reply', () => {
  eq(U.nextOffset(null, -300), -300);                  // first reply, hub behind
  eq(U.nextOffset(-300, -900), -300);                  // a slower reply does not move it back
  eq(U.nextOffset(-300, -100), -100);                  // a faster one does
  eq(U.nextOffset(-300, 9000), 9000);                  // a jump over 5 s: the clock changed
});
check('countdownView text and fraction', () => {
  const v = U.countdownView(1000, 11000, 0, 6000);
  eq(v.text, '0:05'); eq(v.fraction, 0.5);
  const e = U.countdownView(1000, 11000, 0, 20000);
  eq(e.text, '0:00'); eq(e.fraction, 1);
  eq(U.countdownView(1, null, 0, 5).text, '');
});

// rows
const cur = { test: 10, name: 'Power on', step: 'watch', label: 'Waiting for the TV to report on' };
check('rowView ok/tv/driver/skipped icons', () => {
  eq(U.rowView({ test: 1, name: 'A', state: 'ok', detail: 'as expected' }).icon, '✓');
  eq(U.rowView({ test: 1, name: 'A', state: 'tv', detail: 'x' }).icon, '⚠');
  eq(U.rowView({ test: 1, name: 'A', state: 'driver', detail: 'x' }).icon, '✗');
  eq(U.rowView({ test: 1, name: 'A', state: 'skipped', detail: 'x' }).icon, '–');
  eq(U.rowView({ test: 1, name: 'A', state: 'ok', detail: 'as expected' }).detail, 'as expected');
});
check('rowView running shows step label only on the current test', () => {
  const r = U.rowView({ test: 10, name: 'Power on', state: 'running', detail: '' }, cur);
  eq(r.detail, cur.label); eq(r.current, true); eq(r.cls, 's-running');
  const o = U.rowView({ test: 8, name: 'Power off', state: 'running', detail: 'd' }, cur);
  eq(o.detail, 'd'); eq(o.current, false);
});
check('rowView waiting / not run / unknown state', () => {
  eq(U.rowView({ test: 2, name: 'B', state: 'waiting' }).cls, 's-waiting');
  eq(U.rowView({ test: 2, name: 'B', state: 'not run' }).cls, 's-notrun');
  eq(U.rowView({ test: 2, name: 'B', state: 'weird' }).state, 'not run');
  eq(U.rowView({ test: 2, state: 'ok' }).name, 'Test 2');
});
check('rowHtml escapes hub strings', () => {
  const h = U.rowHtml(U.rowView({ test: 3, name: '<b>x</b>', state: 'ok', detail: '"q" & <i>' }));
  assert.ok(!h.includes('<b>') && !h.includes('<i>'));
  assert.ok(h.includes('&lt;b&gt;x&lt;/b&gt;') && h.includes('&quot;q&quot; &amp; &lt;i&gt;'));
  assert.ok(h.includes('data-test="3"'));
});
check('rowHtml selectable checkbox', () => {
  const h = U.rowHtml(U.rowView({ test: 3, name: 'N', state: 'not run' }), { selectable: true, selected: false });
  assert.ok(h.includes('data-pick="3"') && !h.includes(' checked'));
});
check('idleRows list', () => {
  const r = U.idleRows(); eq(r.length, 15); eq(r[0].test, 1); eq(r[9].test, 10); eq(r[14].test, 15); assert.ok(r.every(x => x.state === 'not run'));
});
check('runMode', () => {
  eq(U.runMode(null), 'before'); eq(U.runMode({ status: null }), 'before'); eq(U.runMode({ status: 'running' }), 'running');
  for (const s of ['done', 'aborted', 'stalled', 'interrupted']) eq(U.runMode({ status: s }), 'after');
});
check('summaryLine', () => {
  eq(U.summaryLine(null), '');
  eq(U.summaryLine({ status: 'done', rows: [{ state: 'ok' }, { state: 'ok' }, { state: 'tv' }, { state: 'driver' }, { state: 'skipped' }] }),
    'Done: 2 ok, 1 TV, 1 driver, 1 skipped');
  eq(U.summaryLine({ status: 'stalled', reason: 'no progress', rows: [] }), 'Stalled (no progress): 0 ok, 0 TV, 0 driver, 0 skipped');
  eq(U.summaryLine({ status: 'running', rows: [{ state: 'ok' }] }), 'Running');
});

// prompt
const prompt = { id: 'p7', test: 10, kind: 'picture', text: 'Click when the picture is on.', askedAtMs: 1000, deadlineMs: 121000,
  choices: [{ id: 'on', label: 'Picture is on' }, { id: 'never', label: "It didn't come on" }] };
check('promptView choices and countdown', () => {
  const v = U.promptView(prompt, 0, 61000, false);
  eq(v.id, 'p7'); eq(v.countdown, '1:00'); eq(v.expired, false); eq(v.choices.length, 2);
  eq(v.choices[0].id, 'on'); eq(v.choices[0].disabled, false);
});
check('promptView with offset', () => {
  eq(U.promptView(prompt, 50000, 11000, false).countdown, '1:00');
});
check('promptView clicked disables buttons', () => {
  assert.ok(U.promptView(prompt, 0, 5000, true).choices.every(c => c.disabled));
});
check('promptView expired', () => {
  const v = U.promptView(prompt, 0, 130000, false);
  eq(v.expired, true); eq(v.countdown, '0:00'); assert.ok(v.choices.every(c => c.disabled));
});
check('promptView null and no deadline', () => {
  eq(U.promptView(null, 0, 0, false), null);
  eq(U.promptView({ id: 'a', text: 't', choices: [{ id: 'done', label: 'Done' }] }, 0, 5, false).expired, false);
});
check('promptButtonsHtml escapes and marks disabled', () => {
  const h = U.promptButtonsHtml(U.promptView({ id: 'a', text: 't', choices: [{ id: 'x"y', label: '<i>' }] }, 0, 0, true));
  assert.ok(h.includes('data-choice="x&quot;y"') && h.includes('&lt;i&gt;') && h.includes(' disabled'));
});

// timeout note
check('timeoutNote window of 4 s', () => {
  const lt = { test: 10, step: 'on', label: 'l', atMs: 100000 };
  eq(U.timeoutNote(lt, 0, 100000), 'Test 10: no answer, moving on');
  eq(U.timeoutNote(lt, 0, 103999) !== null, true);
  eq(U.timeoutNote(lt, 0, 104000), null);
  eq(U.timeoutNote(lt, 0, 200000), null);
});
check('timeoutNote uses hub time', () => {
  const lt = { test: 7, atMs: 100000 };
  eq(U.timeoutNote(lt, 500000, 100000 - 500000 + 2000) !== null, true);
  eq(U.timeoutNote(lt, 500000, 100000 - 500000 + 4000), null);
});
check('timeoutNote none', () => { eq(U.timeoutNote(null, 0, 1), null); eq(U.timeoutNote({}, 0, 1), null); });

// pad / controls
check('padEnabled by run status', () => {
  eq(U.padEnabled(null), true); eq(U.padEnabled({ status: null }), true); eq(U.padEnabled({ status: 'running' }), false);
  for (const s of ['done', 'aborted', 'stalled', 'interrupted']) eq(U.padEnabled({ status: s }), true);
});
check('controls', () => {
  const dev = { selected: true };
  deq(U.controls({ device: dev, run: null }), { run: true, abort: false, setup: true, pad: true });
  deq(U.controls({ device: dev, run: { status: 'running' } }), { run: false, abort: true, setup: false, pad: false });
  eq(U.controls({ device: { selected: false }, run: null }).run, false);
});

// pad log
const pe = (at, button, key, n) => ({ atMs: at, button, key, lines: Array.from({ length: n }, (_, i) => 'line ' + (i + 1)) });
check('padLogView: up to 6 lines are all shown, no toggle', () => {
  const v = U.padLogView([pe(1000, 'home', null, 6)], 0, {})[0];
  eq(v.lines.length, 6); eq(v.hidden, 0); eq(v.canCollapse, false);
  const h = U.padLogHtml([v]);
  assert.ok(!h.includes('pad-more'));
});
check('padLogView: beyond 6 lines the rest hides behind more', () => {
  const v = U.padLogView([pe(1000, 'home', null, 15)], 0, {})[0];
  eq(v.lines.length, 6); eq(v.lines[5], 'line 6'); eq(v.hidden, 9); eq(v.expanded, false);
  const h = U.padLogHtml([v]);
  assert.ok(h.includes('+9 more') && h.includes('data-at="1000"') && !h.includes('line 7'));
});
check('padLogView: an opened entry shows every line and offers less', () => {
  const v = U.padLogView([pe(1000, 'home', null, 15), pe(2000, 'mute', null, 3)], 0, { 1000: true })[0];
  eq(v.lines.length, 15); eq(v.hidden, 0); eq(v.canCollapse, true);
  const h = U.padLogHtml([v]);
  assert.ok(h.includes('line 15') && h.includes('>less<') && !/\+\d+ more/.test(h));
});
check('padLogView: order kept (newest last), time, key and missing fields', () => {
  const vs = U.padLogView([pe(1000, 'home', null, 0), { atMs: 2000, button: 'sendKey', key: 'KEY_INFO' }], 0, null);
  eq(vs.length, 2); eq(vs[0].atMs, 1000); eq(vs[1].key, 'KEY_INFO'); eq(vs[1].lines.length, 0);
  assert.ok(/^\d\d:\d\d:\d\d$/.test(vs[0].time));
  deq(U.padLogView(null, 0, {}), []); deq(U.padLogView(undefined, 0, {}), []);
});
check('clockText follows the offset', () => {
  const d = new Date(2026, 10, 8, 12, 30, 45).getTime();
  eq(U.clockText(d, 0), '12:30:45'); eq(U.clockText(d + 60000, 60000), '12:30:45');
});
check('padLogHtml: empty state, escapes hub text', () => {
  assert.ok(U.padLogHtml([]).includes('No keys pressed yet'));
  const h = U.padLogHtml(U.padLogView([{ atMs: 1, button: '<b>', key: 'K"Y', lines: ['<i>&'] }], 0, {}));
  assert.ok(h.includes('&lt;b&gt;') && !h.includes('<i>') && h.includes('&lt;i&gt;&amp;') && h.includes('K&quot;Y'));
});
check('padLogText: all lines, collapsed or not', () => {
  const t = U.padLogText([pe(1000, 'home', null, 8), pe(2000, 'sendKey', 'KEY_INFO', 0)], 0);
  const rows = t.split('\n');
  eq(rows.length, 10); assert.ok(rows[0].endsWith(' home') && rows[9].endsWith(' sendKey KEY_INFO') && rows[8] === '   line 8');
  eq(U.padLogText(null, 0), '');
});
check('pad log panel sits under the pad and has Copy and Clear', () => {
  assert.ok(html.indexOf('id="pad-log"') > html.indexOf('id="pad"') && /id="pad-log-copy"/.test(html) && /id="pad-log-clear"/.test(html) && html.includes('id="pad-log-entries"'));
});

// version banner
check('versionBanner', () => {
  eq(U.versionBanner('0.4.0', '0.4.0'), null);
  eq(U.versionBanner('0.4.0', undefined), null);
  assert.ok(/0\.4\.0.*0\.4\.1/.test(U.versionBanner('0.4.0', '0.4.1')));
});
check('deviceLine', () => {
  eq(U.deviceLine({ name: 'TV', switch: 'on', wsStatus: 'open', selected: true }), 'TV · switch on · socket open');
  eq(U.deviceLine({ selected: false }), 'No device selected');
});

// urls and setup serialization
check('accessToken parse', () => {
  eq(U.accessToken('?access_token=abc-123'), 'abc-123'); eq(U.accessToken('?x=1&access_token=a%20b'), 'a b'); eq(U.accessToken(''), '');
});
check('endpointUrl replaces last path segment', () => {
  const loc = { pathname: '/apps/api/12/ui.html', search: '?access_token=tok' };
  eq(U.endpointUrl(loc, 'status'), '/apps/api/12/status?access_token=tok');
  eq(U.endpointUrl(loc, 'answer', { id: 'p7', choice: 'on' }), '/apps/api/12/answer?id=p7&choice=on&access_token=tok');
});
check('runParams all tests, setup answers', () => {
  const p = U.runParams({ powerOnWithMobile: 'yes', ipRemote: 'no' }, {});
  deq(p, { powerOnWithMobile: 'yes', ipRemote: 'no', attended: 'yes' });
  eq(U.encodeQuery(p), 'powerOnWithMobile=yes&ipRemote=no&attended=yes');
});
check('runParams defaults and bad values to unknown', () => {
  deq(U.runParams(null, null), { powerOnWithMobile: 'unknown', ipRemote: 'unknown', attended: 'yes' });
  eq(U.runParams({ powerOnWithMobile: 'maybe', ipRemote: 'yes' }, null).powerOnWithMobile, 'unknown');
});
check('runParams attended: unchecked sends no, anything else yes', () => {
  eq(U.runParams({ attended: 'no' }, null).attended, 'no');
  eq(U.runParams({ attended: false }, null).attended, 'no');
  eq(U.runParams({ attended: 'yes' }, null).attended, 'yes');
  eq(U.runParams({ attended: 'maybe' }, null).attended, 'yes');
  eq(U.runParams({}, null).attended, 'yes');
  eq(U.encodeQuery(U.runParams({ attended: 'no' }, null)), 'powerOnWithMobile=unknown&ipRemote=unknown&attended=no');
});
check('attended checkbox is in the setup box and checked by default', () => {
  assert.ok(/<section id="setup">[\s\S]*<input type="checkbox" id="setup-attended" checked>[\s\S]*<\/section>/.test(html));
});
check('runParams subset of tests', () => {
  eq(U.runParams({}, { 1: false, 2: false }).tests, '3,4,5,6,7,8,9,10,11,12,13,14,15');
  eq(U.runParams({}, { 1: true }).tests, undefined);
});

// report / profile
check('reportView with profile', () => {
  const v = U.reportView({ status: 'done', report: 'line1\nline2', profile: { model: 'X' } });
  eq(v.text, 'line1\nline2'); eq(v.hasProfile, true); deq(JSON.parse(v.profileJson), { model: 'X' }); assert.ok(v.profileJson.endsWith('\n'));
});
check('reportView without profile', () => {
  const v = U.reportView({ report: 'r', profile: null });
  eq(v.hasProfile, false); eq(v.profileJson, null);
  eq(U.reportView(null).text, '');
});
check('runKey one report per run', () => {
  eq(U.runKey(null), null); eq(U.runKey({ status: 'running', startedAtMs: 5 }), null);
  eq(U.runKey({ status: 'done', startedAtMs: 5 }), '5'); eq(U.runKey({ status: 'aborted', startedAtMs: 6 }), '6');
});

// polling
check('pollState note after 3 failures', () => {
  let s = null;
  s = U.pollState(s, false); eq(s.noteVisible, false);
  s = U.pollState(s, false); eq(s.noteVisible, false);
  s = U.pollState(s, false); eq(s.noteVisible, true);
  s = U.pollState(s, true); eq(s.noteVisible, false); eq(s.fails, 0);
});

check('escapeHtml', () => {
  eq(U.escapeHtml(`<a href="x">'&'</a>`), '&lt;a href=&quot;x&quot;&gt;&#39;&amp;&#39;&lt;/a&gt;');
  eq(U.escapeHtml(null), '');
});

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
