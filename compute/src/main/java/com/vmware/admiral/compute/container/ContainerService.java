/*
 * Copyright (c) 2016-2017 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package com.vmware.admiral.compute.container;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.esotericsoftware.kryo.serializers.VersionFieldSerializer.Since;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import com.vmware.admiral.adapter.common.AdapterRequest;
import com.vmware.admiral.adapter.common.ContainerOperationType;
import com.vmware.admiral.common.serialization.ReleaseConstants;
import com.vmware.admiral.common.util.PropertyUtils;
import com.vmware.admiral.compute.Composable;
import com.vmware.admiral.compute.container.ContainerService.ContainerState.PowerState;
import com.vmware.admiral.compute.container.maintenance.ContainerHealthEvaluator;
import com.vmware.admiral.compute.container.maintenance.ContainerStats;
import com.vmware.admiral.compute.container.util.ContainerUtil;
import com.vmware.admiral.compute.content.EnvDeserializer;
import com.vmware.admiral.compute.content.EnvSerializer;
import com.vmware.admiral.service.common.ServiceTaskCallback;
import com.vmware.photon.controller.model.resources.ComputeService;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.ServiceDocument;
import com.vmware.xenon.common.ServiceDocumentDescription;
import com.vmware.xenon.common.ServiceDocumentDescription.PropertyIndexingOption;
import com.vmware.xenon.common.ServiceDocumentDescription.PropertyUsageOption;
import com.vmware.xenon.common.StatefulService;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;

/**
 * Describes a container instance. The same description service instance can be re-used across many
 * container instances acting as a shared template.
 */
public class ContainerService extends StatefulService {

    public static class ContainerState
            extends com.vmware.photon.controller.model.resources.ResourceState
            implements Composable {
        public static final String FIELD_NAME_NAMES = "names";
        public static final String FIELD_NAME_COMMAND = "command";
        public static final String FIELD_NAME_PORTS = "ports";
        public static final String FIELD_NAME_IMAGE = "image";
        public static final String FIELD_NAME_DESCRIPTION_LINK = "descriptionLink";
        public static final String FIELD_NAME_COMPOSITE_COMPONENT_LINK = "compositeComponentLink";
        public static final String FIELD_NAME_PARENT_LINK = "parentLink";
        public static final String FIELD_NAME_RESOURCE_POOL_LINK = "resourcePoolLink";
        public static final String FIELD_NAME_ENV = "env";
        public static final String FIELD_NAME_POWER_STATE = "powerState";
        public static final String FIELD_NAME_GROUP_RESOURCE_PLACEMENT_LINK =
                "groupResourcePlacementLink";
        public static final String CONTAINER_ALLOCATION_STATUS = "allocation";
        public static final String CONTAINER_DEGRADED_STATUS = "degraded";
        public static final String CONTAINER_UNHEALTHY_STATUS = "unhealthy";
        public static final String CONTAINER_ERROR_STATUS = "error";
        public static final String CONTAINER_RUNNING_STATUS = "running";
        public static final String FIELD_NAME_SYSTEM = "system";
        public static final String FIELD_NAME_VOLUME_DRIVER = "volumeDriver";

        public enum PowerState {
            UNKNOWN,
            PROVISIONING,
            RUNNING,
            PAUSED,
            STOPPED,
            RETIRED,
            ERROR;

            public static PowerState transform(ComputeService.PowerState powerState) {
                switch (powerState) {
                case ON:
                    return PowerState.RUNNING;
                case OFF:
                    return PowerState.STOPPED;
                case UNKNOWN:
                default:
                    return PowerState.UNKNOWN;
                }
            }

            public boolean isUnmanaged() {
                return this == PROVISIONING || this == RETIRED;
            }
        }

        /** The list of names of a given container host. */
        @Documentation(description = "The list of names of a given container host.")
        @PropertyOptions(indexing =
                { PropertyIndexingOption.CASE_INSENSITIVE, PropertyIndexingOption.EXPAND },
                usage = PropertyUsageOption.OPTIONAL)
        public List<String> names;

        /** Defines the description of the container */
        @Documentation(description = "Defines the description of the container.")
        @UsageOption(option = PropertyUsageOption.LINK)
        public String descriptionLink;

        @Documentation(description = "Link to CompositeComponent when a container is part of"
                + " App/Composition request.")
        @PropertyOptions(usage = { PropertyUsageOption.OPTIONAL, PropertyUsageOption.LINK })
        public String compositeComponentLink;

        /** Defines the address of the container */
        @Documentation(description = "Defines the address of the container")
        @UsageOption(option = PropertyUsageOption.OPTIONAL)
        @PropertyOptions(indexing = PropertyIndexingOption.CASE_INSENSITIVE)
        public String address;

        /** Defines which adapter which serve the provision request */
        @Documentation(description = "Defines which adapter which serve the provision request")
        @UsageOption(option = PropertyUsageOption.OPTIONAL)
        public URI adapterManagementReference;

        /** Container state indicating runtime state of a container instance. */
        @Documentation(description = "Container state indicating runtime state of a container"
                + " instance.")
        @UsageOption(option = PropertyUsageOption.OPTIONAL)
        public PowerState powerState;

        /**
         * Port bindings in the format ip:hostPort:containerPort | ip::containerPort |
         * hostPort:containerPort | containerPort where range of ports can also be provided
         */
        @Documentation(description = "Port bindings in the format ip:hostPort:containerPort |"
                + " ip::containerPort | hostPort:containerPort | containerPort where range of ports"
                + " can also be provided")
        @PropertyOptions(usage = PropertyUsageOption.OPTIONAL,
                indexing = PropertyIndexingOption.EXPAND)
        public List<PortBinding> ports;

        /** Joined networks and the configuration with which they are joined. */
        @Documentation(description = "Joined networks.")
        @UsageOption(option = PropertyUsageOption.OPTIONAL)
        public Map<String, ServiceNetwork> networks;

        /** (Required) The docker image */
        @Documentation(description = "The docker image.")
        @PropertyOptions(indexing = PropertyIndexingOption.CASE_INSENSITIVE)
        public String image;

        /** Commands to run. */
        @Documentation(description = "Commands to run.")
        @UsageOption(option = PropertyUsageOption.OPTIONAL)
        @PropertyOptions(indexing = PropertyIndexingOption.CASE_INSENSITIVE)
        public String[] command;

        /** Volumes from the specified container(s) of the format <container name>[:<ro|rw>] */
        @Documentation(description = "Volumes from the specified container(s) of the format"
                + " <container name>[:<ro|rw>]")
        @UsageOption(option = PropertyUsageOption.OPTIONAL)
        public String[] volumesFrom;

        /** Specify volume driver name. */
        @Documentation(description = "Specify volume driver name (default \"local\")")
        @UsageOption(option = PropertyUsageOption.OPTIONAL)
        public String volumeDriver;

        /** Mount a volume e.g /host:/container[:ro] or just named volume like 'vol1' */
        @Documentation(description = "Mount a volume e.g /host:/container[:ro] or just named volume"
                + " like 'vol1'")
        @UsageOption(option = PropertyUsageOption.OPTIONAL)
        public String[] volumes;

        /** A list of services (in a blueprint) the container depends on */
        @Documentation(description = "A list of services (in a blueprint) the container depends"
                + " on.")
        @UsageOption(option = PropertyUsageOption.OPTIONAL)
        public String[] links;

        /** A list of environment variables in the form of VAR=value. */
        @JsonSerialize(contentUsing = EnvSerializer.class)
        @JsonDeserialize(contentUsing = EnvDeserializer.class)
        @Documentation(description = "A list of environment variables in the form of VAR=value.")
        @UsageOption(option = PropertyUsageOption.OPTIONAL)
        public String[] env;

        /** Add a custom host-to-IP mapping (host:ip) */
        @Documentation(description = "Add a custom host-to-IP mapping (host:ip)")
        @UsageOption(option = PropertyUsageOption.OPTIONAL)
        public String[] extraHosts;

        /** Container host link */
        @Documentation(description = "Container host link")
        @PropertyOptions(usage = { PropertyUsageOption.LINK, PropertyUsageOption.OPTIONAL })
        public String parentLink;

        /**
         * Link to the resource placement associated with a given container instance. Null if no
         * placement
         */
        @Documentation(description = "Link to the resource placement associated with a given"
                + " container instance. Null if no placement")
        @PropertyOptions(usage = { PropertyUsageOption.LINK, PropertyUsageOption.OPTIONAL })
        public String groupResourcePlacementLink;

        /** Status of the container */
        @Documentation(description = "Status of the container")
        @UsageOption(option = PropertyUsageOption.OPTIONAL)
        @PropertyOptions(indexing = PropertyIndexingOption.CASE_INSENSITIVE)
        public String status;

        /** Container created time in milliseconds */
        @Documentation(description = "Container created time in milliseconds")
        @UsageOption(option = PropertyUsageOption.OPTIONAL)
        public Long created;

        /** Container started time in milliseconds */
        @Documentation(description = "Container started time in milliseconds")
        @UsageOption(option = PropertyUsageOption.OPTIONAL)
        public Long started;

        /** Effective memory limit */
        @Documentation(description = "Effective memory limit")
        @UsageOption(option = PropertyUsageOption.OPTIONAL)
        public Long memoryLimit;

        /** Storage limit in bytes */
        @Documentation(description = "Storage limit in bytes")
        @UsageOption(option = PropertyUsageOption.OPTIONAL)
        public Long storageLimit;

        /** Is system container */
        @Documentation(description = "Is system container")
        @UsageOption(option = PropertyUsageOption.OPTIONAL)
        public Boolean system;

        /** Mark a container service that the container it represents
         * is deleted intentionally from admiral. This eliminates some
         * race condition bugs.*/
        @Since(ReleaseConstants.RELEASE_VERSION_0_9_5)
        @Documentation(description = "Is the docker container deleted by Admiral.")
        @UsageOption(option = PropertyUsageOption.OPTIONAL)
        public Boolean isDeleted = false;

        /**
         * Percentages of the relative CPU sharing in a given resource pool. This is not an actual
         * limit but a guideline of how much CPU should be divided among all containers running at a
         * given time.
         */
        @Documentation(description = "Percentages of the relative CPU sharing in a given resource"
                + " pool. This is not an actual limit but a guideline of how much CPU should be"
                + " divided among all containers running at a given time.")
        @UsageOption(option = PropertyUsageOption.OPTIONAL)
        public Integer cpuShares;

        /**
         * A list of resource limits to set in the container. The limit could be max open file
         * descriptors or any other limitation.
         */
        @Documentation(description = "A list of resource limits to set in the container")
        @UsageOption(option = PropertyUsageOption.OPTIONAL)
        @Since(ReleaseConstants.RELEASE_VERSION_0_9_5)
        public List<Ulimit> ulimits;

        /** Unmodeled container attributes */
        @Documentation(description = "Unmodeled container attributes")
        @PropertyOptions(indexing = { PropertyIndexingOption.EXCLUDE_FROM_SIGNATURE,
                PropertyIndexingOption.STORE_ONLY }, usage = { PropertyUsageOption.OPTIONAL })
        public Map<String, String> attributes;

        @Override
        public String retrieveCompositeComponentLink() {
            return compositeComponentLink;
        }
    }

    public ContainerService() {
        super(ContainerState.class);
        super.toggleOption(ServiceOption.PERSISTENCE, true);
        super.toggleOption(ServiceOption.REPLICATION, true);
        super.toggleOption(ServiceOption.OWNER_SELECTION, true);
        super.toggleOption(ServiceOption.INSTRUMENTATION, true);
    }

    @Override
    public void handleCreate(Operation startPost) {
        if (startPost.hasBody()) {
            ContainerState body = startPost.getBody(ContainerState.class);
            logFine("Initial name is %s", body.id);
            if (body.powerState == null) {
                body.powerState = PowerState.UNKNOWN;
            }

            // start the container stats service instance for this container
            startMonitoringContainerState(body);
        }

        startPost.complete();
    }

    @Override
    public void handleGet(Operation get) {
        // if GET query contains stats parameter forward to /stats utility service
        if (isStatsRequest(get)) {
            processStatsRequest(get);
            return;
        }
        super.handleGet(get);
    }

    @Override
    public void handlePut(Operation put) {
        if (!checkForBody(put)) {
            return;
        }

        ContainerState putBody = put.getBody(ContainerState.class);

        this.setState(put, putBody);
        put.setBody(putBody).complete();

    }

    @Override
    public void handlePatch(Operation patch) {
        ContainerState currentState = getState(patch);
        ContainerState patchBody = patch.getBody(ContainerState.class);

        if (ContainerStats.KIND.equals(patchBody.documentKind)) {
            patchContainerStats(patch, currentState);
            return;
        }

        ServiceDocumentDescription docDesc = getDocumentTemplate().documentDescription;
        String currentSignature = Utils.computeSignature(currentState, docDesc);

        PropertyUtils.mergeServiceDocuments(currentState, patchBody);

        String newSignature = Utils.computeSignature(currentState, docDesc);

        // if the signature hasn't change we shouldn't modify the state
        if (currentSignature.equals(newSignature)) {
            patch.setStatusCode(Operation.STATUS_CODE_NOT_MODIFIED);
        }

        if (ContainerUtil.isDiscoveredContainer(currentState)) {
            ContainerUtil.ContainerDescriptionHelper.createInstance(this)
                    .updateDiscoveredContainerDesc(currentState, patchBody);
        }

        ContainerUtil.ContainerDescriptionHelper.createInstance(this)
                .updateContainerPorts(currentState, patchBody);

        patch.complete();
    }

    @Override
    public void handleDelete(Operation delete) {
        super.handleDelete(delete);
    }

    private void patchContainerStats(Operation patch, ContainerState currentState) {
        ContainerStats patchStatsBody = patch.getBody(ContainerStats.class);

        ContainerStats containerStats = ContainerStats.transform(this);
        ContainerHealthEvaluator.create(getHost(), currentState)
                .calculateHealthStatus(containerStats, patchStatsBody);
        patchStatsBody.setStats(this);

        patch.setStatusCode(Operation.STATUS_CODE_NOT_MODIFIED);
        patch.complete();
    }

    private void startMonitoringContainerState(ContainerState body) {
        if (body.id != null) {
            // perform maintenance on startup to refresh the container attributes
            // but only for containers that already exist (and have and ID)
            getHost().registerForServiceAvailability((o, ex) -> {
                if (ex != null) {
                    logWarning("Skipping maintenance because service failed to start: %s",
                            ex.getMessage());
                } else {
                    handlePeriodicMaintenance(o);
                }
            }, getSelfLink());
        }
    }

    private boolean isStatsRequest(Operation op) {
        String q = op.getUri().getQuery();
        if (q == null || q.length() == 0) {
            return false;
        }

        return q.startsWith("stats");
    }

    /**
     * Request getting stats through the adapter and then return /stats as body response
     */
    private void processStatsRequest(Operation op) {
        ContainerState containerState = getState(op);
        AdapterRequest request = new AdapterRequest();
        request.resourceReference = UriUtils.buildUri(getHost(), containerState.documentSelfLink);
        request.operationTypeId = ContainerOperationType.STATS.id;
        request.serviceTaskCallback = ServiceTaskCallback.createEmpty();
        sendRequest(Operation
                .createPatch(this, containerState.adapterManagementReference.toString())
                .setBodyNoCloning(request)
                .setCompletion((o, ex) -> {
                    if (ex != null) {
                        // do not return, just log warning, previous /stats will be returned
                        Utils.logWarning("Exception in stats request for container: %s. Error: %s",
                                containerState.documentSelfLink, Utils.toString(ex));
                    }
                    forwardStatsResponse(op, containerState);
                }));
    }

    /**
     * Executes /stats request to the container state and copy its response to the GET operation.
     */
    private void forwardStatsResponse(Operation op, ContainerState containerState) {
        sendRequest(Operation
                .createGet(UriUtils.buildStatsUri(getHost(), containerState.documentSelfLink))
                .setExpiration(op.getExpirationMicrosUtc())
                .setCompletion((o, e) -> {
                    op.setBodyNoCloning(o.getBodyRaw());
                    op.setStatusCode(o.getStatusCode());
                    op.transferResponseHeadersFrom(o);
                    if (e != null) {
                        op.fail(e);
                    } else {
                        op.complete();
                    }
                }));
    }

    @Override
    public ServiceDocument getDocumentTemplate() {
        ContainerState template = (ContainerState) super.getDocumentTemplate();
        com.vmware.photon.controller.model.ServiceUtils.setRetentionLimit(template);

        template.names = new ArrayList<>(2);
        template.names.add("name1 (string)");
        template.names.add("name2 (string)");
        template.image = "library/hello-world";
        template.command = new String[] { "cat (string)" };
        template.volumesFrom = new String[] { "volumeFrom[:ro] (string)" };
        template.volumes = new String[] { "host-volume-dir:/container-volume-dir" };
        template.extraHosts = new String[] { "hostname:ip" };
        template.env = new String[] {
                "ENV_VAR=value (string)",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                        + ":/usr/local/go/bin:/go/bin" };

        PortBinding portBinding = new PortBinding();
        portBinding.containerPort = "5000";
        portBinding.hostPort = "5000";
        template.ports = Collections.singletonList(portBinding);

        template.ulimits = new ArrayList<>();

        template.customProperties = new HashMap<>(1);
        template.customProperties.put("propKey (string)", "customPropertyValue (string)");
        template.adapterManagementReference = URI.create("https://esxhost-01:443/provision-docker");
        template.powerState = ContainerState.PowerState.UNKNOWN;
        template.descriptionLink = UriUtils.buildUriPath(ContainerDescriptionService.FACTORY_LINK,
                "docker-nginx");
        template.attributes = new HashMap<>();
        template.attributes.put("Hostname (string)", "nginx (string)");

        template.networks = new LinkedHashMap<>();
        template.links = new String[] { "service:alias" };

        return template;
    }
}
