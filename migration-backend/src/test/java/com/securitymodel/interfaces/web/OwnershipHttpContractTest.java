package com.securitymodel.interfaces.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.application.audit.OperationAuditService;
import com.securitymodel.application.resource.ResourceQueryService;
import com.securitymodel.application.task.AfterCommitTaskDispatcher;
import com.securitymodel.application.task.ExecutionFingerprintService;
import com.securitymodel.application.task.LddConfigurationPolicy;
import com.securitymodel.application.task.PreDefenseTaskCommandService;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.application.task.TaskExecutionQuotaPolicy;
import com.securitymodel.application.task.TaskProgressQueryService;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.domain.user.SysUser;
import com.securitymodel.infrastructure.executor.DefenseTaskExecutor;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import com.securitymodel.infrastructure.persistence.mapper.SysUserMapper;
import com.securitymodel.infrastructure.security.JwtAuthenticationFilter;
import com.securitymodel.infrastructure.security.JwtTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two-account HTTP contract: real JWT filter, real {@link TaskAccessGuard}, real query and
 * command services, real controllers. Persistence is mocked, so this proves the HTTP
 * authorization chain (token -> principal -> guard -> status code) rather than SQL.
 * The SQL side is covered by {@code MySqlTaskPersistenceIT} and
 * {@code OwnershipMigrationScriptIT}.
 */
class OwnershipHttpContractTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef-r1-http-test";
    private static final String LOGIN_PASSWORD = "pw";
    private static final String ENCODED_PASSWORD =
            new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(LOGIN_PASSWORD);
    private static final long ADMIN = 1L;
    private static final long ALICE = 2L;
    private static final long BOB = 3L;

    private final PreDefenseTaskMapper taskMapper = mock(PreDefenseTaskMapper.class);
    private final SysUserMapper userMapper = mock(SysUserMapper.class);
    private final ResourceQueryService resourceQueryService = mock(ResourceQueryService.class);
    private final JwtTokenService tokenService = new JwtTokenService(SECRET, Duration.ofHours(1));
    private final CurrentUserService currentUserService = new CurrentUserService();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.initialize();

        when(taskMapper.selectCount(any())).thenReturn(0L);
        when(resourceQueryService.findAlgorithmsByIds(any())).thenReturn(Map.of());
        when(resourceQueryService.findDatasetsByIds(any())).thenReturn(Map.of());
        when(taskMapper.selectById(100L)).thenReturn(storedTask(100L));
        when(taskMapper.selectById(200L)).thenReturn(storedTask(200L));
        when(taskMapper.selectById(300L)).thenReturn(storedTask(300L));
        when(taskMapper.selectById(999L)).thenReturn(null);
        when(taskMapper.selectBatchIds(any())).thenAnswer(invocation -> {
            Iterable<?> ids = invocation.getArgument(0);
            List<PreDefenseTask> found = new java.util.ArrayList<>();
            for (Object id : ids) {
                found.add(storedTask(((Number) id).longValue()));
            }
            return found;
        });
        when(userMapper.findStateById(ADMIN)).thenReturn(user(ADMIN, "admin", true, true));
        when(userMapper.findStateById(ALICE)).thenReturn(user(ALICE, "alice", false, true));
        when(userMapper.findStateById(BOB)).thenReturn(user(BOB, "bob", false, false));
        when(userMapper.findByUsername("alice")).thenReturn(loginUser(ALICE, "alice"));
        when(userMapper.findByUsername("bob")).thenReturn(loginUser(BOB, "bob"));

        mockMvc = mockMvc(false);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
        SecurityContextHolder.clearContext();
    }

    @Test
    void ownerReadsOwnTaskOverHttp() throws Exception {
        mockMvc.perform(get("/api/pre-defense/100").header("Authorization", bearer(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(100));
    }

    @Test
    void secondAccountGets403OnEveryEntryPoint() throws Exception {
        // Direct id read: task command detail.
        mockMvc.perform(get("/api/pre-defense/100").header("Authorization", bearer(BOB)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Progress query endpoint.
        mockMvc.perform(get("/api/task-progress/pre_defense/100").header("Authorization", bearer(BOB)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Write path: start.
        mockMvc.perform(post("/api/pre-defense/start/100").header("Authorization", bearer(BOB)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Write path: delete (batch body).
        mockMvc.perform(delete("/api/pre-defense")
                        .header("Authorization", bearer(BOB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[100]"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void demoRowStaysUnreadableAndUnwritableForItsOwner() throws Exception {
        mockMvc.perform(get("/api/pre-defense/200").header("Authorization", bearer(ALICE)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/pre-defense/start/200").header("Authorization", bearer(ALICE)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/pre-defense")
                        .header("Authorization", bearer(ALICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[200]"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCrossUserReadFollowsTheSwitchOnly() throws Exception {
        mockMvc(false).perform(get("/api/pre-defense/100").header("Authorization", bearer(ADMIN)))
                .andExpect(status().isForbidden());

        mockMvc(true).perform(get("/api/pre-defense/100").header("Authorization", bearer(ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void disabledAccountTokenIsRejectedEvenThoughItIsSigned() throws Exception {
        // Contrast: while enabled, the same signed token reads its own task fine.
        when(userMapper.findStateById(BOB)).thenReturn(user(BOB, "bob", false, true));
        mockMvc.perform(get("/api/pre-defense/300").header("Authorization", bearer(BOB)))
                .andExpect(status().isOk());

        // Once disabled in sys_user, the token stops working without waiting for expiry.
        when(userMapper.findStateById(BOB)).thenReturn(user(BOB, "bob", false, false));
        mockMvc.perform(get("/api/pre-defense/300").header("Authorization", bearer(BOB)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/pre-defense/start/300").header("Authorization", bearer(BOB)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminClaimInTheTokenIsNotTrusted() throws Exception {
        // A token carrying adm=true for an account that is no longer admin must not read others.
        String staleAdminToken = "Bearer " + tokenService.issue(ADMIN, "admin", true);
        when(userMapper.findStateById(ADMIN)).thenReturn(user(ADMIN, "admin", false, true));

        mockMvc(true).perform(get("/api/pre-defense/100").header("Authorization", staleAdminToken))
                .andExpect(status().isForbidden());
        mockMvc(true).perform(get("/api/task-progress/statistics").header("Authorization", staleAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("current-user"));
    }

    @Test
    void statisticsScopeFollowsTheSwitch() throws Exception {
        mockMvc.perform(get("/api/task-progress/statistics").header("Authorization", bearer(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("current-user"));

        mockMvc(true).perform(get("/api/task-progress/statistics").header("Authorization", bearer(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("all-users"));
    }

    @Test
    void loginIssuedTokenIsAcceptedAndStillCannotCrossUsers() throws Exception {
        // bob can log in, but the filter still refuses cross-user reads on the same task.
        when(userMapper.findStateById(BOB)).thenReturn(user(BOB, "bob", false, true));

        // Real /auth/code -> /auth/login flow, then reuse the issued token against task APIs.
        String aliceToken = login("alice");
        mockMvc.perform(get("/api/pre-defense/100").header("Authorization", aliceToken))
                .andExpect(status().isOk());

        String bobToken = login("bob");
        mockMvc.perform(get("/api/pre-defense/100").header("Authorization", bobToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    private String login(String username) throws Exception {
        String codeResponse = mockMvc.perform(get("/auth/code"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String uuid = objectMapper.readTree(codeResponse).path("uuid").asText();
        String image = objectMapper.readTree(codeResponse).path("img").asText();
        String decoded = new String(java.util.Base64.getDecoder().decode(image.split(",")[1]));
        java.util.regex.Matcher captcha = java.util.regex.Pattern
                .compile("<text[^>]*>(\\d{4})</text>").matcher(decoded);
        org.junit.jupiter.api.Assertions.assertTrue(captcha.find(), "captcha svg must contain a 4 digit code");
        String code = captcha.group(1);

        String loginResponse = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "uuid", uuid, "code", code, "username", username, "password", LOGIN_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(loginResponse).path("token").asText();
    }

    private MockMvc mockMvc(boolean adminCrossUserRead) {
        TaskAccessGuard guard = new TaskAccessGuard(taskMapper, currentUserService, adminCrossUserRead);
        TaskProgressQueryService queryService = new TaskProgressQueryService(
                taskMapper, resourceQueryService, currentUserService, guard);
        TaskExecutionQuotaPolicy quotaPolicy = mock(TaskExecutionQuotaPolicy.class);
        when(quotaPolicy.status()).thenReturn(Map.of("maxRunningTasks", 4));
        PreDefenseTaskCommandService commandService = new PreDefenseTaskCommandService(
                taskMapper,
                resourceQueryService,
                mock(DefenseTaskExecutor.class),
                mock(LddConfigurationPolicy.class),
                quotaPolicy,
                mock(com.securitymodel.application.task.GpuLeaseService.class),
                mock(ExecutionFingerprintService.class),
                mock(AfterCommitTaskDispatcher.class),
                objectMapper,
                mock(OperationAuditService.class),
                currentUserService,
                guard);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenService, userMapper);
        return MockMvcBuilders.standaloneSetup(
                        new PreDefenseTaskController(commandService),
                        new TaskProgressController(queryService, executor, quotaPolicy),
                        new AuthController(userMapper, tokenService))
                .addFilters(filter)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private String bearer(long userId) {
        return "Bearer " + tokenService.issue(userId, username(userId), false);
    }

    private String username(long userId) {
        return switch ((int) userId) {
            case 1 -> "admin";
            case 2 -> "alice";
            default -> "bob";
        };
    }

    private PreDefenseTask storedTask(long taskId) {
        return switch ((int) taskId) {
            case 100 -> task(100L, ALICE, "user", "success");
            case 200 -> task(200L, ALICE, "demo", "success");
            default -> task(300L, BOB, "user", "pending");
        };
    }

    private PreDefenseTask task(Long taskId, Long ownerUserId, String origin, String status) {
        PreDefenseTask task = new PreDefenseTask();
        task.setTaskId(taskId);
        task.setTaskName("task-" + taskId);
        task.setStatus(status);
        task.setProgress(100);
        task.setDatasetId(1L);
        task.setAlgorithmId(1L);
        task.setOwnerUserId(ownerUserId);
        task.setDataOrigin(origin);
        return task;
    }

    /** Account state is read from the database, not from the token claims. */
    static SysUser user(long userId, String username, boolean admin, boolean enabled) {
        SysUser user = new SysUser();
        user.setUserId(userId);
        user.setUsername(username);
        user.setAdmin(admin ? 1 : 0);
        user.setEnabled(enabled);
        return user;
    }

    private SysUser loginUser(long userId, String username) {
        SysUser user = user(userId, username, false, true);
        user.setPassword(ENCODED_PASSWORD);
        user.setNickName(username);
        return user;
    }
}
