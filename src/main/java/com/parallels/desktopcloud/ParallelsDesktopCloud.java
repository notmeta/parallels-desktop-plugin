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
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.NodeProvisioner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Level;

import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;


public final class ParallelsDesktopCloud extends Cloud
{
	private static final ParallelsLogger LOGGER = ParallelsLogger.getLogger("PDCloud");

	private final List<ParallelsDesktopVM> vms;
	private final ComputerLauncher pdLauncher;
	private final String labelString;
	private final String remoteFS;
	private final boolean useConnectorAsBuilder;
	private final int maxConcurrentVms;
	private final boolean useLinkedClones;
	
	private transient ParallelsDesktopConnectorSlave connectorSlave;

	@DataBoundConstructor
	public ParallelsDesktopCloud(String name, String labelString, String remoteFS, ComputerLauncher pdLauncher,
			boolean useConnectorAsBuilder, int maxConcurrentVms, boolean useLinkedClones, List<ParallelsDesktopVM> vms)
	{
		super(name);
		this.labelString = labelString;
		this.remoteFS = remoteFS;
		if (vms == null)
			this.vms = Collections.emptyList();
		else
			this.vms = vms;
		this.pdLauncher = pdLauncher;
		this.maxConcurrentVms = maxConcurrentVms;
		this.useLinkedClones = useLinkedClones;
		this.useConnectorAsBuilder = useConnectorAsBuilder;
	}

	@Override
	public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload)
	{
		LOGGER.log(Level.SEVERE, "Going to provision %d executors", excessWorkload);
		Collection<NodeProvisioner.PlannedNode> result = new ArrayList<NodeProvisioner.PlannedNode>();
		final ParallelsDesktopConnectorSlaveComputer connector = getConnector();
		if (connector.isOffline())
		{
			return result;
		}
		for (int i = 0; (i < vms.size()) && (excessWorkload > 0); i++)
		{
			ParallelsDesktopVM vm = vms.get(i);
			if (vm.isProvisioned())
				continue;
			if (!label.matches(Label.parse(vm.getLabels())))
				continue;
			vm = connector.startVM(vm);
			if (vm == null)
				continue;
			final String slaveName = name + " " + vm.getVmid();
			vm.setSlaveName(slaveName);
			--excessWorkload;
			
			final ParallelsDesktopVM connectorVm = vm;
			result.add(new NodeProvisioner.PlannedNode(slaveName,
				Computer.threadPoolForRemoting.submit(new Callable<Node>()
				{
					@Override
					public Node call() throws Exception
					{
						return connector.createSlaveOnVM(connectorVm);
					}
				}), 1));
		}
		return result;
	}

	private ParallelsDesktopConnectorSlaveComputer getConnector()
	{
		try
		{
			if (connectorSlave == null)
			{
				String slaveName = name + " host slave";
				connectorSlave = new ParallelsDesktopConnectorSlave(this, slaveName, labelString, remoteFS, pdLauncher, useConnectorAsBuilder);
				Jenkins.getInstance().addNode(connectorSlave);
			}
			return (ParallelsDesktopConnectorSlaveComputer)connectorSlave.toComputer();
		}
		catch(Exception ex)
		{
			LOGGER.log(Level.SEVERE, "Error: %s", ex);
		}
		return null;
	}

	void connectorTerminated()
	{
		connectorSlave = null;
	}

	@Override
	public boolean canProvision(Label label)
	{
		if (label != null)
		{
			for (ParallelsDesktopVM vm : vms)
			{
				if (label.matches(Label.parse(vm.getLabels())))
					return true;
			}
		}
		return false;
	}

	public List<ParallelsDesktopVM> getVms()
	{
		return vms;
	}

	public ComputerLauncher getPdLauncher()
	{
		return pdLauncher;
	}

	public String getLabelString()
	{
		return labelString;
	}

	public String getRemoteFS()
	{
		return remoteFS;
	}
	
	public boolean getUseConnectorAsBuilder()
	{
		return useConnectorAsBuilder;
	}
	
	public int getMaxConcurrentVms()
	{
		return maxConcurrentVms;
	}
	
	public boolean getUseLinkedClones()
	{
		return useLinkedClones;
	}

	@Extension
	public static final class DescriptorImpl extends Descriptor<Cloud>
	{
		@Override
		public String getDisplayName()
		{
			return "Parallels Desktop Cloud";
		}
		
		public FormValidation doCheckMaxConcurrentVms(@QueryParameter String value) throws IOException, ServletException
		{
			try
			{
				Integer.parseInt(value);
				return FormValidation.ok();
			}
			catch (NumberFormatException e)
			{
				return FormValidation.error("Not a number");
			}
		}
	}
}
