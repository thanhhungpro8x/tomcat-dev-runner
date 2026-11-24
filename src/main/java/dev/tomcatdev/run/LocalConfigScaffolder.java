package dev.tomcatdev.run;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.idea.maven.project.MavenProjectsManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Creates the per-webapp env.properties outside the project (~/.tomcat-dev/&lt;project&gt;/&lt;app&gt;/env.properties)
 * for a Tomcat Dev run configuration, listing the placeholders used by the project's templates with their current
 * values. Only this file is created; context.xml, webapp/, classes/ and lib/ are added by the user when needed.
 * An existing env.properties is never overwritten.
 */
public final class LocalConfigScaffolder {

    private LocalConfigScaffolder() {}

    /** @return the created or existing local folders, main webapp first */
    public static List<Path> scaffold(@NotNull Project project, @NotNull TomcatDevRunConfiguration cfg,
                                      @NotNull Consumer<String> log) throws IOException {
        TomcatDevOptions o = cfg.options();
        List<DeploymentResolver.AppSpec> specs = new ArrayList<>();
        Module main = cfg.module();
        if (main != null) specs.add(new DeploymentResolver.AppSpec(main.getName(), o.contextPath, o.localConfigDir));
        specs.addAll(DeploymentResolver.parseExtraDeployments(o.extraDeployments));

        ModuleManager mm = ModuleManager.getInstance(project);
        MavenProjectsManager mpm = MavenProjectsManager.getInstance(project);
        List<Path> dirs = new ArrayList<>();
        for (DeploymentResolver.AppSpec spec : specs) {
            Module m = mm.findModuleByName(spec.moduleName());
            if (m == null) {
                log.accept("⚠ module '" + spec.moduleName() + "' not found, skipped");
                continue;
            }
            MavenProject war = mpm.findProject(m);
            Path webapp = DeploymentResolver.webappDir(m, war);
            Path dir = DeploymentResolver.localDir(project, spec.localConfigDir(), spec.contextPath());
            Files.createDirectories(dir);
            dirs.add(dir);
            log.accept("local config for " + spec.contextPath() + ": " + dir);

            // env.properties: the placeholders used by the project's templates, with their current values
            Path env = dir.resolve("env.properties");
            if (!Files.exists(env)) {
                Set<String> keys = new TreeSet<>();
                if (webapp != null) {
                    collectKeys(webapp.resolve("META-INF").resolve("context.xml"), keys);
                    Path webInf = webapp.resolve("WEB-INF");
                    if (Files.isDirectory(webInf)) {
                        try (Stream<Path> st = Files.walk(webInf)) {
                            for (Path f : st.filter(Files::isRegularFile).filter(PlaceholderFilter::isFilterable).toList()) {
                                String rel = webInf.relativize(f).toString().replace('\\', '/');
                                if (!rel.startsWith("classes/") && !rel.startsWith("lib/")) collectKeys(f, keys);
                            }
                        }
                    }
                }
                Map<String, String> current = DeploymentResolver.filterValues(war, null, s -> {}).values();
                StringBuilder sb = new StringBuilder();
                sb.append("# Local values for ${...} placeholders of ").append(spec.contextPath()).append('\n')
                  .append("# Kept outside the project: never committed. Highest priority over the Maven filter files.\n")
                  .append("# Changes apply at the next restart of the Tomcat Dev configuration.\n\n");
                if (keys.isEmpty()) sb.append("# (no placeholders found in context.xml / WEB-INF templates)\n");
                for (String k : keys) sb.append(k).append('=').append(escape(current.getOrDefault(k, ""))).append('\n');
                Files.writeString(env, sb.toString(), StandardCharsets.UTF_8);
                log.accept("  created env.properties (" + keys.size() + " keys)");
            }

        }
        return dirs;
    }

    private static void collectKeys(Path file, Set<String> keys) {
        if (!Files.isRegularFile(file)) return;
        try {
            keys.addAll(PlaceholderFilter.placeholders(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException ignored) {
        }
    }

    private static String escape(String v) {
        String s = v.replace("\\", "\\\\").replace("\n", "\\n");
        return s.startsWith(" ") ? "\\" + s : s;
    }

}
