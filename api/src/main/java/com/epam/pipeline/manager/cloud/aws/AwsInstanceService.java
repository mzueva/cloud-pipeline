/*
 * Copyright 2017-2019 EPAM Systems, Inc. (https://www.epam.com/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.epam.pipeline.manager.cloud.aws;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.services.ec2.model.Instance;
import com.epam.pipeline.entity.cloud.InstanceTerminationState;
import com.epam.pipeline.entity.pipeline.RunInstance;
import com.epam.pipeline.entity.region.AwsRegion;
import com.epam.pipeline.entity.region.CloudProvider;
import com.epam.pipeline.exception.CmdExecutionException;
import com.epam.pipeline.exception.cloud.aws.AwsEc2Exception;
import com.epam.pipeline.manager.CmdExecutor;
import com.epam.pipeline.manager.cloud.CloudInstanceService;
import com.epam.pipeline.manager.cloud.CommonCloudInstanceService;
import com.epam.pipeline.manager.cloud.commands.NodeUpCommand;
import com.epam.pipeline.manager.cluster.InstanceOfferManager;
import com.epam.pipeline.manager.execution.SystemParams;
import com.epam.pipeline.manager.preference.PreferenceManager;
import com.epam.pipeline.manager.preference.SystemPreferences;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
public class AwsInstanceService implements CloudInstanceService<AwsRegion> {

    private static final String MANUAL = "manual";
    private static final String ON_DEMAND = "on_demand";

    private final EC2Helper ec2Helper;
    private final PreferenceManager preferenceManager;
    private final InstanceOfferManager instanceOfferManager;
    private final CommonCloudInstanceService instanceService;
    private final CmdExecutor cmdExecutor = new CmdExecutor();

    public AwsInstanceService(final EC2Helper ec2Helper,
                              final PreferenceManager preferenceManager,
                              final InstanceOfferManager instanceOfferManager,
                              final CommonCloudInstanceService instanceService) {
        this.ec2Helper = ec2Helper;
        this.preferenceManager = preferenceManager;
        this.instanceOfferManager = instanceOfferManager;
        this.instanceService = instanceService;
    }

    @Override
    public RunInstance scaleUpNode(final AwsRegion region,
                                   final Long runId,
                                   final RunInstance instance) {
        final String command = buildNodeUpCommand(region, runId, instance);
        return instanceService.runNodeUpScript(cmdExecutor, runId, instance, command, Collections.emptyMap());
    }

    @Override
    public void scaleDownNode(final AwsRegion region, final Long runId) {
        final String command = instanceService.buildNodeDownCommand(runId, CloudProvider.AWS.name());
        instanceService.runNodeDownScript(cmdExecutor, command, Collections.emptyMap());
    }

    //TODO: This code won't work for current scripts
    @Override
    public void scaleUpFreeNode(final AwsRegion region, final String nodeId) {
        String command = buildNodeUpDefaultCommand(region, nodeId);
        log.debug("Creating default free node. Command: {}.", command);
        //TODO: issue token for some default user???
        executeCmd(command, Collections.emptyMap());
    }

    @Override
    public void terminateNode(final AwsRegion region, final String internalIp, final String nodeName) {
        final String command = instanceService.buildTerminateNodeCommand(internalIp, nodeName,
                CloudProvider.AWS.name());
        instanceService.runTerminateNodeScript(command, cmdExecutor, Collections.emptyMap());
    }

    @Override
    public void startInstance(final AwsRegion region, final String instanceId) {
        log.debug("Starting AWS instance {}", instanceId);
        ec2Helper.startInstance(instanceId, region.getRegionCode());
    }

    @Override
    public void stopInstance(final AwsRegion region, final String instanceId) {
        log.debug("Stopping AWS instance {}", instanceId);
        ec2Helper.stopInstance(instanceId, region.getRegionCode());
    }

    @Override
    public CloudProvider getProvider() {
        return CloudProvider.AWS;
    }

    @Override
    public LocalDateTime getNodeLaunchTime(final AwsRegion region, final Long runId) {
        return ec2Helper.getInstanceLaunchTime(String.valueOf(runId), region.getRegionCode());
    }

    @Override
    public RunInstance describeInstance(final AwsRegion region,
                                        final String nodeLabel,
                                        final RunInstance instance) {
        log.debug("Getting instance description for label {}.", nodeLabel);
        try {
            final Instance ec2Instance = ec2Helper.getActiveInstance(nodeLabel, region.getRegionCode());
            instance.setNodeId(ec2Instance.getInstanceId());
            instance.setNodeIP(ec2Instance.getPrivateIpAddress());
            instance.setNodeName(ec2Instance.getPrivateDnsName().split("\\.")[0]);
            return instance;
        } catch (AwsEc2Exception e) {
            log.debug("Instance for label {} not found", nodeLabel);
            log.trace(e.getMessage(), e);
            return null;
        }
    }

    @Override
    public boolean reassignNode(final AwsRegion region, final Long oldId, final Long newId) {
        return instanceService.runNodeReassignScript(
                oldId, newId, CloudProvider.AWS.name(), cmdExecutor, Collections.emptyMap());
    }

    @Override
    public Map<String, String> buildContainerCloudEnvVars(final AwsRegion region) {
        final Map<String, String> envVars = new HashMap<>();
        envVars.put(SystemParams.CLOUD_REGION_PREFIX + region.getId(), region.getRegionCode());
        final AWSCredentials credentials = AWSUtils.getCredentialsProvider(region).getCredentials();
        envVars.put(SystemParams.CLOUD_ACCOUNT_PREFIX + region.getId(), credentials.getAWSAccessKeyId());
        envVars.put(SystemParams.CLOUD_ACCOUNT_KEY_PREFIX + region.getId(), credentials.getAWSSecretKey());
        envVars.put(SystemParams.CLOUD_PROVIDER_PREFIX + region.getId(), region.getProvider().name());
        return envVars;
    }

    @Override
    public Optional<InstanceTerminationState> getInstanceTerminationState(final AwsRegion region,
                                                                          final String instanceId) {
        return ec2Helper.getInstanceStateReason(instanceId, region.getRegionCode())
                .map(state -> InstanceTerminationState.builder()
                        .instanceId(instanceId)
                        .stateCode(state.getCode())
                        .stateMessage(state.getMessage())
                        .build());
    }

    private String buildNodeUpCommand(final AwsRegion region,
                                      final Long runId,
                                      final RunInstance instance) {
        NodeUpCommand.NodeUpCommandBuilder commandBuilder =
                instanceService.buildNodeUpCommonCommand(region, runId, instance, CloudProvider.AWS.name())
                               .sshKey(region.getSshKeyName());

        if (StringUtils.isNotBlank(region.getKmsKeyId())) {
            commandBuilder.encryptionKey(region.getKmsKeyId());
        }
        addSpotArguments(instance, commandBuilder, region.getId());
        return commandBuilder.build().getCommand();
    }

    private void addSpotArguments(final RunInstance instance,
                                  final NodeUpCommand.NodeUpCommandBuilder command,
                                  final Long regionId) {
        final Boolean instanceSpotFlag = instance.getSpot();
        final boolean isSpot = preferenceManager.getPreference(SystemPreferences.CLUSTER_SPOT);

        final boolean useSpot = (instanceSpotFlag == null && isSpot)
                || (instanceSpotFlag != null && instanceSpotFlag);
        if (useSpot) {
            setBidPrice(command, instance.getNodeType(), regionId);
        }
    }

    private Double customizeSpotArguments(final String instanceType, final Long regionId) {
        final String spotAllocStrategy = preferenceManager.getPreference(SystemPreferences.CLUSTER_SPOT_ALLOC_STRATEGY);
        final Double bidPrice = preferenceManager.getPreference(SystemPreferences.CLUSTER_SPOT_BID_PRICE);
        switch (spotAllocStrategy) {
            case MANUAL:
                if (bidPrice == null) {
                    log.error("Spot price must be specified in \'" + MANUAL + "\' case.");
                }
                return bidPrice;
            case ON_DEMAND:
                return instanceOfferManager.getPricePerHourForInstance(instanceType, regionId);
            default:
                log.error("Argument spot_alloc_strategy must have \'" + MANUAL + "\' or \'"
                        + ON_DEMAND + "\' value.");
                return bidPrice;
        }
    }

    private String buildNodeUpDefaultCommand(final AwsRegion region, final String nodeId) {

        NodeUpCommand.NodeUpCommandBuilder commandBuilder =
                instanceService.buildNodeUpDefaultCommand(region, nodeId, CloudProvider.AWS.name())
                               .sshKey(region.getSshKeyName());

        if (StringUtils.isNotBlank(region.getKmsKeyId())) {
            commandBuilder.encryptionKey(region.getKmsKeyId());
        }
        if (preferenceManager.getPreference(SystemPreferences.CLUSTER_SPOT)) {
            setBidPrice(commandBuilder, preferenceManager.getPreference(
                    SystemPreferences.CLUSTER_INSTANCE_TYPE), region.getId());
        }
        return commandBuilder.build().getCommand();
    }

    private void setBidPrice(final NodeUpCommand.NodeUpCommandBuilder commandBuilder,
                             final String preference,
                             final Long id) {
        final Double bidPrice = customizeSpotArguments(preference, id);
        commandBuilder.isSpot(true);
        commandBuilder.bidPrice(Optional.ofNullable(bidPrice)
                .map(p -> String.valueOf(bidPrice)).orElse(StringUtils.EMPTY));
    }

    private void executeCmd(final String command, final Map<String, String> envVars) {
        try {
            cmdExecutor.executeCommandWithEnvVars(command, envVars);
        } catch (CmdExecutionException e) {
            log.error(e.getMessage(), e);
        }
    }
}
