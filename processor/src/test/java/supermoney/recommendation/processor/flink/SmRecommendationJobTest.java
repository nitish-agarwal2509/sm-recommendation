package supermoney.recommendation.processor.flink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for the Flink batch job.
 *
 * Runs SmRecommendationJob.main() in local (embedded) mode against the
 * sms_insights_sample.csv fixture, then verifies the JSON output.
 *
 * This exercises the full 8-stage pipeline through real Flink execution:
 *   CSV → UserFeatures → RecommendationPipeline → RecoResult → JSONL
 */
class SmRecommendationJobTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // CSV is always a real file in target/test-classes — url.getPath() is safe
    private static final String CSV_PATH;
    // scoring_rules.yaml may be inside common's JAR in .m2 — extract to temp file if needed
    private static final String CONFIG_PATH;

    static {
        try {
            CSV_PATH    = classpathFilePath("sms_insights_sample.csv");
            CONFIG_PATH = resolveToFile("scoring_rules.yaml");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to resolve test resources", e);
        }
    }

    /** Returns the real filesystem path for a classpath resource that is always a real file. */
    private static String classpathFilePath(String resource) {
        URL url = SmRecommendationJobTest.class.getClassLoader().getResource(resource);
        if (url == null) throw new IllegalStateException("Classpath resource not found: " + resource);
        return url.getPath();
    }

    /**
     * Returns a real filesystem path for a classpath resource, even if it's inside a JAR.
     * Extracts to a temp file when the resource lives in a JAR (e.g. common loaded from .m2).
     */
    private static String resolveToFile(String resource) throws IOException {
        URL url = SmRecommendationJobTest.class.getClassLoader().getResource(resource);
        if (url == null) throw new IllegalStateException("Classpath resource not found: " + resource);
        if ("file".equals(url.getProtocol())) return url.getPath();

        Path tmp = Files.createTempFile("scoring_rules", ".yaml");
        try (InputStream is = url.openStream()) {
            Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        tmp.toFile().deleteOnExit();
        return tmp.toString();
    }

    @Test
    void jobProducesOutputForEveryInputUser(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("recommendations.json");

        runJob(CSV_PATH, outputFile.toString(), CONFIG_PATH);

        List<JsonNode> records = readJsonLines(outputFile);

        // 20 users in sample CSV → 20 output records
        assertEquals(20, records.size(), "Should produce one record per input user");
    }

    @Test
    void everyRecordHasRequiredFields(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("recommendations.json");
        runJob(CSV_PATH, outputFile.toString(), CONFIG_PATH);

        for (JsonNode record : readJsonLines(outputFile)) {
            assertTrue(record.has("user_id"),     "Missing user_id");
            assertTrue(record.has("health_tier"), "Missing health_tier");
            assertTrue(record.has("computed_at"), "Missing computed_at");
            assertTrue(record.has("candidates"),  "Missing candidates");
            assertFalse(record.get("user_id").asText().isBlank(), "user_id should not be blank");
            assertFalse(record.get("computed_at").asText().isBlank(), "computed_at should not be blank");
        }
    }

    @Test
    void candidateCountNeverExceedsFive(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("recommendations.json");
        runJob(CSV_PATH, outputFile.toString(), CONFIG_PATH);

        for (JsonNode record : readJsonLines(outputFile)) {
            int count = record.get("candidates").size();
            assertTrue(count <= 5,
                "Candidates should never exceed 5 for user " + record.get("user_id").asText()
                + ", got: " + count);
        }
    }

    @Test
    void candidateScoresInExpectedRange(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("recommendations.json");
        runJob(CSV_PATH, outputFile.toString(), CONFIG_PATH);

        for (JsonNode record : readJsonLines(outputFile)) {
            for (JsonNode candidate : record.get("candidates")) {
                double propensity = candidate.get("propensity_score").asDouble();
                assertTrue(propensity >= 0.0 && propensity <= 100.0,
                    "propensity_score out of range [0,100]: " + propensity);

                double preSurface = candidate.get("pre_surface_score").asDouble();
                assertTrue(preSurface >= 0.0,
                    "pre_surface_score should be non-negative: " + preSurface);
            }
        }
    }

    @Test
    void u002_upiDormantUser_upiActivationRanksFirst(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("recommendations.json");
        runJob(CSV_PATH, outputFile.toString(), CONFIG_PATH);

        JsonNode u002 = findUser(readJsonLines(outputFile), "u002");
        assertNotNull(u002, "u002 should be in output");

        JsonNode candidates = u002.get("candidates");
        assertFalse(candidates.isEmpty(), "u002 should have at least 1 candidate");

        String topProduct = candidates.get(0).get("product").asText();
        assertEquals("UPI_ACTIVATION", topProduct,
            "UPI dormant user (u002) should have UPI_ACTIVATION ranked first");
    }

    @Test
    void u003_stressedUser_noCreditProducts(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("recommendations.json");
        runJob(CSV_PATH, outputFile.toString(), CONFIG_PATH);

        JsonNode u003 = findUser(readJsonLines(outputFile), "u003");
        assertNotNull(u003, "u003 should be in output");

        Set<String> creditProducts = Set.of(
            "PERSONAL_LOAN", "UNSECURED_CARD", "SECURED_CARD", "CC_BILL_PAYMENT");

        Set<String> u003Products = collectProducts(u003);
        Set<String> intersection = u003Products.stream()
            .filter(creditProducts::contains)
            .collect(Collectors.toSet());

        assertTrue(intersection.isEmpty(),
            "STRESSED user (u003) should have no credit products, found: " + intersection);
    }

    @Test
    void u003_stressedUser_healthTierIsStressed(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("recommendations.json");
        runJob(CSV_PATH, outputFile.toString(), CONFIG_PATH);

        JsonNode u003 = findUser(readJsonLines(outputFile), "u003");
        assertNotNull(u003);
        assertEquals("STRESSED", u003.get("health_tier").asText());
    }

    @Test
    void u001_healthyHighIncomeUser_healthTierIsHealthy(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("recommendations.json");
        runJob(CSV_PATH, outputFile.toString(), CONFIG_PATH);

        JsonNode u001 = findUser(readJsonLines(outputFile), "u001");
        assertNotNull(u001);
        assertEquals("HEALTHY", u001.get("health_tier").asText());
    }

    @Test
    void u006_highBalanceNoFd_fixedDepositInCandidates(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("recommendations.json");
        runJob(CSV_PATH, outputFile.toString(), CONFIG_PATH);

        JsonNode u006 = findUser(readJsonLines(outputFile), "u006");
        assertNotNull(u006, "u006 should be in output");

        Set<String> products = collectProducts(u006);
        assertTrue(products.contains("FIXED_DEPOSIT"),
            "High-balance user without FD (u006) should have FIXED_DEPOSIT in candidates");
    }

    @Test
    void u014_hasFd_fixedDepositNotInCandidates(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("recommendations.json");
        runJob(CSV_PATH, outputFile.toString(), CONFIG_PATH);

        JsonNode u014 = findUser(readJsonLines(outputFile), "u014");
        assertNotNull(u014, "u014 should be in output");

        Set<String> products = collectProducts(u014);
        assertFalse(products.contains("FIXED_DEPOSIT"),
            "User with existing FD (u014) should not receive FIXED_DEPOSIT recommendation");
    }

    @Test
    void u019_highDelinquencyStressedUser_noCreditProducts(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("recommendations.json");
        runJob(CSV_PATH, outputFile.toString(), CONFIG_PATH);

        JsonNode u019 = findUser(readJsonLines(outputFile), "u019");
        assertNotNull(u019, "u019 should be in output");

        assertEquals("STRESSED", u019.get("health_tier").asText(),
            "u019 with high delinquency should be STRESSED");

        Set<String> creditProducts = Set.of(
            "PERSONAL_LOAN", "UNSECURED_CARD", "SECURED_CARD", "CC_BILL_PAYMENT");
        Set<String> intersection = collectProducts(u019).stream()
            .filter(creditProducts::contains)
            .collect(Collectors.toSet());
        assertTrue(intersection.isEmpty(),
            "STRESSED user u019 should have no credit products, found: " + intersection);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void runJob(String csv, String output, String config) throws Exception {
        SmRecommendationJob.main(new String[]{
            "--input",          csv,
            "--output",         output,
            "--scoring-config", config
        });
    }

    private static List<JsonNode> readJsonLines(Path file) throws IOException {
        List<JsonNode> nodes = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (!line.isBlank()) {
                nodes.add(MAPPER.readTree(line));
            }
        }
        return nodes;
    }

    private static JsonNode findUser(List<JsonNode> records, String userId) {
        return records.stream()
            .filter(r -> userId.equals(r.get("user_id").asText()))
            .findFirst()
            .orElse(null);
    }

    private static Set<String> collectProducts(JsonNode record) {
        Set<String> products = new java.util.HashSet<>();
        for (JsonNode candidate : record.get("candidates")) {
            products.add(candidate.get("product").asText());
        }
        return products;
    }
}
