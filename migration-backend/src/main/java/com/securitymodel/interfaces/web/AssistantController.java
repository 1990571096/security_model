package com.securitymodel.interfaces.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.securitymodel.application.assistant.AssistantAuditService;
import com.securitymodel.application.assistant.AssistantChatResponse;
import com.securitymodel.application.assistant.AssistantChatService;
import com.securitymodel.application.assistant.AssistantConfirmationResult;
import com.securitymodel.application.assistant.AssistantConfirmationService;
import com.securitymodel.application.assistant.AssistantPreviewResponse;
import com.securitymodel.application.assistant.AssistantPreviewService;
import com.securitymodel.application.assistant.AssistantQuotaService;
import com.securitymodel.application.assistant.AssistantSessionService;
import com.securitymodel.application.assistant.AssistantToolRegistry;
import com.securitymodel.application.audit.CurrentUserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** R7 controlled assistant API. Authorization is always repeated in the Java service layer. */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final AssistantChatService chatService;
    private final AssistantConfirmationService confirmationService;
    private final AssistantPreviewService previewService;
    private final AssistantSessionService sessionService;
    private final AssistantAuditService auditService;
    private final AssistantQuotaService quotaService;
    private final AssistantToolRegistry registry;
    private final CurrentUserService currentUserService;
    private final String provider;
    private final boolean modelEnabled;

    public AssistantController(
            AssistantChatService chatService,
            AssistantConfirmationService confirmationService,
            AssistantPreviewService previewService,
            AssistantSessionService sessionService,
            AssistantAuditService auditService,
            AssistantQuotaService quotaService,
            AssistantToolRegistry registry,
            CurrentUserService currentUserService,
            @Value("${security-model.assistant.model.provider:disabled}") String provider,
            @Value("${security-model.assistant.model.enabled:false}") boolean modelEnabled) {
        this.chatService = chatService;
        this.confirmationService = confirmationService;
        this.previewService = previewService;
        this.sessionService = sessionService;
        this.auditService = auditService;
        this.quotaService = quotaService;
        this.registry = registry;
        this.currentUserService = currentUserService;
        this.provider = provider;
        this.modelEnabled = modelEnabled;
    }

    @PostMapping("/chat")
    public AssistantChatResponse chat(@RequestBody @jakarta.validation.Valid AssistantChatRequest request) {
        return chatService.chat(request.sessionId(), request.question());
    }

    @PostMapping("/confirmations/confirm")
    public ResponseEntity<JsonNode> confirm(
            @RequestBody @jakarta.validation.Valid AssistantConfirmRequest request) {
        AssistantConfirmationResult result = confirmationService.confirm(
                request.confirmationId(), request.token());
        JsonNode body = result.body();
        if (body instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
            object.put("confirmationReplayed", result.replayed());
        }
        return ResponseEntity.status(result.status())
                .body(body);
    }

    /** Explicit preview endpoint used by tests and by a future model adapter; it never writes. */
    @PostMapping("/tools/{toolName}/preview")
    public AssistantPreviewResponse preview(@PathVariable String toolName,
                                            @RequestBody(required = false) JsonNode arguments) {
        return previewService.preview(toolName, arguments == null
                ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                : arguments);
    }

    @GetMapping("/tools")
    public List<Map<String, Object>> tools() {
        return registry.descriptors().stream().map(descriptor -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", descriptor.name());
            view.put("description", descriptor.description());
            view.put("write", descriptor.write());
            view.put("inputSchema", descriptor.inputSchema());
            return view;
        }).toList();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", provider);
        result.put("modelEnabled", modelEnabled);
        result.put("dailyRequestLimit", quotaService.dailyLimit());
        result.put("quotaRemaining", quotaService.remaining());
        result.put("toolCount", registry.size());
        return result;
    }

    @GetMapping("/sessions")
    public List<Map<String, Object>> sessions(
            @RequestParam(defaultValue = "20") int limit) {
        Long userId = currentUserService.requireStableIdentity().userId();
        return sessionService.listMine(userId, limit);
    }

    @GetMapping("/sessions/{sessionId}")
    public Map<String, Object> session(@PathVariable Long sessionId) {
        Long userId = currentUserService.requireStableIdentity().userId();
        return sessionService.getOwnedView(sessionId, userId);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable Long sessionId) {
        Long userId = currentUserService.requireStableIdentity().userId();
        sessionService.deleteOwned(sessionId, userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/audits")
    public List<?> audits(@RequestParam(defaultValue = "50") int limit) {
        return auditService.findMine(limit);
    }
}
