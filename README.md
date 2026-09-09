# Spring Shop (Production Publish)

Java + Spring Boot shop site with production deployment support.

## Stack
- Java 17
- Spring Boot 3
- Thymeleaf
- PostgreSQL
- Docker + Docker Compose
- Caddy reverse proxy (automatic HTTPS)

## Security
- Spring Security admin authentication
- CSRF protection for all POST forms
- Admin credentials from environment variables
- Secure session cookie settings in `prod` profile
- Health endpoint: `/actuator/health`

## Database
This app no longer uses SQLite for production.

Schema is initialized/updated by Spring SQL scripts:
- `/src/main/resources/schema-postgresql.sql`

## Production Publish

### 1) Prepare server
- Use Ubuntu VPS.
- Point your domain DNS `A` record to server IP.
- Install Docker + Docker Compose.

### 2) Copy project
Copy `/Users/thurahtun/Desktop/myshop/spring-shop` to server.

### 3) Configure environment
```bash
cd spring-shop
cp .env.example .env
```

Edit `.env`:
- `DOMAIN=your-real-domain.com`
- `ADMIN_USERNAME=admin` (or custom)
- `ADMIN_PASSWORD=<strong password>`
- `DB_NAME`, `DB_USER`, `DB_PASSWORD`

You can also set a BCrypt hash directly:
- `ADMIN_PASSWORD={bcrypt}<bcrypt_hash_here>`

### 4) Deploy
```bash
./deploy.sh
```

Equivalent manual command:
```bash
docker compose -f docker-compose.prod.yml --env-file .env up -d --build
```

### 5) Verify
- `https://your-real-domain.com`
- `https://your-real-domain.com/login`
- `https://your-real-domain.com/actuator/health`

## Quick Publish Checklist (Now)
1. Buy domain.
2. Set DNS `A` record to your VPS public IP.
3. Open firewall ports: `80`, `443`, and `22` (SSH).
4. On server:
   ```bash
   cd spring-shop
   cp .env.example .env
   nano .env
   ```
5. Fill `.env` required values:
   - `DOMAIN`
   - `ADMIN_USERNAME`
   - `ADMIN_PASSWORD`
   - `DB_NAME`
   - `DB_USER`
   - `DB_PASSWORD`
6. Start:
   ```bash
   ./deploy.sh
   ```
7. Check:
   ```bash
   docker compose -f docker-compose.prod.yml --env-file .env ps
   docker compose -f docker-compose.prod.yml --env-file .env logs -f app
   ```

`deploy.sh` validates missing/unsafe placeholder values before deploy.

## Update Deployment
```bash
./deploy.sh
```

## Persistent Data
- PostgreSQL data: `./data/postgres`
- Uploaded images: `./uploads`

Keep these folders when deploying updates so menu/image data stays.

## Important
- Never deploy with default admin password.
- Keep `.env` private and do not commit it.
- Avoid `docker compose down -v` in production unless you intentionally want to erase data.
# maemaya
