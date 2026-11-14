package com.securitymodel.application.assistant;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit allow-list. There is no dynamic class loading, no shell tool and no database
 * tool: a prompt-injected document can only name one of these entries, and write entries
 * still require a separate human confirmation.
 */
@Component
public class AssistantToolRegistry {

    private final Map<String, AssistantTool> tools = new LinkedHashMap<>();

    public AssistantToolRegistry(List<AssistantTool> tools) {
        for (AssistantTool tool : tools) {
            String name = tool.descriptor().name();
            if (this.tools.putIfAbsent(name, tool) != null) {
                throw new IllegalStateException("重复的助手工具名称: " + name);
            }
        }
    }

    public AssistantTool find(String name) {
        AssistantTool tool = tools.get(name);
        if (tool == null) {
            throw new AssistantException("ASSISTANT_TOOL_NOT_ALLOWED",
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "工具不在服务端允许列表内");
        }
        return tool;
    }

    public List<AssistantToolDescriptor> descriptors() {
        return tools.values().stream().map(AssistantTool::descriptor).toList();
    }

    public int size() {
        return tools.size();
    }
}
