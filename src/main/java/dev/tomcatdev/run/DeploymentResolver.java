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
     * Order: explicit → tomcat*-maven-plugin &lt;serverXml&gt; → &lt;war&gt;/src/main/tomcatconf/server.xml
     * → &lt;war&gt;/src/main/tomcat/server.xml → CATALINA_HOME/conf/server.xml.
     */
    public static Path resolveServerXml(TomcatDevOptions o, @Nullable MavenProject war, Path tomcatHome, Consumer<String> log) {
        if (!o.serverXml.isBlank()) return checked(Paths.get(o.serverXml.trim()), "server.xml (configured)", log);
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
     * Order (same precedence as Tomcat itself):
     * explicit → CATALINA_HOME/conf/&lt;Engine&gt;/&lt;Host&gt;/&lt;name&gt;.xml → tomcat*-maven-plugin &lt;contextFile&gt;
     * → maven-war-plugin &lt;containerConfigXML&gt; → target/&lt;finalName&gt;/META-INF/context.xml (filtered) → &lt;webapp&gt;/META-INF/context.xml → src/main/resources/META-INF/context.xml.
     */
    public static @Nullable Path resolveContextXml(TomcatDevOptions o, @Nullable MavenProject war, @Nullable Path webappDir,
                                                   Path tomcatHome, Path serverXml, Consumer<String> log) {
        if (!o.contextXml.isBlank()) return checked(Paths.get(o.contextXml.trim()), "context.xml (configured)", log);

        CatalinaBaseWriter.EngineHost eh = CatalinaBaseWriter.engineHost(serverXml);
        String name = CatalinaBaseWriter.descriptorName(o.contextPath);
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
        // Copy already filtered by Maven (maven-war-plugin webResources) wins over the template in src
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

    public static CatalinaBaseWriter.Deployment resolve(@NotNull Project project, @NotNull Module module,
                                                        @NotNull TomcatDevOptions o, @Nullable Path contextXml,
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

        Path staticDir = null;
        if (!"-".equals(o.angularDist.trim())) {
            if (!o.angularDist.isBlank()) staticDir = Paths.get(o.angularDist.trim());
            else staticDir = AngularLocator.distDir(angularProjectDir(project));
        }
        List<CatalinaBaseWriter.Overlay> overlays = builtOverlays(war, webappDir, log);
        return new CatalinaBaseWriter.Deployment(o.contextPath, webappDir, classDirs, jars, contextXml,
                staticDir, o.angularMount, overlays);
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
                                                         Consumer<String> log) {
        List<CatalinaBaseWriter.Overlay> out = new ArrayList<>();
        if (war == null || war.getBuildDirectory() == null || war.getFinalName() == null) return out;
        Path exploded = Paths.get(war.getBuildDirectory(), war.getFinalName());
        Path webInf = exploded.resolve("WEB-INF");
        if (!Files.isDirectory(webInf)) {
            if (webappDir != null && hasFilteredTemplates(webappDir.resolve("WEB-INF"))) {
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
