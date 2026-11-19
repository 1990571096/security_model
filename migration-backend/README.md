# Security Model Backend

This module is the Spring Boot 3 backend for the security model training
platform. It is independent from the archived legacy `eladmin` Maven reactor.

Current delivery facts: `../docs/sdd/26-current-delivery-facts.md`.

## Current scope

- Java 17 baseline.
- Spring Boot 3.5.
- Spring Security 6 stateless configuration.
- Actuator liveness/readiness health endpoints.
- Native JWT authentication, captcha login and current-user lookup.
- Native dataset, algorithm, hyper-parameter, LDD task, report, log and export APIs.
- MySQL + MyBatis-Plus persistence; Redis resource cache and optional knowledge vectors.
- Optional `db` / `rabbit` dispatch modes. `db` is the default; `rabbit` uses
  Transactional Outbox, RabbitMQ and an independent Worker.
- Python process execution boundary for the external VaB LDD runtime.
- Controlled Assistant/RAG with a read-only tool allow-list and one-time write
  confirmation. External models are disabled by default.

## Run

Set `SECURITY_MODEL_JWT_SECRET` to a secret of at least 32 bytes before starting the
backend. The application intentionally has no committed signing-key default and fails
fast when the variable is missing or too short. `DB_NAME` defaults to `security_model`;
never point local runs at the production `eladmin` schema. Configure the database with
`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, and `DB_PWD` as needed.

```powershell
mvn spring-boot:run
```

- `GET http://localhost:8100/actuator/health/liveness`
- `GET http://localhost:8100/actuator/health/readiness`
- `GET http://localhost:8100/api/v1/system/version`

The old Spring Boot 2 backend is archived under Tag `migration-r4-verified` and the
`legacy` branch; it is not a running rollback service in this workspace.
