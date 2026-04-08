package supermoney.recommendation.processor.flink;

import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import supermoney.recommendation.processor.flink.function.CsvToUserFeaturesMapper;
import supermoney.recommendation.processor.flink.function.JsonOutputFormatter;
import supermoney.recommendation.processor.flink.function.RecommendationMapper;
import supermoney.recommendation.processor.flink.io.JsonFileSink;

/**
 * Flink batch job — SM Recommendation Engine V0.
 *
 * Data flow:
 *   sms_insights.csv
 *     → (filter header)
 *     → CsvToUserFeaturesMapper   (CSV line → UserFeatures, normalises fis_affordability_v1)
 *     → RecommendationMapper      (UserFeatures → RecoResult via 8-stage pipeline)
 *     → JsonOutputFormatter       (RecoResult → JSON string)
 *     → JsonFileSink              (writes recommendations.json, one line per user)
 *
 * Running locally:
 *   mvn exec:exec -pl processor \
 *     -Dexec.args="--input processor/src/test/resources/sms_insights_sample.csv \
 *                  --output processor/output/recommendations.json \
 *                  --scoring-config common/src/main/resources/scoring_rules.yaml"
 *
 * Or via the fat JAR:
 *   java -jar processor/target/processor-1.0-SNAPSHOT.jar \
 *     --input ... --output ... --scoring-config ...
 */
public class SmRecommendationJob {

    public static void main(String[] args) throws Exception {
        JobConfig config = JobConfig.fromArgs(args);

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setRuntimeMode(RuntimeExecutionMode.BATCH);
        env.setParallelism(1);  // single-threaded local mode — JsonFileSink is not thread-safe

        env.readTextFile(config.getInputPath())
            .filter(line -> !line.startsWith("customer_id"))   // skip CSV header
            .map(new CsvToUserFeaturesMapper())
            .map(new RecommendationMapper(config.getScoringConfigPath()))
            .map(new JsonOutputFormatter())
            .addSink(new JsonFileSink(config.getOutputPath()));

        env.execute("SM Recommendation — Batch Job V0");
    }
}
