package ua.kpi.km.b14.io;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Таблиця результатів, що записується у CSV (роздільник «,», десяткова крапка). */
public final class CsvTable {

    private final List<String> header;
    private final List<List<String>> rows = new ArrayList<>();

    public CsvTable(String... header) {
        this.header = List.of(header);
    }

    public CsvTable addRow(Object... values) {
        if (values.length != header.size()) {
            throw new IllegalArgumentException("Очікується " + header.size() + " значень, отримано " + values.length);
        }
        List<String> row = new ArrayList<>(values.length);
        for (Object value : values) {
            row.add(format(value));
        }
        rows.add(row);
        return this;
    }

    public void write(Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            List<String> lines = new ArrayList<>();
            lines.add(String.join(",", header));
            rows.forEach(r -> lines.add(String.join(",", r)));
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Текстове подання для консолі: вирівняні стовпці. */
    public String toPrettyString() {
        int[] widths = new int[header.size()];
        for (int c = 0; c < widths.length; c++) {
            widths[c] = header.get(c).length();
            for (List<String> row : rows) {
                widths[c] = Math.max(widths[c], row.get(c).length());
            }
        }
        List<List<String>> all = new ArrayList<>();
        all.add(header);
        all.addAll(rows);
        return all.stream().map(row -> {
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < row.size(); c++) {
                sb.append(String.format("%" + (widths[c] + 2) + "s", row.get(c)));
            }
            return sb.toString();
        }).collect(Collectors.joining(System.lineSeparator()));
    }

    private static String format(Object value) {
        if (value instanceof Double d) {
            return d.isNaN() ? "" : String.format(Locale.ROOT, "%.6g", d);
        }
        String text = String.valueOf(value);
        return text.contains(",") || text.contains("\"") ? '"' + text.replace("\"", "\"\"") + '"' : text;
    }
}
