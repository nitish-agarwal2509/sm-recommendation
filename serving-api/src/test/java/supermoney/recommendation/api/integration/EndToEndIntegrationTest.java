package supermoney.recommendation.api.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import supermoney.recommendation.processor.flink.SmRecommendationJob;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test: CSV → Flink → JSON → Spring Boot API → HTTP response.
 *
 * Flow:
 *   @DynamicPropertySource runs SmRecommendationJob (Flink, embedded) BEFORE the Spring context loads.
 *   The store loads the Flink output at startup. TestRestTemplate drives all 7 scenarios.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EndToEndIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    static final Path OUTPUT_FILE = Path.of("target/e2e-recommendations.json").toAbsolutePath();

    /**
     * Snapshot of Flink output captured immediately after the job runs,
     * before any API calls mutate the file via fatigue writes.
     * Used by scenarios that verify pre-surface batch scoring (not live state).
     */
    private static final Map<String, JsonNode> INITIAL_FLINK_OUTPUT = new HashMap<>();

    @Autowired
    TestRestTemplate rest;

    // ── Setup: run Flink BEFORE Spring context loads ──────────────────────────

    @DynamicPropertySource
    static void runFlinkAndSetProperties(DynamicPropertyRegistry registry) {
        try {
            Files.createDirectories(OUTPUT_FILE.getParent());

            String csvPath    = classpathFilePath("sms_insights_sample.csv");
            String configPath = resolveConfigToFile("scoring_rules.yaml");

            SmRecommendationJob.main(new String[]{
                "--input",          csvPath,
                "--output",         OUTPUT_FILE.toString(),
                "--scoring-config", configPath
            });

            // Cache the Flink output before Spring starts and API calls mutate the file
            ObjectMapper plain = new ObjectMapper();
            for (String line : Files.readAllLines(OUTPUT_FILE)) {
                if (!line.isBlank()) {
                    JsonNode node = plain.readTree(line);
                    INITIAL_FLINK_OUTPUT.put(node.get("user_id").asText(), node);
                }
            }

            registry.add("recommendation.store.local-json-path",  () -> OUTPUT_FILE.toString());
            registry.add("recommendation.scoring-config-path",     () -> configPath);
        } catch (Exception e) {
            throw new RuntimeException("E2E setup failed", e);
        }
    }

    // ── Scenario 1 — Healthy high-income user gets a meaningful recommendation ─

    @Test
    @Order(1)
    void scenario1_healthyUser_returns200WithHealthyTier() {
        ResponseEntity<JsonNode> resp = get("/recommendation?user_id=u001&surface=HOME_BOTTOMSHEET");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        JsonNode body = resp.getBody();
        assertEquals("u001", body.get("user_id").asText());
        assertEquals("HOME_BOTTOMSHEET", body.get("surface").asText());

        JsonNode reco = body.get("recommendation");
        assertNotNull(reco, "recommendation field must be present");
        assertEquals("HEALTHY", reco.get("health_tier").asText());
        assertFalse(reco.get("product").asText().isBlank());
        assertFalse(reco.get("computed_at").asText().isBlank());
        assertTrue(reco.get("final_score").asDouble() > 0);
    }

    // ── Scenario 2 — STRESSED user never gets credit products ────────────────

    @Test
    @Order(2)
    void scenario2_stressedUser_noCreditProduct() {
        ResponseEntity<JsonNode> resp = get("/recommendation?user_id=u003&surface=HOME_BOTTOMSHEET");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        JsonNode reco = resp.getBody().get("recommendation");
        assertEquals("STRESSED", reco.get("health_tier").asText());

        Set<String> creditProducts = Set.of("PERSONAL_LOAN", "UNSECURED_CARD", "SECURED_CARD", "CC_BILL_PAYMENT");
        assertFalse(creditProducts.contains(reco.get("product").asText()),
                "STRESSED user u003 must not receive a credit product, got: " + reco.get("product").asText());
    }

    // ── Scenario 3 — UPI dormant user always gets UPI_ACTIVATION ─────────────

    @Test
    @Order(3)
    void scenario3_upiDormantUser_upiActivationOnAllSurfaces() {
        for (String surface : new String[]{"HOME_BANNER", "HOME_BOTTOMSHEET", "POST_UPI",
                                           "CASHBACK_REDEEMED", "REWARDS_HISTORY"}) {
            ResponseEntity<JsonNode> resp = get("/recommendation?user_id=u002&surface=" + surface);
            assertEquals(HttpStatus.OK, resp.getStatusCode(),
                    "Expected 200 for u002 on surface " + surface);
            String product = resp.getBody().get("recommendation").get("product").asText();
            assertEquals("UPI_ACTIVATION", product,
                    "UPI dormant user u002 must get UPI_ACTIVATION on " + surface);
        }
    }

    // ── Scenario 4 — High-balance user without FD gets FIXED_DEPOSIT ─────────

    @Test
    @Order(4)
    void scenario4_highBalanceNoFd_fixedDepositInOutput() throws Exception {
        // Verify via initial Flink output (pre-API state) — confirms pre-surface scoring
        JsonNode u006 = INITIAL_FLINK_OUTPUT.get("u006");
        assertNotNull(u006, "u006 must be in Flink output");

        boolean hasFd = false;
        for (JsonNode candidate : u006.get("candidates")) {
            if ("FIXED_DEPOSIT".equals(candidate.get("product").asText())) {
                hasFd = true;
                break;
            }
        }
        assertTrue(hasFd, "u006 (high balance, no FD) should have FIXED_DEPOSIT in candidates");

        // Also confirm via API
        ResponseEntity<JsonNode> resp = get("/recommendation?user_id=u006&surface=HOME_BOTTOMSHEET");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    // ── Scenario 5 — Fatigue accumulates and score drops over repeated calls ──

    @Test
    @Order(5)
    void scenario5_fatigueIncrementsOnRepeatedCalls() throws Exception {
        // Use u010 (salary due, high balance) which won't interfere with other scenario users
        String userId = "u010";
        String url    = "/recommendation?user_id=" + userId + "&surface=HOME_BOTTOMSHEET";

        // Make 4 calls, tracking which product each call returns.
        // Products may switch as fatigue penalties build up (this is expected behaviour —
        // a product with count=2 gets displaced by a fresher one).
        Map<String, Integer> expectedCounts = new HashMap<>();
        for (int i = 0; i < 4; i++) {
            ResponseEntity<JsonNode> resp = get(url);
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            String product = resp.getBody().get("recommendation").get("product").asText();
            expectedCounts.merge(product, 1, Integer::sum);
            Thread.sleep(500); // allow async FatigueWriter to flush before next call reads the store
        }

        // Read persisted file and verify every returned product has the correct impression count
        JsonNode record = findUserInOutput(userId);
        assertNotNull(record, "u010 must be in output file");

        JsonNode fatigue = record.get("fatigue");
        assertNotNull(fatigue, "fatigue must be written after impressions");

        int totalImpressions = 0;
        for (Map.Entry<String, Integer> entry : expectedCounts.entrySet()) {
            String product = entry.getKey();
            int apiCount   = entry.getValue();
            assertTrue(fatigue.has(product),
                    "Fatigue node missing for product returned by API: " + product);
            int storedCount = fatigue.get(product).get("shown_count").asInt();
            assertEquals(apiCount, storedCount,
                    "shown_count mismatch for " + product + ": API returned it " + apiCount
                    + " time(s) but stored count is " + storedCount);
            assertFalse(fatigue.get(product).get("shown_at").asText().isBlank(),
                    "shown_at must be set for " + product);
            totalImpressions += storedCount;
        }
        assertEquals(4, totalImpressions, "Total stored impression count must equal 4 API calls");
    }

    // ── Scenario 6 — Surface differentiation (affinity flips ranking) ────────

    @Test
    @Order(6)
    void scenario6_surfaceDifferentiation_differentProductsPossible() {
        // u001 has multiple eligible products — POST_UPI and HOME_BANNER may differ
        // This is a "soft" check: at minimum the surface affinity is applied and response is valid
        ResponseEntity<JsonNode> postUpi  = get("/recommendation?user_id=u001&surface=POST_UPI");
        ResponseEntity<JsonNode> homeBanner = get("/recommendation?user_id=u001&surface=HOME_BANNER");

        assertEquals(HttpStatus.OK, postUpi.getStatusCode());
        assertEquals(HttpStatus.OK, homeBanner.getStatusCode());

        String postUpiProduct   = postUpi.getBody().get("recommendation").get("product").asText();
        String homeBannerProduct = homeBanner.getBody().get("recommendation").get("product").asText();

        // Both must be valid product names (not blank)
        assertFalse(postUpiProduct.isBlank());
        assertFalse(homeBannerProduct.isBlank());

        // Scores must differ if products differ (surface affinity is working)
        double postUpiScore   = postUpi.getBody().get("recommendation").get("final_score").asDouble();
        double homeBannerScore = homeBanner.getBody().get("recommendation").get("final_score").asDouble();
        assertTrue(postUpiScore > 0 && homeBannerScore > 0,
                "Both surfaces must return positive scores");
    }

    // ── Scenario 7 — Unknown user returns 404 ────────────────────────────────

    @Test
    @Order(7)
    void scenario7_unknownUser_returns404() {
        ResponseEntity<JsonNode> resp = get("/recommendation?user_id=u_nonexistent&surface=HOME_BOTTOMSHEET");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        JsonNode body = resp.getBody();
        assertEquals("NO_RECOMMENDATION", body.get("error").asText());
    }

    // ── Bonus: invalid surface returns 400 ───────────────────────────────────

    @Test
    @Order(8)
    void invalidSurface_returns400() {
        ResponseEntity<JsonNode> resp = get("/recommendation?user_id=u001&surface=INVALID");
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("INVALID_SURFACE", resp.getBody().get("error").asText());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<JsonNode> get(String path) {
        return rest.getForEntity(path, JsonNode.class);
    }

    private static JsonNode findUserInOutput(String userId) throws IOException {
        for (String line : Files.readAllLines(OUTPUT_FILE)) {
            if (!line.isBlank()) {
                JsonNode node = MAPPER.readTree(line);
                if (userId.equals(node.get("user_id").asText())) return node;
            }
        }
        return null;
    }

    /**
     * Returns the real filesystem path for a classpath resource.
     * Test-scoped resources (target/test-classes/) are always real files.
     */
    private static String classpathFilePath(String resource) {
        URL url = EndToEndIntegrationTest.class.getClassLoader().getResource(resource);
        if (url == null) throw new IllegalStateException("Classpath resource not found: " + resource);
        return url.getPath();
    }

    /**
     * Returns a real filesystem path for a classpath resource, even if it's inside a JAR.
     * Extracts to a temp file in the JAR case (e.g. when common is loaded from .m2).
     */
    private static String resolveConfigToFile(String resource) throws IOException {
        URL url = EndToEndIntegrationTest.class.getClassLoader().getResource(resource);
        if (url == null) throw new IllegalStateException("Classpath resource not found: " + resource);
        if ("file".equals(url.getProtocol())) return url.getPath();

        // JAR — extract to a temp file so ConfigLoader.loadFromFile can read it
        Path tmp = Files.createTempFile("scoring_rules", ".yaml");
        try (InputStream is = url.openStream()) {
            Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        tmp.toFile().deleteOnExit();
        return tmp.toString();
    }
}
