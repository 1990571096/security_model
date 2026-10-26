# Local Runtime Notes

Date: 2026-07-11

## Redis

The Spring Boot 3 migration backend uses Redis as an optional cache for resource catalog
reads. MySQL remains the source of truth. Cache read, write, serialization, or eviction
failures fall back to MySQL and do not fail resource operations. The cache uses a five-minute
TTL and resource mutations evict affected entries after commit.

Default connection settings are `localhost:6379`. Override them with `REDIS_HOST`,
`REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_CONNECT_TIMEOUT`, and `REDIS_COMMAND_TIMEOUT`.
Redis is not required for the MySQL integration-test profile.

The Redis Windows service may be present on the developer machine for both the legacy
ELADMIN project and the migration backend.

Redis has been registered as a Windows service named `Redis`.

Current service binary:

```text
F:\Study\CODE\Redis-8.4.0-Windows-x64-msys2-with-Service\RedisService.exe
```

Current config:

```text
F:\Study\CODE\Redis-8.4.0-Windows-x64-msys2-with-Service\redis.conf
```

Verification:

```powershell
Get-Service Redis
& 'F:\Study\CODE\Redis-8.4.0-Windows-x64-msys2-with-Service\redis-cli.exe' ping
```

Expected Redis response:

```text
PONG
```

## MySQL

The migration backend's `application.yml` supports environment variable overrides:

- `DB_HOST`
- `DB_PORT`
- `DB_NAME`
- `DB_USER`
- `DB_PWD`
- `SERVER_PORT`
- `REDIS_HOST`
- `REDIS_PORT`
- `REDIS_PASSWORD`
- `REDIS_CONNECT_TIMEOUT`
- `REDIS_COMMAND_TIMEOUT`
- `SECURITY_MODEL_JWT_SECRET`
- `SECURITY_MODEL_JWT_VALIDITY`

Defaults remain compatible with the old local config:

```text
DB_HOST=localhost
DB_PORT=3306
DB_NAME=eladmin
DB_USER=root
DB_PWD=<本机 MySQL 密码>
```

所有环境的 JWT 密钥都必须通过 `SECURITY_MODEL_JWT_SECRET` 提供且至少 32 字节；
未设置或长度不足时后端拒绝启动。生产环境应使用安全的密钥管理设施，不要提交真实密钥。

The current local MySQL service is reachable on `localhost:3306`. The MVP database is `eladmin`; the old `xm-qingge` database is not reused.

Initialized SQL:

```sql
SOURCE sql/eladmin.sql;
SOURCE sql/security_model_mvp_seed.sql;
```

Legacy ELADMIN endpoints:

- Backend: `http://localhost:8000`
- Swagger/Knife4j: `http://localhost:8000/doc.html`
- Frontend: `http://localhost:8013/`

Migration backend and frontend endpoints:

- Backend: `http://localhost:8100`
- Frontend: `http://localhost:8014/`
