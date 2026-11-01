package com.runaxsoftware.qdrant.jdbc.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads Prometheus' text exposition format, which is what {@code GET /metrics} answers with:
 * a {@code # HELP} and a {@code # TYPE} line per metric, then one line per sample,
 * {@code name{label="value",…} 1.5}. Only {@link SystemTable#METRICS} needs it.
 *
 * <p>A line that doesn't parse is skipped rather than failing the statement: the format is
 * Qdrant's to change, and a metric the driver can't read shouldn't hide the ones it can.
 */
final class PrometheusText {

    /**
     * One sample, as a row of {@link SystemTable#METRICS} needs it.
     *
     * @param name   the sample's own name, {@code _bucket} and {@code _count} suffixes included
     * @param labels the labels as a JSON object, or null when the sample has none
     * @param value  the value, or null when it isn't a number Prometheus allows
     * @param type   the type declared for the metric family, or null when none was declared
     * @param help   the description declared for the metric family, or null when none was
     */
    record Sample(String name, String labels, Double value, String type, String help) {
    }

    /**
     * The suffixes Prometheus gives a histogram's or summary's samples, whose type and help are
     * declared once for the family rather than for each sample.
     */
    private static final List<String> FAMILY_SUFFIXES = List.of("_bucket", "_sum", "_count");

    private static final ObjectMapper JSON = new ObjectMapper();

    private PrometheusText() {
    }

    /**
     * Every sample in a metrics answer, in the order the answer lists them.
     */
    static List<Sample> parse(String body) {
        Map<String, String> types = new HashMap<>();
        Map<String, String> helps = new HashMap<>();
        List<Sample> samples = new ArrayList<>();

        for (String line : body.split("\n")) {
            String text = line.strip();
            if (text.isEmpty()) continue;

            if (text.startsWith("#")) {
                comment(text, types, helps);
                continue;
            }

            Sample sample = sample(text, types, helps);
            if (sample != null) samples.add(sample);
        }

        return samples;
    }

    // "# TYPE name gauge" and "# HELP name the description, which may hold spaces"
    private static void comment(String line, Map<String, String> types, Map<String, String> helps) {
        String[] parts = line.substring(1).strip().split("\\s+", 3);
        if (parts.length < 3) return;

        if (parts[0].equals("TYPE")) types.put(parts[1], parts[2].strip());
        else if (parts[0].equals("HELP")) helps.put(parts[1], parts[2].strip());
    }

    private static Sample sample(String line, Map<String, String> types, Map<String, String> helps) {
        int brace = line.indexOf('{');
        String name;
        String labels;
        String rest;

        if (brace >= 0) {
            int end = closingBrace(line, brace);
            if (end < 0) return null;

            name = line.substring(0, brace);
            labels = labels(line.substring(brace + 1, end));
            rest = line.substring(end + 1).strip();
        } else {
            int space = line.indexOf(' ');
            if (space < 0) return null;

            name = line.substring(0, space);
            labels = null;
            rest = line.substring(space + 1).strip();
        }

        if (name.isEmpty() || rest.isEmpty()) return null;

        // A sample may carry a timestamp after its value; Qdrant sends none, but the format allows it
        String value = rest.split("\\s+", 2)[0];

        return new Sample(name, labels, value(value), declared(types, name), declared(helps, name));
    }

    /**
     * Where the label list ends, skipping the braces a quoted label value may hold.
     */
    private static int closingBrace(String line, int open) {
        boolean quoted = false;

        for (int i = open + 1; i < line.length(); i++) {
            char c = line.charAt(i);

            if (quoted && c == '\\') i++;
            else if (c == '"') quoted = !quoted;
            else if (c == '}' && !quoted) return i;
        }

        return -1;
    }

    /**
     * The labels of one sample as a JSON object, in the order they were written, or null when
     * there are none. A label the driver can't read is left out.
     */
    private static String labels(String text) {
        ObjectNode labels = JSON.createObjectNode();
        int i = 0;

        while (i < text.length()) {
            int equals = text.indexOf('=', i);
            if (equals < 0) break;

            String name = text.substring(i, equals).strip();
            int quote = text.indexOf('"', equals + 1);
            if (quote < 0) break;

            int end = closingQuote(text, quote);
            if (end < 0) break;

            if (!name.isEmpty()) labels.put(name, unescape(text.substring(quote + 1, end)));

            int comma = text.indexOf(',', end + 1);
            if (comma < 0) break;
            i = comma + 1;
        }

        return labels.isEmpty() ? null : labels.toString();
    }

    private static int closingQuote(String text, int open) {
        for (int i = open + 1; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '\\') i++;
            else if (c == '"') return i;
        }

        return -1;
    }

    /**
     * A label value as Prometheus escapes it: a backslash, a double quote, and a newline.
     */
    private static String unescape(String text) {
        if (text.indexOf('\\') < 0) return text;

        StringBuilder value = new StringBuilder(text.length());

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c != '\\' || i + 1 == text.length()) {
                value.append(c);
                continue;
            }

            char escaped = text.charAt(++i);
            value.append(switch (escaped) {
                case 'n' -> '\n';
                case '\\' -> '\\';
                case '"' -> '"';
                default -> escaped;
            });
        }

        return value.toString();
    }

    /**
     * A value, including the infinities and the not-a-number Prometheus spells its own way, or
     * null when it isn't a number at all.
     */
    private static Double value(String text) {
        return switch (text) {
            case "+Inf", "Inf" -> Double.POSITIVE_INFINITY;
            case "-Inf" -> Double.NEGATIVE_INFINITY;
            case "NaN" -> Double.NaN;
            default -> {
                try {
                    yield Double.valueOf(text);
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
        };
    }

    /**
     * What was declared for a sample's metric: for its own name, or for the family a histogram's
     * or summary's samples belong to, which is declared once under the name without the suffix.
     */
    private static String declared(Map<String, String> declarations, String name) {
        String own = declarations.get(name);
        if (own != null) return own;

        for (String suffix : FAMILY_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return declarations.get(name.substring(0, name.length() - suffix.length()));
            }
        }

        return null;
    }
}
