package ua.kpi.km.b14.io;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Запис текстових результатів (протоколів подій) у файл UTF-8. */
public final class TextFile {

    private TextFile() {
    }

    public static void writeLines(Path file, List<String> lines) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
