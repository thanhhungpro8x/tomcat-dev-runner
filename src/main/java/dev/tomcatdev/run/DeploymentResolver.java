package dev.tomcatdev.run;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.compiler.CompilerPaths;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.vfs.VirtualFile;
import dev.tomcatdev.hotreload.AngularLocator;
import dev.tomcatdev.hotreload.HotReloadSettings;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.maven.model.MavenPlugin;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.idea.maven.project.MavenProjectsManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;

/**
 * Turns the IntelliJ/Maven project structure into a {@link CatalinaBaseWriter.Deployment} and locates
 * server.xml / context.xml in their conventional locations when the user does not specify them.
 */
public final class DeploymentResolver {

    private DeploymentResolver() {}

    private static final String TOMCAT_MVN_GROUP = "org.apache.tomcat.maven";
    private static final String[] TOMCAT_MVN_PLUGINS = {"tomcat7-maven-plugin", "tomcat8-maven-plugin", "tomcat-maven-plugin"};
    private static final String WAR_GROUP = "org.apache.maven.plugins";
    private static final String WAR_PLUGIN = "maven-war-plugin";

    // ------------------------------------------------------------------ Tomcat home

    public static @Nullable Path resolveTomcatHome(TomcatDevOptions o) {
        if (!o.tomcatHome.isBlank()) {
            Path p = Paths.get(o.tomcatHome.trim());
            return TomcatHomes.isValid(p) ? p : null;
        }
        List<Path> found = TomcatHomes.detect();
        return found.isEmpty() ? null : found.get(0);
    }

    // ------------------------------------------------------------------ server.xml

    /**
     * Order: explicit → ~/.tomcat-dev/&lt;project&gt;/server.xml (local, outside the project)
     * → tomcat*-maven-plugin &lt;serverXml&gt; → &lt;war&gt;/src/main/tomcatconf/server.xml
     * → &lt;war&gt;/src/main/tomcat/server.xml → CATALINA_HOME/conf/server.xml.
     */
    public static Path resolveServerXml(TomcatDevOptions o, @Nullable Path projectLocalRoot, @Nullable MavenProject war,
                                        Path tomcatHome, Consumer<String> log) {
        if (!o.serverXml.isBlank()) return checked(Paths.get(o.serverXml.trim()), "server.xml (configured)", log);
        if (projectLocalRoot != null) {
            Path p = projectLocalRoot.resolve("server.xml");
            if (Files.isRegularFile(p)) return found(p, "local config", log);
        }
        if (war != null) {
            for (String a : TOMCAT_MVN_PLUGINS) {
                Path p = pluginPath(war, TOMCAT_MVN_GROUP, a, "serverXml");
                if (p != null && Files.isRegularFile(p)) return found(p, a + " <serverXml>", log);
            }
            Path dir = Paths.get(war.getDirectory());
            for (String rel : new String[]{"src/main/tomcatconf/server.xml", "src/main/tomcat/server.xml"}) {
                Path p = dir.resolve(rel);
                if (Files.isRegularFile(p)) return found(p, rel, log);
            }
        }
        return found(tomcatHome.resolve("conf").resolve("server.xml"), "CATALINA_HOME/conf", log);
    }

    // ------------------------------------------------------------------ context.xml

    /**
     * Order (same precedence as Tomcat itself, local configuration first):
     * explicit → &lt;local config&gt;/context.xml → CATALINA_HOME/conf/&lt;Engine&gt;/&lt;Host&gt;/&lt;name&gt;.xml → tomcat*-maven-plugin &lt;contextFile&gt;
     * → maven-war-plugin &lt;containerConfigXML&gt; → target/&lt;finalName&gt;/META-INF/context.xml (filtered) → &lt;webapp&gt;/META-INF/context.xml → src/main/resources/META-INF/context.xml.
     */
    public static @Nullable Path resolveContextXml(String explicit, String contextPath, @Nullable Path localDir,
                                                   @Nullable MavenProject war, @Nullable Path webappDir,
                                                   Path tomcatHome, Path serverXml, boolean canFilter,
                                                   Consumer<String> log) {
        if (explicit != null && !explicit.isBlank()) return checked(Paths.get(explicit.trim()), "context.xml (configured)", log);
        if (localDir != null && Files.isRegularFile(localDir.resolve("context.xml"))) {
            return found(localDir.resolve("context.xml"), "local config", log);
        }

        CatalinaBaseWriter.EngineHost eh = CatalinaBaseWriter.engineHost(serverXml);
        String name = CatalinaBaseWriter.descriptorName(contextPath);
        Path homeDescriptor = tomcatHome.resolve("conf").resolve(eh.engine()).resolve(eh.host()).resolve(name + ".xml");
        if (Files.isRegularFile(homeDescriptor)) return found(homeDescriptor, "CATALINA_HOME descriptor", log);

        if (war != null) {
            for (String a : TOMCAT_MVN_PLUGINS) {
                Path p = pluginPath(war, TOMCAT_MVN_GROUP, a, "contextFile");
                if (p != null && Files.isRegularFile(p)) return found(p, a + " <contextFile>", log);
            }
            Path p = pluginPath(war, WAR_GROUP, WAR_PLUGIN, "containerConfigXML");
            if (p != null && Files.isRegularFile(p)) return found(p, "maven-war-plugin <containerConfigXML>", log);
        }
        // The src template is filtered by the plugin when values are available (always up to date).
        // Otherwise the copy already filtered by Maven (maven-war-plugin webResources) wins over the template in src.
        if (canFilter && webappDir != null && hasMavenPlaceholders(webappDir.resolve("META-INF").resolve("context.xml"))) {
            return found(webappDir.resolve("META-INF").resolve("context.xml"), "webapp META-INF template, filtered by the plugin", log);
        }
        if (war != null && war.getBuildDirectory() != null && war.getFinalName() != null) {
            Path p = Paths.get(war.getBuildDirectory(), war.getFinalName(), "META-INF", "context.xml");
            if (Files.isRegularFile(p) && !hasMavenPlaceholders(p)) return found(p, "filtered: target/" + war.getFinalName(), log);
        }
        if (webappDir != null) {
            Path p = webappDir.resolve("META-INF").resolve("context.xml");
            if (Files.isRegularFile(p)) {
                if (hasMavenPlaceholders(p)) {
                    log.accept("⚠ " + p + " is a template with unresolved ${...}. Run "
                               + "'mvn package -DskipTests -Dskip.npm -Dskip.installnodenpm' once to generate the filtered copy in target, "
                               + "or set context.xml in the run configuration");
                }
                return found(p, "webapp META-INF", log);
            }
        }
        if (war != null) {
            Path p = Paths.get(war.getDirectory(), "src", "main", "resources", "META-INF", "context.xml");
            if (Files.isRegularFile(p)) return found(p, "resources META-INF", log);
        }
        return null;
    }

    // ------------------------------------------------------------------ deployment

    /** A resolved webapp: what to write for Tomcat + the modules it contains (to reload only affected webapps). */
    public record ResolvedApp(CatalinaBaseWriter.Deployment deployment, Set<String> moduleNames) {}

    public static ResolvedApp resolve(@NotNull Project project, @NotNull Module module, @NotNull String contextPath,
                                      @Nullable Path contextXml, @Nullable Path angularDist, @NotNull String angularMount,
                                      @Nullable Path localDir, @NotNull Map<String, String> filterValues,
                                      Consumer<String> log) {
        MavenProject war = MavenProjectsManager.getInstance(project).findProject(module);
        Path webappDir = webappDir(module, war);

        // WAR module first, then its jar module dependencies (recursively), production runtime scope only
        LinkedHashSet<Module> modules = new LinkedHashSet<>();
        List<String> libPaths = new ArrayList<>();
        ReadAction.run(() -> {
            modules.add(module);
            OrderEnumerator.orderEntries(module).recursively().productionOnly().runtimeOnly()
                    .withoutSdk().withoutLibraries()
                    .forEachModule(m -> {
                        modules.add(m);
                        return true;
                    });
            libPaths.addAll(OrderEnumerator.orderEntries(module).recursively().productionOnly().runtimeOnly()
                    .withoutSdk().librariesOnly().classes().getPathsList().getPathList());
        });

        List<Path> classDirs = new ArrayList<>();
        for (Module m : modules) {
            String out = CompilerPaths.getModuleOutputPath(m, false);
            if (out != null) classDirs.add(Paths.get(out));
        }
        List<Path> jars = libPaths.stream().map(Paths::get).toList();
        log.accept("modules: " + modules.stream().map(Module::getName).toList());

        List<CatalinaBaseWriter.Overlay> overlays = builtOverlays(war, webappDir, !filterValues.isEmpty(), log);
        CatalinaBaseWriter.Deployment d = new CatalinaBaseWriter.Deployment(contextPath, webappDir, classDirs, jars,
                contextXml, angularDist, angularMount, overlays, localDir, filterValues);
        Set<String> names = new LinkedHashSet<>();
        for (Module m : modules) names.add(m.getName());
        return new ResolvedApp(d, names);
    }

    /** Angular dist for the primary webapp: configured, detected from angular.json, or null ("-"). */
    public static @Nullable Path angularDist(@NotNull Project project, @NotNull TomcatDevOptions o) {
        String v = o.angularDist == null ? "" : o.angularDist.trim();
        if ("-".equals(v)) return null;
        if (!v.isEmpty()) return Paths.get(v);
        return AngularLocator.distDir(angularProjectDir(project));
    }

    // ------------------------------------------------------------------ local configuration outside the project

    /** ~/.tomcat-dev/&lt;project&gt; */
    public static Path projectLocalRoot(@NotNull Project project) {
        return Paths.get(System.getProperty("user.home"), ".tomcat-dev", sanitize(project.getName()));
    }

    /** Configured folder, or ~/.tomcat-dev/&lt;project&gt;/&lt;app&gt; by default (used only if it exists). */
    public static Path localDir(@NotNull Project project, @Nullable String configured, @NotNull String contextPath) {
        if (configured != null && !configured.isBlank()) {
            String c = configured.trim();
            if (c.startsWith("~/")) c = System.getProperty("user.home") + c.substring(1);
            return Paths.get(c);
        }
        return projectLocalRoot(project).resolve(CatalinaBaseWriter.descriptorName(contextPath));
    }

    /** Result of collecting ${...} values: the values and whether any real source (filter file / env.properties) exists. */
    public record FilterValues(Map<String, String> values, boolean hasSources) {}

    /**
     * Values used by the plugin to filter ${...} templates, lowest to highest priority:
     * pom properties and project.* → Maven filter files of the WAR module (build &lt;filters&gt; and maven-war-plugin
     * &lt;filters&gt;, for the Maven profiles active in IntelliJ) → &lt;local config&gt;/env.properties.
     */
    public static FilterValues filterValues(@Nullable MavenProject war, @Nullable Path localDir, Consumer<String> log) {
        Map<String, String> values = new LinkedHashMap<>();
        boolean sources = false;
        if (war != null) {
            try {
                Properties props = war.getProperties();
                if (props != null) for (String k : props.stringPropertyNames()) values.put(k, props.getProperty(k));
            } catch (Throwable ignored) {
            }
            if (war.getMavenId().getGroupId() != null) values.put("project.groupId", war.getMavenId().getGroupId());
            if (war.getMavenId().getArtifactId() != null) values.put("project.artifactId", war.getMavenId().getArtifactId());
            if (war.getMavenId().getVersion() != null) values.put("project.version", war.getMavenId().getVersion());
            if (war.getFinalName() != null) values.put("project.build.finalName", war.getFinalName());
            values.put("project.basedir", war.getDirectory());
            values.put("basedir", war.getDirectory());

            LinkedHashSet<String> filterFiles = new LinkedHashSet<>();
            try {
                filterFiles.addAll(war.getFilters());
            } catch (Throwable ignored) {
            }
            try {
                MavenPlugin warPlugin = war.findPlugin(WAR_GROUP, WAR_PLUGIN);
                Element cfg = warPlugin == null ? null : warPlugin.getConfigurationElement();
                Element filters = cfg == null ? null : cfg.getChild("filters");
                if (filters != null) for (Element f : filters.getChildren("filter")) filterFiles.add(f.getTextTrim());
            } catch (Throwable ignored) {
            }
            for (String f : filterFiles) {
                String path = f.replace("${project.basedir}", war.getDirectory()).replace("${basedir}", war.getDirectory());
                if (path.contains("${")) {
                    log.accept("⚠ Maven filter file not resolved by IntelliJ: " + f);
                    continue;
                }
                Path fp = Paths.get(path);
                if (!fp.isAbsolute()) fp = Paths.get(war.getDirectory()).resolve(path);
                if (!Files.isRegularFile(fp)) continue;
                try {
                    values.putAll(PlaceholderFilter.load(fp));
                    sources = true;
                    log.accept("filter values ← " + fp);
                } catch (Exception e) {
                    log.accept("⚠ cannot read " + fp + ": " + e.getMessage());
                }
            }
        }
        if (localDir != null && Files.isRegularFile(localDir.resolve("env.properties"))) {
            try {
                values.putAll(PlaceholderFilter.load(localDir.resolve("env.properties")));
                sources = true;
                log.accept("filter values ← " + localDir.resolve("env.properties") + " (local, highest priority)");
            } catch (Exception e) {
                log.accept("⚠ cannot read " + localDir.resolve("env.properties") + ": " + e.getMessage());
            }
        }
        return new FilterValues(sources ? values : Map.of(), sources);
    }

    static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** One webapp line of the run configuration: "module | /context | local config dir". */
    public record AppSpec(String moduleName, String contextPath, String localConfigDir) {}

    /** Parses the "additional webapps" text: one webapp per line, "module | /context | local config dir (optional)". */
    public static List<AppSpec> parseExtraDeployments(@Nullable String text) {
        List<AppSpec> out = new ArrayList<>();
        if (text == null) return out;
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\|", -1);
            String module = parts[0].trim();
            String ctx = parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : "/" + module;
            if (!ctx.startsWith("/")) ctx = "/" + ctx;
            String dir = parts.length > 2 ? parts[2].trim() : "";
            if (!module.isEmpty()) out.add(new AppSpec(module, ctx, dir));
        }
        return out;
    }

    /**
     * Files in target/&lt;finalName&gt;/WEB-INF (except classes, lib) that must be used instead of src/main/webapp:
     * <ul>
     *   <li>the src copy is a template with ${...} placeholders (filtered by Maven at build time), or</li>
     *   <li>the file exists only in target (generated at build time, e.g. by maven-war-plugin webResources).</li>
     * </ul>
     * Unfiltered files are still read from src, so edits show up immediately.
     */
    static List<CatalinaBaseWriter.Overlay> builtOverlays(@Nullable MavenProject war, @Nullable Path webappDir,
                                                         boolean pluginFilters, Consumer<String> log) {
        List<CatalinaBaseWriter.Overlay> out = new ArrayList<>();
        if (war == null || war.getBuildDirectory() == null || war.getFinalName() == null) return out;
        Path exploded = Paths.get(war.getBuildDirectory(), war.getFinalName());
        Path webInf = exploded.resolve("WEB-INF");
        if (!Files.isDirectory(webInf)) {
            if (!pluginFilters && webappDir != null && hasFilteredTemplates(webappDir.resolve("WEB-INF"))) {
                log.accept("⚠ src/main/webapp/WEB-INF contains ${...} placeholders but " + exploded
                           + " does not exist yet. Run 'mvn package -DskipTests -Dskip.npm -Dskip.installnodenpm' once");
            }
            return out;
        }
        try (var st = Files.walk(webInf)) {
            for (Path built : st.filter(Files::isRegularFile).toList()) {
                String rel = exploded.relativize(built).toString().replace('\\', '/');
                if (rel.startsWith("WEB-INF/classes/") || rel.startsWith("WEB-INF/lib/")) continue;
                Path src = webappDir == null ? null : webappDir.resolve(rel);
                boolean onlyInTarget = src == null || !Files.isRegularFile(src);
                if (onlyInTarget || hasMavenPlaceholders(src)) {
                    out.add(new CatalinaBaseWriter.Overlay(built, "/" + rel));
                }
            }
        } catch (Exception e) {
            log.accept("⚠ cannot read " + webInf + ": " + e.getMessage());
        }
        return out;
    }

    private static boolean hasFilteredTemplates(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try (var st = Files.walk(dir)) {
            return st.filter(Files::isRegularFile).anyMatch(DeploymentResolver::hasMavenPlaceholders);
        } catch (Exception e) {
            return false;
        }
    }

    public static @Nullable Path angularProjectDir(@NotNull Project project) {
        String configured = HotReloadSettings.get(project).angularProjectDir;
        if (configured != null && !configured.isBlank()) return Paths.get(configured.trim());
        return project.getBasePath() == null ? null : AngularLocator.findProjectDir(Paths.get(project.getBasePath()));
    }

    public static @Nullable Path webappDir(@NotNull Module module, @Nullable MavenProject war) {
        if (war != null) {
            Path p = pluginPath(war, WAR_GROUP, WAR_PLUGIN, "warSourceDirectory");
            if (p != null && Files.isDirectory(p)) return p;
            Path def = Paths.get(war.getDirectory(), "src", "main", "webapp");
            return Files.isDirectory(def) ? def : null;
        }
        for (VirtualFile root : ModuleRootManager.getInstance(module).getContentRoots()) {
            Path p = Paths.get(root.getPath(), "src", "main", "webapp");
            if (Files.isDirectory(p)) return p;
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private static @Nullable Path pluginPath(MavenProject mp, String group, String artifact, String child) {
        try {
            MavenPlugin plugin = mp.findPlugin(group, artifact);
            if (plugin == null) return null;
            Element cfg = plugin.getConfigurationElement();
            if (cfg == null) return null;
            String v = cfg.getChildTextTrim(child);
            if (v == null || v.isEmpty()) return null;
            String dir = mp.getDirectory();
            v = v.replace("${project.basedir}", dir).replace("${basedir}", dir);
            if (v.contains("${")) return null;
            Path p = Paths.get(v);
            return p.isAbsolute() ? p.normalize() : Paths.get(dir).resolve(v).normalize();
        } catch (Throwable t) {
            return null;
        }
    }

    /** ${...} placeholders other than the system properties Tomcat resolves itself (catalina.*, java.*, user.*, os.*). */
    static boolean hasMavenPlaceholders(Path p) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\{([^}]+)}").matcher(Files.readString(p));
            while (m.find()) {
                String k = m.group(1);
                if (!(k.startsWith("catalina.") || k.startsWith("java.") || k.startsWith("user.") || k.startsWith("os."))) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static Path found(Path p, String source, Consumer<String> log) {
        log.accept("found " + p.getFileName() + " [" + source + "]: " + p);
        return p;
    }

    private static Path checked(Path p, String source, Consumer<String> log) {
        if (!Files.isRegularFile(p)) throw new IllegalArgumentException(source + " does not exist: " + p);
        return found(p, source, log);
    }
}
