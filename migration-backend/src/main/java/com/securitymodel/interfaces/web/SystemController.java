package com.securitymodel.interfaces.web;

import java.time.OffsetDateTime;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private final String version;

    public SystemController(@Value("${security-model.app.version:0.1.0-migration}") String version) {
        this.version = version;
    }

    @GetMapping("/version")
    public Map<String, Object> version() {
        return Map.of(
                "application", "security-model",
                "version", version,
                "framework", "Spring Boot 3",
                "java", Runtime.version().feature(),
                "migrationStage", "foundation",
                "serverTime", OffsetDateTime.now().toString()
        );
    }
}
