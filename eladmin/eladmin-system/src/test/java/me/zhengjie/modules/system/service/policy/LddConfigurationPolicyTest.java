package me.zhengjie.modules.system.service.policy;

import com.alibaba.fastjson2.JSONObject;
import me.zhengjie.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LddConfigurationPolicyTest {

    private final LddConfigurationPolicy policy = new LddConfigurationPolicy();

    @Test
    void mergesConfigurationInDocumentedOrder() {
        JSONObject result = policy.mergeAndValidate(
                "{"
                        + "\"dataset\":\"cifar10\","
                        + "\"trigger_type\":\"SIG\","
                        + "\"trigger_label\":0,"
                        + "\"poisoned_mode\":\"all2one\","
                        + "\"poisoned_portion\":0.1,"
                        + "\"batch_size\":64,"
                        + "\"num_epochs\":5,"
                        + "\"lr_poison\":0.1,"
                        + "\"r\":0.1,"
                        + "\"alpha1\":1,"
                        + "\"alpha2\":1,"
                        + "\"seed\":1"
                        + "}",
                "{\"batch_size\":128}",
                "{\"num_epochs\":5}");

        assertEquals("cifar10", result.getString("dataset"));
        assertEquals(128, result.getIntValue("batch_size"));
        assertEquals(5, result.getIntValue("num_epochs"));
    }

    @Test
    void rejectsUnknownAndInvalidParametersBeforeExecution() {
        String valid = "{"
                + "\"dataset\":\"cifar10\","
                + "\"trigger_type\":\"SIG\","
                + "\"trigger_label\":0,"
                + "\"poisoned_mode\":\"all2one\","
                + "\"poisoned_portion\":0.1,"
                + "\"batch_size\":128,"
                + "\"num_epochs\":5,"
                + "\"lr_poison\":0.1,"
                + "\"r\":0.1,"
                + "\"alpha1\":1,"
                + "\"alpha2\":1,"
                + "\"seed\":1"
                + "}";

        assertThrows(BadRequestException.class, () ->
                policy.mergeAndValidate(valid, null, "{\"unexpected\":true}"));
        assertThrows(BadRequestException.class, () ->
                policy.mergeAndValidate(valid, null, "{\"batch_size\":12}"));
        assertThrows(BadRequestException.class, () ->
                policy.mergeAndValidate(valid, null, "{\"poisoned_mode\":\"invalid\"}"));
    }

    @Test
    void normalizesEmptyJsonToAnObject() {
        assertEquals("{}", policy.normalizeObject("", "自定义参数"));
        assertEquals("{\"batch_size\":128}",
                policy.normalizeObject("{\"batch_size\":128}", "自定义参数"));
        assertThrows(BadRequestException.class,
                () -> policy.normalizeObject("[]", "自定义参数"));
    }
}
