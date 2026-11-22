package dev.tomcatdev.hotreload;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Finds the Angular project (angular.json) and its build output folder (plain Java). */
public final class AngularLocator {

    private AngularLocator() {}

    private static final Set<String> SKIP = Set.of("node_modules", "target", ".git", ".idea", "dist", "build", ".angular");
    private static final Pattern OUT_STR = Pattern.compile("\"outputPath\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern OUT_OBJ = Pattern.compile("\"outputPath\"\\s*:\\s*\\{[^}]*?\"base\"\\s*:\\s*\"([^\"]+)\"");

    /** Folder containing angular.json, searched up to 4 levels deep, skipping node_modules/target/... */
    public static Path findProjectDir(Path base) {
        if (base == null || !Files.isDirectory(base)) return null;
        final Path[] found = new Path[1];
        try {
            Files.walkFileTree(base, EnumSet.noneOf(FileVisitOption.class), 4, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path n = dir.getFileName();
                    if (!dir.equals(base) && n != null && SKIP.contains(n.toString())) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if ("angular.json".equals(file.getFileName().toString())) {
                        found[0] = file.getParent();
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
        return found[0];
    }

    /** Built static folder: outputPath from angular.json (+ /browser for Angular 17+). */
    public static Path distDir(Path ngDir) {
        if (ngDir == null) return null;
        Path dist = null;
        try {
            String json = Files.readString(ngDir.resolve("angular.json"), StandardCharsets.UTF_8);
            Matcher m = OUT_OBJ.matcher(json);
            if (m.find()) dist = ngDir.resolve(m.group(1));
            else {
                m = OUT_STR.matcher(json);
                if (m.find()) dist = ngDir.resolve(m.group(1));
            }
        } catch (IOException ignored) {
        }
        if (dist == null) {
            dist = ngDir.resolve("dist");
            try (Stream<Path> st = Files.list(dist)) {
                List<Path> subs = st.filter(Files::isDirectory).toList();
                if (subs.size() == 1) dist = subs.get(0);
            } catch (IOException ignored) {
            }
        }
        // Angular 17+ (application builder): <outputPath>/browser.
        // If it has never been built, still assume /browser when the builder is "application".
        Path browser = dist.resolve("browser");
        if (Files.isDirectory(browser) || usesApplicationBuilder(ngDir)) return browser.normalize();
        return dist.normalize();
    }

    private static boolean usesApplicationBuilder(Path ngDir) {
        try {
            String json = Files.readString(ngDir.resolve("angular.json"), StandardCharsets.UTF_8);
            return json.contains(":application\"") && !json.contains("\"browser\": \"\"");
        } catch (IOException e) {
            return false;
        }
    }
}
