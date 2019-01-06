/*
 * Copyright 2016-2018 Universitat Politècnica de València
 * Copyright 2016-2018 Università della Calabria
 * Copyright 2016-2018 Prodevelop, SL
 * Copyright 2016-2018 Technische Universiteit Eindhoven
 * Copyright 2016-2018 Fundación de la Comunidad Valenciana para la
 * Investigación, Promoción y Estudios Comerciales de Valenciaport
 * Copyright 2016-2018 Rinicom Ltd
 * Copyright 2016-2018 Association pour le développement de la formation
 * professionnelle dans le transport
 * Copyright 2016-2018 Noatum Ports Valenciana, S.A.U.
 * Copyright 2016-2018 XLAB razvoj programske opreme in svetovanje d.o.o.
 * Copyright 2016-2018 Systems Research Institute Polish Academy of Sciences
 * Copyright 2016-2018 Azienda Sanitaria Locale TO5
 * Copyright 2016-2018 Alessandro Bassi Consulting SARL
 * Copyright 2016-2018 Neways Technologies B.V.
 *
 * See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership.
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
package eu.interiot.intermw.example.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.interiot.intermw.bridge.BridgeConfiguration;
import eu.interiot.intermw.bridge.abstracts.AbstractBridge;
import eu.interiot.intermw.bridge.annotations.Bridge;
import eu.interiot.intermw.bridge.exceptions.BridgeException;
import eu.interiot.intermw.commons.exceptions.MiddlewareException;
import eu.interiot.intermw.commons.model.Platform;
import eu.interiot.intermw.commons.model.enums.IoTDeviceType;
import eu.interiot.intermw.commons.requests.SubscribeReq;
import eu.interiot.intermw.commons.requests.UnsubscribeReq;
import eu.interiot.intermw.commons.requests.UpdatePlatformReq;
import eu.interiot.message.ID.EntityID;
import eu.interiot.message.ID.PropertyID;
import eu.interiot.message.Message;
import eu.interiot.message.MessageMetadata;
import eu.interiot.message.managers.URI.URIManagerMessageMetadata;
import eu.interiot.message.managers.URI.URIManagerMessageMetadata.MessageTypesEnum;
import eu.interiot.message.metadata.PlatformMessageMetadata;
import eu.interiot.message.payload.types.IoTDevicePayload;
import eu.interiot.message.payload.types.ObservationPayload;
import org.apache.http.client.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Spark;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Bridge(platformType = "http://example.com/ExamplePlatform")
public class ExampleBridge extends AbstractBridge {
    private final Logger logger = LoggerFactory.getLogger(ExampleBridge.class);
    private HttpClient httpClient;
    private Client client;
    private WebTarget platformTarget;

    public ExampleBridge(BridgeConfiguration configuration, Platform platform) throws MiddlewareException, URISyntaxException {
        super(configuration, platform);
        logger.debug("Example bridge is initializing...");

        // an example how to retrieve a property from bridge configuration file
        String myProperty = configuration.getProperty("example-bridge.myproperty");
        logger.debug("myProperty value: {}", myProperty);

        client = ClientBuilder.newClient();

        logger.info("Example bridge has been initialized successfully.");
    }

    @Override
    public Message registerPlatform(Message message) throws Exception {
        logger.debug("Registering platform {}...", platform.getPlatformId());
        // note: platform object is set by the AbstractBridge constructor when instantiating a new bridge, it
        // contains data extracted from the REGISTER_PLATFORM message. On the other hand the raw
        // REGISTER_PLATFORM message is given as a message parameter of registerPlatform method.

        platformTarget = client.target(platform.getBaseEndpoint().toURI());

        if (message.getMetadata().getMessageTypes().contains(MessageTypesEnum.SYS_INIT)) {
            // restore platform registration after INTER-MW restart. Registration request shouldn't be sent to the platform again.
            logger.debug("Registration of the platform {} has been restored.", platform.getPlatformId());
            return createResponseMessage(message);

        } else {
            Map<String, Object> postData = new HashMap<>();
            postData.put("callbackUrl", configuration.getBridgeCallbackUrl());

            Response response = platformTarget
                    .path("/configure")
                    .request()
                    .post(Entity.json(postData));
            if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
                throw new BridgeException("Unexpected response code received from the platform: " + response.getStatusInfo());
            }

            logger.debug("Platform {} has been registered successfully.", platform.getPlatformId());
            return createResponseMessage(message);
        }
    }

    @Override
    public Message updatePlatform(Message message) throws Exception {
        UpdatePlatformReq req = new UpdatePlatformReq(message);
        platform = req.getPlatform();
        platformTarget = client.target(platform.getBaseEndpoint().toURI());
        logger.debug("Platform {} registration has been updated successfully.", platform.getPlatformId());
        return createResponseMessage(message);
    }

    @Override
    public Message unregisterPlatform(Message message) throws Exception {
        // nothing needs to be done in case of Example platform
        logger.debug("Platform {} has been unregistered.", platform.getPlatformId());
        return createResponseMessage(message);
    }

    @Override
    public Message subscribe(Message message) throws Exception {
        SubscribeReq req = new SubscribeReq(message);
        String conversationId = req.getConversationId();

        if (message.getMetadata().getMessageTypes().contains(MessageTypesEnum.SYS_INIT)) {
            // restore subscription after INTER-MW restart. Subscription is still active on the platform so the
            // subscribe request shouldn't be sent again.
            logger.debug("Restoring subscription {}...", conversationId);
            createObservationsListener(conversationId);
            logger.debug("Subscription {} has been restored.", conversationId);
            return createResponseMessage(message);

        } else {
            logger.debug("Setting up subscription {}...", req.getConversationId());

            // send subscribe request to the platform
            Map<String, Object> postData = new HashMap<>();
            postData.put("conversationId", conversationId);
            postData.put("deviceIds", req.getDeviceIds());

            Response response = platformTarget
                    .path("/subscriptions")
                    .request()
                    .post(Entity.json(postData));
            if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
                throw new BridgeException("Unexpected response code received from the platform: " + response.getStatusInfo());
            }

            createObservationsListener(conversationId);

            logger.debug("Subscription {} to devices {} has been set up successfully.", conversationId, req.getDeviceIds());
            return createResponseMessage(message);
        }
    }

    private void createObservationsListener(String conversationId) throws MalformedURLException {
        logger.debug("Creating callback listener for conversation {} listening at {}...", conversationId,
                new URL(bridgeCallbackUrl, conversationId));

        ObjectMapper objectMapper = new ObjectMapper();

        Spark.post(conversationId, (request, response) -> {
            logger.debug("Observation received from the Example platform {}: {}", platform.getPlatformId(), request.body());
            try {
                ExampleObservation observation = objectMapper.readValue(request.body(), ExampleObservation.class);

                // create message metadata
                PlatformMessageMetadata metadata = new MessageMetadata().asPlatformMessageMetadata();
                metadata.initializeMetadata();
                metadata.addMessageType(URIManagerMessageMetadata.MessageTypesEnum.OBSERVATION);
                metadata.setSenderPlatformId(new EntityID(platform.getPlatformId()));
                metadata.setConversationId(conversationId);

                // create message payload
                ObservationPayload payload = new ObservationPayload();
                EntityID observationID = new EntityID("http://example.com/observations/" + UUID.randomUUID().toString());
                payload.createObservation(observationID);
                payload.setHasLocation(observationID, new EntityID("http://example.com/ExampleLocation"));
                payload.setHasFeatureOfInterest(observationID, new EntityID("http://example.com/FeatureOfInterest"));
                payload.setHasResult(observationID, new EntityID("http://example.com/" + observation.getValue()));
                payload.setMadeBySensor(observationID, new EntityID(observation.getDeviceId()));
                payload.setObservedProperty(observationID, new EntityID("http://example.com/ObservedProperty"));
                payload.setResultTime(observationID, Long.toString(observation.getTimestamp()));

                Message observationMessage = new Message();
                observationMessage.setMetadata(metadata);
                observationMessage.setPayload(payload);

                publisher.publish(observationMessage);
                logger.debug("Observation message {} has been published upstream.", metadata.getMessageID().get());

                response.status(204);
                return "";

            } catch (Exception e) {
                logger.debug("Failed to handle observation with conversationId " + conversationId + ": " + e.getMessage(), e);
                response.status(400);
                return "Failed to handle observation: " + e.getMessage();
            }
        });
    }

    @Override
    public Message unsubscribe(Message message) throws Exception {
        UnsubscribeReq req = new UnsubscribeReq(message);
        String conversationId = req.getSubscriptionId(); // conversation ID to unsubscribe from
        logger.debug("Unsubscribing from the conversation {}...", conversationId);

        Response response = platformTarget
                .path("/subscriptions/{conversationId}")
                .resolveTemplate("conversationId", conversationId)
                .request()
                .delete();
        if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
            throw new BridgeException("Unexpected response code received from the platform: " + response.getStatusInfo());
        }

        logger.debug("Unsubscribed from the conversation {} successfully.", conversationId);
        return createResponseMessage(message);
    }

    @Override
    public Message query(Message message) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Message listDevices(Message message) throws Exception {
        List<ExampleDevice> devices = null;
        try {
            devices = platformTarget
                    .path("/devices")
                    .request(MediaType.APPLICATION_JSON_TYPE)
                    .get(new GenericType<List<ExampleDevice>>() {
                    });
        } catch (Exception e) {
            throw new BridgeException(String.format(
                    "Failed to retrieve list of devices from the platform %s.", platform.getPlatformId()), e);
        }

        MessageMetadata metadata = new MessageMetadata();
        metadata.initializeMetadata();
        metadata.addMessageType(MessageTypesEnum.DEVICE_REGISTRY_INITIALIZE);
        metadata.asPlatformMessageMetadata().setSenderPlatformId(new EntityID(platform.getPlatformId()));

        IoTDevicePayload payload = new IoTDevicePayload();
        for (ExampleDevice device : devices) {
            EntityID entityID = new EntityID(device.getDeviceId());
            payload.createIoTDevice(entityID);
            payload.setHasName(entityID, "Example device " + device.getDeviceId());
            payload.setIsHostedBy(entityID, new EntityID(platform.getPlatformId()));

            PropertyID deviceTypePropertyId = new PropertyID("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
            payload.addDataPropertyAssertionToEntity(entityID, deviceTypePropertyId, IoTDeviceType.SENSOR.getDeviceTypeUri());
        }

        Message registryInitializeMessage = new Message();
        registryInitializeMessage.setMetadata(metadata);
        registryInitializeMessage.setPayload(payload);

        // publish DEVICE_REGISTRY_INITIALIZE message
        publisher.publish(registryInitializeMessage);

        // return LIST_DEVICES response message
        return createResponseMessage(message);
    }

    @Override
    public Message platformCreateDevices(Message message) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Message platformUpdateDevices(Message message) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Message platformDeleteDevices(Message message) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void observe(Message message) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Message actuate(Message message) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Message error(Message message) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Message unrecognized(Message message) {
        throw new UnsupportedOperationException();
    }

    private static class ExampleObservation {
        private String deviceId;
        private long timestamp;
        private double value;

        public ExampleObservation() {
        }

        public String getDeviceId() {
            return deviceId;
        }

        public void setDeviceId(String deviceId) {
            this.deviceId = deviceId;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }

        public double getValue() {
            return value;
        }

        public void setValue(double value) {
            this.value = value;
        }
    }

    private static class ExampleDevice {
        public String deviceId;

        public ExampleDevice() {
        }

        public String getDeviceId() {
            return deviceId;
        }

        public void setDeviceId(String deviceId) {
            this.deviceId = deviceId;
        }
    }
}
