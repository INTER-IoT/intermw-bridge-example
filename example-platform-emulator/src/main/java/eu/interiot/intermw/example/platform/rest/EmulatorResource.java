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
package eu.interiot.intermw.example.platform.rest;

import eu.interiot.intermw.example.platform.EmProvider;
import eu.interiot.intermw.example.platform.model.Device;
import eu.interiot.intermw.example.platform.model.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.EntityManager;
import javax.persistence.EntityTransaction;
import javax.persistence.TypedQuery;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

@Path("/")
public class EmulatorResource {
    private final Logger logger = LoggerFactory.getLogger(EmulatorResource.class);

    @POST
    @Path("/configure")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response configurePlatform(Map<String, String> input) {
        System.setProperty("callbackUrl", input.get("callbackUrl"));
        logger.debug("Platform configured successfully. callbackUrl set to {}.", input.get("callbackUrl"));
        return Response.noContent().build();
    }

    @GET
    @Path("/devices")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Device> getDevices() {
        EntityManager entityManager = EmProvider.getInstance().getEntityManager();
        TypedQuery<Device> query = entityManager.createQuery("SELECT d from Device d", Device.class);
        return query.getResultList();
    }

    @GET
    @Path("/subscriptions")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Subscription> getSubscriptions() {
        EntityManager entityManager = EmProvider.getInstance().getEntityManager();
        TypedQuery<Subscription> query = entityManager.createQuery("SELECT s from Subscription s", Subscription.class);
        return query.getResultList();
    }

    @POST
    @Path("/subscriptions")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createSubscription(Subscription subscription) throws URISyntaxException {
        EntityManager entityManager = EmProvider.getInstance().getEntityManager();
        for (String deviceId : subscription.getDeviceIds()) {
            if (entityManager.find(Device.class, deviceId) == null) {
                return Response.status(Response.Status.BAD_REQUEST).build();
            }
        }

        EntityTransaction transaction = entityManager.getTransaction();
        transaction.begin();
        entityManager.persist(subscription);
        transaction.commit();
        logger.debug("Created subscription {} to devices {}.", subscription.getConversationId(), subscription.getDeviceIds());
        return Response.created(new URI(subscription.getConversationId())).build();
    }

    @DELETE
    @Path("/subscriptions/{conversationId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response removeSubscription(@PathParam("conversationId") String conversationId) {
        EntityManager entityManager = EmProvider.getInstance().getEntityManager();
        Subscription subscription = entityManager.find(Subscription.class, conversationId);
        if (subscription == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        EntityTransaction transaction = entityManager.getTransaction();
        transaction.begin();
        entityManager.remove(subscription);
        transaction.commit();
        logger.debug("Subscription {} has been removed.", subscription.getConversationId());
        return Response.noContent().build();
    }
}