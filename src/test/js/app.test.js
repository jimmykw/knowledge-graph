'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { loadScript, component } = require('./alpine-stub');

loadScript('trace.js');
loadScript('app.js');

const app = () => component('chatApp');

test('buildTrace maps every field of a chat response, including evidence', () => {
  const body = {
    cypherQueries: ['Q'], skillsExecuted: ['entity-lookup'], results: [{ a: 1 }], evidence: [{ cypher: 'Q', count: 1 }],
    rowCount: 1, truncated: true, error: 'e', route: { status: 'BLOCKED' }, quality: { status: 'LOW' }
  };
  assert.deepEqual(app().buildTrace(body), body);
});

test('buildTrace tolerates a missing body and old responses without evidence', () => {
  assert.deepEqual(app().buildTrace(null), {
    cypherQueries: [], skillsExecuted: [], results: [], evidence: [], rowCount: 0, truncated: false, error: null, route: null, quality: null
  });
  assert.deepEqual(app().buildTrace({ cypherQueries: ['Q'] }).evidence, []);
});

test('chatErrorMessage per HTTP status', () => {
  const c = app();
  assert.equal(c.chatErrorMessage(503, null), 'Service unavailable: Neo4j is not reachable.');
  assert.equal(c.chatErrorMessage(422, { conversationId: null, error: 'graph empty' }), 'Chat failed: graph empty');
  assert.equal(c.chatErrorMessage(400, { conversationId: null, error: 'Prompt must not be blank.' }), 'Prompt must not be blank.');
  assert.equal(c.chatErrorMessage(422, { conversationId: null }), 'Chat failed: Chat failed.');
  assert.equal(c.chatErrorMessage(500, { message: 'server said' }), 'server said');
  assert.equal(c.chatErrorMessage(400, null), 'Invalid prompt.');
  assert.equal(c.chatErrorMessage(502, null), 'Chat failed (HTTP 502).');
});

test('uploadErrorMessage per HTTP status, including the unreadable-PDF 400', () => {
  const c = app();
  assert.equal(c.uploadErrorMessage(503, null), 'Service unavailable: Neo4j is not reachable.');
  assert.equal(c.uploadErrorMessage(413, { message: 'PDF has 9 pages' }), 'PDF has 9 pages');
  assert.equal(c.uploadErrorMessage(413, null), 'PDF exceeds the page limit or is too large.');
  assert.equal(c.uploadErrorMessage(400, { message: 'PDF could not be read (corrupt, encrypted or not a PDF).' }),
    'PDF could not be read (corrupt, encrypted or not a PDF).');
  assert.equal(c.uploadErrorMessage(400, null), 'Invalid file.');
  assert.equal(c.uploadErrorMessage(500, null), 'HTTP 500');
});

test('ingestResultMessage covers failure, duplicate and success', () => {
  const c = app();
  const error = c.ingestResultMessage({ ok: false, status: 400 }, { message: 'bad pdf' }, 'a.pdf');
  assert.equal(error.type, 'error');
  assert.equal(error.content, 'Upload failed: bad pdf');
  const dup = c.ingestResultMessage({ ok: true, status: 200 }, { status: 'SKIPPED_DUPLICATE' }, 'a.pdf');
  assert.equal(dup.content, 'Skipped: a.pdf already ingested.');
  const done = c.ingestResultMessage({ ok: true, status: 200 }, { nodeCount: 1, relationshipCount: 0 }, 'a.pdf');
  assert.equal(done.type, 'info');
});

test('formatIngestSummary pluralises and lists labels, types and failed chunks', () => {
  const c = app();
  assert.equal(c.formatIngestSummary({ nodeCount: 3, relationshipCount: 1, countsByLabel: { Person: 2, Org: 1 }, countsByType: { USES: 1 }, failedChunks: 2 }, 'a.pdf'),
    'Ingested a.pdf — 3 nodes (2 Person, 1 Org), 1 relationship (1 USES), 2 chunks failed');
  assert.equal(c.formatIngestSummary({ nodeCount: 1, relationshipCount: 0, failedChunks: 1 }, 'a.pdf'),
    'Ingested a.pdf — 1 node, 0 relationships, 1 chunk failed');
  assert.equal(c.formatIngestSummary({ nodeCount: 4, relationshipCount: 2, entitiesMerged: 3, unresolvedEntities: 1, failedChunks: 0 }, 'a.pdf'),
    'Ingested a.pdf — 4 nodes, 2 relationships, 3 merged into existing, 1 unresolved');
  assert.equal(c.formatIngestSummary({ nodeCount: 2, relationshipCount: 0, entitiesMerged: 0, unresolvedEntities: 0 }, 'a.pdf'),
    'Ingested a.pdf — 2 nodes, 0 relationships');
  assert.equal(c.formatIngestSummary(null, 'a.pdf'), 'Uploaded a.pdf.');
  assert.match(c.formatIngestSummary({ nodeCount: 0 }, 'a.pdf'), /extracted 0 nodes/);
});

test('countParts skips zero counts and handles empty input', () => {
  const c = app();
  assert.equal(c.countParts(null), '');
  assert.equal(c.countParts({ A: 0 }), '');
  assert.equal(c.countParts({ A: 2, B: 0, C: 1 }), '2 A, 1 C');
});

test('hintMessage depends on the conversation state', () => {
  const c = app();
  assert.equal(c.hintMessage(), 'Send a message or drop a PDF to begin.');
  c.conversations = [{ id: 'x' }];
  assert.equal(c.hintMessage(), 'New chat — send a message.');
  c.activeId = 'x';
  assert.match(c.hintMessage(), /^Continue conversation/);
});

test('messageClass and systemIcon', () => {
  const c = app();
  assert.equal(c.messageClass({ role: 'system', type: 'error' }), 'kg-msg kg-system kg-error');
  assert.equal(c.messageClass({ role: 'user' }), 'kg-msg kg-user');
  assert.equal(c.systemIcon('error'), '⚠');
  assert.equal(c.systemIcon('progress'), '↻');
  assert.equal(c.systemIcon('info'), '✓');
});

test('elapsedFor counts whole seconds from startedAt', () => {
  const c = app();
  c.now = 10_500;
  assert.equal(c.elapsedFor({ startedAt: 7_000 }), 3);
  assert.equal(c.elapsedFor({}), 0);
});

test('renderMarkdown escapes the text when DOMPurify is unavailable', () => {
  const c = app();
  assert.equal(c.renderMarkdown(''), '');
  assert.equal(c.renderMarkdown('<img src=x onerror=alert(1)>'), '&lt;img src=x onerror=alert(1)&gt;');
});

test('conversations persist to localStorage, newest first, and can be removed', () => {
  localStorage.clear();
  const c = app();
  c.persistConversation('older', 'first question');
  c.readMap(); // exercise the read path
  const map = c.readMap();
  map.older.createdAt = '2000-01-01T00:00:00.000Z';
  c.writeMap(map);
  c.persistConversation('newer', 'x'.repeat(100));

  assert.deepEqual(c.loadConversations().map((conv) => conv.id), ['newer', 'older']);
  assert.equal(c.readMap().newer.title.length, 40);
  c.removeConversation('older');
  assert.deepEqual(c.conversations.map((conv) => conv.id), ['newer']);
});

test('readMap recovers from corrupt stored data', () => {
  localStorage.setItem('kg.conversations', '{not json');
  assert.deepEqual(app().readMap(), {});
  localStorage.clear();
});

test('startNewChat and selectConversation reset the thread', () => {
  const c = app();
  c.activeId = 'a';
  c.messages = [{ key: 1 }];
  c.draft = 'x';
  c.startNewChat();
  assert.deepEqual([c.activeId, c.messages.length, c.draft], [null, 0, '']);
  c.loading = true;
  c.selectConversation('b');
  assert.deepEqual([c.activeId, c.messages.length, c.loading], ['b', 0, false]);
});

test('replaceMessage swaps by key and reports whether it found the message', () => {
  const c = app();
  c.messages = [{ key: 1, content: 'a' }, { key: 2, content: 'b' }];
  assert.equal(c.replaceMessage({ key: 2 }, { key: 3, content: 'c' }), true);
  assert.deepEqual(c.messages.map((message) => message.key), [1, 3]);
  assert.equal(c.replaceMessage({ key: 99 }, { key: 4 }), false);
});

test('nextKey increases monotonically', () => {
  const c = app();
  assert.deepEqual([c.nextKey(), c.nextKey(), c.nextKey()], [1, 2, 3]);
});

test('uploadFile rejects non-PDF files before any request', async () => {
  const c = app();
  await c.uploadFile({ name: 'notes.txt' });
  assert.equal(c.uploadError, 'Only PDF files are accepted.');
  assert.equal(c.messages.length, 0);
  assert.equal(c.uploading, false);
});

test('handleDrop ignores extra files with a notice and uploads the first', () => {
  const c = app();
  const uploaded = [];
  c.uploadFile = (file) => uploaded.push(file.name);
  c.handleDrop({ dataTransfer: { files: [{ name: 'a.pdf' }, { name: 'b.pdf' }, { name: 'c.pdf' }] } });
  assert.deepEqual(uploaded, ['a.pdf']);
  assert.match(c.messages[0].content, /Ignoring 2 extra file/);
});

test('onEnter sends on Enter and lets Shift+Enter insert a newline', () => {
  const c = app();
  let sent = 0;
  c.sendMessage = () => { sent++; };
  let prevented = 0;
  c.onEnter({ shiftKey: true, preventDefault: () => { prevented++; } });
  assert.deepEqual([sent, prevented], [0, 0]);
  c.onEnter({ shiftKey: false, preventDefault: () => { prevented++; } });
  assert.deepEqual([sent, prevented], [1, 1]);
});

test('sendMessage posts the prompt, stores the new conversation and shows the answer with its trace', async () => {
  localStorage.clear();
  const c = app();
  const requests = [];
  globalThis.fetch = async (url, options) => {
    requests.push({ url, body: JSON.parse(options.body) });
    return { ok: true, status: 200, json: async () => ({ answer: 'IBM did it', conversationId: 'conv-1', cypherQueries: ['Q'], evidence: [] }) };
  };
  c.draft = '  Who built it?  ';

  await c.sendMessage();

  assert.deepEqual(requests, [{ url: '/api/knowledge-graph/chat', body: { prompt: 'Who built it?' } }]);
  assert.equal(c.activeId, 'conv-1');
  assert.equal(c.loading, false);
  assert.deepEqual(c.messages.map((message) => message.role), ['user', 'assistant']);
  assert.equal(c.messages[1].content, 'IBM did it');
  assert.deepEqual(c.messages[1].trace.cypherQueries, ['Q']);
  assert.equal(c.readMap()['conv-1'].title, 'Who built it?');
  delete globalThis.fetch;
});

test('sendMessage reports a server error body as a system error message', async () => {
  const c = app();
  globalThis.fetch = async () => ({ ok: false, status: 503, json: async () => null });
  c.draft = 'hi';

  await c.sendMessage();

  assert.equal(c.messages[1].type, 'error');
  assert.equal(c.messages[1].content, 'Service unavailable: Neo4j is not reachable.');
  delete globalThis.fetch;
});

test('sendMessage reports a network failure', async () => {
  const c = app();
  globalThis.fetch = async () => { throw new Error('offline'); };
  c.draft = 'hi';

  await c.sendMessage();

  assert.match(c.messages[1].content, /Network error/);
  assert.equal(c.loading, false);
  delete globalThis.fetch;
});

test('sendMessage ignores an empty draft', async () => {
  const c = app();
  c.draft = '   ';
  await c.sendMessage();
  assert.equal(c.messages.length, 0);
});
