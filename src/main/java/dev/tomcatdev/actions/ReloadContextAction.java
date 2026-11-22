package dev.tomcatdev.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import dev.tomcatdev.hotreload.HotReloadService;
import org.jetbrains.annotations.NotNull;

/** Reloads the context of the running Tomcat Dev (via JMX): picks up any Java change without restarting the JVM. */
public final class ReloadContextAction extends BaseAction {
    public ReloadContextAction() {
        getTemplatePresentation().setIcon(AllIcons.Actions.ForceRefresh);
    }

    @Override
    protected void perform(@NotNull Project project, @NotNull HotReloadService svc) {
        svc.reloadContext();
    }
}
