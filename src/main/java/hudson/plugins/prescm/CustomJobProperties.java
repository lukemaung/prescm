/**
 * TODO: license info here
 */
package hudson.plugins.prescm;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.util.FormValidation;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

/**
 *
 * @author lmaung
 */
public class CustomJobProperties extends JobProperty {
    private static final String DISPLAY_NAME = "Job Properties for Pre SCM Plugin";
    private static final String SHELL_COMMAND_PARAM = "shellCommand";
    private static final String ENABLE_FLAG_PARAM = "enableFlag";

    /**
     * enable/disable this plugin for the project
     */
    private boolean enableFlag;

    /**
     * shell command to run before scm checkout step
     */
    private String shellCommand;

    @DataBoundConstructor
    public CustomJobProperties(boolean enableFlag, String shellCommand) {
        this.enableFlag = enableFlag;
        this.shellCommand = shellCommand;
    }

    /**
     * retrieves the shell command setup for the job
     * 
     * @return
     */
    public String getShellCommand() {
        return shellCommand;
    }

    /**
     * returns true if check box is selected in the GUI
     *
     * @return
     */
    public boolean getEnableFlag() {
        return enableFlag;
    }

    @Extension // this marker indicates Hudson that this is an implementation of an extension point.
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    public static class DescriptorImpl extends JobPropertyDescriptor {

        @Override
        public JobProperty<?> newInstance(StaplerRequest req, JSONObject formData) {
            // parse form data manually as it is not trivial string array
            boolean enable = formData.containsKey(ENABLE_FLAG_PARAM);
            String cmd = enable ? formData.getJSONObject(ENABLE_FLAG_PARAM)
                    .getString(SHELL_COMMAND_PARAM).trim() : "";
            return new CustomJobProperties(enable, cmd);
        }

        @Override
        public boolean isApplicable(Class<? extends Job> jobType) {
            // all jobs can use this plugin
            return true;
        }

        @Override
        public String getDisplayName() {
            return DISPLAY_NAME;
        }

        /**
         * validates that the value points to an executable file
         *
         * @param value
         * @return
         */
        public FormValidation doExecutableCheck(@QueryParameter String value) {
            return FormValidation.validateExecutable(value);
        }
    }
}
