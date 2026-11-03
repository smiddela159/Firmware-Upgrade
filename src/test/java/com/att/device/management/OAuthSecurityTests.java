package com.att.device.management;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OAuthSecurityTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Security: Protected endpoint /campaigns returns 401 Unauthorized without token")
    void testProtectedEndpointRejectsUnauthenticated() throws Exception {
        mockMvc.perform(post("/campaigns")
                        .header("Idempotency-Key", "unauth-key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Security: Public actuator endpoints are accessible without token")
    void testPublicActuatorAccessible() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Security: Device callback endpoints are accessible without token and without idempotency header")
    void testDeviceCallbacksAccessible() throws Exception {
        mockMvc.perform(post("/devices/gw-00001/inform")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"gw-00001\",\"firmwareVersion\":\"1.0.0\",\"event\":\"CONNECTION_REQUEST\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Security: Obtain OAuth2 Bearer token and access protected endpoint successfully")
    void testObtainTokenAndAccessProtectedApi() throws Exception {
        // 1. Request token via client credentials flow from Authorization Server
        MvcResult tokenResult = mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic("demo-client", "demo-secret"))
                        .param("grant_type", "client_credentials")
                        .param("scope", "campaign.read campaign.write"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").exists())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andReturn();

        String responseBody = tokenResult.getResponse().getContentAsString();
        JsonNode jsonNode = objectMapper.readTree(responseBody);
        String accessToken = jsonNode.get("access_token").asText();
        assertNotNull(accessToken);

        // 2. Call /campaigns with Bearer token
        String campJson = mockMvc.perform(post("/campaigns")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", "auth-camp-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetVersion": "2.0.0",
                                  "imageUrl": "http://cdn.broadband.att.com/images/bgw-2.0.0.bin",
                                  "deviceFilter": { "currentVersion": "1.0.0" },
                                  "maxConcurrent": 10,
                                  "failureThresholdPercent": 25.0
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.campaignId").exists())
                .andExpect(jsonPath("$.targetVersion").value("2.0.0"))
                .andExpect(jsonPath("$.createdBy").value("demo-client"))
                .andReturn().getResponse().getContentAsString();

        String createdCampId = objectMapper.readTree(campJson).get("campaignId").asText();

        // 3. Call GET /campaigns/{id} with Bearer token
        mockMvc.perform(get("/campaigns/" + createdCampId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdBy").value("demo-client"))
                .andExpect(jsonPath("$.lastModifiedBy").value("demo-client"))
                .andExpect(jsonPath("$.statusHistory[0].toStatus").value("RUNNING"))
                .andExpect(jsonPath("$.statusHistory[0].changedBy").value("demo-client"))
                .andExpect(jsonPath("$.timingMetrics").exists());

        // 4. Pause campaign and verify audit trail
        mockMvc.perform(post("/campaigns/" + createdCampId + "/pause")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", "pause-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.statusHistory[1].fromStatus").value("RUNNING"))
                .andExpect(jsonPath("$.statusHistory[1].toStatus").value("PAUSED"))
                .andExpect(jsonPath("$.statusHistory[1].changedBy").value("demo-client"));

        // 5. Call GET /campaigns/{id}/devices with Bearer token
        mockMvc.perform(get("/campaigns/" + createdCampId + "/devices")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campaignId").value(createdCampId))
                .andExpect(jsonPath("$.devices").isArray());

        // 6. Test invalid query param: ?state=FAILEDs returns descriptive 400 Bad Request
        mockMvc.perform(get("/campaigns/" + createdCampId + "/devices?state=FAILEDs")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("Invalid value 'FAILEDs' for parameter 'state'")))
                .andExpect(jsonPath("$.message").value(containsString("Allowed values are: [PENDING, NOTIFIED, DOWNLOAD_SENT, ACCEPTED, REBOOTING, SUCCEEDED, FAILED, TIMED_OUT]")));

        // 7. Test request body validation failure returns descriptive 400 with field errors
        mockMvc.perform(post("/campaigns")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", "invalid-body-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetVersion": "",
                                  "imageUrl": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors.targetVersion").value("targetVersion is required"))
                .andExpect(jsonPath("$.fieldErrors.imageUrl").value("imageUrl is required"));
    }
}

