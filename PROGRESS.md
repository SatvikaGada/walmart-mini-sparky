# Progress

## Done
- Phase 0: Spring Boot 4.1.1 skeleton, Postgres via Docker Compose, health check
- Phase 1: commerce core (schema + seed, catalog/stock read APIs, atomic reserve/release/commit SQL,
  carts with TTL + expiry job (SKIP LOCKED), policy guard, two-principal auth filter,
  audit log, idempotent checkout, cancel), 12 integration tests on Testcontainers Postgres

## In progress
- nothing (Checkpoint A reached)

## Next
- Phase 2: agent service (FastAPI + LangGraph), minimal UI

## Known issues
- Demo auth only (HMAC session token); rate limiter is in-memory
- Failed checkouts are not stored under their idempotency key (retry re-executes)

## Measured numbers (fill from real runs only)
- Concurrency test: 100 threads, 10 units -> 10 succeed, 90 OUT_OF_STOCK
- JaCoCo total coverage: <paste your number>