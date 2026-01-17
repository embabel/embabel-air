![Java](https://img.shields.io/badge/java-%23ED8B00.svg?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring](https://img.shields.io/badge/spring-%236DB33F.svg?style=for-the-badge&logo=spring&logoColor=white)
![Vaadin](https://img.shields.io/badge/Vaadin-00B4F0?style=for-the-badge&logo=Vaadin&logoColor=white)
![Postgres](https://img.shields.io/badge/postgres-%23316192.svg?style=for-the-badge&logo=postgresql&logoColor=white)

<img align="left" src="https://github.com/embabel/embabel-agent/blob/main/embabel-agent-api/images/315px-Meister_der_Weltenchronik_001.jpg?raw=true" width="180">

&nbsp;&nbsp;&nbsp;&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;

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

### Reset Database

To completely reset the database (wipe all data and re-run migrations):

```bash
docker compose down -v && docker compose up -d
```

The `-v` flag removes the Docker volume, deleting all data. On next startup, Flyway will re-run all migrations and the
dev data seeder will recreate demo users and flights.

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
