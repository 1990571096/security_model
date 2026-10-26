package com.securitymodel.application.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
public class LddConfigurationPolicy {

    private static final Set<String> SUPPORTED_KEYS = new LinkedHashSet<>(Arrays.asList(
            "dataset", "trigger_type", "trigger_label", "poisoned_mode",
            "poisoned_portion", "batch_size", "num_epochs", "lr_poison",
            "r", "alpha1", "alpha2", "seed", "data_path"
    ));

    private final ObjectMapper objectMapper;

    public LddConfigurationPolicy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String normalizeObject(String raw, String fieldName) {
        return parseObject(raw, fieldName).toString();
    }

    public void mergeAndValidate(String algorithmDefaults, String hyperConfig, String customParams) {
        ObjectNode merged = parseObject(algorithmDefaults, "算法默认配置");
        merged.setAll(parseObject(hyperConfig, "超参数模板配置"));
        ObjectNode custom = parseObject(customParams, "自定义参数");
        if (custom.has("epochs") && !custom.has("num_epochs")) {
            custom.set("num_epochs", custom.get("epochs"));
            custom.remove("epochs");
        }
        merged.setAll(custom);

        for (String key : merged.properties().stream().map(entry -> entry.getKey()).toList()) {
            if (!SUPPORTED_KEYS.contains(key)) {
                throw new TaskCommandException("LDD 不支持参数: " + key);
            }
        }

        requireText(merged, "dataset");
        requireText(merged, "trigger_type");
        requireNumber(merged, "trigger_label", 0, 99, true);
        requireOneOf(merged, "poisoned_mode", "all2one", "all2all");
        requireNumber(merged, "poisoned_portion", 0.01, 0.6, false);
        requireNumber(merged, "batch_size", 16, 512, true);
        requireNumber(merged, "num_epochs", 1, 200, true);
        requireNumber(merged, "lr_poison", 0.00001, 1.0, false);
        requireNumber(merged, "r", 0.0, 1.0, false);
        requireNumber(merged, "alpha1", 0.0, 10.0, false);
        requireNumber(merged, "alpha2", 0.0, 10.0, false);
        requireNumber(merged, "seed", 0, 999999, true);
        if (merged.has("data_path")) {
            requireText(merged, "data_path");
        }
    }

    private ObjectNode parseObject(String raw, String fieldName) {
        if (!StringUtils.hasText(raw)) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node == null || !node.isObject()) {
                throw new TaskCommandException(fieldName + "必须是 JSON 对象");
            }
            return (ObjectNode) node;
        } catch (TaskCommandException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new TaskCommandException(fieldName + "格式不正确，必须是 JSON 对象");
        }
    }

    private void requireText(ObjectNode config, String key) {
        if (!config.hasNonNull(key) || !StringUtils.hasText(config.get(key).asText())) {
            throw new TaskCommandException("LDD 参数 " + key + " 不能为空");
        }
    }

    private void requireOneOf(ObjectNode config, String key, String... allowed) {
        requireText(config, key);
        if (!Arrays.asList(allowed).contains(config.get(key).asText())) {
            throw new TaskCommandException("LDD 参数 " + key + " 取值不合法");
        }
    }

    private void requireNumber(ObjectNode config, String key, double min, double max, boolean integer) {
        JsonNode value = config.get(key);
        if (value == null || !value.isNumber()) {
            throw new TaskCommandException("LDD 参数 " + key + " 必须是范围为 [" + min + ", " + max + "] 的"
                    + (integer ? "整数" : "数字"));
        }
        double number = value.doubleValue();
        if (Double.isNaN(number) || number < min || number > max
                || (integer && number != Math.rint(number))) {
            throw new TaskCommandException("LDD 参数 " + key + " 必须是范围为 [" + min + ", " + max + "] 的"
                    + (integer ? "整数" : "数字"));
        }
    }
}
