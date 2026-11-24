package dev.tomcatdev.hotreload;

import com.intellij.debugger.DebuggerManagerEx;
import com.intellij.debugger.impl.DebuggerSession;
import com.intellij.debugger.settings.DebuggerSettings;
import com.intellij.debugger.ui.HotSwapStatusListener;
import com.intellij.debugger.ui.HotSwapUI;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.KillableProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.CompileScope;
import com.intellij.openapi.compiler.CompilerPaths;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.Alarm;
import com.intellij.util.execution.ParametersListUtil;
import dev.tomcatdev.run.DeploymentResolver;
import dev.tomcatdev.run.TomcatDevConfigurationType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Save → compile module → HotSwap or context reload in Tomcat (fallback: notify/restart). Also runs the Angular watch and the log. */
@Service(Service.Level.PROJECT)
public final class HotReloadService implements Disposable {

    private static final Logger LOG = Logger.getInstance(HotReloadService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Project project;
    private final Alarm compileAlarm;
    private final Set<Module> pendingModules = ConcurrentHashMap.newKeySet();

    private final Deque<String[]> buffer = new ArrayDeque<>();
    private volatile @Nullable ConsoleView console;
    private volatile KillableProcessHandler angularProcess;
    private volatile Notification lastNotification;

    public HotReloadService(@NotNull Project project) {
        this.project = project;
        this.compileAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
    }

    public static HotReloadService getInstance(@NotNull Project project) {
        return project.getService(HotReloadService.class);
    }

    private HotReloadSettings.S s() {
        return HotReloadSettings.get(project);
    }

    // ================================================================== lifecycle

    public void onProjectOpened() {
        log("Dev Runner for Apache Tomcat ready — hot reload " + (s().enabled ? "ON" : "OFF"));
        settingsChanged();
    }

    public void settingsChanged() {
        if (s().enabled) takeOverIdeHotSwap();
    }

    /** Turns off the IDE's own HotSwap-after-compile to avoid double reloads / prompts; the plugin does the HotSwap. */
    private void takeOverIdeHotSwap() {
        ApplicationManager.getApplication().invokeLater(() -> {
            DebuggerSettings ds = DebuggerSettings.getInstance();
            if (!DebuggerSettings.RUN_HOTSWAP_NEVER.equals(ds.RUN_HOTSWAP_AFTER_COMPILE)) {
                ds.RUN_HOTSWAP_AFTER_COMPILE = DebuggerSettings.RUN_HOTSWAP_NEVER;
                log("Set Settings › Debugger › HotSwap › 'Reload classes after compilation' to Never (the plugin handles HotSwap).");
            }
        });
    }

    @Override
    public void dispose() {
        stopAngularWatch();
    }

    // ================================================================== auto compile

    public void scheduleCompile(@NotNull Module module) {
        HotReloadSettings.S s = s();
        if (!s.enabled || !s.autoCompile) return;
        if (s.onlyWhenRunning && !isTomcatRunning()) return;
        pendingModules.add(module);
        compileAlarm.cancelAllRequests();
        compileAlarm.addRequest(this::compilePending, Math.max(100, s.debounceMs));
    }

    private void compilePending() {
        if (project.isDisposed()) return;
        CompilerManager cm = CompilerManager.getInstance(project);
        if (cm.isCompilationActive()) {
            compileAlarm.addRequest(this::compilePending, 300);
            return;
        }
        Module[] modules = pendingModules.stream().filter(m -> !m.isDisposed()).toArray(Module[]::new);
        pendingModules.clear();
        if (modules.length == 0) return;
        log("Compile: " + Arrays.stream(modules).map(Module::getName).toList());
        CompileScope scope = cm.createModulesCompileScope(modules, false);
        cm.make(scope, null);
    }

    /** Modules compiled since the last reload; used to reload only the webapps that contain them. */
    private final Set<String> dirtyModules = ConcurrentHashMap.newKeySet();

    public void onCompilationFinished(int errors, @NotNull Module[] affected) {
        if (!s().enabled) return;
        for (Module m : affected) dirtyModules.add(m.getName());
        if (errors > 0) {
            warn("Compilation failed (" + errors + " errors) — nothing reloaded.");
            return;
        }
        // Compare the compiled classes on a background thread: IntelliJ may report a HotSwap as successful
        // although a structural change (e.g. a new record component) was not applied.
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<String> structural = shapes.structuralChanges(classDirs());
            ApplicationManager.getApplication().invokeLater(() -> hotSwapOrFallback(structural), project.getDisposed());
        });
    }

    /** Output folders (target/classes) of the modules deployed by the running Tomcat Dev configurations. */
    private List<Path> classDirs() {
        List<Path> out = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (TomcatInfo t : tomcats.values()) for (AppInfo a : t.apps()) names.addAll(a.moduleNames());
        ModuleManager mm = ModuleManager.getInstance(project);
        for (String n : names) {
            Module m = mm.findModuleByName(n);
            String dir = m == null ? null : CompilerPaths.getModuleOutputPath(m, false);
            if (dir != null) out.add(Paths.get(dir));
        }
        return out;
    }

    // ================================================================== HotSwap / restart

    private List<DebuggerSession> debugSessions() {
        List<DebuggerSession> out = new ArrayList<>();
        for (DebuggerSession ds : DebuggerManagerEx.getInstanceEx(project).getSessions()) {
            if (ds.isAttached()) out.add(ds);
        }
        return out;
    }

    private void hotSwapOrFallback(List<String> structural) {
        String mode = s().classChangeMode;
        List<DebuggerSession> sessions = debugSessions();
        if (findTomcatDescriptor() == null && sessions.isEmpty()) return;

        if (HotReloadSettings.MODE_RELOAD.equals(mode)) {
            reloadChangedContexts();
            return;
        }
        if (sessions.isEmpty()) {
            onCannotHotSwap("Tomcat is running in Run mode, so HotSwap is not available.");
            return;
        }
        boolean enhanced = tomcats.values().stream().anyMatch(TomcatInfo::enhancedRedefinition);
        if (!structural.isEmpty() && !enhanced) {
            String which = structural.size() <= 3 ? String.join(", ", structural) : structural.size() + " classes";
            onCannotHotSwap("Structural change in " + which + " (field, method or signature added/removed): HotSwap cannot apply it.");
            return;
        }
        for (DebuggerSession session : sessions) {
            HotSwapUI.getInstance(project).reloadChangedClasses(session, false, new HotSwapStatusListener() {
                public void onSuccess(List<DebuggerSession> list) {
                    dirtyModules.clear();
                    log("HotSwap OK ✔");
                }

                public void onNothingToReload(List<DebuggerSession> list) {
                    log("HotSwap: no changed classes (resources are read by Tomcat directly).");
                }

                public void onCancel(List<DebuggerSession> list) {
                    log("HotSwap cancelled.");
                }

                public void onFailure(List<DebuggerSession> list) {
                    onCannotHotSwap("HotSwap failed (structural class change: added/removed field, method, annotation…).");
                }
            });
        }
    }

    private void onCannotHotSwap(String reason) {
        String mode = s().classChangeMode;
        if (HotReloadSettings.MODE_HOTSWAP_RELOAD.equals(mode)) {
            log(reason + " → reloading the context.");
            reloadChangedContexts();
        } else if (HotReloadSettings.MODE_HOTSWAP_RESTART.equals(mode)) {
            warn(reason + " → restart Tomcat.");
            restartTomcat();
        } else {
            notifyWithRestart(reason + " You can reload the context or restart.");
        }
    }

    // ================================================================== reload context (JMX)

    /** A webapp deployed by a running Tomcat Dev configuration and the IntelliJ modules it contains. */
    public record AppInfo(String contextPath, Set<String> moduleNames) {}

    private record TomcatInfo(int jmxPort, List<AppInfo> apps, boolean enhancedRedefinition) {}

    private final ClassShapes shapes = new ClassShapes();

    private final Map<String, TomcatInfo> tomcats = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean reloading = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean reloadAgain;
    private volatile boolean reloadAgainAll;

    /** Called by TomcatDevState at launch: remembers the JMX port and the webapps of this configuration. */
    public void registerTomcat(String configName, int jmxPort, List<AppInfo> apps) {
        registerTomcat(configName, jmxPort, apps, false);
    }

    /**
     * @param enhancedRedefinition the JVM runs with -XX:+AllowEnhancedClassRedefinition (JetBrains Runtime):
     *                             structural changes can be HotSwapped, no reload needed
     */
    public void registerTomcat(String configName, int jmxPort, List<AppInfo> apps, boolean enhancedRedefinition) {
        tomcats.put(configName, new TomcatInfo(jmxPort, List.copyOf(apps), enhancedRedefinition));
        // shape of the classes the new JVM starts with (compiled by the before-launch build)
        ApplicationManager.getApplication().executeOnPooledThread(() -> shapes.snapshot(classDirs()));
    }

    /** Reloads every webapp of the running Tomcat Dev (toolbar / menu action). */
    public void reloadContext() {
        reloadContexts(true);
    }

    /** Reloads only the webapps containing modules compiled since the last reload (all of them if unknown). */
    public void reloadChangedContexts() {
        reloadContexts(false);
    }

    private void reloadContexts(boolean all) {
        ApplicationManager.getApplication().invokeLater(() -> {
            RunContentDescriptor d = findTomcatDescriptor();
            if (d == null) {
                warn("No running Tomcat Dev to reload.");
                return;
            }
            TomcatInfo info = tomcats.get(d.getDisplayName());
            if (info == null) {
                warn("No JMX port known for '" + d.getDisplayName() + "' (started with an older plugin version?) → restarting.");
                restartDescriptor(d);
                return;
            }
            if (!reloading.compareAndSet(false, true)) {
                reloadAgain = true;   // reload in progress: fold the new compilation into one more reload
                reloadAgainAll |= all;
                return;
            }
            Set<String> changed = new HashSet<>(dirtyModules);
            dirtyModules.clear();
            List<AppInfo> targets = new ArrayList<>();
            for (AppInfo app : info.apps()) {
                if (all || changed.isEmpty() || !Collections.disjoint(app.moduleNames(), changed)) targets.add(app);
            }
            if (targets.isEmpty()) targets.addAll(info.apps());
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    for (AppInfo app : targets) {
                        long t = System.currentTimeMillis();
                        try {
                            log("Reloading context " + displayPath(app.contextPath()) + "…");
                            dev.tomcatdev.run.TomcatContextReloader.reload(info.jmxPort(), app.contextPath());
                            log("Context " + displayPath(app.contextPath()) + " reloaded in " + (System.currentTimeMillis() - t) + " ms ✔");
                        } catch (Exception e) {
                            notifyWithRestart("Context reload failed for " + displayPath(app.contextPath()) + ": " + e.getMessage());
                        }
                    }
                    if (targets.size() < info.apps().size()) {
                        log("Not reloaded (no changed module): " + info.apps().stream().filter(a -> !targets.contains(a))
                                .map(a -> displayPath(a.contextPath())).toList());
                    }
                } finally {
                    reloading.set(false);
                    if (reloadAgain) {
                        boolean again = reloadAgainAll;
                        reloadAgain = false;
                        reloadAgainAll = false;
                        reloadContexts(again);
                    }
                }
            });
        }, project.getDisposed());
    }

    private static String displayPath(String p) {
        return p == null || p.isBlank() || p.equals("/") ? "/" : (p.startsWith("/") ? p : "/" + p);
    }

    public void restartTomcat() {
        ApplicationManager.getApplication().invokeLater(() -> restartDescriptor(findTomcatDescriptor()), project.getDisposed());
    }

    private void restartDescriptor(@Nullable RunContentDescriptor d) {
        if (d == null) {
            warn("No running Tomcat Dev to restart.");
            return;
        }
        log("Restart: " + d.getDisplayName());
        ExecutionUtil.restart(d);
    }

    private Set<String> tomcatConfigNames() {
        Set<String> names = new HashSet<>();
        for (RunnerAndConfigurationSettings st : RunManager.getInstance(project).getAllSettings()) {
            if (TomcatDevConfigurationType.ID.equals(st.getType().getId())) names.add(st.getName());
        }
        return names;
    }

    public @Nullable RunContentDescriptor findTomcatDescriptor() {
        Set<String> names = tomcatConfigNames();
        for (RunContentDescriptor d : RunContentManager.getInstance(project).getAllDescriptors()) {
            if (d.getProcessHandler() == null || d.getProcessHandler().isProcessTerminated()) continue;
            if (names.contains(d.getDisplayName())) return d;
        }
        return null;
    }

    public boolean isTomcatRunning() {
        return findTomcatDescriptor() != null || !debugSessions().isEmpty();
    }

    // ================================================================== Angular watch

    public boolean isAngularWatchRunning() {
        KillableProcessHandler h = angularProcess;
        return h != null && !h.isProcessTerminated();
    }

    public void startAngularWatch() {
        if (isAngularWatchRunning()) {
            log("Angular watch is already running.");
            return;
        }
        Path dir = DeploymentResolver.angularProjectDir(project);
        if (dir == null) {
            warn("No Angular project (angular.json) found. Configure it in Settings › Tools › Dev Runner for Apache Tomcat.");
            return;
        }
        String cmd = s().angularWatchCommand;
        List<String> parts = ParametersListUtil.parse(cmd);
        if (parts.isEmpty()) return;
        GeneralCommandLine cl = SystemInfo.isWindows
                ? new GeneralCommandLine("cmd", "/c").withParameters(parts)
                : new GeneralCommandLine(parts);
        cl.withWorkDirectory(dir.toFile())
          .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
          .withEnvironment("NO_COLOR", "1")
          .withEnvironment("FORCE_COLOR", "0")
          .withCharset(StandardCharsets.UTF_8);
        try {
            KillableProcessHandler h = new KillableProcessHandler(cl);
            h.addProcessListener(new ProcessListener() {
                public void startNotified(@NotNull ProcessEvent event) {}

                public void processWillTerminate(@NotNull ProcessEvent event, boolean willBeDestroyed) {}

                public void processTerminated(@NotNull ProcessEvent event) {
                    log("[ng] exited with code " + event.getExitCode());
                }

                public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                    String text = event.getText();
                    if (text == null || text.isBlank()) return;
                    print("[ng] " + text.stripTrailing(), ProcessOutputTypes.STDERR.equals(outputType));
                }
            });
            h.startNotify();
            angularProcess = h;
            log("Started: " + cmd + "  (in " + dir + ")");
        } catch (ExecutionException e) {
            warn("Could not start Angular watch: " + e.getMessage());
        }
    }

    public void stopAngularWatch() {
        KillableProcessHandler h = angularProcess;
        angularProcess = null;
        if (h != null && !h.isProcessTerminated()) {
            h.killProcess();
            log("Angular watch stopped.");
        }
    }

    // ================================================================== log & notify

    public synchronized void attachConsole(@NotNull ConsoleView c) {
        console = c;
        for (String[] line : buffer) {
            c.print(line[0] + "\n", "1".equals(line[1]) ? ConsoleViewContentType.ERROR_OUTPUT : ConsoleViewContentType.NORMAL_OUTPUT);
        }
    }

    public synchronized void detachConsole() {
        console = null;
    }

    public void log(String msg) {
        print(msg, false);
    }

    public void warn(String msg) {
        print(msg, true);
    }

    private static final String HOME = System.getProperty("user.home", "");

    /** Paths under the user's home are logged as ~/… (no personal paths in screenshots). */
    private static String tilde(String msg) {
        return HOME.length() > 1 ? msg.replace(HOME + "/", "~/").replace(HOME + "\\", "~\\") : msg;
    }

    private synchronized void print(String msg, boolean error) {
        String line = LocalTime.now().format(TS) + "  " + tilde(msg);
        buffer.addLast(new String[]{line, error ? "1" : "0"});
        while (buffer.size() > 1000) buffer.removeFirst();
        ConsoleView c = console;
        if (c != null) {
            c.print(line + "\n", error ? ConsoleViewContentType.ERROR_OUTPUT : ConsoleViewContentType.NORMAL_OUTPUT);
        }
        LOG.info(msg);
    }

    private void notifyWithRestart(String msg) {
        warn(msg);
        Notification old = lastNotification;
        if (old != null) old.expire();
        Notification n = NotificationGroupManager.getInstance()
                .getNotificationGroup("Dev Runner for Apache Tomcat")
                .createNotification(msg, NotificationType.WARNING)
                .addAction(NotificationAction.createSimpleExpiring("Reload Context", this::reloadContext))
                .addAction(NotificationAction.createSimpleExpiring("Restart Tomcat", this::restartTomcat));
        lastNotification = n;
        n.notify(project);
    }
}
