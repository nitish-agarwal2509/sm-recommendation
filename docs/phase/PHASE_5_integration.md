# Phase 5 — End-to-End Integration

**Status: Complete** — 182 tests passing across all 3 modules.

## Goal
Run the full pipeline end-to-end locally: CSV → Flink job → JSON → Spring Boot API → HTTP response.
Validate that all components work together correctly before any cloud migration.

---

## End-to-End Flow
```
sms_insights_sample.csv
        │
        │  SmRecommendationJob.main() (Flink batch, runs in-process via @DynamicPropertySource)
        ▼
serving-api/target/e2e-recommendations.json
        │
        │  @SpringBootTest (RANDOM_PORT) — Spring context loads after Flink completes
        ▼
Spring Boot API (random port)
        │
        │  TestRestTemplate GET /recommendation?user_id=X&surface=Y
        ▼
JSON response (1 recommendation) + async fatigue write
```

---

## Implementation: `EndToEndIntegrationTest.java`

Location: `serving-api/src/test/java/supermoney/recommendation/api/integration/EndToEndIntegrationTest.java`

### Key design decisions

**`@DynamicPropertySource` runs Flink before Spring context loads.**
The static callback fires before Spring's `ApplicationContext` is created, which means:
- Flink output path can be registered as `recommendation.store.local-json-path` dynamically
- `LocalJsonRecommendationStore.load()` (`@PostConstruct`) reads the correct Flink output
- No test-specific `application.yaml` needed

**Initial Flink output snapshot.**
Flink output is parsed into `INITIAL_FLINK_OUTPUT` immediately after the job finishes, before the Spring context starts and API calls begin mutating the file via fatigue writes. Scenarios that verify pre-surface batch scoring (e.g. scenario4) read from this snapshot rather than the live file.

**`resolveConfigToFile()` helper.**
When `common` is installed as a JAR in `.m2`, `scoring_rules.yaml` is inside the JAR and cannot be passed as a filesystem path to Flink's `--scoring-config` arg. The helper extracts it to a temp file in that case, falling through to the raw path for local filesystem resources.

**Atomic fatigue increment.**
`RecommendationStore.incrementFatigue()` computes `newCount = existing + 1` inside the `synchronized` block, eliminating the read-increment-write race that appeared when multiple async writes ran close together.

---

## Test Scenarios

| # | Scenario | User | Assertion |
|---|----------|------|-----------|
| 1 | Healthy high-income user | u001 | `health_tier=HEALTHY`, non-blank product, `final_score > 0` |
| 2 | STRESSED user — no credit products | u003 | product NOT in {PERSONAL_LOAN, UNSECURED_CARD, SECURED_CARD, CC_BILL_PAYMENT} |
| 3 | UPI dormant user — UPI_ACTIVATION on all 5 surfaces | u002 | UPI_ACTIVATION returned for every surface (serve-time arbitration) |
| 4 | High-balance, no FD → FIXED_DEPOSIT in candidates | u006 | FIXED_DEPOSIT present in Flink output candidates (via snapshot) |
| 5 | Fatigue accumulates over 4 calls | u010 | stored shown_count per product matches number of API calls that returned it |
| 6 | Surface differentiation | u001 | both POST_UPI and HOME_BANNER return valid products with positive scores |
| 7 | Unknown user → 404 | u_nonexistent | HTTP 404, `error=NO_RECOMMENDATION` |
| 8 | Invalid surface → 400 | u001 | HTTP 400, `error=INVALID_SURFACE` |

---

## Bugs Fixed During Phase 5

### 1. Serve-time UPI arbitration missing (scenario3)
**Problem:** UPI_ACTIVATION was winning on HOME_BANNER and HOME_BOTTOMSHEET (high affinity 1.0) but losing to PERSONAL_LOAN on CASHBACK_REDEEMED (affinity 0.5 vs PERSONAL_LOAN 0.7).

**Fix:** Added explicit UPI_ACTIVATION arbitration in `SurfaceAffinityApplier.applyAndRank()`. If UPI_ACTIVATION is present in the candidate list and not converted (penalty < 999), it always wins — regardless of surface affinities. This mirrors the batch pipeline's "UPI_ACTIVATION always ranks #1 if eligible" rule.

### 2. `findUserInOutput("u006")` returning null (scenario4)
**Problem:** Async fatigue writes from scenario3 (5 surface calls on u002) were mid-write when scenario4 read the output file, causing partially written or reordered JSONL that the reader couldn't match.

**Fix:** Snapshot the complete Flink output into `INITIAL_FLINK_OUTPUT` (a `Map<String, JsonNode>`) immediately after the Flink job finishes, before Spring starts. Scenario4 reads from this immutable snapshot.

### 3. Fatigue race condition — count stuck at 2 (scenario5)
**Root cause 1 — non-atomic increment:** `RecommendationService` was computing `newCount = existing.getShownCount() + 1` from a locally-read fatigue snapshot, then passing it to the async `FatigueWriter`. Two async writes in flight simultaneously both read count=1 and both wrote count=2.

**Fix:** Moved the increment inside `LocalJsonRecommendationStore.incrementFatigue()` (synchronized), so the read-add-write is a single atomic operation.

**Root cause 2 — product switching:** With a non-atomic increment fixed, count still wouldn't reach 4 for a single product because fatigue penalty causes the top product to switch mid-test (e.g. PERSONAL_LOAN gets displaced after 2 shows). The test was asserting `shownCount >= 4` for the first call's product.

**Fix:** Rewrote the test to track which product each of the 4 calls returned, then verify `storedCount == apiCallCount` per product. Total stored impressions must equal 4. This correctly validates fatigue tracking without assuming a single product wins all calls.

### 4. Duplicate `ApiConfig` bean (discovered during integration)
**Problem:** `@Component` on `ApiConfig` combined with `@EnableConfigurationProperties(ApiConfig.class)` on the main class registered two beans. Unit tests passed (`@WebMvcTest` filters non-web beans) but `@SpringBootTest` failed with `UnsatisfiedDependencyException`.

**Fix:** Removed `@Component` from `ApiConfig`. `@EnableConfigurationProperties` alone is sufficient and is the recommended Spring Boot pattern.

---

## Test Coverage Summary

| Module | Tests | Status |
|--------|------:|-------|
| common | 16 | Passing |
| processor | 142 | Passing |
| serving-api (unit) | 16 | Passing |
| serving-api (E2E integration) | 8 | Passing |
| **Total** | **182** | **All green** |

---

## Running the Tests

```bash
# All modules
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test

# E2E only
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -pl serving-api -Dtest=EndToEndIntegrationTest
```

---

## Manual Validation

All commands run from the **project root** (`sm-recommendation/`).

```bash
# 1. Build
mvn clean package -DskipTests

# 2. Run Flink job — note: -Dinput/-Doutput/-DscoringConfig (not -Dexec.args)
mvn exec:exec -pl processor \
  -Dinput=processor/src/test/resources/sms_insights_sample.csv \
  -Doutput=processor/output/recommendations.json \
  -DscoringConfig=common/src/main/resources/scoring_rules.yaml

# 3. Inspect output — file is JSONL (one JSON object per line), not a JSON array
#    Pretty-print one user:
grep '"user_id":"u001"' processor/output/recommendations.json | python3 -m json.tool

#    Pretty-print all users:
while IFS= read -r line; do echo "$line" | python3 -m json.tool; echo "---"; done \
  < processor/output/recommendations.json

# 4. Start API (in a separate terminal — stays in foreground)
#    Working dir is set to serving-api/ by spring-boot:run; paths in application.yaml use ../
mvn spring-boot:run -pl serving-api

# If port 8080 is already in use: lsof -ti :8080 | xargs kill -9

# 5. Probe all surfaces for u001
for surface in HOME_BANNER HOME_BOTTOMSHEET POST_UPI CASHBACK_REDEEMED REWARDS_HISTORY; do
  echo "=== $surface ==="
  curl -s "http://localhost:8080/recommendation?user_id=u001&surface=$surface" | python3 -m json.tool
done

# 6. Stressed user — should never return a credit product
curl -s "http://localhost:8080/recommendation?user_id=u003&surface=HOME_BOTTOMSHEET" | python3 -m json.tool

# 7. UPI dormant user — should return UPI_ACTIVATION on every surface
curl -s "http://localhost:8080/recommendation?user_id=u002&surface=CASHBACK_REDEEMED" | python3 -m json.tool

# 8. Trigger fatigue — watch product and score change over 4 calls
for i in 1 2 3 4; do
  echo "=== Call $i ==="
  curl -s "http://localhost:8080/recommendation?user_id=u010&surface=HOME_BOTTOMSHEET" | python3 -m json.tool
  sleep 1
done

# 9. Verify fatigue written back to the JSON file
grep '"user_id":"u010"' processor/output/recommendations.json | python3 -m json.tool

# 10. Error cases
curl -s "http://localhost:8080/recommendation?user_id=nobody&surface=HOME_BOTTOMSHEET"
# → {"error":"NO_RECOMMENDATION","user_id":"nobody","surface":"HOME_BOTTOMSHEET"}

curl -s "http://localhost:8080/recommendation?user_id=u001&surface=INVALID"
# → {"error":"INVALID_SURFACE","surface":"INVALID"}
```

---

## Definition of Done

- [x] All 8 integration scenarios pass (`mvn test -pl serving-api`)
- [x] Flink job produces valid JSON for all users in sample CSV
- [x] API returns correct top-1 product per surface for all test users
- [x] Fatigue increments atomically and correctly in JSON file after each API call
- [x] Surface differentiation verified (surface affinity multiplier applied at serve time)
- [x] UPI_ACTIVATION arbitration enforced at serve time (not just in batch)
- [x] `mvn test` (all modules) passes — 182 tests green

---

## After Phase 5 — Cloud Migration Checklist (V1 prep)

These are NOT in scope for V0 but are the logical next steps:

- [ ] Replace `LocalJsonRecommendationStore` with `BigtableRecommendationStore`
- [ ] Replace CSV source with BigQuery incremental read (Flink connector)
- [ ] Set up Bigtable emulator for local cloud-like testing
- [ ] Deploy Flink job to managed Flink on GKE
- [ ] Deploy Spring Boot API to GKE
- [ ] Wire service account credentials for BigQuery + Bigtable access
- [ ] Set up daily Flink job schedule (Kubernetes CronJob)
- [ ] Add Cloud Monitoring metrics: batch run duration, reco coverage rate, API latency P99
