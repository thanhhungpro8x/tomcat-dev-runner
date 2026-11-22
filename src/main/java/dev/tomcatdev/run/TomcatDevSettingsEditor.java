package dev.tomcatdev.run;

import com.intellij.application.options.ModulesComboBox;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.RawCommandLineEditor;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.idea.maven.project.MavenProjectsManager;

import javax.swing.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public final class TomcatDevSettingsEditor extends SettingsEditor<TomcatDevRunConfiguration> {

    private final Project project;
    private final TextFieldWithBrowseButton tomcatHome = new TextFieldWithBrowseButton();
    private final JBLabel tomcatInfo = hint("");
    private final ModulesComboBox module = new ModulesComboBox();
    private final JBTextField contextPath = new JBTextField();
    private final JBTextField httpPort = new JBTextField();
    private final TextFieldWithBrowseButton serverXml = new TextFieldWithBrowseButton();
    private final TextFieldWithBrowseButton contextXml = new TextFieldWithBrowseButton();
    private final TextFieldWithBrowseButton altJre = new TextFieldWithBrowseButton();
    private final RawCommandLineEditor vmOptions = new RawCommandLineEditor();
    private final JBTextField envVars = new JBTextField();
    private final TextFieldWithBrowseButton angularDist = new TextFieldWithBrowseButton();
    private final JBTextField angularMount = new JBTextField();
    private final JBCheckBox startAngular = new JBCheckBox("Start Angular watch together with Tomcat");
    private final JBCheckBox noCache = new JBCheckBox("Disable browser caching of static files (rebuild Angular, then F5)");

    TomcatDevSettingsEditor(Project project) {
        this.project = project;
        browse(tomcatHome, FileChooserDescriptorFactory.createSingleFolderDescriptor());
        browse(serverXml, FileChooserDescriptorFactory.createSingleFileDescriptor("xml"));
        browse(contextXml, FileChooserDescriptorFactory.createSingleFileDescriptor("xml"));
        browse(altJre, FileChooserDescriptorFactory.createSingleFolderDescriptor());
        browse(angularDist, FileChooserDescriptorFactory.createSingleFolderDescriptor());
        module.fillModules(project);
    }

    private void browse(TextFieldWithBrowseButton f, FileChooserDescriptor d) {
        f.addActionListener(e -> {
            VirtualFile vf = FileChooser.chooseFile(d, project, null);
            if (vf != null) f.setText(vf.getPath());
        });
    }

    private static JBLabel hint(String text) {
        JBLabel l = new JBLabel(text);
        l.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        l.setFontColor(UIUtil.FontColor.BRIGHTER);
        return l;
    }

    @Override
    protected @NotNull JComponent createEditor() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Tomcat home:", tomcatHome)
                .addComponentToRightColumn(tomcatInfo)
                .addLabeledComponent("Module WAR:", module)
                .addLabeledComponent("Context path:", contextPath)
                .addLabeledComponent("HTTP port:", httpPort)
                .addComponentToRightColumn(hint("Empty = keep the port from server.xml"))
                .addSeparator()
                .addLabeledComponent("server.xml:", serverXml)
                .addComponentToRightColumn(hint("Empty = auto-detect: tomcat7-maven-plugin › src/main/tomcatconf › CATALINA_HOME/conf"))
                .addLabeledComponent("context.xml:", contextXml)
                .addComponentToRightColumn(hint("Empty = auto-detect: CATALINA_HOME/conf/Catalina/localhost › Maven plugins › target/<finalName>/META-INF (filtered) › webapp/META-INF"))
                .addSeparator()
                .addLabeledComponent("Alternative JRE:", altJre)
                .addComponentToRightColumn(hint("Empty = module SDK. Use JetBrains Runtime for enhanced HotSwap"))
                .addLabeledComponent("VM options:", vmOptions)
                .addLabeledComponent("Environment:", envVars)
                .addComponentToRightColumn(hint("KEY=VALUE;KEY2=VALUE2"))
                .addSeparator()
                .addLabeledComponent("Angular dist:", angularDist)
                .addComponentToRightColumn(hint("Empty = read angular.json; \"-\" = do not mount Angular"))
                .addLabeledComponent("Mount Angular at:", angularMount)
                .addComponent(startAngular)
                .addComponent(noCache)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    @Override
    protected void resetEditorFrom(@NotNull TomcatDevRunConfiguration c) {
        TomcatDevOptions o = c.options();
        tomcatHome.setText(o.tomcatHome);
        Module m = c.module();
        if (m == null) m = guessWarModule();
        module.setSelectedModule(m);
        contextPath.setText(o.contextPath);
        httpPort.setText(o.httpPort);
        serverXml.setText(o.serverXml);
        contextXml.setText(o.contextXml);
        altJre.setText(o.alternativeJre);
        vmOptions.setText(o.vmOptions);
        envVars.setText(o.envVars);
        angularDist.setText(o.angularDist);
        angularMount.setText(o.angularMount);
        startAngular.setSelected(o.startAngularWatch);
        noCache.setSelected(o.disableBrowserCache);
        updateTomcatInfo(o);
    }

    @Override
    protected void applyEditorTo(@NotNull TomcatDevRunConfiguration c) {
        TomcatDevOptions o = c.options();
        o.tomcatHome = tomcatHome.getText().trim();
        Module m = module.getSelectedModule();
        o.moduleName = m == null ? "" : m.getName();
        o.contextPath = contextPath.getText().isBlank() ? "/" : contextPath.getText().trim();
        o.httpPort = httpPort.getText().trim();
        o.serverXml = serverXml.getText().trim();
        o.contextXml = contextXml.getText().trim();
        o.alternativeJre = altJre.getText().trim();
        o.vmOptions = vmOptions.getText();
        o.envVars = envVars.getText().trim();
        o.angularDist = angularDist.getText().trim();
        o.angularMount = angularMount.getText().isBlank() ? "/" : angularMount.getText().trim();
        o.startAngularWatch = startAngular.isSelected();
        o.disableBrowserCache = noCache.isSelected();
        updateTomcatInfo(o);
    }

    private void updateTomcatInfo(TomcatDevOptions o) {
        Path home = DeploymentResolver.resolveTomcatHome(o);
        if (home == null) {
            tomcatInfo.setText(o.tomcatHome.isBlank() ? "No Tomcat found automatically. Please set one" : "Not a valid Tomcat directory");
        } else {
            String v = TomcatHomes.version(home);
            tomcatInfo.setText((o.tomcatHome.isBlank() ? "Auto-detected: " + home + " — " : "") + "Tomcat " + (v == null ? "?" : v));
        }
    }

    /** The module with war packaging (the first one found). */
    private Module guessWarModule() {
        MavenProjectsManager mpm = MavenProjectsManager.getInstance(project);
        List<MavenProject> wars = mpm.getProjects().stream().filter(p -> "war".equals(p.getPackaging())).toList();
        for (MavenProject w : wars) {
            Module m = mpm.findModule(w);
            if (m != null) return m;
        }
        Module[] all = ModuleManager.getInstance(project).getModules();
        return all.length == 1 ? all[0] : null;
    }

    static Path pathOrNull(String s) {
        return s == null || s.isBlank() ? null : Paths.get(s.trim());
    }
}
