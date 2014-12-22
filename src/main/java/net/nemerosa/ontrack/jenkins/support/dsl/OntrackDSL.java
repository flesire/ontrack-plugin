package net.nemerosa.ontrack.jenkins.support.dsl;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Result;
import net.nemerosa.ontrack.client.OTHttpClientLogger;
import net.nemerosa.ontrack.dsl.Ontrack;
import net.nemerosa.ontrack.dsl.OntrackConnection;
import net.nemerosa.ontrack.dsl.ProjectEntity;
import net.nemerosa.ontrack.jenkins.OntrackConfiguration;
import net.nemerosa.ontrack.jenkins.OntrackPluginSupport;
import org.apache.commons.lang.StringUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class OntrackDSL {

    private final String script;
    private final String injectEnvironment;
    private final String injectProperties;
    private final boolean ontrackLog;

    public OntrackDSL(String script, String injectEnvironment, String injectProperties, boolean ontrackLog) {
        this.script = script;
        this.injectEnvironment = injectEnvironment;
        this.injectProperties = injectProperties;
        this.ontrackLog = ontrackLog;
    }

    public OntrackDSLResult run(AbstractBuild<?, ?> theBuild, BuildListener listener) throws InterruptedException, IOException {
        // Connection to Ontrack
        Ontrack ontrack = createOntrackConnector(listener);
        // Connector to Jenkins
        JenkinsConnector jenkins = new JenkinsConnector();
        // Values to bind
        Map<String, Object> values = new HashMap<String, Object>();
        // Gets the environment
        String[] names = injectEnvironment.split(",");
        for (String name : names) {
            name = name.trim();
            String value = theBuild.getEnvironment(listener).get(name, "");
            if (value != null) {
                values.put(name, value);
            }
        }
        // Gets the properties
        Map<String, String> properties = OntrackPluginSupport.parseProperties(injectProperties, theBuild, listener);
        values.putAll(properties);
        // Traces
        listener.getLogger().format("Injecting following values:%n");
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            listener.getLogger().format(" - %s = %s%n", entry.getKey(), entry.getValue());
        }
        // Binding
        values.put("ontrack", ontrack);
        values.put("jenkins", jenkins);
        Binding binding = new Binding(values);
        // Groovy shell
        GroovyShell shell = new GroovyShell(binding);
        // Runs the script
        listener.getLogger().format("Ontrack DSL script running...%n");
        Object shellResult = shell.evaluate(script);
        if (ontrackLog) {
            listener.getLogger().format("Ontrack DSL script returned result: %s%n", shellResult);
        } else {
            listener.getLogger().format("Ontrack DSL script returned result.%n");
        }
        // Result
        return new OntrackDSLResult(
                shellResult,
                jenkins
        );
    }

    private Ontrack createOntrackConnector(final BuildListener listener) {
        OntrackConfiguration config = OntrackConfiguration.getOntrackConfiguration();
        OntrackConnection connection = OntrackConnection.create(config.getOntrackUrl());
        // Logging
        if (ontrackLog) {
            connection = connection.logger(new OTHttpClientLogger() {
                public void trace(String message) {
                    listener.getLogger().println(message);
                }
            });
        }
        // Authentication
        String user = config.getOntrackUser();
        if (StringUtils.isNotBlank(user)) {
            connection = connection.authenticate(
                    user,
                    config.getOntrackPassword()
            );
        }
        // Building the Ontrack root
        return connection.build();
    }

    public static Result toJenkinsResult(Object shellResult) {
        if (shellResult == null ||
                shellResult.equals(0) ||
                shellResult.equals(false) ||
                shellResult.equals("") ||
                shellResult instanceof ProjectEntity) {
            return Result.SUCCESS;
        } else {
            return Result.FAILURE;
        }
    }
}
