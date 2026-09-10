package me.zhengjie.utils;

import org.junit.jupiter.api.Test;

import static me.zhengjie.utils.EncryptUtils.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class EncryptUtilsTest {

    /**
     * 对称加密
     */
    @Test
    public void testDesEncrypt() {
        try {
            String source = "des-test-value";
            assertEquals(source, desDecrypt(desEncrypt(source)));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 对称解密
     */
    @Test
    public void testDesDecrypt() {
        try {
            String source = "des-test-value";
            assertEquals(source, desDecrypt(desEncrypt(source)));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
