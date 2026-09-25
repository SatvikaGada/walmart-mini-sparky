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
- Phase 4: 30-task evaluation harness (12 normal, 4 stock, 4 budget, 10 injection); verifies
  cart totals and items from the backend's real state, not from the LLM's reply; guard-on vs
  guard-off ablation isolating the PolicyGuard's contribution

## In progress
- nothing (Checkpoint A reached)

## Next
- Phase 5: polish — README, k6 flash-sale load test, GitHub Actions CI, JaCoCo badge, git tag v1.0
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