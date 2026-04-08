package supermoney.recommendation.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import supermoney.recommendation.api.service.SurfaceAffinityApplier;
import supermoney.recommendation.common.config.ConfigLoader;
import supermoney.recommendation.common.config.ScoringConfig;
import supermoney.recommendation.common.pipeline.FatigueEvaluator;

/**
 * Spring beans for the scoring/ranking layer.
 * Loaded once at startup; all beans are stateless and thread-safe.
 */
@Configuration
public class ServingApiConfig {

    @Bean
    ScoringConfig scoringConfig(ApiConfig apiConfig) {
        return ConfigLoader.loadFromFile(apiConfig.getScoringConfigPath());
    }

    @Bean
    SurfaceAffinityApplier surfaceAffinityApplier(ScoringConfig scoringConfig) {
        return new SurfaceAffinityApplier(scoringConfig);
    }

    @Bean
    FatigueEvaluator fatigueEvaluator(ScoringConfig scoringConfig) {
        return new FatigueEvaluator(scoringConfig);
    }
}
