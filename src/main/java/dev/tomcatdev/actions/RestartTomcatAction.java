package dev.tomcatdev.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import dev.tomcatdev.hotreload.HotReloadService;
import org.jetbrains.annotations.NotNull;

public final class RestartTomcatAction extends BaseAction {
    public RestartTomcatAction() {
        getTemplatePresentation().setIcon(AllIcons.Actions.Restart);
    }

    @Override
    protected void perform(@NotNull Project project, @NotNull HotReloadService svc) {
        svc.restartTomcat();
    }
}
