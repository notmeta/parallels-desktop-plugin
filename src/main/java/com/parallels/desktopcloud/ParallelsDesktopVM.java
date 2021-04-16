/*
 * The MIT License
 *
 * (c) 2004-2015. Parallels IP Holdings GmbH. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.parallels.desktopcloud;

import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.RetentionStrategy;
import hudson.util.ListBoxModel;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Date;
import java.util.logging.Level;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;


public class ParallelsDesktopVM implements Describable<ParallelsDesktopVM>
{
	private static final ParallelsLogger LOGGER = ParallelsLogger.getLogger("PDVM");

	public enum PostBuildBehaviors
	{
		Suspend,
		Stop,
		KeepRunning,
		ReturnPrevState
	}
	
	public enum VMStates
	{
		Suspended,
		Paused,
		Running,
		Stopped
	}

	private final String vmid;
	private final String labels;
	private final String remoteFS;
	private transient String slaveName;
	private final ComputerLauncher launcher;
	private transient boolean provisioned = false;
	private PostBuildBehaviors postBuildBehavior;
	private transient VMStates prevVmState;
	private RetentionStrategy<?> retentionStrategy;
	
	private String parentVmid;
	private boolean isLinkedClone;
	
	@DataBoundConstructor
	public ParallelsDesktopVM(String vmid, String labels, String remoteFS, ComputerLauncher launcher,
		String postBuildBehavior, RetentionStrategy<?> retentionStrategy)
	{
		this.vmid = vmid;
		this.labels = labels;
		this.remoteFS = remoteFS;
		this.launcher = launcher;
		this.retentionStrategy = retentionStrategy;
		try
		{
			this.postBuildBehavior = PostBuildBehaviors.valueOf(postBuildBehavior);
		}
		catch(Exception ex)
		{
			LOGGER.log(Level.SEVERE, "Error: %s", ex);
		}
		if (this.postBuildBehavior == null)
			this.postBuildBehavior = PostBuildBehaviors.Suspend;
		prevVmState = VMStates.Suspended;
	}
	
	public ParallelsDesktopVM createLinkedClone()
	{
		final String instanceName = generateUniqueName();
		final ParallelsDesktopVM linkedClone = new ParallelsDesktopVM(instanceName, this.labels, this.remoteFS, this.launcher, this.postBuildBehavior.name(), this.retentionStrategy);
		
		linkedClone.isLinkedClone = true;
		linkedClone.parentVmid = getVmid();
		
		return linkedClone;
	}

	public String getVmid()
	{
		return vmid;
	}

	public String getLabels()
	{
		return labels;
	}

	public String getRemoteFS()
	{
		return remoteFS;
	}

	public ComputerLauncher getLauncher()
	{
		return launcher;
	}
	
	public boolean isLinkedClone()
	{
		return isLinkedClone;
	}
	
	public String getParentVmid()
	{
		return parentVmid;
	}

	public void setSlaveName(String slaveName)
	{
		this.slaveName = slaveName;
	}

	public String getSlaveName()
	{
		return slaveName;
	}

	public void setProvisioned(boolean provisioned)
	{
		this.provisioned = provisioned;
	}

	public boolean isProvisioned()
	{
		return provisioned;
	}

	public String getPostBuildBehavior()
	{
		if (postBuildBehavior == null)
			return PostBuildBehaviors.Suspend.name();
		return postBuildBehavior.name();
	}

	public PostBuildBehaviors getPostBuildBehaviorValue()
	{
		return postBuildBehavior;
	}

	public RetentionStrategy<?> getRetentionStrategy() {
        return this.retentionStrategy;
    }

	protected Object readResolve() {
		if (this.retentionStrategy == null) {
			this.retentionStrategy = new ParallelsDesktopCloudRetentionStrategy();
		}
		return this;
	}

	public static VMStates parseVMState(String state)
	{
		if ("stopped".equals(state))
			return VMStates.Stopped;
		else if ("paused".equals(state))
			return VMStates.Paused;
		else if ("running".equals(state))
			return VMStates.Running;
		else if ("suspended".equals(state))
			return VMStates.Suspended;

		return null;
	}

	public void setPrevVMState(VMStates state)
	{
		prevVmState = state;
	}

	public String getPostBuildCommand()
	{
		switch (postBuildBehavior)
		{
		case ReturnPrevState:
			switch (prevVmState)
			{
			case Paused:
				return "pause";
			case Running:
				return null;
			case Stopped:
				return "stop";
			default:
				return "suspend";
			}
		case KeepRunning:
			return null;
		case Stop:
			return "stop";
		default:
			return "suspend";
		}
	}

	void onSlaveReleased(ParallelsDesktopVMSlave slave)
	{
		setProvisioned(false);
	}

	void setLauncherIP(String ip)
	{
		try
		{
			Class<?> c = launcher.getClass();
			Field f = c.getDeclaredField("host");
			f.setAccessible(true);
			f.set(launcher, ip);
			f.setAccessible(false);
		}
		catch (Exception ex)
		{
			LOGGER.log(Level.SEVERE, "Error: %s", ex);
		}
	}

	String getLauncherIP()
	{
		try
		{
			Class<?> c = launcher.getClass();
			Field f = c.getDeclaredField("host");
			f.setAccessible(true);
			String ip = (String)f.get(launcher);
			f.setAccessible(false);
			return ip;
		}
		catch (Exception ex)
		{
			LOGGER.log(Level.SEVERE, "Error: %s", ex);
		}
		return null;
	}
	
	private String generateUniqueName()
	{
		SimpleDateFormat simpleDateFormat = new SimpleDateFormat("HHmmssSSSddMM");
		String dateString = simpleDateFormat.format(new Date());
		return getVmid() + "_" + dateString;
	}

	@Override
	public Descriptor<ParallelsDesktopVM> getDescriptor()
	{
		return Jenkins.getInstance().getDescriptor(getClass());
	}

	@Extension
	public static final class DescriptorImpl extends Descriptor<ParallelsDesktopVM>
	{
		@Override
		public String getDisplayName()
		{
			return "Parallels Desktop virtual machine";
		}

		public ListBoxModel doFillPostBuildBehaviorItems()
		{
			ListBoxModel m = new ListBoxModel();
			m.add(Messages.Parallels_Behavior_Suspend(), PostBuildBehaviors.Suspend.name());
			m.add(Messages.Parallels_Behavior_Stop(), PostBuildBehaviors.Stop.name());
			m.add(Messages.Parallels_Behavior_KeepRunning(), PostBuildBehaviors.KeepRunning.name());
			m.add(Messages.Parallels_Behavior_ReturnPrevState(), PostBuildBehaviors.ReturnPrevState.name());
			return m;
		}

		public static List<Descriptor<RetentionStrategy<?>>> getRetentionStrategyDescriptors() {
            final List<Descriptor<RetentionStrategy<?>>> result = new ArrayList<>();
			result.add(ParallelsDesktopCloudRetentionStrategy.DESCRIPTOR);
			result.add(ParallelsRunOnceCloudRetentionStrategy.DESCRIPTOR);
            return result;
        }
	}
}
