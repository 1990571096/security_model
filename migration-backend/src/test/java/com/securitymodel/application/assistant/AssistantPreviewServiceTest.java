package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.application.audit.AuthenticatedUser;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.domain.assistant.AssistantConfirmation;
import com.securitymodel.infrastructure.persistence.mapper.AssistantConfirmationMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The preview endpoint must not persist a one-time confirmation after its budget has
 * expired. The tool itself may still be finishing a blocking call, so the service checks
 * the deadline again before inserting the confirmation row.
 */
class AssistantPreviewServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void expiredPreviewBudgetDoesNotPersistConfirmation() throws Exception {
        AssistantConfirmationMapper mapper = mock(AssistantConfirmationMapper.class);
        when(mapper.countActive(any(), any())).thenReturn(0L);
        CurrentUserService user = mock(CurrentUserService.class);
        when(user.requireStableIdentity()).thenReturn(new AuthenticatedUser(2L, "alice", false));
        AssistantAuditService audit = mock(AssistantAuditService.class);

        AssistantTool slow = new AssistantTool() {
            @Override
            public AssistantToolDescriptor descriptor() {
                return new AssistantToolDescriptor("preview_slow", "slow preview", true,
                        AssistantSchemas.object());
            }

            @Override
            public AssistantToolResult execute(JsonNode arguments) {
                try {
                    Thread.sleep(30);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return new AssistantToolResult("slow", objectMapper.createObjectNode()
                        .put("operation", "cancel"), "cancel",
                        objectMapper.createObjectNode().put("taskId", 1), 1L, null, "ctx");
            }
        };
        AssistantToolRegistry registry = mock(AssistantToolRegistry.class);
        when(registry.find("preview_slow")).thenReturn(slow);

        AssistantPreviewService service = new AssistantPreviewService(
                registry, mapper, audit, user, 300, 10);

        AssistantException exception = assertThrows(AssistantException.class,
                () -> service.preview("preview_slow", objectMapper.createObjectNode(),
                        null, 5));

        assertEquals("ASSISTANT_TOOL_TIMEOUT", exception.getCode());
        verify(mapper, never()).insert(any(AssistantConfirmation.class));
    }
}
