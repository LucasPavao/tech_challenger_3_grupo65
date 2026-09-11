# Orquestração Docker do monorepo — plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Subir `history-service` e `appointment-service` juntos com um comando, cada um com seu próprio banco, comunicando-se por um RabbitMQ compartilhado, mantendo cada serviço capaz de subir sozinho.

**Architecture:** Compose por serviço, agregado na raiz via `include:`. O RabbitMQ e a rede `shared` vivem em `infra/docker-compose.yml`, incluído pela raiz e por cada serviço — deduplicado pelo Compose. Cada serviço tem `Dockerfile` multi-stage e declara suas portas e seu banco no próprio `.env`.

**Tech Stack:** Docker Compose v2.40+, Docker BuildKit, PostgreSQL 16, RabbitMQ 4, Java 21 (eclipse-temurin alpine), Spring Boot 4.1, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-09-11-docker-compose-orquestracao-design.md`

## Global Constraints

- Todo compose (raiz, `infra/`, serviços) declara `name: grupo65` no topo. Sem isso o serviço vira um projeto próprio e duplica o RabbitMQ.
- Compose mínimo: v2.20 para `include`; validado em v2.40.2.
- Exchanges no código Spring são sempre `TopicExchange`. Tipos divergentes na mesma exchange causam `PRECONDITION_FAILED` e derrubam o channel.
- Portas: history 8080/5432 (`history_db`), appointment 8081/5433 (`appointment_db`), RabbitMQ 5672/15672.
- Contrato de mensageria compartilhado, valores idênticos nos dois serviços: exchange `history.exchange`, routing key `history.created`, queue `history.queue`.
- Aplicações no profile `apps`. `COMPOSE_PROFILES=apps` é obrigatório no `.env` da raiz **e** no `.env` de cada serviço — o Compose lê essa chave do `.env` do diretório de invocação, não do `env_file` dos includes.
- Dentro da rede Docker os hostnames são `rabbitmq` e `<serviço>-postgres`, nas portas internas 5672 e 5432.
- Nenhum `.env` é versionado; só os `.env.example`.

---

### Task 1: Padronizar as exchanges do appointment-service em Topic

O `history-service` declara `history.exchange` como `TopicExchange`; o `appointment-service` declara a mesma exchange como `DirectExchange`. Com brokers separados isso nunca apareceu. No broker compartilhado, o segundo a subir recebe `PRECONDITION_FAILED - inequivalent arg 'type'`, o channel fecha e nenhuma mensagem trafega. Esta task é pré-requisito de tudo: sem ela o compose sobe e a integração não funciona.

**Files:**
- Modify: `appointment-service/src/main/java/br/com/tech/challenge/appointmentservice/config/RabbitMqConfig.java`
- Test: `appointment-service/src/test/java/br/com/tech/challenge/appointmentservice/messaging/HistoryExchangeInteropIT.java` (criar)

**Interfaces:**
- Consumes: nada de tasks anteriores.
- Produces: `historyExchange` e `notificationExchange` passam a ser beans `org.springframework.amqp.core.TopicExchange` (antes `DirectExchange`). O `AppointmentEventPublisher.publish(Appointment, AppointmentEventStatus)` mantém a assinatura.

- [ ] **Step 1: Escrever o teste de interoperabilidade que falha**

O teste declara no broker a topologia **do history-service** (topic exchange + queue durável + binding) antes de subir o contexto do appointment. Se o appointment declarar `Direct`, o `RabbitAdmin` falha ao auto-declarar. Depois publica um evento e confirma que ele chegou na fila — provando o roteamento fim a fim.

Criar `appointment-service/src/test/java/br/com/tech/challenge/appointmentservice/messaging/HistoryExchangeInteropIT.java`:

```java
package br.com.tech.challenge.appointmentservice.messaging;

import br.com.tech.challenge.appointmentservice.config.MessagingConfiguration;
import br.com.tech.challenge.appointmentservice.config.RabbitMqConfig;
import br.com.tech.challenge.appointmentservice.entity.Appointment;
import br.com.tech.challenge.appointmentservice.event.AppointmentEventStatus;
import br.com.tech.challenge.appointmentservice.service.AppointmentEventPublisher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garante que o appointment-service consegue publicar na topologia declarada pelo
 * history-service. Regressao para o conflito Direct x Topic na mesma exchange, que
 * derruba o channel com PRECONDITION_FAILED quando os dois compartilham o broker.
 *
 * Carrega apenas as classes de mensageria, sem o AppointmentServiceApplication, para
 * nao exigir Postgres — o servico ainda nao tem nenhum teste que suba contexto completo.
 */
@SpringBootTest(classes = {
        RabbitAutoConfiguration.class,
        MessagingConfiguration.class,
        RabbitMqConfig.class,
        AppointmentEventPublisher.class
})
@Testcontainers
class HistoryExchangeInteropIT {

    private static final String EXCHANGE = "history.exchange";
    private static final String QUEUE = "history.queue";
    private static final String ROUTING_KEY = "history.created";

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine");

    @Autowired
    AppointmentEventPublisher publisher;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("app.messaging.history-exchange", () -> EXCHANGE);
        registry.add("app.messaging.history-routing-key", () -> ROUTING_KEY);
        registry.add("app.messaging.notification-exchange", () -> "notification.exchange");
        registry.add("app.messaging.notification-routing-key", () -> "notification.created");
        registry.add("app.messaging.publish-notification", () -> false);
    }

    /**
     * Declara a topologia do history-service ANTES de o contexto do appointment subir,
     * para que a auto-declaracao do appointment tenha de ser compativel com ela.
     */
    @BeforeAll
    static void declararTopologiaDoHistoryService() {
        CachingConnectionFactory factory =
                new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        factory.setUsername(RABBIT.getAdminUsername());
        factory.setPassword(RABBIT.getAdminPassword());

        RabbitAdmin admin = new RabbitAdmin(factory);
        TopicExchange exchange = new TopicExchange(EXCHANGE, true, false);
        Queue queue = QueueBuilder.durable(QUEUE).build();

        admin.declareExchange(exchange);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY));

        factory.destroy();
    }

    @Test
    void deveEntregarEventoNaFilaDoHistoryService() {
        Appointment appointment = new Appointment();
        appointment.setId(42L);
        appointment.setPatientId(10L);
        appointment.setDoctorId(7L);
        appointment.setAppointmentDate(LocalDateTime.of(2026, 10, 10, 9, 0));
        appointment.setDescription("Consulta de rotina");

        publisher.publish(appointment, AppointmentEventStatus.SCHEDULED);

        Message received = rabbitTemplate.receive(QUEUE, 5000);

        assertThat(received)
                .as("evento publicado pelo appointment deve chegar na fila do history-service")
                .isNotNull();

        String payload = new String(received.getBody(), StandardCharsets.UTF_8);
        assertThat(payload).contains("\"appointmentId\":42");
        assertThat(payload).contains("\"eventStatus\":\"SCHEDULED\"");
    }
}
```

Notas sobre a montagem do contexto, para quem for depurar:

- `RabbitAutoConfiguration` fornece `ConnectionFactory` e `RabbitAdmin`. É o `RabbitAdmin` que, ao conectar, declara automaticamente todo bean `Declarable` do contexto — inclusive as exchanges de `RabbitMqConfig`. É aí que o conflito de tipo estoura.
- `RabbitMqConfig` define seu próprio `RabbitTemplate`; o da autoconfiguração é `@ConditionalOnMissingBean`, então não há conflito.
- `Appointment` usa Lombok; se os setters não existirem com esses nomes, confira `entity/Appointment.java` e ajuste as chamadas — os campos são `id`, `patientId`, `doctorId`, `appointmentDate`, `description`.

- [ ] **Step 2: Rodar o teste e verificar que ele falha**

```bash
cd appointment-service && ./mvnw -B test -Dtest=HistoryExchangeInteropIT
```

Esperado: FALHA. A mensagem vem do broker, no formato `PRECONDITION_FAILED - inequivalent arg 'type' for exchange 'history.exchange' in vhost '/': received 'direct' but current is 'topic'`. Ela pode aparecer como falha de inicialização do contexto ou como `received == null`, dependendo de quando o `RabbitAdmin` tenta declarar. Qualquer uma das duas conta como a falha esperada; o que **não** pode acontecer é o teste passar.

- [ ] **Step 3: Trocar as exchanges para Topic**

Em `appointment-service/.../config/RabbitMqConfig.java`, substituir o import e os dois beans:

```java
import org.springframework.amqp.core.TopicExchange;
```

```java
    @Bean
    TopicExchange historyExchange(MessagingProperties properties) {
        return new TopicExchange(properties.historyExchange(), true, false);
    }

    @Bean
    TopicExchange notificationExchange(MessagingProperties properties) {
        return new TopicExchange(properties.notificationExchange(), true, false);
    }
```

Remover o import agora órfão de `org.springframework.amqp.core.DirectExchange`. Os argumentos `(nome, durable=true, autoDelete=false)` batem exatamente com o que o `history-service` declara em `config/RabbitMQConfig.java`, que é o requisito para não haver conflito.

- [ ] **Step 4: Rodar o teste e verificar que passa**

```bash
cd appointment-service && ./mvnw -B test -Dtest=HistoryExchangeInteropIT
```

Esperado: PASS.

- [ ] **Step 5: Rodar a suíte completa dos dois serviços**

```bash
cd appointment-service && ./mvnw -B test
cd ../history-service && ./mvnw -B test
```

Esperado: ambas verdes. Nenhum teste do history-service deve ser afetado — a mudança é só do lado do publisher.

- [ ] **Step 6: Commit**

```bash
git add appointment-service/src/main/java/br/com/tech/challenge/appointmentservice/config/RabbitMqConfig.java \
        appointment-service/src/test/java/br/com/tech/challenge/appointmentservice/messaging/HistoryExchangeInteropIT.java
git commit -m "fix: padroniza exchanges do appointment-service em TopicExchange

O history-service declara history.exchange como topic. Com Direct no
appointment, o broker compartilhado recusa a segunda declaracao com
PRECONDITION_FAILED e nenhuma mensagem trafega."
```

---

### Task 2: Infra compartilhada — RabbitMQ e rede

**Files:**
- Create: `infra/docker-compose.yml`
- Create: `infra/.env.example`
- Modify: `.gitignore` (raiz)

**Interfaces:**
- Consumes: nada.
- Produces: serviço Compose `rabbitmq` (hostname `rabbitmq`, porta interna 5672), rede `shared`, volume `rabbitmq-data`. Tasks 3 e 4 dependem desses três nomes exatos.

- [ ] **Step 1: Criar `infra/docker-compose.yml`**

```yaml
name: grupo65

services:
  rabbitmq:
    image: rabbitmq:4-management-alpine
    environment:
      RABBITMQ_DEFAULT_USER: ${RABBITMQ_USER:-guest}
      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_PASSWORD:-guest}
      RABBITMQ_DEFAULT_VHOST: ${RABBITMQ_VHOST:-/}
    ports:
      - "${RABBITMQ_PORT:-5672}:5672"
      - "${RABBITMQ_MANAGEMENT_PORT:-15672}:15672"
    volumes:
      - rabbitmq-data:/var/lib/rabbitmq
    networks:
      - shared
    restart: on-failure
    healthcheck:
      test: ["CMD-SHELL", "rabbitmq-diagnostics -q check_running && rabbitmq-diagnostics -q check_local_alarms"]
      interval: 10s
      timeout: 10s
      retries: 10
      start_period: 20s

networks:
  shared:
    driver: bridge

volumes:
  rabbitmq-data:
```

Todos os valores têm default (`:-`) para que o arquivo resolva mesmo sem `.env`, o que mantém `docker compose config` utilizável num clone recém-feito.

- [ ] **Step 2: Criar `infra/.env.example`**

```
# Contrato compartilhado do broker. Todos os servicos apontam para este RabbitMQ.
RABBITMQ_USER=guest
RABBITMQ_PASSWORD=guest
RABBITMQ_VHOST=/
RABBITMQ_PORT=5672
RABBITMQ_MANAGEMENT_PORT=15672
```

- [ ] **Step 3: Ignorar os `.env` em todo o monorepo**

Acrescentar ao `.gitignore` da raiz (que hoje tem apenas `.idea` e `.claude`, e **sem** quebra de linha no fim — comece adicionando uma):

```
.env
**/.env
```

- [ ] **Step 4: Gerar o `.env` local e validar**

```bash
cp infra/.env.example infra/.env
cd infra && docker compose config
```

Esperado: YAML resolvido, `name: grupo65`, um único serviço `rabbitmq`, rede `grupo65_shared`, nenhuma variável sem valor. Confirme também que `git status` **não** mostra `infra/.env`.

- [ ] **Step 5: Subir o broker isolado e confirmar que fica saudável**

```bash
cd infra && docker compose up -d
docker compose ps
```

Esperado: `rabbitmq` com status `healthy` em até ~40s. O Management fica em http://localhost:15672 (guest/guest). Em seguida, derrube: `docker compose down`.

- [ ] **Step 6: Commit**

```bash
git add infra/docker-compose.yml infra/.env.example .gitignore
git commit -m "feat: RabbitMQ compartilhado e rede shared em infra/"
```

---

### Task 3: Containerizar o history-service

**Files:**
- Create: `history-service/Dockerfile`
- Create: `history-service/.dockerignore`
- Rewrite: `history-service/docker-compose.yml`
- Rewrite: `history-service/.env.example`

**Interfaces:**
- Consumes: da Task 2, o serviço `rabbitmq`, a rede `shared` e o path `../infra/docker-compose.yml` com `../infra/.env`.
- Produces: serviços Compose `history-postgres` (porta host 5432, banco `history_db`) e `history-app` (porta host 8080, profile `apps`), volume `history-postgres-data`. A Task 6 consome a API em `http://localhost:8080/graphql`.

- [ ] **Step 1: Criar `history-service/.dockerignore`**

Sem isso o `target/` local (que já existe e tem centenas de arquivos) vai para o contexto de build, deixando-o lento e podendo contaminar a imagem com um jar velho.

```
target/
.git/
.idea/
.env
*.iml
docs/
```

- [ ] **Step 2: Criar `history-service/Dockerfile`**

```dockerfile
# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
USER app
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

Três detalhes deliberados: a linha `# syntax` garante o frontend que entende `--mount=type=cache`; o `dependency:go-offline` numa layer separada do `COPY src/` faz mudança de código não re-baixar dependências; `chmod +x mvnw` cobre o caso do bit de execução se perder no clone (comum no Windows).

- [ ] **Step 3: Construir a imagem**

```bash
cd history-service && docker build -t grupo65/history-service:dev .
```

Esperado: build conclui. Confirme o tamanho com `docker images grupo65/history-service:dev` — deve ficar na casa dos 200–300 MB, não ~600 MB (se estiver, o estágio final pegou o JDK em vez do JRE).

- [ ] **Step 4: Reescrever `history-service/.env.example`**

Substituir o conteúdo inteiro. As chaves de RabbitMQ de credencial saem (migram para `infra/.env`); as de topologia ficam, porque são do serviço.

```
# Profile do Compose. Sem isso, `docker compose up` sobe so o banco.
COMPOSE_PROFILES=apps

# Banco proprio deste servico
POSTGRES_DB=history_db
POSTGRES_USER=postgres
POSTGRES_PASSWORD=postgres
DB_HOST=localhost
DB_PORT=5432

# Aplicacao
SERVER_PORT=8080
GRAPHIQL_ENABLED=true

# Topologia RabbitMQ — CONTRATO COMPARTILHADO com o appointment-service.
# Os valores devem bater com HISTORY_EXCHANGE e HISTORY_ROUTING_KEY de la.
RABBITMQ_EXCHANGE=history.exchange
RABBITMQ_QUEUE=history.queue
RABBITMQ_ROUTING_KEY=history.created
```

`DB_HOST=localhost` e `DB_PORT=5432` são a ótica de quem roda a aplicação pela IDE. O Compose sobrescreve ambos para o container.

- [ ] **Step 5: Reescrever `history-service/docker-compose.yml`**

Substituir o conteúdo inteiro (o RabbitMQ sai daqui — agora vem de `infra/`):

```yaml
name: grupo65

include:
  - path: ../infra/docker-compose.yml
    env_file: ../infra/.env

services:
  history-postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: ${POSTGRES_DB}
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    ports:
      - "${DB_PORT}:5432"
    volumes:
      - history-postgres-data:/var/lib/postgresql/data
    restart: on-failure
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}"]
      interval: 5s
      timeout: 5s
      retries: 10

  history-app:
    build: .
    profiles: [apps]
    env_file:
      - .env
      - ../infra/.env
    environment:
      DB_HOST: history-postgres
      DB_PORT: 5432
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_PORT: 5672
    ports:
      - "${SERVER_PORT}:${SERVER_PORT}"
    networks:
      - default
      - shared
    restart: on-failure
    depends_on:
      history-postgres:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:${SERVER_PORT}/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 40s

volumes:
  history-postgres-data:
```

Por que `env_file` lista dois arquivos: o `.env` do serviço traz banco, porta e topologia; `../infra/.env` traz `RABBITMQ_USER`/`RABBITMQ_PASSWORD`/`RABBITMQ_VHOST` sem duplicá-los. O bloco `environment` vem depois e vence os dois, trocando os endereços de `localhost`/porta publicada para os nomes e portas internos da rede Docker.

Por que o Postgres **não** está em `shared`: assim o appointment-service não consegue alcançar o banco do history nem por engano. O isolamento vira topologia.

- [ ] **Step 6: Validar a configuração**

```bash
cp history-service/.env.example history-service/.env
cd history-service && docker compose config
```

Esperado: três serviços (`rabbitmq`, `history-postgres`, `history-app`), nenhuma variável vazia, `history-app` nas redes `default` e `shared`, `history-postgres` só em `default`.

- [ ] **Step 7: Subir o serviço sozinho**

```bash
cd history-service && docker compose up -d --build
docker compose ps
```

Esperado: exatamente três containers, todos `healthy` (dê ~60s para o `history-app`, que roda Flyway no boot). Se `history-app` ficar `unhealthy`, veja `docker compose logs history-app` — a causa mais provável é o Flyway não alcançar o banco, o que significa `DB_HOST` errado.

- [ ] **Step 8: Provar que a API responde**

```bash
curl -s localhost:8080/actuator/health
curl -s -X POST localhost:8080/graphql \
  -H 'Content-Type: application/json' \
  -d '{"query":"{ patientHistory(patientId: 1) { id appointmentId } }"}'
```

Esperado: health `{"status":"UP",...}`; o GraphQL responde `{"data":{"patientHistory":[]}}` — lista vazia é o resultado correto, não um erro.

- [ ] **Step 9: Derrubar e commitar**

```bash
cd history-service && docker compose down
git add history-service/Dockerfile history-service/.dockerignore \
        history-service/docker-compose.yml history-service/.env.example
git commit -m "feat: containeriza o history-service com banco proprio"
```

---

### Task 4: Containerizar o appointment-service

Mesma forma da Task 3, com a porta e o banco próprios. O código está repetido de propósito: quem executa esta task pode não ter lido a anterior.

**Files:**
- Create: `appointment-service/Dockerfile`
- Create: `appointment-service/.dockerignore`
- Rewrite: `appointment-service/docker-compose.yml`
- Rewrite: `appointment-service/.env.example`

**Interfaces:**
- Consumes: da Task 2, o serviço `rabbitmq`, a rede `shared` e o path `../infra/docker-compose.yml`.
- Produces: serviços Compose `appointment-postgres` (porta host 5433, banco `appointment_db`) e `appointment-app` (porta host 8081, profile `apps`), volume `appointment-postgres-data`. A Task 6 consome `POST http://localhost:8081/appointments`.

- [ ] **Step 1: Criar `appointment-service/.dockerignore`**

```
target/
.git/
.idea/
.env
*.iml
docs/
```

- [ ] **Step 2: Criar `appointment-service/Dockerfile`**

```dockerfile
# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
USER app
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

**Verifique antes de construir:** o `appointment-service` precisa ter `mvnw` e `.mvn/` na raiz da pasta. Se não tiver (só o `history-service` os tem hoje), gere com `mvn -N wrapper:wrapper -Dmaven=3.9.9` dentro de `appointment-service/` e inclua os arquivos gerados no commit desta task.

- [ ] **Step 3: Construir a imagem**

```bash
cd appointment-service && docker build -t grupo65/appointment-service:dev .
```

Esperado: build conclui; imagem na casa dos 200–300 MB.

- [ ] **Step 4: Reescrever `appointment-service/.env.example`**

```
# Profile do Compose. Sem isso, `docker compose up` sobe so o banco.
COMPOSE_PROFILES=apps

# Banco proprio deste servico
POSTGRES_DB=appointment_db
POSTGRES_USER=postgres
POSTGRES_PASSWORD=postgres
DB_HOST=localhost
DB_PORT=5433

# Aplicacao
SERVER_PORT=8081

# Topologia RabbitMQ — CONTRATO COMPARTILHADO com o history-service.
# HISTORY_EXCHANGE e HISTORY_ROUTING_KEY devem bater com RABBITMQ_EXCHANGE e
# RABBITMQ_ROUTING_KEY de la, senao a mensagem nao chega na fila.
HISTORY_EXCHANGE=history.exchange
HISTORY_ROUTING_KEY=history.created
NOTIFICATION_EXCHANGE=notification.exchange
NOTIFICATION_ROUTING_KEY=notification.created
PUBLISH_NOTIFICATION=false
```

Note a porta 5433: é o que evita a colisão com o Postgres do history-service.

- [ ] **Step 5: Reescrever `appointment-service/docker-compose.yml`**

Substituir o conteúdo inteiro:

```yaml
name: grupo65

include:
  - path: ../infra/docker-compose.yml
    env_file: ../infra/.env

services:
  appointment-postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: ${POSTGRES_DB}
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    ports:
      - "${DB_PORT}:5432"
    volumes:
      - appointment-postgres-data:/var/lib/postgresql/data
    restart: on-failure
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}"]
      interval: 5s
      timeout: 5s
      retries: 10

  appointment-app:
    build: .
    profiles: [apps]
    env_file:
      - .env
      - ../infra/.env
    environment:
      DB_HOST: appointment-postgres
      DB_PORT: 5432
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_PORT: 5672
    ports:
      - "${SERVER_PORT}:${SERVER_PORT}"
    networks:
      - default
      - shared
    restart: on-failure
    depends_on:
      appointment-postgres:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:${SERVER_PORT}/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 40s

volumes:
  appointment-postgres-data:
```

- [ ] **Step 6: Validar a configuração**

```bash
cp appointment-service/.env.example appointment-service/.env
cd appointment-service && docker compose config
```

Esperado: três serviços (`rabbitmq`, `appointment-postgres`, `appointment-app`), Postgres publicando em 5433, app em 8081.

- [ ] **Step 7: Subir o serviço sozinho e provar a API**

```bash
cd appointment-service && docker compose up -d --build
docker compose ps
curl -s localhost:8081/actuator/health
```

Esperado: três containers `healthy`; health `UP`. Nenhum container do history-service deve estar de pé — confira com `docker ps --filter name=history`, que deve sair vazio.

- [ ] **Step 8: Derrubar e commitar**

```bash
cd appointment-service && docker compose down
git add appointment-service/Dockerfile appointment-service/.dockerignore \
        appointment-service/docker-compose.yml appointment-service/.env.example
git commit -m "feat: containeriza o appointment-service com banco proprio"
```

---

### Task 5: Agregador na raiz e Makefile

**Files:**
- Create: `docker-compose.yml` (raiz)
- Create: `.env.example` (raiz)
- Create: `Makefile` (raiz)

**Interfaces:**
- Consumes: das Tasks 3 e 4, os composes `history-service/docker-compose.yml` e `appointment-service/docker-compose.yml`.
- Produces: um único projeto Compose `grupo65` com 5 serviços. A Task 6 usa `make up`.

- [ ] **Step 1: Criar `docker-compose.yml` na raiz**

```yaml
name: grupo65

include:
  - path: ./history-service/docker-compose.yml
    env_file: ./history-service/.env
  - path: ./appointment-service/docker-compose.yml
    env_file: ./appointment-service/.env
```

É o arquivo inteiro. O `env_file` por entrada é o que permite que os dois serviços usem `DB_PORT` e `POSTGRES_DB` com valores diferentes: cada include resolve suas variáveis contra o `.env` do próprio serviço. Ambos incluem `../infra/docker-compose.yml`, e o Compose deduplica — o RabbitMQ aparece uma única vez.

- [ ] **Step 2: Criar `.env.example` na raiz**

```
# Sobe tambem as aplicacoes, nao so os bancos e o broker.
# O Compose le esta chave do .env do diretorio onde o comando roda,
# nao do env_file dos includes — por isso a raiz precisa do seu proprio.
COMPOSE_PROFILES=apps
```

- [ ] **Step 3: Criar o `Makefile` na raiz**

Use TAB para indentar as receitas — espaços fazem o make falhar com `missing separator`.

```makefile
.PHONY: setup up build infra down clean logs ps smoke

ENVS := .env infra/.env history-service/.env appointment-service/.env

setup: ## Cria os .env que ainda nao existem, a partir dos .env.example
	@for f in $(ENVS); do \
		if [ ! -f $$f ]; then cp $$f.example $$f; echo "criado $$f"; \
		else echo "mantido $$f"; fi; \
	done

up: setup ## Sobe tudo (bancos, broker e aplicacoes)
	docker compose up -d

build: setup ## Sobe tudo reconstruindo as imagens
	docker compose up -d --build

infra: setup ## Sobe apenas os bancos e o RabbitMQ (para rodar as apps pela IDE)
	COMPOSE_PROFILES= docker compose up -d

down: ## Derruba tudo, preservando os volumes
	docker compose down

clean: ## Derruba tudo e apaga os volumes (perde os dados)
	docker compose down -v

logs: ## Segue os logs de todos os servicos
	docker compose logs -f

ps: ## Estado dos containers
	docker compose ps

smoke: ## Teste ponta a ponta: appointment -> RabbitMQ -> history
	./scripts/smoke-test.sh
```

O alvo `smoke` chama um script criado na Task 6; até lá ele falha, o que é esperado.

- [ ] **Step 4: Validar a agregação**

```bash
make setup
docker compose config --services | sort
```

Esperado, exatamente estes cinco, e **um só** `rabbitmq`:

```
appointment-app
appointment-postgres
history-app
history-postgres
rabbitmq
```

Confirme também que não há colisão de portas:

```bash
docker compose config | grep -A1 published
```

Esperado: 8080, 8081, 5432, 5433, 5672, 15672 — cada um uma única vez.

- [ ] **Step 5: Subir tudo**

```bash
make build
make ps
```

Esperado: cinco containers, todos `healthy`. Dê até ~90s na primeira vez (build das duas imagens + boot do Spring).

- [ ] **Step 6: Verificar o modo só-infra**

```bash
make clean
make infra
docker compose ps --services
```

Esperado: apenas `rabbitmq`, `history-postgres`, `appointment-postgres` — nenhuma aplicação. Depois `make clean` de novo.

- [ ] **Step 7: Commit**

```bash
git add docker-compose.yml .env.example Makefile
git commit -m "feat: compose agregador na raiz e Makefile"
```

---

### Task 6: Provar a comunicação via RabbitMQ ponta a ponta

Esta é a task que verifica o objetivo central: o `appointment-service` publica, o RabbitMQ entrega, o `history-service` consome e persiste. Ela entrega um script repetível, não uma conferência manual.

**Files:**
- Create: `scripts/smoke-test.sh`
- Modify: `README.md` (seção "Arquitetura Docker", já existente)

**Interfaces:**
- Consumes: da Task 5, `make up` e os cinco serviços; da Task 3, `http://localhost:8080/graphql`; da Task 4, `http://localhost:8081/appointments`.
- Produces: `scripts/smoke-test.sh`, chamado por `make smoke`. Sai com código 0 no sucesso e 1 na falha.

- [ ] **Step 1: Escrever o smoke test que falha**

Criar `scripts/smoke-test.sh`. Ele cria um agendamento no appointment-service e faz polling no GraphQL do history-service até o registro aparecer — polling porque a entrega é assíncrona e um `sleep` fixo produz teste instável.

```bash
#!/usr/bin/env bash
# Teste ponta a ponta: appointment-service -> RabbitMQ -> history-service.
# Cria um agendamento e espera o registro correspondente aparecer no historico.
set -euo pipefail

APPOINTMENT_URL="${APPOINTMENT_URL:-http://localhost:8081}"
HISTORY_URL="${HISTORY_URL:-http://localhost:8080}"
TIMEOUT_SEGUNDOS="${TIMEOUT_SEGUNDOS:-60}"

# patientId aleatorio para o teste ser repetivel sem limpar o banco
PATIENT_ID=$(( (RANDOM % 900000) + 100000 ))
APPOINTMENT_DATE=$(date -u -d '+30 days' '+%Y-%m-%dT%H:%M:%S')

echo "==> 1/3 aguardando os servicos responderem"
for url in "$APPOINTMENT_URL/actuator/health" "$HISTORY_URL/actuator/health"; do
  fim=$(( SECONDS + TIMEOUT_SEGUNDOS ))
  until curl -sf "$url" | grep -q '"status":"UP"'; do
    if (( SECONDS >= fim )); then
      echo "FALHA: $url nao ficou UP em ${TIMEOUT_SEGUNDOS}s" >&2
      exit 1
    fi
    sleep 2
  done
  echo "    OK $url"
done

echo "==> 2/3 criando agendamento (patientId=$PATIENT_ID)"
resposta=$(curl -sf -X POST "$APPOINTMENT_URL/appointments" \
  -H 'Content-Type: application/json' \
  -d "{\"patientId\":$PATIENT_ID,\"doctorId\":7,\"appointmentDate\":\"$APPOINTMENT_DATE\",\"description\":\"Smoke test\"}")
echo "    resposta: $resposta"

echo "==> 3/3 aguardando o evento chegar no history-service via RabbitMQ"
consulta="{\"query\":\"{ patientHistory(patientId: \\\"$PATIENT_ID\\\") { appointmentId eventStatus description } }\"}"
fim=$(( SECONDS + TIMEOUT_SEGUNDOS ))
while true; do
  historico=$(curl -sf -X POST "$HISTORY_URL/graphql" \
    -H 'Content-Type: application/json' -d "$consulta" || echo '')
  if echo "$historico" | grep -q '"eventStatus":"SCHEDULED"'; then
    echo "    OK evento recebido: $historico"
    echo
    echo "SUCESSO: a comunicacao via RabbitMQ esta funcionando."
    exit 0
  fi
  if (( SECONDS >= fim )); then
    echo "FALHA: o evento nao chegou ao history-service em ${TIMEOUT_SEGUNDOS}s" >&2
    echo "Ultima resposta do GraphQL: $historico" >&2
    echo "Investigue: docker compose logs history-app | grep -i rabbit" >&2
    exit 1
  fi
  sleep 2
done
```

Torne executável e versione o bit:

```bash
chmod +x scripts/smoke-test.sh
git update-index --chmod=+x scripts/smoke-test.sh 2>/dev/null || true
```

- [ ] **Step 2: Rodar com tudo derrubado e ver falhar**

```bash
make clean
./scripts/smoke-test.sh
```

Esperado: FALHA no passo 1, com `nao ficou UP`. Isso confirma que o script realmente verifica algo — um smoke test que passa com os serviços desligados não testa nada.

- [ ] **Step 3: Subir tudo e rodar o teste**

```bash
make build
make smoke
```

Esperado: `SUCESSO: a comunicacao via RabbitMQ esta funcionando.`

- [ ] **Step 4: Se falhar no passo 3, diagnosticar nesta ordem**

Não altere o script para fazê-lo passar. Cada sintoma tem uma causa específica:

| Sintoma | Causa provável | Verificação |
|---|---|---|
| `PRECONDITION_FAILED` nos logs | Task 1 não foi aplicada | `docker compose logs appointment-app \| grep -i precondition` |
| Fila `history.queue` sem consumidor | `history-app` não subiu ou não alcança o broker | Management em http://localhost:15672 → aba Queues |
| Mensagens publicadas mas `unroutable` | routing key divergente entre os `.env` | comparar `HISTORY_ROUTING_KEY` com `RABBITMQ_ROUTING_KEY` |
| `history-app` não enxerga o broker | falta `shared` em `networks` | `docker inspect grupo65-history-app-1 \| grep -A5 Networks` |
| Mensagem na DLQ `history.queue.dlq` | payload fora do contrato | `docs/messaging/appointment-event.md` |

- [ ] **Step 5: Confirmar a topologia no broker**

```bash
docker compose exec rabbitmq rabbitmqctl list_exchanges name type | grep history
docker compose exec rabbitmq rabbitmqctl list_queues name messages consumers
```

Esperado: `history.exchange` com tipo **topic** (não `direct`); `history.queue` com pelo menos 1 consumidor e 0 mensagens acumuladas; `history.queue.dlq` com 0 mensagens.

- [ ] **Step 6: Confirmar o isolamento dos bancos**

Cada serviço tem o seu, e um não alcança o do outro:

```bash
docker compose exec history-postgres psql -U postgres -d history_db -c '\dt'
docker compose exec appointment-postgres psql -U postgres -d appointment_db -c '\dt'
docker compose exec history-app wget -qO- --timeout=3 appointment-postgres:5432 || echo "OK: banco do appointment inalcancavel a partir do history"
```

Esperado: cada `\dt` lista só as tabelas do seu serviço; o terceiro comando falha e imprime a mensagem de OK.

- [ ] **Step 7: Confirmar a independência de cada serviço**

```bash
make clean
cd history-service && docker compose up -d
docker compose ps --services
```

Esperado: exatamente `rabbitmq`, `history-postgres`, `history-app` — nenhum container do appointment. Em seguida:

```bash
cd ../appointment-service && docker compose up -d
docker ps --filter label=com.docker.compose.project=grupo65 --format '{{.Names}}' | sort
```

Esperado: os cinco containers, com **um só** RabbitMQ — o appointment reaproveitou o broker já de pé em vez de subir outro. Depois `cd .. && make clean`.

- [ ] **Step 8: Documentar o uso no README**

Na seção "Arquitetura Docker" do `README.md`, remova a nota `> **Nota:** a estrutura descrita abaixo está em implementação.` e insira, logo após o bloco de comandos existente, uma subseção `### Comandos` antes de `### Portas`:

```markdown
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
```

- [ ] **Step 9: Commit**

```bash
git add scripts/smoke-test.sh README.md
git commit -m "test: smoke test ponta a ponta da integracao via RabbitMQ"
```

---

## Critérios de aceite do plano

Ao fim da Task 6, todos devem valer:

1. `docker compose config` resolve sem erro na raiz e em cada serviço, com um único `rabbitmq` e sem colisão de portas.
2. `make up` deixa os cinco containers `healthy`.
3. `make smoke` termina com `SUCESSO`.
4. `docker compose up` dentro de `history-service/` sobe exatamente três containers e nenhum do appointment-service.
5. `./mvnw -B test` verde nos dois serviços.
6. `git status` limpo — nenhum `.env` aparece como não rastreado.
