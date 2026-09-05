# History Service — Ingestão de AppointmentEvent (Fase 1) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Consumir `AppointmentEvent` do RabbitMQ e gravar cada evento como uma linha nova e imutável em `medical_history`.

**Architecture:** Append-only. O listener desserializa o evento em um `record` validado, o service mapeia para a entidade e insere; nenhum `UPDATE` existe no código. Idempotência vem de `UNIQUE(event_id)` no banco — uma reentrega do RabbitMQ colide, é logada e descartada com ACK. Falha de conversão ou validação vai para a DLQ já declarada.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring AMQP, Spring Data JPA, Flyway, PostgreSQL 16, Testcontainers 2.0.5, JUnit 5 + Mockito + Awaitility.

**Spec:** `docs/superpowers/specs/2026-08-30-history-service-ingestion-design.md`

## Global Constraints

- Pacote base: `br.com.tech.challenge.historyservice`.
- **Spring Boot 4.1.0 reorganizou pacotes de teste.** Use exatamente: `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`, `org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase`, `org.springframework.boot.test.autoconfigure.json.JsonTest`, `org.springframework.boot.test.json.JacksonTester`, `org.springframework.boot.testcontainers.service.connection.ServiceConnection`. Os pacotes que aparecem na maior parte da documentação online (Boot 3.x) **não existem** aqui.
- **Testcontainers 2.0.5.** Artefatos são `org.testcontainers:testcontainers-postgresql` / `testcontainers-rabbitmq` / `testcontainers-junit-jupiter` (prefixo `testcontainers-`), e as classes ficam em `org.testcontainers.postgresql.PostgreSQLContainer` e `org.testcontainers.rabbitmq.RabbitMQContainer`. Os coordenadas 1.x (`org.testcontainers:postgresql`) **não resolvem** na 2.0.5.
- Versões de dependência vêm do parent `spring-boot-starter-parent:4.1.0` — **nunca escreva `<version>`** nas dependências novas.
- Imagens Docker dos testes: `postgres:16-alpine` e `rabbitmq:4-management-alpine` (as mesmas do `docker-compose.yml`).
- A entidade é imutável: sem setters, sem `@Data`, `@Column(updatable = false)` em todas as colunas.
- Nenhum relacionamento JPA (`@ManyToOne`/`@OneToMany`) — apenas IDs escalares.
- Docker precisa estar rodando para os testes. Verifique com `docker info` antes de começar.

---

### Task 1: Fundação — dependências, schema e suporte a Testcontainers

Corrige a migration quebrada (vírgula sobrando em `V1__create_medical_history_table.sql:8` impede o Flyway de rodar), desliga o `ddl-auto` que briga com o Flyway, e monta o suporte de Testcontainers que todas as tarefas seguintes usam.

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.properties:15`
- Modify: `src/main/resources/db/migration/V1__create_medical_history_table.sql` (reescrita completa — nunca aplicou com sucesso, então não precisa de uma `V2`)
- Create: `src/test/java/br/com/tech/challenge/historyservice/support/PostgresTestcontainers.java`
- Test: `src/test/java/br/com/tech/challenge/historyservice/MedicalHistorySchemaTest.java`

**Interfaces:**
- Consumes: nada.
- Produces: tabela `medical_history` com as colunas listadas abaixo; classe `PostgresTestcontainers` (`@TestConfiguration`) importável via `@Import(PostgresTestcontainers.class)` pelas Tasks 3 e 5.

- [ ] **Step 1: Adicionar as dependências ao `pom.xml`**

Dentro de `<dependencies>`, adicione depois do bloco `spring-boot-starter-actuator`:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-validation</artifactId>
		</dependency>
```

E no final da lista de dependências (junto das outras `<scope>test</scope>`):

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-testcontainers</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers-junit-jupiter</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers-postgresql</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers-rabbitmq</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.awaitility</groupId>
			<artifactId>awaitility</artifactId>
			<scope>test</scope>
		</dependency>
```

- [ ] **Step 2: Verificar que as dependências resolvem**

Run: `./mvnw -q dependency:resolve`
Expected: termina sem `ERROR`. Se algum `testcontainers-*` não resolver, confirme que você usou o prefixo `testcontainers-` no `artifactId` — as coordenadas 1.x não existem na 2.0.5.

- [ ] **Step 3: Desligar o `ddl-auto`**

Em `src/main/resources/application.properties`, troque a linha `spring.jpa.hibernate.ddl-auto=update` por:

```properties
spring.jpa.hibernate.ddl-auto=none
```

O Flyway é o dono do schema. Com `update` o Hibernate cria colunas por conta própria e mascara erros de migration.

- [ ] **Step 4: Criar a configuração de Testcontainers do Postgres**

Crie `src/test/java/br/com/tech/challenge/historyservice/support/PostgresTestcontainers.java`:

```java
package br.com.tech.challenge.historyservice.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainers {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
    }
}
```

`@ServiceConnection` faz o Spring apontar o datasource para o container automaticamente — não escreva `spring.datasource.url` nos testes.

- [ ] **Step 5: Escrever o teste do schema (vai falhar)**

Crie `src/test/java/br/com/tech/challenge/historyservice/MedicalHistorySchemaTest.java`:

```java
package br.com.tech.challenge.historyservice;

import java.util.List;

import br.com.tech.challenge.historyservice.support.PostgresTestcontainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainers.class)
class MedicalHistorySchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayCriaTabelaMedicalHistoryComTodasAsColunas() {
        List<String> colunas = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'medical_history'",
                String.class);

        assertThat(colunas).containsExactlyInAnyOrder(
                "id", "event_id", "appointment_id", "patient_id", "patient_name",
                "doctor_id", "doctor_name", "description", "date_time", "status",
                "event_type", "occurred_at", "recorded_at");
    }

    @Test
    void eventIdTemConstraintUnica() {
        Integer total = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                WHERE tc.table_name = 'medical_history'
                  AND tc.constraint_type = 'UNIQUE'
                  AND kcu.column_name = 'event_id'
                """, Integer.class);

        assertThat(total).isEqualTo(1);
    }
}
```

- [ ] **Step 6: Rodar o teste para confirmar que falha**

Run: `./mvnw test -Dtest=MedicalHistorySchemaTest`
Expected: FAIL. A migration atual tem erro de sintaxe, então o Flyway aborta a subida do contexto com `FlywayException` / `PSQLException: syntax error at or near ")"`.

- [ ] **Step 7: Reescrever a migration**

Substitua **todo** o conteúdo de `src/main/resources/db/migration/V1__create_medical_history_table.sql` por:

```sql
CREATE TABLE medical_history (
    id             BIGSERIAL    PRIMARY KEY,
    event_id       UUID         NOT NULL,
    appointment_id BIGINT       NOT NULL,
    patient_id     BIGINT       NOT NULL,
    patient_name   VARCHAR(255),
    doctor_id      BIGINT       NOT NULL,
    doctor_name    VARCHAR(255),
    description    TEXT,
    date_time      TIMESTAMP    NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    event_type     VARCHAR(20)  NOT NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    recorded_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_medical_history_event_id UNIQUE (event_id)
);

CREATE INDEX idx_medical_history_patient ON medical_history (patient_id, occurred_at DESC);
CREATE INDEX idx_medical_history_appointment ON medical_history (appointment_id, occurred_at ASC);
```

Nomes em `snake_case` sem aspas: o Postgres normaliza para minúsculas, o que casa com a `CamelCaseToUnderscoresNamingStrategy` padrão do Hibernate. A versão antiga usava `appointmentId`, que virava `appointmentid` e não casava com nada.

- [ ] **Step 8: Rodar o teste para confirmar que passa**

Run: `./mvnw test -Dtest=MedicalHistorySchemaTest`
Expected: PASS, 2 testes.

Se falhar com `Validate failed: Migration checksum mismatch`, um volume local guarda a migration quebrada. Limpe com `docker compose down -v` (isso apaga os dados locais de desenvolvimento; os testes usam containers descartáveis e não são afetados).

- [ ] **Step 9: Commit**

```bash
git add pom.xml src/main/resources/application.properties \
        src/main/resources/db/migration/V1__create_medical_history_table.sql \
        src/test/java/br/com/tech/challenge/historyservice/support/PostgresTestcontainers.java \
        src/test/java/br/com/tech/challenge/historyservice/MedicalHistorySchemaTest.java
git commit -m "feat: corrige schema de medical_history e adiciona suporte a Testcontainers"
```

---

### Task 2: Contrato do evento — enums e DTO

Define em código o contrato negociado com a Pessoa 4. O DTO é um `record` com Bean Validation; a validação em si é executada na Task 4.

**Files:**
- Create: `src/main/java/br/com/tech/challenge/historyservice/domain/AppointmentStatus.java`
- Create: `src/main/java/br/com/tech/challenge/historyservice/domain/EventType.java`
- Create: `src/main/java/br/com/tech/challenge/historyservice/dto/AppointmentEventDTO.java`
- Test: `src/test/java/br/com/tech/challenge/historyservice/dto/AppointmentEventDTOTest.java`

**Interfaces:**
- Consumes: nada.
- Produces:
  - `enum AppointmentStatus { SCHEDULED, COMPLETED, CANCELLED }`
  - `enum EventType { CREATED, UPDATED }`
  - `record AppointmentEventDTO(UUID eventId, EventType eventType, Instant occurredAt, Long appointmentId, Long patientId, String patientName, Long doctorId, String doctorName, LocalDateTime dateTime, String description, AppointmentStatus status)` — usado pelas Tasks 4 e 5.

- [ ] **Step 1: Escrever o teste de desserialização (vai falhar)**

Crie `src/test/java/br/com/tech/challenge/historyservice/dto/AppointmentEventDTOTest.java`:

```java
package br.com.tech.challenge.historyservice.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import br.com.tech.challenge.historyservice.domain.AppointmentStatus;
import br.com.tech.challenge.historyservice.domain.EventType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JsonTest
class AppointmentEventDTOTest {

    private static final String PAYLOAD_COMPLETO = """
            {
              "eventId": "8f14e45f-ceea-467a-9f4b-1d2c3e4f5a6b",
              "eventType": "CREATED",
              "occurredAt": "2026-08-30T14:32:10Z",
              "appointmentId": 42,
              "patientId": 10,
              "patientName": "Maria Souza",
              "doctorId": 7,
              "doctorName": "Dr. Joao Lima",
              "dateTime": "2026-09-05T09:00:00",
              "description": "Consulta de rotina",
              "status": "SCHEDULED"
            }
            """;

    @Autowired
    private JacksonTester<AppointmentEventDTO> json;

    @Test
    void desserializaPayloadCompleto() throws Exception {
        AppointmentEventDTO evento = json.parseObject(PAYLOAD_COMPLETO);

        assertThat(evento.eventId()).isEqualTo(UUID.fromString("8f14e45f-ceea-467a-9f4b-1d2c3e4f5a6b"));
        assertThat(evento.eventType()).isEqualTo(EventType.CREATED);
        assertThat(evento.occurredAt()).isEqualTo(Instant.parse("2026-08-30T14:32:10Z"));
        assertThat(evento.appointmentId()).isEqualTo(42L);
        assertThat(evento.patientId()).isEqualTo(10L);
        assertThat(evento.patientName()).isEqualTo("Maria Souza");
        assertThat(evento.doctorId()).isEqualTo(7L);
        assertThat(evento.doctorName()).isEqualTo("Dr. Joao Lima");
        assertThat(evento.dateTime()).isEqualTo(LocalDateTime.of(2026, 9, 5, 9, 0));
        assertThat(evento.description()).isEqualTo("Consulta de rotina");
        assertThat(evento.status()).isEqualTo(AppointmentStatus.SCHEDULED);
    }

    @Test
    void aceitaNomesAusentes() throws Exception {
        String semNomes = """
                {
                  "eventId": "8f14e45f-ceea-467a-9f4b-1d2c3e4f5a6b",
                  "eventType": "UPDATED",
                  "occurredAt": "2026-08-30T14:32:10Z",
                  "appointmentId": 42,
                  "patientId": 10,
                  "doctorId": 7,
                  "dateTime": "2026-09-05T09:00:00",
                  "status": "CANCELLED"
                }
                """;

        AppointmentEventDTO evento = json.parseObject(semNomes);

        assertThat(evento.patientName()).isNull();
        assertThat(evento.doctorName()).isNull();
        assertThat(evento.description()).isNull();
        assertThat(evento.status()).isEqualTo(AppointmentStatus.CANCELLED);
    }

    @Test
    void rejeitaCampoDesconhecido() {
        String comCampoExtra = PAYLOAD_COMPLETO.replace(
                "\"status\": \"SCHEDULED\"",
                "\"status\": \"SCHEDULED\", \"campoInesperado\": true");

        assertThatThrownBy(() -> json.parseObject(comCampoExtra))
                .hasMessageContaining("campoInesperado");
    }
}
```

O terceiro teste protege o contrato: se a Pessoa 4 mudar o formato do evento sem avisar, a build quebra em vez de o campo ser silenciosamente ignorado. Ele depende de `spring.jackson.deserialization.fail-on-unknown-properties=true`, que já está em `application.properties:8`.

- [ ] **Step 2: Rodar o teste para confirmar que falha**

Run: `./mvnw test -Dtest=AppointmentEventDTOTest`
Expected: FAIL na compilação — `cannot find symbol: class AppointmentEventDTO`.

- [ ] **Step 3: Criar os enums**

`src/main/java/br/com/tech/challenge/historyservice/domain/AppointmentStatus.java`:

```java
package br.com.tech.challenge.historyservice.domain;

/** Status da consulta no momento em que o evento foi emitido. */
public enum AppointmentStatus {
    SCHEDULED,
    COMPLETED,
    CANCELLED
}
```

`src/main/java/br/com/tech/challenge/historyservice/domain/EventType.java`:

```java
package br.com.tech.challenge.historyservice.domain;

/** O que originou o evento no appointment-service. */
public enum EventType {
    CREATED,
    UPDATED
}
```

- [ ] **Step 4: Criar o DTO**

`src/main/java/br/com/tech/challenge/historyservice/dto/AppointmentEventDTO.java`:

```java
package br.com.tech.challenge.historyservice.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import br.com.tech.challenge.historyservice.domain.AppointmentStatus;
import br.com.tech.challenge.historyservice.domain.EventType;
import jakarta.validation.constraints.NotNull;

/**
 * Contrato do evento publicado pelo appointment-service.
 * Formato documentado em docs/messaging/appointment-event.md.
 * patientName, doctorName e description sao opcionais.
 */
public record AppointmentEventDTO(
        @NotNull UUID eventId,
        @NotNull EventType eventType,
        @NotNull Instant occurredAt,
        @NotNull Long appointmentId,
        @NotNull Long patientId,
        String patientName,
        @NotNull Long doctorId,
        String doctorName,
        @NotNull LocalDateTime dateTime,
        String description,
        @NotNull AppointmentStatus status) {
}
```

- [ ] **Step 5: Rodar o teste para confirmar que passa**

Run: `./mvnw test -Dtest=AppointmentEventDTOTest`
Expected: PASS, 3 testes.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/br/com/tech/challenge/historyservice/domain \
        src/main/java/br/com/tech/challenge/historyservice/dto \
        src/test/java/br/com/tech/challenge/historyservice/dto
git commit -m "feat: define contrato do AppointmentEvent com enums e DTO validado"
```

---

### Task 3: Entidade imutável e repository

Substitui a entidade atual (`entities/MedicalHistory.java`, com campos que não batem com o schema e `@Data` gerando setters) e a interface de repository vazia.

**Files:**
- Modify: `src/main/java/br/com/tech/challenge/historyservice/entities/MedicalHistory.java` (reescrita completa)
- Modify: `src/main/java/br/com/tech/challenge/historyservice/repositories/MedicalHistoryRepository.java` (reescrita completa)
- Test: `src/test/java/br/com/tech/challenge/historyservice/repositories/MedicalHistoryRepositoryTest.java`

**Interfaces:**
- Consumes: `AppointmentStatus`, `EventType` (Task 2); `PostgresTestcontainers` (Task 1).
- Produces:
  - `MedicalHistory` com builder: `MedicalHistory.builder().eventId(UUID).appointmentId(Long).patientId(Long).patientName(String).doctorId(Long).doctorName(String).description(String).dateTime(LocalDateTime).status(AppointmentStatus).eventType(EventType).occurredAt(Instant).build()` e getters `getId()`, `getEventId()`, `getAppointmentId()`, `getPatientId()`, `getPatientName()`, `getDoctorId()`, `getDoctorName()`, `getDescription()`, `getDateTime()`, `getStatus()`, `getEventType()`, `getOccurredAt()`, `getRecordedAt()`.
  - `MedicalHistoryRepository extends JpaRepository<MedicalHistory, Long>` com `boolean existsByEventId(UUID eventId)` e `List<MedicalHistory> findByAppointmentIdOrderByOccurredAtAsc(Long appointmentId)`.

- [ ] **Step 1: Escrever o teste do repository (vai falhar)**

Crie `src/test/java/br/com/tech/challenge/historyservice/repositories/MedicalHistoryRepositoryTest.java`:

```java
package br.com.tech.challenge.historyservice.repositories;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import br.com.tech.challenge.historyservice.domain.AppointmentStatus;
import br.com.tech.challenge.historyservice.domain.EventType;
import br.com.tech.challenge.historyservice.entities.MedicalHistory;
import br.com.tech.challenge.historyservice.support.PostgresTestcontainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainers.class)
class MedicalHistoryRepositoryTest {

    @Autowired
    private MedicalHistoryRepository repository;

    private MedicalHistory registro(UUID eventId, Long appointmentId, AppointmentStatus status, Instant occurredAt) {
        return MedicalHistory.builder()
                .eventId(eventId)
                .appointmentId(appointmentId)
                .patientId(10L)
                .patientName("Maria Souza")
                .doctorId(7L)
                .doctorName("Dr. Joao Lima")
                .description("Consulta de rotina")
                .dateTime(LocalDateTime.of(2026, 9, 5, 9, 0))
                .status(status)
                .eventType(EventType.CREATED)
                .occurredAt(occurredAt)
                .build();
    }

    @Test
    void persisteERecuperaTodosOsCampos() {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-08-30T14:32:10Z");

        MedicalHistory salvo = repository.saveAndFlush(
                registro(eventId, 42L, AppointmentStatus.SCHEDULED, occurredAt));

        assertThat(salvo.getId()).isNotNull();
        assertThat(salvo.getRecordedAt()).isNotNull();
        assertThat(salvo.getEventId()).isEqualTo(eventId);
        assertThat(salvo.getAppointmentId()).isEqualTo(42L);
        assertThat(salvo.getPatientId()).isEqualTo(10L);
        assertThat(salvo.getPatientName()).isEqualTo("Maria Souza");
        assertThat(salvo.getDoctorId()).isEqualTo(7L);
        assertThat(salvo.getDoctorName()).isEqualTo("Dr. Joao Lima");
        assertThat(salvo.getDescription()).isEqualTo("Consulta de rotina");
        assertThat(salvo.getDateTime()).isEqualTo(LocalDateTime.of(2026, 9, 5, 9, 0));
        assertThat(salvo.getStatus()).isEqualTo(AppointmentStatus.SCHEDULED);
        assertThat(salvo.getEventType()).isEqualTo(EventType.CREATED);
        assertThat(salvo.getOccurredAt()).isEqualTo(occurredAt);
    }

    @Test
    void aceitaNomesNulos() {
        MedicalHistory semNomes = MedicalHistory.builder()
                .eventId(UUID.randomUUID())
                .appointmentId(42L)
                .patientId(10L)
                .doctorId(7L)
                .dateTime(LocalDateTime.of(2026, 9, 5, 9, 0))
                .status(AppointmentStatus.CANCELLED)
                .eventType(EventType.UPDATED)
                .occurredAt(Instant.parse("2026-08-30T14:32:10Z"))
                .build();

        MedicalHistory salvo = repository.saveAndFlush(semNomes);

        assertThat(salvo.getPatientName()).isNull();
        assertThat(salvo.getDoctorName()).isNull();
        assertThat(salvo.getDescription()).isNull();
    }

    @Test
    void rejeitaEventIdDuplicado() {
        UUID mesmoEventId = UUID.randomUUID();
        repository.saveAndFlush(registro(mesmoEventId, 42L, AppointmentStatus.SCHEDULED,
                Instant.parse("2026-08-30T14:32:10Z")));

        assertThatThrownBy(() -> repository.saveAndFlush(
                registro(mesmoEventId, 42L, AppointmentStatus.SCHEDULED,
                        Instant.parse("2026-08-30T14:32:10Z"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void acumulaVariasLinhasParaOMesmoAppointment() {
        repository.saveAndFlush(registro(UUID.randomUUID(), 42L, AppointmentStatus.SCHEDULED,
                Instant.parse("2026-08-30T14:00:00Z")));
        repository.saveAndFlush(registro(UUID.randomUUID(), 42L, AppointmentStatus.COMPLETED,
                Instant.parse("2026-08-30T15:00:00Z")));

        List<MedicalHistory> trilha = repository.findByAppointmentIdOrderByOccurredAtAsc(42L);

        assertThat(trilha).hasSize(2);
        assertThat(trilha).extracting(MedicalHistory::getStatus)
                .containsExactly(AppointmentStatus.SCHEDULED, AppointmentStatus.COMPLETED);
    }

    @Test
    void existsByEventIdEncontraRegistroGravado() {
        UUID eventId = UUID.randomUUID();
        repository.saveAndFlush(registro(eventId, 42L, AppointmentStatus.SCHEDULED,
                Instant.parse("2026-08-30T14:32:10Z")));

        assertThat(repository.existsByEventId(eventId)).isTrue();
        assertThat(repository.existsByEventId(UUID.randomUUID())).isFalse();
    }
}
```

`acumulaVariasLinhasParaOMesmoAppointment` é o teste que trava o modelo append-only: se alguém no futuro adicionar `UNIQUE(appointment_id)`, ele quebra.

- [ ] **Step 2: Rodar o teste para confirmar que falha**

Run: `./mvnw test -Dtest=MedicalHistoryRepositoryTest`
Expected: FAIL na compilação — `MedicalHistory.builder()` não existe e `MedicalHistoryRepository` não tem os métodos.

- [ ] **Step 3: Reescrever a entidade**

Substitua **todo** o conteúdo de `src/main/java/br/com/tech/challenge/historyservice/entities/MedicalHistory.java` por:

```java
package br.com.tech.challenge.historyservice.entities;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import br.com.tech.challenge.historyservice.domain.AppointmentStatus;
import br.com.tech.challenge.historyservice.domain.EventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Linha do log append-only do historico de consultas.
 * Cada AppointmentEvent recebido vira um registro novo; nada e atualizado depois do insert.
 */
@Entity
@Table(name = "medical_history")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MedicalHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, updatable = false)
    private UUID eventId;

    @Column(name = "appointment_id", nullable = false, updatable = false)
    private Long appointmentId;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private Long patientId;

    @Column(name = "patient_name", updatable = false)
    private String patientName;

    @Column(name = "doctor_id", nullable = false, updatable = false)
    private Long doctorId;

    @Column(name = "doctor_name", updatable = false)
    private String doctorName;

    @Column(name = "description", updatable = false)
    private String description;

    @Column(name = "date_time", nullable = false, updatable = false)
    private LocalDateTime dateTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20, updatable = false)
    private AppointmentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20, updatable = false)
    private EventType eventType;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @PrePersist
    void aoPersistir() {
        if (recordedAt == null) {
            recordedAt = Instant.now();
        }
    }
}
```

Sem `@Data` e sem `@Setter`: a entidade só pode ser construída pelo builder. `@EnumType.STRING` grava `"SCHEDULED"` em vez de `0` — ordinal quebra se alguém reordenar o enum.

- [ ] **Step 4: Reescrever o repository**

Substitua **todo** o conteúdo de `src/main/java/br/com/tech/challenge/historyservice/repositories/MedicalHistoryRepository.java` por:

```java
package br.com.tech.challenge.historyservice.repositories;

import java.util.List;
import java.util.UUID;

import br.com.tech.challenge.historyservice.entities.MedicalHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MedicalHistoryRepository extends JpaRepository<MedicalHistory, Long> {

    boolean existsByEventId(UUID eventId);

    List<MedicalHistory> findByAppointmentIdOrderByOccurredAtAsc(Long appointmentId);
}
```

- [ ] **Step 5: Rodar o teste para confirmar que passa**

Run: `./mvnw test -Dtest=MedicalHistoryRepositoryTest`
Expected: PASS, 5 testes.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/br/com/tech/challenge/historyservice/entities \
        src/main/java/br/com/tech/challenge/historyservice/repositories \
        src/test/java/br/com/tech/challenge/historyservice/repositories
git commit -m "feat: entidade MedicalHistory imutavel e repository append-only"
```

---

### Task 4: HistoryIngestionService

Valida o evento, mapeia para a entidade e insere. Concentra a regra de idempotência.

**Files:**
- Create: `src/main/java/br/com/tech/challenge/historyservice/services/HistoryIngestionService.java`
- Test: `src/test/java/br/com/tech/challenge/historyservice/services/HistoryIngestionServiceTest.java`

**Interfaces:**
- Consumes: `AppointmentEventDTO` (Task 2), `MedicalHistory` + `MedicalHistoryRepository` (Task 3).
- Produces: `HistoryIngestionService` com construtor `HistoryIngestionService(MedicalHistoryRepository repository, Validator validator)` e método público `void ingest(AppointmentEventDTO evento)` — chamado pelo listener na Task 5.

- [ ] **Step 1: Escrever o teste do service (vai falhar)**

Crie `src/test/java/br/com/tech/challenge/historyservice/services/HistoryIngestionServiceTest.java`:

```java
package br.com.tech.challenge.historyservice.services;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import br.com.tech.challenge.historyservice.domain.AppointmentStatus;
import br.com.tech.challenge.historyservice.domain.EventType;
import br.com.tech.challenge.historyservice.dto.AppointmentEventDTO;
import br.com.tech.challenge.historyservice.entities.MedicalHistory;
import br.com.tech.challenge.historyservice.repositories.MedicalHistoryRepository;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoryIngestionServiceTest {

    private static final UUID EVENT_ID = UUID.fromString("8f14e45f-ceea-467a-9f4b-1d2c3e4f5a6b");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-30T14:32:10Z");

    private static ValidatorFactory validatorFactory;

    private MedicalHistoryRepository repository;
    private HistoryIngestionService service;

    @BeforeAll
    static void abrirValidatorFactory() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
    }

    @AfterAll
    static void fecharValidatorFactory() {
        validatorFactory.close();
    }

    @BeforeEach
    void setUp() {
        repository = mock(MedicalHistoryRepository.class);
        Validator validator = validatorFactory.getValidator();
        service = new HistoryIngestionService(repository, validator);
    }

    private AppointmentEventDTO evento(String patientName, String doctorName) {
        return new AppointmentEventDTO(
                EVENT_ID, EventType.CREATED, OCCURRED_AT, 42L, 10L, patientName,
                7L, doctorName, LocalDateTime.of(2026, 9, 5, 9, 0),
                "Consulta de rotina", AppointmentStatus.SCHEDULED);
    }

    @Test
    void mapeiaTodosOsCamposDoEventoParaAEntidade() {
        service.ingest(evento("Maria Souza", "Dr. Joao Lima"));

        ArgumentCaptor<MedicalHistory> captor = ArgumentCaptor.forClass(MedicalHistory.class);
        verify(repository).save(captor.capture());
        MedicalHistory salvo = captor.getValue();

        assertThat(salvo.getEventId()).isEqualTo(EVENT_ID);
        assertThat(salvo.getEventType()).isEqualTo(EventType.CREATED);
        assertThat(salvo.getOccurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(salvo.getAppointmentId()).isEqualTo(42L);
        assertThat(salvo.getPatientId()).isEqualTo(10L);
        assertThat(salvo.getPatientName()).isEqualTo("Maria Souza");
        assertThat(salvo.getDoctorId()).isEqualTo(7L);
        assertThat(salvo.getDoctorName()).isEqualTo("Dr. Joao Lima");
        assertThat(salvo.getDateTime()).isEqualTo(LocalDateTime.of(2026, 9, 5, 9, 0));
        assertThat(salvo.getDescription()).isEqualTo("Consulta de rotina");
        assertThat(salvo.getStatus()).isEqualTo(AppointmentStatus.SCHEDULED);
    }

    @Test
    void propagaNomesNulos() {
        service.ingest(evento(null, null));

        ArgumentCaptor<MedicalHistory> captor = ArgumentCaptor.forClass(MedicalHistory.class);
        verify(repository).save(captor.capture());

        assertThat(captor.getValue().getPatientName()).isNull();
        assertThat(captor.getValue().getDoctorName()).isNull();
    }

    @Test
    void ignoraEventoJaProcessado() {
        when(repository.existsByEventId(EVENT_ID)).thenReturn(true);

        service.ingest(evento("Maria Souza", "Dr. Joao Lima"));

        verify(repository, never()).save(any());
    }

    @Test
    void engoleColisaoDeEventIdConcorrente() {
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("uk_medical_history_event_id"));

        assertThatCode(() -> service.ingest(evento("Maria Souza", "Dr. Joao Lima")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejeitaEventoSemCampoObrigatorio() {
        AppointmentEventDTO semStatus = new AppointmentEventDTO(
                EVENT_ID, EventType.CREATED, OCCURRED_AT, 42L, 10L, "Maria Souza",
                7L, "Dr. Joao Lima", LocalDateTime.of(2026, 9, 5, 9, 0),
                "Consulta de rotina", null);

        assertThatThrownBy(() -> service.ingest(semStatus))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("status");

        verify(repository, never()).save(any());
    }

    @Test
    void rejeitaEventoNulo() {
        assertThatThrownBy(() -> service.ingest(null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).save(any());
    }
}
```

Os dois testes de idempotência cobrem caminhos diferentes: `ignoraEventoJaProcessado` é a checagem barata antes do insert; `engoleColisaoDeEventIdConcorrente` é a rede de segurança quando dois consumers processam a mesma reentrega ao mesmo tempo e a checagem prévia não adianta.

- [ ] **Step 2: Rodar o teste para confirmar que falha**

Run: `./mvnw test -Dtest=HistoryIngestionServiceTest`
Expected: FAIL na compilação — `cannot find symbol: class HistoryIngestionService`.

- [ ] **Step 3: Implementar o service**

Crie `src/main/java/br/com/tech/challenge/historyservice/services/HistoryIngestionService.java`:

```java
package br.com.tech.challenge.historyservice.services;

import java.util.Set;

import br.com.tech.challenge.historyservice.dto.AppointmentEventDTO;
import br.com.tech.challenge.historyservice.entities.MedicalHistory;
import br.com.tech.challenge.historyservice.repositories.MedicalHistoryRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grava cada AppointmentEvent recebido como uma linha nova em medical_history.
 * Nao atualiza registros existentes: o historico e append-only.
 */
@Slf4j
@Service
public class HistoryIngestionService {

    private final MedicalHistoryRepository repository;
    private final Validator validator;

    public HistoryIngestionService(MedicalHistoryRepository repository, Validator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    @Transactional
    public void ingest(AppointmentEventDTO evento) {
        if (evento == null) {
            throw new IllegalArgumentException("AppointmentEvent nao pode ser nulo");
        }

        Set<ConstraintViolation<AppointmentEventDTO>> violacoes = validator.validate(evento);
        if (!violacoes.isEmpty()) {
            throw new ConstraintViolationException(violacoes);
        }

        if (repository.existsByEventId(evento.eventId())) {
            log.warn("Evento {} ja processado, ignorando reentrega", evento.eventId());
            return;
        }

        try {
            repository.save(toEntity(evento));
            log.debug("Historico gravado para appointment {} a partir do evento {}",
                    evento.appointmentId(), evento.eventId());
        } catch (DataIntegrityViolationException e) {
            // Corrida entre consumers processando a mesma reentrega: o UNIQUE(event_id) barrou.
            log.warn("Evento {} inserido concorrentemente, ignorando", evento.eventId());
        }
    }

    private MedicalHistory toEntity(AppointmentEventDTO evento) {
        return MedicalHistory.builder()
                .eventId(evento.eventId())
                .eventType(evento.eventType())
                .occurredAt(evento.occurredAt())
                .appointmentId(evento.appointmentId())
                .patientId(evento.patientId())
                .patientName(evento.patientName())
                .doctorId(evento.doctorId())
                .doctorName(evento.doctorName())
                .dateTime(evento.dateTime())
                .description(evento.description())
                .status(evento.status())
                .build();
    }
}
```

- [ ] **Step 4: Rodar o teste para confirmar que passa**

Run: `./mvnw test -Dtest=HistoryIngestionServiceTest`
Expected: PASS, 6 testes.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/br/com/tech/challenge/historyservice/services \
        src/test/java/br/com/tech/challenge/historyservice/services
git commit -m "feat: HistoryIngestionService com validacao e idempotencia por eventId"
```

---

### Task 5: Listener tipado e teste ponta a ponta

Troca o listener atual (`messaging/HistoryMessageListener.java`, que só loga um `Map<String,Object>`) por um listener tipado ligado ao service, e prova o fluxo completo com RabbitMQ e Postgres reais.

**Files:**
- Delete: `src/main/java/br/com/tech/challenge/historyservice/messaging/HistoryMessageListener.java`
- Create: `src/main/java/br/com/tech/challenge/historyservice/messaging/AppointmentEventListener.java`
- Create: `src/test/java/br/com/tech/challenge/historyservice/support/RabbitTestcontainers.java`
- Test: `src/test/java/br/com/tech/challenge/historyservice/messaging/AppointmentEventListenerIT.java`

**Interfaces:**
- Consumes: `HistoryIngestionService.ingest(AppointmentEventDTO)` (Task 4), `AppointmentEventDTO` (Task 2), `PostgresTestcontainers` (Task 1).
- Produces: `AppointmentEventListener` — sem API pública além do `@RabbitListener`.

- [ ] **Step 1: Criar a configuração de Testcontainers do RabbitMQ**

Crie `src/test/java/br/com/tech/challenge/historyservice/support/RabbitTestcontainers.java`:

```java
package br.com.tech.challenge.historyservice.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class RabbitTestcontainers {

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbitContainer() {
        return new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management-alpine"));
    }
}
```

Fica separado de `PostgresTestcontainers` para que os testes de `@DataJpaTest` não subam um broker que não usam.

- [ ] **Step 2: Escrever o teste de integração (vai falhar)**

Crie `src/test/java/br/com/tech/challenge/historyservice/messaging/AppointmentEventListenerIT.java`:

```java
package br.com.tech.challenge.historyservice.messaging;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import br.com.tech.challenge.historyservice.domain.AppointmentStatus;
import br.com.tech.challenge.historyservice.domain.EventType;
import br.com.tech.challenge.historyservice.dto.AppointmentEventDTO;
import br.com.tech.challenge.historyservice.entities.MedicalHistory;
import br.com.tech.challenge.historyservice.repositories.MedicalHistoryRepository;
import br.com.tech.challenge.historyservice.support.PostgresTestcontainers;
import br.com.tech.challenge.historyservice.support.RabbitTestcontainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Import({PostgresTestcontainers.class, RabbitTestcontainers.class})
class AppointmentEventListenerIT {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private MedicalHistoryRepository repository;

    @Value("${app.rabbitmq.exchange}")
    private String exchange;

    @Value("${app.rabbitmq.routing-key}")
    private String routingKey;

    @BeforeEach
    void limparBase() {
        repository.deleteAll();
    }

    private AppointmentEventDTO evento(UUID eventId, EventType tipo, AppointmentStatus status, Instant occurredAt) {
        return new AppointmentEventDTO(
                eventId, tipo, occurredAt, 42L, 10L, "Maria Souza",
                7L, "Dr. Joao Lima", LocalDateTime.of(2026, 9, 5, 9, 0),
                "Consulta de rotina", status);
    }

    @Test
    void gravaHistoricoAoReceberEventoDaFila() {
        UUID eventId = UUID.randomUUID();

        rabbitTemplate.convertAndSend(exchange, routingKey,
                evento(eventId, EventType.CREATED, AppointmentStatus.SCHEDULED,
                        Instant.parse("2026-08-30T14:32:10Z")));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<MedicalHistory> registros = repository.findByAppointmentIdOrderByOccurredAtAsc(42L);
            assertThat(registros).hasSize(1);
            assertThat(registros.getFirst().getEventId()).isEqualTo(eventId);
            assertThat(registros.getFirst().getStatus()).isEqualTo(AppointmentStatus.SCHEDULED);
            assertThat(registros.getFirst().getDoctorName()).isEqualTo("Dr. Joao Lima");
        });
    }

    @Test
    void acumulaTrilhaAoReceberEventosSucessivosDoMesmoAppointment() {
        rabbitTemplate.convertAndSend(exchange, routingKey,
                evento(UUID.randomUUID(), EventType.CREATED, AppointmentStatus.SCHEDULED,
                        Instant.parse("2026-08-30T14:00:00Z")));
        rabbitTemplate.convertAndSend(exchange, routingKey,
                evento(UUID.randomUUID(), EventType.UPDATED, AppointmentStatus.COMPLETED,
                        Instant.parse("2026-08-30T15:00:00Z")));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<MedicalHistory> trilha = repository.findByAppointmentIdOrderByOccurredAtAsc(42L);
            assertThat(trilha).hasSize(2);
            assertThat(trilha).extracting(MedicalHistory::getStatus)
                    .containsExactly(AppointmentStatus.SCHEDULED, AppointmentStatus.COMPLETED);
        });
    }

    @Test
    void naoDuplicaQuandoOMesmoEventoChegaDuasVezes() {
        UUID eventId = UUID.randomUUID();
        AppointmentEventDTO mesmoEvento = evento(eventId, EventType.CREATED,
                AppointmentStatus.SCHEDULED, Instant.parse("2026-08-30T14:32:10Z"));

        rabbitTemplate.convertAndSend(exchange, routingKey, mesmoEvento);
        rabbitTemplate.convertAndSend(exchange, routingKey, mesmoEvento);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(repository.findByAppointmentIdOrderByOccurredAtAsc(42L)).hasSize(1));

        // Confirma que a contagem se mantem estavel e nao e apenas um resultado transitorio.
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(repository.findByAppointmentIdOrderByOccurredAtAsc(42L)).hasSize(1));
    }
}
```

- [ ] **Step 3: Rodar o teste para confirmar que falha**

Run: `./mvnw test -Dtest=AppointmentEventListenerIT`
Expected: FAIL. O `HistoryMessageListener` atual apenas loga a mensagem, então o repository fica vazio e o `await` estoura com `Condition ... was not fulfilled within 10 seconds`.

- [ ] **Step 4: Remover o listener antigo**

```bash
git rm src/main/java/br/com/tech/challenge/historyservice/messaging/HistoryMessageListener.java
```

- [ ] **Step 5: Criar o listener tipado**

Crie `src/main/java/br/com/tech/challenge/historyservice/messaging/AppointmentEventListener.java`:

```java
package br.com.tech.challenge.historyservice.messaging;

import br.com.tech.challenge.historyservice.dto.AppointmentEventDTO;
import br.com.tech.challenge.historyservice.services.HistoryIngestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consome AppointmentEvent publicado pelo appointment-service.
 *
 * Qualquer excecao lancada daqui rejeita a mensagem. Como
 * spring.rabbitmq.listener.simple.default-requeue-rejected=false, ela vai direto para a DLQ
 * (history.queue.dlq) em vez de entrar em loop de reentrega.
 */
@Slf4j
@Component
public class AppointmentEventListener {

    private final HistoryIngestionService ingestionService;

    public AppointmentEventListener(HistoryIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @RabbitListener(queues = "${app.rabbitmq.queue}")
    public void onAppointmentEvent(AppointmentEventDTO evento) {
        log.info("AppointmentEvent recebido: eventId={} appointmentId={} tipo={}",
                evento.eventId(), evento.appointmentId(), evento.eventType());
        ingestionService.ingest(evento);
    }
}
```

O parâmetro tipado faz o `JacksonJsonMessageConverter` (já registrado em `config/RabbitMQConfig.java`) desserializar direto para o record. Um JSON que não case com o contrato falha na conversão, antes de o método rodar, e a mensagem vai para a DLQ.

- [ ] **Step 6: Rodar o teste para confirmar que passa**

Run: `./mvnw test -Dtest=AppointmentEventListenerIT`
Expected: PASS, 3 testes.

- [ ] **Step 7: Rodar a suíte completa**

Run: `./mvnw test`
Expected: PASS em tudo. `HistoryApplicationTests.contextLoads` é um `@SpringBootTest` sem containers e vai falhar por não conseguir conectar no Postgres — corrija anotando-o com `@Import({PostgresTestcontainers.class, RabbitTestcontainers.class})` e adicionando os imports correspondentes.

- [ ] **Step 8: Commit**

```bash
git add -A src/main/java/br/com/tech/challenge/historyservice/messaging \
           src/test/java/br/com/tech/challenge/historyservice
git commit -m "feat: listener tipado grava AppointmentEvent no historico"
```

---

### Task 6: Documentação do contrato e do serviço

Entrega a documentação que o plano do grupo cobra de cada integrante, e dá à Pessoa 4 um documento concreto para implementar o produtor.

**Files:**
- Create: `docs/messaging/appointment-event.md`
- Modify: `README.md` (nova seção ao final)

**Interfaces:**
- Consumes: contrato definido na Task 2, topologia em `config/RabbitMQConfig.java`.
- Produces: nada em código.

- [ ] **Step 1: Escrever o documento do contrato**

Crie `docs/messaging/appointment-event.md`:

````markdown
# Contrato do AppointmentEvent

Mensagem publicada pelo `appointment-service` e consumida pelo `history-service`.

## Topologia

| Item | Valor padrão | Variável de ambiente |
|---|---|---|
| Exchange (topic, durável) | `history.exchange` | `RABBITMQ_EXCHANGE` |
| Queue (durável) | `history.queue` | `RABBITMQ_QUEUE` |
| Routing key | `history.created` | `RABBITMQ_ROUTING_KEY` |
| Dead letter exchange | `history.exchange.dlx` | derivada |
| Dead letter queue | `history.queue.dlq` | derivada |

A topologia é declarada pelo `history-service` em `config/RabbitMQConfig.java` e é toda
configurável por `.env` — alinhar os nomes com a Pessoa 4 não exige mudança de código.

## Payload

`content-type: application/json`

```json
{
  "eventId": "8f14e45f-ceea-467a-9f4b-1d2c3e4f5a6b",
  "eventType": "CREATED",
  "occurredAt": "2026-08-30T14:32:10Z",
  "appointmentId": 42,
  "patientId": 10,
  "patientName": "Maria Souza",
  "doctorId": 7,
  "doctorName": "Dr. João Lima",
  "dateTime": "2026-09-05T09:00:00",
  "description": "Consulta de rotina - cardiologia",
  "status": "SCHEDULED"
}
```

| Campo | Tipo | Obrigatório | Observação |
|---|---|---|---|
| `eventId` | UUID | sim | **Novo a cada publicação**, inclusive em republicação do mesmo appointment. É a chave de deduplicação. |
| `eventType` | `CREATED` \| `UPDATED` | sim | |
| `occurredAt` | ISO-8601 com `Z` | sim | Instante do fato no produtor. |
| `appointmentId` | int64 | sim | |
| `patientId` | int64 | sim | |
| `patientName` | string | não | Aceita `null`/ausente. |
| `doctorId` | int64 | sim | |
| `doctorName` | string | não | Aceita `null`/ausente. |
| `dateTime` | ISO-8601 **sem** timezone | sim | Data/hora da consulta. |
| `description` | string | não | |
| `status` | `SCHEDULED` \| `COMPLETED` \| `CANCELLED` | sim | Status no momento do evento. |

## Regras para o produtor

1. **Nunca reutilize um `eventId`.** O `history-service` descarta silenciosamente eventos com
   `eventId` já visto. Reutilizar significa perder o evento.
2. **`status` é obrigatório.** O `history-service` não deriva status de `eventType` — `UPDATED`
   sozinho não diz se a consulta foi concluída ou cancelada.
3. **Não envie campos fora desta lista.** Campo desconhecido faz a desserialização falhar e a
   mensagem vai para a DLQ.

## Comportamento em falha

| Situação | Destino |
|---|---|
| JSON inválido ou campo desconhecido | DLQ (`history.queue.dlq`) |
| Campo obrigatório ausente / enum inválido | DLQ |
| `eventId` repetido | descartada com sucesso (log `WARN`) |
| Banco indisponível | DLQ |

Nada é reenfileirado (`default-requeue-rejected=false`), então uma mensagem ruim nunca trava a fila.
````

- [ ] **Step 2: Adicionar a seção ao README**

Acrescente ao final de `README.md`:

````markdown
## Histórico médico (Fase 1 — ingestão)

O `history-service` é um log **append-only**: cada `AppointmentEvent` recebido do RabbitMQ vira uma
linha nova em `medical_history`. Nenhum registro é atualizado ou apagado, então o histórico de uma
consulta é o conjunto das suas linhas ordenado por `occurred_at`.

Contrato da mensagem: [`docs/messaging/appointment-event.md`](docs/messaging/appointment-event.md).
Design e decisões: [`docs/superpowers/specs/2026-08-30-history-service-ingestion-design.md`](docs/superpowers/specs/2026-08-30-history-service-ingestion-design.md).

### Tabela `medical_history`

| Coluna | Origem |
|---|---|
| `event_id` | do evento — `UNIQUE`, deduplica reentregas do RabbitMQ |
| `appointment_id`, `patient_id`, `doctor_id` | do evento — apenas IDs, sem relacionamento JPA entre serviços |
| `patient_name`, `doctor_name` | snapshot opcional do evento (podem ser `NULL`) |
| `status`, `event_type`, `occurred_at` | estado e instante no momento do evento |
| `recorded_at` | quando o `history-service` gravou |

### Testar a ingestão manualmente

Com a infraestrutura no ar (`docker compose up -d`) e a aplicação rodando, publique pelo console do
RabbitMQ (http://localhost:15672, `guest`/`guest`) na exchange `history.exchange` com routing key
`history.created`, usando `content_type: application/json` e o payload documentado em
`docs/messaging/appointment-event.md`. Confira o resultado:

```bash
docker exec -it historyservice-postgres \
  psql -U postgres -d mydatabase -c "SELECT event_id, appointment_id, status, occurred_at FROM medical_history ORDER BY recorded_at;"
```

### Rodar os testes

Precisa de Docker em execução — os testes sobem Postgres e RabbitMQ descartáveis via Testcontainers.

```bash
./mvnw test
```
````

- [ ] **Step 3: Verificar que a build continua verde**

Run: `./mvnw test`
Expected: PASS em tudo (documentação não altera código, mas confirma que nada quebrou antes do commit).

- [ ] **Step 4: Commit**

```bash
git add docs/ README.md
git commit -m "docs: contrato do AppointmentEvent e documentacao da ingestao"
```

---

## Pendências para a Fase 2

- Decidir se `patientHistory(patientId)` devolve a trilha crua ou o último evento por `appointmentId`.
- Schema GraphQL, resolvers e `spring-boot-starter-graphql`.
- Spring Security: `PATIENT` só acessa o próprio `patientId`; `DOCTOR`/`NURSE` acessam qualquer um.

## Pendências com o grupo (bloqueiam a integração real, não a Fase 1)

- **Pessoa 4:** incluir `eventId`, `status` e `occurredAt` no `AppointmentEvent` (o modelo do PDF não tem os três).
- **Pessoa 4:** confirmar exchange, queue e routing key definitivas — o `history-service` já lê tudo de `.env`.
- **Pessoa 2:** `patientName`/`doctorName` são opcionais; se o `appointment-service` não tiver acesso aos nomes, o `history-service` funciona sem eles.
