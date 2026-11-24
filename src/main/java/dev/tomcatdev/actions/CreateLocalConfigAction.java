package dev.tomcatdev.actions;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.icons.AllIcons;
import com.intellij.ide.actions.RevealFileAction;
import com.intellij.openapi.project.Project;
import dev.tomcatdev.hotreload.HotReloadService;
import dev.tomcatdev.run.LocalConfigScaffolder;
import dev.tomcatdev.run.TomcatDevRunConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * Creates ~/.tomcat-dev/&lt;project&gt;/&lt;app&gt;/env.properties for the selected Tomcat Dev run configuration,
 * then opens the folder. An existing env.properties is kept.
 */
public final class CreateLocalConfigAction extends BaseAction {
    public CreateLocalConfigAction() {
        getTemplatePresentation().setIcon(AllIcons.Actions.AddFile);
    }

    @Override
    protected void perform(@NotNull Project project, @NotNull HotReloadService svc) {
        TomcatDevRunConfiguration cfg = pick(project);
        if (cfg == null) {
            svc.warn("Create a Tomcat Dev run configuration first (Run › Edit Configurations… › + › Tomcat Dev).");
            return;
        }
        try {
            List<Path> dirs = LocalConfigScaffolder.scaffold(project, cfg, svc::log);
            if (!dirs.isEmpty()) RevealFileAction.openDirectory(dirs.get(0).toFile());
            svc.log("Local configuration ready. Restart '" + cfg.getName() + "' to apply it.");
        } catch (Exception e) {
            svc.warn("Could not create the local configuration: " + e.getMessage());
        }
    }

    private static @Nullable TomcatDevRunConfiguration pick(Project project) {
        RunManager rm = RunManager.getInstance(project);
        RunnerAndConfigurationSettings selected = rm.getSelectedConfiguration();
        if (selected != null && selected.getConfiguration() instanceof TomcatDevRunConfiguration t) return t;
        for (RunnerAndConfigurationSettings st : rm.getAllSettings()) {
            if (st.getConfiguration() instanceof TomcatDevRunConfiguration t) return t;
        }
        return null;
    }
}
