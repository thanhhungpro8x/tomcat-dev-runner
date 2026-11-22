package dev.tomcatdev.hotreload;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;

/** Per-project hot reload settings (stored in workspace.xml, not committed). */
@Service(Service.Level.PROJECT)
@State(name = "TomcatDevHotReload", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class HotReloadSettings implements PersistentStateComponent<HotReloadSettings.S> {

    /** HotSwap; on failure (or when not debugging) → reload the context. */
    public static final String MODE_HOTSWAP_RELOAD = "HOTSWAP_RELOAD";
    /** Always reload the context after each compilation (picks up any change, Spring restarts). */
    public static final String MODE_RELOAD = "RELOAD";
    /** HotSwap; on failure → notify only. */
    public static final String MODE_HOTSWAP_NOTIFY = "HOTSWAP_NOTIFY";
    /** HotSwap; on failure → restart Tomcat. */
    public static final String MODE_HOTSWAP_RESTART = "HOTSWAP_RESTART";
    public static final String[] MODES = {MODE_HOTSWAP_RELOAD, MODE_RELOAD, MODE_HOTSWAP_NOTIFY, MODE_HOTSWAP_RESTART};

    public static final class S {
        public boolean enabled = true;
        public boolean autoCompile = true;
        public boolean onlyWhenRunning = true;
        public int debounceMs = 500;
        public String classChangeMode = MODE_HOTSWAP_RELOAD;
        public String angularProjectDir = "";
        public String angularWatchCommand = "npx ng build --watch --configuration development";
    }

    private S state = new S();

    public static S get(@NotNull Project project) {
        return project.getService(HotReloadSettings.class).state;
    }

    @Override
    public @NotNull S getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull S s) {
        XmlSerializerUtil.copyBean(s, state);
    }
}
