package dev.tomcatdev.hotreload;

import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Objects;

/** Settings › Tools › Dev Runner for Apache Tomcat (hot reload; Tomcat settings live in the run configuration). */
public final class HotReloadConfigurable implements Configurable {

    private final Project project;
    private JBCheckBox enabled, autoCompile, onlyWhenRunning;
    private JBTextField debounce, ngCmd;
    private ComboBox<String> mode;
    private static final String[] MODE_LABELS = {
            "HotSwap, fall back to context reload (recommended)",
            "Always reload the context (picks up any Java change, Spring restarts)",
            "HotSwap, notify on failure",
            "HotSwap, restart Tomcat on failure"};
    private TextFieldWithBrowseButton ngDir;

    public HotReloadConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @Nls String getDisplayName() {
        return "Dev Runner for Apache Tomcat";
    }

    private static JComponent hint(String text) {
        JBLabel l = new JBLabel(text);
        l.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        l.setFontColor(UIUtil.FontColor.BRIGHTER);
        return l;
    }

    @Override
    public @Nullable JComponent createComponent() {
        enabled = new JBCheckBox("Enable hot reload for this project");
        autoCompile = new JBCheckBox("Compile the changed module on save");
        onlyWhenRunning = new JBCheckBox("Only compile on save while Tomcat Dev is running");
        debounce = new JBTextField();
        mode = new ComboBox<>(MODE_LABELS);
        ngDir = new TextFieldWithBrowseButton();
        ngDir.addActionListener(e -> {
            VirtualFile vf = FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFolderDescriptor(), project, null);
            if (vf != null) ngDir.setText(vf.getPath());
        });
        ngCmd = new JBTextField();

        JPanel panel = FormBuilder.createFormBuilder()
                .addComponent(enabled)
                .addComponent(autoCompile)
                .addComponent(onlyWhenRunning)
                .addLabeledComponent("Debounce (ms):", debounce)
                .addLabeledComponent("After compilation:", mode)
                .addComponentToRightColumn(hint("HotSwap only updates method bodies and needs Debug mode. A context reload picks up new fields/methods/endpoints (2–5 s) and also works in Run mode."))
                .addSeparator()
                .addLabeledComponent("Angular project dir:", ngDir)
                .addComponentToRightColumn(hint("Empty = find angular.json in the project"))
                .addLabeledComponent("Angular watch command:", ngCmd)
                .addComponentToRightColumn(hint("Tomcat settings (home, server.xml, context.xml, port…) are in Run › Edit Configurations › Tomcat Dev"))
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        reset();
        return panel;
    }

    private HotReloadSettings.S st() {
        return HotReloadSettings.get(project);
    }

    private String modeValue() {
        int i = Math.max(0, mode.getSelectedIndex());
        return HotReloadSettings.MODES[i];
    }

    private static int modeIndex(String m) {
        for (int i = 0; i < HotReloadSettings.MODES.length; i++) if (HotReloadSettings.MODES[i].equals(m)) return i;
        return 0;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    @Override
    public boolean isModified() {
        HotReloadSettings.S s = st();
        return s.enabled != enabled.isSelected()
               || s.autoCompile != autoCompile.isSelected()
               || s.onlyWhenRunning != onlyWhenRunning.isSelected()
               || s.debounceMs != parseInt(debounce.getText(), s.debounceMs)
               || !Objects.equals(s.classChangeMode, modeValue())
               || !Objects.equals(s.angularProjectDir, ngDir.getText().trim())
               || !Objects.equals(s.angularWatchCommand, ngCmd.getText().trim());
    }

    @Override
    public void apply() {
        HotReloadSettings.S s = st();
        s.enabled = enabled.isSelected();
        s.autoCompile = autoCompile.isSelected();
        s.onlyWhenRunning = onlyWhenRunning.isSelected();
        s.debounceMs = parseInt(debounce.getText(), s.debounceMs);
        s.classChangeMode = modeValue();
        s.angularProjectDir = ngDir.getText().trim();
        s.angularWatchCommand = ngCmd.getText().trim();
        HotReloadService.getInstance(project).settingsChanged();
    }

    @Override
    public void reset() {
        if (enabled == null) return;
        HotReloadSettings.S s = st();
        enabled.setSelected(s.enabled);
        autoCompile.setSelected(s.autoCompile);
        onlyWhenRunning.setSelected(s.onlyWhenRunning);
        debounce.setText(String.valueOf(s.debounceMs));
        mode.setSelectedIndex(modeIndex(s.classChangeMode));
        ngDir.setText(s.angularProjectDir);
        ngCmd.setText(s.angularWatchCommand);
    }
}
