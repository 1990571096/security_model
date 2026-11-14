# Local Runtime Notes

Date: 2026-07-11

## Redis

The Spring Boot 3 migration backend uses Redis as an optional cache for resource catalog
reads. MySQL remains the source of truth. Cache read, write, serialization, or eviction
failures fall back to MySQL and do not fail resource operations. The cache uses a five-minute
TTL and resource mutations evict affected entries after commit. Each key also has a
generation counter; a reader that missed before an update may only refill the cache when the
generation it observed is still current. Eviction increments the generation first, so a slow
reader cannot put a pre-update value back after commit. Cache hit/miss/degraded and
stale-refill counts are exposed as bounded Micrometer counters (no task or user tags).

Default connection settings are `localhost:6379`. Override them with `REDIS_HOST`,
`REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_CONNECT_TIMEOUT`, and `REDIS_COMMAND_TIMEOUT`.
Redis is not required for the MySQL integration-test profile. If Redis is down, all reads
fall back to MySQL and the `security_model_cache_requests{result=degraded}` counter grows.

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

Redis 缓存代际的真实集成测试默认跳过；本机 Redis 可用时用 `REDIS_IT=true` 运行
`ResourceCatalogCacheRedisIT`，验证“读取中被 evict 的旧值不回填”。CI 不启动 Redis 时该测试跳过，不影响其他测试。

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
- `SECURITY_MODEL_OBSERVABILITY_STALE_PROGRESS_SECONDS`
- `SECURITY_MODEL_OBSERVABILITY_REFRESH_INTERVAL_MS`
- `SECURITY_MODEL_OBSERVABILITY_REFRESH_INITIAL_DELAY_MS`

Admin-only observability:
- `GET /actuator/health` anonymous, details hidden.
- `GET /actuator/metrics` and `GET /api/observability/summary` require an admin JWT.
- Aggregate metrics never use `taskId`, `runId` or `userId` as tags.
- The statistics page now reads `GET /api/task-progress/statistics`; it no longer displays
  hardcoded ASR/retention figures. CPU/GPU utilization and algorithm ASR remain uncollected
  and are shown as unavailable, not estimated.

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
