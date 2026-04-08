# Phase 1 — Common Module

## Goal
Build the shared foundation used by both the Flink processor and the Spring Boot serving API.
No business logic here — just models, enums, config POJOs, and YAML loading.

---

## Modules to Create
```
sm-recommendation/
├── pom.xml                        ← parent POM (multi-module)
└── common/
    ├── pom.xml
    └── src/
        ├── main/java/supermoney/recommendation/common/
        │   ├── model/
        │   │   ├── Product.java            ← enum (11 products)
        │   │   ├── HealthTier.java         ← enum (HEALTHY, NEUTRAL, STRESSED)
        │   │   ├── Surface.java            ← enum (5 surfaces)
        │   │   ├── UserFeatures.java       ← POJO: all SMS insights fields + derived fields
        │   │   ├── ScoredCandidate.java    ← POJO: product, propensity_score, pre_surface_score, reason
        │   │   └── Recommendation.java     ← POJO: what the API returns (top-1 per surface call)
        │   └── config/
        │       ├── ScoringConfig.java      ← top-level YAML config POJO
        │       ├── ProductScoringRule.java ← per-product signals, weights, min/max
        │       ├── EligibilityConfig.java  ← per-product eligibility thresholds
        │       ├── FatigueConfig.java      ← max_impressions per product
        │       ├── BusinessPriority.java   ← priority weights per product
        │       └── ConfigLoader.java       ← loads scoring_rules.yaml from classpath
        └── test/java/supermoney/recommendation/common/
            ├── ConfigLoaderTest.java
            └── ModelSerializationTest.java
```

---

## Key Design Decisions

### UserFeatures POJO
- Contains ALL SMS insights columns used anywhere in the pipeline (no raw maps)
- Also contains the 5 derived fields computed by Flink:
  `healthScore`, `surplusCash`, `dtiRatio`, `avgUpiTicket`, `billUrgency`
- Field names match SMS insights column names exactly (makes CSV parsing trivial)
- All fields nullable (`Double`, `Integer`, `Boolean` — not primitives) to handle missing data

### ScoredCandidate POJO
```java
public class ScoredCandidate {
    private Product product;
    private double propensityScore;    // 0–100, before health adjustment
    private double preSurfaceScore;    // final score before surface affinity
    private HealthTier healthTier;
    private List<String> reasonTokens; // e.g. ["income_signal", "clean_history"]
}
```

### Recommendation POJO (API response shape)
```java
public class Recommendation {
    private String userId;
    private Surface surface;
    private Product product;
    private double finalScore;
    private HealthTier healthTier;
    private List<String> reasonTokens;
    private String creativeVariant;
    private String computedAt;
}
```

### YAML Config Structure (scoring_rules.yaml)
```yaml
products:
  PERSONAL_LOAN:
    business_priority: 0.95
    signals:
      - name: income_level
        column: calculated_income_amount_v4
        min: 20000
        max: 150000
        weight: 25
      - name: repayment_cleanliness
        column: binary_clean_credit
        type: binary
        weight: 20
    eligibility:
      min_income: 20000
      max_dti: 0.50
      max_active_loans: 3
    fatigue:
      max_impressions: 3
    health_adjustments:
      HEALTHY: 0
      NEUTRAL: -10
      STRESSED: BLOCKED
```

---

## Classes to Implement

### Product.java
```java
public enum Product {
    UPI_ACTIVATION,
    PERSONAL_LOAN,
    UNSECURED_CARD,
    SECURED_CARD,
    CC_BILL_PAYMENT,
    RENT_PAYMENT,
    BILL_PAYMENTS,
    RECHARGES,
    REFERRALS,
    FIXED_DEPOSIT,
    FLIGHTS;
}
```

### HealthTier.java
```java
public enum HealthTier { HEALTHY, NEUTRAL, STRESSED }
```

### Surface.java
```java
public enum Surface {
    HOME_BANNER,        // S1
    HOME_BOTTOMSHEET,   // S2
    POST_UPI,           // S3
    CASHBACK_REDEEMED,  // S4
    REWARDS_HISTORY     // S5
}
```

### ConfigLoader.java
- Loads `scoring_rules.yaml` using Jackson `ObjectMapper` (YAML flavor)
- Singleton pattern or static factory — no Spring context needed here (used by Flink too)
- Validates required fields on load; throws `IllegalStateException` if misconfigured

---

## Dependencies (common/pom.xml)
```xml
<!-- Jackson for YAML parsing -->
<dependency>
    <groupId>com.fasterxml.jackson.dataformat</groupId>
    <artifactId>jackson-dataformat-yaml</artifactId>
    <version>2.15.2</version>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.15.2</version>
</dependency>
<!-- Lombok (optional, reduces boilerplate on POJOs) -->
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <version>1.18.30</version>
    <scope>provided</scope>
</dependency>
```

---

## Tests

### ConfigLoaderTest.java
- Loads `scoring_rules.yaml` from test resources
- Asserts all 11 products present
- Asserts weights sum > 0 per product
- Asserts business_priority in (0, 1] for all products
- Asserts fatigue max_impressions > 0 for all products

### ModelSerializationTest.java
- Serialize/deserialize `ScoredCandidate` via Jackson
- Serialize/deserialize `UserFeatures` via Jackson
- Asserts round-trip equality

---

## Definition of Done
- [ ] Parent POM created with modules: `common`, `processor`, `serving-api`
- [ ] All enums created
- [ ] `UserFeatures` POJO has all fields used across all 11 scorers + derived fields
- [ ] `ScoredCandidate` and `Recommendation` POJOs created
- [ ] `scoring_rules.yaml` written with all 11 products, signals, weights, eligibility, fatigue config
- [ ] `ConfigLoader` loads and validates YAML without errors
- [ ] All tests pass: `mvn test -pl common`
