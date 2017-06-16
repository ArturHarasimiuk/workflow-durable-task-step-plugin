package org.jenkinsci.plugins.workflow.support.steps;

import com.google.inject.Inject;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.slaves.WorkspaceList;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecution;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.jenkinsci.plugins.workflow.support.actions.WorkspaceActionImpl;

/**
 * @author Jesse Glick
 */
public class WorkspaceStepExecution extends AbstractStepExecutionImpl {

    @Inject(optional=true) private transient WorkspaceStep step;
    @StepContextParameter private transient Computer computer;
    @StepContextParameter private transient Run<?,?> run;
    @StepContextParameter private transient TaskListener listener;
    @StepContextParameter private transient FlowNode flowNode;
    private BodyExecution body;

    @Override
    public boolean start() throws Exception {
        Job<?,?> job = run.getParent();
        if (!(job instanceof TopLevelItem)) {
            throw new Exception(job + " must be a top-level job");
        }
        Node node = computer.getNode();
        if (node == null) {
            throw new Exception("computer does not correspond to a live node");
        }
        WorkspaceList.Lease lease;
        String dir = step.getDir();
        if (dir == null) {
            FilePath baseWorkspace = node.getWorkspaceFor((TopLevelItem) job);
            if (baseWorkspace == null) {
                throw new IllegalStateException(node + " is offline");
            }
            lease = computer.getWorkspaceList().allocate(baseWorkspace);
        } else {
            FilePath rootPath = node.getRootPath();
            if (rootPath == null) {
                throw new IllegalStateException(node + " is offline");
            }
            FilePath baseWorkspace = rootPath.child(dir);
            lease = computer.getWorkspaceList().allocate(baseWorkspace);
        }
        FilePath workspace = lease.path; // may be baseWorkspace + @2, @3, etc.
        flowNode.addAction(new WorkspaceActionImpl(workspace, flowNode));
        listener.getLogger().println("Running in " + workspace);
        body = getContext().newBodyInvoker()
                .withContexts(
                    EnvironmentExpander.merge(getContext().get(EnvironmentExpander.class),
                        new ExpanderImpl(workspace.getRemote())),
                    workspace)
                .withCallback(new Callback(lease))
                .start();
        return false;
    }

    private static final class ExpanderImpl extends EnvironmentExpander {
        private static final long serialVersionUID = 1;
        private final String path;
        private ExpanderImpl(String path) {
            this.path = path;
        }
        @Override public void expand(EnvVars env) throws IOException, InterruptedException {
            env.override("WORKSPACE", path);
        }
    }

    @Override
    public void stop(Throwable cause) throws Exception {
        if (body!=null)
            body.cancel(cause);
    }

    @SuppressFBWarnings(value="SE_BAD_FIELD", justification="lease is pickled")
    private static final class Callback extends BodyExecutionCallback.TailCall {

        private final WorkspaceList.Lease lease;

        Callback(WorkspaceList.Lease lease) {
            this.lease = lease;
        }

        @Override protected void finished(StepContext context) throws Exception {
            lease.release();
        }

    }

    private static final long serialVersionUID = 1L;

}
