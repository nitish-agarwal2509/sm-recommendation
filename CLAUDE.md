# SM Recommendation Engine — Claude Context

## Project
Rule-based contextual recommendation engine for super.money (UPI-first fintech).
Recommends 1 financial product per user per surface call across 11 product lines.

## Tech Stack
- **Language:** Java (all layers — no Python)
- **Batch Processor:** Apache Flink 1.18.1 (batch mode, `RuntimeExecutionMode.BATCH`)
- **Serving API:** Spring Boot
- **Store:** Google Cloud Bigtable (local V0: JSON file)
- **Feature Source:** Google BigQuery / SMS insights (local V0: CSV file)
- **Build:** Maven multi-module (`common`, `processor`, `serving-api`)
- **Config:** YAML (all thresholds/weights — no hardcoded values in Java)

## Module Structure
```
sm-recommendation/
├── CLAUDE.md
├── pom.xml                  ← parent POM
├── common/                  ← enums, models, config POJOs, YAML loader (no framework deps)
├── processor/               ← Flink job + pipeline logic (all 6 stages)
├── serving-api/             ← Spring Boot API (GET /recommendation)
└── docs/
    ├── PRD.md               ← full product requirements + algorithms
    └── phase/               ← implementation plan (one file per phase)
```

## Build Commands
```bash
# Full build
mvn clean package

# Run tests for one module
mvn test -pl common
mvn test -pl processor
mvn test -pl serving-api

# Run Flink job locally
mvn exec:exec -pl processor \
  -Dexec.args="--input processor/src/test/resources/sms_insights_sample.csv \
               --output processor/output/recommendations.json \
               --scoring-config common/src/main/resources/scoring_rules.yaml"

# Start serving API
mvn spring-boot:run -pl serving-api
```

**IMPORTANT — use `exec:exec`, not `exec:java` for Flink.** `exec:java` shares Maven's classloader and causes `ClassNotFoundException` for Flink's internal serialization. `exec:exec` spawns a fresh JVM.

**JDK compatibility:** JDK 17+ required. JDK 25 needs `--add-opens` flags for Flink/Kryo (already configured in pom.xml).

**Maven location:** `/Users/nitish.agarwal5/tools/maven/bin/mvn`

## Key Design Decisions
- **Local-first V0:** Flink reads local CSV, writes JSON. API reads JSON. No BigQuery/Bigtable until cloud migration.
- **1 recommendation per surface call:** Flink stores top-5 pre-surface candidates. API applies surface affinity at serve time → returns top-1.
- **No hardcoded thresholds:** All weights, min/max, eligibility rules, fatigue caps live in `scoring_rules.yaml`.
- **Fatigue = frequency capping** (not hard block after 1 show). Configurable `max_impressions` per product.
- **Held product detection:** Inferred from SMS insights columns (no product-side API in V0).
- **Rent detection:** Derived from large recurring monthly debit pattern in bank account data.
- **Health gate runs BEFORE scoring:** STRESSED users never reach credit product scorers.

## Implementation Phases
| Phase | What | Status |
|-------|------|--------|
| 1 | Common module (enums, models, YAML config) | Complete |
| 2 | Pipeline logic in plain Java (no Flink, fully unit-tested) | Complete |
| 3 | Flink job (wraps Phase 2 logic, CSV → JSON) | Complete |
| 4 | Spring Boot serving API (JSON read, surface affinity, fatigue write) | Not started |
| 5 | End-to-end integration tests | Not started |

## Pipeline (6 Stages, per user)
1. Candidate Generation (all products minus held)
2. Health Policy Gate (STRESSED → block credit products)
3. Eligibility Filter (hard pass/fail rules per product)
4. Propensity Scoring (weighted normalized signals → 0–100)
5. Ranking & Arbitration (final formula + diversity rules)
6. Write top-5 → local JSON (or Bigtable in cloud)

## Final Score Formula
```
final_score[p] =
    (adjusted_propensity[p]   × 0.50)
  + (business_priority[p]×100 × 0.25)
  - (fatigue_penalty[p]       × 0.15)
  + (trigger_boost[p]         × 0.10)
```

## Health Tiers
- HEALTHY (≥ 70): all products eligible
- NEUTRAL (40–69): credit products get penalty
- STRESSED (< 40): PERSONAL_LOAN, UNSECURED_CARD, SECURED_CARD, CC_BILL_PAYMENT blocked

## Surfaces (5)
`HOME_BANNER`, `HOME_BOTTOMSHEET`, `POST_UPI`, `CASHBACK_REDEEMED`, `REWARDS_HISTORY`
Surface affinity multiplier applied at API serve time (not stored in batch output).

## PRD
Full product requirements, exact scoring formulas, eligibility rules, feature derivation:
`docs/PRD.md`
