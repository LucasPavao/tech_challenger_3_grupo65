# tech_challenger_3_grupo65
Backend API desenvolvida para o Tech Challenger FIAP - Fase 3 - Grupo 65.

## Pré-requisitos

| Ferramenta | Versão | Para quê |
|---|---|---|
| Docker com Compose v2 | Compose **2.20 ou superior** (testado na 2.40.2) | subir o projeto. Confira com `docker compose version` — o antigo `docker-compose`, com hífen, é a v1 e **não** funciona |
| `make` | qualquer | atalhos de setup. Já vem no Linux e no macOS (Xcode Command Line Tools) |
| JDK 21 | 21 | só para rodar os testes ou as aplicações fora do Docker |

As portas **8080, 8081, 8082, 5432, 5433, 5434, 5672 e 15672** precisam estar livres. Um PostgreSQL
instalado localmente costuma ocupar a 5432.

**No Windows, use o WSL2** e clone o repositório *dentro* do sistema de arquivos do Linux
(`~/...`, não `/mnt/c/...`): o `make` só existe lá, e o Docker Desktop se integra ao WSL2.
Se você clonou antes de o repositório ter um `.gitattributes`, **clone de novo** — um
checkout feito no Windows pode ter convertido os scripts para CRLF, e aí o build falha com
`sh: ./mvnw: not found`.

## Primeiros passos

```bash
git clone https://github.com/LucasPavao/tech_challenger_3_grupo65
cd tech_challenger_3_grupo65
make setup   # cria os .env a partir dos .env.example
make up      # constrói as imagens e sobe tudo
make ps      # espere os sete containers ficarem healthy
```

A **primeira** execução de `make up` demora alguns minutos: ela baixa as imagens base e todas
as dependências Maven dos dois serviços. As seguintes reaproveitam o cache e sobem em
segundos. Se parecer travado, acompanhe com `make logs`.

Sobre os arquivos `.env`:

- **Não são versionados.** Sem eles, qualquer `docker compose` — na raiz ou dentro de um
  serviço — falha com `stat .../.env: no such file or directory`. Rode `make setup` antes de
  tudo; `make up` e `make build` já o chamam.
- **`make setup` nunca sobrescreve** um `.env` existente. Se um `.env.example` mudar, apague o
  `.env` correspondente e rode `make setup` de novo. Um `.env` desatualizado faz os serviços
  subirem nas portas erradas e as requisições devolverem `404`.

Sem `make` (Windows fora do WSL2, no Git Bash ou no PowerShell), o equivalente é:

```bash
cp .env.example .env
cp infra/.env.example infra/.env
cp history-service/.env.example history-service/.env
cp appointment-service/.env.example appointment-service/.env
cp notification-service/.env.example notification-service/.env
docker compose up -d --build
```

## Arquitetura Docker

Cada serviço é autocontido: tem seu próprio `docker-compose.yml`, seu próprio banco
PostgreSQL e seu próprio `.env`. O `docker-compose.yml` da raiz apenas agrega os
serviços via `include`, e o RabbitMQ é compartilhado, definido uma única vez em
`infra/`.

```
make setup                               # antes de tudo: cria os .env (sem eles, os comandos abaixo falham)
docker compose up                        # na raiz: sobe todos os serviços
docker compose up                        # dentro de um serviço: sobe só ele + o broker
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
| `make smoke` | teste ponta a ponta appointment → RabbitMQ → history e notification |
| `make logs` / `make ps` | logs e estado dos containers |
| `make down` / `make clean` | derruba tudo (`clean` também apaga os volumes) |

Endpoints: appointment-service (serviço principal) em http://localhost:8080,
history-service em http://localhost:8081 (GraphiQL em `/graphiql`), notification-service em
http://localhost:8082 e RabbitMQ Management em http://localhost:15672 (guest/guest).

**Cuidado:** como todos os serviços formam um único projeto Compose, `docker compose down`
de dentro da pasta de um serviço derruba o projeto **inteiro**. Para parar apenas um,
use `docker compose stop <serviço>-app <serviço>-postgres`.

**Cuidado:** a exchange é declarada pelo publisher (appointment-service), mas cada fila é
declarada pelo seu consumidor (history-service e notification-service). Se você subir com
`make up` e criar um agendamento antes de os consumidores terminarem de subir, a mensagem é
descartada em silêncio pelo RabbitMQ — espere todos os serviços ficarem saudáveis
(`make ps`) antes de testar.

### Portas

| Serviço | App | Postgres | Banco |
|---|---|---|---|
| appointment-service | 8080 | 5433 | `appointment_db` |
| history-service | 8081 | 5432 | `history_db` |
| notification-service | 8082 | 5434 | `notification_db` |

RabbitMQ: 5672 (AMQP) e 15672 (Management).

### Problemas comuns na instalação

| Sintoma | Causa | Solução |
|---|---|---|
| `stat .../.env: no such file or directory` | os `.env` ainda não foram criados | `make setup` na raiz |
| `sh: ./mvnw: not found` durante o build | scripts com fim de linha CRLF, de um checkout feito no Windows | clonar de novo, de preferência dentro do WSL2 |
| `failed to bind host port ... address already in use` | outro processo já usa a porta — comum com um PostgreSQL local na 5432 | parar esse processo, ou trocar a porta no `.env` do serviço (`DB_PORT`, `SERVER_PORT`) ou em `infra/.env` (`RABBITMQ_PORT`) |
| `404` nas rotas de um serviço | `.env` desatualizado, com portas antigas | apagar o `.env` do serviço e rodar `make setup` |
| `FATAL: database "mydatabase" does not exist` ao rodar pela IDE | código anterior à correção dos valores padrão de conexão | atualizar a branch |
| `make: command not found` | Windows fora do WSL2 | usar o WSL2, ou os comandos equivalentes de [Primeiros passos](#primeiros-passos) |
| erro sobre `include` ao rodar `docker compose` | Compose anterior à 2.20, ou o `docker-compose` v1 | atualizar o Docker e conferir com `docker compose version` |
| agendamento criado, mas nada chega ao histórico nem às notificações, sem erro nos logs | `.env` de antes da troca para a exchange única `appointment.exchange` | [Atualizando de uma versão anterior](#atualizando-de-uma-versão-anterior) |
| `PRECONDITION_FAILED - inequivalent arg 'x-dead-letter-exchange'` nos logs de um consumidor | fila criada por uma versão anterior, ainda gravada no volume do RabbitMQ | [Atualizando de uma versão anterior](#atualizando-de-uma-versão-anterior) |
| alterações de código não aparecem depois de `git pull` | `make up` reaproveita as imagens já construídas | `make build` |

### Atualizando de uma versão anterior

Quem já rodou o projeto antes da troca para a exchange única `appointment.exchange` fica com
restos que impedem a comunicação, mesmo depois de atualizar a branch:

1. **`.env` antigos.** O `make setup` não sobrescreve arquivos existentes. Um
   `history-service/.env` antigo, com `RABBITMQ_EXCHANGE=history.exchange`, faz o history
   escutar uma exchange que ninguém mais usa; um `notificationservice/.env` antigo ocupa a
   porta 5433, que é do appointment.
2. **Imagens antigas.** O `make up` não recompila o código, só reaproveita as imagens.
3. **Filas antigas no volume do RabbitMQ.** A `history.queue` criada pela versão anterior tem
   outra configuração de dead letter, e o broker recusa a nova com `PRECONDITION_FAILED`.

Com o ambiente de pé, nesta ordem:

```bash
rm -f appointment-service/.env history-service/.env notification-service/.env
make setup
make build                   # recompila e recria as aplicações com os .env novos
docker compose exec rabbitmq rabbitmqctl delete_queue history.queue
docker compose exec rabbitmq rabbitmqctl delete_queue history.queue.dlq
docker compose restart history-app
make smoke
```

A ordem importa: se as filas forem apagadas antes de as aplicações serem recriadas, a versão
antiga, ainda rodando, as recria com a configuração velha. Para começar do zero — perdendo os
dados dos bancos —, `make clean && make build` resolve tudo de uma vez.

## Fluxo de teste

A coleção Postman com todas as rotas está em
[`docs/postman/tech-challenge-grupo65.postman_collection.json`](docs/postman/tech-challenge-grupo65.postman_collection.json).
Importe esse único arquivo no Postman: ele cobre os três serviços, separado em pastas.

| Pasta | Para quê |
|---|---|
| **0. Health** | confirmar que o ambiente está de pé antes de qualquer coisa |
| **1. Appointment (REST)** | as rotas do serviço principal, uma a uma |
| **2. History (GraphQL)** | consultas do histórico, incluindo os casos de erro |
| **3. Notification (REST)** | notificações geradas para um paciente |

### Passo 0 — subir o ambiente

```bash
make setup   # só na primeira vez, cria os .env
make up
make ps      # espere os sete containers ficarem healthy
```

Esperar o `healthy` importa: a exchange é declarada pelo appointment-service, mas a fila é
declarada pelo history-service. Se você criar um agendamento antes de o history-service ter
subido pela primeira vez, não existe fila ligada à exchange e o RabbitMQ **descarta a
mensagem em silêncio** — o agendamento é criado, mas nunca aparece no histórico.

### Passo 1 — health dos serviços

| Serviço | URL |
|---|---|
| appointment-service | <http://localhost:8080/actuator/health> |
| history-service | <http://localhost:8081/actuator/health> |
| notification-service | <http://localhost:8082/actuator/health> |
| RabbitMQ Management | <http://localhost:15672> (guest / guest) |

Os três respondem com os componentes detalhados. Confira que `db` **e** `rabbit` estão `UP`
nos três — um `rabbit` DOWN significa que a integração não vai funcionar, mesmo com o
serviço respondendo normalmente nas rotas REST.

### Passo 2 — criar um agendamento

```bash
curl -s -X POST http://localhost:8080/appointments \
  -H 'content-type: application/json' \
  -d '{"patientId":777,"doctorId":7,"appointmentDate":"2030-12-01T09:00:00","description":"Consulta de rotina"}'
```

Responde `201` com o `id` gerado. A `appointmentDate` precisa estar **no futuro** — data no
passado devolve `400`. Guarde o `id` e o `patientId`, usados nos próximos passos.

### Passo 3 — consultar o histórico via GraphQL

O evento viaja pelo RabbitMQ, então leva um instante. Consulte pelo `patientId` do passo 2:

```bash
curl -s -X POST http://localhost:8081/graphql \
  -H 'content-type: application/json' \
  -d '{"query":"{ patientHistory(patientId: \"777\") { appointmentId eventStatus appointmentDate description } }"}'
```

O `patientId` vai **entre aspas**: é um `ID!` no schema. No navegador, o GraphiQL em
<http://localhost:8081/graphiql> dá autocomplete do schema e é mais rápido para explorar.

**`patientHistory` devolve só o último evento de cada consulta** — é o estado atual, não a
trilha. Se você criar um agendamento e depois remarcá-lo, essa query mostra apenas o
`RESCHEDULED`; o `SCHEDULED` continua gravado, mas colapsado. É intencional: a query usa
`DISTINCT ON (appointment_id)` para responder "como está cada consulta deste paciente
hoje". Para ver o histórico completo, use `appointmentTimeline` (passo 4).

### Passo 4 — evoluir o status e ver a trilha crescer

Os comandos abaixo usam o id `1`, que é o do primeiro agendamento de um banco novo. Se você
já criou outros — pelo Postman ou pelo `make smoke` —, troque o `1` pelo `id` retornado no
passo 2, nas duas chamadas.

```bash
curl -s -X PATCH http://localhost:8080/appointments/1/status \
  -H 'content-type: application/json' -d '{"status":"COMPLETED"}'
```

Valores aceitos: `SCHEDULED`, `COMPLETED`, `CANCELLED`. `COMPLETED` e `CANCELLED` são
estados finais — tentar alterar depois devolve `422`. Agora consulte a trilha completa
daquele agendamento:

```bash
curl -s -X POST http://localhost:8081/graphql \
  -H 'content-type: application/json' \
  -d '{"query":"{ appointmentTimeline(appointmentId: \"1\") { eventStatus occurredAt appointmentDate } }"}'
```

Devem aparecer **duas linhas**: o `SCHEDULED` da criação e o `COMPLETED` da conclusão. Esse
é o ponto que demonstra a arquitetura — o histórico é append-only, então cada mudança no
appointment-service vira um registro novo em vez de sobrescrever o anterior.

### Passo 5 — conferir a notificação

O mesmo evento do passo 2 também é entregue ao notification-service, por outra fila:

```bash
curl -s http://localhost:8082/notifications/patient/777
```

Deve aparecer uma notificação por evento publicado — a do agendamento e a da conclusão —, todas
com `status` `SENT`.

### Atalho: o fluxo inteiro automatizado

```bash
make smoke
```

Faz os passos 1, 2, 3 e 5 e falha com diagnóstico se a integração estiver quebrada.
Rode antes de investigar qualquer coisa à mão — ele separa "o ambiente está ruim" de "a
requisição está errada".

É também a forma mais rápida de confirmar o ambiente antes de uma apresentação.

### Quando o histórico não recebe o evento

| Sintoma | Causa provável | Como verificar |
|---|---|---|
| `patientHistory` volta vazio | o history-service ainda não tinha subido quando você publicou | Management → Queues → `history.queue` deve ter 1 consumidor |
| 404 nas rotas do appointment | `.env` desatualizado, serviços trocados de porta | `curl localhost:8080/appointments` deve responder 200 |
| mensagem na `history.queue.dlq` | payload fora do contrato | `docs/messaging/appointment-event.md` no history-service |
| `rabbit` DOWN no health | broker não subiu ou app não alcança a rede `shared` | `docker compose logs rabbitmq` |

## Adicionando um novo serviço

Exemplo com um `billing-service`. São quatro arquivos novos e **uma** edição fora
da pasta do serviço.

### 1. `billing-service/docker-compose.yml`

```yaml
name: grupo65                       # convenção: mesmo projeto para todos

include:
  - path: ../infra/docker-compose.yml
    env_file: ../infra/.env

services:
  billing-postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: ${POSTGRES_DB}
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    ports: ["${DB_PORT}:5432"]
    volumes: [billing-postgres-data:/var/lib/postgresql/data]
    networks: [billing-net]         # só a rede privada do serviço
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}"]
      interval: 5s
      retries: 10

  billing-app:
    build: .
    profiles: [apps]
    env_file:
      - .env
      - ../infra/.env
    environment:
      DB_HOST: billing-postgres     # sobrescreve o localhost do .env
      DB_PORT: 5432                      # porta interna, não a publicada
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_PORT: 5672
    ports: ["${SERVER_PORT}:${SERVER_PORT}"]
    networks: [billing-net, shared] # rede privada + `shared` para o broker
    restart: on-failure
    depends_on:
      billing-postgres: { condition: service_healthy }
      rabbitmq:              { condition: service_healthy }
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:${SERVER_PORT}/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 40s

networks:
  billing-net:
    driver: bridge

volumes:
  billing-postgres-data:
```

### 2. `billing-service/.env.example`

Usa a próxima faixa de portas livre (8080/5433, 8081/5432 e 8082/5434 já estão tomadas):

```
COMPOSE_PROFILES=apps
POSTGRES_DB=billing_db
POSTGRES_USER=postgres
POSTGRES_PASSWORD=postgres
DB_HOST=localhost
DB_PORT=5435
SERVER_PORT=8083
```

### 3. `Dockerfile` e `.dockerignore`

Copiados de qualquer serviço existente, sem alteração — o `mvnw` e o `pom.xml` vêm do
contexto de build.

### 4. Uma entrada no `docker-compose.yml` da raiz

```yaml
  - path: ./billing-service/docker-compose.yml
    env_file: ./billing-service/.env
```

Depois, `make setup && make up`.

Não esqueça de atualizar a tabela de **Portas** deste README com a faixa usada pelo
novo serviço — é a outra edição central que esta receita não cobre sozinha.

### O que não se toca

`infra/`, o compose dos outros serviços, o `Makefile` (o `ENVS` é derivado dos
`.env.example` existentes, então não precisa de edição), nenhum `.env` alheio. O único
acoplamento central é a entrada no `include` — não há como eliminá-la, pois o Compose
não aceita glob em `include`.

### Armadilhas

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
