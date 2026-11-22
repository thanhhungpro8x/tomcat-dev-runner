package dev.tomcatdev.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import dev.tomcatdev.hotreload.HotReloadService;
import org.jetbrains.annotations.NotNull;

public final class StartAngularWatchAction extends BaseAction {
    public StartAngularWatchAction() {
        getTemplatePresentation().setIcon(AllIcons.Actions.Execute);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        e.getPresentation().setEnabled(p != null && !HotReloadService.getInstance(p).isAngularWatchRunning());
    }

    @Override
    protected void perform(@NotNull Project project, @NotNull HotReloadService svc) {
        svc.startAngularWatch();
    }
}
