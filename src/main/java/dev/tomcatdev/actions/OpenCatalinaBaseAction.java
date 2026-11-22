package dev.tomcatdev.actions;

import com.intellij.icons.AllIcons;
import com.intellij.ide.actions.RevealFileAction;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.project.Project;
import dev.tomcatdev.hotreload.HotReloadService;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Opens the generated CATALINA_BASE folder (server.xml, context, logs) for inspection. */
public final class OpenCatalinaBaseAction extends BaseAction {
    public OpenCatalinaBaseAction() {
        getTemplatePresentation().setIcon(AllIcons.Actions.MenuOpen);
    }

    @Override
    protected void perform(@NotNull Project project, @NotNull HotReloadService svc) {
        Path dir = Paths.get(PathManager.getSystemPath(), "tomcat-dev", project.getLocationHash());
        if (!Files.isDirectory(dir)) {
            svc.warn("No CATALINA_BASE yet. Run a Tomcat Dev configuration once.");
            return;
        }
        RevealFileAction.openDirectory(dir.toFile());
    }
}
