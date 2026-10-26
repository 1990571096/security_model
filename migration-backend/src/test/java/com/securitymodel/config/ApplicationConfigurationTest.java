package com.securitymodel.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ApplicationConfigurationTest {

    @Test
    void applicationYamlProfilesAreValidYaml() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

        assertDoesNotThrow(() -> loader.load("application", new ClassPathResource("application.yml")));
        assertDoesNotThrow(() -> loader.load("application-local", new ClassPathResource("application-local.yml")));
    }
}
