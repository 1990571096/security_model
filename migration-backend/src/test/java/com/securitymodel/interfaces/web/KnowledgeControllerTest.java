package com.securitymodel.interfaces.web;

import com.securitymodel.application.audit.AuthenticatedUser;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.application.knowledge.KnowledgeIndexService;
import com.securitymodel.application.knowledge.KnowledgeQueryService;
import com.securitymodel.infrastructure.persistence.mapper.KnowledgeDocumentMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R6-04: admin indexing is enforced on the server, independent of the page. */
class KnowledgeControllerTest {

    private final KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        KnowledgeController controller = new KnowledgeController(
                mock(KnowledgeQueryService.class), indexService,
                mock(KnowledgeDocumentMapper.class), new CurrentUserService());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void nonAdminCannotReindex() throws Exception {
        authenticate(2L, "alice", false);

        mockMvc.perform(post("/api/knowledge/reindex"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
    }

    @Test
    void adminCanReindex() throws Exception {
        authenticate(1L, "admin", true);

        mockMvc.perform(post("/api/knowledge/algorithms/reindex"))
                .andExpect(status().isOk());
        verify(indexService).reindexAlgorithms();
    }

    @Test
    void legacyIdentityWithoutUserIdIsRejected() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthenticatedUser(null, "legacy", false), null, List.of()));

        mockMvc.perform(post("/api/knowledge/reindex"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    private void authenticate(Long userId, String username, boolean admin) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthenticatedUser(userId, username, admin), null, List.of()));
    }
}
