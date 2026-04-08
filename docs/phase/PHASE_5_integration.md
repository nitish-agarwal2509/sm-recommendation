# Phase 5 — End-to-End Integration

## Goal
Run the full pipeline end-to-end locally: CSV → Flink job → JSON → Spring Boot API → HTTP response.
Validate that all components work together correctly before any cloud migration.

---

## End-to-End Flow
```
sms_insights_sample.csv
        │
        │  mvn exec:exec -pl processor (Flink batch job)
        ▼
processor/output/recommendations.json
        │
        │  mvn spring-boot:run -pl serving-api
        ▼
Spring Boot API (localhost:8080)
        │
        │  curl GET /recommendation?user_id=X&surface=Y
        ▼
JSON response (1 recommendation)
        │
        │  curl GET /recommendation (same user, same surface, again)
        ▼
JSON response (fatigue incremented — may differ or score lower)
```

---

## Integration Test Scenarios

### Scenario 1 — Healthy User, High Income (Personal Loan candidate)
**Setup:** User with HEALTHY tier, income 80k, no delinquency, no existing loan
**Run Flink → API**
**Assert:**
- Flink output: PERSONAL_LOAN in top-5 candidates
- API (HOME_BOTTOMSHEET): returns PERSONAL_LOAN or UNSECURED_CARD
- `health_tier` = HEALTHY in response
- `computed_at` is set

### Scenario 2 — Stressed User (credit products blocked)
**Setup:** User with health_score < 40 (STRESSED)
**Assert:**
- Flink output: candidates contain NO credit products (PERSONAL_LOAN, UNSECURED_CARD, SECURED_CARD, CC_BILL absent)
- API: returns non-credit product (BILL_PAYMENTS, REFERRALS, FD, etc.)

### Scenario 3 — UPI Activation (UPI dormant)
**Setup:** User with upi_user_flag=0, valid bank account
**Assert:**
- Flink output: UPI_ACTIVATION is rank #1 candidate (arbitration rule)
- API (all surfaces): returns UPI_ACTIVATION regardless of surface

### Scenario 4 — Bill Payments (overdue bills)
**Setup:** User with broadband_flag=1, electricity_flag=1, bill_urgency=45
**Assert:**
- Flink output: BILL_PAYMENTS in top-3 candidates
- Trigger boost applied (+20 for bill_urgency >= 30 visible in pre_surface_score)

### Scenario 5 — Fatigue Accumulation
**Setup:** User with a known top recommendation
**Steps:**
1. Call `GET /recommendation?user_id=X&surface=HOME_BOTTOMSHEET` → note returned product P
2. Repeat the call 3 more times (total 4 calls)
3. Call again (5th call)
**Assert:**
- shown_count[P] increments on each call (check JSON file directly)
- By 4th call: fatigue penalty increasing (score dropping)
- Product P should still rank #1 for first few calls (fatigue penalty ≠ hard block)

### Scenario 6 — Surface Differentiation
**Setup:** Any user with multiple eligible products
**Assert:**
- `GET .../surface=POST_UPI` may return different product than `GET .../surface=HOME_BANNER`
- Specifically: UNSECURED_CARD (0.9 affinity) should score higher on POST_UPI than FLIGHTS (0.3 affinity)

### Scenario 7 — Unknown User
**Assert:**
- `GET /recommendation?user_id=nonexistent&surface=HOME_BOTTOMSHEET` → HTTP 404

---

## Integration Test Automation

### Class: `EndToEndIntegrationTest.java`
Location: `serving-api/src/test/java/.../EndToEndIntegrationTest.java`

This test:
1. Runs `SmRecommendationJob` programmatically (in-process, no subprocess)
2. Starts a `SpringBootTest` with a test `application.yaml` pointing to the test output JSON
3. Uses `MockMvc` or `TestRestTemplate` for HTTP calls
4. Asserts all 7 scenarios above

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "recommendation.store.local-json-path=target/test-recommendations.json"
})
public class EndToEndIntegrationTest {

    @BeforeAll
    static void runFlinkJob() throws Exception {
        SmRecommendationJob.main(new String[]{
            "--input", "src/test/resources/sms_insights_sample.csv",
            "--output", "target/test-recommendations.json",
            "--scoring-config", "../common/src/main/resources/scoring_rules.yaml"
        });
    }

    @Test
    void healthyUserGetsLoanOrCard() { ... }

    @Test
    void stressedUserGetsNoCreditProduct() { ... }

    // ...
}
```

---

## Manual Validation Checklist

Run these manually as a sanity check before declaring Phase 5 done:

```bash
# 1. Clean build
mvn clean package -DskipTests

# 2. Run Flink job
mvn exec:exec -pl processor \
  -Dexec.args="--input processor/src/test/resources/sms_insights_sample.csv \
               --output processor/output/recommendations.json \
               --scoring-config common/src/main/resources/scoring_rules.yaml"

# 3. Inspect output
cat processor/output/recommendations.json | python3 -m json.tool | head -80

# 4. Start API
mvn spring-boot:run -pl serving-api &

# 5. Probe all surfaces for a user
for surface in HOME_BANNER HOME_BOTTOMSHEET POST_UPI CASHBACK_REDEEMED REWARDS_HISTORY; do
  echo "=== $surface ==="
  curl -s "http://localhost:8080/recommendation?user_id=u001&surface=$surface" | python3 -m json.tool
done

# 6. Trigger fatigue
for i in 1 2 3 4 5; do
  echo "=== Call $i ==="
  curl -s "http://localhost:8080/recommendation?user_id=u001&surface=HOME_BOTTOMSHEET" | python3 -m json.tool
done

# 7. Inspect fatigue written to JSON
grep "u001" processor/output/recommendations.json | python3 -m json.tool
```

---

## Performance Check
```bash
# Simple latency check (not load test — just P99 sanity)
for i in $(seq 1 50); do
  curl -o /dev/null -s -w "%{time_total}\n" \
    "http://localhost:8080/recommendation?user_id=u001&surface=HOME_BOTTOMSHEET"
done | sort -n | tail -5
# All values should be < 0.050 (50ms)
```

---

## Definition of Done
- [ ] All 7 integration scenarios pass
- [ ] Flink job produces valid JSON for all users in sample CSV
- [ ] API returns correct top-1 product per surface for all test users
- [ ] Fatigue increments correctly in JSON file after each API call
- [ ] Surface differentiation verified (different surfaces → potentially different product)
- [ ] P99 latency < 50ms on local machine
- [ ] `mvn verify` (all modules, all tests) passes clean
- [ ] Manual checklist above runs without errors

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
