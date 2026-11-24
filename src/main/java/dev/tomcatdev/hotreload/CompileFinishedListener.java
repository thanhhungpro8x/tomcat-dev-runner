package dev.tomcatdev.hotreload;

import com.intellij.openapi.compiler.CompilationStatusListener;
import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/** After every build (automatic or Ctrl+F9) → push the changes into Tomcat. */
public final class CompileFinishedListener implements CompilationStatusListener {

    private final Project project;

    public CompileFinishedListener(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public void compilationFinished(boolean aborted, int errors, int warnings, @NotNull CompileContext context) {
        if (aborted || project.isDisposed() || context.getProject() != project) return;
        HotReloadService.getInstance(project).onCompilationFinished(errors, context.getCompileScope().getAffectedModules());
    }
}
