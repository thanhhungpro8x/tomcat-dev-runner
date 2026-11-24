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
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.idea.maven.project.MavenProjectsManager;

import javax.swing.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Run configuration form. Conventions used for every field:
 * <ul>
 *   <li>the default value is shown greyed out inside the empty field (placeholder);</li>
 *   <li>the help line below the field: plain text explains, values / paths / examples are in <code>code</code>,
 *       each on its own line;</li>
 *   <li>paths under the user's home are shown as <code>~/…</code> (no personal paths in screenshots).</li>
 * </ul>
 */
public final class TomcatDevSettingsEditor extends SettingsEditor<TomcatDevRunConfiguration> {

    private final Project project;

    private final TextFieldWithBrowseButton tomcatHome = new TextFieldWithBrowseButton();
    private final JBLabel tomcatInfo = hint("");
    private final ModulesComboBox module = new ModulesComboBox();
    private final JBTextField contextPath = new JBTextField();
    private final JBLabel contextInfo = hint("");
    private final JBTextField httpPort = new JBTextField();
    private final TextFieldWithBrowseButton localDir = new TextFieldWithBrowseButton();
    private final JBLabel localInfo = hint("");
    private final JBTextArea extraApps = new JBTextArea(3, 40);
    private final TextFieldWithBrowseButton serverXml = new TextFieldWithBrowseButton();
    private final TextFieldWithBrowseButton contextXml = new TextFieldWithBrowseButton();
    private final TextFieldWithBrowseButton altJre = new TextFieldWithBrowseButton();
    private final RawCommandLineEditor vmOptions = new RawCommandLineEditor();
    private final JBTextField envVars = new JBTextField();
    private final TextFieldWithBrowseButton angularDist = new TextFieldWithBrowseButton();
    private final JBLabel angularInfo = hint("");
    private final JBTextField angularMount = new JBTextField();
    private final JBCheckBox startAngular = new JBCheckBox("Start the Angular watch build together with Tomcat");
    private final JBCheckBox noCache = new JBCheckBox("Disable browser caching of static files");

    TomcatDevSettingsEditor(Project project) {
        this.project = project;
        browse(tomcatHome, FileChooserDescriptorFactory.createSingleFolderDescriptor());
        browse(localDir, FileChooserDescriptorFactory.createSingleFolderDescriptor());
        browse(serverXml, FileChooserDescriptorFactory.createSingleFileDescriptor("xml"));
        browse(contextXml, FileChooserDescriptorFactory.createSingleFileDescriptor("xml"));
        browse(altJre, FileChooserDescriptorFactory.createSingleFolderDescriptor());
        browse(angularDist, FileChooserDescriptorFactory.createSingleFolderDescriptor());
        module.fillModules(project);

        // placeholders: what is used when the field stays empty
        placeholder(tomcatHome, "Auto-detect");
        contextPath.getEmptyText().setText("/");
        httpPort.getEmptyText().setText("From server.xml");
        placeholder(localDir, "~/.tomcat-dev/<project>/<app>");
        extraApps.getEmptyText().setText("admin-web | /admin");
        placeholder(serverXml, "Auto-detect");
        placeholder(contextXml, "Auto-detect");
        placeholder(altJre, "Module SDK");
        vmOptions.getEditorField().getEmptyText().setText("-Xmx2g -Dspring.profiles.active=dev");
        envVars.getEmptyText().setText("KEY=value;OTHER_KEY=value");
        placeholder(angularDist, "From angular.json");
        angularMount.getEmptyText().setText("/");
    }

    private void browse(TextFieldWithBrowseButton f, FileChooserDescriptor d) {
        f.addActionListener(e -> {
            VirtualFile vf = FileChooser.chooseFile(d, project, null);
            if (vf != null) f.setText(tilde(vf.getPath()));
        });
    }

    private static void placeholder(TextFieldWithBrowseButton f, String text) {
        if (f.getTextField() instanceof JBTextField t) t.getEmptyText().setText(text);
    }

    private static JBLabel hint(String html) {
        JBLabel l = new JBLabel(html(html));
        l.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        l.setFontColor(UIUtil.FontColor.BRIGHTER);
        return l;
    }

    private static String html(String body) {
        return body.isEmpty() ? "" : "<html>" + body + "</html>";
    }

    private static String code(String s) {
        return "<code>" + esc(s) + "</code>";
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** /Users/me/x → ~/x */
    static String tilde(String path) {
        String home = System.getProperty("user.home");
        if (path != null && home != null && !home.isEmpty() && (path.equals(home) || path.startsWith(home + "/") || path.startsWith(home + "\\"))) {
            return "~" + path.substring(home.length());
        }
        return path;
    }

    static String tilde(Path p) {
        return p == null ? "" : tilde(p.toString());
    }

    @Override
    protected @NotNull JComponent createEditor() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Tomcat home:", tomcatHome)
                .addComponentToRightColumn(tomcatInfo)
                .addLabeledComponent("WAR module:", module)
                .addLabeledComponent("Context path:", contextPath)
                .addComponentToRightColumn(contextInfo)
                .addLabeledComponent("HTTP port:", httpPort)
                .addComponentToRightColumn(hint("Leave empty to keep the HTTP port defined in server.xml (usually " + code("8080") + ")."))

                .addSeparator()
                .addLabeledComponent("Local config folder:", localDir)
                .addComponentToRightColumn(localInfo)
                .addLabeledComponent("Additional webapps:", new JBScrollPane(extraApps))
                .addComponentToRightColumn(hint(
                        "Other WAR modules deployed on the same Tomcat, one per line. Each one is reloaded separately.<br>"
                        + "Format: " + code("<module name> | <context path> | <local config folder>") + "<br>"
                        + "Example:<br>"
                        + "&nbsp;&nbsp;" + code("admin-web | /admin") + "<br>"
                        + "&nbsp;&nbsp;" + code("reports-web | /reports | ~/configs/reports") + "<br>"
                        + "The local config folder is optional (default: " + code("~/.tomcat-dev/<project>/<app>") + ")."))

                .addSeparator()
                .addLabeledComponent("server.xml:", serverXml)
                .addComponentToRightColumn(hint(
                        "Leave empty to use the first file found:<br>"
                        + "1. " + code("~/.tomcat-dev/<project>/server.xml") + "<br>"
                        + "2. " + code("<serverXml>") + " of tomcat7-maven-plugin<br>"
                        + "3. " + code("src/main/tomcatconf/server.xml") + "<br>"
                        + "4. " + code("CATALINA_HOME/conf/server.xml")))
                .addLabeledComponent("context.xml:", contextXml)
                .addComponentToRightColumn(hint(
                        "Leave empty to use the first file found:<br>"
                        + "1. " + code("<local config folder>/context.xml") + "<br>"
                        + "2. " + code("CATALINA_HOME/conf/Catalina/localhost/<app>.xml") + "<br>"
                        + "3. " + code("<contextFile>") + " / " + code("<containerConfigXML>") + " of the Maven plugins<br>"
                        + "4. " + code("src/main/webapp/META-INF/context.xml") + " (placeholders filtered by the plugin)<br>"
                        + "5. " + code("target/<finalName>/META-INF/context.xml") + " (filtered by Maven)"))

                .addSeparator()
                .addLabeledComponent("Alternative JRE:", altJre)
                .addComponentToRightColumn(hint("Leave empty to run Tomcat with the module SDK. "
                        + "Select a JetBrains Runtime to allow HotSwap of new fields and methods."))
                .addLabeledComponent("VM options:", vmOptions)
                .addComponentToRightColumn(hint("Extra JVM options passed to Tomcat."))
                .addLabeledComponent("Environment variables:", envVars)
                .addComponentToRightColumn(hint("Separated by " + code(";") + ". The IDE environment is inherited."))

                .addSeparator()
                .addLabeledComponent("Angular dist folder:", angularDist)
                .addComponentToRightColumn(angularInfo)
                .addLabeledComponent("Mount Angular at:", angularMount)
                .addComponentToRightColumn(hint("Path inside the webapp where the Angular files are served."))
                .addComponent(startAngular)
                .addComponent(noCache)
                .addComponentToRightColumn(hint("After an Angular rebuild, a normal browser refresh shows the change. "
                        + "Applies to this development Tomcat only."))
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    @Override
    protected void resetEditorFrom(@NotNull TomcatDevRunConfiguration c) {
        TomcatDevOptions o = c.options();
        tomcatHome.setText(tilde(o.tomcatHome));
        Module m = c.module();
        if (m == null) m = guessWarModule();
        module.setSelectedModule(m);
        contextPath.setText(o.contextPath);
        httpPort.setText(o.httpPort);
        localDir.setText(tilde(o.localConfigDir));
        extraApps.setText(o.extraDeployments);
        serverXml.setText(tilde(o.serverXml));
        contextXml.setText(tilde(o.contextXml));
        altJre.setText(tilde(o.alternativeJre));
        vmOptions.setText(o.vmOptions);
        envVars.setText(o.envVars);
        angularDist.setText(tilde(o.angularDist));
        angularMount.setText(o.angularMount);
        startAngular.setSelected(o.startAngularWatch);
        noCache.setSelected(o.disableBrowserCache);
        updateInfo(o);
    }

    @Override
    protected void applyEditorTo(@NotNull TomcatDevRunConfiguration c) {
        TomcatDevOptions o = c.options();
        o.tomcatHome = expand(tomcatHome.getText());
        Module m = module.getSelectedModule();
        o.moduleName = m == null ? "" : m.getName();
        o.contextPath = contextPath.getText().isBlank() ? "/" : contextPath.getText().trim();
        o.httpPort = httpPort.getText().trim();
        o.localConfigDir = localDir.getText().trim();          // "~/" is expanded at launch
        o.extraDeployments = extraApps.getText().trim();
        o.serverXml = expand(serverXml.getText());
        o.contextXml = expand(contextXml.getText());
        o.alternativeJre = expand(altJre.getText());
        o.vmOptions = vmOptions.getText();
        o.envVars = envVars.getText().trim();
        o.angularDist = expand(angularDist.getText());
        o.angularMount = angularMount.getText().isBlank() ? "/" : angularMount.getText().trim();
        o.startAngularWatch = startAngular.isSelected();
        o.disableBrowserCache = noCache.isSelected();
        updateInfo(o);
    }

    /** ~/x → absolute path (other fields are used as real paths). */
    private static String expand(String s) {
        String v = s == null ? "" : s.trim();
        if (v.equals("~") || v.startsWith("~/")) return System.getProperty("user.home") + v.substring(1);
        return v;
    }

    private void updateInfo(TomcatDevOptions o) {
        // Tomcat
        Path home = DeploymentResolver.resolveTomcatHome(o);
        if (home == null) {
            tomcatInfo.setText(html(o.tomcatHome.isBlank()
                    ? "No Tomcat installation was found automatically. Select the Tomcat folder (it contains "
                      + code("bin/") + " and " + code("lib/") + ")."
                    : "This is not a Tomcat folder: " + code("bin/bootstrap.jar") + " or " + code("lib/catalina.jar") + " is missing."));
        } else {
            String v = TomcatHomes.version(home);
            tomcatInfo.setText(html((o.tomcatHome.isBlank() ? "Detected: " + code(tilde(home)) + "<br>" : "")
                                    + "Version: " + code("Tomcat " + (v == null ? "?" : v))));
        }

        // context path
        String ctx = o.contextPath == null || o.contextPath.isBlank() || o.contextPath.equals("/") ? "/" : o.contextPath;
        contextInfo.setText(html("URL path of the webapp, for example " + code("/my-app")
                                 + ".<br>Opens at " + code("http://localhost:<port>" + (ctx.equals("/") ? "/" : ctx + "/"))));

        // local config
        Path ld = DeploymentResolver.localDir(project, o.localConfigDir, ctx);
        boolean exists = Files.isDirectory(ld);
        localInfo.setText(html(
                "Folder outside the project overriding this webapp's configuration (never committed).<br>"
                + (o.localConfigDir.isBlank() ? "Used: " : "Folder: ") + code(tilde(ld)) + "<br>"
                + "Status: " + (exists ? "<b>found</b>" : "<b>not created</b> — create it with "
                                                           + "<i>Tools › Dev Runner for Apache Tomcat › Create Local Config</i>")));

        // Angular
        Path dist = DeploymentResolver.angularDist(project, o);
        if ("-".equals(o.angularDist.trim())) {
            angularInfo.setText(html("Angular is not mounted (" + code("-") + ")."));
        } else {
            angularInfo.setText(html("Built Angular files served by Tomcat. Leave empty to read " + code("outputPath")
                                     + " from " + code("angular.json") + "; enter " + code("-") + " to disable.<br>"
                                     + "Used: " + (dist == null ? "<i>no angular.json found</i>" : code(tilde(dist))
                                     + (Files.isDirectory(dist) ? "" : " <i>(not built yet)</i>"))));
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
