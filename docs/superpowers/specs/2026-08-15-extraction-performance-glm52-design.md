# Extraction Performance: switch to GLM 5.2 + raise concurrency

**Date:** 2026-08-15
**Status:** Approved (building)
**Scope:** `POST /api/knowledge-graph` end-to-end latency — the two LLM extraction passes.

## Problem

End-to-end `POST /api/knowledge-graph` is slow. The two LLM extraction passes
(Pass 1: entities, Pass 2: relationships) dominate runtime; PDF parse/split and the
batched Neo4j writes are negligible by comparison.

Wall-clock model:

```
total ≈ Pass1 + Pass2
each pass ≈ (num_chunks × per_call_latency) / effective_concurrency
```

Current configuration bottlenecks both factors:

- `app.models.extract.pool-size: 2` — only 2 concurrent LLM calls per pass.
- Extraction model is a local Ollama 27B (`qwen3.8:27b-mlx`) at
  `http://localhost:11434/v1`. A local single-GPU server typically serializes
  requests, so the pool-size of 2 often does not produce true parallelism, and
  per-call latency for a 27B model is high.

## Constraints (from clarifying questions)

- **Pain point:** end-to-end request latency (not just PDF parsing).
- **Backend:** flexible — may switch model / use a hosted API.
- **Quality:** **preserve** current extraction output. Changes must not alter
  what entities/relationships are produced. This rules out merging the two
  passes (changes results) and changing chunk size (changes chunk boundaries).

## Approach

Combine two quality-neutral levers — lower per-call latency and higher real
concurrency — by switching extraction to the same OpenAI-compatible endpoint the
chat model already uses, and raising the pool size.

The chat model is already configured as `glm-5.2` at
`https://opencode.ai/zen/go/v1` with `${OPENAI_API_KEY}`. Extraction is built
from the same `OpenAiChatModel` + `ChatClient` wiring
(`ChatClientConfig.extractChatModel`), driven entirely by
`app.models.extract.*`. Therefore the switch is **configuration-only** — no code
changes.

### Why pool-size becomes effective

Ollama on a single local accelerator serializes requests, so the existing
`pool-size: 2` rarely yields 2× throughput. A hosted API can serve concurrent
requests, so raising `pool-size` to 8 produces ~linear speedup up to the API's
rate limit. The two passes remain sequential (Pass 2 depends on Pass 1's
canonical entity index), but within each pass the chunks are embarrassingly
parallel.

## Change

`src/main/resources/application.yml`, `app.models.extract` block:

```yaml
extract:
  base-url: https://opencode.ai/zen/go/v1   # was http://localhost:11434/v1 (Ollama)
  api-key: ${OPENAI_API_KEY}                # was ${EXTRACT_API_KEY:ollama}
  model: glm-5.2                            # was qwen3.8:27b-mlx
  temperature: 0.1                          # unchanged
  timeout: 600s                             # unchanged
  max-retries: 5                            # unchanged
  pool-size: 8                              # was 2
```

The previous local-Ollama values are kept as a YAML comment for easy rollback.

### No code changes required

- `ChatClientConfig.extractChatModel` reads these properties at startup
  (`ChatClientConfig.java:43`).
- `EntityExtractionService` and `RelationshipExtractionService` each size their
  `ExecutorService` from `poolSize`
  (`EntityExtractionService.java:54`, `RelationshipExtractionService.java:56`).
- Prompts, the two-pass structure, parsing, dedupe, and Neo4j writes are
  untouched → extraction output is preserved.

### `pool-size: 8` is the one judgment call

Higher is faster but risks rate-limiting the zen endpoint. 8 is a safe start.
Spring AI's retry/backoff (`max-retries: 5`) handles transient 429s; the
`ExtractionPrompter` also retries once on parse failure. Tune up or down based
on observed retry/429 warnings in the logs.

## Verification

1. `./gradlew compileJava` — sanity (no code change; should pass trivially).
2. `./gradlew bootRun` — requires Neo4j + APOC at `bolt://localhost:7688`.
3. If re-testing the same `procurement.pdf`, delete its `:Document {hash}` node
   first, otherwise the request returns `SKIPPED_DUPLICATE` and makes no LLM
   calls.
4. POST the sample `procurement.pdf` (see `.idea/httpRequests/`).
5. Compare the timing funnel already emitted by the code:
   - `Pass 1: LLM calls completed in Xms`
   - `Pass 2: LLM calls completed in Xms`
   - `Knowledge graph built in Xms`
   - per-chunk `Pass 1: chunk i/N completed` progress lines (concurrency visible
     from how many land in the same timestamp window).
6. Watch for repeated retry/429 warnings; lower `pool-size` if they persist.

## Expected impact

Hosted GLM 5.2 lowers per-call latency versus local 27B-MLX, and 4× concurrency
compounds it. Expect roughly a 5–20× end-to-end speedup depending on document
size and the endpoint's effective rate limit. Quality is unchanged (same prompts,
same parsing, same two-pass logic).

## Rollback

Revert the `app.models.extract` yaml block (the Ollama values are kept as a
comment) and redeploy.