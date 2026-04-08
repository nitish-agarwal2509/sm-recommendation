# PRD: super.money Recommendation Engine (V0)

## Context
super.money is a UPI-first fintech. The recommendation engine surfaces one contextual product recommendation per user per session, using batch-computed rule-based scoring on SMS insights (Finbox data). V0 is purely batch + rule-based. No ML, no streaming, no real-time triggers.

**Output: 1 recommendation per user** (not 5). The engine computes a ranked score for all eligible products and returns the single highest-scoring one. This is stored in Bigtable and served by the API.

---

## 1. Product Lines

| # | Product | Type | User value proposition |
|---|---------|------|----------------------|
| 1 | UPI Activation | Activation | Activate UPI on super.money (dormant or not activated) |
| 2 | Personal Loan | Credit | Get instant credit at competitive rates |
| 3 | Unsecured Credit Card | Credit | Get a card with cashback & rewards |
| 4 | Secured Credit Card | Credit | Get a card backed by FD, with cashback & rewards |
| 5 | CC Bill Payment | Retention | Pay your credit card bill via super.money |
| 6 | Rent Payment | Transactional | Pay rent via UPI, earn rewards |
| 7 | Bill Payments | Transactional | Pay broadband / DTH / electricity / LPG / postpaid bills |
| 8 | Recharges | Transactional | Recharge prepaid mobile |
| 9 | Referrals | Growth | Invite friends, earn cashback |
| 10 | Fixed Deposit | Savings | Grow idle money safely |
| 11 | Flights | Transactional | Book flights, earn cashback |

---

## 2. Data Architecture (V0)

```
Finbox SMS Insights (1,014 feature columns)
        │  existing pipeline → lands in BigQuery
        ▼
BigQuery table: sms_insights  ← THIS IS the feature store. No separate Flink feature pipeline needed.
        │
        │  Flink batch job (daily, incremental read: WHERE date_processed > last_run_watermark)
        │  - derives 5 secondary features per row (health score, surplus, DTI, etc.)
        │  - runs 6-stage pipeline per user
        ▼
Bigtable (or local JSON file in V0 local mode)
  row key: user_id
  columns:
    scored_candidates     (JSON: top-5 products with pre-surface scores + propensity + reason)
    health_tier           (HEALTHY / NEUTRAL / STRESSED)
    computed_at           (batch run timestamp)
    fatigue:shown_at[PRODUCT]    (per-product shown timestamp — written by Serving API)
    fatigue:shown_count[PRODUCT] (per-product show count — incremented by Serving API)
        │
        │  Spring Boot Serving API (<50ms read)
        ▼
Client (surface + user_id) → applies surface affinity → returns top-1 for that surface
```

**Local V0 execution:** Flink reads from a local CSV (sms_insights sample), writes scored candidates to a local JSON file (one JSON object per line, keyed by user_id). Spring Boot reads from this JSON file instead of Bigtable. BigQuery + Bigtable connectors wired later when moving to cloud.

---

## 3. Secondary Feature Derivations (computed by Flink, one-time per row)

These are the only new computations. All other features come directly from SMS insights columns.

```java
// 1. Financial Health Score (0–100)
double fis_base         = fis_affordability_v1 * 60;              // 0–60 pts
double loan_pen         = min(cnt_delinquncy_loan_c90, 3) / 3.0 * 15;
double cc_pen           = min(cnt_delinquncy_cc_c90, 3) / 3.0 * 10;
double bounce_pen       = (bounce_flag == 1 ? 10 : 0) + (auto_debit_bounce_m0 > 0 ? 5 : 0);
double dti              = total_emi_loan_all_acc_m0 / max(total_income_m0, 1.0);
double dti_pen          = clamp((dti - 0.30) * 50, 0, 30);       // penalty kicks in above 30% DTI
double health_score     = clamp(fis_base - loan_pen - cc_pen - bounce_pen - dti_pen, 0, 100);
// → HEALTHY if >= 70, NEUTRAL if >= 40, STRESSED if < 40

// 2. Monthly Surplus Cash
double surplus_cash     = total_income_m0 - total_emi_loan_all_acc_m0 - obligations;

// 3. Debt-to-Income Ratio
double dti_ratio        = total_emi_loan_all_acc_m0 / max(total_income_m0, 1.0);

// 4. Average UPI Ticket Size
double avg_upi_ticket   = amt_total_txn_upi_3m / max(cnt_total_txn_upi_3m, 1.0);

// 5. Bill Urgency Score
double bill_urgency     = max(max_dpd_broadband, max(max_dpd_dth,
                              max(max_dpd_electric,
                                  cnt_postpaid_bill_overdue_c180 > 0 ? 30.0 : 0.0)));
```

---

## 4. Health Policy Gate

Runs **before scoring** — STRESSED users never reach credit product scorers.

| Tier | health_score range | Products BLOCKED |
|------|-------------------|-----------------|
| HEALTHY | ≥ 70 | None |
| NEUTRAL | 40–69 | None (penalty applied in scorer) |
| STRESSED | < 40 | Personal Loan, Unsecured Card, Secured Card, CC Bill Payment |

STRESSED users CAN receive: UPI Activation, Bill Payments, Recharges, Referrals, FD, Flights, Rent Payment.

---

## 5. Eligibility Rules Per Product (Hard Pass/Fail — before scoring)

Eligibility failures = product dropped entirely. No score computed.

```
UPI Activation:
  - upi_user_flag = 0  OR  upi_recency > 90  (dormant on super.money)
  - acc0_acc_number is not null               (linkable bank account exists)

Personal Loan:
  - calculated_income_amount_v4 >= 20,000
  - cnt_delinquncy_loan_c90 = 0
  - dti_ratio < 0.50
  - cnt_active_loan_accounts_m1 < 3
  - health_tier IN (HEALTHY, NEUTRAL)

Unsecured Card:
  - calculated_income_amount_v4 >= 25,000
  - cnt_cc_acc = 0  OR  (rupay_cc_profile = true AND count_active_cc_cards = 1)
  - cnt_delinquncy_cc_c90 = 0 AND cnt_delinquncy_loan_c90 = 0
  - max_dpd_acc1 < 30
  - health_tier IN (HEALTHY, NEUTRAL)

Secured Card:
  - fd_flag = 1                               (has FD for collateral)
  - cnt_cc_acc = 0
  - calculated_income_amount_v4 >= 15,000
  - bounce_flag = 0
  - health_tier IN (HEALTHY, NEUTRAL)

CC Bill Payment:
  - credit_card_user_flag = 1 AND cnt_cc_acc > 0
  - cc_bill_m0 > 0                            (has outstanding bill)
  - health_tier IN (HEALTHY, NEUTRAL)

Rent Payment:
  - upi_user_flag = 1
  - calculated_income_amount_v4 >= 15,000
  - large_recurring_debit = true              (derived: see Section 5.1 below)
  - NOT already paying rent via super.money   (inferred: no rent-sized credits in acc of landlord)

Bill Payments:
  - any of: broadband_flag=1, dth_flag=1, electricity_flag=1, postpaid_flag=1, lpg_flag=1
  - upi_user_flag = 1

Recharges:
  - prepaid_flag = 1
  - upi_user_flag = 1

Referrals:
  - upi_user_flag = 1
  - digital_savviness >= 5

FD:
  - fd_flag = 0 AND cnt_fd_accounts = 0      (don't recommend if already has FD)
  - total_avg_bal_30 >= 10,000

Flights:
  - calculated_income_amount_v4 >= 50,000
  - digital_savviness >= 6
```

### 5.1 Rent Detection (derived signal)
```java
// Proxy: consistent large monthly debit (~same date) suggesting rent
boolean large_recurring_debit =
    acc0_amt_debits_p30 >= 5000         // last 30d debit is large
    AND acc0_amt_debits_c30 >= 5000
    AND acc0_amt_debits_c60 >= 5000 * 2 // consistent over 2 months
    AND acc0_vintage >= 60;             // account old enough to have history
```

### 5.2 Held Product Inference from SMS Insights
Since there's no product-side API in V0, infer from SMS insights:

| Product | "Already has it" signal |
|---------|------------------------|
| Loan | `cnt_active_loan_accounts_m1 >= 1` OR `loan_acc1 != null` |
| Unsecured Card | `cnt_cc_acc >= 1` AND `rupay_cc_profile = false` |
| Secured Card | `cnt_cc_acc >= 1` |
| FD | `fd_flag = 1` OR `cnt_fd_accounts >= 1` |
| UPI (super.money) | `upi_user_flag = 1` AND `upi_recency <= 30` |
| CC Bill Payment | infer: `cc_bill_m0 = 0` (no outstanding bill) → skip |

---

## 6. Propensity Scoring — Exact Formula Per Product

### Normalization helper
```
normalize(x, min_val, max_val) = clamp((x - min_val) / (max_val - min_val), 0.0, 1.0)
```
All `min_val`, `max_val`, and `weight` values come from `scoring_rules.yaml`.

### Weighted Sum Formula (same for all products)
```
propensity_score =
    Σ (normalize(signal_i, min_i, max_i) × weight_i)
    / Σ weight_i
    × 100
```

---

### 6.1 UPI Activation
| Signal | Column | Normalization | Weight |
|--------|--------|--------------|--------|
| Account linkability | `acc0_acc_number != null` → 1.0 else 0.0 | binary | 40 |
| Digital savviness | `digital_savviness` | normalize(x, 0, 10) | 30 |
| Net banking enabled | `net_banking_flag` | binary | 20 |
| Finance app usage | `recency_apps_genre_finance` | normalize(x, 0, 180) inverted | 10 |

---

### 6.2 Personal Loan
| Signal | Column | Normalization | Weight |
|--------|--------|--------------|--------|
| Income level | `calculated_income_amount_v4` | normalize(x, 20000, 150000) | 25 |
| Credit-seeking intent | `cnt_loan_applications_c30 + cnt_cc_applications_c30` | normalize(x, 0, 5) | 25 |
| Repayment cleanliness | `cnt_delinquncy_loan_c90 = 0 AND auto_debit_bounce_m0 = 0` → 1.0 else 0.0 | binary | 20 |
| Debt headroom | `1 - dti_ratio` (derived) | normalize(x, 0, 1) | 20 |
| Account vintage | `acc0_vintage` | normalize(x, 0, 730) | 10 |

**Cross-sell boost:** If `cc_bill_m0 > 0 AND days_since(cc_bill_latest_date) >= 23` → add +15 to propensity (CC bill due = loan opportunity).

---

### 6.3 Unsecured Credit Card
| Signal | Column | Normalization | Weight |
|--------|--------|--------------|--------|
| Income | `calculated_income_amount_v4` | normalize(x, 25000, 200000) | 30 |
| Clean credit history | `max_dpd_acc1 = 0 AND cnt_delinquncy_cc_c90 = 0` → 1.0 else 0.0 | binary | 25 |
| Rupay upgrade opportunity | `rupay_cc_profile = true` → 1.0 else 0.5 | binary | 20 |
| CC application intent | `cnt_cc_applications_c30` | normalize(x, 0, 3) | 15 |
| UPI spend (willingness to spend) | `amt_total_debits_upi_3m` | normalize(x, 0, 300000) | 10 |

---

### 6.4 Secured Credit Card
| Signal | Column | Normalization | Weight |
|--------|--------|--------------|--------|
| FD amount (collateral quality) | `amt_fd_accounts_c180` | normalize(x, 0, 500000) | 30 |
| Income | `calculated_income_amount_v4` | normalize(x, 15000, 100000) | 25 |
| Clean history | `bounce_flag = 0 AND cnt_delinquncy_loan_c90 = 0` → 1.0 else 0.0 | binary | 25 |
| Digital savviness | `digital_savviness` | normalize(x, 0, 10) | 20 |

---

### 6.5 CC Bill Payment
| Signal | Column | Normalization | Weight |
|--------|--------|--------------|--------|
| Bill urgency (days until due) | `days_since(cc_bill_latest_date)` | normalize(x, 20, 30) → higher = more urgent | 40 |
| Bill amount | `cc_bill_m0` | normalize(x, 0, 100000) | 35 |
| UPI active | `upi_user_flag = 1` → 1.0 else 0.5 | binary | 15 |
| CC utilisation | `cc_utilisation` | normalize(x, 0, 1) | 10 |

---

### 6.6 Rent Payment
| Signal | Column | Normalization | Weight |
|--------|--------|--------------|--------|
| Recurring debit size | `acc0_amt_debits_p30` | normalize(x, 5000, 50000) | 40 |
| Income level | `calculated_income_amount_v4` | normalize(x, 15000, 100000) | 30 |
| UPI active | `upi_user_flag = 1` → 1.0 else 0.5 | binary | 20 |
| Digital savviness | `digital_savviness` | normalize(x, 0, 10) | 10 |

---

### 6.7 Bill Payments
| Signal | Column | Normalization | Weight |
|--------|--------|--------------|--------|
| Bill urgency | `bill_urgency` (derived) | normalize(x, 0, 60) | 40 |
| Number of active bill types | count of (broadband_flag + dth_flag + electricity_flag + postpaid_flag + lpg_flag) | normalize(x, 1, 5) | 30 |
| UPI active | `upi_user_flag = 1` → 1.0 else 0.5 | binary | 20 |
| Avg postpaid bill value | `avg_postpaid_bill_3m` | normalize(x, 0, 5000) | 10 |

**Sub-product selection (which specific bill to show):**
```
Priority: pick the bill type with lowest recency (most overdue / due soonest):
  min(broadband_recency, dth_recency, electricity_recency, lpg_recency, postpaid_recency)
→ show "Pay your [provider_name] bill"
```

---

### 6.8 Recharges
| Signal | Column | Normalization | Weight |
|--------|--------|--------------|--------|
| Recharge frequency | `cnt_paytm_wallet_recharge_m1 + m2 + m3` | normalize(x, 0, 9) | 40 |
| Avg recharge amount | `(sum_amt_topup_m1 + m2 + m3) / 3` | normalize(x, 0, 500) | 30 |
| UPI active | `upi_user_flag = 1` → 1.0 else 0.5 | binary | 30 |

---

### 6.9 Referrals
| Signal | Column | Normalization | Weight |
|--------|--------|--------------|--------|
| Digital savviness | `digital_savviness` | normalize(x, 5, 10) | 35 |
| UPI activity | `cnt_total_txn_upi_3m` | normalize(x, 0, 100) | 30 |
| Multi-platform usage | count of (phonepe_user_flag + paytm_user_flag + mobikwik_user_flag) | normalize(x, 0, 3) | 20 |
| Finance app vintage | `vintage_apps_genre_finance` | normalize(x, 0, 365) | 15 |

---

### 6.10 Fixed Deposit
| Signal | Column | Normalization | Weight |
|--------|--------|--------------|--------|
| Average balance | `total_avg_bal_30` | normalize(x, 10000, 500000) | 35 |
| Monthly surplus | `surplus_cash` (derived) | normalize(x, 0, 100000) | 25 |
| Income stability | `calculated_income_confidence_v4 = "HIGH"` → 1.0, else 0.5 | binary | 15 |
| Salary consistency | `salary_m1_v4 > 0 AND salary_m2_v4 > 0 AND salary_m3_v4 > 0` → 1.0 else 0.5 | binary | 15 |
| Investment propensity | `fps_investment_v1_probability` | normalize(x, 0, 1) | 10 |

---

### 6.11 Flights
| Signal | Column | Normalization | Weight |
|--------|--------|--------------|--------|
| Income | `calculated_income_amount_v4` | normalize(x, 50000, 300000) | 40 |
| Avg balance | `total_avg_bal_30` | normalize(x, 50000, 500000) | 30 |
| Digital savviness | `digital_savviness` | normalize(x, 6, 10) | 30 |

---

## 7. The Uber Formula — End-to-End Walkthrough

Given one user row from SMS insights, here is the complete flow that produces one recommendation:

```
INPUT: UserFeatures (from sms_insights row)

STEP 1 — Derive secondary features:
  health_score, surplus_cash, dti_ratio, avg_upi_ticket, bill_urgency

STEP 2 — Health gate:
  health_tier = HEALTHY / NEUTRAL / STRESSED
  IF STRESSED → remove {PERSONAL_LOAN, UNSECURED_CARD, SECURED_CARD, CC_BILL_PAYMENT} from candidates

STEP 3 — Eligibility filter (per remaining product):
  FOR each candidate product:
    IF any hard rule fails → drop product
  → eligible_products[]

STEP 4 — Propensity score (per eligible product):
  FOR each product p in eligible_products:
    propensity[p] = weighted_normalized_sum(signals[p]) × 100

STEP 5 — Health adjustment:
  adjusted_propensity[p] = propensity[p] + health_adjustment[p][health_tier]
  (see table in Section 7.1)

STEP 6 — Trigger boost (batch-approximated):
  trigger_boost[p] = trigger_signal(features, p)
  (see table in Section 7.2)

STEP 7 — Fatigue penalty:
  fatigue_penalty[p] = lookup from Bigtable: was this product shown in last 48h?
    → if shown_at < now - 48h → penalty = 0
    → if shown_at >= now - 48h → penalty = 30
    → if shown_at >= now - 24h → penalty = 50  (very recent)
    → if user already converted (has_product[p] = true from SMS inferences) → penalty = 999

STEP 8 — Final score:
  final_score[p] =
      (adjusted_propensity[p]   × 0.50)
    + (business_priority[p]×100 × 0.25)
    - (fatigue_penalty[p]       × 0.15)
    + (trigger_boost[p]         × 0.10)

STEP 9 — Arbitration:
  Sort by final_score DESC
  Apply diversity rules (Section 7.3)

STEP 10 — Output:
  Sort all eligible products by final_score DESC
  Apply arbitration rules (Section 7.3)
  Write to Bigtable/local JSON:
    top-5 candidates: [{ product, propensity_score, pre_surface_score=final_score, health_tier, reason }]
    health_tier, computed_at
  (DO NOT overwrite fatigue columns — those are owned by the Serving API)

OUTPUT: top-5 pre-surface candidates per user stored.
        Serving API applies surface affinity + fatigue at read time → returns 1 recommendation per surface call.
```

---

### 7.1 Health Adjustments

| Product | HEALTHY | NEUTRAL | STRESSED |
|---------|:-------:|:-------:|:--------:|
| UPI Activation | 0 | 0 | 0 |
| Personal Loan | 0 | -10 | BLOCKED |
| Unsecured Card | 0 | -10 | BLOCKED |
| Secured Card | 0 | -5 | BLOCKED |
| CC Bill Payment | 0 | -5 | BLOCKED |
| Rent Payment | 0 | 0 | 0 |
| Bill Payments | 0 | 0 | +10 |
| Recharges | 0 | 0 | 0 |
| Referrals | 0 | 0 | 0 |
| FD | 0 | +5 | +15 |
| Flights | 0 | 0 | 0 |

---

### 7.2 Trigger Boosts (V0 batch-approximated)

| Condition | Product boosted | Boost |
|-----------|----------------|-------|
| `salary_date_m1_v3` within 3 days of batch run date | FD, Rent, CC Bill Payment | +15 |
| `total_avg_bal_30 < 5000` | Personal Loan | +12 |
| `bill_urgency >= 30` | Bill Payments | +20 |
| `days_since(cc_bill_latest_date) >= 23` | CC Bill Payment | +20 |
| `cnt_loan_applications_c30 > 0 OR cnt_cc_applications_c30 > 0` | Personal Loan, Unsecured Card | +10 |
| `cc_bill_m0 > 0 AND days_since >= 23` | Personal Loan (cross-sell) | +15 |

---

### 7.3 Arbitration Rules (diversity + ethics)

```
1. UPI Activation → always rank 1 if eligible (overrides all scores)
2. Max 1 credit product per recommendation cycle
   (if NEUTRAL: credit penalty already applied; no additional rule)
3. FD is preferred over Flights if their final_scores are within 5 points
   (ethical preference: savings > lifestyle)
4. Tie-breaking: prefer lower business_priority product
   (avoids always recommending highest-monetizing product)
```

---

### 7.4 Business Priority

| Product | Priority | Rationale |
|---------|:--------:|-----------|
| UPI Activation | 1.0 | Core funnel — must activate first |
| Personal Loan | 0.95 | Highest monetization, high intent |
| Unsecured Card | 0.90 | High LTV product |
| Secured Card | 0.85 | Good LTV, lower eligibility bar |
| CC Bill Payment | 0.75 | Retention + habit formation |
| Rent Payment | 0.65 | High frequency, habit forming |
| Bill Payments | 0.60 | High frequency transactional |
| Recharges | 0.55 | Frequent, low friction |
| Referrals | 0.50 | Growth |
| FD | 0.40 | Good product, lower margin |
| Flights | 0.30 | Occasional, lifestyle |

---

## 8. Surfaces & Real Estates

| Surface ID | Surface Name | Description | V0? |
|------------|-------------|-------------|-----|
| S1 | App Home — Nudge Banner | Persistent banner at top of home screen, lower CTR | Yes |
| S2 | App Home — Nudge Bottomsheet | Blocking modal on app open, higher CTR | Yes |
| S3 | Post-UPI Transaction | Shown after a UPI payment completes | Yes |
| S4 | Cashback Redeemed | Shown when user redeems cashback to bank account | Yes |
| S5 | Rewards History Page | Shown on rewards/cashback history page | Yes |

**Surface × Product Affinity Multiplier:**

| Product | S1: Home Banner | S2: Home Bottomsheet | S3: Post-UPI | S4: Cashback Redeemed | S5: Rewards History |
|---------|:--------------:|:-------------------:|:------------:|:--------------------:|:------------------:|
| UPI Activation | 1.0 | 1.0 | 0.5 | 0.5 | 0.5 |
| Personal Loan | 0.8 | 1.0 | 0.4 | 0.7 | 0.6 |
| Unsecured Card | 0.9 | 1.0 | 0.9 | 1.0 | 1.0 |
| Secured Card | 0.8 | 1.0 | 0.8 | 0.9 | 0.9 |
| CC Bill Payment | 0.9 | 1.0 | 0.5 | 0.6 | 0.7 |
| Rent Payment | 0.9 | 1.0 | 0.5 | 0.7 | 0.5 |
| Bill Payments | 0.8 | 0.9 | 0.7 | 0.6 | 0.6 |
| Recharges | 0.7 | 0.8 | 0.8 | 0.5 | 0.5 |
| Referrals | 0.6 | 0.8 | 0.8 | 0.9 | 0.9 |
| FD | 0.8 | 0.9 | 0.7 | 0.9 | 0.8 |
| Flights | 0.5 | 0.7 | 0.3 | 0.8 | 0.8 |

**How surfaces and the "1 recommendation" work together:**

The Flink job stores **top-5 pre-surface scored candidates** per user (not yet surface-adjusted). At serve time, the API:
1. Reads the top-5 candidates for the user
2. Applies `final_score[p] × surface_affinity[surface][p]` for each candidate
3. Re-sorts by surface-adjusted score
4. Returns the **top-1 for that specific surface**

This means the same user may see a different product recommendation on the Home Bottomsheet vs. Post-UPI Transaction — both are derived from the same batch-computed candidate set, just surface-adjusted differently at read time.

**Surface affinity is applied at serve time** (not stored). Pre-surface scores are stored.

---

## 9. Fatigue Management (V0)

**V0 constraints:** No explicit dismiss or "not interested" action. No session/click tracking.

**V0 approach — API-side impression tracking:**
- Every time `GET /recommendation` is called and returns product P, the API:
  1. Increments `fatigue:shown_count[P]` in Bigtable
  2. Writes `fatigue:shown_at[P] = now()`
- Next batch run reads these fatigue signals and incorporates them into final_score computation.

**Frequency Capping (per product):** Rather than a hard block after 1 show, each product has a configurable `max_impressions_before_fatigue` in YAML. Penalty scales with show count:

```
shown_count = fatigue:shown_count[product]   (default 0 if never shown)
max_cap     = scoring_rules.yaml → fatigue → products[p].max_impressions  (e.g., 3 for Loan, 5 for Bills)

fatigue_penalty[p]:
  shown_count = 0           → 0    (never shown)
  shown_count = 1           → 10   (shown once, mild penalty)
  shown_count = 2           → 25   (shown twice, increasing)
  shown_count >= max_cap    → 70   (fully fatigued — very unlikely to rank #1)
  has_product[p] = true     → 999  (converted — permanently excluded)

# Additionally, recency still matters:
if shown_at[p] < 24h ago → add +20 to above penalty (shown today, back off)
```

**Max impressions before fatigue (configurable in YAML):**

| Product | Max Impressions |
|---------|:--------------:|
| UPI Activation | 5 |
| Personal Loan | 3 |
| Unsecured Card | 3 |
| Secured Card | 3 |
| CC Bill Payment | 4 |
| Rent Payment | 4 |
| Bill Payments | 5 |
| Recharges | 6 |
| Referrals | 4 |
| FD | 4 |
| Flights | 5 |

---

## 10. Bigtable / Local JSON Schema

**Local V0:** Single JSON file `recommendations.json` (one JSON line per user_id). Same logical structure as Bigtable.

**Write (by Flink job, daily — merges with existing fatigue data):**
```
row key: user_id
column family: reco
  reco:candidates       = JSON array of top-5 { product, propensity_score, pre_surface_score, health_tier, reason }
  reco:health_tier      = "NEUTRAL"
  reco:computed_at      = "2026-04-08T02:00:00Z"

column family: fatigue  ← NOT overwritten by Flink; preserved across runs
  fatigue:PERSONAL_LOAN:shown_count = "2"                    ← written by serving API
  fatigue:PERSONAL_LOAN:shown_at    = "2026-04-07T14:30:00Z" ← written by serving API
  fatigue:UNSECURED_CARD:shown_count = "1"
  fatigue:UNSECURED_CARD:shown_at    = "2026-04-06T09:00:00Z"
  (one entry per product, per user)
```

**Read (by serving API):**
```
GET /recommendation?user_id=X&surface=HOME_BOTTOMSHEET
  1. Read reco:candidates + all fatigue:* columns
  2. Apply fatigue penalty to each candidate's pre_surface_score
  3. Apply surface affinity multiplier
  4. Sort → return top-1
  5. Write: fatigue:[returned_product]:shown_count += 1
             fatigue:[returned_product]:shown_at = now()
```

**Incremental Flink read (BigQuery, production):**
```sql
SELECT * FROM sms_insights
WHERE date_processed > '{last_run_watermark}'
-- watermark stored in job config or a separate BQ metadata table
-- updated to max(date_processed) after each successful run
```

---

## 11. Serving API Response

```json
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
```

---

## 12. V0 Scope

**In scope:**
- All 11 product scorers with YAML-driven rules
- Health gate (derive health_score from SMS features)
- Eligibility filter per product
- Propensity scoring with weighted normalized signals
- Trigger boost (batch-approximated)
- Fatigue via Bigtable shown_at (API writes on read)
- Arbitration rules
- 5 surfaces with affinity multipliers at serve time
- Spring Boot serving API (single recommendation output)
- Local: Flink reads local CSV, outputs to stdout; API connects to local Bigtable emulator

**Out of scope (V1+):**
- ML propensity models
- Real-time streaming / event triggers
- Explicit dismiss / negative feedback actions
- A/B experimentation
- Additional surfaces

---

## 13. Success Metrics

| Metric | V0 Target |
|--------|-----------|
| Population coverage | ≥ 70% users get a recommendation |
| CTR (tap on bottomsheet) | ≥ 20% |
| Conversion | ≥ 3% |
| Health gate suppression rate | Monitor (expected 20–30%) |
| Serving latency P99 | < 50ms |

---

## 14. Resolved Questions

| Question | Resolution |
|----------|-----------|
| Rent detection | Derive from large recurring monthly debit pattern |
| Held product list | Infer from SMS insights columns (fd_flag, loan_acc1, cnt_cc_acc, etc.) |
| CC Bill due date | Use `days_since(cc_bill_latest_date) >= 23` as proxy |
| Flights geo/seasonal | No constraints for V0 |
| Salary Credit Push surface | Removed — not in V0 |
| Fatigue dismiss tracking | Not in V0 — use API shown_at proxy instead |
| Converted users | Infer from SMS insights held product signals |
