package supermoney.recommendation.api.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import supermoney.recommendation.api.controller.dto.ErrorResponse;
import supermoney.recommendation.api.controller.dto.RecommendationResponse;
import supermoney.recommendation.api.controller.dto.RecommendationResponse.RecommendationDto;
import supermoney.recommendation.api.service.RecommendationService;
import supermoney.recommendation.api.service.RecommendationService.RecommendationResult;
import supermoney.recommendation.common.model.Surface;

import java.util.Optional;

/**
 * GET /recommendation?user_id=X&surface=Y
 *
 * Returns the top-1 surface-adjusted recommendation for the given user and surface.
 * Writes fatigue data asynchronously on every 200 response.
 */
@RestController
public class RecommendationController {

    private final RecommendationService service;

    public RecommendationController(RecommendationService service) {
        this.service = service;
    }

    @GetMapping("/recommendation")
    public ResponseEntity<?> getRecommendation(
            @RequestParam("user_id") String userId,
            @RequestParam("surface") String surfaceParam) {

        // Validate surface
        Surface surface;
        try {
            surface = Surface.valueOf(surfaceParam.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                    new ErrorResponse("INVALID_SURFACE", "Unknown surface: " + surfaceParam));
        }

        Optional<RecommendationResult> result = service.getRecommendation(userId, surface);

        if (result.isEmpty()) {
            return ResponseEntity.status(404).body(
                    new ErrorResponse(userId, surfaceParam,
                            "NO_RECOMMENDATION",
                            "No eligible recommendation found for this user"));
        }

        RecommendationResult r = result.get();
        RecommendationDto dto = new RecommendationDto(
                r.candidate().getProduct().name(),
                r.surfaceScore(),
                r.candidate().getHealthTier().name(),
                r.candidate().getReasonTokens(),
                r.creativeVariant(),
                r.computedAt()
        );

        return ResponseEntity.ok(new RecommendationResponse(userId, surfaceParam, dto));
    }
}
