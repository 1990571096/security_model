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

    // Keep these sets in sync with sql/security_model_vab_seed.sql (config_schema).
    static final Set<String> SUPPORTED_DATASETS = new LinkedHashSet<>(Arrays.asList(
            "cifar10", "cifar100", "gtsrb"
    ));

    static final Set<String> SUPPORTED_TRIGGER_TYPES = new LinkedHashSet<>(Arrays.asList(
            "SIG", "WaNet", "badnet", "blended", "CL"
    ));

    private final ObjectMapper objectMapper;

    public LddConfigurationPolicy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String normalizeObject(String raw, String fieldName) {
        return parseObject(raw, fieldName).toString();
    }

    public void mergeAndValidate(String algorithmDefaults, String hyperConfig, String customParams) {
        normalizeAndValidate(algorithmDefaults, hyperConfig, customParams);
    }

    /** Returns the exact normalized configuration that must be persisted and dispatched. */
    public ObjectNode normalizeAndValidate(String algorithmDefaults, String hyperConfig, String customParams) {
        return normalizeAndValidate(algorithmDefaults, hyperConfig, customParams, null);
    }

    public ObjectNode normalizeAndValidate(String algorithmDefaults, String hyperConfig, String customParams,
                                           String selectedDataset) {
        ObjectNode merged = parseObject(algorithmDefaults, "算法默认配置");
        merged.setAll(parseObject(hyperConfig, "超参数模板配置"));
        if (selectedDataset != null) {
            merged.put("dataset", selectedDataset);
        }
        ObjectNode custom = parseObject(customParams, "自定义参数");
        if (custom.has("epochs") && !custom.has("num_epochs")) {
            custom.set("num_epochs", custom.get("epochs"));
            custom.remove("epochs");
        }
        merged.setAll(custom);

        for (String key : merged.properties().stream().map(entry -> entry.getKey()).toList()) {
            if (!SUPPORTED_KEYS.contains(key)) {
                throw new TaskCommandException("PARAMETER_UNKNOWN", "LDD 不支持参数: " + key);
            }
        }

        requireOneOf(merged, "dataset", SUPPORTED_DATASETS);
        if (selectedDataset != null && !selectedDataset.equals(merged.path("dataset").asText())) {
            throw new TaskCommandException("DATASET_PARAMETER_MISMATCH",
                    "LDD 参数 dataset 必须与所选数据集一致：" + selectedDataset);
        }
        requireOneOf(merged, "trigger_type", SUPPORTED_TRIGGER_TYPES);
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
        return merged;
    }

    private ObjectNode parseObject(String raw, String fieldName) {
        if (!StringUtils.hasText(raw)) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node == null || !node.isObject()) {
                throw new TaskCommandException("PARAMETERS_MALFORMED", fieldName + "必须是 JSON 对象");
            }
            return (ObjectNode) node;
        } catch (TaskCommandException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new TaskCommandException("PARAMETERS_MALFORMED", fieldName + "格式不正确，必须是 JSON 对象");
        }
    }

    private void requireText(ObjectNode config, String key) {
        requireTextValue(config, key);
    }

    private String requireTextValue(ObjectNode config, String key) {
        JsonNode value = config.get(key);
        if (value == null || value.isNull() || (value.isTextual() && !StringUtils.hasText(value.asText()))) {
            throw new TaskCommandException("PARAMETER_REQUIRED", "LDD 参数 " + key + " 不能为空");
        }
        if (!value.isTextual()) {
            throw new TaskCommandException("PARAMETER_TYPE_INVALID",
                    "LDD 参数 " + key + " 必须是字符串");
        }
        return value.asText();
    }

    private void requireOneOf(ObjectNode config, String key, Set<String> allowed) {
        String value = requireTextValue(config, key);
        if (!allowed.contains(value)) {
            throw new TaskCommandException("PARAMETER_VALUE_INVALID",
                    "LDD 参数 " + key + " 取值不合法，支持: " + String.join(", ", allowed));
        }
    }

    private void requireOneOf(ObjectNode config, String key, String... allowed) {
        requireOneOf(config, key, new LinkedHashSet<>(Arrays.asList(allowed)));
    }

    private void requireNumber(ObjectNode config, String key, double min, double max, boolean integer) {
        JsonNode value = config.get(key);
        if (value == null || !value.isNumber()) {
            throw new TaskCommandException("PARAMETER_TYPE_INVALID",
                    "LDD 参数 " + key + " 必须是范围为 [" + min + ", " + max + "] 的"
                            + (integer ? "整数" : "数字"));
        }
        double number = value.doubleValue();
        if (Double.isNaN(number) || number < min || number > max
                || (integer && number != Math.rint(number))) {
            throw new TaskCommandException("PARAMETER_OUT_OF_RANGE",
                    "LDD 参数 " + key + " 必须是范围为 [" + min + ", " + max + "] 的"
                            + (integer ? "整数" : "数字"));
        }
    }
}
