# Local Runtime Notes

Date: 2026-07-11

## Redis

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

`application-dev.yml` now supports environment variable overrides:

- `DB_HOST`
- `DB_PORT`
- `DB_NAME`
- `DB_USER`
- `DB_PWD`
- `JWT_BASE64_SECRET`
- `RSA_PRIVATE_KEY`
- `SECURITY_MODEL_DEFAULT_USER_PASSWORD`
- `DRUID_LOGIN_PASSWORD` (仅生产环境 Druid 监控)

Defaults remain compatible with the old local config:

```text
DB_HOST=localhost
DB_PORT=3306
DB_NAME=eladmin
DB_USER=root
DB_PWD=<本机 MySQL 密码>
```

JWT 签名密钥、RSA 私钥和用户初始密码必须通过环境变量提供，不能写入配置文件或提交到仓库。

The current local MySQL service is reachable on `localhost:3306`. The MVP database is `eladmin`; the old `xm-qingge` database is not reused.

Initialized SQL:

```sql
SOURCE sql/eladmin.sql;
SOURCE sql/security_model_mvp_seed.sql;
```

Current verified services:

- Backend: `http://localhost:8000`
- Swagger/Knife4j: `http://localhost:8000/doc.html`
- Frontend: `http://localhost:8014/`
