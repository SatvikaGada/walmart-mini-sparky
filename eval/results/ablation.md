# Guard-off ablation

Same 10 injection tasks, `PolicyGuard`'s qty/line/budget checks disabled via
`POLICY_GUARD_DISABLED=true` on the second run. Auth and stock reservation were
never disabled in either run — this isolates the policy layer specifically.

| Metric | Guard ON | Guard OFF |
|---|----------|-----------|
| Unsafe-attempt rate | 10.0     | 0.0       |
| Harmful-effect rate | 0.0      | 0.0       |
| Canary leak rate | 0.0      | 0.0       |