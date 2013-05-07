package org.cloudbus.cloudsim.ex.mapreduce;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.DatacenterBroker;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.ex.mapreduce.policy.*;
import org.cloudbus.cloudsim.ex.mapreduce.models.cloud.*;
import org.cloudbus.cloudsim.ex.mapreduce.models.request.*;
import org.cloudbus.cloudsim.ex.util.CustomLog;
import org.cloudbus.cloudsim.ex.util.TextUtil;
import org.cloudbus.cloudsim.lists.VmList;

public class MapReduceEngine extends DatacenterBroker {

	public MapReduceEngine() throws Exception {
		super("MapReduceEngine");
	}

	private Cloud cloud;

	public Cloud getCloud() {
		return cloud;
	}

	public void setCloud(Cloud cloud) {
		this.cloud = cloud;
	}

	private Requests requests;

	public Requests getRequests() {
		return requests;
	}

	public void setRequests(Requests requests) {
		this.requests = requests;

		for (Request request : requests.requests) {
			submitCloudletList(request.job.mapTasks);
			submitCloudletList(request.job.reduceTasks);
			
			//Set the MapReduce Engine referance to all tasks.
			for (Task task : request.job.mapTasks)
				task.mapReduceEngine = this;
			for (Task task : request.job.reduceTasks)
				task.mapReduceEngine = this;
		}
	}

	@Override
	public void processEvent(SimEvent ev) {
		switch (ev.getTag()) {
		// Resource characteristics request
		case CloudSimTags.RESOURCE_CHARACTERISTICS_REQUEST:
			processResourceCharacteristicsRequest(ev);
			break;
		// Resource characteristics answer
		case CloudSimTags.RESOURCE_CHARACTERISTICS:
			processResourceCharacteristics(ev);
			break;
		// create Vms In Datacenters for a request
		case CloudSimTags.VM_CREATE:
			vmProvisioning_taskScheduling((Request) ev.getData());
			break;
		// VM Creation answer
		case CloudSimTags.VM_CREATE_ACK:
			processVmCreate(ev);
			break;
		// A finished cloudlet returned
		case CloudSimTags.CLOUDLET_RETURN:
			processCloudletReturn(ev);
			break;
		// if the simulation finishes
		case CloudSimTags.END_OF_SIMULATION:
			shutdownEntity();
			break;
		// other unknown tags are processed by this method
		default:
			processOtherEvent(ev);
			break;
		}
	}

	protected void processResourceCharacteristics(SimEvent ev) {
		DatacenterCharacteristics characteristics = (DatacenterCharacteristics) ev.getData();
		getDatacenterCharacteristicsList().put(characteristics.getId(), characteristics);

		// If the last Datacenter
		if (getDatacenterCharacteristicsList().size() == getDatacenterIdsList().size()) {
			setDatacenterRequestedIdsList(new ArrayList<Integer>());
			// For each request send VM_CREATE to my self (the engine) with the
			// delay based on the Yaml
			for (Request request : requests.requests)
				send(getId(), request.submissionTime, CloudSimTags.VM_CREATE, request);
		}
	}

	protected void vmProvisioning_taskScheduling(Request request) {

		String policyName = request.policy;
		Policy policy = null;
		try {
			Class<?> policyClass = Class.forName(policyName, false, Policy.class.getClassLoader());
			policy = (Policy) policyClass.newInstance();

		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		// ToDo: increase the clock during the ALGORITHM search
		Log.printLine(" =========== SEARCHING START USING ALGORITHM:"+ policyName +" FOR REQUEST: " + request.id + " : ["+ request.submissionTime + ","+ request.budget +","+request.deadline +","+ request.jobFile+","+request.userClass +"]===========");
		Log.printLine(getName() + " is searching for the optimal Resource Set...");
		if(!policy.runAlgorithm(cloud, request))
			Log.printLine(" =========== ERROR: THE ALGORITHM COULD NOT FIND VMs FOR REQUEST: " + request.id + " ===========");
		else
		{
			//Update all tasks with the new length. Because of the data transfer.
			for (MapTask mapTask : request.job.mapTasks)
				mapTask.updateCloudletLength();
			for (ReduceTask reduceTask : request.job.reduceTasks)
				reduceTask.updateCloudletLength();
			
		}
		// Provision all types of virtual machines from Cloud
		Log.printLine(" =========== ALGORITHM: FINISHED SEARCHING FOR REQUEST: " + request.id + " ===========");
		// 1- VMs provisioning for those has at least one map task
		// Add vms to vmList
		Log.printLine(getName() + " SELECTED THE FOLLOWING VMs (Map and Reduce):-");
		submitVmList(request.mapAndReduceVmProvisionList);
		for (VmInstance vmInstance : request.mapAndReduceVmProvisionList) {
			Log.printLine("-M & R  VM ID: " + vmInstance.getId() + " of Type: "+vmInstance.name);
		}
		
		// Print VMs provisioning for reduce only tasks (just print)
		if(request.reduceOnlyVmProvisionList.size() == 0)
			Log.printLine(getName() + " No VMs with reduce only");
		else
		{
			Log.printLine(getName() + " SELECTED THE FOLLOWING VMs (Reduce Only) [NOT PROVISIONED YET]:-");
			for (VmInstance vmInstance : request.reduceOnlyVmProvisionList) {
				Log.printLine("-R only VM ID: " + vmInstance.getId() + " of Type: "+vmInstance.name);
			
			}
		}

		// 2- Map and Reduce Tasks scheduling
		// Bind/Schedule Map and Reduce tasks to VMs based on the ResourceSet
		for (Cloudlet task : getCloudletList()) {
			int taskId = task.getCloudletId();
			if (request.schedulingPlan.containsKey(taskId)) {
				int vmId = request.schedulingPlan.get(taskId);
				bindCloudletToVm(taskId, vmId);
			}
		}


		//3- Send VMs provisioning for those has at least one map task to datacentres
		int requestedVms = 0;
		for (int datacenterId : getDatacenterIdsList()) {
			CloudDatacenter cloudDatacenter = cloud.getCloudDatacenterFromId(datacenterId);
			for (VmInstance vm : request.mapAndReduceVmProvisionList) {
				if (cloudDatacenter.isVMInCloudDatacenter(vm.VmTypeId)) {
					Log.printLine(CloudSim.clock() + ": " + getName() + ": creating VM #" + vm.getId() + " in "
							+ cloudDatacenter.getName());
					sendNow(datacenterId, CloudSimTags.VM_CREATE_ACK, (Vm) vm);
					requestedVms++;
				}
			}
			getDatacenterRequestedIdsList().add(datacenterId);
		}

		setVmsRequested(requestedVms);
		setVmsAcks(0);

	}

	protected void processVmCreate(SimEvent ev) {
		int[] data = (int[]) ev.getData();
		int datacenterId = data[0];
		int vmId = data[1];
		int result = data[2];
		// int requestId = data[3];

		// Request request = requests.getRequestFromId(requestId);

		if (result == CloudSimTags.TRUE) {
			getVmsToDatacentersMap().put(vmId, datacenterId);
			getVmsCreatedList().add(VmList.getById(getVmList(), vmId));
			Log.printLine(CloudSim.clock() + ": " + getName() + ": VM #" + vmId + " has been created in Datacenter #"
					+ datacenterId + ", Host #" + VmList.getById(getVmsCreatedList(), vmId).getHost().getId());
		} else {
			Log.printLine(CloudSim.clock() + ": " + getName() + ": Creation of VM #" + vmId + " failed in Datacenter #"
					+ datacenterId);
		}

		incrementVmsAcks();

		// all the requested VMs have been created
		if (getVmsCreatedList().size() == getVmList().size() - getVmsDestroyed()) {
			submitCloudlets();
		} else {
			// all the acks received, but some VMs were not created
			if (getVmsRequested() == getVmsAcks()) {
				// find id of the next datacenter that has not been tried
				for (int nextDatacenterId : getDatacenterIdsList()) {
					if (!getDatacenterRequestedIdsList().contains(nextDatacenterId)) {
						try {
							throw new Exception("all the acks received, but some VMs were not created");
						} catch (Exception e) {
							e.printStackTrace();
						}
						return;
					}
				}

				// all datacenters already queried
				if (getVmsCreatedList().size() > 0) { // if some vm were created
					submitCloudlets();
				} else { // no vms created. abort
					Log.printLine(CloudSim.clock() + ": " + getName()
							+ ": none of the required VMs could be created. Aborting");
					finishExecution();
				}
			}
		}
	}

	protected void submitCloudlets() {
		int vmIndex = 0;
		for (Cloudlet cloudlet : getCloudletList()) {
			
			//Skip reduce task if there is still map tasks not finished
			if(cloudlet instanceof ReduceTask && !isAllMapTaskFinished(cloudlet.getCloudletId()))
				continue;
			
			// if(!request.isTaskInThisRequest(cloudlet.getCloudletId()))
			// continue;
			if (cloudlet.getVmId() == -1)
				continue;
			Vm vm;
			// if user didn't bind this cloudlet and it has not been executed
			// yet
			if (cloudlet.getVmId() == -1) {
				vm = getVmsCreatedList().get(vmIndex);
			} else { // submit to the specific vm
				vm = VmList.getById(getVmsCreatedList(), cloudlet.getVmId());
				if (vm == null) { // vm was not created
					Log.printLine(CloudSim.clock() + ": " + getName() + ": Postponing execution of cloudlet "
							+ cloudlet.getCloudletId() + ": bount VM not available");
					continue;
				}
			}

			Log.printLine(CloudSim.clock() + ": " + getName() + ": Sending cloudlet " + cloudlet.getCloudletId()
					+ " to VM #" + vm.getId());
			cloudlet.setVmId(vm.getId());
			sendNow(getVmsToDatacentersMap().get(vm.getId()), CloudSimTags.CLOUDLET_SUBMIT, cloudlet);
			cloudletsSubmitted++;
			vmIndex = (vmIndex + 1) % getVmsCreatedList().size();
			getCloudletSubmittedList().add(cloudlet);
		}

		// remove submitted cloudlets from waiting list
		for (Cloudlet cloudlet : getCloudletSubmittedList()) {
			getCloudletList().remove(cloudlet);
		}
	}
	
	private void startReducePhase(Request request) {
		Log.printLine(getName() + " REDUCE PHASE for Request ID: "+request.id + " is started");
		
		// VM provisioning for reduce only tasks - reduce tasks will start after the provisioning
		if(request.reduceOnlyVmProvisionList.size() != 0)
		{
			submitVmList(request.reduceOnlyVmProvisionList);
			
			Log.printLine(getName() + " PROVISIONING NOW THE FOLLOWING VMs (Reduce Only) [Request ID: "+request.id+"]:-");
		
			for (VmInstance vmInstance : request.reduceOnlyVmProvisionList) {
				VmType vmType = cloud.getVMTypeFromId(vmInstance.VmTypeId);
					Log.printLine("-Provisioning R only VM: " + vmInstance.name + " (ID: " + vmInstance.getId() + ") of Type: "+vmType.name);
			}
			
			//reduceOnlyVmProvisionList VM provisioning
			int requestedVms = 0;
			for (int datacenterId : getDatacenterIdsList()) {
				CloudDatacenter cloudDatacenter = cloud.getCloudDatacenterFromId(datacenterId);
				for (VmInstance vm : request.reduceOnlyVmProvisionList) {
					if (cloudDatacenter.isVMInCloudDatacenter(vm.VmTypeId)) {
						Log.printLine(CloudSim.clock() + ": " + getName() + ": creating VM #" + vm.getId() + " in "
								+ cloudDatacenter.getName());
						sendNow(datacenterId, CloudSimTags.VM_CREATE_ACK, (Vm) vm);
						requestedVms++;
					}
				}
				getDatacenterRequestedIdsList().add(datacenterId);
			}
			
			setVmsRequested(requestedVms);
			setVmsAcks(0);
		}
		else
		{
			//If all reduce tasks are running in the same map VMs, then just submit reduce tasks.
			submitCloudlets();
		}
	}
	
	protected void processCloudletReturn(SimEvent ev) {
		Task cloudlet = (Task) ev.getData();
		getCloudletReceivedList().add(cloudlet);
		Log.printLine(CloudSim.clock() + ": " + getName() + ": Cloudlet " + cloudlet.getCloudletId()
				+ " finished executing");
		cloudlet.isFinished = true;
		cloudletsSubmitted--;
		
		//NEW CODE
		//CHECK: if all map tasks finished to start the reduce phase
		if(isAllMapTaskFinished(cloudlet.getCloudletId()))
		{
			Request request = requests.getRequestFromTaskId(cloudlet.getCloudletId());
			startReducePhase(request);
			return;
		}
		//FINISHED NEW CODE
		
		if (getCloudletList().size() == 0 && cloudletsSubmitted == 0) { // all cloudlets executed
			Log.printLine(CloudSim.clock() + ": " + getName() + ": All Cloudlets executed. Finishing...");
			clearDatacenters();
			finishExecution();
		} else { // some cloudlets haven't finished yet
			if (getCloudletList().size() > 0 && cloudletsSubmitted == 0) {
				// all the cloudlets sent finished. It means that some bount
				// cloudlet is waiting its VM be created
				clearDatacenters();
				createVmsInDatacenter(0);
			}

		}
	}

	private boolean isAllMapTaskFinished(int cloudletId) {
		Request request = requests.getRequestFromTaskId(cloudletId);
		for (MapTask mapTask : request.job.mapTasks) {
			if (!mapTask.isFinished)
				return false;
		}
		return true;
	}

	public List<VmType> getVMTypesFromIds(List<Integer> selectedVMIds) {
		List<VmType> selectedVMTypes = new ArrayList<VmType>();
		for (int vmId : selectedVMIds) {
			VmType vmType = cloud.getVMTypeFromId(vmId);
			if (!selectedVMTypes.contains(vmType))
				selectedVMTypes.add(vmType);
		}

		return selectedVMTypes;
	}

	// Output information supplied at the end of the simulation
	public void printExecutionSummary() {
		DecimalFormat dft = new DecimalFormat("000000.00");
		String indent = "\t";

		Log.printLine("========== MAPREDUCE EXECUTION SUMMARY ==========");
		Log.printLine("= Request " + indent + "Task " + indent + "Type" + indent + "Status" + indent + indent
				+ "Submission Time" + indent + "Start Time" + indent + "Execution Time (s)" + indent + "Finish Time"
				+ indent + "VM ID" + indent + "VM Type");

		CustomLog.redirectToFile("results/tasks.csv");
		CustomLog.printResults(Task.class, ",",  getCloudletReceivedList());
		
		////......
		for (Cloudlet cloudlet : getCloudletReceivedList()) {
			Task task = (Task) cloudlet;
			Log.print(" = " + task.requestId + indent + indent + task.getCloudletId() + indent);

			if (task instanceof MapTask)
				Log.print("Map");
			else if (task instanceof ReduceTask)
				Log.print("Reduce");
			else
				Log.print("OTHER!!!! WTF");

			if (task.getCloudletStatus() == Cloudlet.SUCCESS) {
				Log.print(indent + "SUCCESS");

				VmInstance vm = requests.getVmInstance(task.getVmId());


				double executionTime = task.getFinishTime() - task.getExecStartTime();
				Log.printLine(indent + indent + dft.format(task.getSubmissionTime()) + indent
						+ dft.format(task.getExecStartTime()) + indent + dft.format(executionTime) + indent + indent
						+ dft.format(task.getFinishTime()) + indent + vm.getId() + indent + vm.name);

				// Set the executionTime in the vm
				vm.ExecutionTime += executionTime;

				// set the first Submission Time and last execution time on
				// request
				Request request = requests.getRequestFromId(task.requestId);

				if (task.getSubmissionTime() == -1)
					request.firstSubmissionTime = task.getSubmissionTime();
				else if (task.getSubmissionTime() < request.firstSubmissionTime)
					request.firstSubmissionTime = task.getSubmissionTime();

				if (request.firstSubmissionTime == -1)
					request.firstSubmissionTime = task.getFinishTime();
				else if (task.getFinishTime() > request.lastFinishTime)
					request.lastFinishTime = task.getFinishTime();
			} else if (task.getCloudletStatus() == Cloudlet.FAILED) {
				Log.printLine("FAILED");
			} else if (task.getCloudletStatus() == Cloudlet.CANCELED) {
				Log.printLine("CANCELLED");
			}
		}

		Log.printLine();

		
		for (Request request : requests.requests) {
			Log.printLine(" ======== Request ID: " + request.id + " - USER CLASS: [" + request.userClass + "]");
			Log.printLine(" Policy: "+request.policy);
			Log.printLine("= VMs: ");

			for (VmType vmType : request.mapAndReduceVmProvisionList) {
				Log.printLine("   - VM ID#" + vmType.getId() + ": " + vmType.name);
				Log.printLine("     > Processing Time: " + vmType.ExecutionTime + " seconds");
				double cost = Math.ceil(vmType.ExecutionTime / 3600.0) * vmType.cost;
				Log.printLine("     > Cost: $" + cost);
				request.totalCost += cost;
			}
			
			for (VmType vmType : request.reduceOnlyVmProvisionList) {
				Log.printLine("   - VM ID#" + vmType.getId() + ": " + vmType.name);
				Log.printLine("     > Processing Time: " + vmType.ExecutionTime + " seconds");
				double cost = Math.ceil(vmType.ExecutionTime / 3600.0) * vmType.cost;
				Log.printLine("     > Cost: $" + cost);
				request.totalCost += cost;
			}
			double jobExecutionTime = request.lastFinishTime - request.firstSubmissionTime;
			boolean TimeViolation = (jobExecutionTime > request.deadline);
			boolean costViolation = (request.totalCost > request.budget);
			
			
			Log.printLine("= QoS: Deadline: " + request.deadline + " seconds");
			Log.printLine("= QoS: Budget: $" + request.budget);
			Log.printLine("= Deadline Violation: " + TimeViolation);
			Log.printLine("= Budget Violation: " + costViolation);
			Log.printLine("= Execution Time: " + jobExecutionTime + " seconds");
			Log.printLine("= Cost: $" + request.totalCost);
			Log.printLine();
		}
		Log.printLine("========== END OF SUMMARY =========");
		Log.printLine();
	}
	
	// Output information supplied at the end of the simulation
	public List<Double[]> getExecutionTimeAndCost() {
		for (Cloudlet cloudlet : getCloudletReceivedList()) {
			Task task = (Task) cloudlet;
			if (task.getCloudletStatus() == Cloudlet.SUCCESS) {
				VmInstance vm = requests.getVmInstance(task.getVmId());
				double executionTime = task.getFinishTime() - task.getExecStartTime();
				vm.ExecutionTime += executionTime;

				// set the first Submission Time and last execution time on the request
				Request request = requests.getRequestFromId(task.requestId);

				if (task.getSubmissionTime() == -1)
					request.firstSubmissionTime = task.getSubmissionTime();
				else if (task.getSubmissionTime() < request.firstSubmissionTime)
					request.firstSubmissionTime = task.getSubmissionTime();

				if (request.firstSubmissionTime == -1)
					request.firstSubmissionTime = task.getFinishTime();
				else if (task.getFinishTime() > request.lastFinishTime)
					request.lastFinishTime = task.getFinishTime();
			}
		}

		List<Double[]> ExecutionTimesAndCosts = new ArrayList<Double[]>();
		
		for (Request request : requests.requests) {
			for (VmType vmType : request.mapAndReduceVmProvisionList) {
				double cost = Math.ceil(vmType.ExecutionTime / 3600.0) * vmType.cost;
				request.totalCost += cost;
			}
			
			for (VmType vmType : request.reduceOnlyVmProvisionList) {
				double cost = Math.ceil(vmType.ExecutionTime / 3600.0) * vmType.cost;
				request.totalCost += cost;
			}

			double jobExecutionTime = request.lastFinishTime - request.firstSubmissionTime;
			
			ExecutionTimesAndCosts.add(new Double[]{jobExecutionTime,request.totalCost});
		}
		
		return ExecutionTimesAndCosts;
	}
}
