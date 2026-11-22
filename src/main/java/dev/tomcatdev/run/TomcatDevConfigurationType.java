package dev.tomcatdev.run;

import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeBase;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public final class TomcatDevConfigurationType extends ConfigurationTypeBase {

    public static final String ID = "TomcatDevRunner";

    public TomcatDevConfigurationType() {
        super(ID, "Tomcat Dev", "Runs a WAR module on a local Tomcat with hot reload for Java and Angular",
              AllIcons.RunConfigurations.Application);
        addFactory(new Factory(this));
    }

    static final class Factory extends ConfigurationFactory {
        Factory(TomcatDevConfigurationType type) {
            super(type);
        }

        @Override
        public @NotNull String getId() {
            return "TomcatDevFactory";
        }

        @Override
        public @NotNull RunConfiguration createTemplateConfiguration(@NotNull Project project) {
            return new TomcatDevRunConfiguration(project, this, "Tomcat Dev");
        }
    }
}
