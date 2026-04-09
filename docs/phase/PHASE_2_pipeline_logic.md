# Phase 2 — Pipeline Logic (Plain Java, No Flink)

## Goal
Implement the full 6-stage recommendation pipeline as plain Java classes with zero framework dependency.
Every stage is independently unit-testable. This is where the algorithm is validated before any infra is introduced.

---

## Principle
Each pipeline stage is a **pure function**: takes input, returns output, no side effects.
The `RecommendationPipeline` orchestrator chains them. Flink (Phase 3) simply calls this orchestrator.

---

## Module Structure
```
processor/
└── src/
    ├── main/java/supermoney/recommendation/processor/
    │   ├── pipeline/
    │   │   ├── FeatureDeriver.java          ← Stage 0: derive 5 secondary features
    │   │   ├── HealthGate.java              ← Stage 2: compute tier, block STRESSED credit
    │   │   ├── EligibilityFilter.java       ← Stage 3: per-product hard pass/fail rules
    │   │   ├── PropensityScorer.java        ← Stage 4: weighted normalized score per product
    │   │   ├── HealthAdjuster.java          ← Stage 5: apply health adjustment to propensity
    │   │   ├── TriggerBoostEvaluator.java   ← Stage 6: batch-approximated trigger boosts
    │   │   ├── FatigueEvaluator.java        ← Stage 7: frequency-capped fatigue penalty
    │   │   ├── Ranker.java                  ← Stage 8: final formula + arbitration rules
    │   │   └── RecommendationPipeline.java  ← Orchestrator: chains all stages
    │   └── scorer/                          ← one scorer class per product
    │       ├── UpiActivationScorer.java
    │       ├── PersonalLoanScorer.java
    │       ├── UnsecuredCardScorer.java
    │       ├── SecuredCardScorer.java
    │       ├── CcBillPaymentScorer.java
    │       ├── RentPaymentScorer.java
    │       ├── BillPaymentsScorer.java
    │       ├── RechargesScorer.java
    │       ├── ReferralsScorer.java
    │       ├── FixedDepositScorer.java
    │       └── FlightsScorer.java
    └── test/java/supermoney/recommendation/processor/
        ├── pipeline/
        │   ├── FeatureDeriverTest.java
        │   ├── HealthGateTest.java
        │   ├── EligibilityFilterTest.java
        │   ├── PropensityScorerTest.java
        │   ├── RankerTest.java
        │   └── RecommendationPipelineTest.java   ← end-to-end integration test
        └── scorer/
            ├── PersonalLoanScorerTest.java
            └── (one test per scorer)
```

---

## Stage-by-Stage Specification

### Stage 0 — FeatureDeriver
**Input:** `UserFeatures` (raw SMS insights fields)
**Output:** same `UserFeatures` with derived fields populated
**Computes:**
```java
// 1. Financial Health Score
double fisBase       = fis_affordability_v1 * 60;
double loanPen       = Math.min(cnt_delinquncy_loan_c90, 3) / 3.0 * 15;
double ccPen         = Math.min(cnt_delinquncy_cc_c90, 3) / 3.0 * 10;
double bouncePen     = (bounce_flag == 1 ? 10 : 0) + (auto_debit_bounce_m0 > 0 ? 5 : 0);
double dti           = total_emi_loan_all_acc_m0 / Math.max(total_income_m0, 1.0);
double dtiPen        = clamp((dti - 0.30) * 50, 0, 30);
double healthScore   = clamp(fisBase - loanPen - ccPen - bouncePen - dtiPen, 0, 100);
// → HEALTHY ≥ 70, NEUTRAL ≥ 40, STRESSED < 40

// 2. Surplus Cash
double surplusCash   = total_income_m0 - total_emi_loan_all_acc_m0 - obligations;

// 3. DTI Ratio
double dtiRatio      = total_emi_loan_all_acc_m0 / Math.max(total_income_m0, 1.0);

// 4. Avg UPI Ticket
double avgUpiTicket  = amt_total_txn_upi_3m / Math.max(cnt_total_txn_upi_3m, 1.0);

// 5. Bill Urgency
double billUrgency   = Math.max(max_dpd_broadband, Math.max(max_dpd_dth,
                           Math.max(max_dpd_electric,
                               cnt_postpaid_bill_overdue_c180 > 0 ? 30.0 : 0.0)));
```

---

### Stage 2 — HealthGate
**Input:** `UserFeatures` (with healthScore derived), all `Product` candidates
**Output:** filtered list of `Product` — removes credit products for STRESSED users

```
STRESSED blocks: PERSONAL_LOAN, UNSECURED_CARD, SECURED_CARD, CC_BILL_PAYMENT
```

Also derives and sets `healthTier` on `UserFeatures`.

---

### Stage 3 — EligibilityFilter
**Input:** `UserFeatures`, list of candidate `Product`
**Output:** filtered list — only products passing ALL hard rules

See PRD Section 5 for exact per-product rules. Each product has its own `isEligible(UserFeatures)` method.
Also handles **held product exclusion** (inferred from SMS insights — Section 5.2 in PRD).

---

### Stage 4 — PropensityScorer
**Input:** `UserFeatures`, eligible `Product` list, `ScoringConfig`
**Output:** `Map<Product, Double>` — raw propensity score 0–100

```java
// For each product:
double weightedSum = 0, totalWeight = 0;
for (SignalConfig signal : productConfig.getSignals()) {
    double raw = resolveSignal(features, signal);
    double normalized = normalize(raw, signal.getMin(), signal.getMax());
    weightedSum += normalized * signal.getWeight();
    totalWeight += signal.getWeight();
}
double propensity = (weightedSum / totalWeight) * 100;
```

**normalize:** `clamp((x - min) / (max - min), 0.0, 1.0)`
**Binary signals:** resolved as `1.0` (true) or `0.0` (false)
**Inverted normalize:** `1.0 - normalize(x, min, max)` (used for recency signals)

---

### Stage 5 — HealthAdjuster
**Input:** `Map<Product, Double>` propensity scores, `HealthTier`
**Output:** `Map<Product, Double>` adjusted propensity scores

Applies health adjustment from config (e.g., NEUTRAL → -10 for Personal Loan, +5 for FD).

---

### Stage 6 — TriggerBoostEvaluator
**Input:** `UserFeatures`, eligible products, batch run date
**Output:** `Map<Product, Double>` trigger boost per product

```
salary within 3 days  → FD, Rent, CC Bill +15
avg_bal < 5000        → Personal Loan +12
bill_urgency >= 30    → Bill Payments +20
cc_bill due >= 23d    → CC Bill Payment +20
loan/cc application   → Personal Loan, Unsecured Card +10
cc bill → loan cross  → Personal Loan +15
```

---

### Stage 7 — FatigueEvaluator
**Input:** `Map<Product, FatigueData>` (shown_count + shown_at per product), `ScoringConfig`
**Output:** `Map<Product, Double>` fatigue penalty per product

```
shown_count = 0         → 0
shown_count = 1         → 10
shown_count = 2         → 25
shown_count >= max_cap  → 70
converted (inferred)    → 999
+ shown_at < 24h ago    → +20 additional
```

For **local V0:** `FatigueData` loaded from `fatigue` section of local JSON file.
No Bigtable call in Phase 2 — `FatigueData` passed as a parameter (injected by orchestrator).

---

### Stage 8 — Ranker
**Input:** adjusted propensities, trigger boosts, fatigue penalties, `ScoringConfig`
**Output:** `List<ScoredCandidate>` sorted by `preSurfaceScore` DESC (top-5)

**Final score formula:**
```
final_score[p] =
    (adjusted_propensity[p]   × 0.50)
  + (business_priority[p]×100 × 0.25)
  - (fatigue_penalty[p]       × 0.15)
  + (trigger_boost[p]         × 0.10)
```

**Arbitration rules (applied after sorting):**
1. UPI Activation → rank 1 if eligible (override all scores)
2. Max 1 credit product in top-5 (drop lower-scoring credit products)
3. FD preferred over Flights if scores within 5 points
4. Tie-break: prefer lower `business_priority` product

---

### RecommendationPipeline (Orchestrator)
```java
public List<ScoredCandidate> run(UserFeatures features, Map<Product, FatigueData> fatigueData) {
    features = featureDeriver.derive(features);
    List<Product> candidates = Arrays.asList(Product.values());
    candidates = healthGate.filter(features, candidates);
    candidates = eligibilityFilter.filter(features, candidates);
    Map<Product, Double> propensity = propensityScorer.score(features, candidates);
    Map<Product, Double> adjusted = healthAdjuster.adjust(propensity, features.getHealthTier());
    Map<Product, Double> boosts = triggerBoostEvaluator.evaluate(features, candidates);
    Map<Product, Double> fatigue = fatigueEvaluator.evaluate(fatigueData, candidates);
    return ranker.rank(adjusted, boosts, fatigue);
}
```

---

## Test Strategy

### Unit Tests Per Stage
Each stage gets its own test class with:
- Happy path: expected output for a "standard" user
- Edge cases: STRESSED user, zero income, all bills present, etc.
- Boundary values: health_score exactly 40 (NEUTRAL/STRESSED boundary), exactly 70

### Key Test Cases

**HealthGateTest:**
- STRESSED user: PERSONAL_LOAN, UNSECURED_CARD, SECURED_CARD, CC_BILL_PAYMENT removed
- NEUTRAL user: all products pass gate
- HEALTHY user: all products pass gate

**EligibilityFilterTest:**
- Personal Loan: income < 20000 → excluded
- Personal Loan: delinquency > 0 → excluded
- FD: fd_flag = 1 → excluded (already has FD)
- UPI Activation: upi_user_flag = 1 AND upi_recency <= 30 → excluded (already active)

**RankerTest:**
- UPI Activation always ranks #1 when eligible
- Only 1 credit product in output when multiple credit products eligible
- FD beats Flights when scores within 5 points

**RecommendationPipelineTest (end-to-end):**
- 5 hand-crafted `UserFeatures` objects covering:
  1. Healthy, high income, existing UPI → should recommend Personal Loan or Card
  2. Stressed user → only non-credit products eligible
  3. User with bills overdue → Bill Payments ranks high
  4. User with cc_bill due → CC Bill Payment ranks high
  5. User with no UPI + bank account → UPI Activation ranks #1

---

## Definition of Done

**Status: Complete**

- [x] All 8 pipeline stages implemented as pure Java classes (FeatureDeriver, HealthGate, EligibilityFilter, PropensityScorer, HealthAdjuster, TriggerBoostEvaluator, FatigueEvaluator, Ranker)
- [x] Propensity scoring driven by YAML signals via `FeatureResolver` — no per-product scorer subclasses needed
- [x] `RecommendationPipeline` orchestrator chains all stages
- [x] Unit tests for all stages pass
- [x] End-to-end `RecommendationPipelineTest` with 5 user scenarios passes
- [x] `mvn test -pl processor` green — 131 tests (pipeline stages)
- [x] No Flink, Spring Boot, or cloud SDK imports in pipeline code
