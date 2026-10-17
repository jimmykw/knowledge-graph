'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { loadScript, component } = require('./alpine-stub');

loadScript('trace.js');

const panel = (trace) => component('tracePanel', trace);
const evidence = (count, extra = {}) => ({ cypher: 'Q' + count, count: count, rows: Array.from({ length: count }, (_, i) => ({ i: i })), truncated: false, ...extra });

test('kgEscape escapes html metacharacters and quotes', () => {
  assert.equal(window.kgEscape('<script>alert("x")</script>'), '&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt;');
  assert.equal(window.kgEscape("it's & that"), 'it&#39;s &amp; that');
});

test('kgEscape turns null and undefined into an empty string and stringifies numbers', () => {
  assert.equal(window.kgEscape(null), '');
  assert.equal(window.kgEscape(undefined), '');
  assert.equal(window.kgEscape(42), '42');
});

test('init supplies an empty trace when none is given', () => {
  const p = panel(null);
  p.init();
  assert.deepEqual(p.trace.cypherQueries, []);
  assert.equal(p.trace.rowCount, 0);
  assert.equal(p.trace.route, null);
});

test('summary totals rows across all queries with singular and plural wording', () => {
  const many = panel({ skillsExecuted: ['a', 'b'], evidence: [evidence(3), evidence(2)] });
  assert.equal(many.summary(), '🔧 2 tool rounds · 2 Cypher queries · 5 rows');
  const one = panel({ skillsExecuted: ['a'], evidence: [evidence(1)] });
  assert.equal(one.summary(), '🔧 1 tool round · 1 Cypher queries · 1 row');
});

test('summary flags an error', () => {
  assert.match(panel({ skillsExecuted: [], evidence: [], error: 'boom' }).summary(), /⚠ error$/);
});

test('old responses without evidence fall back to cypherQueries, rowCount and results', () => {
  const p = panel({ cypherQueries: ['MATCH (n) RETURN n'], rowCount: 4, results: [{ n: 1 }], truncated: true, skillsExecuted: [] });
  assert.deepEqual(p.queryList(), ['MATCH (n) RETURN n']);
  assert.equal(p.totalRows(), 4);
  assert.equal(p.shownIndex(), -1);
  assert.deepEqual(p.shownRows(), [{ n: 1 }]);
  assert.equal(p.shownCount(), 4);
  assert.equal(p.shownTruncated(), true);
});

test('queryMeta describes rows, truncation and errors', () => {
  const p = panel({ evidence: [evidence(1), evidence(5, { truncated: true }), evidence(0, { error: 'bad' }), evidence(0)] });
  assert.equal(p.queryMeta(0), '1 row');
  assert.equal(p.queryMeta(1), '5 rows · server-truncated');
  assert.equal(p.queryMeta(2), 'error: bad');
  assert.equal(p.queryMeta(3), '0 rows');
  assert.equal(p.queryMeta(9), '');
});

test('queryMetaEmpty is true only for successful queries with no rows', () => {
  const p = panel({ evidence: [evidence(2), evidence(0), evidence(0, { error: 'bad' })] });
  assert.equal(p.queryMetaEmpty(0), false);
  assert.equal(p.queryMetaEmpty(1), true);
  assert.equal(p.queryMetaEmpty(2), false);
  assert.equal(p.queryMetaEmpty(9), false);
});

test('shownIndex picks the query with the most rows and the later one on a tie', () => {
  assert.equal(panel({ evidence: [evidence(2), evidence(7), evidence(1)] }).shownIndex(), 1);
  assert.equal(panel({ evidence: [evidence(3), evidence(3)] }).shownIndex(), 1);
  assert.equal(panel({ evidence: [] }).shownIndex(), -1);
});

test('a picked query overrides the default and an out-of-range pick is ignored', () => {
  const p = panel({ evidence: [evidence(2), evidence(7)] });
  p.pick(0);
  assert.equal(p.shownIndex(), 0);
  assert.equal(p.shownCount(), 2);
  p.pick(5);
  assert.equal(p.shownIndex(), 1);
});

test('queriesWithRows lists only queries that returned rows', () => {
  const p = panel({ evidence: [evidence(0), evidence(4), evidence(0, { error: 'x' }), evidence(1)] });
  assert.deepEqual(p.queriesWithRows(), [{ index: 1, count: 4 }, { index: 3, count: 1 }]);
});

test('cappedRows limits the table to 50 rows', () => {
  assert.equal(panel({ evidence: [evidence(80)] }).cappedRows().length, 50);
  assert.equal(panel({ evidence: [evidence(3)] }).cappedRows().length, 3);
});

test('columnList is the union of keys across rows in first-seen order and skips non-objects', () => {
  const p = panel({ evidence: [{ cypher: 'Q', count: 3, rows: [{ a: 1, b: 2 }, { b: 3, c: 4 }, null, 'text'] }] });
  assert.deepEqual(p.columnList(), ['a', 'b', 'c']);
});

test('renderCell escapes markup and quotes in strings', () => {
  const p = panel({});
  assert.equal(p.renderCell('<script>alert(1)</script>'), '&lt;script&gt;alert(1)&lt;/script&gt;');
  assert.equal(p.renderCell('say "hi"'), 'say &quot;hi&quot;');
  assert.equal(p.renderCell(null), '');
  assert.equal(p.renderCell(7), '7');
});

test('renderCell renders objects as escaped pretty JSON inside a code element', () => {
  const html = panel({}).renderCell({ name: '<b>x</b>' });
  assert.match(html, /^<code class="kg-json">/);
  assert.ok(!html.includes('<b>'));
  assert.ok(html.includes('&lt;b&gt;x&lt;/b&gt;'));
});

test('routeBadge reports blocked, skipped and routed turns', () => {
  assert.equal(panel({ route: null }).routeBadge(), '');
  assert.equal(panel({ route: { status: 'BLOCKED', intent: 'CHITCHAT', graphProbability: 0.02 } }).routeBadge(),
    '💬 not from graph · CHITCHAT (98%)');
  assert.equal(panel({ route: { status: 'SKIPPED' } }).routeBadge(), 'routing skipped');
  assert.equal(panel({ route: { status: 'ROUTED' } }).routeBadge(), '');
});

test('quality chip, low flag and title for OK, LOW and SKIPPED', () => {
  const ok = panel({ quality: { status: 'OK', evidenceTruncated: false } });
  assert.equal(ok.qualityChip(), 'quality checked');
  assert.equal(ok.qualityIsLow(), false);
  assert.equal(ok.qualityTitle(), 'The judge found the answer grounded and relevant.');

  const low = panel({ quality: { status: 'LOW', feedback: 'not grounded', evidenceTruncated: true } });
  assert.equal(low.qualityChip(), 'quality low');
  assert.equal(low.qualityIsLow(), true);
  assert.match(low.qualityTitle(), /^not grounded/);
  assert.match(low.qualityTitle(), /may mean unverified/);

  const skipped = panel({ quality: { status: 'SKIPPED' } });
  assert.equal(skipped.qualityChip(), 'quality check unavailable');
  assert.equal(skipped.qualityTitle(), 'The quality judge could not score this answer.');

  assert.equal(panel({ quality: null }).qualityChip(), '');
  assert.equal(panel({ quality: null }).qualityIsLow(), false);
});

test('a LOW verdict without feedback falls back to a generic title', () => {
  assert.equal(panel({ quality: { status: 'LOW' } }).qualityTitle(), 'The judge rated this answer low.');
});

test('highlightQuery falls back to plain text when highlight.js is absent', () => {
  const el = { textContent: '' };
  panel({}).highlightQuery(el, 'MATCH (n) RETURN n');
  assert.equal(el.textContent, 'MATCH (n) RETURN n');
  panel({}).highlightQuery(el, undefined);
  assert.equal(el.textContent, '');
});

test('toggle flips the panel open state', () => {
  const p = panel({});
  p.toggle();
  assert.equal(p.open, true);
  p.toggle();
  assert.equal(p.open, false);
});
