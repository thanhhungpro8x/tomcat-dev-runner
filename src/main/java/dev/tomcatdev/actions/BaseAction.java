package dev.tomcatdev.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import dev.tomcatdev.hotreload.HotReloadService;
import org.jetbrains.annotations.NotNull;

abstract class BaseAction extends DumbAwareAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(e.getProject() != null);
    }

    @Override
    public final void actionPerformed(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        if (p != null) perform(p, HotReloadService.getInstance(p));
    }

    protected abstract void perform(@NotNull Project project, @NotNull HotReloadService svc);
}
