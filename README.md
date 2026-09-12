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

### Comandos

| Comando | O que faz |
|---|---|
| `make setup` | cria os `.env` a partir dos `.env.example` (não sobrescreve) |
| `make up` | sobe tudo: bancos, RabbitMQ e aplicações |
| `make build` | idem, reconstruindo as imagens |
| `make infra` | sobe só os bancos e o RabbitMQ, para rodar as apps pela IDE |
| `make smoke` | teste ponta a ponta appointment → RabbitMQ → history |
| `make logs` / `make ps` | logs e estado dos containers |
| `make down` / `make clean` | derruba tudo (`clean` também apaga os volumes) |

Endpoints: appointment-service em http://localhost:8081, history-service em
http://localhost:8080 (GraphiQL em `/graphiql`), RabbitMQ Management em
http://localhost:15672 (guest/guest).

**Cuidado:** como todos os serviços formam um único projeto Compose, `docker compose down`
de dentro da pasta de um serviço derruba o projeto **inteiro**. Para parar apenas um,
use `docker compose stop <serviço>-app <serviço>-postgres`.

**Cuidado:** a exchange é declarada pelo publisher (appointment-service) mas a fila é
declarada pelo consumer (history-service). Se você subir com `make up` e postar um
agendamento antes de o history-service terminar de subir, a mensagem é descartada em
silêncio pelo RabbitMQ — espere os dois serviços ficarem saudáveis (`make ps`) antes de
testar.

Num clone novo, rode `make setup` antes de qualquer coisa: os arquivos `.env` não são
versionados, e `docker compose up`/`docker compose config` na raiz falham com um erro
genérico de arquivo não encontrado se eles ainda não existirem, porque o `include`
referencia o `.env` de cada serviço via `env_file`. `make up` e `make build` já chamam
`make setup` primeiro, então o problema só aparece se você rodar `docker compose`
diretamente sem antes gerar os `.env`.

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
    networks: [notification-net]         # só a rede privada do serviço
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}"]
      interval: 5s
      retries: 10

  notification-app:
    build: .
    profiles: [apps]
    env_file:
      - .env
      - ../infra/.env
    environment:
      DB_HOST: notification-postgres     # sobrescreve o localhost do .env
      DB_PORT: 5432                      # porta interna, não a publicada
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_PORT: 5672
    ports: ["${SERVER_PORT}:${SERVER_PORT}"]
    networks: [notification-net, shared] # rede privada + `shared` para o broker
    restart: on-failure
    depends_on:
      notification-postgres: { condition: service_healthy }
      rabbitmq:              { condition: service_healthy }
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:${SERVER_PORT}/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 40s

networks:
  notification-net:
    driver: bridge

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

Não esqueça de atualizar a tabela de **Portas** deste README com a faixa usada pelo
novo serviço — é a outra edição central que esta receita não cobre sozinha.

#### O que não se toca

`infra/`, o compose dos outros serviços, o `Makefile` (o `ENVS` é derivado dos
`.env.example` existentes, então não precisa de edição), nenhum `.env` alheio. O único
acoplamento central é a entrada no `include` — não há como eliminá-la, pois o Compose
não aceita glob em `include`.

#### Armadilhas

- **`name: grupo65` no topo é obrigatório.** Sem ele o serviço vira um projeto Compose
  próprio e sobe um RabbitMQ paralelo em vez de reusar o compartilhado.
- **Cada serviço precisa da sua própria rede privada (`<serviço>-net`).** O Postgres
  fica só nela; a aplicação entra nela **e** na `shared`. Omitir `shared` na aplicação
  e ela não enxerga o broker; colocar o Postgres na `shared` (ou usar a rede `default`
  do projeto) quebra o isolamento entre bancos — como todos os composes declaram o
  mesmo `name: grupo65`, a rede `default` é uma só para o projeto inteiro, compartilhada
  por todos os serviços.
- **Exchanges sempre `TopicExchange`** no código Spring. Dois serviços declarando a
  mesma exchange com tipos diferentes derrubam o channel com `PRECONDITION_FAILED` —
  foi exatamente o conflito encontrado entre history e appointment.
