/*
 * Copyright 2017-2024 EPAM Systems, Inc. (https://www.epam.com/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.epam.pipeline.monitor.service.reporter;

import com.epam.pipeline.monitor.model.node.GpuUsages;
import com.epam.pipeline.monitor.rest.NodeReporterAPIExecutor;
import com.epam.pipeline.monitor.service.k8s.KubernetesUtils;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
@Slf4j
public class NodeReporterService {
    private static final String NAME_LABEL = "name";
    private static final String CLOUD_INS_TYPE_LABEL = "cloud_ins_type";

    private final String reportingPodName;
    private final String namespace;
    private final Executor executor;
    private final NodeReporterAPIExecutor nodeReporterClient;

    public NodeReporterService(@Value("${node.reporter.srv.pod.name:cp-node-reporter}")
                               final String reportingPodName,
                               @Value("${node.reporter.srv.namespace:default}") final String namespace,
                               @Value("${monitoring.gpu.usage.pool.size:1}") final int poolSize,
                               final NodeReporterAPIExecutor nodeReporterClient) {
        this.reportingPodName = reportingPodName;
        this.namespace = namespace;
        this.executor = Executors.newFixedThreadPool(poolSize);
        this.nodeReporterClient = nodeReporterClient;
    }

    public List<GpuUsages> collectGpuUsages(final Set<String> gpuInstanceTypes) {
        try (KubernetesClient client = KubernetesUtils.getKubernetesClient()) {
            final Set<String> nodeNames = getGpuNodeNames(client, gpuInstanceTypes);
            final List<Pod> pods = getReportingPods(client, nodeNames);
            return requestUsages(pods).stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList());
        } catch (KubernetesClientException e) {
            log.error("An error occurred while sending request to k8s", e);
            return Collections.emptyList();
        }
    }

    private Set<String> getGpuNodeNames(final KubernetesClient client, final Set<String> gpuInstanceTypes) {
        return KubernetesUtils.findNodesByLabel(client, CLOUD_INS_TYPE_LABEL, gpuInstanceTypes).stream()
                .map(Node::getMetadata)
                .filter(Objects::nonNull)
                .map(ObjectMeta::getName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private List<Pod> getReportingPods(final KubernetesClient client, final Set<String> monitoringNodeNames) {
        return KubernetesUtils.findPodsByLabel(client, NAME_LABEL, reportingPodName, namespace).stream()
                .filter(pod -> monitoringNodeNames.contains(pod.getSpec().getNodeName()))
                .collect(Collectors.toList());
    }

    private List<CompletableFuture<GpuUsages>> requestUsages(final List<Pod> reporterPods) {
        return reporterPods.stream()
                .map(pod -> requestUsages(pod)
                        .exceptionally(e -> {
                            log.error("Failed to collect usages from {} for {}.",
                                    KubernetesUtils.getPodName(pod),
                                    KubernetesUtils.getNodeName(pod), e);
                            return GpuUsages.builder().build();
                        }))
                .collect(Collectors.toList());
    }

    private CompletableFuture<GpuUsages> requestUsages(final Pod pod) {
        return CompletableFuture.supplyAsync(() -> getStats(pod).orElseThrow(IllegalArgumentException::new), executor);
    }

    private Optional<GpuUsages> getStats(final Pod pod) {
        final String nodename = KubernetesUtils.getNodeName(pod);
        log.info("Retrieving usages from {} for {}...", KubernetesUtils.getPodName(pod), nodename);
        return KubernetesUtils.getPodIp(pod)
                .map(nodeReporterClient::loadGpuStats)
                .map(stat -> GpuUsages.builder()
                        .usages(stat)
                        .nodename(nodename)
                        .timestamp(LocalDateTime.now())
                        .build());
    }
}
