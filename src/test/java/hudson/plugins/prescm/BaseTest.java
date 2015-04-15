package hudson.plugins.prescm;

import hudson.matrix.Axis;
import hudson.matrix.AxisList;
import hudson.matrix.MatrixConfiguration;
import hudson.matrix.LabelAxis;
import hudson.matrix.MatrixProject;
import hudson.model.AbstractBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.model.Run;
import hudson.slaves.DumbSlave;
import hudson.tasks.Builder;
import hudson.tasks.Shell;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.junit.Assert;
import org.jvnet.hudson.test.HudsonTestCase;

public abstract class BaseTest extends HudsonTestCase {
	private AbstractBuild build;
	private Collection<? extends Job> children;
	public static final String JOB_PREFIX = "testjob_";
	
	protected DumbSlave createSlaveNode() throws Exception {
		DumbSlave slave = createOnlineSlave();
		PreSCM preSCM = new PreSCM("");
		slave.getNodeProperties().add(preSCM);
		return slave;
	}

	protected void setUpAndRunProject(String preScmCommand, DumbSlave slave)
			throws Exception {
		FreeStyleProject project = createFreeStyleProject();
		project.renameTo(JOB_PREFIX + System.currentTimeMillis());
		CustomJobProperties prop = new CustomJobProperties(true, preScmCommand);
		project.setAssignedNode(slave);
		project.addProperty(prop);
		Builder builder = new Shell("echo job $JOB_NAME @ " + System.currentTimeMillis());
		project.getBuildersList().add(builder);
		project.save();
		build = project.scheduleBuild2(0).get();
	}

	protected void setUpAndRunMatrixProject(String preScmCommand,
			DumbSlave[] slaves) throws Exception {
		MatrixProject project = createMatrixProject(""
				+ System.currentTimeMillis());
		project.renameTo(JOB_PREFIX + System.currentTimeMillis());
		AxisList axes = new AxisList();
		List<String> labels = new ArrayList<String>();
		for (DumbSlave s : slaves) {
			labels.add(s.getDisplayName());
		}
		LabelAxis labelAxis = new LabelAxis("label", labels);
		Axis axis1 = new Axis("test", "1", "2");
		Axis axis2 = new Axis("config", "A", "B");
		axes.add(labelAxis);
		axes.add(axis1);
		axes.add(axis2);
		project.setAxes(axes);
		CustomJobProperties prop = new CustomJobProperties(true, preScmCommand);
		project.addProperty(prop);
		Builder builder = new Shell("echo job $JOB_NAME @ " + System.currentTimeMillis());
		project.getBuildersList().add(builder);
		project.save();
		build = project.scheduleBuild2(0).get();
		children = project.getAllJobs();
	}

	protected void verifyMasterOutput(String expected) throws Exception {
		boolean found = false;
		for (Object line : build.getLog(100)) {
			System.out.println("console output: " + line);
			if (((String) line).contains(expected)) {
				found = true;
			}
		}
		Assert.assertTrue("'" + expected + "' not found in console output for build " + build.getFullDisplayName(), 
				found);
	}

	protected void verifyChildrenOutput(String expected) throws Exception {
		boolean found = false;
		String completeExpectedString = null;
		for (Job b : children) {
			Run lastBuild = b.getLastBuild();
			String fullJobName = lastBuild.getFullDisplayName();
			int commaPos = fullJobName.lastIndexOf(",");
			int spacePos = fullJobName.lastIndexOf(" ");
			String testVar = fullJobName.substring(commaPos+1, spacePos);
			completeExpectedString = expected + " " + testVar;
			for (Object line : lastBuild.getLog(100)) {
				System.out.println(b + ": console output: " + line);
				if (((String) line).startsWith(completeExpectedString)) {
					found = true;
				}
			} // done checking all the lines in the console output
			if (b instanceof MatrixConfiguration) {
				Assert.assertTrue(
					"'" + completeExpectedString + "' not found in console output " + b.getLastBuild().getLog(100), found);
			}
		}
	}

}
