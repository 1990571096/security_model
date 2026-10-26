package com.securitymodel;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@MapperScan("com.securitymodel.infrastructure.persistence.mapper")
public class SecurityModelApplication {

    public static void main(String[] args) {
        SpringApplication.run(SecurityModelApplication.class, args);
    }
}
