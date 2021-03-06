package net.nemerosa.ontrack.jenkins.changelog;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import net.nemerosa.ontrack.dsl.Build;
import net.nemerosa.ontrack.dsl.*;
import net.nemerosa.ontrack.dsl.http.OTNotFoundException;
import net.nemerosa.ontrack.jenkins.dsl.OntrackDSLConnector;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.util.*;

import static java.lang.String.format;
import static net.nemerosa.ontrack.jenkins.OntrackPluginSupport.expand;

@SuppressWarnings("unused")
public class OntrackChangelogPublisher extends Notifier {

    /**
     * Name of the project to get the change log for
     */
    private final String project;
    /**
     * Name of the branch to get the change log for
     */
    private final String branch;
    /**
     * Name of the parameter which contains the Ontrack build name on a give Jenkins build
     */
    private final String buildNameParameter;

    /**
     * If the change logs must be distinguished between each intermediate build
     */
    private final boolean distinctBuilds;

    /**
     * Must the file change log be collected?
     */
    private final boolean collectFiles;

    @DataBoundConstructor
    public OntrackChangelogPublisher(String project, String branch, String buildNameParameter, boolean distinctBuilds, boolean collectFiles) {
        this.project = project;
        this.branch = branch;
        this.buildNameParameter = buildNameParameter;
        this.distinctBuilds = distinctBuilds;
        this.collectFiles = collectFiles;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        // Gets the project and branch name
        String projectName = expand(project, build, listener);
        String branchName = expand(branch, build, listener);

        // Gets the current build name
        String lastBuildName = getBuildName(build);

        // Gets the previous build name
        String previousBuildName = null;
        AbstractBuild<?, ?> previousBuild = build.getPreviousBuild();
        if (previousBuild != null) {
            previousBuildName = getBuildName(previousBuild);
        }

        // Checks the build boundaries
        if (StringUtils.isBlank(lastBuildName)) {
            return noChangeLog(listener, "No build name can be retrieved from the current build");
        } else if (previousBuild == null) {
            return noChangeLog(listener, "There is no previous build");
        } else if (StringUtils.isBlank(previousBuildName)) {
            return noChangeLog(listener, "No build name can be retrieved from the previous build");
        }

        // Gets the Ontrack connector
        Ontrack ontrack = OntrackDSLConnector.createOntrackConnector(listener);

        // Gets the two builds from Ontrack
        Build build1;
        try {
            build1 = ontrack.build(projectName, branchName, previousBuildName);
        } catch (OTNotFoundException ignored) {
            return noChangeLog(listener, format("Build %s cannot be found.", previousBuildName));
        }
        Build buildN;
        try {
            buildN = ontrack.build(projectName, branchName, lastBuildName);
        } catch (OTNotFoundException ignored) {
            return noChangeLog(listener, format("Build %s cannot be found.", lastBuildName));
        }

        // Gets the build intervals
        List<Build> builds = Arrays.asList(build1, buildN);
        // If distinctBuilds, collect all builds between 1 and N
        if (distinctBuilds) {
            builds = ontrack.branch(projectName, branchName).intervalFilter(
                    ImmutableMap.of(
                            "from", build1.getName(),
                            "to", buildN.getName()
                    )
            );
        }

        // Collects the change logs for each interval
        List<OntrackChangeLog> changeLogs = new ArrayList<OntrackChangeLog>();
        int count = builds.size();
        for (int i = 1; i < count; i++) {
            Build a = builds.get(i - 1);
            Build b = builds.get(i);
            // Different builds
            if (a.getId() != b.getId()) {
                // Gets the change log from A to B
                ChangeLog changeLog = a.getChangeLog(b);
                // Reduces the amount of information for the change log
                OntrackChangeLog ontrackChangeLog = collectInfo(changeLog);
                // Adds to the list
                changeLogs.add(ontrackChangeLog);
            }
        }

        // Adds a change log action to register the change log
        build.addAction(new OntrackChangeLogAction(build, changeLogs));

        // OK
        return true;
    }

    protected OntrackChangeLog collectInfo(ChangeLog changeLog) {

        // Gets the commits
        List<OntrackChangeLogCommit> commits = Lists.transform(
                changeLog.getCommits(),
                new Function<ChangeLogCommit, OntrackChangeLogCommit>() {
                    @Override
                    public OntrackChangeLogCommit apply(ChangeLogCommit input) {
                        return new OntrackChangeLogCommit(
                                input.getId(),
                                input.getShortId(),
                                input.getAuthor(),
                                input.getTimestamp(),
                                input.getMessage(),
                                input.getFormattedMessage(),
                                input.getLink()
                        );
                    }
                }
        );

        // Gets the issues
        List<OntrackChangeLogIssue> issues = Lists.transform(
                changeLog.getIssues(),
                new Function<ChangeLogIssue, OntrackChangeLogIssue>() {
                    @Override
                    public OntrackChangeLogIssue apply(ChangeLogIssue input) {
                        return new OntrackChangeLogIssue(
                                input.getKey(),
                                input.getDisplayKey(),
                                input.getSummary(),
                                input.getStatus(),
                                input.getUpdateTime(),
                                input.getUrl()
                        );
                    }
                }
        );

        // Gets the files
        List<OntrackChangeLogFile> files;
        if (collectFiles) {
            files = Lists.transform(
                    changeLog.getFiles(),
                    new Function<ChangeLogFile, OntrackChangeLogFile>() {
                        @Override
                        public OntrackChangeLogFile apply(ChangeLogFile input) {
                            return new OntrackChangeLogFile(
                                    input.getPath(),
                                    input.getChangeTypes()
                            );
                        }
                    }
            );
        } else {
            files = Collections.emptyList();
        }

        // Page link
        String page = changeLog.link("page");

        // OK
        return new OntrackChangeLog(
                changeLog.getFrom().getName(),
                changeLog.getTo().getName(),
                page,
                new ArrayList<OntrackChangeLogCommit>(
                        commits
                ),
                new ArrayList<OntrackChangeLogIssue>(
                        issues
                ),
                new ArrayList<OntrackChangeLogFile>(
                        files
                )
        );
    }

    protected boolean noChangeLog(BuildListener listener, String reason) {
        listener.getLogger().format("No change log can be computed. %s%n", reason);
        return true;
    }

    protected String getBuildName(AbstractBuild<?, ?> build) {
        List<ParametersAction> parametersActions = build.getActions(ParametersAction.class);
        for (ParametersAction parametersAction : parametersActions) {
            ParameterValue parameterValue = parametersAction.getParameter(buildNameParameter);
            if (parameterValue != null) {
                return Objects.toString(parameterValue.getValue(), null);
            }
        }
        // Not found
        return null;
    }

    public String getProject() {
        return project;
    }

    public String getBranch() {
        return branch;
    }

    public String getBuildNameParameter() {
        return buildNameParameter;
    }

    public boolean isDistinctBuilds() {
        return distinctBuilds;
    }

    public boolean isCollectFiles() {
        return collectFiles;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    @Extension
    public static final class OntrackChangelogPublisherDescriptor extends BuildStepDescriptor<Publisher> {

        public OntrackChangelogPublisherDescriptor() {
            super(OntrackChangelogPublisher.class);
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Ontrack: Change log publication";
        }
    }
}
