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
package eu.interiot.intermw.integrationtest;

import eu.interiot.intermw.api.InterMwApiImpl;
import eu.interiot.intermw.commons.DefaultConfiguration;
import eu.interiot.intermw.commons.interfaces.Configuration;
import eu.interiot.intermw.commons.model.Client;
import eu.interiot.intermw.commons.model.IoTDevice;
import eu.interiot.intermw.commons.model.Platform;
import eu.interiot.message.Message;
import eu.interiot.message.managers.URI.URIManagerMessageMetadata.MessageTypesEnum;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class ExampleBridgeIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(ExampleBridgeIntegrationTest.class);

    // INTER-MW configuration file
    private static final String CONFIG_FILE = "intermw.properties";

    // platform type as defined by platformType attribute of @Bridge annotation of the bridge class ExampleBridge
    private static final String PLATFORM_TYPE = "http://example.com/ExamplePlatform";

    // ID of the client created in the integration test
    private static final String CLIENT_ID = "testclient";

    // ID of the platform instance created in the integration test
    private static final String PLATFORM_ID = "http://example.com/platform1";

    // address of the Example Platform emulator
    private static final String PLATFORM_ENDPOINT = "http://localhost:4568";

    // device ID prefix
    private static final String DEVICE_ID_PREFIX = "http://example.com/devices/";

    // location of the platform and devices
    private static final String LOCATION_ID = "http://example.com/ExampleLocation";

    // maximum time to wait for response messages before integration test fails
    private static final int TIMEOUT = 30;

    // number of emulated devices on Example platform
    private static final int NUMBER_OF_DEVICES = 10;

    // InterMwApi instance
    private InterMwApiImpl interMwApi;

    // conversations map where incoming response messages from INTER-MW are stored
    private Map<String, BlockingQueue<Message>> conversations = new HashMap<>();

    // INTER-MW configuration
    private Configuration conf;

    @Before
    public void setUp() throws Exception {
        conf = new DefaultConfiguration(CONFIG_FILE);
        TestUtils.clearParliament(conf);
        TestUtils.clearRabbitMQ(conf);

        interMwApi = new InterMwApiImpl(conf);
    }

    /**
     * Integration test without using IPSM semantic translation
     *
     * @throws Exception
     */
    @Test
    public void testWithoutIPSM() throws Exception {
        logger.info("Example Bridge integration test (without IPSM) has started.");
        registerClient();
        registerPlatform(false);
        String conversationId = subscribe();
        checkObservations(conversationId);
        unsubscribe(conversationId);
        unregisterPlatform();
        unregisterClient();
        logger.info("Example Bridge integration test (without IPSM) finished successfully.");
    }

    /**
     * Integration test with using IPSM semantic translation
     *
     * @throws Exception
     */
    @Test
    public void testWithIPSM() throws Exception {
        logger.info("Example Bridge integration test (with IPSM) has started.");

        logger.debug("Uploading alignments...");
        TestUtils.uploadAlignment(conf.getIPSMApiBaseUrl(), "src/test/resources/alignments/example-downstream-alignment.rdf");
        TestUtils.uploadAlignment(conf.getIPSMApiBaseUrl(), "src/test/resources/alignments/example-upstream-alignment.rdf");

        registerClient();
        registerPlatform(true);
        String conversationId = subscribe();
        checkObservations(conversationId);
        unsubscribe(conversationId);
        unregisterPlatform();
        unregisterClient();
        logger.info("Example Bridge integration test (with IPSM) finished successfully.");
    }

    /**
     * Registers CLIENT_ID client with INTER-MW and starts thread for retrieving response messages.
     *
     * @throws Exception
     */
    private void registerClient() throws Exception {
        Client client = new Client();
        client.setClientId(CLIENT_ID);
        client.setResponseDelivery(Client.ResponseDelivery.CLIENT_PULL);
        client.setResponseFormat(Client.ResponseFormat.JSON_LD);

        interMwApi.registerClient(client);
        startPullingResponseMessages(CLIENT_ID);
    }

    /**
     * Registers PLATFORM_ID platform with INTER-MW and waits for the response message.
     *
     * @throws Exception
     */
    private void registerPlatform(boolean useAlignments) throws Exception {
        Platform platform = new Platform();
        platform.setPlatformId(PLATFORM_ID);
        platform.setClientId(CLIENT_ID);
        platform.setName("Example Platform #1");
        platform.setType(PLATFORM_TYPE);
        platform.setBaseEndpoint(new URL(PLATFORM_ENDPOINT));
        platform.setLocationId(LOCATION_ID);

        if (useAlignments) {
            platform.setDownstreamInputAlignmentName("");
            platform.setDownstreamInputAlignmentVersion("");
            platform.setDownstreamOutputAlignmentName("Example_Downstream_Alignment");
            platform.setDownstreamOutputAlignmentVersion("1.0");

            platform.setUpstreamInputAlignmentName("Example_Upstream_Alignment");
            platform.setUpstreamInputAlignmentVersion("1.0");
            platform.setUpstreamOutputAlignmentName("");
            platform.setUpstreamOutputAlignmentVersion("");
        }

        String conversationId = interMwApi.registerPlatform(platform);
        Message responseMessage = waitForResponseMessage(conversationId);
        Set<MessageTypesEnum> messageTypes = responseMessage.getMetadata().getMessageTypes();
        assertTrue(messageTypes.containsAll(EnumSet.of(MessageTypesEnum.RESPONSE, MessageTypesEnum.PLATFORM_REGISTER)));

        // wait for devices discovery process to finish and check if it was successful
        long startTime = new Date().getTime();
        List<IoTDevice> registeredDevices;
        do {
            registeredDevices = interMwApi.listDevices(CLIENT_ID, platform.getPlatformId());
            if (registeredDevices.size() >= NUMBER_OF_DEVICES) {
                break;
            }
        } while (new Date().getTime() - startTime < TIMEOUT * 1000);
        assertEquals("Device discovery process failed.", NUMBER_OF_DEVICES, registeredDevices.size());
    }

    /**
     * Unregisters PLATFORM_ID platform from INTER-MW and waits for the response message.
     *
     * @throws Exception
     */
    private void unregisterPlatform() throws Exception {
        String conversationId = interMwApi.removePlatform(CLIENT_ID, PLATFORM_ID);
        Message responseMessage = waitForResponseMessage(conversationId);
        Set<MessageTypesEnum> messageTypes = responseMessage.getMetadata().getMessageTypes();
        assertTrue(messageTypes.containsAll(EnumSet.of(MessageTypesEnum.RESPONSE, MessageTypesEnum.PLATFORM_UNREGISTER)));
    }

    /**
     * Subscribes to selected devices and waits for the response message.
     *
     * @return ID of the conversation corresponding to the subscription
     * @throws Exception
     */
    private String subscribe() throws Exception {
        List<String> deviceIds = new ArrayList<>();

        for (int i = 1; i <= 3; i++) {
            String deviceId = DEVICE_ID_PREFIX + i;
            deviceIds.add(deviceId);
        }

        String conversationId = interMwApi.subscribe(CLIENT_ID, deviceIds);

        Message responseMessage = waitForResponseMessage(conversationId);
        Set<MessageTypesEnum> messageTypes = responseMessage.getMetadata().getMessageTypes();
        assertTrue(messageTypes.containsAll(EnumSet.of(MessageTypesEnum.RESPONSE, MessageTypesEnum.SUBSCRIBE)));
        return conversationId;
    }

    /**
     * Waits for a few observation messages corresponding to specified conversation coming from the Example platform emulator
     * through INTER-MW.
     *
     * @param conversationId ID of the conversation
     * @throws Exception
     */
    private void checkObservations(String conversationId) throws Exception {
        for (int i = 0; i < 6; i++) {
            Message observationMessage = waitForResponseMessage(conversationId);
            Set<MessageTypesEnum> messageTypes = observationMessage.getMetadata().getMessageTypes();
            assertTrue(messageTypes.contains(MessageTypesEnum.OBSERVATION));
        }
    }

    /**
     * Unsubscribes from specified conversation and waits for the response message.
     *
     * @param conversationId ID of the conversation
     * @throws Exception
     */
    private void unsubscribe(String conversationId) throws Exception {
        String responseConversationId = interMwApi.unsubscribe(CLIENT_ID, conversationId);
        Message responseMessage = waitForResponseMessage(responseConversationId);
        Set<MessageTypesEnum> messageTypes = responseMessage.getMetadata().getMessageTypes();
        assertTrue(messageTypes.containsAll(EnumSet.of(MessageTypesEnum.RESPONSE, MessageTypesEnum.UNSUBSCRIBE)));
    }

    /**
     * Unregisters CLIENT_ID client.
     *
     * @throws Exception
     */
    private void unregisterClient() throws Exception {
        interMwApi.removeClient(CLIENT_ID);
    }

    /**
     * Creates a new thread for retrieving response messages from INTER-MW for specified client and storing them to
     * corresponding BlockingQueue in conversations map.
     *
     * @param clientId ID of the client for which to retrieve response messages
     */
    private void startPullingResponseMessages(String clientId) {
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                do {
                    try {
                        Message message = interMwApi.retrieveResponseMessage(clientId, -1);
                        String conversationId = message.getMetadata().getConversationId().get();
                        logger.debug("New message received with conversationId {} and type {}.",
                                conversationId, message.getMetadata().getMessageTypes());

                        conversations.get(conversationId).add(message);

                    } catch (Exception e) {
                        logger.error("Failed to retrieve response message.", e);
                        fail();
                    }
                } while (true);
            }
        };
        new Thread(runnable).start();
    }

    /**
     * Waits for a response message with specified conversationId, waiting up to the TIMEOUT time.
     *
     * @param conversationId conversation ID
     * @return response message
     * @throws InterruptedException
     */
    private Message waitForResponseMessage(String conversationId) throws InterruptedException {
        if (!conversations.containsKey(conversationId)) {
            conversations.put(conversationId, new LinkedBlockingQueue<>());
            logger.debug("Added new conversationId: {}", conversationId);
        }

        Message message = conversations.get(conversationId).poll(TIMEOUT, TimeUnit.SECONDS);
        assertNotNull(message);
        assertEquals(message.getMetadata().getConversationId().get(), conversationId);
        assertEquals(message.getMetadata().asPlatformMessageMetadata().getSenderPlatformId().get().toString(), PLATFORM_ID);
        return message;
    }
}
