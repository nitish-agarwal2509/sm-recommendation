package supermoney.recommendation.processor.flink.function;

import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.configuration.Configuration;
import supermoney.recommendation.common.config.ConfigLoader;
import supermoney.recommendation.common.config.ScoringConfig;
import supermoney.recommendation.common.model.FatigueData;
import supermoney.recommendation.common.model.Product;
import supermoney.recommendation.common.model.ScoredCandidate;
import supermoney.recommendation.common.model.UserFeatures;
import supermoney.recommendation.processor.flink.model.RecoResult;
import supermoney.recommendation.processor.pipeline.RecommendationPipeline;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Flink RichMapFunction that runs the full 8-stage recommendation pipeline for each user.
 *
 * open()  — loads ScoringConfig and initialises RecommendationPipeline once per task slot.
 * map()   — calls RecommendationPipeline.run() for every UserFeatures row.
 *
 * Fatigue data is intentionally empty for batch runs — the Serving API owns the fatigue
 * columns and writes them at serve time. The Flink job only writes reco:candidates.
 */
public class RecommendationMapper extends RichMapFunction<UserFeatures, RecoResult> {

    private final String scoringConfigPath;
    private transient RecommendationPipeline pipeline;

    public RecommendationMapper(String scoringConfigPath) {
        this.scoringConfigPath = scoringConfigPath;
    }

    @Override
    public void open(Configuration parameters) {
        ScoringConfig config = ConfigLoader.loadFromFile(scoringConfigPath);
        this.pipeline = new RecommendationPipeline(config);
    }

    @Override
    public RecoResult map(UserFeatures features) {
        // V0: no fatigue data in batch run — pass empty map
        Map<Product, FatigueData> emptyFatigue = Map.of();
        List<ScoredCandidate> candidates = pipeline.run(features, emptyFatigue);

        return new RecoResult(
            features.getCustomerId(),
            features.getHealthTier(),
            Instant.now().toString(),
            candidates
        );
    }
}
