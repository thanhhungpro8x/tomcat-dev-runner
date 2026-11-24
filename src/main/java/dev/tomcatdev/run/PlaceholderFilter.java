package dev.tomcatdev.run;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maven-style ${...} substitution done by the plugin itself, so templates (context.xml, web.xml, …) can be filtered
 * with values kept outside the project, without running Maven. Plain Java.
 */
public final class PlaceholderFilter {

    private PlaceholderFilter() {}

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}$\\s]+)}");

    /** Placeholders Tomcat or the JVM resolves at runtime; never treated as Maven placeholders. */
    public static boolean isRuntimeProperty(String key) {
        return key.startsWith("catalina.") || key.startsWith("java.") || key.startsWith("user.") || key.startsWith("os.")
               || key.startsWith("env.") || key.equals("line.separator") || key.equals("file.separator");
    }

    /** Keys of Maven-style placeholders (runtime properties excluded). */
    public static Set<String> placeholders(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            if (!isRuntimeProperty(m.group(1))) out.add(m.group(1));
        }
        return out;
    }

    public static boolean hasPlaceholders(Path file) {
        try {
            return !placeholders(Files.readString(file, StandardCharsets.UTF_8)).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** Replaces known placeholders (two passes, so values may reference other keys). Unknown ones are kept. */
    public static String apply(String text, Map<String, String> values) {
        if (values == null || values.isEmpty()) return text;
        String s = text;
        for (int pass = 0; pass < 2; pass++) {
            Matcher m = PLACEHOLDER.matcher(s);
            StringBuilder sb = new StringBuilder();
            boolean changed = false;
            while (m.find()) {
                String key = m.group(1);
                String v = isRuntimeProperty(key) ? null : values.get(key);
                if (v != null) changed = true;
                m.appendReplacement(sb, Matcher.quoteReplacement(v != null ? v : m.group()));
            }
            m.appendTail(sb);
            s = sb.toString();
            if (!changed) break;
        }
        return s;
    }

    /** Reads a .properties file as UTF-8 (falls back to ISO-8859-1), preserving key order. */
    public static LinkedHashMap<String, String> load(Path file) throws IOException {
        Properties p = new Properties() {
            private final Set<Object> order = new LinkedHashSet<>();

            @Override
            public synchronized Object put(Object k, Object v) {
                order.add(k);
                return super.put(k, v);
            }

            @Override
            public Set<Object> keySet() {
                return order;
            }
        };
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (java.nio.charset.MalformedInputException e) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
                p.load(r);
            }
        }
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (Object k : p.keySet()) out.put(String.valueOf(k), p.getProperty(String.valueOf(k)));
        return out;
    }

    /** Text files worth filtering. */
    public static boolean isFilterable(Path file) {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".xml") || n.endsWith(".properties") || n.endsWith(".yml") || n.endsWith(".yaml")
               || n.endsWith(".json") || n.endsWith(".conf") || n.endsWith(".txt");
    }
}
