# Phase 3 — Flink Batch Job

## Goal
Wrap the Phase 2 pipeline logic in a Flink batch job. Flink adds parallelism and the CSV-read → JSON-write I/O layer. The pipeline logic itself is not rewritten — it's called from Flink operators.

---

## Principle
Flink operators are thin wrappers. All logic lives in Phase 2 classes.
`RichMapFunction.open()` → load config once. `RichMapFunction.map()` → call `RecommendationPipeline.run()`.

---

## Module Structure
```
processor/
└── src/
    ├── main/java/supermoney/recommendation/processor/
    │   ├── flink/
    │   │   ├── SmRecommendationJob.java        ← main entry point
    │   │   ├── function/
    │   │   │   ├── CsvToUserFeaturesMapper.java ← MapFunction: CSV line → UserFeatures
    │   │   │   ├── RecommendationMapper.java    ← RichMapFunction: UserFeatures → RecoResult
    │   │   │   └── JsonOutputFormatter.java     ← MapFunction: RecoResult → JSON string
    │   │   └── io/
    │   │       ├── LocalCsvSource.java          ← reads sms_insights_sample.csv
    │   │       └── LocalJsonSink.java           ← writes recommendations.json (one JSON per line)
    └── test/java/supermoney/recommendation/processor/
        └── flink/
            └── SmRecommendationJobTest.java     ← runs full Flink job in local mode
```

---

## Data Flow
```
sms_insights_sample.csv
        │
        │  readTextFile (Flink source)
        ▼
String (raw CSV line)
        │
        │  CsvToUserFeaturesMapper
        ▼
UserFeatures
        │
        │  RecommendationMapper (RichMapFunction)
        │  → loads config in open()
        │  → loads fatigue data from local JSON (if exists) in open()
        │  → calls RecommendationPipeline.run(features, fatigueData)
        ▼
RecoResult { userId, List<ScoredCandidate>, healthTier, computedAt }
        │
        │  JsonOutputFormatter
        ▼
String (JSON line: { "user_id": "...", "candidates": [...], ... })
        │
        │  writeAsText / custom sink
        ▼
recommendations.json (one line per user)
```

---

## Key Classes

### SmRecommendationJob.java
```java
public class SmRecommendationJob {
    public static void main(String[] args) throws Exception {
        JobConfig config = JobConfig.fromArgs(args);  // --input, --output, --scoring-config

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setRuntimeMode(RuntimeExecutionMode.BATCH);
        env.setParallelism(1);  // local mode

        DataStream<String> lines = env.readTextFile(config.getInputPath());

        lines
            .filter(line -> !line.startsWith("user_id"))  // skip CSV header
            .map(new CsvToUserFeaturesMapper())
            .map(new RecommendationMapper(config.getScoringConfigPath()))
            .map(new JsonOutputFormatter())
            .writeAsText(config.getOutputPath(), WriteMode.OVERWRITE);

        env.execute("SM Recommendation — Batch Job");
    }
}
```

### RecommendationMapper.java (RichMapFunction)
```java
public class RecommendationMapper extends RichMapFunction<UserFeatures, RecoResult> {
    private final String scoringConfigPath;
    private RecommendationPipeline pipeline;
    private Map<String, Map<Product, FatigueData>> fatigueStore;

    @Override
    public void open(Configuration parameters) {
        ScoringConfig config = ConfigLoader.load(scoringConfigPath);
        this.pipeline = new RecommendationPipeline(config);
        this.fatigueStore = FatigueLoader.loadFromJson(/* fatigue JSON path */);
    }

    @Override
    public RecoResult map(UserFeatures features) {
        Map<Product, FatigueData> fatigue = fatigueStore.getOrDefault(features.getUserId(), Map.of());
        List<ScoredCandidate> candidates = pipeline.run(features, fatigue);
        return new RecoResult(features.getUserId(), candidates, features.getHealthTier(), Instant.now().toString());
    }
}
```

### CsvToUserFeaturesMapper.java
- Parses a CSV line from `sms_insights_sample.csv`
- Maps column index → `UserFeatures` field (driven by a header line)
- Handles nulls gracefully — missing fields → `null` (not 0, to distinguish absent from zero)
- Uses same column names as the actual BigQuery table (no mapping layer needed for cloud migration)

### LocalJsonSink
- Output format: one JSON object per line (JSONL)
- Does **not** overwrite fatigue columns if `recommendations.json` already exists — merges reco data only
- This mirrors the Bigtable behavior (Flink writes `reco:*` columns, not `fatigue:*` columns)

---

## Local I/O Files

### Input: `sms_insights_sample.csv`
Location: `processor/src/test/resources/sms_insights_sample.csv`
- 20–30 rows covering all health tiers, product eligibility scenarios
- Use actual column names from the real SMS insights schema
- Derive from `docs/sms_insights.csv` sample

### Output: `recommendations.json`
Location: configurable at runtime (default: `processor/output/recommendations.json`)
Sample output line:
```json
{
  "user_id": "u123",
  "health_tier": "NEUTRAL",
  "computed_at": "2026-04-08T02:00:00Z",
  "candidates": [
    { "product": "PERSONAL_LOAN", "propensity_score": 72.5, "pre_surface_score": 83.2, "reason_tokens": ["income_signal", "clean_history"] },
    { "product": "UNSECURED_CARD", "propensity_score": 65.0, "pre_surface_score": 77.4, "reason_tokens": ["income_signal", "rupay_upgrade"] },
    { "product": "FIXED_DEPOSIT",  "propensity_score": 55.0, "pre_surface_score": 60.1, "reason_tokens": ["high_balance"] },
    { "product": "BILL_PAYMENTS",  "propensity_score": 40.0, "pre_surface_score": 52.0, "reason_tokens": ["bills_active"] },
    { "product": "REFERRALS",      "propensity_score": 30.0, "pre_surface_score": 41.5, "reason_tokens": ["digital_savvy"] }
  ]
}
```

---

## pom.xml (processor module additions)
```xml
<!-- Flink streaming Java -->
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-streaming-java</artifactId>
    <version>1.18.1</version>
    <scope>provided</scope>
</dependency>
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-clients</artifactId>
    <version>1.18.1</version>
    <scope>provided</scope>
</dependency>
<!-- For local test execution, Flink needs to be on classpath -->
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-streaming-java</artifactId>
    <version>1.18.1</version>
    <scope>test</scope>
    <classifier>tests</classifier>
</dependency>
```

**Fat JAR:** use `maven-shade-plugin` to package with all dependencies.
Mark Flink deps as `provided` in main scope (Flink cluster provides them), but include for local run.

---

## Running Locally
```bash
# Compile and package
mvn clean package -pl processor -am

# Run (uses exec:exec to avoid classloader issues — same pattern as Flink example)
mvn exec:exec -pl processor \
  -Dexec.args="--input processor/src/test/resources/sms_insights_sample.csv \
               --output processor/output/recommendations.json \
               --scoring-config common/src/main/resources/scoring_rules.yaml"
```

Or run `SmRecommendationJob.main()` directly from IDE with the same args.

---

## Tests

### SmRecommendationJobTest.java
- Runs the full Flink job in local (embedded) mode
- Input: `sms_insights_sample.csv` (test resources)
- Output: in-memory or temp file
- Asserts:
  - Every user in input has an entry in output
  - Each output has 1–5 candidates (never more than 5)
  - UPI Activation ranks #1 when user has no UPI
  - STRESSED users have no credit products in candidates
  - `computed_at` is set on all entries

---

## Definition of Done

**Status: Complete**

- [x] `SmRecommendationJob` runs end-to-end locally without errors
- [x] Input CSV fully parsed (all 69 columns, nulls handled gracefully)
- [x] Output JSONL written (one line per user, snake_case schema)
- [x] Fatigue section preserved across runs (Flink only writes `candidates`/`health_tier`/`computed_at`)
- [x] `SmRecommendationJobTest` passes — 11 tests green (fixed classpath-vs-JAR path resolution)
- [x] Fat JAR produced: `mvn clean package -pl processor -am`
- [x] `mvn test -pl processor` green — 142 tests total

**Run commands:**
```bash
# Via Maven exec plugin (recommended)
mvn exec:exec -pl processor \
  -Dinput=processor/src/test/resources/sms_insights_sample.csv \
  -Doutput=processor/output/recommendations.json \
  -DscoringConfig=common/src/main/resources/scoring_rules.yaml

# Via fat JAR (requires --add-opens for Flink/Kryo)
java --add-opens java.base/java.lang=ALL-UNNAMED \
     --add-opens java.base/java.util=ALL-UNNAMED \
     --add-opens java.base/java.io=ALL-UNNAMED \
     -jar processor/target/processor-1.0-SNAPSHOT.jar \
     --input processor/src/test/resources/sms_insights_sample.csv \
     --output processor/output/recommendations.json \
     --scoring-config common/src/main/resources/scoring_rules.yaml
```
