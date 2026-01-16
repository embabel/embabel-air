# Embabel Air

AI-powered airline assistant with chat interface.

## Prerequisites

- Java 21
- Docker (for PostgreSQL)
- Maven

## Database Setup

The application uses PostgreSQL with pgvector for persistence.

### Start PostgreSQL

```bash
docker compose up -d
```

This starts a PostgreSQL 17 container with pgvector extension on port 5432.

### Database Configuration

Default connection settings in `application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/embabel_air
    username: embabel
    password: embabel
```

## Database Migrations

Flyway manages database schema migrations automatically on startup.

Migrations are in `src/main/resources/db/migration/` with naming convention `V{version}__{description}.sql`.

### Generate DDL

To regenerate DDL from JPA entities:

```bash
mvn test -Dtest=GenerateDdlTest
```

This outputs to `src/main/resources/db/schema.sql`. Copy relevant changes to a new migration file.

## Running the Application

```bash
mvn spring-boot:run
```

The application starts on http://localhost:8747

## Running Tests

```bash
mvn test
```

Integration tests use Testcontainers to spin up a PostgreSQL instance automatically.
