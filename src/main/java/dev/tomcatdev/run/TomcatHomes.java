package dev.tomcatdev.run;

import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/** Finds and identifies local Tomcat installations (plain Java). */
public final class TomcatHomes {

    private TomcatHomes() {}

    public static boolean isValid(Path home) {
        return home != null
               && Files.isRegularFile(home.resolve("bin").resolve("bootstrap.jar"))
               && Files.isRegularFile(home.resolve("lib").resolve("catalina.jar"))
               && Files.isDirectory(home.resolve("conf"));
    }

    /** For example "10.1.34.0"; null if it cannot be read. */
    public static String version(Path home) {
        try (JarFile jar = new JarFile(home.resolve("lib").resolve("catalina.jar").toFile())) {
            ZipEntry e = jar.getEntry("org/apache/catalina/util/ServerInfo.properties");
            if (e == null) return null;
            Properties p = new Properties();
            try (InputStream in = jar.getInputStream(e)) {
                p.load(in);
            }
            return p.getProperty("server.number");
        } catch (Exception ex) {
            return null;
        }
    }

    public static int majorVersion(Path home) {
        String v = version(home);
        if (v == null) return -1;
        try {
            return Integer.parseInt(v.split("\\.")[0]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Common Tomcat install locations, newest version first. */
    public static List<Path> detect() {
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        for (String env : new String[]{"CATALINA_HOME", "TOMCAT_HOME"}) {
            String v = System.getenv(env);
            if (v != null && !v.isBlank()) add(out, Paths.get(v));
        }
        Path home = Paths.get(System.getProperty("user.home"));
        // Homebrew (Apple Silicon + Intel)
        for (String prefix : new String[]{"/opt/homebrew/opt", "/usr/local/opt"}) {
            scan(out, Paths.get(prefix), "tomcat", "libexec");
        }
        for (Path dir : new Path[]{home, home.resolve("apps"), home.resolve("Applications"), home.resolve("tools"),
                Paths.get("/opt"), Paths.get("/usr/local"), Paths.get("/Library"), Paths.get("C:\\")}) {
            scan(out, dir, "tomcat", null);
        }
        List<Path> list = new ArrayList<>(out);
        list.sort(Comparator.comparing((Path p) -> Optional.ofNullable(version(p)).orElse("0"),
                TomcatHomes::compareVersions).reversed());
        return list;
    }

    private static void scan(Set<Path> out, Path dir, String nameContains, String sub) {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!n.contains(nameContains)) continue;
                Path cand = sub == null ? p : p.resolve(sub);
                add(out, cand);
                // layout like ~/apps/tomcat/apache-tomcat-10.1.x
                if (sub == null && !isValid(cand) && Files.isDirectory(cand)) {
                    try (DirectoryStream<Path> inner = Files.newDirectoryStream(cand)) {
                        for (Path q : inner) add(out, q);
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void add(Set<Path> out, Path p) {
        try {
            if (isValid(p)) out.add(p.toRealPath());
        } catch (Exception ignored) {
        }
    }

    static int compareVersions(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? parse(x[i]) : 0, yi = i < y.length ? parse(y[i]) : 0;
            if (xi != yi) return Integer.compare(xi, yi);
        }
        return 0;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*", ""));
        } catch (Exception e) {
            return 0;
        }
    }
}
