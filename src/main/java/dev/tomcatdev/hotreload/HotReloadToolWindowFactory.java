package dev.tomcatdev.hotreload;

import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/** "Tomcat Dev" tool window: log + toolbar. */
public final class HotReloadToolWindowFactory implements ToolWindowFactory, DumbAware {

    private static final String[] ACTIONS = {
            "TomcatDev.Toggle", "TomcatDev.CreateLocalConfig", "TomcatDev.OpenBase",
            "TomcatDev.AngularStart", "TomcatDev.AngularStop", "TomcatDev.Reload", "TomcatDev.Restart"
    };

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ConsoleView console = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
        HotReloadService svc = HotReloadService.getInstance(project);

        SimpleToolWindowPanel panel = new SimpleToolWindowPanel(false, true);
        panel.setContent(console.getComponent());

        ActionManager am = ActionManager.getInstance();
        DefaultActionGroup group = new DefaultActionGroup();
        for (String id : ACTIONS) {
            AnAction a = am.getAction(id);
            if (a != null) group.add(a);
        }
        ActionToolbar tb = am.createActionToolbar("TomcatDevToolbar", group, false);
        tb.setTargetComponent(panel);
        panel.setToolbar(tb.getComponent());

        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setDisposer(() -> {
            svc.detachConsole();
            Disposer.dispose(console);
        });
        toolWindow.getContentManager().addContent(content);
        svc.attachConsole(console);
    }
}
