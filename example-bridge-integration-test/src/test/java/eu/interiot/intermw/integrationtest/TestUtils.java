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

import eu.interiot.intermw.commons.interfaces.Configuration;
import org.apache.commons.io.FileUtils;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.rdfconnection.RDFConnectionFactory;
import org.apache.jena.update.UpdateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;
import java.io.File;
import java.nio.charset.Charset;

public class TestUtils {
    private static final Logger logger = LoggerFactory.getLogger(TestUtils.class);

    public static void clearParliament(Configuration conf) {
        try (RDFConnection conn = RDFConnectionFactory.connect(conf.getParliamentUrl(), "sparql", "sparql", "sparql")) {
            String allGraphsQuery = "SELECT DISTINCT ?g \n" +
                    "WHERE {\n" +
                    "  GRAPH ?g {}\n" +
                    "}";
            QueryExecution allGraphsQueryExecution = conn.query(allGraphsQuery);
            ResultSet allGraphsResultSet = allGraphsQueryExecution.execSelect();

            UpdateRequest updateRequest = new UpdateRequest();
            while (allGraphsResultSet.hasNext()) {
                QuerySolution next = allGraphsResultSet.next();
                String graphName = next.get("g").asResource().toString();

                if (graphName.contains("Default Graph") || graphName.contains("http://parliament.semwebcentral.org/parliament#MasterGraph")) {
                    continue;
                }

                updateRequest.add("DROP GRAPH <" + graphName + ">;");
            }
            if (!updateRequest.getOperations().isEmpty()) {
                conn.update(updateRequest);
            }
        }
        logger.debug("Parliament has been cleared.");
    }

    public static void clearRabbitMQ(Configuration conf) {
        CachingConnectionFactory cf = new CachingConnectionFactory(conf.getRabbitmqHostname(), conf.getRabbitmqPort());
        cf.setUsername(conf.getRabbitmqUsername());
        cf.setPassword(conf.getRabbitmqPassword());
        RabbitAdmin rabbitAdmin = new RabbitAdmin(cf);
        String[] queuesToDelete = {"arm_prm_queue", "prm_arm_queue",
                "prm_ipsmrm_queue", "ipsmrm_prm_queue",
                "error_queue",
                "ipsmrm_bridge_http_example.com_platform1_queue",
                "bridge_ipsmrm_http_example.com_platform1_queue",
                "client-testclient"};
        for (String queueToDelete : queuesToDelete) {
            rabbitAdmin.deleteQueue(queueToDelete);
        }
        logger.debug("RabbitMQ has been cleared.");
    }

    public static void uploadAlignment(String ipsmApiBaseUrl, String alignmentFilePath) throws Exception {
        Client client = ClientBuilder.newClient();
        WebTarget webTarget = client.target(ipsmApiBaseUrl);

        String alignmentData = FileUtils.readFileToString(new File(alignmentFilePath), Charset.forName("UTF-8"));

        Response response = webTarget.path("/alignments")
                .request()
                .post(Entity.xml(alignmentData));
        if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
            logger.debug("Alignment file {} has been uploaded successfully.", alignmentFilePath);

        } else {
            String content = response.readEntity(String.class);
            throw new Exception(String.format("Failed to upload IPSM alignment. Response from IPSM API: %s: %s",
                    response.getStatusInfo(), content));
        }
    }
}
