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
package eu.interiot.intermw.example.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.interiot.intermw.example.platform.model.Device;
import eu.interiot.intermw.example.platform.model.Subscription;
import org.apache.commons.cli.*;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.EntityManager;
import javax.persistence.EntityTransaction;
import javax.persistence.TypedQuery;
import java.net.URI;
import java.net.URL;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExamplePlatformEmulator {
    private final Logger logger = LoggerFactory.getLogger(ExamplePlatformEmulator.class);
    private static final int EMULATOR_PORT = 4568;
    private static final int DEFAULT_DELAY = 4;
    private static final int DEFAULT_NUMBER_OF_DEVICES = 10;
    private Thread dispatcherThread;
    private HttpServer httpServer;
    private int delay;
    private int numberOfDevices;

    public ExamplePlatformEmulator(int delay, int numberOfDevices) {
        this.delay = delay;
        this.numberOfDevices = numberOfDevices;
    }

    public void start() {
        logger.debug("ExamplePlatformEmulator is initializing...");
        logger.debug("Delay between observation messages is set to {} seconds.", delay);

        EmProvider.init();

        logger.debug("Number of emulated devices to generate: {}", numberOfDevices);
        EntityManager entityManager = EmProvider.getInstance().getEntityManager();
        EntityTransaction transaction = entityManager.getTransaction();
        transaction.begin();
        for (int i = 1; i <= numberOfDevices; i++) {
            Device device = new Device("http://example.com/devices/" + i);
            entityManager.persist(device);
        }
        transaction.commit();
        logger.debug("Devices have been generated successfully.");

        final ResourceConfig rc = new ResourceConfig().packages("eu.interiot.intermw.example.platform.rest");
        httpServer = GrizzlyHttpServerFactory.createHttpServer(URI.create("http://localhost:" + EMULATOR_PORT), rc);

        ObservationsDispatcher dispatcher = new ObservationsDispatcher();
        dispatcherThread = new Thread(dispatcher);
        dispatcherThread.start();

        logger.info("ExamplePlatformEmulator is listening on port {}.", EMULATOR_PORT);
    }

    public void stop() {
        httpServer.shutdown();
        dispatcherThread.interrupt();
        EmProvider.getInstance().close();
        logger.debug("ExamplePlatformEmulator has stopped.");
    }

    public static void main(String[] args) throws Exception {
        Options options = new Options();

        Option delayOpt = new Option("d", "delay", true,
                "delay between generated observations in seconds, default " + DEFAULT_DELAY + " seconds");
        delayOpt.setRequired(false);
        options.addOption(delayOpt);

        Option numberOfDevicesOpt = new Option("nd", "numberOfDevices", true,
                "number of emulated devices, default " + DEFAULT_NUMBER_OF_DEVICES);
        numberOfDevicesOpt.setRequired(false);
        options.addOption(numberOfDevicesOpt);

        Option helpOpt = new Option("h", "help", false, "show usage");
        helpOpt.setRequired(false);
        options.addOption(helpOpt);

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd = null;

        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("example-platform-emulator", options);

            System.exit(1);
        }

        if (cmd.hasOption("help")) {
            formatter.printHelp("java -jar example-platform-emulator-<version>.jar", options);
            return;
        }

        int delay = cmd.hasOption("delay") ?
                Integer.parseInt(cmd.getOptionValue("delay")) : DEFAULT_DELAY;
        int numberOfDevices = cmd.hasOption("numberOfDevices") ?
                Integer.parseInt(cmd.getOptionValue("numberOfDevices")) : DEFAULT_NUMBER_OF_DEVICES;

        new ExamplePlatformEmulator(delay, numberOfDevices).start();
    }

    class ObservationsDispatcher implements Runnable {
        private double value = 1.0;

        @Override
        public void run() {
            HttpClient httpClient = HttpClientBuilder.create().build();
            EntityManager entityManager = EmProvider.getInstance().getEntityManager();
            ObjectMapper objectMapper = new ObjectMapper();

            while (!Thread.interrupted()) {
                try {
                    Thread.sleep(delay * 1000);
                } catch (InterruptedException e) {
                    break;
                }

                if (System.getProperty("callbackUrl") == null) {
                    continue; // emulator hasn't been initialized yet
                }

                try {
                    URL callbackUrl = new URL(System.getProperty("callbackUrl"));
                    TypedQuery<Subscription> query = entityManager.createQuery("SELECT s from Subscription s", Subscription.class);
                    List<Subscription> subscriptions = query.getResultList();

                    for (Subscription subscription : subscriptions) {
                        logger.debug("Dispatching observations for subscription {} ({} devices)...",
                                subscription.getConversationId(), subscription.getDeviceIds().size());
                        for (String deviceId : subscription.getDeviceIds()) {
                            try {
                                Map<String, Object> observation = new HashMap<>();
                                observation.put("deviceId", deviceId);
                                observation.put("timestamp", new Date().getTime());
                                observation.put("value", value);
                                value = value + 1;

                                URL conversationEndpoint = new URL(callbackUrl, subscription.getConversationId());
                                HttpPost httpPost = new HttpPost(conversationEndpoint.toURI());
                                String observationJson = objectMapper.writeValueAsString(observation);
                                HttpEntity httpEntity = new StringEntity(observationJson, ContentType.APPLICATION_JSON);
                                httpPost.setEntity(httpEntity);
                                HttpResponse response = httpClient.execute(httpPost);
                                if (response.getStatusLine().getStatusCode() != 204) {
                                    throw new Exception("Invalid response from the INTER-MW: " + response.getStatusLine());
                                }
                                logger.debug("Observation for the device {} has been sent to {}.",
                                        deviceId, conversationEndpoint);

                            } catch (Exception e) {
                                logger.error(String.format("Failed to send observation for device %s and subscription %s to %s: %s",
                                        deviceId, subscription.getConversationId(), callbackUrl, e.getMessage()));
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.error("Failed to dispatch observations.", e);
                }
            }
        }
    }
}
