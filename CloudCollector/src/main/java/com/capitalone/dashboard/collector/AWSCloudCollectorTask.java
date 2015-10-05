/*************************DA-BOARD-LICENSE-START*********************************
 * Copyright 2014 CapitalOne, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *************************DA-BOARD-LICENSE-END*********************************/

package com.capitalone.dashboard.collector;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClient;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import com.capitalone.dashboard.model.AWSConfig;
import com.capitalone.dashboard.model.CloudComputeAggregatedData;
import com.capitalone.dashboard.model.CloudComputeRawData;
import com.capitalone.dashboard.model.Collector;
import com.capitalone.dashboard.repository.AWSConfigRepository;
import com.capitalone.dashboard.repository.BaseCollectorRepository;
import com.capitalone.dashboard.repository.CloudAggregatedDataRepository;
import com.capitalone.dashboard.repository.CloudRawDataRepository;

/**
 * Collects {@link AWSCloudCollector} data from feature content source system.
 * 
 * @author
 * @param <ObjectId>
 */
@Component
public class AWSCloudCollectorTask extends CollectorTask<AWSCloudCollector> {
	private static final Log logger = LogFactory
			.getLog(AWSCloudCollectorTask.class);

	private final CloudRawDataRepository awsRawDataRepository;
	private final CloudAggregatedDataRepository awsAggregatedDataRepository;
	private final AWSCloudSettings awsSetting;
	private final AWSCloudClient awsClient;
	private final AWSConfigRepository awsConfigRepository;
	private final BaseCollectorRepository<AWSCloudCollector> collectorRepository;
	private final Iterable<CloudComputeAggregatedData> allAggregatedData; 

	/**
	 * Default constructor for the collector task. This will construct this
	 * collector task with all repository, scheduling, and settings
	 * configurations custom to this collector.
	 * 
	 * @param taskScheduler
	 *            A task scheduler artifact
	 * @param teamRepository
	 *            The repository being use for feature collection
	 * @param featureSettings
	 *            The settings being used for feature collection from the source
	 *            system
	 */
	@Autowired
	public AWSCloudCollectorTask(TaskScheduler taskScheduler,
			BaseCollectorRepository<AWSCloudCollector> collectorRepository,
			CloudRawDataRepository awsObjectRepository,
			AWSCloudSettings cloudSettings, AWSCloudClient cloudClient,
			AWSConfigRepository awsConfigRepository,
			CloudAggregatedDataRepository awsAggregatedRepository) {
		super(taskScheduler, "AWSCloud");
		this.collectorRepository = collectorRepository;
		this.awsClient = cloudClient;
		this.awsRawDataRepository = awsObjectRepository;
		this.awsSetting = cloudSettings;
		this.awsConfigRepository = awsConfigRepository;
		this.awsAggregatedDataRepository = awsAggregatedRepository;
		this.allAggregatedData= awsAggregatedDataRepository.findAll();
	}

	public AWSCloudCollector getCollector() {
		return AWSCloudCollector.prototype();
	}

	/**
	 * Accessor method for the collector repository
	 */
	public CloudRawDataRepository getAWSObjectRepository() {
		return awsRawDataRepository;
	}

	/**
	 * Accessor method for the current chronology setting, for the scheduler
	 */
	public String getCron() {
		return awsSetting.getCron();
	}

	/**
	 * The collection action. This is the task which will run on a schedule to
	 * gather data from the feature content source system and update the
	 * repository with retrieved data.
	 */
	public void collect(AWSCloudCollector collector) {
		logger.info("Starting AWS collection...");

		ClientConfiguration clientConfig = new ClientConfiguration()
				.withProxyHost(awsSetting.getProxyURL())
				.withProxyPort(awsSetting.getProxyPort())
				.withPreemptiveBasicProxyAuth(true)
				.withProxyUsername(awsSetting.getProxyUser())
				.withProxyPassword(awsSetting.getProxyPassword());

		List<AWSConfig> enabledList = enabledConfigs(collector);
		for (AWSConfig config : enabledList) {
			String accessKey = config.getAccessKey();
			String secretKey = config.getSecretKey();

			AWSCredentials creds = new BasicAWSCredentials(accessKey, secretKey);
			AmazonEC2Client ec2Client = new AmazonEC2Client(creds, clientConfig);
			AmazonCloudWatchClient cwClient = new AmazonCloudWatchClient(creds,
					clientConfig);
			DescribeInstancesResult result = ec2Client.describeInstances();
			// create list of instances
			List<Instance> instanceList = new ArrayList<Instance>();
			List<Reservation> reservations = result.getReservations();
			for (Reservation currRes : reservations) {
				List<Instance> currInstanceList = currRes.getInstances();
				instanceList.addAll(currInstanceList);
			}
			
			//delete old data
			CloudComputeAggregatedData oldData = findAggregatedDataByConfig(config);
			if (oldData != null) {
				awsAggregatedDataRepository.delete(oldData);
			}

			ArrayList<CloudComputeRawData> rawDataList = new ArrayList<CloudComputeRawData> ();
			// for every instance determine all metrics
			logger.info("Collecting Raw Data...");
			for (Instance currInstance : instanceList) {
				CloudComputeRawData object = awsClient.getMetrics(currInstance,
						cwClient, accessKey);
				object.setCollectorItemId(config.getId());
				System.out.println("Collector Item ID:"
						+ object.getCollectorItemId());
				rawDataList.add(object);
				awsRawDataRepository.save(object);
			}


			logger.info("Agregating Data...");
			CloudComputeAggregatedData aggregatedData = new CloudComputeAggregatedData();
			ObjectId id = config.getId();
			aggregatedData.setAgeWarning(awsRawDataRepository.runAgeWarning(id)
					.size());
			aggregatedData.setAgeExpired(awsRawDataRepository.runAgeExpired(id)
					.size());
			aggregatedData.setAgeGood(awsRawDataRepository.runAgeGood(id)
					.size());
			aggregatedData.setCpuHigh(awsRawDataRepository
					.runCpuUtilizationHigh(id).size());
			aggregatedData.setCpuMid(awsRawDataRepository.runCpuUtilizationMid(
					id).size());
			aggregatedData.setCpuLow(awsRawDataRepository.runCpuUtilizationLow(
					id).size());
			aggregatedData.setNonEncryptedCount(awsRawDataRepository
					.runNonEncrypted(id).size());
			aggregatedData.setNonTaggedCount(awsRawDataRepository.runNonTagged(
					id).size());
			aggregatedData.setStoppedCount(awsRawDataRepository.runStopped(id)
					.size());
			aggregatedData.setCollectorItemId(config.getId());
			aggregatedData.setTotalInstanceCount(awsRawDataRepository
					.runAllInstanceCount(id).size());
			aggregatedData.setCollectorItemId(id);
			aggregatedData.setDetailList(rawDataList);
			awsAggregatedDataRepository.save(aggregatedData);

		}
		logger.info("Finished Cloud collection.");
	}

	public CloudComputeAggregatedData findAggregatedDataByConfig(AWSConfig config) {
		CloudComputeAggregatedData returnData = null;
		for (CloudComputeAggregatedData data : this.allAggregatedData) {
			returnData = data;
		}
		return returnData;
	}
	@Override
	public BaseCollectorRepository<AWSCloudCollector> getCollectorRepository() {
		return collectorRepository;
	}

	private List<AWSConfig> enabledConfigs(Collector collector) {
		return awsConfigRepository.findEnabledAWSConfig(collector.getId());
	}
}