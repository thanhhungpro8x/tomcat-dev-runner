package dev.tomcatdev.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.*;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.util.xmlb.XmlSerializer;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Being a ModuleRunProfile, the IDE adds a "Build" step before launch and enables both Run and Debug
 * (DefaultJavaProgramRunner / GenericDebuggerRunner), because the state is a JavaCommandLineState.
 */
public final class TomcatDevRunConfiguration extends RunConfigurationBase<Object> implements ModuleRunProfile {

    private TomcatDevOptions options = new TomcatDevOptions();

    TomcatDevRunConfiguration(@NotNull Project project, @NotNull ConfigurationFactory factory, String name) {
        super(project, factory, name);
    }

    public TomcatDevOptions options() {
        return options;
    }

    public @Nullable Module module() {
        if (options.moduleName == null || options.moduleName.isBlank()) return null;
        return ModuleManager.getInstance(getProject()).findModuleByName(options.moduleName);
    }

    /** All modules deployed by this configuration: the main WAR module and the additional webapps. */
    @Override
    public @NotNull Module[] getModules() {
        List<Module> out = new ArrayList<>();
        Module m = module();
        if (m != null) out.add(m);
        ModuleManager mm = ModuleManager.getInstance(getProject());
        for (DeploymentResolver.AppSpec spec : DeploymentResolver.parseExtraDeployments(options.extraDeployments)) {
            Module x = mm.findModuleByName(spec.moduleName());
            if (x != null && !out.contains(x)) out.add(x);
        }
        return out.toArray(Module.EMPTY_ARRAY);
    }

    @Override
    public @NotNull SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
        return new TomcatDevSettingsEditor(getProject());
    }

    @Override
    public void checkConfiguration() throws RuntimeConfigurationException {
        if (module() == null) throw new RuntimeConfigurationError("Select the WAR module to run");
        if (DeploymentResolver.resolveTomcatHome(options) == null) {
            throw new RuntimeConfigurationError(options.tomcatHome.isBlank()
                    ? "No Tomcat installation found automatically. Please set Tomcat home"
                    : "Invalid Tomcat home (bin/bootstrap.jar or lib/catalina.jar is missing)");
        }
        Set<String> paths = new HashSet<>();
        paths.add(CatalinaBaseWriter.descriptorName(options.contextPath));
        ModuleManager mm = ModuleManager.getInstance(getProject());
        for (DeploymentResolver.AppSpec spec : DeploymentResolver.parseExtraDeployments(options.extraDeployments)) {
            if (mm.findModuleByName(spec.moduleName()) == null) {
                throw new RuntimeConfigurationError("Additional webapp: module '" + spec.moduleName() + "' not found");
            }
            if (!paths.add(CatalinaBaseWriter.descriptorName(spec.contextPath()))) {
                throw new RuntimeConfigurationError("Context path " + spec.contextPath() + " is used twice");
            }
        }
        if (!options.httpPort.isBlank()) {
            try {
                Integer.parseInt(options.httpPort.trim());
            } catch (NumberFormatException e) {
                throw new RuntimeConfigurationError("HTTP port must be a number");
            }
        }
    }

    @Override
    public @Nullable RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment env) throws ExecutionException {
        return new TomcatDevState(env, this);
    }

    @Override
    public void readExternal(@NotNull Element element) throws InvalidDataException {
        super.readExternal(element);
        TomcatDevOptions o = new TomcatDevOptions();
        XmlSerializer.deserializeInto(o, element);
        options = o;
    }

    @Override
    public void writeExternal(@NotNull Element element) {
        super.writeExternal(element);
        XmlSerializer.serializeInto(options, element);
    }

    @Override
    public RunConfiguration clone() {
        TomcatDevRunConfiguration c = (TomcatDevRunConfiguration) super.clone();
        TomcatDevOptions o = new TomcatDevOptions();
        Element tmp = new Element("tmp");
        XmlSerializer.serializeInto(options, tmp);
        XmlSerializer.deserializeInto(o, tmp);
        c.options = o;
        return c;
    }
}
