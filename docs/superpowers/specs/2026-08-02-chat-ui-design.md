# Chat UI Design — Knowledge Graph

**Date:** 2026-08-02
**Goal:** A single-page chat UI for the Knowledge Graph app — ask questions grounded in a Neo4j graph, with inline PDF ingestion to populate the graph. Chat + drag-and-drop upload as one seamless flow.

## Tech Stack & Serving

Served by Spring Boot from `src/main/resources/static/` (auto-served static assets). No Node toolchain, no build step, no `package.json`. The repo stays pure Java/Gradle. All client libraries loaded from CDN via `<script>`/`<link>` tags in `index.html`.

**Client stack (CDN, zero npm):**

- **Alpine.js 3.x** — reactive UI state (messages, conversations, loading). Chosen over htmx because the API returns JSON, not HTML fragments; Alpine handles JSON→DOM reactivity cleanly.
- **Pico.css 2.x** — classless CSS framework; semantic HTML looks clean out of the box, supports light/dark via `prefers-color-scheme`. Minimal hand-written CSS on top.
- **marked 12.x** — render the LLM's Markdown answers to HTML.
- **highlight.js 11.x** — Cypher syntax highlighting in the trace panel.
- **DOMPurify 3.x** — sanitize rendered Markdown/HTML before injection (LLM output is untrusted).

CDN scripts carry SRI `integrity` hashes where the CDN provides them.

## Backend

Zero changes. The UI calls the four existing endpoints as-is:

| Endpoint | Used by UI for |
|---|---|
| `POST /api/knowledge-graph` (multipart `file`) | PDF ingestion from drop zone |
| `POST /api/knowledge-graph/chat` (JSON `{prompt, conversationId}`) | Chat submit |
| `DELETE /api/knowledge-graph/chat?conversationId=X` | Sidebar conversation delete |

## Layout & Components

Two-column layout on desktop (sidebar + chat panel), collapsing to a single stacked column on mobile.

### Sidebar — conversation list

- "New chat" button at top (starts a conversation with a fresh `conversationId`; server resolves to a generated UUID).
- Conversation entries: title = first user prompt (truncated to 40 chars), active one highlighted.
- Hover an entry reveals a trash icon; click → `confirm()` → `DELETE` the conversation → remove from list. If deleting the active one, switch to a new-chat state (empty panel, pending first message).
- Conversations persisted in `localStorage` as a map keyed by `conversationId`. On page load, the sidebar hydrates from localStorage. The chat history for a conversation is **not** stored client-side; switching conversations clears the thread and shows a hint. The backend's H2 memory carries prior turns — the next prompt the user sends will include that memory automatically.

### Chat panel — drop zone + message thread + input

- **Drop zone** at the top: a thin zone labeled "Drop a PDF here or click to upload". Always visible (even mid-conversation) since you might ingest more docs. Supports drag-and-drop and click-to-browse. One file at a time.
- **Message thread**: vertical scroll, newest at bottom.
  - User messages: right-aligned bubble.
  - Assistant messages: left-aligned, Markdown-rendered (via `marked` + `DOMPurify`).
  - System messages (ingest summaries, errors): centered, muted, with icon (✓ success, ⚠ error).
- **Input row** pinned at bottom: textarea (auto-grows, Enter to send, Shift+Enter for newline) + Send button. Disabled while a request is in flight.

### Trace toggle — below each assistant message

A small row: "🔧 3 tool rounds · 2 Cypher queries · 197 rows" — clicking expands a panel revealing:

- **Skills executed**: list of skill names.
- **Cypher queries**: each query in a `<pre>` with `highlight.js` syntax highlighting; a "Copy" button per query.
- **Result rows**: rendered as an HTML table (columns from the Cypher RETURN; nested maps shown as JSON in a cell). Capped at 50 rows with a "showing 50 of N" note (client-side cap, independent of the backend `truncated` flag).

Collapsed by default; per-message toggle.

## State Model

Single Alpine `x-data` object on the root:

```js
{
  conversations: [],    // hydrated from localStorage: [{id, title, createdAt}]
  activeId: null,       // conversationId of the open conversation
  messages: [],         // current thread: [{role, content, trace, error, type}]
  draft: '',            // textarea contents
  loading: false,       // chat request in flight
  uploading: false      // ingest in flight
}
```

## Flows

### Flow 1 — Page load

```
page loads
 → read localStorage kg.conversations map
 → render sidebar list
 → if conversations exist: set activeId to most recent
    (DON'T fetch history — show empty thread + hint
     "Continue conversation: send a message.")
 → else: show empty state "Send a message or drop a PDF to begin."
```

### Flow 2 — Send a chat message

```
user types, hits Send (or Enter)
 → push {role:'user', content:draft} to messages
 → clear draft, set loading=true
 → placeholder assistant bubble: "Working… 12s" (elapsed timer)
 → POST /api/knowledge-graph/chat {prompt, conversationId}
    ↳ activeId null → server generates UUID;
      read from response.conversationId, persist to localStorage
      with title = first prompt
    ↳ else use activeId
 → on 200: replace placeholder with
    {role:'assistant', content:answer, trace:{cypherQueries, skillsExecuted,
                                              rowCount, results, error}}
 → on error: push {role:'system', type:'error', content: error message}
 → loading=false
```

### Flow 3 — Switch conversation

```
click sidebar entry
 → activeId = entry.id
 → clear messages, set loading=false
 → show hint "Continue conversation — your history is on the server;
   send a message to proceed."
```

Backend H2 memory holds the prior turns; the next prompt includes that memory automatically. The UI does not fetch or replay history.

### Flow 4 — Upload a PDF

```
user drops file onto drop zone
 → client-side guard: reject non-.pdf, ignore extra files
 → set uploading=true,
   show centered system message "Uploading <filename>…"
 → POST /api/knowledge-graph (multipart, file)
 → on 200: read GraphSummaryResponse
    ↳ SKIPPED_DUPLICATE → "Skipped: <filename> already ingested"
    ↳ else → "✓ Ingested <filename> — N nodes, R relationships,
               F chunks failed" (format counts by label/relType)
 → on error (413, 503, etc.) → "⚠ Upload failed: <message>"
 → uploading=false
```

Upload is independent of the active conversation — ingesting a PDF doesn't create or switch conversations; it just populates the graph. The user can then ask chat questions about the newly ingested content in whatever conversation is active.

### Flow 5 — Delete a conversation

```
hover sidebar entry → trash icon → click
 → confirm() "Delete this conversation?"
 → DELETE /api/knowledge-graph/chat?conversationId=X
 → on 204: remove from localStorage + sidebar
    ↳ if it was active → reset to empty thread, activeId=null,
      hint "New chat — send a message."
 → on 404: remove from localStorage anyway (server has nothing)
   + remove from sidebar
```

## localStorage Schema

Single namespaced key (`kg.conversations`); never store message bodies. Survives reloads; stays tiny.

```json
{
  "kg.conversations": {
    "c397217e-6045-4dd1-b63f-3feb54d2cd54": {
      "title": "What did IBM create?",
      "createdAt": "2026-08-02T12:00:00Z"
    }
  }
}
```

## Error Handling

All errors surface as a centered system message with a ⚠ icon and the message text; the request flag (`loading`/`uploading`) resets to false. No toasts/modals.

| Status | Source | UI message |
|---|---|---|
| 200 `SKIPPED_DUPLICATE` | upload | "Skipped: `<filename>` already ingested" |
| 200 empty (0 nodes) | upload | "Ingested `<filename>` — extracted 0 nodes (PDF may be image-only or encrypted)" |
| 413 | upload (>50 pages) | "Upload failed: PDF exceeds 50-page limit" |
| 503 | upload/chat (Neo4j down) | "Service unavailable: Neo4j is not reachable" |
| 400 | chat (blank/oversize prompt) | error text from body (`InvalidPromptException` message) |
| 500 (`ChatException`) | chat | "Chat failed: `<error>`" |
| network failure | either | "Network error — is the server running?" |

## Edge Cases

- **Orphaned localStorage entry** (server reset, H2 wiped): `DELETE` returns 404 → remove the entry from localStorage and sidebar anyway.
- **Reload mid-conversation**: conversation list survives; thread is cleared. The hint makes it clear the history is server-side, not lost.
- **Send while loading**: Send button + textarea Enter handler disabled while `loading` is true.
- **Upload while chat loading**: allowed independently; `uploading` and `loading` are separate flags.
- **Non-PDF dropped**: client-side guard — reject anything without `.pdf` extension, show a transient inline error in the drop zone.
- **Multiple files dropped**: ignore all but the first; show a warning.
- **Large trace** (hundreds of result rows): trace table caps at 50 rows with "showing 50 of N" (client-side cap, independent of backend `truncated`).
- **Long Cypher query**: `<pre>` with horizontal scroll; "Copy" button copies raw text.
- **Empty conversation list on first visit**: centered empty-state in the chat panel: "Send a message or drop a PDF to begin."

## Markdown & Safety

- LLM answers render through `marked.parse()` then `DOMPurify.sanitize()` before `innerHTML` injection.
- Cypher queries in the trace pass through a text-only path (`textContent`, no HTML) — shown in `<pre><code>` via highlight.js's `textContent` API, so no injection risk.

## File Structure

All new files under `src/main/resources/static/`:

```
static/
  index.html              — root page, loads CDN libs, mounts Alpine root
  css/
    app.css               — Pico.css overrides, layout, bubble styles, drop zone
  js/
    app.js                — Alpine component: state, fetch logic, localStorage, 5 flows
    trace.js              — Alpine component for the per-message trace panel
```

Single `index.html` at the static root → Spring Boot serves it at `GET /`. No client-side router (single page, one view). All libraries CDN-loaded in `index.html` `<head>`; no `vendor/` directory.

## Out of Scope

- No backend Java/config changes — no new endpoints, no DTO changes, no streaming.
- No Node/npm toolchain.
- No graph visualization (node-link diagrams) — tables only.
- No client-side message history persistence (only conversation metadata in localStorage; full history stays server-side in H2).
- No automated tests (matches repo convention).
- No auth/multi-user concerns (single-user local tool).

## Testing & Verification

There is no test framework in the repo and the existing convention is "no test scaffolding" (per AGENTS.md and the multi-turn plan's global constraints). For this UI, verification is:

1. `./gradlew compileJava` — must pass (the spec adds no Java; this just confirms nothing broke).
2. **Manual smoke test** — `./gradlew bootRun`, open `http://localhost:8080`, walk through each flow:
   - Drop `procurement.pdf` (repo root) → see ingest summary.
   - Type "What did Company X create?" → see spinner → see answer + expandable trace → Cypher query visible, result table renders.
   - Send a follow-up ("Tell me more about...") → verify memory resolves the pronoun.
   - New chat → sends same first prompt → sidebar gets second entry → switch back to first → hint shows → follow-up resolves via server memory.
   - Delete a conversation → sidebar updates → active cleared.
   - Drag a `.txt` file → rejection shows.
   - Stop Neo4j → send a chat → see "Service unavailable" system message.

No automated tests added. The static files are validated by manual smoke since there's no JS test runner and adding one is out of scope.