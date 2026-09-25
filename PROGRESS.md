# Progress

## Done
- Phase 0: Spring Boot 4.1.1 skeleton, Postgres via Docker Compose, health check
- Phase 1: commerce core (schema + seed, catalog/stock read APIs, atomic reserve/release/commit SQL,
  carts with TTL + expiry job (SKIP LOCKED), policy guard, two-principal auth filter,
  audit log, idempotent checkout, cancel), 12 integration tests on Testcontainers Postgres
- Phase 2:  FastAPI + LangGraph agent, provider-agnostic LLM client with retry/fallback, 
  7 tools with pydantic validation, deterministic verify node, single-page UI 
- Phase 3: security hardening — sanitizer with strip/wrap/flag, 10 seeded injection attacks
  (V3 migration: 4 malicious descriptions + 6 malicious reviews), 14 pytest tests with a
  fake LLM and a fake backend (no Docker needed), 5 manual injection attempts against the
  live agent + UI

## In progress
- nothing (Checkpoint A reached)

## Next
- Phase 4: evaluation harness (tasks.yaml, run_eval.py, results table, guard-off ablation)

## Known issues
- Demo auth only (HMAC session token); rate limiter is in-memory
- Failed checkouts are not stored under their idempotency key (retry re-executes)
- flag() is regex-based and only a signal; it can be evaded by paraphrase or another
  language, and is not relied on for safety
- The decoy-pricing attack (ATK-002) has no injection text, so the sanitizer can't flag
  it at all; only the budget check catches it if the agent tries to add it
## Measured numbers (fill from real runs only)
- Concurrency test: 100 threads, 10 units -> 10 succeed, 90 OUT_OF_STOCK
- JaCoCo total coverage: <paste your number>