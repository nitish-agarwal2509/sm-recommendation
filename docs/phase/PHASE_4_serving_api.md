# Phase 4 — Spring Boot Serving API

## Goal
Thin read layer: load the `recommendations.json` produced by Flink, apply surface affinity at request time, return the top-1 product for the requested surface, and write fatigue (impression tracking) back to the JSON file.

Target: **< 50ms P99 latency** (file read is in-memory cache; no disk I/O per request).

---

## Module Structure
```
serving-api/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/supermoney/recommendation/api/
    │   │   ├── SmRecommendationApiApplication.java   ← Spring Boot main
    │   │   ├── controller/
    │   │   │   └── RecommendationController.java     ← GET /recommendation
    │   │   ├── service/
    │   │   │   ├── RecommendationService.java        ← orchestrates read + surface affinity + fatigue write
    │   │   │   └── SurfaceAffinityApplier.java       ← applies surface multipliers, returns top-1
    │   │   ├── store/
    │   │   │   ├── RecommendationStore.java          ← interface (local JSON or Bigtable)
    │   │   │   └── LocalJsonRecommendationStore.java ← V0 local implementation
    │   │   ├── fatigue/
    │   │   │   └── FatigueWriter.java                ← writes shown_count + shown_at on impression
    │   │   └── config/
    │   │       ├── ApiConfig.java                    ← @ConfigurationProperties for application.yaml
    │   │       └── ScoringConfigLoader.java          ← loads scoring_rules.yaml (surface affinity)
    │   └── resources/
    │       └── application.yaml
    └── test/
        └── java/supermoney/recommendation/api/
            ├── controller/
            │   └── RecommendationControllerTest.java ← MockMvc unit tests
            └── service/
                ├── RecommendationServiceTest.java
                └── SurfaceAffinityApplierTest.java
```

---

## API Contract

### GET /recommendation
```
Query params:
  user_id  (required) — e.g. "u123"
  surface  (required) — one of: HOME_BANNER, HOME_BOTTOMSHEET, POST_UPI, CASHBACK_REDEEMED, REWARDS_HISTORY

Response 200:
{
  "user_id": "u123",
  "surface": "HOME_BOTTOMSHEET",
  "recommendation": {
    "product": "PERSONAL_LOAN",
    "final_score": 83.2,
    "health_tier": "NEUTRAL",
    "reason_tokens": ["income_signal", "clean_history", "credit_seeking_intent"],
    "creative_variant": "loan_v1",
    "computed_at": "2026-04-08T02:00:00Z"
  }
}

Response 404:
{
  "user_id": "u999",
  "surface": "HOME_BOTTOMSHEET",
  "error": "NO_RECOMMENDATION",
  "message": "No eligible recommendation found for this user"
}

Response 400:
{
  "error": "INVALID_SURFACE",
  "message": "Unknown surface: INVALID_SURFACE"
}
```

---

## Request Processing Flow

```
GET /recommendation?user_id=u123&surface=HOME_BOTTOMSHEET
        │
        ▼
RecommendationController
        │
        ▼
RecommendationService.getRecommendation(userId, surface)
        │
        ├─ 1. Load user's record from RecommendationStore (in-memory cache)
        │       → { candidates[], health_tier, computed_at, fatigue{} }
        │
        ├─ 2. For each candidate, apply fatigue penalty (using stored fatigue data):
        │       fatigue_penalty = FatigueEvaluator.evaluate(fatigueData, product)
        │       (same Phase 2 FatigueEvaluator — reused here)
        │
        ├─ 3. Apply surface affinity multiplier:
        │       surface_adjusted_score = (pre_surface_score - fatigue_penalty×0.15) × surface_affinity[surface][product]
        │
        ├─ 4. Sort by surface_adjusted_score DESC → take top-1
        │
        ├─ 5. Write fatigue (async, non-blocking):
        │       shown_count[product] += 1
        │       shown_at[product] = now()
        │
        └─ 6. Return Recommendation response
```

**Note on fatigue at serve time vs. batch time:**
- Batch job (Phase 3) already factored in fatigue when computing `pre_surface_score`.
- Serve time re-applies fatigue penalty on top (using current shown_count/shown_at) to catch impressions since the last batch run.
- This means fatigue is applied **twice** intentionally: once at batch, once at serve. This is correct — the serve-time penalty reflects actual impression history since the batch ran.

---

## Key Classes

### RecommendationStore (interface)
```java
public interface RecommendationStore {
    Optional<UserRecoRecord> findByUserId(String userId);
    void updateFatigue(String userId, Product product, int shownCount, Instant shownAt);
}
```

### LocalJsonRecommendationStore
- Loads `recommendations.json` into a `ConcurrentHashMap<String, UserRecoRecord>` on startup (`@PostConstruct`)
- `findByUserId` → O(1) map lookup
- `updateFatigue` → updates in-memory map + writes back to JSON file (async, debounced)
- File path configured via `application.yaml`

```yaml
# application.yaml
recommendation:
  store:
    local-json-path: processor/output/recommendations.json
  scoring-config-path: common/src/main/resources/scoring_rules.yaml
```

### SurfaceAffinityApplier
- Loaded from `scoring_rules.yaml` surface affinity matrix
- `applyAndRank(List<ScoredCandidate> candidates, Surface surface, Map<Product, Double> fatiguePenalties)`
  - Returns `Optional<ScoredCandidate>` — top-1 for this surface

### Surface Affinity Matrix (from PRD Section 8)
```
              S1:HomeBanner  S2:HomeBottomsheet  S3:PostUPI  S4:Cashback  S5:Rewards
UPI_ACT           1.0             1.0              0.5          0.5          0.5
PERSONAL_LOAN     0.8             1.0              0.4          0.7          0.6
UNSECURED_CARD    0.9             1.0              0.9          1.0          1.0
SECURED_CARD      0.8             1.0              0.8          0.9          0.9
CC_BILL           0.9             1.0              0.5          0.6          0.7
RENT              0.9             1.0              0.5          0.7          0.5
BILL_PAYMENTS     0.8             0.9              0.7          0.6          0.6
RECHARGES         0.7             0.8              0.8          0.5          0.5
REFERRALS         0.6             0.8              0.8          0.9          0.9
FD                0.8             0.9              0.7          0.9          0.8
FLIGHTS           0.5             0.7              0.3          0.8          0.8
```

### FatigueWriter
- Called after every successful `GET /recommendation` response
- Async: uses `@Async` Spring annotation (non-blocking, doesn't add to P99 latency)
- Writes to `LocalJsonRecommendationStore.updateFatigue()`

---

## Creative Variant Mapping (V0 static)
```yaml
# application.yaml
creative-variants:
  PERSONAL_LOAN: "loan_v1"
  UNSECURED_CARD: "card_unsecured_v1"
  SECURED_CARD: "card_secured_v1"
  FIXED_DEPOSIT: "fd_v1"
  UPI_ACTIVATION: "upi_v1"
  CC_BILL_PAYMENT: "cc_bill_v1"
  RENT_PAYMENT: "rent_v1"
  BILL_PAYMENTS: "bill_v1"
  RECHARGES: "recharge_v1"
  REFERRALS: "referral_v1"
  FLIGHTS: "flights_v1"
```

---

## Dependencies (serving-api/pom.xml)
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
    <groupId>com.supermoney.recommendation</groupId>
    <artifactId>common</artifactId>
    <version>${project.version}</version>
</dependency>
<!-- Jackson YAML for scoring_rules.yaml -->
<dependency>
    <groupId>com.fasterxml.jackson.dataformat</groupId>
    <artifactId>jackson-dataformat-yaml</artifactId>
</dependency>
```

---

## Running Locally
```bash
# From root, start the API (reads processor/output/recommendations.json)
mvn spring-boot:run -pl serving-api

# Test
curl "http://localhost:8080/recommendation?user_id=u123&surface=HOME_BOTTOMSHEET"
```

---

## Tests

### RecommendationControllerTest.java (MockMvc)
- `GET /recommendation?user_id=u123&surface=HOME_BOTTOMSHEET` → 200, valid JSON
- `GET /recommendation?user_id=unknown&surface=HOME_BOTTOMSHEET` → 404
- `GET /recommendation?user_id=u123&surface=INVALID` → 400
- Verify fatigue write called after 200 response
- Verify response shape matches API contract

### SurfaceAffinityApplierTest.java
- UNSECURED_CARD ranks higher on POST_UPI than HOME_BANNER (per affinity matrix)
- FD preferred over FLIGHTS when scores within 5 points
- Empty candidate list returns `Optional.empty()`

### RecommendationServiceTest.java
- Uses a mock `RecommendationStore` returning pre-built candidates
- Verifies full flow: load → fatigue apply → surface affinity → top-1 returned

---

## Definition of Done
- [ ] `GET /recommendation` returns correct top-1 for all 5 surfaces
- [ ] Surface affinity multipliers applied at serve time (not stored)
- [ ] Fatigue written on every successful response (shown_count + shown_at)
- [ ] `LocalJsonRecommendationStore` loads JSON on startup (in-memory cache)
- [ ] 404 for unknown user, 400 for invalid surface
- [ ] MockMvc tests pass
- [ ] `mvn test -pl serving-api` green
- [ ] Manual end-to-end: `curl` returns valid JSON response < 50ms
