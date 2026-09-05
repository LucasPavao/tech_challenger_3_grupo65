# History Service — GraphQL de leitura (Fase 2a) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expor o histórico append-only por GraphQL em duas queries — o estado atual de cada consulta de um paciente, e a trilha completa de uma consulta.

**Architecture:** O endpoint `POST /graphql` é servido pelo `spring-boot-starter-graphql` sobre o Web MVC que já existe. O schema vive em `src/main/resources/graphql/schema.graphqls` e cada `@QueryMapping` casa por nome com um campo do `type Query`. A leitura é separada da ingestão: `MedicalHistoryQueryService` só lê, `HistoryIngestionService` só escreve. `patientHistory` colapsa a trilha para o último evento de cada `appointmentId` via `DISTINCT ON` nativo do Postgres; `appointmentTimeline` devolve as linhas cronológicas.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring for GraphQL 2.0.4 (graphql-java 25.0), Spring Data JPA, PostgreSQL 16, Testcontainers 2.0.5, JUnit 5 + Mockito.

**Spec:** `docs/superpowers/specs/2026-08-30-history-service-ingestion-design.md` (seção 8 traz as decisões desta fase)

## Global Constraints

- Pacote base: `br.com.tech.challenge.historyservice`.
- **Spring Boot 4.1 reorganizou os pacotes de teste.** Use exatamente: `org.springframework.boot.graphql.test.autoconfigure.GraphQlTest`, `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`, `org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase`. Os pacotes da documentação 3.x **não existem** aqui. `GraphQlTester` continua em `org.springframework.graphql.test.tester`.
- **Datas são `String` ISO-8601 no schema**, nunca um scalar customizado. `graphql-java-extended-scalars` só existe até a 22.0 e o Boot 4.1 gerencia graphql-java 25.0 — misturar as duas é risco sem retorno.
- Versões vêm do parent `spring-boot-starter-parent:4.1.0` — **nunca escreva `<version>`** nas dependências novas.
- **Nenhum `UPDATE` ou `DELETE`.** Esta fase é só leitura; o log é append-only.
- A entidade `MedicalHistory` é imutável e não tem setters — DTOs de resposta são `record`s montados a partir dos getters.
- Docker precisa estar rodando para os testes (`docker info`). Não é preciso subir o `docker compose`: os testes usam Testcontainers.
- Segurança está **fora** desta fase. O endpoint fica aberto; a autorização entra num plano próprio quando a Pessoa 1 publicar o formato do JWT.

---

### Task 1: Fundação GraphQL — dependências, schema e endpoint no ar

Coloca o endpoint `/graphql` de pé com uma query trivial, provando que o starter, o schema e o transporte HTTP estão ligados antes de qualquer regra de negócio entrar.

**Files:**
- Modify: `pom.xml`
- Create: `src/main/resources/graphql/schema.graphqls`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/br/com/tech/challenge/historyservice/graphql/GraphQlSchemaTest.java`

**Interfaces:**
- Consumes: nada.
- Produces: endpoint `POST /graphql`; arquivo `schema.graphqls` que as Tasks 3 e 4 estendem.

- [ ] **Step 1: Adicionar as dependências ao `pom.xml`**

Dentro de `<dependencies>`, logo depois do bloco `spring-boot-starter-webmvc`:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-graphql</artifactId>
		</dependency>
```

E junto das dependências de teste (as que têm `<scope>test</scope>`):

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-graphql-test</artifactId>
			<scope>test</scope>
		</dependency>
```

- [ ] **Step 2: Verificar que as dependências resolvem**

Run: `./mvnw -q dependency:resolve`
Expected: termina sem `ERROR`.

- [ ] **Step 3: Escrever o teste do schema (vai falhar)**

Crie `src/test/java/br/com/tech/challenge/historyservice/graphql/GraphQlSchemaTest.java`:

```java
package br.com.tech.challenge.historyservice.graphql;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.graphql.execution.GraphQlSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Valida que o schema carrega e declara os campos esperados. Nao executa query --
 * os resolvers entram nas Tasks 3 e 4.
 */
@GraphQlTest
class GraphQlSchemaTest {

    /**
     * Nao existe bean GraphQLSchema no contexto -- o GraphQlAutoConfiguration expoe GraphQlSource,
     * e o schema sai de GraphQlSource.schema().
     */
    @Autowired
    private GraphQlSource graphQlSource;

    private GraphQLSchema schema() {
        return graphQlSource.schema();
    }

    @Test
    void schemaCarregaComOTipoQuery() {
        assertThat(schema().getQueryType()).isNotNull();
        assertThat(schema().getQueryType().getName()).isEqualTo("Query");
    }

    @Test
    void declaraOTipoMedicalRecordComOsCamposDoHistorico() {
        assertThat(schema().getObjectType("MedicalRecord")).isNotNull();
        assertThat(schema().getObjectType("MedicalRecord").getFieldDefinitions())
                .extracting(f -> f.getName())
                .contains("appointmentId", "patientId", "patientName", "doctorId", "doctorName",
                        "description", "appointmentDate", "eventStatus", "occurredAt");
    }

    @Test
    void declaraOEnumComAsQuatroTransicoes() {
        assertThat(schema().getType("AppointmentEventStatus")).isNotNull();
        assertThat(((GraphQLEnumType) schema().getType("AppointmentEventStatus")).getValues())
                .extracting(v -> v.getName())
                .containsExactlyInAnyOrder("SCHEDULED", "RESCHEDULED", "CANCELLED", "COMPLETED");
    }
}
```

- [ ] **Step 4: Rodar o teste para confirmar que falha**

Run: `./mvnw test -Dtest=GraphQlSchemaTest`
Expected: FAIL. Sem `schema.graphqls`, o Spring GraphQL não sobe: `InvalidSchemaLocationsException` ou `No schema files found`.

- [ ] **Step 5: Criar o schema**

Crie `src/main/resources/graphql/schema.graphqls`:

```graphql
type Query {
    "Ultimo evento de cada consulta do paciente, da mais recente para a mais antiga."
    patientHistory(patientId: ID!): [MedicalRecord!]!
}

"""
Uma linha do historico append-only: o estado da consulta no momento de um evento.
Datas sao strings ISO-8601.
"""
type MedicalRecord {
    "Identificador da linha no historico, nao da consulta."
    id: ID!
    appointmentId: ID!
    patientId: ID!
    patientName: String
    doctorId: ID!
    doctorName: String
    description: String
    "Inicio da consulta neste evento, ISO-8601 sem timezone. Ex: 2026-09-05T09:00:00"
    appointmentDate: String!
    eventStatus: AppointmentEventStatus!
    "Quando o evento ocorreu no appointment-service, ISO-8601 UTC. Ex: 2026-08-30T14:32:10Z"
    occurredAt: String!
}

"Transicao sofrida pela consulta, declarada pelo appointment-service."
enum AppointmentEventStatus {
    SCHEDULED
    RESCHEDULED
    CANCELLED
    COMPLETED
}
```

- [ ] **Step 6: Habilitar o GraphiQL para uso local**

Acrescente ao final de `src/main/resources/application.properties`:

```properties
# GraphQL
spring.graphql.schema.locations=classpath:graphql/
spring.graphql.graphiql.enabled=${GRAPHIQL_ENABLED:true}
spring.graphql.graphiql.path=/graphiql
```

O GraphiQL é a IDE web para explorar o schema em http://localhost:8080/graphiql. Fica ligado por
padrão para facilitar a demonstração do Tech Challenge; `GRAPHIQL_ENABLED=false` desliga.

- [ ] **Step 7: Rodar o teste para confirmar que passa**

Run: `./mvnw test -Dtest=GraphQlSchemaTest`
Expected: PASS, 3 testes.

- [ ] **Step 8: Commit**

```bash
git add pom.xml src/main/resources/graphql/schema.graphqls \
        src/main/resources/application.properties \
        src/test/java/br/com/tech/challenge/historyservice/graphql/GraphQlSchemaTest.java
git commit -m "feat: adiciona schema GraphQL e endpoint de consulta"
```

---

### Task 2: Consultas de leitura no repository

Adiciona as duas consultas que alimentam os resolvers. A de `patientHistory` usa `DISTINCT ON`, extensão do Postgres que devolve a primeira linha de cada grupo — é o jeito mais direto de colapsar a trilha para o último evento de cada consulta.

**Files:**
- Modify: `src/main/java/br/com/tech/challenge/historyservice/repositories/MedicalHistoryRepository.java`
- Test: `src/test/java/br/com/tech/challenge/historyservice/repositories/MedicalHistoryQueryTest.java`

**Interfaces:**
- Consumes: `MedicalHistory` (entidade existente, com `getEventStatus()`, `getAppointmentDate()`, `getOccurredAt()`), `AppointmentEventStatus`, `PostgresTestcontainers`.
- Produces, em `MedicalHistoryRepository`:
  - `List<MedicalHistory> findLatestEventPerAppointment(Long patientId)` — uma linha por `appointmentId`, a mais recente por `occurred_at`, ordenadas da consulta mais recente para a mais antiga.
  - `List<MedicalHistory> findByAppointmentIdOrderByOccurredAtAsc(Long appointmentId)` — **já existe**, não recriar.

- [ ] **Step 1: Escrever o teste (vai falhar)**

Crie `src/test/java/br/com/tech/challenge/historyservice/repositories/MedicalHistoryQueryTest.java`:

```java
package br.com.tech.challenge.historyservice.repositories;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import br.com.tech.challenge.historyservice.domain.AppointmentEventStatus;
import br.com.tech.challenge.historyservice.entities.MedicalHistory;
import br.com.tech.challenge.historyservice.support.PostgresTestcontainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainers.class)
class MedicalHistoryQueryTest {

    @Autowired
    private MedicalHistoryRepository repository;

    private void grava(Long patientId, Long appointmentId, AppointmentEventStatus eventStatus,
                       LocalDateTime appointmentDate, Instant occurredAt) {
        repository.saveAndFlush(MedicalHistory.builder()
                .eventId(UUID.randomUUID())
                .appointmentId(appointmentId)
                .patientId(patientId)
                .patientName("Maria Souza")
                .doctorId(7L)
                .doctorName("Dr. Joao Lima")
                .description("Consulta de rotina")
                .appointmentDate(appointmentDate)
                .eventStatus(eventStatus)
                .occurredAt(occurredAt)
                .build());
    }

    @Test
    void devolveApenasOUltimoEventoDeCadaConsulta() {
        LocalDateTime data = LocalDateTime.of(2026, 9, 5, 9, 0);
        LocalDateTime novaData = LocalDateTime.of(2026, 9, 12, 14, 0);

        // Consulta 42: agendada, remarcada e concluida.
        grava(10L, 42L, AppointmentEventStatus.SCHEDULED, data, Instant.parse("2026-08-30T14:00:00Z"));
        grava(10L, 42L, AppointmentEventStatus.RESCHEDULED, novaData, Instant.parse("2026-08-31T10:00:00Z"));
        grava(10L, 42L, AppointmentEventStatus.COMPLETED, novaData, Instant.parse("2026-09-12T15:00:00Z"));
        // Consulta 58: apenas agendada.
        grava(10L, 58L, AppointmentEventStatus.SCHEDULED, data.plusMonths(1), Instant.parse("2026-09-01T09:00:00Z"));

        List<MedicalHistory> historico = repository.findLatestEventPerAppointment(10L);

        assertThat(historico).hasSize(2);
        assertThat(historico).extracting(MedicalHistory::getAppointmentId)
                .containsExactly(42L, 58L);
        assertThat(historico).extracting(MedicalHistory::getEventStatus)
                .containsExactly(AppointmentEventStatus.COMPLETED, AppointmentEventStatus.SCHEDULED);
        assertThat(historico.getFirst().getAppointmentDate()).isEqualTo(novaData);
    }

    @Test
    void ordenaDaConsultaMaisRecenteParaAMaisAntiga() {
        LocalDateTime data = LocalDateTime.of(2026, 9, 5, 9, 0);
        grava(10L, 10L, AppointmentEventStatus.COMPLETED, data, Instant.parse("2026-08-01T10:00:00Z"));
        grava(10L, 20L, AppointmentEventStatus.SCHEDULED, data, Instant.parse("2026-08-15T10:00:00Z"));
        grava(10L, 30L, AppointmentEventStatus.CANCELLED, data, Instant.parse("2026-08-10T10:00:00Z"));

        assertThat(repository.findLatestEventPerAppointment(10L))
                .extracting(MedicalHistory::getAppointmentId)
                .containsExactly(20L, 30L, 10L);
    }

    @Test
    void naoMisturaPacientes() {
        LocalDateTime data = LocalDateTime.of(2026, 9, 5, 9, 0);
        grava(10L, 42L, AppointmentEventStatus.SCHEDULED, data, Instant.parse("2026-08-30T14:00:00Z"));
        grava(99L, 77L, AppointmentEventStatus.SCHEDULED, data, Instant.parse("2026-08-30T14:00:00Z"));

        assertThat(repository.findLatestEventPerAppointment(10L))
                .extracting(MedicalHistory::getAppointmentId)
                .containsExactly(42L);
    }

    @Test
    void devolveListaVaziaParaPacienteSemHistorico() {
        assertThat(repository.findLatestEventPerAppointment(404L)).isEmpty();
    }
}
```

- [ ] **Step 2: Rodar o teste para confirmar que falha**

Run: `./mvnw test -Dtest=MedicalHistoryQueryTest`
Expected: FAIL na compilação — `cannot find symbol: method findLatestEventPerAppointment`.

- [ ] **Step 3: Adicionar a consulta ao repository**

Substitua **todo** o conteúdo de `src/main/java/br/com/tech/challenge/historyservice/repositories/MedicalHistoryRepository.java` por:

```java
package br.com.tech.challenge.historyservice.repositories;

import java.util.List;
import java.util.UUID;

import br.com.tech.challenge.historyservice.entities.MedicalHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MedicalHistoryRepository extends JpaRepository<MedicalHistory, Long> {

    boolean existsByEventId(UUID eventId);

    List<MedicalHistory> findByAppointmentIdOrderByOccurredAtAsc(Long appointmentId);

    /**
     * Estado atual de cada consulta do paciente: colapsa a trilha append-only para o evento mais
     * recente de cada appointment_id.
     *
     * DISTINCT ON e uma extensao do Postgres -- devolve a primeira linha de cada grupo, por isso o
     * ORDER BY interno precisa comecar por appointment_id. A subquery reordena o resultado por
     * data do evento, que e a ordem que o cliente ve.
     */
    @Query(value = """
            SELECT * FROM (
                SELECT DISTINCT ON (appointment_id) *
                FROM medical_history
                WHERE patient_id = :patientId
                ORDER BY appointment_id, occurred_at DESC
            ) AS ultimo
            ORDER BY ultimo.occurred_at DESC
            """, nativeQuery = true)
    List<MedicalHistory> findLatestEventPerAppointment(@Param("patientId") Long patientId);
}
```

- [ ] **Step 4: Rodar o teste para confirmar que passa**

Run: `./mvnw test -Dtest=MedicalHistoryQueryTest`
Expected: PASS, 4 testes.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/br/com/tech/challenge/historyservice/repositories \
        src/test/java/br/com/tech/challenge/historyservice/repositories/MedicalHistoryQueryTest.java
git commit -m "feat: consulta do ultimo evento de cada consulta do paciente"
```

---

### Task 3: Service de leitura e a query `patientHistory`

Liga o repository ao GraphQL. O service concentra a regra de leitura e é o ponto onde a autorização vai entrar na fase seguinte; o resolver só traduz argumentos e resposta.

**Files:**
- Create: `src/main/java/br/com/tech/challenge/historyservice/dto/MedicalRecordResponse.java`
- Create: `src/main/java/br/com/tech/challenge/historyservice/services/MedicalHistoryQueryService.java`
- Create: `src/main/java/br/com/tech/challenge/historyservice/graphql/HistoryQueryController.java`
- Test: `src/test/java/br/com/tech/challenge/historyservice/services/MedicalHistoryQueryServiceTest.java`
- Test: `src/test/java/br/com/tech/challenge/historyservice/graphql/HistoryQueryControllerTest.java`

**Interfaces:**
- Consumes: `MedicalHistoryRepository.findLatestEventPerAppointment(Long)` (Task 2), schema com `patientHistory` (Task 1).
- Produces:
  - `record MedicalRecordResponse(String id, String appointmentId, String patientId, String patientName, String doctorId, String doctorName, String description, String appointmentDate, AppointmentEventStatus eventStatus, String occurredAt)` com o factory estático `MedicalRecordResponse.from(MedicalHistory)` — usado também pela Task 4.
  - `MedicalHistoryQueryService` com construtor `MedicalHistoryQueryService(MedicalHistoryRepository repository)` e método `List<MedicalRecordResponse> patientHistory(Long patientId)`. A Task 4 acrescenta um segundo método a esta classe.
  - `HistoryQueryController` com `@QueryMapping List<MedicalRecordResponse> patientHistory(@Argument Long patientId)`.

- [ ] **Step 1: Escrever o teste do service (vai falhar)**

Crie `src/test/java/br/com/tech/challenge/historyservice/services/MedicalHistoryQueryServiceTest.java`:

```java
package br.com.tech.challenge.historyservice.services;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import br.com.tech.challenge.historyservice.domain.AppointmentEventStatus;
import br.com.tech.challenge.historyservice.dto.MedicalRecordResponse;
import br.com.tech.challenge.historyservice.entities.MedicalHistory;
import br.com.tech.challenge.historyservice.repositories.MedicalHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MedicalHistoryQueryServiceTest {

    private MedicalHistoryRepository repository;
    private MedicalHistoryQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(MedicalHistoryRepository.class);
        service = new MedicalHistoryQueryService(repository);
    }

    private MedicalHistory registro() {
        return MedicalHistory.builder()
                .eventId(UUID.randomUUID())
                .appointmentId(42L)
                .patientId(10L)
                .patientName("Maria Souza")
                .doctorId(7L)
                .doctorName("Dr. Joao Lima")
                .description("Consulta de rotina")
                .appointmentDate(LocalDateTime.of(2026, 9, 5, 9, 0))
                .eventStatus(AppointmentEventStatus.SCHEDULED)
                .occurredAt(Instant.parse("2026-08-30T14:32:10Z"))
                .build();
    }

    @Test
    void converteAEntidadeParaAResposta() {
        when(repository.findLatestEventPerAppointment(10L)).thenReturn(List.of(registro()));

        MedicalRecordResponse resposta = service.patientHistory(10L).getFirst();

        assertThat(resposta.appointmentId()).isEqualTo("42");
        assertThat(resposta.patientId()).isEqualTo("10");
        assertThat(resposta.patientName()).isEqualTo("Maria Souza");
        assertThat(resposta.doctorId()).isEqualTo("7");
        assertThat(resposta.doctorName()).isEqualTo("Dr. Joao Lima");
        assertThat(resposta.description()).isEqualTo("Consulta de rotina");
        assertThat(resposta.eventStatus()).isEqualTo(AppointmentEventStatus.SCHEDULED);
    }

    @Test
    void formataAsDatasComoIso8601() {
        when(repository.findLatestEventPerAppointment(10L)).thenReturn(List.of(registro()));

        MedicalRecordResponse resposta = service.patientHistory(10L).getFirst();

        assertThat(resposta.appointmentDate()).isEqualTo("2026-09-05T09:00:00");
        assertThat(resposta.occurredAt()).isEqualTo("2026-08-30T14:32:10Z");
    }

    @Test
    void propagaNomesNulos() {
        MedicalHistory semNomes = MedicalHistory.builder()
                .eventId(UUID.randomUUID())
                .appointmentId(42L)
                .patientId(10L)
                .doctorId(7L)
                .appointmentDate(LocalDateTime.of(2026, 9, 5, 9, 0))
                .eventStatus(AppointmentEventStatus.CANCELLED)
                .occurredAt(Instant.parse("2026-08-30T14:32:10Z"))
                .build();
        when(repository.findLatestEventPerAppointment(10L)).thenReturn(List.of(semNomes));

        MedicalRecordResponse resposta = service.patientHistory(10L).getFirst();

        assertThat(resposta.patientName()).isNull();
        assertThat(resposta.doctorName()).isNull();
        assertThat(resposta.description()).isNull();
    }

    @Test
    void devolveListaVaziaQuandoNaoHaHistorico() {
        when(repository.findLatestEventPerAppointment(404L)).thenReturn(List.of());

        assertThat(service.patientHistory(404L)).isEmpty();
    }

    @Test
    void rejeitaPatientIdNulo() {
        assertThatThrownBy(() -> service.patientHistory(null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).findLatestEventPerAppointment(any());
    }
}
```

`devolveListaVaziaQuandoNaoHaHistorico` fixa uma decisão: paciente sem histórico devolve lista
vazia, não erro. O `history-service` não conhece o cadastro de pacientes — ele não tem como
distinguir "paciente não existe" de "paciente sem consultas", e inventar um `NOT_FOUND` seria
afirmar algo que ele não sabe.

- [ ] **Step 2: Rodar o teste para confirmar que falha**

Run: `./mvnw test -Dtest=MedicalHistoryQueryServiceTest`
Expected: FAIL na compilação — `cannot find symbol: class MedicalHistoryQueryService`.

- [ ] **Step 3: Criar o DTO de resposta**

Crie `src/main/java/br/com/tech/challenge/historyservice/dto/MedicalRecordResponse.java`:

```java
package br.com.tech.challenge.historyservice.dto;

import java.time.format.DateTimeFormatter;

import br.com.tech.challenge.historyservice.domain.AppointmentEventStatus;
import br.com.tech.challenge.historyservice.entities.MedicalHistory;

/**
 * Uma linha do historico como o GraphQL a expoe.
 *
 * Ids sao String porque o tipo ID! do GraphQL e serializado como string, e as datas sao ISO-8601
 * pelo mesmo motivo -- o schema nao usa scalar customizado.
 */
public record MedicalRecordResponse(
        String id,
        String appointmentId,
        String patientId,
        String patientName,
        String doctorId,
        String doctorName,
        String description,
        String appointmentDate,
        AppointmentEventStatus eventStatus,
        String occurredAt) {

    public static MedicalRecordResponse from(MedicalHistory registro) {
        return new MedicalRecordResponse(
                String.valueOf(registro.getId()),
                String.valueOf(registro.getAppointmentId()),
                String.valueOf(registro.getPatientId()),
                registro.getPatientName(),
                String.valueOf(registro.getDoctorId()),
                registro.getDoctorName(),
                registro.getDescription(),
                registro.getAppointmentDate().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                registro.getEventStatus(),
                registro.getOccurredAt().toString());
    }
}
```

`Instant.toString()` já produz ISO-8601 com `Z` (`2026-08-30T14:32:10Z`), e
`ISO_LOCAL_DATE_TIME` produz `2026-09-05T09:00:00` — os dois formatos documentados no schema.

- [ ] **Step 4: Criar o service de leitura**

Crie `src/main/java/br/com/tech/challenge/historyservice/services/MedicalHistoryQueryService.java`:

```java
package br.com.tech.challenge.historyservice.services;

import java.util.List;

import br.com.tech.challenge.historyservice.dto.MedicalRecordResponse;
import br.com.tech.challenge.historyservice.repositories.MedicalHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leitura do historico. Separado do HistoryIngestionService de proposito: um so le, o outro so
 * escreve.
 *
 * E aqui que a autorizacao entra na proxima fase -- a regra "PATIENT so acessa o proprio
 * patientId" depende do valor do argumento, entao nao cabe numa anotacao de resolver.
 */
@Service
@Transactional(readOnly = true)
public class MedicalHistoryQueryService {

    private final MedicalHistoryRepository repository;

    public MedicalHistoryQueryService(MedicalHistoryRepository repository) {
        this.repository = repository;
    }

    /**
     * Estado atual de cada consulta do paciente. Paciente sem historico devolve lista vazia: este
     * servico nao conhece o cadastro de pacientes e nao pode afirmar que o paciente nao existe.
     */
    public List<MedicalRecordResponse> patientHistory(Long patientId) {
        if (patientId == null) {
            throw new IllegalArgumentException("patientId nao pode ser nulo");
        }

        return repository.findLatestEventPerAppointment(patientId).stream()
                .map(MedicalRecordResponse::from)
                .toList();
    }
}
```

- [ ] **Step 5: Rodar o teste do service para confirmar que passa**

Run: `./mvnw test -Dtest=MedicalHistoryQueryServiceTest`
Expected: PASS, 5 testes.

- [ ] **Step 6: Escrever o teste do resolver (vai falhar)**

Crie `src/test/java/br/com/tech/challenge/historyservice/graphql/HistoryQueryControllerTest.java`:

```java
package br.com.tech.challenge.historyservice.graphql;

import java.util.List;

import br.com.tech.challenge.historyservice.domain.AppointmentEventStatus;
import br.com.tech.challenge.historyservice.dto.MedicalRecordResponse;
import br.com.tech.challenge.historyservice.services.MedicalHistoryQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@GraphQlTest(HistoryQueryController.class)
class HistoryQueryControllerTest {

    @Autowired
    private GraphQlTester graphQlTester;

    @MockitoBean
    private MedicalHistoryQueryService queryService;

    private MedicalRecordResponse resposta(String appointmentId, AppointmentEventStatus eventStatus) {
        return new MedicalRecordResponse("1", appointmentId, "10", "Maria Souza", "7",
                "Dr. Joao Lima", "Consulta de rotina", "2026-09-05T09:00:00",
                eventStatus, "2026-08-30T14:32:10Z");
    }

    @Test
    void patientHistoryDevolveOsCamposDoRegistro() {
        when(queryService.patientHistory(10L))
                .thenReturn(List.of(resposta("42", AppointmentEventStatus.COMPLETED)));

        graphQlTester.document("""
                        query {
                          patientHistory(patientId: 10) {
                            appointmentId
                            patientName
                            doctorName
                            appointmentDate
                            eventStatus
                            occurredAt
                          }
                        }
                        """)
                .execute()
                .path("patientHistory[0].appointmentId").entity(String.class).isEqualTo("42")
                .path("patientHistory[0].patientName").entity(String.class).isEqualTo("Maria Souza")
                .path("patientHistory[0].doctorName").entity(String.class).isEqualTo("Dr. Joao Lima")
                .path("patientHistory[0].appointmentDate").entity(String.class).isEqualTo("2026-09-05T09:00:00")
                .path("patientHistory[0].eventStatus").entity(String.class).isEqualTo("COMPLETED")
                .path("patientHistory[0].occurredAt").entity(String.class).isEqualTo("2026-08-30T14:32:10Z");
    }

    @Test
    void patientHistoryConverteOArgumentoIdParaLong() {
        when(queryService.patientHistory(10L)).thenReturn(List.of());

        graphQlTester.document("{ patientHistory(patientId: 10) { appointmentId } }")
                .execute()
                .path("patientHistory").entityList(Object.class).hasSize(0);

        verify(queryService).patientHistory(10L);
    }

    @Test
    void patientHistoryDevolveListaVaziaSemErro() {
        when(queryService.patientHistory(404L)).thenReturn(List.of());

        graphQlTester.document("{ patientHistory(patientId: 404) { appointmentId } }")
                .execute()
                .errors().verify()
                .path("patientHistory").entityList(Object.class).hasSize(0);
    }

    @Test
    void devolveApenasOsCamposPedidos() {
        when(queryService.patientHistory(10L))
                .thenReturn(List.of(resposta("42", AppointmentEventStatus.SCHEDULED)));

        graphQlTester.document("{ patientHistory(patientId: 10) { appointmentId } }")
                .execute()
                .path("patientHistory[0]").entity(java.util.Map.class)
                .satisfies(registro -> assertThat(registro).containsOnlyKeys("appointmentId"));
    }
}
```

O último teste prova a característica que justifica GraphQL no desafio: o cliente pede
`appointmentId` e recebe só isso, sem os outros campos.

- [ ] **Step 7: Rodar o teste para confirmar que falha**

Run: `./mvnw test -Dtest=HistoryQueryControllerTest`
Expected: FAIL na compilação — `cannot find symbol: class HistoryQueryController`.

- [ ] **Step 8: Criar o resolver**

Crie `src/main/java/br/com/tech/challenge/historyservice/graphql/HistoryQueryController.java`:

```java
package br.com.tech.challenge.historyservice.graphql;

import java.util.List;

import br.com.tech.challenge.historyservice.dto.MedicalRecordResponse;
import br.com.tech.challenge.historyservice.services.MedicalHistoryQueryService;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

/**
 * Resolvers das queries do historico. Cada @QueryMapping casa por nome com um campo do type Query
 * em graphql/schema.graphqls.
 *
 * O controller so traduz argumentos e resposta; a regra de leitura fica no service.
 */
@Controller
public class HistoryQueryController {

    private final MedicalHistoryQueryService queryService;

    public HistoryQueryController(MedicalHistoryQueryService queryService) {
        this.queryService = queryService;
    }

    @QueryMapping
    public List<MedicalRecordResponse> patientHistory(@Argument Long patientId) {
        return queryService.patientHistory(patientId);
    }
}
```

- [ ] **Step 9: Rodar os dois testes para confirmar que passam**

Run: `./mvnw test -Dtest=MedicalHistoryQueryServiceTest,HistoryQueryControllerTest`
Expected: PASS, 9 testes no total.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/br/com/tech/challenge/historyservice/dto/MedicalRecordResponse.java \
        src/main/java/br/com/tech/challenge/historyservice/services/MedicalHistoryQueryService.java \
        src/main/java/br/com/tech/challenge/historyservice/graphql/HistoryQueryController.java \
        src/test/java/br/com/tech/challenge/historyservice/services/MedicalHistoryQueryServiceTest.java \
        src/test/java/br/com/tech/challenge/historyservice/graphql/HistoryQueryControllerTest.java
git commit -m "feat: query patientHistory com o estado atual de cada consulta"
```

---

### Task 4: A query `appointmentTimeline`

Acrescenta a segunda query: a trilha completa de uma consulta, que é o que o modelo append-only tem de diferente de uma tabela de estado.

**Files:**
- Modify: `src/main/resources/graphql/schema.graphqls`
- Modify: `src/main/java/br/com/tech/challenge/historyservice/services/MedicalHistoryQueryService.java`
- Modify: `src/main/java/br/com/tech/challenge/historyservice/graphql/HistoryQueryController.java`
- Test: `src/test/java/br/com/tech/challenge/historyservice/services/MedicalHistoryQueryServiceTest.java` (acrescentar)
- Test: `src/test/java/br/com/tech/challenge/historyservice/graphql/HistoryQueryControllerTest.java` (acrescentar)

**Interfaces:**
- Consumes: `MedicalHistoryRepository.findByAppointmentIdOrderByOccurredAtAsc(Long)` (já existe), `MedicalRecordResponse.from(MedicalHistory)` (Task 3).
- Produces:
  - `MedicalHistoryQueryService.appointmentTimeline(Long appointmentId)` devolvendo `List<MedicalRecordResponse>` em ordem cronológica crescente.
  - `HistoryQueryController.appointmentTimeline(@Argument Long appointmentId)`.

- [ ] **Step 1: Acrescentar os testes do service (vão falhar)**

Em `src/test/java/br/com/tech/challenge/historyservice/services/MedicalHistoryQueryServiceTest.java`, acrescente estes métodos **dentro da classe**, antes da chave final:

```java
    @Test
    void appointmentTimelineDevolveATrilhaEmOrdemCronologica() {
        MedicalHistory agendada = MedicalHistory.builder()
                .eventId(UUID.randomUUID()).appointmentId(42L).patientId(10L).doctorId(7L)
                .appointmentDate(LocalDateTime.of(2026, 9, 5, 9, 0))
                .eventStatus(AppointmentEventStatus.SCHEDULED)
                .occurredAt(Instant.parse("2026-08-30T14:00:00Z")).build();
        MedicalHistory remarcada = MedicalHistory.builder()
                .eventId(UUID.randomUUID()).appointmentId(42L).patientId(10L).doctorId(7L)
                .appointmentDate(LocalDateTime.of(2026, 9, 12, 14, 0))
                .eventStatus(AppointmentEventStatus.RESCHEDULED)
                .occurredAt(Instant.parse("2026-08-31T10:00:00Z")).build();
        when(repository.findByAppointmentIdOrderByOccurredAtAsc(42L))
                .thenReturn(List.of(agendada, remarcada));

        List<MedicalRecordResponse> trilha = service.appointmentTimeline(42L);

        assertThat(trilha).extracting(MedicalRecordResponse::eventStatus)
                .containsExactly(AppointmentEventStatus.SCHEDULED, AppointmentEventStatus.RESCHEDULED);
        assertThat(trilha).extracting(MedicalRecordResponse::appointmentDate)
                .containsExactly("2026-09-05T09:00:00", "2026-09-12T14:00:00");
    }

    @Test
    void appointmentTimelineDevolveListaVaziaParaConsultaDesconhecida() {
        when(repository.findByAppointmentIdOrderByOccurredAtAsc(404L)).thenReturn(List.of());

        assertThat(service.appointmentTimeline(404L)).isEmpty();
    }

    @Test
    void appointmentTimelineRejeitaAppointmentIdNulo() {
        assertThatThrownBy(() -> service.appointmentTimeline(null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).findByAppointmentIdOrderByOccurredAtAsc(any());
    }
```

O primeiro teste é o que prova o valor do append-only: a data antiga (`2026-09-05T09:00:00`)
continua visível na trilha depois da remarcação.

- [ ] **Step 2: Rodar para confirmar que falha**

Run: `./mvnw test -Dtest=MedicalHistoryQueryServiceTest`
Expected: FAIL na compilação — `cannot find symbol: method appointmentTimeline`.

- [ ] **Step 3: Acrescentar o método ao service**

Em `src/main/java/br/com/tech/challenge/historyservice/services/MedicalHistoryQueryService.java`, acrescente **dentro da classe**, depois de `patientHistory`:

```java
    /**
     * Trilha completa de uma consulta, do evento mais antigo ao mais recente. E o que o log
     * append-only oferece e uma tabela de estado nao: a data anterior de uma consulta remarcada
     * continua visivel na linha anterior.
     */
    public List<MedicalRecordResponse> appointmentTimeline(Long appointmentId) {
        if (appointmentId == null) {
            throw new IllegalArgumentException("appointmentId nao pode ser nulo");
        }

        return repository.findByAppointmentIdOrderByOccurredAtAsc(appointmentId).stream()
                .map(MedicalRecordResponse::from)
                .toList();
    }
```

- [ ] **Step 4: Rodar o teste do service para confirmar que passa**

Run: `./mvnw test -Dtest=MedicalHistoryQueryServiceTest`
Expected: PASS, 8 testes.

- [ ] **Step 5: Acrescentar a query ao schema**

Em `src/main/resources/graphql/schema.graphqls`, dentro do `type Query`, acrescente depois de `patientHistory`:

```graphql
    "Trilha completa de uma consulta, do evento mais antigo ao mais recente."
    appointmentTimeline(appointmentId: ID!): [MedicalRecord!]!
```

- [ ] **Step 6: Acrescentar o teste do resolver (vai falhar)**

Em `src/test/java/br/com/tech/challenge/historyservice/graphql/HistoryQueryControllerTest.java`, acrescente **dentro da classe**:

```java
    @Test
    void appointmentTimelineDevolveATrilhaDaConsulta() {
        when(queryService.appointmentTimeline(42L)).thenReturn(List.of(
                resposta("42", AppointmentEventStatus.SCHEDULED),
                resposta("42", AppointmentEventStatus.RESCHEDULED),
                resposta("42", AppointmentEventStatus.COMPLETED)));

        graphQlTester.document("""
                        query {
                          appointmentTimeline(appointmentId: 42) {
                            eventStatus
                            appointmentDate
                          }
                        }
                        """)
                .execute()
                .path("appointmentTimeline").entityList(Object.class).hasSize(3)
                .path("appointmentTimeline[0].eventStatus").entity(String.class).isEqualTo("SCHEDULED")
                .path("appointmentTimeline[2].eventStatus").entity(String.class).isEqualTo("COMPLETED");
    }

    @Test
    void appointmentTimelineDevolveListaVaziaSemErro() {
        when(queryService.appointmentTimeline(404L)).thenReturn(List.of());

        graphQlTester.document("{ appointmentTimeline(appointmentId: 404) { eventStatus } }")
                .execute()
                .errors().verify()
                .path("appointmentTimeline").entityList(Object.class).hasSize(0);
    }
```

- [ ] **Step 7: Rodar para confirmar que falha**

Run: `./mvnw test -Dtest=HistoryQueryControllerTest`
Expected: FAIL na compilação — `cannot find symbol: method appointmentTimeline`.

- [ ] **Step 8: Acrescentar o resolver**

Em `src/main/java/br/com/tech/challenge/historyservice/graphql/HistoryQueryController.java`, acrescente **dentro da classe**, depois de `patientHistory`:

```java
    @QueryMapping
    public List<MedicalRecordResponse> appointmentTimeline(@Argument Long appointmentId) {
        return queryService.appointmentTimeline(appointmentId);
    }
```

- [ ] **Step 9: Rodar os testes para confirmar que passam**

Run: `./mvnw test -Dtest=MedicalHistoryQueryServiceTest,HistoryQueryControllerTest,GraphQlSchemaTest`
Expected: PASS, 17 testes no total.

- [ ] **Step 10: Commit**

```bash
git add src/main/resources/graphql/schema.graphqls \
        src/main/java/br/com/tech/challenge/historyservice/services/MedicalHistoryQueryService.java \
        src/main/java/br/com/tech/challenge/historyservice/graphql/HistoryQueryController.java \
        src/test/java/br/com/tech/challenge/historyservice/services/MedicalHistoryQueryServiceTest.java \
        src/test/java/br/com/tech/challenge/historyservice/graphql/HistoryQueryControllerTest.java
git commit -m "feat: query appointmentTimeline com a trilha completa da consulta"
```

---

### Task 5: Tratamento de erro

Sem isto, uma exceção do service vira `INTERNAL_ERROR` com a mensagem trocada por "INTERNAL_ERROR for <uuid>" — o cliente não descobre que mandou um argumento inválido. Traduz `IllegalArgumentException` para o erro tipado `BAD_REQUEST` do GraphQL.

**Files:**
- Create: `src/main/java/br/com/tech/challenge/historyservice/graphql/GraphQlExceptionResolver.java`
- Test: `src/test/java/br/com/tech/challenge/historyservice/graphql/GraphQlExceptionResolverTest.java`

**Interfaces:**
- Consumes: `HistoryQueryController` e `MedicalHistoryQueryService` (Tasks 3 e 4).
- Produces: `GraphQlExceptionResolver`, um `@Component` sem API pública própria — o Spring GraphQL o descobre pelo tipo.

- [ ] **Step 1: Escrever o teste (vai falhar)**

Crie `src/test/java/br/com/tech/challenge/historyservice/graphql/GraphQlExceptionResolverTest.java`:

```java
package br.com.tech.challenge.historyservice.graphql;

import br.com.tech.challenge.historyservice.services.MedicalHistoryQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@GraphQlTest(HistoryQueryController.class)
@Import(GraphQlExceptionResolver.class)
class GraphQlExceptionResolverTest {

    @Autowired
    private GraphQlTester graphQlTester;

    @MockitoBean
    private MedicalHistoryQueryService queryService;

    @Test
    void traduzArgumentoInvalidoParaBadRequest() {
        when(queryService.patientHistory(any()))
                .thenThrow(new IllegalArgumentException("patientId nao pode ser nulo"));

        graphQlTester.document("{ patientHistory(patientId: 10) { appointmentId } }")
                .execute()
                .errors()
                .satisfy(erros -> {
                    assertThat(erros).hasSize(1);
                    assertThat(erros.getFirst().getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
                    assertThat(erros.getFirst().getMessage()).isEqualTo("patientId nao pode ser nulo");
                });
    }

    @Test
    void naoVazaDetalheDeErroInesperado() {
        when(queryService.patientHistory(any()))
                .thenThrow(new IllegalStateException("connection pool exhausted at 10.0.0.5:5432"));

        graphQlTester.document("{ patientHistory(patientId: 10) { appointmentId } }")
                .execute()
                .errors()
                .satisfy(erros -> {
                    assertThat(erros).hasSize(1);
                    assertThat(erros.getFirst().getMessage()).doesNotContain("10.0.0.5");
                    assertThat(erros.getFirst().getMessage()).doesNotContain("pool");
                });
    }
}
```

O segundo teste é de segurança: um erro inesperado não pode devolver detalhe de infraestrutura
para o cliente. Ele passa pelo comportamento padrão do Spring GraphQL (que mascara o que não é
tratado), e existe para travar esse comportamento caso alguém adicione um `catch` genérico depois.

- [ ] **Step 2: Rodar para confirmar que falha**

Run: `./mvnw test -Dtest=GraphQlExceptionResolverTest`
Expected: FAIL na compilação — `cannot find symbol: class GraphQlExceptionResolver`.

- [ ] **Step 3: Criar o resolver de exceção**

Crie `src/main/java/br/com/tech/challenge/historyservice/graphql/GraphQlExceptionResolver.java`:

```java
package br.com.tech.challenge.historyservice.graphql;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

/**
 * Traduz excecoes dos resolvers para erros tipados do GraphQL.
 *
 * Sem isto, tudo vira INTERNAL_ERROR com a mensagem substituida por um id opaco, e o cliente nao
 * descobre que enviou um argumento invalido. O que nao e tratado aqui continua mascarado de
 * proposito -- nao devolvemos detalhe de infraestrutura ao cliente.
 */
@Slf4j
@Component
public class GraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {

    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        if (ex instanceof IllegalArgumentException) {
            return GraphqlErrorBuilder.newError(env)
                    .errorType(ErrorType.BAD_REQUEST)
                    .message(ex.getMessage())
                    .build();
        }

        log.error("Erro nao tratado no resolver {}", env.getField().getName(), ex);
        return null;
    }
}
```

Devolver `null` entrega a exceção ao tratamento padrão do Spring GraphQL, que a mascara. O `log.error`
garante que o detalhe real fique no log do servidor, onde é útil, e não na resposta.

`org.springframework.graphql.execution.ErrorType` é um enum próprio do Spring GraphQL que
implementa `graphql.ErrorClassification` — não confunda com `graphql.ErrorType`, que é outro tipo e
não tem o valor `BAD_REQUEST`. Na resposta JSON ele aparece em `errors[0].extensions.classification`
como `"BAD_REQUEST"`.

- [ ] **Step 4: Rodar o teste para confirmar que passa**

Run: `./mvnw test -Dtest=GraphQlExceptionResolverTest`
Expected: PASS, 2 testes.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/br/com/tech/challenge/historyservice/graphql/GraphQlExceptionResolver.java \
        src/test/java/br/com/tech/challenge/historyservice/graphql/GraphQlExceptionResolverTest.java
git commit -m "feat: traduz argumento invalido para erro tipado do GraphQL"
```

---

### Task 6: Teste de integração ponta a ponta

Prova o caminho completo com Postgres e RabbitMQ reais: um evento publicado na fila aparece na resposta GraphQL. Os testes anteriores usam mock do service ou do banco — este não usa nenhum.

**Files:**
- Test: `src/test/java/br/com/tech/challenge/historyservice/graphql/HistoryGraphQlIT.java`

**Interfaces:**
- Consumes: `AppointmentEventDTO` (Fase 1), `PostgresTestcontainers`, `RabbitTestcontainers`, ambas as queries (Tasks 3 e 4).
- Produces: nada em código.

- [ ] **Step 1: Escrever o teste de integração (vai falhar)**

Crie `src/test/java/br/com/tech/challenge/historyservice/graphql/HistoryGraphQlIT.java`:

```java
package br.com.tech.challenge.historyservice.graphql;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import br.com.tech.challenge.historyservice.domain.AppointmentEventStatus;
import br.com.tech.challenge.historyservice.dto.AppointmentEventDTO;
import br.com.tech.challenge.historyservice.repositories.MedicalHistoryRepository;
import br.com.tech.challenge.historyservice.support.PostgresTestcontainers;
import br.com.tech.challenge.historyservice.support.RabbitTestcontainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.HttpGraphQlTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Fluxo completo: evento publicado no RabbitMQ real -> gravado no Postgres real -> lido por
 * GraphQL sobre HTTP. Nada mockado.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureHttpGraphQlTester
@Import({PostgresTestcontainers.class, RabbitTestcontainers.class})
class HistoryGraphQlIT {

    private static final LocalDateTime DATA_ORIGINAL = LocalDateTime.of(2026, 9, 5, 9, 0);
    private static final LocalDateTime NOVA_DATA = LocalDateTime.of(2026, 9, 12, 14, 0);

    @Autowired
    private HttpGraphQlTester graphQlTester;

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

    private void publica(AppointmentEventStatus eventStatus, LocalDateTime appointmentDate, Instant occurredAt) {
        rabbitTemplate.convertAndSend(exchange, routingKey, new AppointmentEventDTO(
                UUID.randomUUID(), eventStatus, occurredAt, 42L, 10L, "Maria Souza",
                7L, "Dr. Joao Lima", appointmentDate, "Consulta de rotina"));
    }

    @Test
    void eventoPublicadoNaFilaApareceNoPatientHistory() {
        publica(AppointmentEventStatus.SCHEDULED, DATA_ORIGINAL, Instant.parse("2026-08-30T14:00:00Z"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(repository.findLatestEventPerAppointment(10L)).hasSize(1));

        graphQlTester.document("""
                        query {
                          patientHistory(patientId: 10) {
                            appointmentId
                            eventStatus
                            appointmentDate
                            doctorName
                          }
                        }
                        """)
                .execute()
                .path("patientHistory[0].appointmentId").entity(String.class).isEqualTo("42")
                .path("patientHistory[0].eventStatus").entity(String.class).isEqualTo("SCHEDULED")
                .path("patientHistory[0].appointmentDate").entity(String.class).isEqualTo("2026-09-05T09:00:00")
                .path("patientHistory[0].doctorName").entity(String.class).isEqualTo("Dr. Joao Lima");
    }

    @Test
    void patientHistoryMostraApenasOEstadoAtualDepoisDeRemarcar() {
        publica(AppointmentEventStatus.SCHEDULED, DATA_ORIGINAL, Instant.parse("2026-08-30T14:00:00Z"));
        publica(AppointmentEventStatus.RESCHEDULED, NOVA_DATA, Instant.parse("2026-08-31T10:00:00Z"));
        publica(AppointmentEventStatus.COMPLETED, NOVA_DATA, Instant.parse("2026-09-12T15:00:00Z"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(repository.findByAppointmentIdOrderByOccurredAtAsc(42L)).hasSize(3));

        graphQlTester.document("{ patientHistory(patientId: 10) { appointmentId eventStatus appointmentDate } }")
                .execute()
                .path("patientHistory").entityList(Object.class).hasSize(1)
                .path("patientHistory[0].eventStatus").entity(String.class).isEqualTo("COMPLETED")
                .path("patientHistory[0].appointmentDate").entity(String.class).isEqualTo("2026-09-12T14:00:00");
    }

    @Test
    void appointmentTimelineMostraATrilhaInteiraComADataAntiga() {
        publica(AppointmentEventStatus.SCHEDULED, DATA_ORIGINAL, Instant.parse("2026-08-30T14:00:00Z"));
        publica(AppointmentEventStatus.RESCHEDULED, NOVA_DATA, Instant.parse("2026-08-31T10:00:00Z"));
        publica(AppointmentEventStatus.COMPLETED, NOVA_DATA, Instant.parse("2026-09-12T15:00:00Z"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(repository.findByAppointmentIdOrderByOccurredAtAsc(42L)).hasSize(3));

        graphQlTester.document("{ appointmentTimeline(appointmentId: 42) { eventStatus appointmentDate } }")
                .execute()
                .path("appointmentTimeline").entityList(Object.class).hasSize(3)
                .path("appointmentTimeline[0].eventStatus").entity(String.class).isEqualTo("SCHEDULED")
                .path("appointmentTimeline[0].appointmentDate").entity(String.class).isEqualTo("2026-09-05T09:00:00")
                .path("appointmentTimeline[1].eventStatus").entity(String.class).isEqualTo("RESCHEDULED")
                .path("appointmentTimeline[2].eventStatus").entity(String.class).isEqualTo("COMPLETED");
    }

    @Test
    void pacienteSemHistoricoDevolveListaVazia() {
        graphQlTester.document("{ patientHistory(patientId: 404) { appointmentId } }")
                .execute()
                .errors().verify()
                .path("patientHistory").entityList(Object.class).hasSize(0);
    }
}
```

`appointmentTimelineMostraATrilhaInteiraComADataAntiga` é o teste que fecha o desenho todo: a data
de 5/set continua acessível depois da remarcação para 12/set, enquanto `patientHistory` só mostra a
atual.

- [ ] **Step 2: Rodar para confirmar que falha**

Run: `./mvnw test -Dtest=HistoryGraphQlIT`
Expected: FAIL. Se as Tasks 1-5 estiverem completas, este teste pode passar de primeira — nesse
caso confirme que ele realmente exercita o caminho novo comentando temporariamente o `@QueryMapping`
de `appointmentTimeline` e vendo o teste quebrar, depois restaure.

- [ ] **Step 3: Rodar a suíte completa**

Run: `./mvnw test`
Expected: PASS em tudo — 26 testes da Fase 1 mais os desta fase.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/br/com/tech/challenge/historyservice/graphql/HistoryGraphQlIT.java
git commit -m "test: fluxo completo do RabbitMQ ate a resposta GraphQL"
```

---

### Task 7: Documentação

Entrega a documentação da parte da Pessoa 3 que o plano do grupo cobra, com as queries prontas para a collection de testes.

**Files:**
- Create: `docs/graphql/queries.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: schema e queries das Tasks 1, 3 e 4.
- Produces: nada em código.

- [ ] **Step 1: Escrever a documentação das queries**

Crie `docs/graphql/queries.md`:

````markdown
# Queries GraphQL do history-service

Endpoint: `POST /graphql`
IDE web para explorar o schema: http://localhost:8080/graphiql

O `history-service` é um log **append-only** — cada evento do `appointment-service` vira uma linha
nova. As duas queries oferecem as duas leituras úteis desse log.

## `patientHistory(patientId: ID!)`

Estado **atual** de cada consulta do paciente: uma entrada por consulta, com o evento mais recente.
Ordenadas da consulta com atividade mais recente para a mais antiga.

```graphql
query {
  patientHistory(patientId: 10) {
    appointmentId
    doctorName
    appointmentDate
    eventStatus
    description
  }
}
```

```json
{
  "data": {
    "patientHistory": [
      {
        "appointmentId": "42",
        "doctorName": "Dr. João Lima",
        "appointmentDate": "2026-09-12T14:00:00",
        "eventStatus": "COMPLETED",
        "description": "Atendimento realizado"
      }
    ]
  }
}
```

Paciente sem histórico devolve `[]`, não erro: o `history-service` não conhece o cadastro de
pacientes e não pode afirmar que o paciente não existe.

## `appointmentTimeline(appointmentId: ID!)`

Trilha **completa** de uma consulta, do evento mais antigo ao mais recente.

```graphql
query {
  appointmentTimeline(appointmentId: 42) {
    eventStatus
    appointmentDate
    occurredAt
  }
}
```

```json
{
  "data": {
    "appointmentTimeline": [
      { "eventStatus": "SCHEDULED",   "appointmentDate": "2026-09-05T09:00:00", "occurredAt": "2026-08-30T14:00:00Z" },
      { "eventStatus": "RESCHEDULED", "appointmentDate": "2026-09-12T14:00:00", "occurredAt": "2026-08-31T10:00:00Z" },
      { "eventStatus": "COMPLETED",   "appointmentDate": "2026-09-12T14:00:00", "occurredAt": "2026-09-12T15:00:00Z" }
    ]
  }
}
```

Repare que a data original (5/set) continua visível na primeira linha depois da remarcação para
12/set. É isso que o log append-only entrega e uma tabela de estado não entregaria.

## Campos de `MedicalRecord`

| Campo | Tipo | Observação |
|---|---|---|
| `id` | `ID!` | identificador da **linha do histórico**, não da consulta |
| `appointmentId` | `ID!` | agrupa a trilha |
| `patientId` | `ID!` | |
| `patientName` | `String` | snapshot do evento; pode ser `null` |
| `doctorId` | `ID!` | |
| `doctorName` | `String` | snapshot do evento; pode ser `null` |
| `description` | `String` | pode ser `null` |
| `appointmentDate` | `String!` | início da consulta neste evento, ISO-8601 sem timezone |
| `eventStatus` | `AppointmentEventStatus!` | `SCHEDULED`, `RESCHEDULED`, `CANCELLED` ou `COMPLETED` |
| `occurredAt` | `String!` | quando o evento ocorreu no produtor, ISO-8601 UTC |

Datas são `String` ISO-8601, não um scalar customizado: `graphql-java-extended-scalars` só existe
até a 22.0 enquanto o Boot 4.1 gerencia graphql-java 25.0.

## Testar por `curl`

```bash
curl -s -X POST http://localhost:8080/graphql \
  -H 'content-type: application/json' \
  -d '{"query":"{ patientHistory(patientId: 10) { appointmentId eventStatus appointmentDate } }"}' \
  | python3 -m json.tool
```

## Erros

| Situação | Resposta |
|---|---|
| Paciente ou consulta sem registros | `data` com lista vazia, sem `errors` |
| Argumento ausente ou de tipo errado | `errors[0].extensions.classification` = `ValidationError` (validação do próprio graphql-java) |
| Campo inexistente na query | `errors[0].extensions.classification` = `ValidationError` |
| Argumento rejeitado pelo service | `errors[0].extensions.classification` = `BAD_REQUEST` |

## Ainda não implementado

Autorização por role (`PATIENT` só acessa o próprio `patientId`; `DOCTOR`/`NURSE` acessam
qualquer um) entra quando a Pessoa 1 publicar o formato do JWT. Hoje o endpoint está aberto —
apropriado só para ambiente local.
````

- [ ] **Step 2: Acrescentar a seção ao README**

Em `README.md`, acrescente antes da seção `## 6. Rodar os testes`:

````markdown
## 5b. Consultar o histórico por GraphQL

Com a aplicação no ar, o schema pode ser explorado em http://localhost:8080/graphiql.

Estado atual de cada consulta do paciente:

```bash
curl -s -X POST http://localhost:8080/graphql \
  -H 'content-type: application/json' \
  -d '{"query":"{ patientHistory(patientId: 10) { appointmentId eventStatus appointmentDate doctorName } }"}' \
  | python3 -m json.tool
```

Trilha completa de uma consulta:

```bash
curl -s -X POST http://localhost:8080/graphql \
  -H 'content-type: application/json' \
  -d '{"query":"{ appointmentTimeline(appointmentId: 42) { eventStatus appointmentDate occurredAt } }"}' \
  | python3 -m json.tool
```

As duas queries, os campos disponíveis e os formatos de resposta estão em
[`docs/graphql/queries.md`](docs/graphql/queries.md).

> O endpoint `/graphql` está **aberto** nesta fase. A autorização por role entra quando a Pessoa 1
> publicar o formato do JWT.
````

- [ ] **Step 3: Verificar que a build continua verde**

Run: `./mvnw test`
Expected: PASS em tudo.

- [ ] **Step 4: Commit**

```bash
git add docs/graphql/queries.md README.md
git commit -m "docs: documenta as queries GraphQL do historico"
```

---

## Verificação manual ao final

Com `docker compose up -d` e a aplicação no ar, publique os três eventos da consulta 42 conforme o
README (seção 5), depois:

```bash
curl -s -X POST http://localhost:8080/graphql -H 'content-type: application/json' \
  -d '{"query":"{ patientHistory(patientId: 10) { appointmentId eventStatus appointmentDate } }"}' \
  | python3 -m json.tool
```

Esperado: **uma** entrada, com `eventStatus: "COMPLETED"` e a data remarcada.

```bash
curl -s -X POST http://localhost:8080/graphql -H 'content-type: application/json' \
  -d '{"query":"{ appointmentTimeline(appointmentId: 42) { eventStatus appointmentDate } }"}' \
  | python3 -m json.tool
```

Esperado: **três** entradas, a primeira com a data original de `2026-09-05T09:00:00`.

## Fora de escopo desta fase

- **Spring Security / autorização por role.** Plano próprio, quando a Pessoa 1 publicar o formato do
  JWT e os nomes das claims. O ponto de extensão é o `MedicalHistoryQueryService`.
- **Paginação.** `patientHistory` devolve todas as consultas do paciente. Para o volume do Tech
  Challenge não há problema; se virar requisito, o lugar é o argumento da query e o `Pageable` no
  repository.
- **`@BatchMapping`.** Só faria sentido se `MedicalRecord` ganhasse um campo aninhado resolvido por
  outra fonte. Hoje todos os campos vêm da própria linha, então não há N+1 a resolver.
