package supermoney.recommendation.processor.flink.io;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Writes JSON strings to a file, one line per record (JSONL format).
 *
 * Why custom sink instead of Flink's writeAsText?
 * - writeAsText is deprecated in Flink 1.18
 * - writeAsText creates a directory (part files) with parallelism > 1
 * - This sink always writes to a single file regardless of parallelism
 *
 * NOT thread-safe — intended for parallelism=1 (local V0 batch mode).
 */
public class JsonFileSink extends RichSinkFunction<String> {

    private final String outputPath;
    private transient BufferedWriter writer;

    public JsonFileSink(String outputPath) {
        this.outputPath = outputPath;
    }

    @Override
    public void open(Configuration parameters) throws IOException {
        Path path = Paths.get(outputPath);
        // Create parent directories if they don't exist
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        writer = Files.newBufferedWriter(path);
    }

    @Override
    public void invoke(String json, Context context) throws IOException {
        writer.write(json);
        writer.newLine();
    }

    @Override
    public void close() throws IOException {
        if (writer != null) {
            writer.flush();
            writer.close();
        }
    }
}
