package dev.tomcatdev.run;

import com.intellij.execution.CantRunException;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.JavaCommandLineState;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import dev.tomcatdev.hotreload.HotReloadService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.idea.maven.project.MavenProjectsManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Generates CATALINA_BASE, then runs org.apache.catalina.startup.Bootstrap like a regular Java application. */
public final class TomcatDevState extends JavaCommandLineState {

    private final TomcatDevRunConfiguration config;

    TomcatDevState(@NotNull ExecutionEnvironment env, @NotNull TomcatDevRunConfiguration config) {
        super(env);
        this.config = config;
    }

    @Override
    protected JavaParameters createJavaParameters() throws ExecutionException {
        Project project = config.getProject();
        TomcatDevOptions o = config.options();
        HotReloadService svc = HotReloadService.getInstance(project);
        Consumer<String> log = svc::log;

        Module module = config.module();
        if (module == null) throw new CantRunException("No WAR module selected");
        Path home = DeploymentResolver.resolveTomcatHome(o);
        if (home == null) throw new CantRunException("Tomcat not found. Set Tomcat home in the run configuration.");
        int major = TomcatHomes.majorVersion(home);
        log.accept("──────── " + config.getName() + " ────────");
        log.accept("Tomcat " + TomcatHomes.version(home) + " @ " + home);
        if (major > 0 && major < 10) {
            log.accept("⚠ Tomcat " + major + " uses javax.*; Spring Boot 3 / Jakarta EE require Tomcat 10+");
        }

        Path base = Paths.get(PathManager.getSystemPath(), "tomcat-dev", project.getLocationHash(), sanitize(config.getName()));
        List<HotReloadService.AppInfo> apps = new ArrayList<>();
        try {
            MavenProjectsManager mpm = MavenProjectsManager.getInstance(project);
            ModuleManager mm = ModuleManager.getInstance(project);
            Path projectLocal = DeploymentResolver.projectLocalRoot(project);
            Path serverXml = DeploymentResolver.resolveServerXml(o, projectLocal, mpm.findProject(module), home, log);

            // main webapp first, then the additional ones
            List<DeploymentResolver.AppSpec> specs = new ArrayList<>();
            specs.add(new DeploymentResolver.AppSpec(module.getName(), o.contextPath, o.localConfigDir));
            specs.addAll(DeploymentResolver.parseExtraDeployments(o.extraDeployments));

            List<CatalinaBaseWriter.Deployment> deployments = new ArrayList<>();
            for (int i = 0; i < specs.size(); i++) {
                DeploymentResolver.AppSpec spec = specs.get(i);
                boolean main = i == 0;
                Module m = main ? module : mm.findModuleByName(spec.moduleName());
                if (m == null) throw new CantRunException("Module '" + spec.moduleName() + "' not found");
                log.accept("── webapp " + spec.contextPath() + " (" + m.getName() + ")");
                MavenProject war = mpm.findProject(m);
                Path webapp = DeploymentResolver.webappDir(m, war);
                Path localDir = DeploymentResolver.localDir(project, spec.localConfigDir(), spec.contextPath());
                if (Files.isDirectory(localDir)) log.accept("local config: " + localDir);
                else log.accept("local config: none (create " + localDir + " to override settings outside the project)");
                DeploymentResolver.FilterValues fv = DeploymentResolver.filterValues(war, Files.isDirectory(localDir) ? localDir : null, log);
                Path contextXml = DeploymentResolver.resolveContextXml(main ? o.contextXml : "", spec.contextPath(),
                        Files.isDirectory(localDir) ? localDir : null, war, webapp, home, serverXml, fv.hasSources(), log);
                if (contextXml == null) log.accept("no webapp-specific context.xml");
                DeploymentResolver.ResolvedApp app = DeploymentResolver.resolve(project, m, spec.contextPath(), contextXml,
                        main ? DeploymentResolver.angularDist(project, o) : null, main ? o.angularMount : "/",
                        Files.isDirectory(localDir) ? localDir : null, fv.values(), log);
                deployments.add(app.deployment());
                apps.add(new HotReloadService.AppInfo(spec.contextPath(), app.moduleNames()));
            }
            Integer port = o.httpPort.isBlank() ? null : Integer.valueOf(o.httpPort.trim());
            CatalinaBaseWriter.write(home, base, serverXml, port, deployments, o.disableBrowserCache, log);
            log.accept("CATALINA_BASE = " + base);
        } catch (CantRunException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new CantRunException(e.getMessage());
        } catch (Exception e) {
            throw new CantRunException("Could not generate the Tomcat configuration: " + e.getMessage(), e);
        }

        JavaParameters p = new JavaParameters();
        p.setJdk(jdk(project, module, o));
        p.getClassPath().add(home.resolve("bin").resolve("bootstrap.jar").toString());
        p.getClassPath().add(home.resolve("bin").resolve("tomcat-juli.jar").toString());
        p.setMainClass("org.apache.catalina.startup.Bootstrap");
        p.getProgramParametersList().add("start");
        p.setWorkingDirectory(base.toString());

        ParametersList vm = p.getVMParametersList();
        vm.add("-Dcatalina.home=" + home);
        vm.add("-Dcatalina.base=" + base);
        vm.add("-Djava.io.tmpdir=" + base.resolve("temp"));
        vm.add("-Djava.util.logging.manager=org.apache.juli.ClassLoaderLogManager");
        vm.add("-Djava.util.logging.config.file=" + base.resolve("conf").resolve("logging.properties"));
        vm.add("-Djava.protocol.handler.pkgs=org.apache.catalina.webresources");
        vm.add("-Djdk.tls.ephemeralDHKeySize=2048");
        // same as catalina.sh for JDK 9+
        for (String open : new String[]{"java.base/java.lang", "java.base/java.io", "java.base/java.util",
                "java.base/java.util.concurrent", "java.rmi/sun.rmi.transport"}) {
            vm.add("--add-opens=" + open + "=ALL-UNNAMED");
        }
        try {
            int jmxPort = TomcatContextReloader.freePort();
            for (String a : TomcatContextReloader.jvmArgs(jmxPort)) vm.add(a);
            svc.registerTomcat(config.getName(), jmxPort, apps);
            log.accept("JMX (context reload) on 127.0.0.1:" + jmxPort);
        } catch (Exception e) {
            log.accept("⚠ could not enable JMX, context reload will not be available: " + e.getMessage());
        }
        if (!o.vmOptions.isBlank()) vm.addParametersString(o.vmOptions);

        p.setEnv(parseEnv(o.envVars));
        p.setPassParentEnvs(true);

        if (o.startAngularWatch) svc.startAngularWatch();
        return p;
    }

    private static Sdk jdk(Project project, Module module, TomcatDevOptions o) throws CantRunException {
        if (!o.alternativeJre.isBlank()) {
            return JavaSdk.getInstance().createJdk("Tomcat Dev JRE", o.alternativeJre.trim(), false);
        }
        Sdk sdk = ModuleRootManager.getInstance(module).getSdk();
        if (sdk == null) sdk = ProjectRootManager.getInstance(project).getProjectSdk();
        if (sdk == null) throw new CantRunException("No JDK configured for the module/project (or set an Alternative JRE)");
        return sdk;
    }

    static Map<String, String> parseEnv(String s) {
        Map<String, String> env = new LinkedHashMap<>();
        if (s == null) return env;
        for (String part : s.split("[;\\n]")) {
            int i = part.indexOf('=');
            if (i > 0) env.put(part.substring(0, i).trim(), part.substring(i + 1).trim());
        }
        return env;
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
