# Recommendation Engine — Implementation Context

> **Purpose**: This file captures the full architectural context from the TDD for the super.money Recommendation Engine. Feed this to Claude Code when starting implementation in `/Users/nitish.agarwal5/claude-project/sm-recommendation`.

---

## 1. What We're Building

A contextual Recommendation Engine for super.money (a UPI-heavy fintech app like PhonePe/GPay/CRED). The engine recommends the next-best financial product to each user across 5 product lines: **FD, Personal Loan, Credit Card, UPI Activation, Referral**.

Core philosophy: **"Health First"** — a Financial Health Score gates credit products. Stressed users are suppressed from Loans/Cards before scoring even runs.

The system evolves: **V0 (rules) → V1 (ML) → V2 (contextual bandits + real-time)**.

**V0 scope (what we're implementing now):**
- Rule-based scoring engine (config-driven, YAML/JSON)
- Health Policy Gate (financial_health_score → health_tier → product filtering)
- 5 product scorers (FD, Loan, Card, UPI, Referral)
- Ranking & arbitration with fatigue management
- Serving API (thin, reads pre-computed recommendations)
- 3 surfaces: Post-UPI Transaction, App Home Feed, Salary Credit Push
- Batch Flink processor (reads from offline Feature Store)

---

## 2. Architecture — Four Layers

```
┌─────────────────────────────────────────────────────┐
│ Layer 1: FEATURE STORE (existing, extend)           │
│ All data sources → Flink → Offline Feature Store    │
│ Computes: financial_health_score, health_tier,      │
│ derived features (disposable_income_ratio, etc.)    │
└──────────────────┬──────────────────────────────────┘
                   │ batch read
┌──────────────────▼──────────────────────────────────┐
│ Layer 2: RECOMMENDATION PROCESSOR (new Flink job)   │
│ 6-stage pipeline per user:                          │
│ 1. Candidate Generation (all products - held)       │
│ 2. Health Policy Gate (filter by health_tier)       │
│ 3. Eligibility Filtering (binary pass/fail)         │
│ 4. Propensity Scoring (0-100, weighted rules)       │
│ 5. Ranking & Arbitration (multi-factor formula)     │
│ 6. Write top 5 recos → Recommendation Store        │
└──────────────────┬──────────────────────────────────┘
                   │ pre-computed recos
┌──────────────────▼──────────────────────────────────┐
│ Layer 3: SERVING API (new microservice)             │
│ Thin, low-latency read layer                        │
│ - Surface filtering (affinity matrix)               │
│ - Fatigue enforcement                               │
│ - A/B experiment assignment                         │
│ - Returns ranked list per surface                   │
└──────────────────┬──────────────────────────────────┘
                   │ top recommendation
┌──────────────────▼──────────────────────────────────┐
│ Layer 4: JOURNEY SYSTEM (existing, integrate)       │
│ Delivery only — does NOT make reco decisions        │
│ Multi-touch delivery, channel fatigue, tracking     │
└─────────────────────────────────────────────────────┘
```

**V0 Data Flow:**
All Data Sources → Feature Store Flink → Offline Feature Store → Recommendation Flink (batch read) → Recommendation Store → Serving API → Client/Journey System

---

## 3. Data Sources (V0)

| Source | Ingestion | Key Signals |
|--------|-----------|-------------|
| SMS Insights (Finbox) | Finbox → BigQuery → Feature Store Flink | Income, balances, loans, CC, investments, delinquencies (~600 attributes) |
| Internal Transactions & Product Data | Internal DW → Feature Store Flink | Product holdings, UPI txn history, KYC status, card data |
| Journey System State | Journey DB → Feature Store Flink | Past recos shown, delivery history, user response |

**NOT in V0:** Clickhouse app activity events (V1+).

---

## 4. Derived Features (computed in Feature Store)

| Feature | Logic | Purpose |
|---------|-------|---------|
| `disposable_income_ratio` | (income - obligations - avg_debits) / income | Financial headroom |
| `income_stability_score` | CV of salary across 3 months | Income confidence |
| `credit_seeking_intensity` | lending_apps_c30 + loan_apps_c30 + cc_apps_c30 | Credit demand signal |
| `debt_burden_ratio` | total_EMI / income | Debt service capacity |
| `surplus_cash_indicator` | avg_balance - obligations - (avg_debits × 1.2) | FD/investment trigger |
| `financial_stress_score` | Composite of bounces, delinquencies, declining balances | Risk gating |
| `salary_timing_flag` | Days since last salary credit | Timing optimization |
| `financial_health_score` | Composite: balance trend + bounce count 90d + delinquency count + debt_burden_ratio + income stability | **Policy gate for product eligibility** |
| `health_tier` | Bucketed from financial_health_score: Healthy / Neutral / Stressed | **Direct input to Health Policy Gate** |

---

## 5. Health Policy Gate

Runs as **Stage 2** in the pipeline — BEFORE scoring, not after ranking.

| Health Tier | Credit Products (Loan, Card) | Savings Products (FD) | Neutral Products (UPI, Referral) |
|-------------|------------------------------|-----------------------|----------------------------------|
| **Healthy** | Eligible (full scoring) | Eligible | Eligible |
| **Neutral** | Eligible with penalty (-10 to score) | Eligible with boost (+15) | Eligible |
| **Stressed** | **EXCLUDED before scoring** | Eligible with boost (+15) | Eligible |

---

## 6. Product Scoring Rules (V0)

All rules are config-driven (YAML/JSON). Each rule = `(feature, threshold, score)` tuple. Scores are additive (weighted sum → 0-100).

### 6.1 Fixed Deposit (FD)
**Eligibility:** No existing FD, income ≥15K, no fraud, data sufficient. **Threshold:** ≥40.

| Signal | Weight | Logic |
|--------|--------|-------|
| avg_balance_c30 | 25 | ≥1L: 25, ≥50K: 20, ≥25K: 10 |
| surplus_cash_indicator | 25 | ≥50K: 25, ≥20K: 15, else: 5 |
| income_stability_score | 15 | CV<0.1: 15, CV<0.2: 10, else: 5 |
| no_delinquencies_90d | 10 | Zero: 10, else: 0 |
| salary_timing | 10 | Within 3d: 10, 7d: 5 |
| investment_propensity | 10 | fps_investment>0.7: 10, >0.5: 5 |
| no_bounces | 5 | All zero: 5, else: 0 |

### 6.2 Personal Loan
**Eligibility:** Income ≥20K, affordability above threshold, debt_burden <0.5, no delinquency c30, no fraud. **Threshold:** ≥50. **Health gate: Healthy only.**

| Signal | Weight | Logic |
|--------|--------|-------|
| credit_seeking_intensity | 30 | lending_apps≥3: 30, ≥1: 20, loan_apps_c60>0: 10 |
| income_level | 20 | ≥50K: 20, ≥30K: 15, ≥20K: 10 |
| clean_repayment_90d | 15 | Zero delinquency + no bounces: 15 |
| disposable_income_ratio | 15 | >0.4: 15, >0.25: 10, else: 5 |
| prior_loan_experience | 10 | Closed loans >0: 10 |
| balance_stability | 10 | min_bal_c30 > 5K: 10 |

### 6.3 Credit Card
**Eligibility:** No super.money card, income ≥25K, no fraud, DPD<30. **Threshold:** ≥45. **Health gate: Healthy or Neutral.**

| Signal | Weight | Logic |
|--------|--------|-------|
| no_cc_good_income | 25 | cc_user=false AND income≥30K: 25, else: 10 |
| high_cc_utilization | 25 | >80%: 25, >60%: 15 |
| cc_applications | 20 | cc_apps_c30>0: 20, c60>0: 10 |
| digital_savviness | 15 | high: 15, medium: 10, else: 5 |
| income_level | 15 | ≥50K: 15, ≥35K: 10, ≥25K: 5 |

### 6.4 UPI Activation
**No health gate.** Available to all tiers.

| Signal | Weight | Logic |
|--------|--------|-------|
| existing_upi_user | 30 | upi_flag=true + high volume: 30, moderate: 20, new: 10 |
| digital_payment_activity | 25 | ewallets≥2: 25, ≥1: 15, else: 5 |
| transaction_frequency | 20 | debit_txn_c30>20: 20, >10: 15, else: 5 |
| competitor_platform | 15 | phonepe/paytm=true: 15, else: 5 |
| app_engagement | 10 | Reserved for V1 Clickhouse signals |

### 6.5 Referral
**No health gate.** Available to all tiers.

| Signal | Weight | Logic |
|--------|--------|-------|
| digital_savviness | 25 | high: 25, medium: 15, else: 5 |
| social_communication_apps | 25 | count>10: 25, >5: 15, else: 5 |
| active_supermoney_user | 20 | txn_c30>5: 20, >2: 10 |
| multi_product_holder | 15 | ≥2 products: 15, 1: 5 |
| p2p_activity | 15 | High UPI P2P debits: 15 |

---

## 7. Ranking Formula

```
final_score = ((propensity + health_adjustment) × 0.50)
            + (business_priority × 0.25 × 100)
            - (fatigue_penalty × 0.15)
            + (trigger_boost × 0.10)

then × surface_affinity_multiplier (at serving time)
```

**health_adjustment:** +15 savings if Neutral, -10 credit if Neutral, 0 if Healthy. Stressed credit already removed.

**Business Priority:**
| Product | Priority |
|---------|----------|
| Card | 0.9 |
| FD | 0.8 |
| Loan | 0.7 |
| UPI | 0.5 |
| Referral | 0.4 |

**Fatigue Rules:**
| Rule | Constraint | Effect |
|------|-----------|--------|
| Same product cooldown | Shown in last 48h | +30 penalty |
| Dismissed cooldown | User dismissed | +50 penalty, 7-day cooldown |
| Daily limit | Max 3 recos/day | Block |
| Weekly limit | Max 10 recos/week | Block |
| Converted exclusion | Already converted | Remove permanently |

---

## 8. Surface-Product Affinity Matrix (V0)

Multipliers: BEST=1.0, GOOD=0.8, MEDIUM=0.5, LOW=0.1

| Surface | FD | Card | Loan | Referral |
|---------|-----|------|------|----------|
| Post-UPI Transaction | GOOD | BEST | LOW | GOOD |
| App Home Feed | BEST | BEST | GOOD | MEDIUM |
| Transaction History | GOOD | BEST | MEDIUM | LOW |
| Pre-Payment Screen | LOW | BEST | LOW | LOW |
| Salary Credit Push | BEST | GOOD | MEDIUM | LOW |
| Failed Txn / Low Bal | LOW | GOOD | BEST | LOW |
| Bill Payment Flow | LOW | BEST | LOW | LOW |

**V0 surfaces (implement these 3):** Post-UPI Transaction, App Home Feed, Salary Credit Push.

---

## 9. Serving API Spec

- Accepts: `user_id`, `surface`, `txn_context` (optional: amount, merchant_category, txn_type)
- Returns: Ranked list of recommendations (not single), each with: product, score, creative_variant, reason
- Logic: Read pre-computed recos → filter by surface affinity → apply fatigue → A/B assignment → return
- Target latency: <50ms P99 (it's a cache/store read)
- A/B: Deterministic hash-based bucketing

---

## 10. Trigger Events (detected between Flink runs)

| Trigger | Detection | Action |
|---------|-----------|--------|
| Salary credit | salary_m1 changed | Prioritize FD within 24hrs |
| Credit-seeking spike | lending_apps_c30 increased by ≥2 | Prioritize loan (Healthy/Neutral only) |
| Balance surge | latest_balance up >50% vs avg | Trigger FD |
| CC app elsewhere | cnt_cc_applications_c30 up | Prioritize card (Healthy/Neutral only) |
| EMI bounce | ecs_bounce or si_bounce appeared | Suppress credit; may downgrade health_tier |
| Health tier change | health_tier worsened | Re-run full pipeline; suppress newly ineligible |

---

## 11. Cold Start Strategy

| Scenario | Approach | Default Reco |
|----------|----------|-------------|
| No SMS data (Finbox pending) | Popularity-based | UPI activation |
| Partial SMS (<30 days) | Conservative rules, higher thresholds | UPI + Referral |
| Full SMS, no super.money history | Full scoring pipeline | Score-based |
| Returning user, stale features | Last known recos, reduced confidence | Previous top reco with "stale" flag |

---

## 12. Config Structure (suggested)

```yaml
# scoring_rules.yaml
products:
  fd:
    eligibility:
      - feature: has_fd
        operator: equals
        value: false
      - feature: calculated_income
        operator: gte
        value: 15000
      - feature: fraud_flag
        operator: equals
        value: false
    scoring_rules:
      - feature: avg_balance_c30
        weight: 25
        tiers:
          - threshold: 100000
            score: 25
          - threshold: 50000
            score: 20
          - threshold: 25000
            score: 10
          - default: 0
    threshold: 40
    health_gate: null  # no gate for FD

  personal_loan:
    eligibility:
      - feature: calculated_income
        operator: gte
        value: 20000
      - feature: debt_burden_ratio
        operator: lt
        value: 0.5
      - feature: delinquency_c30
        operator: equals
        value: 0
      - feature: fraud_flag
        operator: equals
        value: false
    scoring_rules:
      - feature: credit_seeking_intensity
        weight: 30
        tiers:
          - threshold: 3
            score: 30
          - threshold: 1
            score: 20
          - default: 10
    threshold: 50
    health_gate: [healthy]  # only healthy tier

# health_config.yaml
health_tiers:
  healthy:
    credit_products: allowed
    savings_boost: 0
    credit_penalty: 0
  neutral:
    credit_products: allowed_with_penalty
    savings_boost: 15
    credit_penalty: -10
  stressed:
    credit_products: blocked
    savings_boost: 15
    credit_penalty: null  # N/A, blocked before scoring

# surface_affinity.yaml
affinity_matrix:
  post_upi_transaction:
    fd: 0.8       # GOOD
    card: 1.0     # BEST
    loan: 0.1     # LOW
    referral: 0.8  # GOOD
  app_home_feed:
    fd: 1.0
    card: 1.0
    loan: 0.8
    referral: 0.5
  salary_credit_push:
    fd: 1.0
    card: 0.8
    loan: 0.5
    referral: 0.1

# fatigue_config.yaml
fatigue:
  same_product_cooldown_hours: 48
  same_product_penalty: 30
  dismissed_cooldown_days: 7
  dismissed_penalty: 50
  daily_limit: 3
  weekly_limit: 10

# ranking_weights.yaml
ranking:
  propensity_weight: 0.50
  business_priority_weight: 0.25
  fatigue_weight: 0.15
  trigger_weight: 0.10
business_priority:
  card: 0.9
  fd: 0.8
  loan: 0.7
  upi: 0.5
  referral: 0.4
```

---

## 13. Suggested Project Structure

```
sm-recommendation/
├── config/
│   ├── scoring_rules.yaml
│   ├── health_config.yaml
│   ├── surface_affinity.yaml
│   ├── fatigue_config.yaml
│   └── ranking_weights.yaml
├── src/
│   ├── models/              # Data models / schemas
│   │   ├── user_features.py
│   │   ├── recommendation.py
│   │   └── enums.py         # HealthTier, Product, Surface enums
│   ├── processor/           # Recommendation Flink processor logic
│   │   ├── pipeline.py      # Orchestrates 6-stage pipeline
│   │   ├── candidate_generator.py
│   │   ├── health_gate.py
│   │   ├── eligibility.py
│   │   ├── scorer.py        # Config-driven propensity scoring
│   │   ├── ranker.py        # Ranking formula + arbitration
│   │   └── trigger_detector.py
│   ├── serving/             # Serving API
│   │   ├── api.py           # FastAPI/Flask endpoints
│   │   ├── surface_filter.py
│   │   ├── fatigue_manager.py
│   │   └── ab_experiment.py
│   ├── config/              # Config loader
│   │   └── loader.py        # YAML parsing + validation
│   └── utils/
│       └── constants.py
├── tests/
│   ├── test_health_gate.py
│   ├── test_scorer.py
│   ├── test_ranker.py
│   ├── test_pipeline.py
│   ├── test_serving_api.py
│   └── test_fatigue.py
├── requirements.txt
└── README.md
```

---

## 14. Key Design Decisions (for implementation)

1. **Config-driven scoring** — No hardcoded thresholds. All rules loaded from YAML. Changing a threshold = config change, no code deploy.
2. **Health gate runs BEFORE scoring** (Stage 2) — Don't waste compute on products that will be suppressed.
3. **Reco Processor is separate from Feature Store** — Independent Flink job, own deploy cycle, own storage.
4. **Serving API is a thin read layer** — No heavy computation. Read pre-computed recos, apply surface filter + fatigue, return.
5. **Batch-first for V0** — No real-time Bigtable reads. Flink reads offline store in batch, writes to Recommendation Store.
6. **Top 5 recos per user stored** — Serving API picks from these based on surface context.
7. **Rules generate training data for V1** — Every serve/impression/click/conversion logged with full feature vector.

---

## 15. Tech Stack (expected)

- **Language:** Python (processor logic), Java/Kotlin (if Flink is JVM-based in your org)
- **Processor:** Apache Flink (batch mode for V0)
- **Serving API:** FastAPI (Python) or Spring Boot (Java) — lightweight
- **Storage:** Offline Feature Store (existing), Recommendation Store (new — Redis/DynamoDB/Bigtable)
- **Config:** YAML files, loaded at startup, hot-reloadable
- **Testing:** pytest with fixtures for user feature profiles

---

## 16. What to Build First (V0 implementation order)

1. **Config loader + data models** — Enums, YAML parsing, validation
2. **Health gate** — Simple but critical; unit test thoroughly
3. **Eligibility checker** — Binary pass/fail per product
4. **Propensity scorer** — Config-driven weighted sum
5. **Ranker** — Multi-factor formula
6. **Pipeline orchestrator** — Wire stages 1-6 together
7. **Serving API** — FastAPI with surface filtering + fatigue
8. **Integration tests** — End-to-end: feature vector in → ranked recos out
