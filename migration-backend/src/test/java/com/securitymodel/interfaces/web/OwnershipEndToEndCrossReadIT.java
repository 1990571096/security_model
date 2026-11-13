package com.securitymodel.interfaces.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.SecurityModelApplication;
import com.securitymodel.support.OwnershipE2ESupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Same full-chain setup as {@link OwnershipEndToEndIT}, but with
 * {@code admin-cross-user-read=true}: an administrator may read another account's task,
 * while cross-user writes stay forbidden and ordinary accounts remain isolated.
 */
@SpringBootTest(classes = SecurityModelApplication.class)
@AutoConfigureMockMvc
class OwnershipEndToEndCrossReadIT {

    private static final String JWT_SECRET = "e2e-ownership-secret-0123456789abcdef0123456789";

    static {
        OwnershipE2ESupport.schemaUrl();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", OwnershipE2ESupport::schemaUrl);
        registry.add("spring.datasource.username", () -> OwnershipE2ESupport.USERNAME);
        registry.add("spring.datasource.password", () -> OwnershipE2ESupport.PASSWORD);
        registry.add("security-model.auth.jwt-secret", () -> JWT_SECRET);
        registry.add("security-model.ownership.admin-cross-user-read", () -> "true");
    }

    @Test
    void adminReadsAnotherUsersTaskAndStatisticsSwitchToAllUsers() throws Exception {
        String admin = login("admin");

        mockMvc.perform(get("/api/pre-defense/" + OwnershipE2ESupport.ALICE_TASK)
                        .header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value((int) OwnershipE2ESupport.ALICE_TASK));

        mockMvc.perform(get("/api/task-progress/statistics").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("all-users"));
    }

    @Test
    void adminStillCannotWriteAnotherUsersTask() throws Exception {
        String admin = login("admin");
        mockMvc.perform(post("/api/pre-defense/start/" + OwnershipE2ESupport.ALICE_TASK)
                        .header("Authorization", admin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void ordinaryAccountsStayIsolatedEvenWhenTheAdminSwitchIsOn() throws Exception {
        String bob = login("bob");
        mockMvc.perform(get("/api/pre-defense/" + OwnershipE2ESupport.ALICE_TASK)
                        .header("Authorization", bob))
                .andExpect(status().isForbidden());

        // The switch is admin-only: it does not make rows readable for ordinary accounts, and
        // an admin with cross-user read sees unattributed rows as well.
        String admin = login("admin");
        mockMvc.perform(get("/api/pre-defense/" + OwnershipE2ESupport.UNATTRIBUTED_TASK)
                        .header("Authorization", admin))
                .andExpect(status().isOk());
    }

    @Test
    void adminReadsExportsArtifactsAndAuditButStillCannotSubmit() throws Exception {
        String admin = login("admin");

        mockMvc.perform(get("/api/pre-defense/cleaned-dataset/exports/" + OwnershipE2ESupport.EXPORT_ID)
                        .header("Authorization", admin))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/pre-defense/cleaned-dataset/exports/" + OwnershipE2ESupport.EXPORT_ID + "/download")
                        .header("Authorization", admin))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/pre-defense/artifact-preview/" + OwnershipE2ESupport.ARTIFACT_TASK)
                        .param("artifactPath", OwnershipE2ESupport.PREVIEW_PNG.toString())
                        .header("Authorization", admin))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/audit/task/" + OwnershipE2ESupport.ARTIFACT_TASK)
                        .header("Authorization", admin))
                .andExpect(status().isOk());

        // Cross-user read never grants writes: submitting an export for another user's task is still forbidden.
        mockMvc.perform(post("/api/pre-defense/cleaned-dataset/exports/" + OwnershipE2ESupport.ARTIFACT_TASK)
                        .param("isolationRatio", String.valueOf(OwnershipE2ESupport.EXPORT_RATIO))
                        .header("Authorization", admin))
                .andExpect(status().isForbidden());
    }

    private String login(String username) throws Exception {
        String codeResponse = mockMvc.perform(get("/auth/code"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String uuid = objectMapper.readTree(codeResponse).path("uuid").asText();
        String image = objectMapper.readTree(codeResponse).path("img").asText();
        String svg = new String(java.util.Base64.getDecoder().decode(image.split(",")[1]));
        var captcha = java.util.regex.Pattern.compile("<text[^>]*>(\\d{4})</text>").matcher(svg);
        assertTrue(captcha.find(), "captcha svg must contain a 4 digit code");

        String body = objectMapper.writeValueAsString(Map.of(
                "uuid", uuid, "code", captcha.group(1), "username", username,
                "password", OwnershipE2ESupport.RAW_PASSWORD));
        String response = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("token").asText();
    }
}
