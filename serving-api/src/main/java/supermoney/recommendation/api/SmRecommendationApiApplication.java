package supermoney.recommendation.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import supermoney.recommendation.api.config.ApiConfig;

/**
 * Spring Boot entry point for the SM Recommendation serving API.
 *
 * Reads from processor/output/recommendations.json (produced by the Flink batch job).
 * Applies surface affinity multipliers at serve time and returns the top-1 recommendation.
 * Writes fatigue (shown_count + shown_at) asynchronously on each response.
 *
 * Usage:
 *   mvn spring-boot:run -pl serving-api
 *   curl "http://localhost:8080/recommendation?user_id=u001&surface=HOME_BOTTOMSHEET"
 */
@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties(ApiConfig.class)
public class SmRecommendationApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmRecommendationApiApplication.class, args);
    }
}
