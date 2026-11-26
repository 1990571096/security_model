package com.securitymodel.interfaces.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.SecurityModelApplication;
import com.securitymodel.support.OwnershipE2ESupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", OwnershipE2ESupport::schemaUrl);
        registry.add("spring.datasource.username", () -> OwnershipE2ESupport.USERNAME);
        registry.add("spring.datasource.password", () -> OwnershipE2ESupport.PASSWORD);
        registry.add("security-model.auth.jwt-secret", () -> JWT_SECRET);
        registry.add("security-model.ownership.admin-cross-user-read", () -> "true");
    }

    @Test
    void accountInfoReturnsTheAuthenticatedUsernameAndCurrentRole() throws Exception {
        mockMvc.perform(get("/auth/info").header("Authorization", login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.admin").value(true));
        mockMvc.perform(get("/auth/info").header("Authorization", login("bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("bob"))
                .andExpect(jsonPath("$.admin").value(false));
    }

    @Test
    void onlyAdminCanDeleteExperimenterAndHistoricalTaskRemainsReadable() throws Exception {
        String admin = login("admin");
        String accounts = "/api/admin/accounts";
        String username = "account_deletion_it";
        String password = "test-delete-password";
        String credentials = objectMapper.writeValueAsString(Map.of("username", username, "password", password));
        String accountInput = objectMapper.writeValueAsString(
                Map.of("username", username, "password", password, "nickName", "Deletion test"));
        String created = mockMvc.perform(post(accounts).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(accountInput))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long userId = objectMapper.readTree(created).path("userId").asLong();
        String account = accounts + "/" + userId;
        String session = loginWithCredentials(credentials);
        long taskId = 90801L;
        String task = "/api/pre-defense/" + taskId;
        jdbc.update("""
                INSERT INTO sys_pre_defense (task_id, task_name, status, dataset_id, algorithm_id, progress,
                                             create_by, owner_user_id, data_origin)
                VALUES (?, 'Deletion history', 'success', 1, 1, 100, ?, ?, 'platform')
                """, taskId, username, userId);
        try {
            mockMvc.perform(get(task).header("Authorization", session)).andExpect(status().isOk());
            mockMvc.perform(delete(account).header("Authorization", session)).andExpect(status().isForbidden());
            mockMvc.perform(delete(accounts + "/1").header("Authorization", admin)).andExpect(status().isBadRequest());
            mockMvc.perform(delete(account).header("Authorization", admin)).andExpect(status().isNoContent());

            mockMvc.perform(get(task).header("Authorization", session)).andExpect(status().isUnauthorized());
            mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get(accounts).header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.username == '" + username + "')]").isEmpty());
            mockMvc.perform(put(account + "/enabled").header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(delete(account).header("Authorization", admin)).andExpect(status().isNotFound());
            mockMvc.perform(post(accounts).header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON).content(accountInput))
                    .andExpect(status().isConflict());
            mockMvc.perform(get(task).header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("success"));
            assertEquals(1, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM sys_user
                    WHERE user_id = ? AND enabled = b'0' AND deleted_at IS NOT NULL AND password IS NULL
                    """, Integer.class, userId));
            assertEquals(1, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM security_operation_audit
                    WHERE resource_type = 'user_account' AND resource_id = ? AND action = 'DELETE'
                      AND operator = 'admin' AND after_status = 'deleted'
                    """, Integer.class, userId));
        } finally {
            jdbc.update("DELETE FROM sys_pre_defense WHERE task_id = ?", taskId);
        }
    }

    @Test
    void adminReadsAnotherUsersTaskAndStatisticsSwitchToAllUsers() throws Exception {
        String admin = login("admin");

        mockMvc.perform(get("/api/pre-defense/" + OwnershipE2ESupport.ALICE_TASK)
                        .header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(String.valueOf(OwnershipE2ESupport.ALICE_TASK)));

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
        return loginWithCredentials(objectMapper.writeValueAsString(Map.of(
                "username", username, "password", OwnershipE2ESupport.RAW_PASSWORD)));
    }

    private String loginWithCredentials(String body) throws Exception {
        String response = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("token").asText();
    }
}
