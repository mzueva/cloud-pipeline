/*
 * Copyright 2017-2021 EPAM Systems, Inc. (https://www.epam.com/)
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

package com.epam.pipeline.manager.execution;

import com.epam.pipeline.entity.cluster.DockerMount;
import com.epam.pipeline.entity.cluster.container.ContainerMemoryResourcePolicy;
import com.epam.pipeline.entity.cluster.container.ImagePullPolicy;
import com.epam.pipeline.entity.execution.OSSpecificLaunchCommandTemplate;
import com.epam.pipeline.entity.pipeline.PipelineRun;
import com.epam.pipeline.entity.pipeline.run.RunAssignPolicy;
import com.epam.pipeline.manager.cluster.KubernetesConstants;
import com.epam.pipeline.manager.cluster.KubernetesManager;
import com.epam.pipeline.manager.cluster.container.ContainerMemoryResourceService;
import com.epam.pipeline.manager.cluster.container.ContainerResources;
import com.epam.pipeline.manager.preference.PreferenceManager;
import com.epam.pipeline.manager.preference.SystemPreferences;
import com.epam.pipeline.manager.security.AuthManager;
import com.epam.pipeline.utils.CommonUtils;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EmptyDirVolumeSource;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HostPathVolumeSource;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodDNSConfig;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.SecurityContext;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.internal.PodOperationsImpl;
import io.fabric8.kubernetes.client.utils.HttpClientUtils;
import okhttp3.OkHttpClient;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class PipelineExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(PipelineExecutor.class);

    private static final String REF_DATA_MOUNT = "ref-data";
    private static final String RUNS_DATA_MOUNT = "runs-data";
    private static final String EMPTY_MOUNT = "dshm";
    private static final String NGINX_ENDPOINT = "nginx";
    private static final String TRUE = "true";
    private static final String USE_HOST_NETWORK = "CP_USE_HOST_NETWORK";
    private static final String DEFAULT_CPU_REQUEST = "1";
    private static final String CPU_REQUEST_NAME = "cpu";
    private static final DockerMount HOST_CGROUP_MOUNT = DockerMount.builder()
            .name("host-cgroups")
            .hostPath("/sys/fs/cgroup")
            .mountPath("/sys/fs/cgroup").build();
    private static final String DOMAIN_DELIMITER = "@";
    private static final String DEFAULT_KUBE_SERVICE_ACCOUNT = "default";

    private final PreferenceManager preferenceManager;
    private final String kubeNamespace;
    private final AuthManager authManager;
    private final KubernetesManager kubernetesManager;
    private final Map<ContainerMemoryResourcePolicy, ContainerMemoryResourceService> memoryRequestServices;

    public PipelineExecutor(final PreferenceManager preferenceManager,
                            final AuthManager authManager,
                            final List<ContainerMemoryResourceService> memoryRequestServices,
                            @Value("${kube.namespace}") final String kubeNamespace,
                            final KubernetesManager kubernetesManager) {
        this.preferenceManager = preferenceManager;
        this.kubeNamespace = kubeNamespace;
        this.authManager = authManager;
        this.memoryRequestServices = CommonUtils.groupByKey(memoryRequestServices,
                ContainerMemoryResourceService::policy);
        this.kubernetesManager = kubernetesManager;
    }

    public void launchRootPod(final String command, final PipelineRun run, final List<EnvVar> envVars,
                              final List<String> endpoints, final String pipelineId,
                              final RunAssignPolicy podAssignPolicy, final String secretName, final String clusterId) {
        launchRootPod(command, run, envVars, endpoints, pipelineId, podAssignPolicy,
                secretName, clusterId, ImagePullPolicy.ALWAYS, Collections.emptyMap(), null, null);
    }

    public void launchRootPod(final String command, final PipelineRun run, final List<EnvVar> envVars,
                              final List<String> endpoints, final String pipelineId,
                              final RunAssignPolicy podAssignPolicy, final String secretName, final String clusterId,
                              final ImagePullPolicy imagePullPolicy, Map<String, String> kubeLabels,
                              final String kubeServiceAccount,
                              final OSSpecificLaunchCommandTemplate commandTemplate) {
        try (KubernetesClient client = kubernetesManager.getKubernetesClient()) {
            final Map<String, String> labels = new HashMap<>();
            labels.put("spawned_by", "pipeline-api");
            labels.put("pipeline_id", pipelineId);
            labels.put("owner", normalizeOwner(run.getOwner()));
            if (Boolean.TRUE.equals(run.getSensitive())) {
                labels.put("sensitive", "true");
            }
            if (MapUtils.isNotEmpty(kubeLabels)) {
                labels.putAll(kubeLabels);
            }
            addWorkerLabel(clusterId, labels, run);
            LOGGER.debug("Root pipeline task ID: {}", run.getPodId());
            final Map<String, String> nodeSelector = new HashMap<>();
            final String runIdLabel = String.valueOf(run.getId());

            if (preferenceManager.getPreference(SystemPreferences.CLUSTER_ENABLE_AUTOSCALING)) {
                nodeSelector.put(podAssignPolicy.getSelector().getLabel(), podAssignPolicy.getSelector().getValue());
                // id pod ip == pipeline id we have a root pod, otherwise we prefer to skip pod in autoscaler
                if (run.getPodId().equals(pipelineId) &&
                        podAssignPolicy.isMatch(KubernetesConstants.RUN_ID_LABEL, runIdLabel)) {
                    labels.put(KubernetesConstants.TYPE_LABEL, KubernetesConstants.PIPELINE_TYPE);
                }
                labels.put(KubernetesConstants.RUN_ID_LABEL, runIdLabel);
            } else {
                nodeSelector.put("skill", "luigi");
            }

            labels.putAll(getServiceLabels(endpoints));

            final OkHttpClient httpClient = HttpClientUtils.createHttpClient(client.getConfiguration());
            final ObjectMeta metadata = getObjectMeta(run, labels);
            final String verifiedKubeServiceAccount = fetchVerifiedKubeServiceAccount(client, kubeServiceAccount);
            final PodSpec spec = getPodSpec(run, envVars, secretName, nodeSelector, podAssignPolicy.getTolerances(),
                    run.getActualDockerImage(), command, imagePullPolicy,
                    podAssignPolicy.isMatch(KubernetesConstants.RUN_ID_LABEL, runIdLabel),
                    verifiedKubeServiceAccount, commandTemplate);
            final Pod pod = new Pod("v1", "Pod", metadata, spec, null);
            final Pod created = new PodOperationsImpl(httpClient, client.getConfiguration(), kubeNamespace).create(pod);
            LOGGER.debug("Created POD: {}", created.toString());
        }
    }

    private String normalizeOwner(final String owner) {
        return splitName(owner).replaceAll(KubernetesConstants.KUBE_NAME_FULL_REGEXP, "-");
    }

    private String fetchVerifiedKubeServiceAccount(final KubernetesClient client, final String kubeServiceAccount) {
        if (StringUtils.isNotBlank(kubeServiceAccount)) {
            return client.serviceAccounts()
                .inNamespace(kubeNamespace).list().getItems().stream()
                .filter(serviceAccount -> serviceAccount.getMetadata().getName().equals(kubeServiceAccount))
                .map(serviceAccount -> serviceAccount.getMetadata().getName())
                .findFirst()
                .orElseGet(() -> {
                    LOGGER.warn(String.format(
                            "Can't find kube service account that was requested: %s! Default will be used",
                            kubeServiceAccount));
                    return DEFAULT_KUBE_SERVICE_ACCOUNT;
                });
        } else {
            return DEFAULT_KUBE_SERVICE_ACCOUNT;
        }
    }

    private String splitName(final String owner) {
        return owner.split(DOMAIN_DELIMITER)[0];
    }

    private void addWorkerLabel(final String clusterId, final Map<String, String> labels, final PipelineRun run) {
        final String clusterLabel = getChildLabel(clusterId, run);
        if (StringUtils.isNotBlank(clusterLabel)) {
            labels.put(KubernetesConstants.POD_WORKER_NODE_LABEL, clusterLabel);
        }
    }

    private String getChildLabel(final String clusterId, final PipelineRun run) {
        return StringUtils.defaultIfBlank(clusterId,
                Optional.ofNullable(run.getParentRunId()).map(String::valueOf).orElse(StringUtils.EMPTY));
    }

    private PodSpec getPodSpec(final PipelineRun run, final List<EnvVar> envVars, final String secretName,
                               final Map<String, String> nodeSelector, final Map<String, String> nodeTolerances,
                               final String dockerImage, final String command, final ImagePullPolicy imagePullPolicy,
                               final boolean isParentPod, final String kubeServiceAccount,
                               final OSSpecificLaunchCommandTemplate template) {
        final PodSpec spec = new PodSpec();
        spec.setRestartPolicy("Never");
        spec.setTerminationGracePeriodSeconds(
                preferenceManager.getPreference(SystemPreferences.KUBE_POD_GRACE_PERIOD_SECONDS));
        spec.setDnsPolicy("ClusterFirst");
        spec.setNodeSelector(nodeSelector);
        if (preferenceManager.getPreference(SystemPreferences.KUBE_POD_DOMAINS_ENABLED)) {
            configurePodDns(run, spec);
        }
        if (!StringUtils.isEmpty(secretName)) {
            spec.setImagePullSecrets(Collections.singletonList(new LocalObjectReference(secretName)));
        }
        final boolean isDockerInDockerEnabled = authManager.isAdmin() && isParameterEnabled(envVars,
                KubernetesConstants.CP_CAP_DIND_NATIVE);
        final boolean isSystemdEnabled = isParameterEnabled(envVars, KubernetesConstants.CP_CAP_SYSTEMD_CONTAINER);

        spec.setServiceAccountName(kubeServiceAccount);

        spec.setVolumes(getVolumes(isDockerInDockerEnabled, isSystemdEnabled));

        Optional.of(PodSpecMapperHelper.buildTolerations(nodeTolerances))
                .filter(CollectionUtils::isNotEmpty)
                .ifPresent(spec::setTolerations);

        if (envVars.stream().anyMatch(envVar -> envVar.getName().equals(USE_HOST_NETWORK))){
            spec.setHostNetwork(true);
        }

        spec.setContainers(Collections.singletonList(getContainer(run,
                envVars, dockerImage, command, imagePullPolicy,
                isDockerInDockerEnabled, isSystemdEnabled, isParentPod, template)));
        return spec;
    }

    private void configurePodDns(final PipelineRun run, final PodSpec spec) {
        spec.setHostname(run.getPodId());
        spec.setSubdomain(preferenceManager.getPreference(SystemPreferences.KUBE_POD_SUBDOMAIN));
        final PodDNSConfig podDNSConfig = new PodDNSConfig();
        podDNSConfig.setSearches(Collections.singletonList(preferenceManager.getPreference(
                SystemPreferences.KUBE_POD_SEARCH_PATH)));
        spec.setDnsConfig(podDNSConfig);
    }

    private boolean isParameterEnabled(List<EnvVar> envVars, String parameter) {
        return ListUtils.emptyIfNull(envVars)
                .stream()
                .anyMatch(env -> parameter.equals(env.getName()) && TRUE.equals(env.getValue()));
    }


    private Container getContainer(PipelineRun run,
                                   List<EnvVar> envVars,
                                   String dockerImage,
                                   String command,
                                   ImagePullPolicy imagePullPolicy,
                                   boolean isDockerInDockerEnabled,
                                   boolean isSystemdEnabled, boolean isParentPod,
                                   OSSpecificLaunchCommandTemplate template) {
        Container container = new Container();
        container.setName("pipeline");
        SecurityContext securityContext = new SecurityContext();
        securityContext.setPrivileged(true);
        container.setSecurityContext(securityContext);
        container.setEnv(envVars);
        container.setImage(dockerImage);
        String entryPoint = Optional.ofNullable(template.getEntrypoint()).orElse("/bin/bash");
        container.setCommand(Collections.singletonList(entryPoint));
        if (!StringUtils.isEmpty(command)) {
            List<String> args = CollectionUtils.isEmpty(template.getArgs()) ?
                    Collections.singletonList("-c") : template.getArgs();
            List<String> mergedArgs = new ArrayList<>(args);
            mergedArgs.add(command);
            container.setArgs(mergedArgs);
        }
        container.setVolumeMounts(getMounts(isDockerInDockerEnabled, isSystemdEnabled));
        container.setTerminationMessagePath("/dev/termination-log");
        container.setImagePullPolicy(imagePullPolicy.getName());
        container.setVolumeMounts(getMounts(isDockerInDockerEnabled, isSystemdEnabled));
        if (isParentPod) {
            buildContainerResources(run, envVars, container);
        }
        return container;
    }

    private void buildContainerResources(PipelineRun run, List<EnvVar> envVars, Container container) {
        final ContainerResources cpuResources = buildCpuRequests(envVars);
        final ContainerResources memoryResources = buildMemoryRequests(run, envVars);
        container.setResources(ContainerResources.merge(cpuResources, memoryResources)
                .toContainerRequirements());
    }

    private ContainerResources buildMemoryRequests(final PipelineRun run, final List<EnvVar> envVars) {
        final String policyName = ListUtils.emptyIfNull(envVars).stream()
                .filter(var -> SystemParams.CONTAINER_MEMORY_RESOURCE_POLICY.getEnvName().equals(var.getName()))
                .findFirst()
                .map(EnvVar::getValue)
                .orElse(preferenceManager.getPreference(SystemPreferences.LAUNCH_CONTAINER_MEMORY_RESOURCE_POLICY));
        final ContainerMemoryResourcePolicy policy = CommonUtils.getEnumValueOrDefault(
                policyName, ContainerMemoryResourcePolicy.NO_LIMIT);
        return memoryRequestServices.get(policy).buildResourcesForRun(run);
    }

    private ContainerResources buildCpuRequests(List<EnvVar> envVars) {
        return ListUtils.emptyIfNull(envVars).stream()
                .filter(var -> SystemParams.CONTAINER_CPU_RESOURCE.getEnvName().equals(var.getName()))
                .findFirst()
                .map(var -> {
                    if (NumberUtils.isDigits(var.getValue())) {
                        return var.getValue();
                    }
                    return DEFAULT_CPU_REQUEST;
                })
                .filter(cpuRequest -> Integer.parseInt(cpuRequest) > 0)
                .map(cpuRequest ->
                    ContainerResources.builder()
                            .requests(Collections.singletonMap(CPU_REQUEST_NAME, new Quantity(cpuRequest)))
                            .build())
                .orElse(ContainerResources.empty());
    }

    private List<Volume> getVolumes(final boolean isDockerInDockerEnabled, final boolean isSystemdEnabled) {
        final List<Volume> volumes = new ArrayList<>();
        volumes.add(createVolume(REF_DATA_MOUNT, "/ebs/reference"));
        volumes.add(createVolume(RUNS_DATA_MOUNT, "/ebs/runs"));
        volumes.add(createEmptyVolume(EMPTY_MOUNT, "Memory"));
        final List<DockerMount> dockerMounts = preferenceManager.getPreference(
                SystemPreferences.DOCKER_IN_DOCKER_MOUNTS);
        if (isDockerInDockerEnabled &&
                CollectionUtils.isNotEmpty(dockerMounts)) {
            dockerMounts.forEach(mount -> volumes.add(createVolume(mount.getName(), mount.getHostPath())));
        }
        final List<DockerMount> commonMounts = preferenceManager.getPreference(
                SystemPreferences.LAUNCH_COMMON_MOUNTS);
        if (CollectionUtils.isNotEmpty(commonMounts)) {
            dockerMounts.forEach(mount -> volumes.add(createVolume(mount.getName(), mount.getHostPath())));
        }
        if (isSystemdEnabled) {
            volumes.add(createVolume(HOST_CGROUP_MOUNT.getName(), HOST_CGROUP_MOUNT.getHostPath()));
        }
        return volumes;
    }

    private List<VolumeMount> getMounts(final boolean isDockerInDockerEnabled, final boolean isSystemdEnabled) {
        final List<VolumeMount> mounts = new ArrayList<>();
        mounts.add(getVolumeMount(REF_DATA_MOUNT, "/common"));
        mounts.add(getVolumeMount(RUNS_DATA_MOUNT, "/runs"));
        mounts.add(getVolumeMount(EMPTY_MOUNT, "/dev/shm"));
        final List<DockerMount> dockerMounts = preferenceManager.getPreference(
                SystemPreferences.DOCKER_IN_DOCKER_MOUNTS);
        if (isDockerInDockerEnabled &&
                CollectionUtils.isNotEmpty(dockerMounts)) {
            dockerMounts.forEach(mount -> mounts.add(getVolumeMount(mount.getName(), mount.getMountPath())));
        }
        if (isSystemdEnabled) {
            mounts.add(getVolumeMount(HOST_CGROUP_MOUNT.getName(), HOST_CGROUP_MOUNT.getMountPath()));
        }
        return mounts;
    }

    private VolumeMount getVolumeMount(String name, String path) {
        VolumeMount mount = new VolumeMount();
        mount.setName(name);
        mount.setMountPath(path);
        return mount;
    }

    private ObjectMeta getObjectMeta(PipelineRun run, Map<String, String> labels) {
        ObjectMeta metadata = new ObjectMeta();
        metadata.setName(run.getPodId());
        metadata.setLabels(labels);
        return metadata;
    }

    private Volume createVolume(String name, String hostPath) {
        Volume volume = new Volume();
        volume.setName(name);
        volume.setHostPath(new HostPathVolumeSource(hostPath, StringUtils.EMPTY));
        return volume;
    }

    private Volume createEmptyVolume(String name, String medium) {
        Volume volume = new Volume();
        EmptyDirVolumeSource emptyDir = new EmptyDirVolumeSource();
        emptyDir.setMedium(medium);
        volume.setEmptyDir(emptyDir);
        volume.setName(name);
        return volume;
    }

    private Map<String, String> getServiceLabels(List<String> endpointsString) {
        Map<String, String> labels = new HashMap<>();
        if (CollectionUtils.isEmpty(endpointsString)) {
            return labels;
        }
        if (endpointsString.stream()
                .anyMatch(s -> !StringUtils.isEmpty(s) && s.contains(NGINX_ENDPOINT))) {
            labels.put("job-type", "Service");
        }
        return labels;
    }

}
