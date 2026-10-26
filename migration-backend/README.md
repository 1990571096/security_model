# Security Model Backend

This module is the Spring Boot 3 backend for the security model training
platform. It is intentionally independent from the legacy `eladmin` Maven
reactor.

## Current scope

- Java 17 baseline.
- Spring Boot 3.5.
- Spring Security 6 stateless configuration.
- Actuator health endpoint.
- Native JWT authentication, captcha login and current-user lookup.
- Native dataset, algorithm, hyper-parameter, LDD task, report, log and export APIs.
- MySQL and MyBatis-Plus persistence. Redis and message brokers are not part
  of the current runtime.
- Python process execution boundary for the external VaB LDD runtime.

## Run

Set `SECURITY_MODEL_JWT_SECRET` to a secret of at least 32 bytes before starting the
backend. The application intentionally has no committed signing-key default and fails
fast when the variable is missing or too short. Configure the database with `DB_HOST`,
`DB_PORT`, `DB_NAME`, `DB_USER`, and `DB_PWD` as needed.

```powershell
mvn spring-boot:run
```

- `GET http://localhost:8100/actuator/health`
- `GET http://localhost:8100/api/v1/system/version`

The old Spring Boot 2 backend remains available on port 8000 as a rollback
baseline only.
