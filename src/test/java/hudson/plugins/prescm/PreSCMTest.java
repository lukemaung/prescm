package hudson.plugins.prescm;

import hudson.slaves.DumbSlave;

/**
 * 
 * @author lmaung
 */
public class PreSCMTest extends BaseTest {

	public void testValidCommand() throws Exception {
		DumbSlave slave = createSlaveNode();
		setUpAndRunProject("echo success", slave);
		verifyMasterOutput("success"); // expects this string in the console output if pre-scm plugin did its work
	}

	public void testInvalidCommand() throws Exception {
		DumbSlave slave = createSlaveNode();
		setUpAndRunProject("ajskdfljsdakfl", slave);
		verifyMasterOutput("command not found"); // expects this error message in console output if pre-scm plugin did its work
	}

	public void testValidMatrix() throws Exception {
		DumbSlave slave1 = createSlaveNode();
		DumbSlave slave2 = createSlaveNode();
		for (int i = 0; i < 1000; i++) {
			// run the matrix job 1000 times to weed out potential timing related issues
			// this will take a while ~2hr max
			setUpAndRunMatrixProject("hostname && echo success $test" /*pre scm command*/, 
					new DumbSlave[] { slave1, slave2 } /* slaves */);
			verifyChildrenOutput("success"); // expects this string in the console output if pre-scm plugin did its work
		}
	}

}
