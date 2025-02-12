/*
 * Copyright 2017-2020 EPAM Systems, Inc. (https://www.epam.com/)
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

package com.epam.pipeline.manager.cluster;


import com.epam.pipeline.common.MessageConstants;
import com.epam.pipeline.common.MessageHelper;
import com.epam.pipeline.controller.vo.InstanceOfferRequestVO;
import com.epam.pipeline.dao.cluster.InstanceOfferDao;
import com.epam.pipeline.entity.cluster.*;
import com.epam.pipeline.entity.configuration.PipelineConfiguration;
import com.epam.pipeline.entity.contextual.ContextualPreferenceExternalResource;
import com.epam.pipeline.entity.contextual.ContextualPreferenceLevel;
import com.epam.pipeline.entity.pipeline.PipelineRun;
import com.epam.pipeline.entity.pipeline.RunInstance;
import com.epam.pipeline.entity.region.AbstractCloudRegion;
import com.epam.pipeline.entity.region.CloudProvider;
import com.epam.pipeline.exception.git.GitClientException;
import com.epam.pipeline.manager.cloud.CloudFacade;
import com.epam.pipeline.manager.cloud.CloudInstancePriceService;
import com.epam.pipeline.manager.cloud.offer.InstanceOfferFilter;
import com.epam.pipeline.manager.cloud.offer.InstanceOfferMinimumRequirementsFilter;
import com.epam.pipeline.manager.cloud.offer.InstanceOfferTermTypeFilter;
import com.epam.pipeline.manager.cloud.offer.InstanceOfferUniqueFilter;
import com.epam.pipeline.manager.contextual.ContextualPreferenceManager;
import com.epam.pipeline.manager.pipeline.PipelineRunManager;
import com.epam.pipeline.manager.pipeline.PipelineVersionManager;
import com.epam.pipeline.manager.preference.AbstractSystemPreference;
import com.epam.pipeline.manager.preference.PreferenceManager;
import com.epam.pipeline.manager.preference.SystemPreferences;
import com.epam.pipeline.manager.region.CloudRegionManager;
import com.epam.pipeline.utils.RunDurationUtils;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

@Service
@AllArgsConstructor
@NoArgsConstructor
public class InstanceOfferManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(InstanceOfferManager.class);

    private static final int FALLBACK_INSTANCE_OFFER_INSERT_BATCH_SIZE = 10_000;
    private static final Set<String> FALLBACK_FILTER_TERM_TYPES =
            Arrays.stream(CloudInstancePriceService.TermType.values())
                    .map(CloudInstancePriceService.TermType::getName)
                    .collect(Collectors.toSet());
    private static final boolean FALLBACK_FILTER_UNIQUE = true;
    private static final int FALLBACK_FILTER_CPU_MIN = 2;
    private static final int FALLBACK_FILTER_MEM_MIN = 3;

    @Autowired
    private InstanceOfferDao instanceOfferDao;

    @Autowired
    private PipelineVersionManager versionManager;

    @Autowired
    private PipelineRunManager pipelineRunManager;

    @Autowired
    private MessageHelper messageHelper;

    @Autowired
    private PreferenceManager preferenceManager;

    @Autowired
    private CloudRegionManager cloudRegionManager;

    @Autowired
    private ContextualPreferenceManager contextualPreferenceManager;

    @Autowired
    private CloudFacade cloudFacade;

    private final AntPathMatcher matcher = new AntPathMatcher();

    private static final double ONE_SECOND = 1000;
    private static final double ONE_MINUTE = 60 * ONE_SECOND;
    private static final double ONE_HOUR = 60 * ONE_MINUTE;

    private static final String DELIMITER = ",";

    private static final List<String> INSTANCE_TYPES_PREFERENCES = Collections.singletonList(
            SystemPreferences.CLUSTER_ALLOWED_INSTANCE_TYPES.getKey());
    private static final List<String> TOOL_INSTANCE_TYPES_PREFERENCES = Arrays.asList(
            SystemPreferences.CLUSTER_ALLOWED_INSTANCE_TYPES_DOCKER.getKey(),
            SystemPreferences.CLUSTER_ALLOWED_INSTANCE_TYPES.getKey());
    private static final List<String> PRICE_TYPES_PREFERENCES = Collections.singletonList(
            SystemPreferences.CLUSTER_ALLOWED_PRICE_TYPES.getKey());
    private static final List<String> MASTER_PRICE_TYPES_PREFERENCES = Collections.singletonList(
            SystemPreferences.CLUSTER_ALLOWED_MASTER_PRICE_TYPES.getKey());

    public Date getPriceListPublishDate() {
        return instanceOfferDao.getPriceListPublishDate();
    }

    public InstancePrice getInstanceEstimatedPrice(Long id, String version, String configName,
            String instanceType, int instanceDisk, Boolean spot, Long regionId) throws GitClientException {

        Boolean useSpot = spot;

        if (StringUtils.isEmpty(instanceType) || instanceDisk <= 0 || useSpot == null) {
            PipelineConfiguration pipelineConfiguration =
                    versionManager.loadParametersFromScript(id, version, configName);
            if (StringUtils.isEmpty(instanceType)) {
                instanceType = pipelineConfiguration.getInstanceType();
            }
            if (instanceDisk <= 0) {
                instanceDisk = Integer.parseInt(pipelineConfiguration.getInstanceDisk());
            }
            if (useSpot == null) {
                useSpot = pipelineConfiguration.getIsSpot();
            }
        }

        InstancePrice instancePrice = getInstanceEstimatedPrice(instanceType, instanceDisk, useSpot, regionId);

        List<PipelineRun> runs = pipelineRunManager.loadAllRunsByPipeline(id, version)
                .stream().filter(run -> run.getStatus().isFinal()).collect(toList());
        if (!runs.isEmpty()) {
            long minimumDuration = -1;
            long maximumDuration = -1;
            long totalDurations = 0;
            for (PipelineRun run : runs) {
                long duration = RunDurationUtils.getBillableDuration(run).toMillis();
                if (minimumDuration == -1 || minimumDuration > duration) {
                    minimumDuration = duration;
                }
                if (maximumDuration == -1 || maximumDuration < duration) {
                    maximumDuration = duration;
                }
                totalDurations += duration;
            }
            double averageDuration = (double) totalDurations / runs.size();
            instancePrice.setAverageTimePrice(instancePrice.getPricePerHour() * averageDuration / ONE_HOUR);
            instancePrice.setMinimumTimePrice(instancePrice.getPricePerHour() * minimumDuration / ONE_HOUR);
            instancePrice.setMaximumTimePrice(instancePrice.getPricePerHour() * maximumDuration / ONE_HOUR);
        }
        return instancePrice;
    }

    public InstancePrice getInstanceEstimatedPrice(
            String instanceType, int instanceDisk, Boolean spot, Long regionId) {
        final boolean isSpot = isSpotRequest(spot);
        final Long actualRegionId = defaultRegionIfNull(regionId);
        Assert.isTrue(isInstanceAllowed(instanceType, actualRegionId, isSpot) ||
                        isToolInstanceAllowed(instanceType, actualRegionId, isSpot),
                messageHelper.getMessage(MessageConstants.ERROR_INSTANCE_TYPE_IS_NOT_ALLOWED,
                        instanceType));
        double computePricePerHour = getPricePerHourForInstance(instanceType, isSpot, actualRegionId);
        double initialDiskPricePerHour = getPriceForDisk(instanceDisk, actualRegionId, instanceType, isSpot);
        double diskPricePerHour = initialDiskPricePerHour / instanceDisk;
        double pricePerHour = initialDiskPricePerHour + computePricePerHour;
        return new InstancePrice(instanceType, instanceDisk, pricePerHour, computePricePerHour, diskPricePerHour);
    }

    public PipelineRunPrice getPipelineRunEstimatedPrice(Long runId, Long regionId) {
        final Long actualRegionId = defaultRegionIfNull(regionId);
        PipelineRun pipelineRun = pipelineRunManager.loadPipelineRun(runId, false);
        RunInstance runInstance = pipelineRun.getInstance();
        boolean spot = isSpotRequest(runInstance.getSpot());
        double computePricePerHour = getPricePerHourForInstance(runInstance.getNodeType(), spot, actualRegionId);
        double initialDiskPricePerHour = getPriceForDisk(runInstance.getNodeDisk(), actualRegionId, 
                runInstance.getNodeType(), spot);
        double pricePerHour = initialDiskPricePerHour + computePricePerHour;

        PipelineRunPrice price = new PipelineRunPrice();
        price.setInstanceDisk(runInstance.getNodeDisk());
        price.setInstanceType(runInstance.getNodeType());
        price.setPricePerHour(pricePerHour);

        if (pipelineRun.getStatus().isFinal()) {
            price.setTotalPrice(RunDurationUtils.getBillableDuration(pipelineRun).toMillis() / ONE_HOUR * pricePerHour);
        } else {
            price.setTotalPrice(0);
        }
        return price;
    }

    /**
     * Returns all instance types that are allowed on a system-wide level for all regions.
     */
    public List<InstanceType> getAllowedInstanceTypes() {
        final String allowed = preferenceManager.getPreference(SystemPreferences.CLUSTER_ALLOWED_INSTANCE_TYPES);
        return getAllInstanceTypes().stream()
                .filter(offer -> isInstanceTypeAllowed(offer.getName(), allowed))
                .collect(toList());
    }

    /**
     * Returns all instance types that are allowed on a system-wide level for the specified or default region.
     *
     * @param regionId If specified then instance types will be loaded only for the specified region.
     */
    public List<InstanceType> getAllowedInstanceTypes(final Long regionId, final Boolean spot) {
        final boolean isSpot = isSpotRequest(spot);
        final String allowed = preferenceManager.getPreference(SystemPreferences.CLUSTER_ALLOWED_INSTANCE_TYPES);
        return getAllInstanceTypes(defaultRegionIfNull(regionId), isSpot).stream()
                .filter(offer -> isInstanceTypeAllowed(offer.getName(), allowed))
                .collect(toList());
    }

    /**
     * Returns tool instance types that are allowed on a system-wide level for the specified or default region.
     *
     * @param regionId If specified then instance types will be loaded for the specified region.
     */
    public List<InstanceType> getAllowedToolInstanceTypes(final Long regionId, final Boolean spot) {
        final boolean isSpot = isSpotRequest(spot);
        final String allowed = preferenceManager.getPreference(SystemPreferences.CLUSTER_ALLOWED_INSTANCE_TYPES_DOCKER);
        return getAllInstanceTypes(defaultRegionIfNull(regionId), isSpot).stream()
                .filter(offer -> isInstanceTypeAllowed(offer.getName(), allowed))
                .collect(toList());
    }

    public Optional<InstanceOffer> findOffer(final String instanceType, final Long regionId) {
        final InstanceOfferRequestVO requestVO = buildInstanceTypeRequest(instanceType, regionId);
        return ListUtils.emptyIfNull(instanceOfferDao.loadInstanceOffers(requestVO))
                .stream()
                .findFirst();
    }

    public double getPricePerHourForInstance(final String instanceType, final Long regionId) {
        final InstanceOfferRequestVO requestVO = buildInstanceTypeRequest(instanceType, regionId);
        return ListUtils.emptyIfNull(instanceOfferDao.loadInstanceOffers(requestVO))
                .stream()
                .map(InstanceOffer::getPricePerUnit)
                .filter(price -> Double.compare(price, 0.0) > 0)
                .min(Double::compareTo)
                .orElse(0.0);
    }

    /**
     * Checks if the given instance type is allowed for the authorized user in the specified or default region.
     *
     * Also checks if the instance type is one of the offered instance types.
     *
     * @param instanceType To be checked.
     * @param regionId If specified then instance types for the specified region will be used.
     */
    public boolean isInstanceAllowed(final String instanceType, final Long regionId, final boolean spot) {
        return isInstanceTypeAllowed(instanceType, null, defaultRegionIfNull(regionId),
                INSTANCE_TYPES_PREFERENCES, spot);
    }

    /**
     * Checks if the given instance type is allowed for the authorized user in the specified or default region.
     * Also checks if the instance type is one of the offered instance types.
     *
     * @param instanceType To be checked.
     * @param resources List of preference resources: tool, region, etc.
     * @param regionId If specified then instance types for the specified region will be used.
     */
    public boolean isInstanceAllowed(final String instanceType,
                                     final List<ContextualPreferenceExternalResource> resources,
                                     final Long regionId,
                                     final boolean spot) {
        return isInstanceTypeAllowed(instanceType, resources, defaultRegionIfNull(regionId),
                INSTANCE_TYPES_PREFERENCES, spot);
    }

    /**
     * Checks if the given tool instance type is allowed for the authorized user and the requested tool
     * in the specified or default region.
     *
     * Also checks if the instance type is one of the offered instance types.
     *
     * @param instanceType To be checked.
     * @param resources List of preference resources: tool, region, etc.
     * @param regionId If specified then instance types for the specified region will be used.
     */
    public boolean isToolInstanceAllowed(final String instanceType,
                                         final List<ContextualPreferenceExternalResource> resources,
                                         final Long regionId,
                                         final boolean spot) {
        return isInstanceTypeAllowed(instanceType, resources, defaultRegionIfNull(regionId),
                TOOL_INSTANCE_TYPES_PREFERENCES, spot);
    }

    /**
     * Checks if the given tool instance type is allowed for the authorized user and the requested tool
     * in the specified or default region.
     *
     * Also checks if the instance type is one of the offered instance types.
     *
     * @param instanceType To be checked.
     * @param regionId If specified then instance types for the specified region will be used.
     */
    public boolean isToolInstanceAllowed(final String instanceType,
                                         final Long regionId,
                                         final boolean spot) {
        return isInstanceTypeAllowed(instanceType, null, defaultRegionIfNull(regionId),
                TOOL_INSTANCE_TYPES_PREFERENCES, spot);
    }

    /**
     * Checks if the given tool instance type is allowed for the authorized user and the requested tool
     * in any of the existing regions.
     *
     * Also checks if the instance type is one of the offered instance types.
     *
     * @param instanceType To be checked.
     * @param toolResource Optional tool resource.
     */
    public boolean isToolInstanceAllowedInAnyRegion(final String instanceType,
                                                    final ContextualPreferenceExternalResource toolResource) {
        final List<ContextualPreferenceExternalResource> resources = toolResource != null
                ? Collections.singletonList(toolResource) : Collections.emptyList();
        return isInstanceTypeAllowed(instanceType, resources, null, TOOL_INSTANCE_TYPES_PREFERENCES, false)
                || isInstanceTypeAllowed(instanceType, resources, null, TOOL_INSTANCE_TYPES_PREFERENCES, true);
    }

    private boolean isInstanceTypeAllowed(final String instanceType,
                                          final List<ContextualPreferenceExternalResource> resources,
                                          final Long regionId,
                                          final List<String> instanceTypesPreferences,
                                          final boolean spot) {
        return !StringUtils.isEmpty(instanceType)
                && isInstanceTypeMatchesAllowedPatterns(instanceType, resources, instanceTypesPreferences)
                && isInstanceTypeOffered(instanceType, regionId, spot);
    }

    private boolean isInstanceTypeMatchesAllowedPatterns(final String instanceType,
                                                         final List<ContextualPreferenceExternalResource> resources,
                                                         final List<String> instanceTypesPreferences) {
        return getContextualPreferenceValueAsList(resources, instanceTypesPreferences).stream()
                .anyMatch(pattern -> matcher.match(pattern, instanceType));
    }

    /**
     * Checks if the given price type is allowed for the authorized user and tool.
     *
     * @param priceType To be checked.
     * @param resources List of resources.
     * @param isMaster is checking node a master in cluster run
     */
    public boolean isPriceTypeAllowed(final String priceType,
                                      final List<ContextualPreferenceExternalResource> resources,
                                      final boolean isMaster) {
        final List<String> priceTypesPreferences = isMaster
                                     ? MASTER_PRICE_TYPES_PREFERENCES
                                     : PRICE_TYPES_PREFERENCES;
        return getContextualPreferenceValueAsList(resources, priceTypesPreferences).stream()
                .anyMatch(priceType::equals);
    }

    public boolean isPriceTypeAllowed(final String priceType) {
        return isPriceTypeAllowed(priceType, null, false);
    }

    public void refreshPriceList() {
        LOGGER.debug(messageHelper.getMessage(MessageConstants.DEBUG_INSTANCE_OFFERS_UPDATE_STARTED));
        List<InstanceOffer> offers = cloudRegionManager.loadAll()
                .stream()
                .map(this::updatePriceList)
                .flatMap(List::stream)
                .collect(toList());

        LOGGER.debug(messageHelper.getMessage(MessageConstants.DEBUG_INSTANCE_OFFERS_UPDATE_FINISHED));
        LOGGER.info(messageHelper.getMessage(MessageConstants.INFO_INSTANCE_OFFERS_UPDATED, offers.size()));
    }

    public void refreshPriceList(Long id) {
        AbstractCloudRegion region = cloudRegionManager.load(id);
        updatePriceList(region);
    }

    public void refreshPriceList(AbstractCloudRegion region) {
        updatePriceList(region);
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private List<InstanceOffer> updatePriceList(final AbstractCloudRegion region) {
        try {
            final List<InstanceOffer> offers = retrievePriceList(region);
            if (offers.isEmpty()) {
                LOGGER.warn("Skipping instance offers update for region {} {} #{} " +
                                "because no instance offers have been retrieved...",
                        region.getProvider(), region.getRegionCode(), region.getId());
                return offers;
            }
            final List<InstanceOffer> filteredOffers = filterPriceList(region, offers);
            return replacePriceList(region, filteredOffers);
        } catch (RuntimeException e) {
            LOGGER.error("Failed to update instance offers for region {} {} #{}.",
                    region.getProvider(), region.getRegionCode(), region.getId(), e);
            return Collections.emptyList();
        }
    }

    private List<InstanceOffer> retrievePriceList(final AbstractCloudRegion region) {
        LOGGER.debug("Retrieving instance offers for region {} {} #{}...",
                region.getProvider(), region.getRegionCode(), region.getId());
        final List<InstanceOffer> offers = cloudFacade.refreshPriceListForRegion(region.getId());
        LOGGER.debug("Retrieved {} instance offers for region {} {} #{}.",
                offers.size(), region.getProvider(), region.getRegionCode(), region.getId());
        return offers;
    }

    private List<InstanceOffer> filterPriceList(final AbstractCloudRegion region, final List<InstanceOffer> offers) {
        LOGGER.debug("Filtering instance offers for region {} {} #{}...",
                region.getProvider(), region.getRegionCode(), region.getId());
        final InstanceOfferFilter filter = getFilter();
        final List<InstanceOffer> filteredOffers = filter.filter(offers);
        LOGGER.debug("Filtered out {} instance offers for region {} {} #{}.",
                offers.size() - filteredOffers.size(), region.getProvider(), region.getRegionCode(), region.getId());
        return filteredOffers;
    }

    private InstanceOfferFilter getFilter() {
        return offers -> getFilters().stream()
                .map(filter -> (Function<List<InstanceOffer>, List<InstanceOffer>>) filter::filter)
                .reduce(Function::andThen)
                .orElseGet(Function::identity)
                .apply(offers);
    }

    private List<InstanceOfferFilter> getFilters() {
        final List<InstanceOfferFilter> filters = new ArrayList<>();
        final Set<String> termTypes = getFilterTermTypes();
        if (CollectionUtils.isNotEmpty(termTypes)) {
            filters.add(new InstanceOfferTermTypeFilter(termTypes));
        }
        if (isFilterUnique()) {
            filters.add(new InstanceOfferUniqueFilter());
        }
        final int cpu = getFilterCpuMin();
        final int mem = getFilterMemMin();
        if (cpu > 0 || mem > 0) {
            filters.add(new InstanceOfferMinimumRequirementsFilter(cpu, mem));
        }
        return filters;
    }

    private Set<String> getFilterTermTypes() {
        return Optional.of(SystemPreferences.CLUSTER_INSTANCE_OFFER_FILTER_TERM_TYPES)
                .map(preferenceManager::getPreference)
                .map(it -> StringUtils.split(it, ','))
                .map(Arrays::asList)
                .<Set<String>>map(HashSet::new)
                .orElse(FALLBACK_FILTER_TERM_TYPES);
    }

    private boolean isFilterUnique() {
        return Optional.of(SystemPreferences.CLUSTER_INSTANCE_OFFER_FILTER_UNIQUE)
                .map(preferenceManager::getPreference)
                .orElse(FALLBACK_FILTER_UNIQUE);
    }

    private Integer getFilterCpuMin() {
        return Optional.of(SystemPreferences.CLUSTER_INSTANCE_OFFER_FILTER_CPU_MIN)
                .map(preferenceManager::getPreference)
                .orElse(FALLBACK_FILTER_CPU_MIN);
    }

    private Integer getFilterMemMin() {
        return Optional.of(SystemPreferences.CLUSTER_INSTANCE_OFFER_FILTER_MEM_MIN)
                .map(preferenceManager::getPreference)
                .orElse(FALLBACK_FILTER_MEM_MIN);
    }

    private List<InstanceOffer> replacePriceList(final AbstractCloudRegion region, final List<InstanceOffer> offers) {
        LOGGER.debug("Inserting {} instance offers to region {} {} #{}.",
                offers.size(), region.getProvider(), region.getRegionCode(), region.getId());
        instanceOfferDao.replaceInstanceOffersForRegion(region.getId(), offers, getInstanceOfferInsertBatchSize());
        LOGGER.debug("Inserted {} instance offers to region {} {} #{}.",
                offers.size(), region.getProvider(), region.getRegionCode(), region.getId());
        return offers;
    }

    private int getInstanceOfferInsertBatchSize() {
        return Optional.of(SystemPreferences.CLUSTER_INSTANCE_OFFER_INSERT_BATCH_SIZE)
            .map(preferenceManager::getPreference)
            .orElse(FALLBACK_INSTANCE_OFFER_INSERT_BATCH_SIZE);
    }

    private boolean isSpotRequest(Boolean spot) {
        return spot == null ? preferenceManager.getPreference(SystemPreferences.CLUSTER_SPOT) : spot;
    }

    private double getPricePerHourForInstance(String instanceType, boolean isSpot, Long regionId) {
        return isSpot ? getSpotPricePerHour(instanceType, regionId) :
                getPricePerHourForInstance(instanceType, regionId);
    }

    private double getSpotPricePerHour(String instanceType, Long regionId) {
        return cloudFacade.getSpotPrice(regionId, instanceType);
    }

    /**
     * Returns all instance types for all regions.
     */
    public List<InstanceType> getAllInstanceTypes() {
        return ListUtils.sum(getAllInstanceTypes(null, false), getAllInstanceTypes(null, true));
    }

    /**
     * Returns all instance types for the specified region.
     *
     * @param regionId If specified then instance types will be loaded only for the specified region.
     */
    public List<InstanceType> getAllInstanceTypes(final Long regionId, final boolean spot) {
        return cloudFacade.getAllInstanceTypes(regionId, spot);
    }

    private double getPriceForDisk(int instanceDisk, Long regionId, String instanceType, boolean spot) {
        InstanceOfferRequestVO requestVO = new InstanceOfferRequestVO();
        requestVO.setProductFamily(CloudInstancePriceService.STORAGE_PRODUCT_FAMILY);
        requestVO.setVolumeType(CloudInstancePriceService.GENERAL_PURPOSE_VOLUME_TYPE);
        requestVO.setRegionId(regionId);
        cloudFacade.adjustOfferRequest(regionId, requestVO);
        List<InstanceOffer> offers = instanceOfferDao.loadInstanceOffers(requestVO);
        return cloudFacade.getPriceForDisk(regionId, offers, instanceDisk, instanceType, spot);
    }

    private boolean isInstanceTypeAllowed(final String instanceType, final String pattern) {
        if (StringUtils.isBlank(pattern)) {
            return true;
        }
        List<String> allowedInstancePatterns = Arrays.asList(pattern.split(","));
        return allowedInstancePatterns.stream().anyMatch(type -> matcher.match(type, instanceType));
    }

    /**
     * Returns allowed instance and price types for a current user in the specified region.
     *
     * @param toolId If specified then allowed types will be bounded for the specified tool.
     * @param regionId If specified then allowed types will be loaded only for the specified region.
     * @param spot if true allowed instances types will be filtered and only spot instance proposal will be shown
     */
    public AllowedInstanceAndPriceTypes getAllowedInstanceAndPriceTypes(final Long toolId, final Long regionId,
                                                                        final Boolean spot) {
        final boolean isSpot = isSpotRequest(spot);
        final ContextualPreferenceExternalResource resource = toolResource(toolId);
        final List<InstanceType> instanceTypes = getAllInstanceTypes(defaultRegionIfNull(regionId), isSpot);
        final List<InstanceType> allowedInstanceTypes = getAllowedInstanceTypes(instanceTypes, resource,
                SystemPreferences.CLUSTER_ALLOWED_INSTANCE_TYPES);
        final List<InstanceType> allowedInstanceDockerTypes = getAllowedInstanceTypes(instanceTypes, resource,
                SystemPreferences.CLUSTER_ALLOWED_INSTANCE_TYPES_DOCKER,
                SystemPreferences.CLUSTER_ALLOWED_INSTANCE_TYPES);
        final List<String> allowedPriceTypes = getContextualPreferenceValueAsList(resource,
                SystemPreferences.CLUSTER_ALLOWED_PRICE_TYPES);
        final List<String> allowedMasterPriceTypes = getContextualPreferenceValueAsList(resource,
                SystemPreferences.CLUSTER_ALLOWED_MASTER_PRICE_TYPES);
        return new AllowedInstanceAndPriceTypes(allowedInstanceTypes, allowedInstanceDockerTypes,
                                                allowedPriceTypes, allowedMasterPriceTypes);
    }

    private ContextualPreferenceExternalResource toolResource(final Long toolId) {
        return Optional.ofNullable(toolId)
                .map(Object::toString)
                .map(id -> new ContextualPreferenceExternalResource(ContextualPreferenceLevel.TOOL, id))
                .orElse(null);
    }

    private Long defaultRegionIfNull(final Long regionId) {
        return Optional.ofNullable(regionId)
                .orElseGet(() -> cloudRegionManager.loadDefaultRegion().getId());
    }

    private List<InstanceType> getAllowedInstanceTypes(
            final List<InstanceType> instanceTypes,
            final ContextualPreferenceExternalResource resource,
            final AbstractSystemPreference.StringPreference... preferences) {
        final List<String> allowedInstanceTypePatterns = getContextualPreferenceValueAsList(resource, preferences);
        return instanceTypes.stream()
                .filter(instanceType -> allowedInstanceTypePatterns.stream()
                        .anyMatch(pattern -> matcher.match(pattern, instanceType.getName())))
                .collect(toList());
    }

    private List<String> getContextualPreferenceValueAsList(
            final ContextualPreferenceExternalResource resource,
            final AbstractSystemPreference.StringPreference... preferences) {
        final List<String> preferenceNames = Arrays.stream(preferences)
                .map(AbstractSystemPreference::getKey)
                .collect(toList());
        return getContextualPreferenceValueAsList(Optional.ofNullable(resource)
                .map(Collections::singletonList)
                .orElse(null), preferenceNames);
    }

    private List<String> getContextualPreferenceValueAsList(final List<ContextualPreferenceExternalResource> resources,
                                                            final List<String> preferences) {
        return Arrays.asList(contextualPreferenceManager
                .searchList(preferences, resources).getValue().split(DELIMITER));
    }

    private boolean isInstanceTypeOffered(final String instanceType, final Long regionId, final boolean spot) {
        final InstanceOfferRequestVO request = new InstanceOfferRequestVO();
        request.setRegionId(regionId);
        request.setInstanceType(instanceType);
        if (!spot) {
            request.setTermType(CloudInstancePriceService.TermType.ON_DEMAND.getName());
        }
        return ListUtils.emptyIfNull(instanceOfferDao.loadInstanceOffers(request))
                .stream()
                .anyMatch(offer -> matchingOffer(instanceType, spot, offer));
    }

    private boolean matchingOffer(final String instanceType,
                                  final boolean spot,
                                  final InstanceOffer offer) {
        final String type = offer.getInstanceType();
        if (!instanceType.equalsIgnoreCase(type)) {
            return false;
        }
        return offer.getTermType().equalsIgnoreCase(getTermType(offer.getCloudProvider(), spot));
    }

    private String getTermType(final CloudProvider provider, final boolean spot) {
        if (!spot) {
            return CloudInstancePriceService.TermType.ON_DEMAND.getName();
        }
        return PriceType.spotTermName(provider);
    }

    private static InstanceOfferRequestVO buildInstanceTypeRequest(final String instanceType,
                                                                   final Long regionId) {
        final InstanceOfferRequestVO requestVO = new InstanceOfferRequestVO();
        requestVO.setInstanceType(instanceType);
        requestVO.setTermType(CloudInstancePriceService.TermType.ON_DEMAND.getName());
        requestVO.setOperatingSystem(CloudInstancePriceService.LINUX_OPERATING_SYSTEM);
        requestVO.setTenancy(CloudInstancePriceService.SHARED_TENANCY);
        requestVO.setUnit(CloudInstancePriceService.HOURS_UNIT);
        requestVO.setProductFamily(CloudInstancePriceService.INSTANCE_PRODUCT_FAMILY);
        requestVO.setRegionId(regionId);
        return requestVO;
    }
}
