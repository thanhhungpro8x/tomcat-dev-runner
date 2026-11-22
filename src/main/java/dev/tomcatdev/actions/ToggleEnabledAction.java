package dev.tomcatdev.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.actionSystem.ToggleAction;
import dev.tomcatdev.hotreload.HotReloadService;
import dev.tomcatdev.hotreload.HotReloadSettings;
import org.jetbrains.annotations.NotNull;

public final class ToggleEnabledAction extends ToggleAction implements DumbAware {
    public ToggleEnabledAction() {
        getTemplatePresentation().setIcon(AllIcons.Actions.Lightning);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public boolean isSelected(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        return p != null && HotReloadSettings.get(p).enabled;
    }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
        Project p = e.getProject();
        if (p == null) return;
        HotReloadSettings.get(p).enabled = state;
        HotReloadService svc = HotReloadService.getInstance(p);
        svc.log("Hot reload " + (state ? "BẬT" : "TẮT"));
        svc.settingsChanged();
    }
}
