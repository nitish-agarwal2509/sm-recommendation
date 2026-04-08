package supermoney.recommendation.processor.flink;

/**
 * Parses command-line arguments for SmRecommendationJob.
 *
 * Supported arguments:
 *   --input          path to sms_insights CSV file
 *   --output         path for recommendations.json output
 *   --scoring-config path to scoring_rules.yaml
 *   --fatigue-json   (optional) path to existing recommendations.json with fatigue data
 */
public class JobConfig {

    private final String inputPath;
    private final String outputPath;
    private final String scoringConfigPath;
    private final String fatigueJsonPath;   // nullable — no fatigue in fresh runs

    private JobConfig(String inputPath, String outputPath,
                      String scoringConfigPath, String fatigueJsonPath) {
        this.inputPath        = inputPath;
        this.outputPath       = outputPath;
        this.scoringConfigPath = scoringConfigPath;
        this.fatigueJsonPath  = fatigueJsonPath;
    }

    public static JobConfig fromArgs(String[] args) {
        String input     = null;
        String output    = null;
        String scoring   = null;
        String fatigue   = null;

        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--input"          -> input   = args[i + 1];
                case "--output"         -> output  = args[i + 1];
                case "--scoring-config" -> scoring = args[i + 1];
                case "--fatigue-json"   -> fatigue = args[i + 1];
            }
        }

        if (input == null || output == null || scoring == null) {
            throw new IllegalArgumentException(
                "Usage: SmRecommendationJob " +
                "--input <csv> --output <json> --scoring-config <yaml> " +
                "[--fatigue-json <json>]");
        }

        return new JobConfig(input, output, scoring, fatigue);
    }

    public String getInputPath()        { return inputPath; }
    public String getOutputPath()       { return outputPath; }
    public String getScoringConfigPath() { return scoringConfigPath; }
    public String getFatigueJsonPath()  { return fatigueJsonPath; }
}
