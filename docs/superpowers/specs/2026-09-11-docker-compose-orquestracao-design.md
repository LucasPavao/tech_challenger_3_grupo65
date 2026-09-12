# Orquestração Docker do monorepo — design

**Data:** 2026-09-11
**Status:** aprovado, pronto para plano de implementação

## Problema

Os dois serviços do monorepo (`history-service` e `appointment-service`) têm cada um
seu `docker-compose.yml`, e cada um sobe **seu próprio RabbitMQ**. Como a integração
entre eles é assíncrona via RabbitMQ, eles não conseguem se comunicar. Além disso
ambos usam o mesmo nome de banco (`mydatabase`) e as mesmas portas (5432, 5672, 8080),
então subir os dois ao mesmo tempo colide. Nenhum dos serviços tem `Dockerfile`.

## Objetivos

1. Um comando sobe todas as aplicações juntas, comunicando-se via RabbitMQ.
2. Cada serviço tem seu próprio banco de dados, isolado dos demais.
3. Cada serviço continua subindo sozinho, para desenvolvimento e testes.
4. Escalável: criar um serviço novo não exige reescrever a configuração central.
   O serviço define seu próprio banco, suas portas e sua configuração.

## Não-objetivos

Gateway/Nginx, provisionamento de topologia RabbitMQ via `definitions.json`, réplicas,
observabilidade (Prometheus/Grafana) e pipeline de CI. Todos cabem depois sem
retrabalho neste desenho.

## Decisões

| # | Decisão | Alternativas descartadas |
|---|---|---|
| 1 | Compose por serviço, agregado na raiz via `include:` | compose único central; combinação via `COMPOSE_FILE` |
| 2 | RabbitMQ em `infra/docker-compose.yml`, incluído pela raiz **e** por cada serviço | broker só na raiz + broker descartável por serviço; broker externo ao Compose |
| 3 | `Dockerfile` multi-stage com cache mount do Maven | multi-stage sem cache; imagem que copia jar pré-construído |
| 4 | Portas e nomes de banco declarados no `.env` do próprio serviço | bancos sem porta publicada; portas dinâmicas |
| 5 | Padronizar as exchanges em `TopicExchange` | padronizar em `Direct`; resolver no Compose (impossível) |

## Arquitetura

### Layout

```
tech_challenger_3_grupo65/
├── docker-compose.yml          # name + include dos serviços
├── Makefile                    # atalhos de conveniência
├── .env                        # overrides globais (opcional)
├── infra/
│   ├── docker-compose.yml      # rabbitmq + rede `shared` — fonte única
│   └── .env.example
├── history-service/
│   ├── docker-compose.yml      # include ../infra + postgres próprio + app
│   ├── Dockerfile
│   ├── .dockerignore
│   └── .env.example
└── appointment-service/        # mesma forma
```

### Agregação via `include`

A raiz contém apenas:

```yaml
name: grupo65
include:
  - path: ./history-service/docker-compose.yml
    env_file: ./history-service/.env
  - path: ./appointment-service/docker-compose.yml
    env_file: ./appointment-service/.env
```

Cada serviço declara `name: grupo65` e inclui a infra com o `.env` dela explícito:

```yaml
name: grupo65
include:
  - path: ../infra/docker-compose.yml
    env_file: ../infra/.env
```

Amarrar `infra/.env` é necessário: como a infra é alcançada por dois caminhos de
include, sem isso as credenciais do broker dependeriam da ordem do include.

**Comportamento resultante:**

- `docker compose up` na raiz → 1 RabbitMQ, 2 Postgres, 2 apps.
- `docker compose up` em `history-service/` → RabbitMQ + Postgres e app dele apenas.
- Subir os dois serviços separadamente converge no mesmo projeto Compose e **reusa**
  o broker já em execução, em vez de duplicá-lo.
- Serviço novo: criar a pasta no molde e adicionar uma entrada no `include` da raiz.

**Validado no Compose v2.40.2:** o `include` transitivo da infra é deduplicado, e o
`env_file` por include isola variáveis de mesmo nome com valores diferentes.

**Trade-off aceito:** sendo tudo um único projeto Compose, `docker compose down`
dentro da pasta de um serviço derruba o projeto inteiro. Para parar um serviço só,
usa-se `docker compose stop <serviços>`. Documentar no README.

### Rede

Como todos os composes declaram `name: grupo65`, eles formam um único projeto Compose —
e a rede `default` implícita de um projeto Compose é uma só, compartilhada por todos os
serviços dele. Usá-la para conectar cada app ao seu Postgres não isola nada: qualquer
container do projeto alcançaria qualquer outro por ela.

Por isso cada serviço declara sua própria rede privada (`<serviço>-net`, ex.:
`history-net`, `appointment-net`):

- `<serviço>-net`: só o Postgres do serviço e a app dele entram nela.
- `shared` (declarada em `infra/`): conecta as **apps** ao RabbitMQ; o Postgres de
  nenhum serviço entra nela.

Cada app fica em duas redes (`<serviço>-net` e `shared`); cada Postgres fica só na sua
`<serviço>-net`. O Postgres de um serviço não alcança o de outro, porque não compartilham
nenhuma rede — nem por acidente. O isolamento entre bancos é topológico, não apenas
convencional.

### Configuração

| Arquivo | Governa |
|---|---|
| `infra/.env` | credenciais e portas do RabbitMQ — o contrato compartilhado |
| `<serviço>/.env` | seu Postgres (porta, nome, credenciais), porta da app, exchanges e routing keys |
| `.env` da raiz | `COMPOSE_PROFILES=apps` (obrigatório) e overrides globais |

Hostnames diferem entre rodar na IDE e rodar em container. O `.env` permanece escrito
na ótica do desenvolvedor local (`localhost` + porta publicada); o bloco `environment`
da app no Compose sobrescreve `DB_HOST` e `RABBITMQ_HOST` para os nomes de serviço
(`rabbitmq`, `<serviço>-postgres`) e as portas internas.

Convenção de portas — cada serviço escolhe a próxima livre no seu próprio `.env`:

| Serviço | App | Postgres | Banco |
|---|---|---|---|
| history-service | 8080 | 5432 | `history_db` |
| appointment-service | 8081 | 5433 | `appointment_db` |

RabbitMQ: 5672 (AMQP) e 15672 (Management).

### Imagens

`Dockerfile` multi-stage idêntico em forma nos dois serviços:

```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
USER app
ENTRYPOINT ["java","-jar","/app/app.jar"]
```

Razões: `dependency:go-offline` em layer separada evita re-baixar dependências a cada
mudança de código; o cache mount do `.m2` sobrevive a rebuilds; alpine no runtime traz
`wget` do busybox, que viabiliza o healthcheck sem instalar pacotes. `-DskipTests` é
deliberado — testes rodam no ciclo de desenvolvimento, não a cada `up`.

### Healthchecks e ordem de subida

- Postgres: `pg_isready -U $POSTGRES_USER -d $POSTGRES_DB`
- RabbitMQ: `rabbitmq-diagnostics -q check_running && rabbitmq-diagnostics -q check_local_alarms`
  (padroniza no check mais forte, hoje usado só pelo history-service)
- Apps: `wget -qO- http://localhost:<porta>/actuator/health`, com `start_period: 40s`
  para cobrir boot do Spring + Flyway.

Cada app declara `depends_on` com `condition: service_healthy` para seu Postgres e
para o RabbitMQ.

**Semântica de mensageria, não resolvida por ordem de boot:** a exchange é declarada
pelo publisher, mas a fila pelo consumer. Se o appointment-service publicar antes de o
history-service ter subido pela primeira vez, a topic exchange descarta a mensagem
silenciosamente. Após a primeira subida do history-service a fila é durável e
sobrevive no volume. `depends_on` não garante que o listener registrou, então isso é
documentado, não contornado.

### Modo IDE

As aplicações ficam no profile `apps`, com `COMPOSE_PROFILES=apps` no `.env` da raiz
**e** no `.env` de cada serviço. Assim `docker compose up` sobe tudo, e
`COMPOSE_PROFILES= docker compose up -d` sobe apenas Postgres e RabbitMQ para debugar a
aplicação pela IDE. Escalável: um serviço novo entra no profile sem precisar ser listado
em lugar nenhum.

`COMPOSE_PROFILES` é lido do `.env` do diretório de invocação, **não** do `env_file` dos
includes — validado no Compose v2.40.2. Por isso a raiz precisa do seu próprio `.env`,
ainda que só com essa chave; sem ele, `docker compose up` na raiz sobe apenas os bancos e
o broker.

### Makefile

Atalhos de conveniência na raiz: `setup` (copia os `.env.example` ainda inexistentes),
`up`, `infra`, `logs`, `ps`, `down`.

## Mudanças em arquivos existentes

| Arquivo | Mudança |
|---|---|
| `appointment-service/.../config/RabbitMqConfig.java` | `DirectExchange` → `TopicExchange` nas exchanges de history e notification |
| `appointment-service/.env.example` | banco `appointment_db` na porta 5433, app em 8081 |
| `history-service/.env.example` | banco `history_db` na porta 5432; chaves de RabbitMQ migram para `infra/.env` |
| `history-service/docker-compose.yml` | reescrito no formato acima |
| `appointment-service/docker-compose.yml` | reescrito no formato acima |
| `.gitignore` da raiz | ignorar `**/.env` |

Arquivos novos: `docker-compose.yml` e `Makefile` na raiz; `infra/docker-compose.yml` e
`infra/.env.example`; `Dockerfile` e `.dockerignore` por serviço; seção no README.

**Atenção ao grupo:** os `.env` locais não são versionados. Como portas e nomes de
banco mudam, cada pessoa precisa regerar o seu a partir do `.env.example` — caso
contrário o appointment-service continuará apontando para a 5432 e colidirá. O
`make setup` cria apenas os que ainda não existem, sem sobrescrever.

## Adicionando um novo serviço

Exemplo com um `notification-service`. São quatro arquivos novos e **uma** edição fora
da pasta do serviço.

### 1. `notification-service/docker-compose.yml`

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
    environment:
      DB_HOST: notification-postgres     # sobrescreve o localhost do .env
      DB_PORT: 5432                      # porta interna, não a publicada
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_PORT: 5672
    ports: ["${SERVER_PORT}:${SERVER_PORT}"]
    networks: [notification-net, shared] # rede privada + `shared` para o broker
    depends_on:
      notification-postgres: { condition: service_healthy }
      rabbitmq:              { condition: service_healthy }
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:${SERVER_PORT}/actuator/health"]
      start_period: 40s

networks:
  notification-net:
    driver: bridge

volumes:
  notification-postgres-data:
```

### 2. `notification-service/.env.example`

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

### 3. `Dockerfile` e `.dockerignore`

Copiados de qualquer serviço existente, sem alteração — o `mvnw` e o `pom.xml` vêm do
contexto de build.

### 4. Uma entrada no `docker-compose.yml` da raiz

```yaml
  - path: ./notification-service/docker-compose.yml
    env_file: ./notification-service/.env
```

Depois, `make setup && make up`.

### O que não se toca

`infra/`, o compose dos outros serviços, o `Makefile`, nenhum `.env` alheio. O único
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

## Critérios de aceite

1. `docker compose config` resolve sem erro na raiz e em cada serviço, com um único
   `rabbitmq` e sem colisão de portas.
2. `make up` deixa todos os containers em estado `healthy`.
3. Ponta a ponta: `POST` de agendamento no appointment-service (8080) resulta em
   registro consultável via GraphQL no history-service (8081). Exercita Postgres,
   RabbitMQ, a mudança de exchange e a rede de uma vez.
4. Independência: após `docker compose down -v` na raiz, `docker compose up` dentro de
   `history-service/` sobe exatamente três containers e nenhum do appointment-service.
5. As suítes dos dois serviços seguem verdes após a mudança de exchange
   (`AppointmentEventContractTest` é o candidato a acusar).
