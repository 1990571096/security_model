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
 * Full-chain ownership test: real Spring Security filter chain, real controllers, real
 * services and real MySQL rows — no mocked mapper or service. Two accounts sign in through
 * /auth/code + /auth/login and then address the same task.
 *
 * <p>Cross-user read is explicitly disabled here; the enabled variant lives in
 * {@link OwnershipEndToEndCrossReadIT}.</p>
 */
@SpringBootTest(classes = SecurityModelApplication.class)
@AutoConfigureMockMvc
class OwnershipEndToEndIT {

    private static final String JWT_SECRET = "e2e-ownership-secret-0123456789abcdef0123456789";

    static {
        // Touching the support class creates and seeds the isolated schema before the context starts.
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
        registry.add("security-model.ownership.admin-cross-user-read", () -> "false");
    }

    @Test
    void sameTaskIsReadableByItsOwnerAndForbiddenToTheOtherAccount() throws Exception {
        String alice = login("alice");
        String bob = login("bob");

        mockMvc.perform(get("/api/pre-defense/" + OwnershipE2ESupport.ALICE_TASK)
                        .header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value((int) OwnershipE2ESupport.ALICE_TASK));

        mockMvc.perform(get("/api/pre-defense/" + OwnershipE2ESupport.ALICE_TASK)
                        .header("Authorization", bob))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(get("/api/task-progress/pre_defense/" + OwnershipE2ESupport.ALICE_TASK)
                        .header("Authorization", bob))
                .andExpect(status().isForbidden());
    }

    @Test
    void writesOnAnotherUsersTaskAreForbidden() throws Exception {
        String bob = login("bob");

        mockMvc.perform(post("/api/pre-defense/start/" + OwnershipE2ESupport.ALICE_TASK)
                        .header("Authorization", bob))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(post("/api/pre-defense/cancel/" + OwnershipE2ESupport.ALICE_TASK)
                        .header("Authorization", bob))
                .andExpect(status().isForbidden());
    }

    @Test
    void demoAndUnattributedRowsStayClosedEvenForTheirPaperOwner() throws Exception {
        String alice = login("alice");

        mockMvc.perform(get("/api/pre-defense/" + OwnershipE2ESupport.ALICE_DEMO_TASK)
                        .header("Authorization", alice))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/pre-defense/start/" + OwnershipE2ESupport.ALICE_DEMO_TASK)
                        .header("Authorization", alice))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/pre-defense/" + OwnershipE2ESupport.UNATTRIBUTED_TASK)
                        .header("Authorization", alice))
                .andExpect(status().isForbidden());
    }

    @Test
    void accountDisabledAfterLoginLosesAccessImmediately() throws Exception {
        String bob = login("bob");
        mockMvc.perform(get("/api/pre-defense/" + OwnershipE2ESupport.BOB_TASK)
                        .header("Authorization", bob))
                .andExpect(status().isOk());

        OwnershipE2ESupport.setEnabled(OwnershipE2ESupport.BOB_ID, false);
        try {
            mockMvc.perform(get("/api/pre-defense/" + OwnershipE2ESupport.BOB_TASK)
                            .header("Authorization", bob))
                    .andExpect(status().isUnauthorized());
        } finally {
            OwnershipE2ESupport.setEnabled(OwnershipE2ESupport.BOB_ID, true);
        }
    }

    @Test
    void unknownUserAndLegacyTokenAreRejected() throws Exception {
        // Signed token for a user id that has no row: must not authenticate.
        String ghost = "Bearer " + signedToken("ghost", 9999L, true);
        mockMvc.perform(get("/api/pre-defense/" + OwnershipE2ESupport.ALICE_TASK)
                        .header("Authorization", ghost))
                .andExpect(status().isUnauthorized());

        // Token without userId (pre-R1 rollout): authenticated but cannot be mapped to an owner.
        String legacy = "Bearer " + signedToken("alice", null, false);
        mockMvc.perform(get("/api/pre-defense/" + OwnershipE2ESupport.ALICE_TASK)
                        .header("Authorization", legacy))
                .andExpect(status().isForbidden());
    }

    @Test
    void actuatorAndObservabilitySummaryRequireAdmin() throws Exception {
        // Health is public; aggregate metrics and the admin summary are not.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());

        String alice = login("alice");
        mockMvc.perform(get("/api/observability/summary")
                        .header("Authorization", alice))
                .andExpect(status().isForbidden());

        String admin = login("admin");
        mockMvc.perform(get("/api/observability/summary")
                        .header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("all-users"));
    }

    @Test
    void adminWithoutCrossUserReadCannotReachAnotherUsersTask() throws Exception {
        String admin = login("admin");
        mockMvc.perform(get("/api/pre-defense/" + OwnershipE2ESupport.ALICE_TASK)
                        .header("Authorization", admin))
                .andExpect(status().isForbidden());

        String alice = login("alice");
        mockMvc.perform(get("/api/task-progress/statistics").header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("current-user"));
    }

    @Test
    void exportDownloadAndArtifactPreviewAreOwnerScoped() throws Exception {
        String alice = login("alice");
        String bob = login("bob");

        mockMvc.perform(get("/api/pre-defense/cleaned-dataset/exports/" + OwnershipE2ESupport.EXPORT_ID)
                        .header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exportId").value((int) OwnershipE2ESupport.EXPORT_ID));

        mockMvc.perform(get("/api/pre-defense/cleaned-dataset/exports/" + OwnershipE2ESupport.EXPORT_ID + "/download")
                        .header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Content-Type", "application/zip"));

        mockMvc.perform(get("/api/pre-defense/artifact-preview/" + OwnershipE2ESupport.ARTIFACT_TASK)
                        .param("artifactPath", OwnershipE2ESupport.PREVIEW_PNG.toString())
                        .header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Content-Type", "image/png"));

        mockMvc.perform(get("/api/pre-defense/cleaned-dataset/exports/" + OwnershipE2ESupport.EXPORT_ID)
                        .header("Authorization", bob))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/pre-defense/cleaned-dataset/exports/" + OwnershipE2ESupport.EXPORT_ID + "/download")
                        .header("Authorization", bob))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/pre-defense/artifact-preview/" + OwnershipE2ESupport.ARTIFACT_TASK)
                        .param("artifactPath", OwnershipE2ESupport.PREVIEW_PNG.toString())
                        .header("Authorization", bob))
                .andExpect(status().isForbidden());
    }

    @Test
    void exportSubmitChecksOwnershipBeforeRatioValidation() throws Exception {
        String alice = login("alice");
        String bob = login("bob");

        mockMvc.perform(post("/api/pre-defense/cleaned-dataset/exports/" + OwnershipE2ESupport.ARTIFACT_TASK)
                        .param("isolationRatio", String.valueOf(OwnershipE2ESupport.EXPORT_RATIO))
                        .header("Authorization", bob))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Alice passes the ownership gate and then fails ratio validation: 400, not 403.
        mockMvc.perform(post("/api/pre-defense/cleaned-dataset/exports/" + OwnershipE2ESupport.ARTIFACT_TASK)
                        .param("isolationRatio", "999")
                        .header("Authorization", alice))
                .andExpect(status().isBadRequest());
    }

    @Test
    void auditQueryIsOwnerScoped() throws Exception {
        String alice = login("alice");
        String bob = login("bob");

        mockMvc.perform(get("/api/audit/task/" + OwnershipE2ESupport.ARTIFACT_TASK)
                        .header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("TASK_START"));

        mockMvc.perform(get("/api/audit/task/" + OwnershipE2ESupport.ARTIFACT_TASK)
                        .header("Authorization", bob))
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

    /** Builds a token with the same secret, optionally omitting the stable userId claim. */
    private String signedToken(String username, Long userId, boolean admin) {
        var builder = io.jsonwebtoken.Jwts.builder().subject(username)
                .claim("adm", admin)
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 3_600_000L))
                .signWith(io.jsonwebtoken.security.Keys
                        .hmacShaKeyFor(JWT_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (userId != null) {
            builder.claim("userId", userId);
        }
        return builder.compact();
    }
}
