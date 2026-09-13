# Finalização do notification-service — plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fechar os critérios de aceite da Pessoa 5 no notification-service — idempotência por `eventId`, validação do evento, schema versionado e testes de recebimento, criação e processamento — mantendo o serviço integrado via `docker compose` e RabbitMQ, e entregar a branch pronta para PR na `main`.

**Architecture:** A branch `feat/notificationService` recebe a `main` (que já tem o serviço containerizado e integrado) e o serviço é renomeado para `notification-service/`. O `NotificationService` passa a validar o evento e a deduplicar pelo `eventId`, com `UNIQUE(event_id)` no banco como garantia final, no mesmo padrão do `HistoryIngestionService`. O schema sai do `ddl-auto=update` para uma migration Flyway com `ddl-auto=validate`.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring AMQP, Spring Data JPA, Flyway, PostgreSQL 16, RabbitMQ 4, JUnit 5, Mockito, Testcontainers, Awaitility, Docker Compose v2.

**Spec:** `docs/superpowers/specs/2026-09-13-notification-service-finalizacao-design.md`

## Global Constraints

- Chave de idempotência: `eventId` do `AppointmentEvent`; constraint `uk_notifications_event_id UNIQUE (event_id)`.
- Tabela `notifications`, colunas exatas: `id BIGSERIAL PK`, `event_id UUID NOT NULL`, `event_status VARCHAR(20) NOT NULL`, `appointment_id BIGINT NOT NULL`, `patient_id BIGINT NOT NULL`, `message VARCHAR(255) NOT NULL`, `status VARCHAR(10) NOT NULL`, `created_at TIMESTAMP NOT NULL`; índice `idx_notifications_patient ON notifications (patient_id, created_at DESC)`.
- `spring.jpa.hibernate.ddl-auto=validate`.
- `NotificationService` **sem `@Transactional`** (motivo documentado no `HistoryIngestionService`).
- Pasta do serviço: `notification-service/`. O pacote Java `br.com.tech.challenge.notificationservice` e `spring.application.name=notificationservice` **não mudam**.
- Topologia: exchange `appointment.exchange`, fila `notification.queue`, routing key `notification.created`, DLQ `notification.queue.dlq`. Portas: app 8082, Postgres 5434, banco `notification_db`.
- Notificação imediata por evento, envio simulado por log (`LogNotificationSender`). **Fora de escopo:** autenticação/JWT, lembrete agendado, envio real, `futureAppointments` do history.
- Anotações de teste no Spring Boot 4.1 (pacotes confirmados nos JARs):
  - `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`
  - `org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase`
  - `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`
  - `org.springframework.test.context.bean.override.mockito.MockitoBean`
  - `org.springframework.boot.test.context.TestConfiguration`
- Validações com ambiente limpo usam `COMPOSE_PROJECT_NAME` próprio, para **não tocar nos volumes `grupo65_*`** do mantenedor — a Task 7 depende deles.
- Toda mensagem de commit termina com:
  ```
  Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_019PC7ahgX95xoVhyBKU3zFc
  ```
- Push e PR são ações externas: **confirmar com o mantenedor antes** (Task 8).

---

### Task 1: Trazer a `main` para a branch

A `feat/notificationService` tem o notificationservice idêntico ao commit `1e04d18` da Pessoa 5, que é ancestral do PR #8 já integrado à `main`. Os merges por squash cortam a ancestralidade, então o merge gera conflitos add/add. A versão da `main` é a evolução dos mesmos arquivos; nenhum trabalho se perde.

**Files:**
- Modify (resolução de conflito, fica a versão da `main`): 12 arquivos em `notificationservice/` — `.env.example`, `README.md`, `docker-compose.yml`, `mvnw`, `pom.xml`, `config/RabbitMQConfig.java`, `dto/AppointmentEvent.java`, `messaging/NotificationMessageListener.java`, `service/NotificationService.java`, `src/main/resources/application.properties`, `NotificationApplicationTests.java`, `service/NotificationServiceTest.java`

**Interfaces:**
- Consumes: nada.
- Produces: branch com o notificationservice idêntico ao da `origin/main` e três suítes verdes. Todas as tasks seguintes partem daqui.

- [ ] **Step 1: Conferir o ponto de partida**

```bash
git branch --show-current          # esperado: feat/notificationService
git status --short                 # esperado: vazio
git fetch origin
git log --oneline HEAD..origin/main   # esperado: 659ef29 Feat/rabbit mq (#8)
```

Se a árvore não estiver limpa ou a branch for outra, pare e reporte.

- [ ] **Step 2: Fazer o merge e ver os conflitos**

```bash
git merge origin/main
git diff --name-only --diff-filter=U
```

Esperado: `CONFLICT (add/add)` e exatamente os 12 arquivos listados acima, todos em `notificationservice/`. Se aparecer conflito **fora** de `notificationservice/`, pare e reporte — isso não era esperado.

- [ ] **Step 3: Resolver com a versão da `main`**

Durante o merge, `--theirs` é a `origin/main`.

```bash
git checkout --theirs -- $(git diff --name-only --diff-filter=U)
git add -A notificationservice
git diff --name-only --diff-filter=U                  # esperado: vazio
git diff --stat origin/main -- notificationservice    # esperado: vazio (idêntico à main)
```

- [ ] **Step 4: Rodar as três suítes**

```bash
(cd appointment-service && ./mvnw -B test) 2>&1 | grep -E "Tests run:.*Skipped: [0-9]+$|BUILD" | tail -2
(cd history-service && ./mvnw -B test) 2>&1 | grep -E "Tests run:.*Skipped: [0-9]+$|BUILD" | tail -2
(cd notificationservice && ./mvnw -B test) 2>&1 | grep -E "Tests run:.*Skipped: [0-9]+$|BUILD" | tail -2
```

Esperado: appointment `Tests run: 13, Failures: 0, Errors: 0`; history `Tests run: 57, Failures: 0, Errors: 0`; notification `Tests run: 8, Failures: 0, Errors: 0`; todas `BUILD SUCCESS`. Requer Docker rodando (Testcontainers).

- [ ] **Step 5: Commitar o merge**

```bash
git commit -F - <<'MSG'
merge: traz a main (PR #8) para a feat/notificationService

A main ja tem o notification-service containerizado e integrado via
appointment.exchange. Os 12 conflitos add/add no notificationservice foram
resolvidos com a versao da main, que e a evolucao dos mesmos arquivos do
commit 1e04d18 (ancestral do PR #8): nenhum trabalho da Pessoa 5 se perde.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019PC7ahgX95xoVhyBKU3zFc
MSG
git log --oneline -1
```

---

### Task 2: Renomear para `notification-service` e remover `EventType`

**Files:**
- Rename: `notificationservice/` → `notification-service/`
- Delete: `notification-service/src/main/java/br/com/tech/challenge/notificationservice/dto/EventType.java`
- Modify: `docker-compose.yml` (raiz, 2 linhas)
- Modify: `README.md` (raiz, 3 linhas: o `cp` de "Primeiros passos", a tabela "Portas" e o `rm` do guia de migração)

**Interfaces:**
- Consumes: branch da Task 1.
- Produces: serviço em `notification-service/`. **Todas as tasks seguintes usam este caminho.** Serviços Compose continuam `notification-app` e `notification-postgres`; o volume continua `grupo65_notification-postgres-data`.

- [ ] **Step 1: Renomear a pasta**

```bash
git mv notificationservice notification-service
git ls-files notificationservice | wc -l      # esperado: 0
ls -d notificationservice 2>/dev/null || echo "pasta antiga nao existe"
```

O `git mv` de diretório move a pasta inteira, inclusive arquivos ignorados como `.env` e `target/`. Se a pasta antiga ainda existir com sobras, apague-a só depois de confirmar que `git ls-files notificationservice` é vazio: `rm -rf notificationservice`.

- [ ] **Step 2: Remover o enum sem uso**

```bash
grep -rnw "EventType" notification-service/src --include=*.java | grep -v "dto/EventType.java" || echo "sem uso"
git rm -q notification-service/src/main/java/br/com/tech/challenge/notificationservice/dto/EventType.java
```

Esperado antes do `git rm`: `sem uso`. Se aparecer algum uso, pare e reporte.

- [ ] **Step 3: Atualizar os caminhos no compose e no README da raiz**

```bash
python3 - <<'PY'
def troca(p, velho, novo):
    t = open(p, encoding="utf-8").read()
    assert t.count(velho) == 1, f"{p}: nao encontrado exatamente uma vez: {velho!r}"
    open(p, "w", encoding="utf-8").write(t.replace(velho, novo))
    print(f"ok  {p}")

troca("docker-compose.yml",
      "  - path: ./notificationservice/docker-compose.yml\n    env_file: ./notificationservice/.env\n",
      "  - path: ./notification-service/docker-compose.yml\n    env_file: ./notification-service/.env\n")
troca("README.md",
      "cp notificationservice/.env.example notificationservice/.env",
      "cp notification-service/.env.example notification-service/.env")
troca("README.md",
      "| notificationservice | 8082 | 5434 | `notification_db` |",
      "| notification-service | 8082 | 5434 | `notification_db` |")
troca("README.md",
      "rm -f appointment-service/.env history-service/.env notificationservice/.env",
      "rm -f appointment-service/.env history-service/.env notification-service/.env")
PY
grep -n "notificationservice" docker-compose.yml README.md
```

Esperado no `grep`: uma única linha no README, a do texto explicativo do guia ("um `notificationservice/.env` antigo ocupa a porta 5433"), que descreve o caminho antigo e será reescrita na Task 7.

- [ ] **Step 4: Recriar o `.env` do serviço e validar o compose**

```bash
rm -f notification-service/.env && make setup | grep notification
docker compose config --services | sort
(cd notification-service && docker compose config --format json | python3 -c 'import sys,json;print(json.load(sys.stdin)["name"])')
```

Esperado: `criado notification-service/.env`; 7 serviços (`appointment-app`, `appointment-postgres`, `history-app`, `history-postgres`, `notification-app`, `notification-postgres`, `rabbitmq`); nome do projeto `grupo65`.

- [ ] **Step 5: Rodar a suíte do serviço no novo caminho**

```bash
(cd notification-service && ./mvnw -B test) 2>&1 | grep -E "Tests run:.*Skipped: [0-9]+$|BUILD" | tail -2
```

Esperado: `Tests run: 8, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add -A notification-service docker-compose.yml README.md
git status --short | grep -v "^R\|^D\|^M" || true
git commit -F - <<'MSG'
refactor: renomeia notificationservice para notification-service

Segue o nome do plano de divisao do projeto e a convencao dos outros
servicos. O pacote Java e o spring.application.name nao mudam. Remove o
enum EventType, sem uso desde a troca para o contrato com eventStatus.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019PC7ahgX95xoVhyBKU3zFc
MSG
```

---

### Task 3: Schema versionado e campos de idempotência

**Files:**
- Create: `notification-service/src/main/resources/db/migration/V1__create_notifications.sql`
- Create: `notification-service/src/test/java/br/com/tech/challenge/notificationservice/NotificationSchemaTest.java`
- Modify: `notification-service/src/main/java/br/com/tech/challenge/notificationservice/model/Notification.java`
- Modify: `notification-service/src/main/java/br/com/tech/challenge/notificationservice/repository/NotificationRepository.java`
- Modify: `notification-service/src/main/java/br/com/tech/challenge/notificationservice/service/NotificationService.java` (método `createNotification`)
- Modify: `notification-service/src/main/resources/application.properties` (`ddl-auto`)
- Modify: `notification-service/src/test/java/br/com/tech/challenge/notificationservice/service/NotificationServiceTest.java` (um teste novo)

**Interfaces:**
- Consumes: serviço em `notification-service/` (Task 2).
- Produces:
  - `Notification#getEventId(): UUID`, `Notification#setEventId(UUID)`
  - `Notification#getEventStatus(): AppointmentEventStatus`, `Notification#setEventStatus(AppointmentEventStatus)`
  - `NotificationRepository#findByEventId(UUID eventId): Optional<Notification>`
  - Tabela `notifications` criada pela migration V1, com `uk_notifications_event_id`.

- [ ] **Step 1: Escrever o teste de schema**

Criar `notification-service/src/test/java/br/com/tech/challenge/notificationservice/NotificationSchemaTest.java`:

```java
package br.com.tech.challenge.notificationservice;

import br.com.tech.challenge.notificationservice.support.PostgresTestcontainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainers.class)
class NotificationSchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayCriaTabelaNotificationsComTodasAsColunas() {
        List<String> colunas = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'notifications'",
                String.class);

        assertThat(colunas).containsExactlyInAnyOrder(
                "id", "event_id", "event_status", "appointment_id", "patient_id",
                "message", "status", "created_at");
    }

    @Test
    void eventIdTemConstraintUnica() {
        Integer total = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                WHERE tc.table_name = 'notifications'
                  AND tc.constraint_type = 'UNIQUE'
                  AND kcu.column_name = 'event_id'
                """, Integer.class);

        assertThat(total).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Trocar `ddl-auto` para `validate` antes de rodar**

Isto vem antes do teste de propósito: com `update`, o próprio Hibernate criaria a tabela e a constraint, e o teste passaria sem migration nenhuma.

```bash
cd notification-service
python3 - <<'PY'
p = "src/main/resources/application.properties"; t = open(p).read()
assert t.count("spring.jpa.hibernate.ddl-auto=update") == 1
open(p, "w").write(t.replace("spring.jpa.hibernate.ddl-auto=update", "spring.jpa.hibernate.ddl-auto=validate"))
print("ok")
PY
```

- [ ] **Step 3: Rodar e ver falhar**

```bash
./mvnw -B test -Dtest=NotificationSchemaTest 2>&1 | grep -E "Tests run|Schema-validation|missing table|BUILD" | head -5
```

Esperado: FALHA ao carregar o contexto, com `Schema-validation: missing table [notifications]`.

- [ ] **Step 4: Escrever a migration**

Criar `notification-service/src/main/resources/db/migration/V1__create_notifications.sql`:

```sql
CREATE TABLE notifications (
    id             BIGSERIAL    PRIMARY KEY,
    event_id       UUID         NOT NULL,
    event_status   VARCHAR(20)  NOT NULL,
    appointment_id BIGINT       NOT NULL,
    patient_id     BIGINT       NOT NULL,
    message        VARCHAR(255) NOT NULL,
    status         VARCHAR(10)  NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    CONSTRAINT uk_notifications_event_id UNIQUE (event_id)
);

CREATE INDEX idx_notifications_patient ON notifications (patient_id, created_at DESC);
```

- [ ] **Step 5: Mapear os campos novos na entidade**

Substituir o conteúdo de `notification-service/src/main/java/br/com/tech/challenge/notificationservice/model/Notification.java`:

```java
package br.com.tech.challenge.notificationservice.model;

import br.com.tech.challenge.notificationservice.dto.AppointmentEventStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Data
@NoArgsConstructor
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Chave de idempotencia: cada evento gera no maximo uma notificacao. */
    @Column(name = "event_id", nullable = false, unique = true, updatable = false)
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_status", nullable = false, length = 20, updatable = false)
    private AppointmentEventStatus eventStatus;

    @Column(name = "appointment_id", nullable = false)
    private Long appointmentId;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(nullable = false)
    private String message;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private NotificationStatus status;
}
```

- [ ] **Step 6: Adicionar a consulta por `eventId` no repositório**

Substituir o conteúdo de `notification-service/src/main/java/br/com/tech/challenge/notificationservice/repository/NotificationRepository.java`:

```java
package br.com.tech.challenge.notificationservice.repository;

import br.com.tech.challenge.notificationservice.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByPatientId(Long patientId);

    Optional<Notification> findByEventId(UUID eventId);
}
```

- [ ] **Step 7: Rodar o teste de schema e ver passar**

```bash
./mvnw -B test -Dtest=NotificationSchemaTest 2>&1 | grep -E "Tests run:|BUILD" | tail -2
```

Esperado: `Tests run: 2, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 8: Escrever o teste que exige gravar `eventId` e `eventStatus`**

As colunas novas são `NOT NULL`: sem preencher os campos, toda inserção real falharia. Acrescentar este teste em `NotificationServiceTest.java`, logo após `deveGerarMensagemDeAcordoComOStatusDoEvento`:

```java
    @Test
    void deveGravarEventIdEEventStatusNaNotificacao() {
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        AppointmentEvent evento = evento(AppointmentEventStatus.RESCHEDULED);

        Notification result = notificationService.processAppointmentEvent(evento);

        assertThat(result.getEventId()).isEqualTo(evento.eventId());
        assertThat(result.getEventStatus()).isEqualTo(AppointmentEventStatus.RESCHEDULED);
    }
```

- [ ] **Step 9: Rodar e ver falhar**

```bash
./mvnw -B test -Dtest=NotificationServiceTest#deveGravarEventIdEEventStatusNaNotificacao 2>&1 | grep -E "Tests run|expected|but was|BUILD" | head -4
```

Esperado: FALHA com `expected: <uuid>` `but was: null`.

- [ ] **Step 10: Preencher os campos na criação**

Em `NotificationService.java`, no método `createNotification`, trocar:

```java
        Notification notification = new Notification();

        notification.setAppointmentId(event.appointmentId());
```

por:

```java
        Notification notification = new Notification();

        notification.setEventId(event.eventId());
        notification.setEventStatus(event.eventStatus());
        notification.setAppointmentId(event.appointmentId());
```

- [ ] **Step 11: Rodar a suíte completa**

```bash
./mvnw -B test 2>&1 | grep -E "Tests run:.*Skipped: [0-9]+$|BUILD" | tail -2
cd ..
```

Esperado: `Tests run: 11, Failures: 0, Errors: 0`, `BUILD SUCCESS` (8 anteriores + 2 de schema + 1 novo). O `NotificationApplicationTests` confirma que o Hibernate valida a entidade contra a migration.

- [ ] **Step 12: Commit**

```bash
git add notification-service
git commit -F - <<'MSG'
feat(notification): versiona o schema com Flyway e grava a chave de idempotencia

A tabela notifications passa a ser criada pela migration V1, com
UNIQUE(event_id), e o Hibernate so valida (ddl-auto=validate), como nos
outros servicos. A notificacao grava eventId e eventStatus do evento.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019PC7ahgX95xoVhyBKU3zFc
MSG
```

---

### Task 4: Validação do evento e idempotência no processamento

**Files:**
- Modify: `notification-service/src/main/java/br/com/tech/challenge/notificationservice/service/NotificationService.java` (reescrita)
- Modify: `notification-service/src/test/java/br/com/tech/challenge/notificationservice/service/NotificationServiceTest.java` (reescrita)
- Create: `notification-service/src/test/java/br/com/tech/challenge/notificationservice/service/NotificationServicePersistenceTest.java`

**Interfaces:**
- Consumes: `Notification#getEventId/setEventId`, `Notification#getEventStatus/setEventStatus`, `NotificationRepository#findByEventId(UUID): Optional<Notification>` (Task 3).
- Produces:
  - Construtor `NotificationService(NotificationRepository, NotificationSender, jakarta.validation.Validator)`
  - `NotificationService#processAppointmentEvent(AppointmentEvent): Notification` — idempotente; lança `IllegalArgumentException` (evento nulo), `ConstraintViolationException` (evento inválido) e `RuntimeException` (falha no envio).
  - `NotificationService#findByPatientId(Long): List<Notification>` — inalterado.
  - O `NotificationMessageListener` não muda: continua chamando `processAppointmentEvent`.

- [ ] **Step 1: Reescrever o teste unitário com os casos novos**

Substituir o conteúdo de `NotificationServiceTest.java`:

```java
package br.com.tech.challenge.notificationservice.service;

import br.com.tech.challenge.notificationservice.dto.AppointmentEvent;
import br.com.tech.challenge.notificationservice.dto.AppointmentEventStatus;
import br.com.tech.challenge.notificationservice.model.Notification;
import br.com.tech.challenge.notificationservice.model.NotificationStatus;
import br.com.tech.challenge.notificationservice.notification.NotificationSender;
import br.com.tech.challenge.notificationservice.repository.NotificationRepository;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationSender notificationSender;

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(
                notificationRepository,
                notificationSender,
                Validation.buildDefaultValidatorFactory().getValidator());
    }

    @Test
    void deveCriarNotificacaoComoPendingEDepoisMarcarComoSentAoProcessarEventoAgendado() {
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AtomicReference<NotificationStatus> statusNoMomentoDoEnvio = new AtomicReference<>();
        doAnswer(invocation -> {
            Notification n = invocation.getArgument(0);
            statusNoMomentoDoEnvio.set(n.getStatus());
            return null;
        }).when(notificationSender).send(any(Notification.class));

        Notification result = notificationService.processAppointmentEvent(evento(AppointmentEventStatus.SCHEDULED));

        verify(notificationRepository, times(2)).save(any(Notification.class));
        verify(notificationSender, times(1)).send(any(Notification.class));

        assertThat(statusNoMomentoDoEnvio.get()).isEqualTo(NotificationStatus.PENDING);
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(result.getAppointmentId()).isEqualTo(1L);
        assertThat(result.getPatientId()).isEqualTo(10L);
        assertThat(result.getMessage()).contains("agendada");
    }

    @ParameterizedTest
    @CsvSource({
            "SCHEDULED,   agendada",
            "RESCHEDULED, remarcada",
            "CANCELLED,   cancelada",
            "COMPLETED,   realizada"
    })
    void deveGerarMensagemDeAcordoComOStatusDoEvento(AppointmentEventStatus status, String trechoEsperado) {
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Notification result = notificationService.processAppointmentEvent(evento(status));

        assertThat(result.getMessage()).contains(trechoEsperado);
    }

    @Test
    void deveGravarEventIdEEventStatusNaNotificacao() {
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        AppointmentEvent evento = evento(AppointmentEventStatus.RESCHEDULED);

        Notification result = notificationService.processAppointmentEvent(evento);

        assertThat(result.getEventId()).isEqualTo(evento.eventId());
        assertThat(result.getEventStatus()).isEqualTo(AppointmentEventStatus.RESCHEDULED);
    }

    @Test
    void deveManterNotificacaoPendingEPropagarErroQuandoOEnvioFalha() {
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new IllegalStateException("provedor fora do ar"))
                .when(notificationSender).send(any(Notification.class));

        assertThatThrownBy(() -> notificationService.processAppointmentEvent(evento(AppointmentEventStatus.SCHEDULED)))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(IllegalStateException.class);

        // a propagacao rejeita a mensagem, que vai para a DLQ; a notificacao fica gravada como PENDING
        ArgumentCaptor<Notification> salva = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(1)).save(salva.capture());
        assertThat(salva.getValue().getStatus()).isEqualTo(NotificationStatus.PENDING);
    }

    @Test
    void deveRejeitarEventoNuloSemGravarNemEnviar() {
        assertThatThrownBy(() -> notificationService.processAppointmentEvent(null))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(notificationRepository, notificationSender);
    }

    @Test
    void deveRejeitarEventoForaDoContratoSemGravarNemEnviar() {
        AppointmentEvent semData = new AppointmentEvent(
                UUID.randomUUID(), AppointmentEventStatus.SCHEDULED, Instant.parse("2026-09-12T14:00:00Z"),
                1L, 10L, null, 5L, null, null, "Consulta de rotina");

        assertThatThrownBy(() -> notificationService.processAppointmentEvent(semData))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("appointmentDate");

        verifyNoInteractions(notificationRepository, notificationSender);
    }

    @Test
    void deveIgnorarReentregaDeEventoJaNotificado() {
        AppointmentEvent evento = evento(AppointmentEventStatus.SCHEDULED);
        Notification jaEnviada = notificacaoExistente(evento.eventId(), NotificationStatus.SENT);
        when(notificationRepository.findByEventId(evento.eventId())).thenReturn(Optional.of(jaEnviada));

        Notification result = notificationService.processAppointmentEvent(evento);

        assertThat(result).isSameAs(jaEnviada);
        verify(notificationRepository, never()).save(any(Notification.class));
        verifyNoInteractions(notificationSender);
    }

    @Test
    void deveReenviarNaMesmaLinhaQuandoANotificacaoFicouPending() {
        AppointmentEvent evento = evento(AppointmentEventStatus.SCHEDULED);
        Notification pendente = notificacaoExistente(evento.eventId(), NotificationStatus.PENDING);
        when(notificationRepository.findByEventId(evento.eventId())).thenReturn(Optional.of(pendente));
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Notification result = notificationService.processAppointmentEvent(evento);

        assertThat(result).isSameAs(pendente);
        assertThat(result.getId()).isEqualTo(7L);
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.SENT);
        verify(notificationSender, times(1)).send(pendente);
        verify(notificationRepository, times(1)).save(pendente);
    }

    @Test
    void deveDevolverALinhaExistenteQuandoAInsercaoColideComOutroConsumer() {
        AppointmentEvent evento = evento(AppointmentEventStatus.SCHEDULED);
        Notification gravadaPeloOutro = notificacaoExistente(evento.eventId(), NotificationStatus.SENT);
        when(notificationRepository.findByEventId(evento.eventId()))
                .thenReturn(Optional.empty(), Optional.of(gravadaPeloOutro));
        when(notificationRepository.save(any(Notification.class)))
                .thenThrow(new DataIntegrityViolationException("uk_notifications_event_id"));

        Notification result = notificationService.processAppointmentEvent(evento);

        assertThat(result).isSameAs(gravadaPeloOutro);
        verifyNoInteractions(notificationSender);
    }

    @Test
    void deveBuscarNotificacoesPorPaciente() {
        Long patientId = 10L;
        Notification notification = new Notification();
        notification.setPatientId(patientId);

        when(notificationRepository.findByPatientId(patientId))
                .thenReturn(List.of(notification));

        List<Notification> result = notificationService.findByPatientId(patientId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPatientId()).isEqualTo(patientId);
        verify(notificationRepository, times(1)).findByPatientId(patientId);
    }

    private static AppointmentEvent evento(AppointmentEventStatus status) {
        return new AppointmentEvent(
                UUID.randomUUID(),
                status,
                Instant.parse("2026-09-12T14:00:00Z"),
                1L,
                10L,
                null,
                5L,
                null,
                LocalDateTime.of(2030, 9, 10, 14, 30),
                "Consulta de rotina"
        );
    }

    private static Notification notificacaoExistente(UUID eventId, NotificationStatus status) {
        Notification notification = new Notification();
        notification.setId(7L);
        notification.setEventId(eventId);
        notification.setEventStatus(AppointmentEventStatus.SCHEDULED);
        notification.setAppointmentId(1L);
        notification.setPatientId(10L);
        notification.setMessage("Sua consulta foi agendada para 2030-09-10T14:30");
        notification.setCreatedAt(LocalDateTime.of(2026, 9, 12, 14, 0));
        notification.setStatus(status);
        return notification;
    }
}
```

- [ ] **Step 2: Escrever o teste de persistência que reproduz a corrida**

Criar `notification-service/src/test/java/br/com/tech/challenge/notificationservice/service/NotificationServicePersistenceTest.java`:

```java
package br.com.tech.challenge.notificationservice.service;

import br.com.tech.challenge.notificationservice.dto.AppointmentEvent;
import br.com.tech.challenge.notificationservice.dto.AppointmentEventStatus;
import br.com.tech.challenge.notificationservice.model.Notification;
import br.com.tech.challenge.notificationservice.notification.NotificationSender;
import br.com.tech.challenge.notificationservice.repository.NotificationRepository;
import br.com.tech.challenge.notificationservice.support.PostgresTestcontainers;
import jakarta.validation.Validation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Processamento contra um Postgres real. O teste com mock puro nao prova nada sobre a colisao de
 * event_id: sem banco, o UNIQUE nunca dispara de verdade.
 *
 * Com a deduplicacao funcionando, o segundo processamento encontraria a linha e nunca chegaria ao
 * banco. Por isso o repositorio delega ao real em tudo, menos no findByEventId: as duas checagens
 * de deduplicacao devolvem vazio, reproduzindo a corrida em que o outro consumer ainda nao tinha
 * commitado, e a releitura apos a colisao delega ao real.
 *
 * O @Transactional(NOT_SUPPORTED) desliga a transacao que o @DataJpaTest abre por padrao, para que
 * cada save rode na propria transacao, como em producao.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestcontainers.class, NotificationServicePersistenceTest.CorridaConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationServicePersistenceTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class CorridaConfig {

        @Bean
        NotificationSender notificationSender() {
            return mock(NotificationSender.class);
        }

        @Bean
        NotificationService notificationService(NotificationRepository real, NotificationSender sender) {
            NotificationRepository naCorrida =
                    mock(NotificationRepository.class, AdditionalAnswers.delegatesTo(real));
            // doReturn nao executa o metodo real durante o stub, ao contrario de when(...)
            doReturn(Optional.empty())
                    .doReturn(Optional.empty())
                    .doAnswer(invocation -> real.findByEventId(invocation.getArgument(0)))
                    .when(naCorrida).findByEventId(any());

            return new NotificationService(
                    naCorrida, sender, Validation.buildDefaultValidatorFactory().getValidator());
        }
    }

    @Autowired
    private NotificationRepository repository;

    @Autowired
    private NotificationService service;

    @Autowired
    private NotificationSender sender;

    @AfterEach
    void limpar() {
        repository.deleteAll();
    }

    @Test
    void devolveALinhaExistenteQuandoAInsercaoColideComAConstraintReal() {
        AppointmentEvent evento = new AppointmentEvent(
                UUID.randomUUID(), AppointmentEventStatus.SCHEDULED, Instant.parse("2026-09-12T14:00:00Z"),
                42L, 10L, "Maria Souza", 7L, "Dr. Joao Lima",
                LocalDateTime.of(2030, 9, 10, 14, 30), "Consulta de rotina");

        Notification primeira = service.processAppointmentEvent(evento);

        Notification[] segunda = new Notification[1];
        assertThatCode(() -> segunda[0] = service.processAppointmentEvent(evento))
                .doesNotThrowAnyException();

        assertThat(segunda[0].getId()).isEqualTo(primeira.getId());
        assertThat(repository.count()).isEqualTo(1);
        verify(sender, times(1)).send(any(Notification.class));
    }
}
```

- [ ] **Step 3: Rodar e ver falhar**

```bash
cd notification-service
./mvnw -B test -Dtest='NotificationServiceTest,NotificationServicePersistenceTest' 2>&1 | grep -E "COMPILATION ERROR|cannot be applied|BUILD" | head -4
```

Esperado: `COMPILATION ERROR` — `constructor NotificationService in class NotificationService cannot be applied to given types` (o construtor ainda não recebe `Validator`).

- [ ] **Step 4: Reescrever o `NotificationService`**

Substituir o conteúdo de `NotificationService.java`:

```java
package br.com.tech.challenge.notificationservice.service;

import br.com.tech.challenge.notificationservice.dto.AppointmentEvent;
import br.com.tech.challenge.notificationservice.model.Notification;
import br.com.tech.challenge.notificationservice.model.NotificationStatus;
import br.com.tech.challenge.notificationservice.notification.NotificationSender;
import br.com.tech.challenge.notificationservice.repository.NotificationRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationSender notificationSender;
    private final Validator validator;

    public NotificationService(
            NotificationRepository notificationRepository,
            NotificationSender notificationSender,
            Validator validator) {

        this.notificationRepository = notificationRepository;
        this.notificationSender = notificationSender;
        this.validator = validator;
    }

    /**
     * Processa um AppointmentEvent e devolve a unica notificacao associada ao seu eventId.
     *
     * Idempotente pelo eventId: uma reentrega do mesmo evento nao grava uma segunda linha. Se a
     * notificacao ja foi enviada, e devolvida sem reenvio; se ficou PENDING por uma falha anterior,
     * o envio e tentado de novo sobre a mesma linha.
     *
     * Sem @Transactional de proposito, pelo mesmo motivo do HistoryIngestionService: com uma
     * transacao no metodo, a violacao do UNIQUE(event_id) marcaria a transacao como rollback-only e
     * o commit lancaria UnexpectedRollbackException fora do catch, mandando para a DLQ um evento ja
     * gravado. Cada save roda na transacao do proprio repositorio.
     *
     * @throws IllegalArgumentException     se o evento for nulo
     * @throws ConstraintViolationException se o evento violar o contrato; a mensagem vai para a DLQ
     * @throws RuntimeException             se o envio falhar; a notificacao fica PENDING e a mensagem vai para a DLQ
     */
    public Notification processAppointmentEvent(AppointmentEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("AppointmentEvent nao pode ser nulo");
        }
        Set<ConstraintViolation<AppointmentEvent>> violacoes = validator.validate(event);
        if (!violacoes.isEmpty()) {
            throw new ConstraintViolationException(violacoes);
        }

        Optional<Notification> existente = notificationRepository.findByEventId(event.eventId());
        if (existente.isPresent()) {
            Notification notification = existente.get();
            if (notification.getStatus() == NotificationStatus.SENT) {
                log.warn("Evento {} ja notificado, ignorando reentrega", event.eventId());
                return notification;
            }
            log.warn("Evento {} com notificacao PENDING, tentando enviar de novo", event.eventId());
            return send(notification);
        }

        Notification saved;
        try {
            saved = notificationRepository.save(createNotification(event));
        } catch (DataIntegrityViolationException e) {
            // Corrida entre consumers processando a mesma reentrega: o UNIQUE(event_id) barrou.
            log.warn("Evento {} inserido concorrentemente, ignorando", event.eventId());
            return notificationRepository.findByEventId(event.eventId())
                    .orElseThrow(() -> e);
        }
        log.debug("Notificacao criada: id={}, appointmentId={}", saved.getId(), saved.getAppointmentId());

        return send(saved);
    }

    private Notification send(Notification notification) {
        try {
            notificationSender.send(notification);
        } catch (Exception e) {
            log.error("Erro ao enviar notificacao: id={}, appointmentId={}. Erro: {}",
                    notification.getId(), notification.getAppointmentId(), e.getMessage());
            // Mantem o status PENDING: um reprocessamento da DLQ tenta de novo sobre a mesma linha
            throw new RuntimeException("Falha ao enviar notificação", e);
        }
        notification.setStatus(NotificationStatus.SENT);
        log.info("Notificacao enviada: id={}, appointmentId={}", notification.getId(), notification.getAppointmentId());

        return notificationRepository.save(notification);
    }

    private Notification createNotification(AppointmentEvent event) {
        Notification notification = new Notification();

        notification.setEventId(event.eventId());
        notification.setEventStatus(event.eventStatus());
        notification.setAppointmentId(event.appointmentId());
        notification.setPatientId(event.patientId());
        notification.setMessage(createMessage(event));
        notification.setCreatedAt(LocalDateTime.now());
        notification.setStatus(NotificationStatus.PENDING);

        return notification;
    }

    private String createMessage(AppointmentEvent event) {
        return switch (event.eventStatus()) {
            case SCHEDULED ->
                    "Sua consulta foi agendada para " + event.appointmentDate();
            case RESCHEDULED ->
                    "Sua consulta foi remarcada para " + event.appointmentDate();
            case CANCELLED ->
                    "Sua consulta foi cancelada";
            case COMPLETED ->
                    "Sua consulta foi realizada";
        };
    }

    public List<Notification> findByPatientId(Long patientId) {
        return notificationRepository.findByPatientId(patientId);
    }
}
```

- [ ] **Step 5: Rodar os testes da task e ver passar**

```bash
./mvnw -B test -Dtest='NotificationServiceTest,NotificationServicePersistenceTest' 2>&1 | grep -E "Tests run:.*Skipped: [0-9]+$|BUILD" | tail -2
```

Esperado: `Tests run: 14, Failures: 0, Errors: 0` (13 unitários, contando os 4 do parametrizado, mais 1 de persistência), `BUILD SUCCESS`.

- [ ] **Step 6: Rodar a suíte completa**

```bash
./mvnw -B test 2>&1 | grep -E "Tests run:.*Skipped: [0-9]+$|BUILD" | tail -2
cd ..
```

Esperado: `Tests run: 17, Failures: 0, Errors: 0`, `BUILD SUCCESS` (14 + 2 de schema + 1 `contextLoads`). O `contextLoads` confirma que o Spring injeta o `Validator` no construtor.

- [ ] **Step 7: Commit**

```bash
git add notification-service
git commit -F - <<'MSG'
feat(notification): valida o evento e garante uma notificacao por eventId

O evento fora do contrato e rejeitado para a DLQ sem gravar nada. Uma
reentrega do mesmo eventId devolve a notificacao existente: se ja foi
enviada, nao reenvia; se ficou PENDING, tenta de novo na mesma linha.
A corrida entre consumers e barrada pelo UNIQUE(event_id), com teste
contra o Postgres real.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019PC7ahgX95xoVhyBKU3zFc
MSG
```

---

### Task 5: Testes de recebimento via RabbitMQ e de consulta

Os comportamentos já existem depois da Task 4; esta task comprova o caminho real — mensagem na exchange, listener, serviço, banco — e a rota REST. Os testes devem passar ao serem escritos. Se o caso de duplicidade ou o de evento sem `appointmentDate` falhar, a ligação entre listener e serviço está errada: não ajuste o teste, investigue.

**Files:**
- Modify: `notification-service/pom.xml` (Awaitility)
- Create: `notification-service/src/test/java/br/com/tech/challenge/notificationservice/messaging/NotificationMessageListenerIT.java`
- Create: `notification-service/src/test/java/br/com/tech/challenge/notificationservice/controller/NotificationControllerTest.java`

**Interfaces:**
- Consumes: `NotificationRepository#findByEventId`, `NotificationRepository#findByPatientId`, `Notification#getEventStatus`, `NotificationService#findByPatientId(Long)` (Tasks 3 e 4); propriedades `app.rabbitmq.exchange`, `app.rabbitmq.routing-key`, `app.rabbitmq.queue`.
- Produces: nada consumido por tasks seguintes.

- [ ] **Step 1: Adicionar o Awaitility**

```bash
cd notification-service
python3 - <<'PY'
p = "pom.xml"; t = open(p).read()
assert "<artifactId>awaitility</artifactId>" not in t
assert t.count("\t</dependencies>") == 1
dep = ("\t\t<dependency>\n\t\t\t<groupId>org.awaitility</groupId>\n"
       "\t\t\t<artifactId>awaitility</artifactId>\n\t\t\t<scope>test</scope>\n\t\t</dependency>\n")
open(p, "w").write(t.replace("\t</dependencies>", dep + "\t</dependencies>"))
print("ok")
PY
```

- [ ] **Step 2: Escrever o teste de recebimento**

Criar `src/test/java/br/com/tech/challenge/notificationservice/messaging/NotificationMessageListenerIT.java`:

```java
package br.com.tech.challenge.notificationservice.messaging;

import br.com.tech.challenge.notificationservice.dto.AppointmentEvent;
import br.com.tech.challenge.notificationservice.dto.AppointmentEventStatus;
import br.com.tech.challenge.notificationservice.model.Notification;
import br.com.tech.challenge.notificationservice.model.NotificationStatus;
import br.com.tech.challenge.notificationservice.repository.NotificationRepository;
import br.com.tech.challenge.notificationservice.support.PostgresTestcontainers;
import br.com.tech.challenge.notificationservice.support.RabbitTestcontainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Import({PostgresTestcontainers.class, RabbitTestcontainers.class})
class NotificationMessageListenerIT {

    private static final LocalDateTime DATA_CONSULTA = LocalDateTime.of(2030, 9, 10, 14, 30);

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private NotificationRepository repository;

    @Value("${app.rabbitmq.exchange}")
    private String exchange;

    @Value("${app.rabbitmq.routing-key}")
    private String routingKey;

    @Value("${app.rabbitmq.queue}")
    private String queue;

    @BeforeEach
    void limparBase() {
        repository.deleteAll();
        while (rabbitTemplate.receive(queue + ".dlq") != null) {
            // drena a DLQ para nao herdar mensagens de outro teste
        }
    }

    private AppointmentEvent evento(UUID eventId, AppointmentEventStatus status, long patientId) {
        return new AppointmentEvent(
                eventId, status, Instant.parse("2026-09-12T14:00:00Z"), 42L, patientId,
                "Maria Souza", 7L, "Dr. Joao Lima", DATA_CONSULTA, "Consulta de rotina");
    }

    @Test
    void criaNotificacaoEnviadaAoReceberEventoDaFila() {
        UUID eventId = UUID.randomUUID();

        rabbitTemplate.convertAndSend(exchange, routingKey, evento(eventId, AppointmentEventStatus.SCHEDULED, 10L));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Optional<Notification> gravada = repository.findByEventId(eventId);
            assertThat(gravada).isPresent();
            assertThat(gravada.get().getStatus()).isEqualTo(NotificationStatus.SENT);
            assertThat(gravada.get().getEventStatus()).isEqualTo(AppointmentEventStatus.SCHEDULED);
            assertThat(gravada.get().getPatientId()).isEqualTo(10L);
            assertThat(gravada.get().getMessage()).contains("agendada");
        });
    }

    @Test
    void naoDuplicaQuandoOMesmoEventoChegaDuasVezes() {
        AppointmentEvent mesmoEvento = evento(UUID.randomUUID(), AppointmentEventStatus.SCHEDULED, 11L);

        rabbitTemplate.convertAndSend(exchange, routingKey, mesmoEvento);
        rabbitTemplate.convertAndSend(exchange, routingKey, mesmoEvento);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(repository.findByPatientId(11L)).hasSize(1));
        // da tempo de a segunda entrega ser processada antes de concluir que nao duplicou
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(repository.findByPatientId(11L)).hasSize(1));
    }

    @Test
    void mandaParaDlqEventoComCampoDesconhecido() {
        enviaJsonCru("""
                {
                  "eventId": "%s",
                  "eventStatus": "SCHEDULED",
                  "occurredAt": "2026-09-12T14:00:00Z",
                  "appointmentId": 99,
                  "patientId": 12,
                  "doctorId": 7,
                  "appointmentDate": "2030-09-10T14:30:00",
                  "campoInesperado": true
                }
                """.formatted(UUID.randomUUID()));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(rabbitTemplate.receive(queue + ".dlq")).isNotNull());

        assertThat(repository.findByPatientId(12L)).isEmpty();
    }

    @Test
    void mandaParaDlqEventoSemAppointmentDate() {
        enviaJsonCru("""
                {
                  "eventId": "%s",
                  "eventStatus": "CANCELLED",
                  "occurredAt": "2026-09-12T14:00:00Z",
                  "appointmentId": 77,
                  "patientId": 13,
                  "doctorId": 7
                }
                """.formatted(UUID.randomUUID()));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(rabbitTemplate.receive(queue + ".dlq")).isNotNull());

        assertThat(repository.findByPatientId(13L)).isEmpty();
    }

    private void enviaJsonCru(String payload) {
        Message mensagem = MessageBuilder
                .withBody(payload.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();

        rabbitTemplate.send(exchange, routingKey, mensagem);
    }
}
```

- [ ] **Step 3: Escrever o teste da rota de consulta**

Criar `src/test/java/br/com/tech/challenge/notificationservice/controller/NotificationControllerTest.java`:

```java
package br.com.tech.challenge.notificationservice.controller;

import br.com.tech.challenge.notificationservice.dto.AppointmentEventStatus;
import br.com.tech.challenge.notificationservice.model.Notification;
import br.com.tech.challenge.notificationservice.model.NotificationStatus;
import br.com.tech.challenge.notificationservice.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;

    @Test
    void listaAsNotificacoesDoPaciente() throws Exception {
        Notification notification = new Notification();
        notification.setId(1L);
        notification.setEventId(UUID.fromString("8f14e45f-ceea-467a-9f4b-1d2c3e4f5a6b"));
        notification.setEventStatus(AppointmentEventStatus.SCHEDULED);
        notification.setAppointmentId(42L);
        notification.setPatientId(10L);
        notification.setMessage("Sua consulta foi agendada para 2030-09-10T14:30");
        notification.setCreatedAt(LocalDateTime.of(2026, 9, 12, 14, 0));
        notification.setStatus(NotificationStatus.SENT);
        when(notificationService.findByPatientId(10L)).thenReturn(List.of(notification));

        mockMvc.perform(get("/notifications/patient/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].patientId").value(10))
                .andExpect(jsonPath("$[0].appointmentId").value(42))
                .andExpect(jsonPath("$[0].eventStatus").value("SCHEDULED"))
                .andExpect(jsonPath("$[0].status").value("SENT"));
    }

    @Test
    void devolveListaVaziaParaPacienteSemNotificacoes() throws Exception {
        when(notificationService.findByPatientId(99L)).thenReturn(List.of());

        mockMvc.perform(get("/notifications/patient/99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
```

- [ ] **Step 4: Rodar os testes da task**

```bash
./mvnw -B test -Dtest='NotificationMessageListenerIT,NotificationControllerTest' 2>&1 | grep -E "Tests run:.*Skipped: [0-9]+$|<<< FAIL|BUILD" | tail -4
```

Esperado: `Tests run: 6, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 5: Rodar a suíte completa**

```bash
./mvnw -B test 2>&1 | grep -E "Tests run:.*Skipped: [0-9]+$|BUILD" | tail -2
cd ..
```

Esperado: `Tests run: 23, Failures: 0, Errors: 0`, `BUILD SUCCESS` (17 + 4 de integração + 2 do controller). Linhas `ConstraintViolationException` e `UnrecognizedPropertyException` no log são esperadas: vêm dos testes que mandam payload inválido para a DLQ.

- [ ] **Step 6: Commit**

```bash
git add notification-service
git commit -F - <<'MSG'
test(notification): cobre recebimento via RabbitMQ, DLQ e consulta

Teste de integracao com RabbitMQ e Postgres reais: evento na exchange vira
notificacao enviada, o mesmo evento duas vezes gera uma unica linha, e
payload fora do contrato vai para a DLQ sem gravar nada. Teste da rota
GET /notifications/patient/{patientId}.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019PC7ahgX95xoVhyBKU3zFc
MSG
```

---

### Task 6: `make smoke` comprova a notificação

**Files:**
- Modify: `scripts/smoke-test.sh` (reescrita)
- Modify: `Makefile` (descrição do alvo `smoke`)
- Modify: `README.md` (linha do `make smoke` em "Comandos" e texto da seção "Atalho")

**Interfaces:**
- Consumes: stack com o código das Tasks 3–5; rotas `GET /actuator/health` (8080, 8081, 8082), `POST /appointments` (8080), `POST /graphql` (8081), `GET /notifications/patient/{patientId}` (8082).
- Produces: `make smoke` com 4 etapas, usado nas Tasks 7 e 8. Variáveis opcionais: `APPOINTMENT_URL`, `HISTORY_URL`, `NOTIFICATION_URL`, `TIMEOUT_SEGUNDOS`.

- [ ] **Step 1: Reescrever o script**

Substituir o conteúdo de `scripts/smoke-test.sh`:

```bash
#!/usr/bin/env bash
# Teste ponta a ponta: appointment-service -> RabbitMQ -> history-service e notification-service.
# Cria um agendamento e espera o evento aparecer no historico e virar uma notificacao enviada.
set -euo pipefail

APPOINTMENT_URL="${APPOINTMENT_URL:-http://localhost:8080}"
HISTORY_URL="${HISTORY_URL:-http://localhost:8081}"
NOTIFICATION_URL="${NOTIFICATION_URL:-http://localhost:8082}"
TIMEOUT_SEGUNDOS="${TIMEOUT_SEGUNDOS:-60}"

# patientId aleatorio para o teste ser repetivel sem limpar o banco
PATIENT_ID=$(( (RANDOM % 900000) + 100000 ))
APPOINTMENT_DATE=$(date -u -d '+30 days' '+%Y-%m-%dT%H:%M:%S' 2>/dev/null || date -u -v+30d '+%Y-%m-%dT%H:%M:%S')

echo "==> 1/4 aguardando os servicos responderem"
for url in "$APPOINTMENT_URL/actuator/health" "$HISTORY_URL/actuator/health" "$NOTIFICATION_URL/actuator/health"; do
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

echo "==> 2/4 criando agendamento (patientId=$PATIENT_ID)"
resposta=$(curl -s -w '\n%{http_code}' -X POST "$APPOINTMENT_URL/appointments" \
  -H 'Content-Type: application/json' \
  -d "{\"patientId\":$PATIENT_ID,\"doctorId\":7,\"appointmentDate\":\"$APPOINTMENT_DATE\",\"description\":\"Smoke test\"}") \
  || { echo "FALHA: nao consegui falar com $APPOINTMENT_URL" >&2; exit 1; }
http_code=$(echo "$resposta" | tail -n1)
corpo=$(echo "$resposta" | sed '$d')
if [ "$http_code" != "201" ]; then
  echo "FALHA: POST /appointments retornou $http_code" >&2
  echo "Resposta: $corpo" >&2
  exit 1
fi
echo "    resposta: $corpo"

echo "==> 3/4 aguardando o evento chegar no history-service via RabbitMQ"
consulta="{\"query\":\"{ patientHistory(patientId: \\\"$PATIENT_ID\\\") { appointmentId eventStatus description } }\"}"
fim=$(( SECONDS + TIMEOUT_SEGUNDOS ))
while true; do
  historico=$(curl -sf -X POST "$HISTORY_URL/graphql" \
    -H 'Content-Type: application/json' -d "$consulta" || echo '')
  if echo "$historico" | grep -q '"eventStatus":"SCHEDULED"'; then
    echo "    OK evento recebido: $historico"
    break
  fi
  if (( SECONDS >= fim )); then
    echo "FALHA: o evento nao chegou ao history-service em ${TIMEOUT_SEGUNDOS}s" >&2
    echo "Ultima resposta do GraphQL: $historico" >&2
    echo "Investigue: docker compose logs history-app | grep -i rabbit" >&2
    exit 1
  fi
  sleep 2
done

echo "==> 4/4 aguardando a notificacao ser enviada pelo notification-service"
fim=$(( SECONDS + TIMEOUT_SEGUNDOS ))
while true; do
  notificacoes=$(curl -sf "$NOTIFICATION_URL/notifications/patient/$PATIENT_ID" || echo '')
  if echo "$notificacoes" | grep -q '"status":"SENT"'; then
    echo "    OK notificacao enviada: $notificacoes"
    echo
    echo "SUCESSO: appointment -> RabbitMQ -> history e notification funcionando."
    exit 0
  fi
  if (( SECONDS >= fim )); then
    echo "FALHA: a notificacao nao foi enviada em ${TIMEOUT_SEGUNDOS}s" >&2
    echo "Ultima resposta: $notificacoes" >&2
    echo "Investigue: docker compose logs notification-app | grep -iE 'rabbit|notifica|flyway'" >&2
    exit 1
  fi
  sleep 2
done
```

```bash
bash -n scripts/smoke-test.sh && echo "sintaxe ok"
```

- [ ] **Step 2: Atualizar Makefile e README**

```bash
python3 - <<'PY'
def troca(p, velho, novo):
    t = open(p, encoding="utf-8").read()
    assert t.count(velho) == 1, f"{p}: nao encontrado exatamente uma vez: {velho!r}"
    open(p, "w", encoding="utf-8").write(t.replace(velho, novo))
    print(f"ok  {p}")

troca("Makefile",
      "smoke: ## Teste ponta a ponta: appointment -> RabbitMQ -> history",
      "smoke: ## Teste ponta a ponta: appointment -> RabbitMQ -> history e notification")
troca("README.md",
      "| `make smoke` | teste ponta a ponta appointment → RabbitMQ → history |",
      "| `make smoke` | teste ponta a ponta appointment → RabbitMQ → history e notification |")
troca("README.md",
      "Faz exatamente os passos 1 a 3 e falha com diagnóstico se a integração estiver quebrada.",
      "Faz os passos 1, 2, 3 e 5 e falha com diagnóstico se a integração estiver quebrada.")
PY
```

- [ ] **Step 3: Subir uma stack isolada com o código novo**

Pare a stack do mantenedor **preservando os volumes** (a Task 7 precisa deles) e suba uma stack com nome próprio:

```bash
make down
export COMPOSE_PROJECT_NAME=g65task6
make setup
make build
for i in $(seq 1 60); do
  n=$(docker ps --filter label=com.docker.compose.project=$COMPOSE_PROJECT_NAME --format '{{.Status}}' | grep -c "(healthy)")
  [ "$n" = 7 ] && break; sleep 5
done
echo "healthy: $n/7"
```

Esperado: `healthy: 7/7`.

- [ ] **Step 4: Rodar o smoke test e ver passar**

```bash
make smoke
```

Esperado: as quatro etapas `OK` e `SUCESSO: appointment -> RabbitMQ -> history e notification funcionando.`

- [ ] **Step 5: Confirmar que o notification agora é verificado**

Com o notification-app parado, o script falha já na etapa 1, no health da 8082 — o script antigo não checava essa porta:

```bash
docker compose stop notification-app
TIMEOUT_SEGUNDOS=10 make smoke; echo "exit=$?"
docker compose start notification-app
```

Esperado: `FALHA: http://localhost:8082/actuator/health nao ficou UP em 10s` e `exit` diferente de 0.

- [ ] **Step 6: Derrubar a stack isolada**

```bash
docker compose down -v --rmi local
unset COMPOSE_PROJECT_NAME
docker volume ls -q --filter label=com.docker.compose.project=grupo65   # os volumes do mantenedor continuam
```

- [ ] **Step 7: Commit**

```bash
git add scripts/smoke-test.sh Makefile README.md
git commit -F - <<'MSG'
test: make smoke comprova tambem a notificacao enviada

O smoke test passa a esperar o health do notification-service e uma
notificacao SENT para o paciente criado, alem do evento no history.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019PC7ahgX95xoVhyBKU3zFc
MSG
```

---

### Task 7: Documentação e guia de migração validado num ambiente existente

**Files:**
- Modify: `README.md` (guia "Atualizando de uma versão anterior", tabela "Problemas comuns na instalação", nota de segurança)
- Modify: `notification-service/README.md` (processamento e idempotência, testes, segurança, problemas comuns)

**Interfaces:**
- Consumes: volumes `grupo65_*` do mantenedor; `make smoke` da Task 6.
- Produces: guia de migração executável, usado na Task 8 como referência.

- [ ] **Step 1: Verificar a precondição — tabela antiga no volume do notification**

```bash
unset COMPOSE_PROJECT_NAME
docker compose up -d notification-postgres
sleep 5
docker compose exec -T notification-postgres psql -U postgres -d notification_db -Atc \
  "SELECT to_regclass('public.notifications') IS NOT NULL,
          to_regclass('public.flyway_schema_history') IS NOT NULL,
          EXISTS (SELECT 1 FROM information_schema.columns
                  WHERE table_name = 'notifications' AND column_name = 'event_id')"
```

Esperado: `t|f|f` — a tabela existe, sem histórico do Flyway e sem `event_id`.

Se a saída for outra (o volume foi recriado desde a versão anterior), recrie o estado da versão anterior com o schema que o Hibernate gerava, e confira de novo:

```bash
docker compose rm -sf notification-postgres
docker volume rm grupo65_notification-postgres-data
docker compose up -d notification-postgres
sleep 5
docker compose exec -T notification-postgres psql -U postgres -d notification_db -c "
CREATE TABLE notifications (
    id             BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    appointment_id BIGINT,
    created_at     TIMESTAMP(6),
    message        VARCHAR(255),
    patient_id     BIGINT,
    status         VARCHAR(255) CHECK (status IN ('PENDING', 'SENT'))
);"
```

- [ ] **Step 2: Reproduzir a falha sem o passo de migração**

```bash
make setup
make build
sleep 60
docker compose ps notification-app --format '{{.Status}}'
docker compose logs --no-color notification-app 2>&1 | grep -m1 -iE "already exists"
```

Esperado: o `notification-app` não fica `healthy` (reiniciando ou `unhealthy`) e o log contém `relation "notifications" already exists`. **Anote a linha exata**: ela vai para a tabela de problemas no Step 3. Se a mensagem for diferente, use a observada.

- [ ] **Step 3: Atualizar o README da raiz**

```bash
python3 - <<'PY'
p = "README.md"; t = open(p, encoding="utf-8").read()
def troca(velho, novo):
    global t
    assert t.count(velho) == 1, f"nao encontrado exatamente uma vez: {velho[:80]!r}"
    t = t.replace(velho, novo)

troca("""   escutar uma exchange que ninguém mais usa; um `notificationservice/.env` antigo ocupa a
   porta 5433, que é do appointment.""",
"""   escutar uma exchange que ninguém mais usa; um `.env` antigo do notification ocupa a
   porta 5433, que é do appointment.""")

troca("""3. **Filas antigas no volume do RabbitMQ.** A `history.queue` criada pela versão anterior tem
   outra configuração de dead letter, e o broker recusa a nova com `PRECONDITION_FAILED`.""",
"""3. **Filas antigas no volume do RabbitMQ.** A `history.queue` criada pela versão anterior tem
   outra configuração de dead letter, e o broker recusa a nova com `PRECONDITION_FAILED`.
4. **Tabela antiga do notification.** Versões anteriores deixavam o Hibernate criar a tabela
   `notifications`; agora ela vem de uma migration Flyway, que falha se a tabela já existir.
   Os dados são notificações de desenvolvimento, então o volume do banco do notification é
   recriado.
5. **Pasta antiga.** O serviço foi renomeado de `notificationservice` para
   `notification-service`; depois do `git pull`, a pasta antiga sobra só com arquivos ignorados
   (`.env`, `target/`).""")

troca("""rm -f appointment-service/.env history-service/.env notification-service/.env
make setup
make build                   # recompila e recria as aplicações com os .env novos""",
"""rm -f appointment-service/.env history-service/.env notification-service/.env
git ls-files notificationservice    # deve sair vazio: a pasta antiga só tem arquivos ignorados
rm -rf notificationservice
make setup
docker compose rm -sf notification-app notification-postgres
docker volume rm grupo65_notification-postgres-data
make build                   # recompila e recria as aplicações com os .env novos""")

troca("""| alterações de código não aparecem depois de `git pull` | `make up` reaproveita as imagens já construídas | `make build` |""",
"""| alterações de código não aparecem depois de `git pull` | `make up` reaproveita as imagens já construídas | `make build` |
| notification-app não sobe e o log mostra `relation "notifications" already exists` | tabela criada pelo Hibernate numa versão anterior, antes da migration Flyway | [Atualizando de uma versão anterior](#atualizando-de-uma-versão-anterior) |""")

troca("""(`make ps`) antes de testar.""",
"""(`make ps`) antes de testar.

**Segurança:** o appointment-service, o history-service e o notification-service ainda não
exigem autenticação nesta branch. Na integração do auth-service, o notification-service deve
receber o mesmo `SecurityConfig` de resource server JWT usado nos outros dois: health público e
demais rotas autenticadas.""")
open(p, "w", encoding="utf-8").write(t)
print("ok")
PY
```

Se a mensagem anotada no Step 2 for diferente de `relation "notifications" already exists`, ajuste a linha da tabela com o texto observado antes de seguir.

- [ ] **Step 4: Atualizar o README do notification-service**

```bash
python3 - <<'PY'
p = "notification-service/README.md"; t = open(p, encoding="utf-8").read()
def troca(velho, novo):
    global t
    assert t.count(velho) == 1, f"nao encontrado exatamente uma vez: {velho[:80]!r}"
    t = t.replace(velho, novo)

troca("""## Rodando junto com os outros serviços""",
"""## Processamento e idempotência

1. **Validação.** O evento é validado contra o contrato (`@NotNull` no `AppointmentEvent`). Um
   evento inválido — por exemplo, sem `appointmentDate` — é rejeitado e vai para a
   `notification.queue.dlq`, sem gravar nada.
2. **Idempotência pelo `eventId`.** Cada evento gera no máximo uma notificação. Se o mesmo
   `eventId` chegar de novo (reentrega do RabbitMQ):
   - notificação já `SENT`: é devolvida sem reenviar;
   - notificação `PENDING`, de um envio que falhou: o envio é tentado de novo sobre a mesma linha.
   A garantia final é a constraint `uk_notifications_event_id UNIQUE (event_id)` no banco, que
   barra dois consumidores gravando o mesmo evento ao mesmo tempo.
3. **Envio.** A notificação nasce `PENDING`, é enviada por log (`LEMBRETE ENVIADO`) e fica `SENT`.
   Se o envio falhar, continua `PENDING` e a mensagem vai para a DLQ.

O schema é criado pela migration Flyway `V1__create_notifications.sql`; o Hibernate só valida
(`ddl-auto=validate`).

## Rodando junto com os outros serviços""")

troca("""Requer Docker: o teste que sobe o contexto usa Testcontainers.

- `NotificationApplicationTests` — sobe o contexto Spring com Postgres e RabbitMQ em containers.
- `NotificationServiceTest` — testes unitários (Mockito): notificação criada como `PENDING` e
  marcada como `SENT` após o envio, mensagem para cada `eventStatus`, erro no envio mantendo
  `PENDING`, e busca por paciente.""",
"""Requer Docker: os testes de integração usam Testcontainers.

| Teste | O que prova |
|---|---|
| `NotificationMessageListenerIT` | evento publicado na exchange vira notificação `SENT`; o mesmo evento duas vezes gera uma única linha; payload fora do contrato vai para a DLQ sem gravar nada |
| `NotificationServiceTest` | mensagem para cada `eventStatus`, `PENDING` → `SENT`, falha no envio, evento nulo e inválido rejeitados, reentrega ignorada, `PENDING` reenviado na mesma linha, colisão entre consumidores |
| `NotificationServicePersistenceTest` | colisão de `event_id` contra o Postgres real resulta em uma única linha |
| `NotificationSchemaTest` | a migration cria todas as colunas e a constraint única |
| `NotificationControllerTest` | `GET /notifications/patient/{patientId}` |
| `NotificationApplicationTests` | o contexto sobe com Postgres e RabbitMQ em containers |

## Segurança

Este serviço ainda não exige autenticação. Na integração do auth-service, deve receber o mesmo
`SecurityConfig` de resource server JWT usado no appointment-service e no history-service: health
público e demais rotas autenticadas.""")

troca("""| mensagem na `notification.queue.dlq` | payload fora do contrato | comparar com o formato acima |""",
"""| mensagem na `notification.queue.dlq` | payload fora do contrato | comparar com o formato acima |
| serviço não sobe e o log mostra `relation "notifications" already exists` | tabela criada pelo Hibernate numa versão anterior | ver [Atualizando de uma versão anterior](../README.md#atualizando-de-uma-versão-anterior) |""")
open(p, "w", encoding="utf-8").write(t)
print("ok")
PY
```

- [ ] **Step 5: Executar o guia do README ao pé da letra**

```bash
BLOCO=$(python3 - <<'PY'
import re
t = open("README.md", encoding="utf-8").read()
sec = next(x for x in re.split(r"^### ", t, flags=re.M) if x.startswith("Atualizando de uma versão anterior"))
print(re.findall(r"```bash\n(.*?)```", sec, flags=re.S)[0])
PY
)
echo "$BLOCO"
bash -c "$BLOCO"; echo "exit=$?"
```

Esperado: `exit=0` e, no fim, `SUCESSO: appointment -> RabbitMQ -> history e notification funcionando.`

- [ ] **Step 6: Conferir o estado migrado**

```bash
for i in $(seq 1 30); do n=$(docker compose ps --format '{{.Status}}' | grep -c "(healthy)"); [ "$n" = 7 ] && break; sleep 5; done
echo "healthy: $n/7"
docker compose exec -T notification-postgres psql -U postgres -d notification_db -Atc \
  "SELECT version, success FROM flyway_schema_history WHERE version = '1'"
docker compose logs --no-color --since 5m notification-app 2>&1 | grep -c "already exists"
```

Esperado: `healthy: 7/7`; `1|t`; contagem `0`.

- [ ] **Step 7: Idempotência ponta a ponta**

```bash
PID=$(( (RANDOM % 90000) + 950000 ))
EVT=$(cat /proc/sys/kernel/random/uuid)
PAYLOAD="{\\\"eventId\\\":\\\"$EVT\\\",\\\"eventStatus\\\":\\\"SCHEDULED\\\",\\\"occurredAt\\\":\\\"2026-09-13T10:00:00Z\\\",\\\"appointmentId\\\":900,\\\"patientId\\\":$PID,\\\"doctorId\\\":7,\\\"appointmentDate\\\":\\\"2030-09-10T14:30:00\\\",\\\"description\\\":\\\"idempotencia\\\"}"
for i in 1 2; do
  curl -s -u guest:guest -H "content-type:application/json" -X POST \
    -d "{\"properties\":{\"content_type\":\"application/json\"},\"routing_key\":\"notification.created\",\"payload\":\"$PAYLOAD\",\"payload_encoding\":\"string\"}" \
    http://localhost:15672/api/exchanges/%2F/appointment.exchange/publish; echo
done
sleep 5
curl -s http://localhost:8082/notifications/patient/$PID | python3 -c "import sys,json;print('notificacoes:', len(json.load(sys.stdin)))"
docker compose exec -T notification-postgres psql -U postgres -d notification_db -Atc \
  "SELECT count(*) FROM notifications WHERE event_id = '$EVT'"
```

Esperado: duas respostas `{"routed":true}`; `notificacoes: 1`; contagem `1`.

- [ ] **Step 8: Evento inválido vai para a DLQ sem gravar**

```bash
PID=$(( (RANDOM % 90000) + 960000 ))
antes=$(docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages | awk '$1=="notification.queue.dlq"{print $2}')
PAYLOAD="{\\\"eventId\\\":\\\"$(cat /proc/sys/kernel/random/uuid)\\\",\\\"eventStatus\\\":\\\"SCHEDULED\\\",\\\"occurredAt\\\":\\\"2026-09-13T10:00:00Z\\\",\\\"appointmentId\\\":901,\\\"patientId\\\":$PID,\\\"doctorId\\\":7}"
curl -s -u guest:guest -H "content-type:application/json" -X POST \
  -d "{\"properties\":{\"content_type\":\"application/json\"},\"routing_key\":\"notification.created\",\"payload\":\"$PAYLOAD\",\"payload_encoding\":\"string\"}" \
  http://localhost:15672/api/exchanges/%2F/appointment.exchange/publish; echo
sleep 5
depois=$(docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages | awk '$1=="notification.queue.dlq"{print $2}')
echo "DLQ: $antes -> $depois"
curl -s http://localhost:8082/notifications/patient/$PID; echo
```

Esperado: `{"routed":true}`; a DLQ aumenta em 1; a consulta devolve `[]`.

- [ ] **Step 9: Commit**

```bash
git add README.md notification-service/README.md
git commit -F - <<'MSG'
docs: guia de migracao e documentacao do notification-service

O guia de atualizacao passa a apagar a pasta antiga notificationservice e
a recriar o volume do banco do notification, cuja tabela era criada pelo
Hibernate e agora vem de migration Flyway. Validado num ambiente com a
tabela antiga: sem o passo o servico nao sobe; com ele, sobe, deduplica
por eventId e manda evento invalido para a DLQ.

O README do servico documenta validacao, idempotencia, migration, testes
e o ponto de integracao de seguranca.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019PC7ahgX95xoVhyBKU3zFc
MSG
```

---

### Task 8: Validação final num clone limpo e PR

**Files:**
- Nenhum arquivo alterado; esta task valida e integra.

**Interfaces:**
- Consumes: branch com as Tasks 1–7.
- Produces: branch publicada e PR aberto para a `main`.

- [ ] **Step 1: Suítes e estrutura**

```bash
for s in appointment-service history-service notification-service; do
  echo "== $s"; (cd $s && ./mvnw -B test) 2>&1 | grep -E "Tests run:.*Skipped: [0-9]+$|BUILD" | tail -2
done
git ls-files notificationservice | wc -l
git status --short
```

Esperado: appointment `13`, history `57`, notification `23` testes, todos `Failures: 0, Errors: 0` e `BUILD SUCCESS`; `0` arquivos rastreados em `notificationservice`; árvore limpa.

- [ ] **Step 2: Clone limpo, com projeto Compose isolado**

```bash
make down
REPO=$(pwd); V=$(mktemp -d)/clone
git clone -q --branch feat/notificationService "$REPO" "$V" && cd "$V"
export COMPOSE_PROJECT_NAME=g65final
make setup
make up
for i in $(seq 1 72); do
  n=$(docker ps --filter label=com.docker.compose.project=$COMPOSE_PROJECT_NAME --format '{{.Status}}' | grep -c "(healthy)")
  [ "$n" = 7 ] && break; sleep 5
done
echo "healthy: $n/7"
make smoke
npx --yes newman@6 run docs/postman/tech-challenge-grupo65.postman_collection.json --delay-request 300 \
  | grep -E "│\s+(requests|assertions)"
```

Esperado: `make setup` cria 5 `.env`, entre eles `notification-service/.env`; `healthy: 7/7`; `SUCESSO: appointment -> RabbitMQ -> history e notification funcionando.`; collection com `failed` igual a `0` em requests e assertions.

- [ ] **Step 3: Limpar o ambiente de validação**

```bash
docker compose down -v --rmi local
unset COMPOSE_PROJECT_NAME
cd "$REPO" && rm -rf "$V"
make up
```

- [ ] **Step 4: Confirmar com o mantenedor antes de publicar**

Push e PR são ações externas. Apresente o resumo (tasks concluídas, contagem de testes, resultado do smoke e da collection) e **aguarde confirmação explícita** antes do Step 5.

- [ ] **Step 5: Push e PR**

```bash
git push origin feat/notificationService
```

Abrir o PR de `feat/notificationService` para `main` — pelo GitHub (`https://github.com/LucasPavao/tech_challenger_3_grupo65/compare/main...feat/notificationService`) ou pela ferramenta de PR disponível — com:

- **Título:** `Feat/notification service: idempotencia, validacao, migration e testes`
- **Descrição:**

```markdown
## O que muda

- Traz a `main` (PR #8) para a branch da Pessoa 5 e renomeia o serviço para `notification-service/`.
- Valida o `AppointmentEvent`: evento fora do contrato vai para a DLQ sem gravar nada.
- Idempotência pelo `eventId`: cada evento gera no máximo uma notificação, com `UNIQUE(event_id)` no banco.
- Schema versionado com Flyway (`V1__create_notifications.sql`) e `ddl-auto=validate`.
- Testes de recebimento via RabbitMQ, DLQ, persistência, schema e consulta.
- `make smoke` comprova também a notificação enviada.

## Critérios da Pessoa 5 (plano de divisão do projeto)

Consumer do RabbitMQ, recebimento do `AppointmentEvent`, processamento de criação e edição, persistência e envio simulado por log — com testes de recebimento, criação e processamento.

## Quem já tem o ambiente

Seguir "Atualizando de uma versão anterior" no README: a pasta antiga `notificationservice/` e o volume do banco do notification precisam ser recriados.

## Fora deste PR

Autenticação no notification-service (na integração do auth-service), lembrete agendado e envio real.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_019PC7ahgX95xoVhyBKU3zFc
```
