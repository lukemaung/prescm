/**
 * TODO: license info
 */
package hudson.plugins.prescm;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Launcher.ProcStarter;
import hudson.matrix.MatrixConfiguration;
import hudson.model.*;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Adds shell command execution feature before the SCM checkout step of a job's
 * life cycle.
 *
 * The actual command is configured in the job setup.
 *
 * To use the plugin:
 * 1) enable "Pre-SCM" plugin by marking the checkbox in Node Configuration UI
 * 2) enter shell command in Job Configuration UI
 * 
 * @author lmaung
 */
public class PreSCM extends NodeProperty<Node> {
    private static final Logger LOGGER = Logger.getLogger(PreSCM.class.getName());

    private static final String JOB_NAME = "JOB_NAME";
    private static final String BASE_SHELL = "/bin/sh";
    private static final String TEMP_DIR = "/tmp";
    private static final String PREFIX = "tmp-";
    private static final String EXTENSION = ".sh";
    private static final String SHELL_OPTIONS = "-xe";
    private final String name;

    // for matrix projects, we cannot assume that a build's properties are fully populated
    // during setUp(). furthermore, setUp() is called twice. therefore we need
    // to 1) track if the pre scm plugin has been kicked off already
    // and 2) explicitly cache the build's properties (and remove from cache when done using it)
    private static final List<String> preScmInProgress = new CopyOnWriteArrayList<String>();
    private static final Map<String, Map> preScmProperties = new ConcurrentHashMap<String, Map>();

    @DataBoundConstructor
    public PreSCM(String name) {
        this.name = name;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return PreSCM.DESCRIPTOR;
    }

    @Override
    public Environment setUp(AbstractBuild build, Launcher launcher, BuildListener listener)
            throws IOException, InterruptedException {
        PrintStream consoleOutput = listener.getLogger(); // re-use the console output
        // use the job name and executor names as key to track whether the
        // pre-scm plugin has been triggered. we need to do this because
        // abstractbuild.createLauncher() can potentially kick off pre-scm
        // plugin twice1
        // note that this composite key will not work if the executor name has
        // been changed between the 2 invocations. however, this is unlikely imho.
        boolean isMatrix = build.getParent() instanceof MatrixConfiguration;
        String projectName = isMatrix ?
        		build.getParent().getParent().getDisplayName() : build.getParent().getName();
        // cache properties because the second call doesn't initialize 
        // the environment properties        		
        Map currentProperties = isMatrix ?
        		((MatrixConfiguration)build.getParent()).getParent().getProperties() :
        		build.getParent().getProperties();
        if (build.getParent() instanceof MatrixConfiguration) {
        	MatrixConfiguration mc = (MatrixConfiguration)build.getParent();
        }
        if (currentProperties != null && !currentProperties.isEmpty()) {
        	preScmProperties.put(projectName, currentProperties);
        }

        Executor exec = build.getExecutor();
        if (exec == null) { // build may be on a temporary flyweight thread. verify it
            for (Executor check : Computer.currentComputer().getOneOffExecutors()) { // find the one running this build
                if (check.getCurrentExecutable() == build) { // found it
                    exec = check; // use it for further processing next
                    break;
                }
            }
        }
        if (exec != null) { // may happen at the first call from matrix project. second call is fine.
            String execName = exec.getName();
            String key = projectName + execName;

            if (preScmInProgress.contains(key)) {
                // pre-scm was already run, so don't run the pre-scm part again
                preScmInProgress.remove(key); // remove the state token for build
                preScmProperties.remove(projectName); // remove the cached properties for the project
                LOGGER.fine("skipping pre-scm command(s) as they were already run for job " + projectName + " on executor " + execName);
            } else {
                // pre-scm is not run yet, so let it run
                preScmInProgress.add(key); // mark it as run
                Map properties = preScmProperties.get(projectName);
                if (properties == null || properties.isEmpty()) {
                    // this can happen to an old job which has not been persisted
                    // with the new property
                    consoleOutput.println("Pre-SCM shell command is not setup. "
                            + "Go to project and click on Save once.");
                } else {
                    // first, find the shell command in the job's properties map.
                    // this is done in a second pass to make sure we have iterated
                    // through all the regular job properties before handling
                    // the custom properties
                    Iterator iterator = properties.values().iterator();
                    while (iterator.hasNext()) {
                        Object currentProperty = iterator.next();
                        if (currentProperty instanceof CustomJobProperties) {
                            CustomJobProperties customProperty = (CustomJobProperties) currentProperty;
                            boolean enableFlag = customProperty.getEnableFlag();
                            if (enableFlag) {
                                // first, create a map of regular build / environment variables
                                EnvVars environment = build.getEnvironment(listener);
                                if (isMatrix) {
                                	// axis variables are not yet in the scope 
                                	//  of the environment, so explicitly
                                	// put them into scope
                                	String jobName = environment.get(JOB_NAME);
                                	String[] keyVals = jobName.substring(jobName.indexOf('/')).split(",");
                                	for (String keyVal : keyVals) {
                                		String[] tokens = keyVal.split("=");
                                		environment.put(tokens[0], tokens[1]);
                                	}
                                }
                                String shellCommand = customProperty.getShellCommand();
                                consoleOutput.println("Will execute shell command '"
                                        + shellCommand + "' (with variable replacement) before SCM checkout starts.");

                                FilePath tmpDir = null;
                                FilePath tmpFile = null;
                                try {
                                    // using recommended idiom from:
                                    // http://wiki.hudson-ci.org/display/HUDSON/Hints+for+plugin-development+newbies
                                    Launcher tempLauncher = Computer.currentComputer()
                                            .getNode().createLauncher(listener);
                                    if (Hudson.getInstance().getChannel() == tempLauncher.getChannel()) {
                                    	// defensively hard-code NOT to allow pre-scm to run on master
                                    	// (this shouldn't happen. but can be disastrous if it does)
                                    	LOGGER.severe("pre-scm is attempting to run script on hudson master node! This is not allowed. debug info: build=" + build.getDisplayName());
                                    	return super.setUp(build, launcher, listener); 
                                    }
                                    // note, cannot use build.getWorkspace() to get workspace dir 
                                    // because we're still at pre-scm stage. so use temp dir 
                                    // as the working directory for the pre-scm script
                                    tmpDir = new FilePath(tempLauncher.getChannel(), TEMP_DIR); // Don't assume what the launcher is. Set it explicitly. 
                                    tmpFile = tmpDir.createTextTempFile(PREFIX, EXTENSION, shellCommand);
                                    String fileName = tmpFile.getBaseName() + EXTENSION;
                                    consoleOutput.println("about to launch pre-scm script on node: " + Computer.currentComputer().getDisplayName());
                                    LOGGER.info("about to launch pre-scm script "
                                    		+ fileName + " on node: " 
                                    		+ Computer.currentComputer().getDisplayName()
                                    		+ " for: " + execName);
                                    ProcStarter proc = tempLauncher.launch().cmds(BASE_SHELL, SHELL_OPTIONS, fileName)
                                    		.envs(environment) // pass build/environment vars
                                    		.stderr(consoleOutput).stdout(consoleOutput) // dump all to console
                                    		.pwd(tmpDir);
                                    LOGGER.log(Level.FINE, "pre-scm debug info: commands = " + proc.cmds() + ", dir=" + proc.pwd().toURI());
                                    int exitCode = proc.join(); // wait for process to complete
                                    consoleOutput.println("Exit code from post-build shell command is " + exitCode);
                                    LOGGER.info("Exit code from post-build shell command is " + exitCode);
                                } catch (IOException ioe) {
                                    LOGGER.log(Level.SEVERE, "Problem launching post-build shell command", ioe);
                                } catch (InterruptedException ie) {
                                    LOGGER.log(Level.SEVERE, "Problem launching post-build shell command", ie);
                                } finally {
                                	if (tmpFile != null) {
                                		try {
                                			tmpFile.delete();
                                		} catch (IOException ioe) {
                                			LOGGER.log(Level.SEVERE, "Problem deleting " + tmpFile.getName(), ioe);
                                		}
                                	}
                                }
                                // NEW-END                                
                                break; // forcefully make sure to only launch once
                            }
                        }
                    }
                }
            }
        }
        return super.setUp(build, launcher, listener); // continue with the rest of the build
    }

    @Extension // this marker indicates Hudson that this is an implementation of an extension point.
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    public static class DescriptorImpl extends NodePropertyDescriptor {
        private static final String DISPLAY_NAME = "Pre SCM Plugin";
        
        protected DescriptorImpl() {
            super(PreSCM.class);
            load();
        }

        public String getDisplayName() {
            return DISPLAY_NAME;
        }
    }
}
