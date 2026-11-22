package dev.tomcatdev.hotreload;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A saved Java/resource source file → compile its module (debounced).
 * Files in src/main/webapp and the Angular dist need no handling: Tomcat reads them directly.
 */
public final class SourceChangeListener implements BulkFileListener {

    private final Project project;

    public SourceChangeListener(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public void after(@NotNull List<? extends VFileEvent> events) {
        if (project.isDisposed() || project.isDefault()) return;
        if (!HotReloadSettings.get(project).enabled) return;
        ProjectFileIndex idx = ProjectFileIndex.getInstance(project);
        HotReloadService svc = null;
        for (VFileEvent e : events) {
            if (e instanceof VFileDeleteEvent) continue;
            String path = e.getPath();
            if (path.contains("/node_modules/") || path.contains("/.git/") || path.contains("/.idea/")) continue;
            VirtualFile f = e.getFile();
            if (f == null || !f.isValid() || f.isDirectory()) continue;
            if (!idx.isInSourceContent(f) || idx.isInTestSourceContent(f) || idx.isExcluded(f)) continue;
            Module m = idx.getModuleForFile(f);
            if (m == null) continue;
            if (svc == null) svc = HotReloadService.getInstance(project);
            svc.scheduleCompile(m);
        }
    }
}
