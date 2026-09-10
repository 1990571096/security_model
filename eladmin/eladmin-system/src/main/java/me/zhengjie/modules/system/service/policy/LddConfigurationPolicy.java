package me.zhengjie.modules.system.service.policy;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import me.zhengjie.exception.BadRequestException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Validates and merges the configuration accepted by the LDD adapter.
 *
 * The merge order is part of the execution contract:
 * algorithm defaults -> hyper-parameter template -> task overrides.
 */
@Component
public class LddConfigurationPolicy {

    private static final Set<String> SUPPORTED_KEYS = new LinkedHashSet<>(Arrays.asList(
            "dataset", "trigger_type", "trigger_label", "poisoned_mode",
            "poisoned_portion", "batch_size", "num_epochs", "lr_poison",
            "r", "alpha1", "alpha2", "seed", "data_path"
    ));

    /**
     * Merge and validate the effective LDD configuration.
     *
     * @param algorithmDefaults algorithm-level defaults
     * @param hyperParameters reusable template JSON, may be null
     * @param taskOverrides task-specific JSON, may be null
     * @return validated mutable JSON object used by the execution snapshot
     */
    public JSONObject mergeAndValidate(
            String algorithmDefaults,
            String hyperParameters,
            String taskOverrides) {
        JSONObject merged = parseObjectOrEmpty(algorithmDefaults, "算法默认配置");
        merged.putAll(parseObjectOrEmpty(hyperParameters, "超参数模板配置"));
        merged.putAll(parseObjectOrEmpty(taskOverrides, "自定义参数"));

        for (String key : merged.keySet()) {
            if (!SUPPORTED_KEYS.contains(key)) {
                throw new BadRequestException("LDD 不支持参数: " + key);
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
        if (merged.containsKey("data_path")) {
            requireText(merged, "data_path");
        }
        return merged;
    }

    /**
     * Normalize an optional JSON object before persisting it in a JSON column.
     */
    public String normalizeObject(String raw, String fieldName) {
        return parseObjectOrEmpty(raw, fieldName).toJSONString();
    }

    private JSONObject parseObjectOrEmpty(String raw, String fieldName) {
        if (!StringUtils.hasText(raw)) {
            return new JSONObject();
        }
        try {
            JSONObject object = JSON.parseObject(raw);
            if (object == null) {
                throw new BadRequestException(fieldName + "必须是 JSON 对象");
            }
            return object;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException(fieldName + "格式不正确，必须是 JSON 对象");
        }
    }

    private void requireText(JSONObject config, String key) {
        if (!StringUtils.hasText(config.getString(key))) {
            throw new BadRequestException("LDD 参数 " + key + " 不能为空");
        }
    }

    private void requireOneOf(JSONObject config, String key, String... allowed) {
        String value = config.getString(key);
        if (!StringUtils.hasText(value) || !Arrays.asList(allowed).contains(value)) {
            throw new BadRequestException("LDD 参数 " + key + " 取值不合法");
        }
    }

    private void requireNumber(JSONObject config, String key, double min, double max, boolean integer) {
        Object rawValue = config.get(key);
        Number number = rawValue instanceof Number ? (Number) rawValue : null;
        if (number == null || Double.isNaN(number.doubleValue()) || number.doubleValue() < min
                || number.doubleValue() > max
                || (integer && number.doubleValue() != Math.rint(number.doubleValue()))) {
            throw new BadRequestException("LDD 参数 " + key + " 必须是范围为 ["
                    + min + ", " + max + "] 的" + (integer ? "整数" : "数字"));
        }
    }
}
