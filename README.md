# tech_challenger_3_grupo65
Backend API desenvolvida para o Tech Challenger FIAP - Fase 3 - Grupo 65.

## Arquitetura Docker

Cada serviço é autocontido: tem seu próprio `docker-compose.yml`, seu próprio banco
PostgreSQL e seu próprio `.env`. O `docker-compose.yml` da raiz apenas agrega os
serviços via `include`, e o RabbitMQ é compartilhado, definido uma única vez em
`infra/`.

```
docker compose up            # na raiz: sobe todos os serviços
docker compose up            # dentro de um serviço: sobe só ele + o broker
COMPOSE_PROFILES= docker compose up -d   # só a infra, para rodar a app pela IDE
```

O design completo está em
[`docs/superpowers/specs/2026-09-11-docker-compose-orquestracao-design.md`](docs/superpowers/specs/2026-09-11-docker-compose-orquestracao-design.md).

> **Nota:** a estrutura descrita abaixo está em implementação. A receita já vale como
> convenção acordada pelo grupo.

### Portas

| Serviço | App | Postgres | Banco |
|---|---|---|---|
| history-service | 8080 | 5432 | `history_db` |
| appointment-service | 8081 | 5433 | `appointment_db` |

RabbitMQ: 5672 (AMQP) e 15672 (Management).

Os arquivos `.env` não são versionados. Rode `make setup` para gerá-los a partir dos
`.env.example` — ele não sobrescreve os que já existem.

### Adicionando um novo serviço

Exemplo com um `notification-service`. São quatro arquivos novos e **uma** edição fora
da pasta do serviço.

#### 1. `notification-service/docker-compose.yml`

```yaml
name: grupo65                       # convenção: mesmo projeto para todos

include:
  - path: ../infra/docker-compose.yml
    env_file: ../infra/.env

services:
  notification-postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: ${POSTGRES_DB}
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    ports: ["${DB_PORT}:5432"]
    volumes: [notification-postgres-data:/var/lib/postgresql/data]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}"]
      interval: 5s
      retries: 10

  notification-app:
    build: .
    profiles: [apps]
    environment:
      DB_HOST: notification-postgres     # sobrescreve o localhost do .env
      DB_PORT: 5432                      # porta interna, não a publicada
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_PORT: 5672
    ports: ["${SERVER_PORT}:${SERVER_PORT}"]
    networks: [default, shared]          # `shared` dá acesso ao broker
    depends_on:
      notification-postgres: { condition: service_healthy }
      rabbitmq:              { condition: service_healthy }
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:${SERVER_PORT}/actuator/health"]
      start_period: 40s

volumes:
  notification-postgres-data:
```

#### 2. `notification-service/.env.example`

Usa a próxima faixa de portas livre (8080/5432 e 8081/5433 já estão tomadas):

```
COMPOSE_PROFILES=apps
POSTGRES_DB=notification_db
POSTGRES_USER=postgres
POSTGRES_PASSWORD=postgres
DB_HOST=localhost
DB_PORT=5434
SERVER_PORT=8082
```

#### 3. `Dockerfile` e `.dockerignore`

Copiados de qualquer serviço existente, sem alteração — o `mvnw` e o `pom.xml` vêm do
contexto de build.

#### 4. Uma entrada no `docker-compose.yml` da raiz

```yaml
  - path: ./notification-service/docker-compose.yml
    env_file: ./notification-service/.env
```

Depois, `make setup && make up`.

#### O que não se toca

`infra/`, o compose dos outros serviços, o `Makefile`, nenhum `.env` alheio. O único
acoplamento central é a entrada no `include` — não há como eliminá-la, pois o Compose
não aceita glob em `include`.

#### Armadilhas

- **`name: grupo65` no topo é obrigatório.** Sem ele o serviço vira um projeto Compose
  próprio e sobe um RabbitMQ paralelo em vez de reusar o compartilhado.
- **`networks: [default, shared]` na aplicação.** Omitir `shared` e ela não enxerga o
  broker; incluir `shared` no Postgres quebra o isolamento entre bancos.
- **Exchanges sempre `TopicExchange`** no código Spring. Dois serviços declarando a
  mesma exchange com tipos diferentes derrubam o channel com `PRECONDITION_FAILED` —
  foi exatamente o conflito encontrado entre history e appointment.
