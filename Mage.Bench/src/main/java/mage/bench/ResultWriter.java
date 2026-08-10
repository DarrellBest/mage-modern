package mage.bench;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only JSONL writer, one line per finished game, flushed on every
 * append. Runs are long enough that a crash at game 73 must keep 73 games,
 * and separate JVM processes can write distinct files that merge trivially.
 *
 * @author Darrell Best
 */
public final class ResultWriter implements Closeable {

    private static final Gson GSON = new Gson();

    private final Writer writer;

    public ResultWriter(String path) throws IOException {
        this.writer = new OutputStreamWriter(new FileOutputStream(path, true), StandardCharsets.UTF_8);
    }

    public void append(GameResult result) throws IOException {
        writer.write(GSON.toJson(result));
        writer.write(System.lineSeparator());
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }

    public static List<GameResult> read(String path) throws IOException {
        List<GameResult> results = new ArrayList<>();
        if (!Files.exists(Paths.get(path))) {
            return results;
        }
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(path), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    results.add(GSON.fromJson(line, GameResult.class));
                }
            }
        }
        return results;
    }
}
