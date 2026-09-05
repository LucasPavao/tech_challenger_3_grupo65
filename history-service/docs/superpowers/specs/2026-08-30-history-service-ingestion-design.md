# History Service — Design (Fase 1: Ingestão)

**Autor:** Pessoa 3 (Guilherme) — Tech Challenge FIAP Fase 3, Grupo 65
**Data:** 2026-08-30
**Escopo desta fase:** consumir `AppointmentEvent` do RabbitMQ e gravar o histórico. GraphQL fica para a Fase 2.

## 1. Responsabilidade do serviço

O `history-service` é o **registro imutável de tudo que aconteceu com as consultas**. Ele não
agenda, não edita e não é fonte da verdade de nada — apenas escuta eventos do `appointment-service`
e acumula linhas.

Decisões tomadas com o time (registradas aqui porque contradizem o plano original em pontos):

| Decisão | Motivo |
|---|---|
| **Sem `futureAppointments`** | Consultas futuras são estado, e estado pertence ao `appointment-service`. O plano original pedia essa query; foi descartada deliberadamente. |
| **Append-only, sem `UPDATE`** | Cada evento vira uma linha nova. O histórico de um `appointmentId` é o conjunto de suas linhas ordenado no tempo. |
| **Uma única tabela** | Cópias locais de `patient`/`doctor` precisariam de eventos do user-service, que não existem no contrato do grupo. Nome de médico/paciente entra como *snapshot* nullable. |
| **Sem relacionamento JPA cruzando serviço** | Exigência da seção 5 do plano do grupo: só `patientId`, `doctorId`, `appointmentId`. |

## 2. Contrato da mensagem (`AppointmentEvent`)

Publicado pelo `appointment-service` (Pessoa 2), topologia definida pela Pessoa 4.
**Este contrato estende o modelo do PDF** com três campos que a ingestão precisa: `eventId`,
`status` e `occurredAt`.

```json
{
  "eventId": "8f14e45f-ceea-467a-9f4b-1d2c3e4f5a6b",
  "eventStatus": "SCHEDULED",
  "occurredAt": "2026-08-30T14:32:10Z",
  "appointmentId": 42,
  "patientId": 10,
  "patientName": "Maria Souza",
  "doctorId": 7,
  "doctorName": "Dr. João Lima",
  "appointmentDate": "2026-09-05T09:00:00",
  "description": "Consulta de rotina - cardiologia"
}
```

| Campo | Tipo | Obrigatório | Observação |
|---|---|---|---|
| `eventId` | UUID (string) | sim | **Único por evento.** Chave de deduplicação — o produtor gera um novo a cada publicação, inclusive em republicação. |
| `eventStatus` | enum `SCHEDULED` \| `RESCHEDULED` \| `CANCELLED` \| `COMPLETED` | sim | A transicao declarada pelo produtor. |
| `occurredAt` | ISO-8601 **com `Z`** (Instant) | sim | Quando o fato ocorreu no produtor. Ordena a trilha. |
| `appointmentId` | integer (int64) | sim | Agrupa a trilha. |
| `patientId` | integer (int64) | sim | |
| `patientName` | string | **não** | Snapshot. `null` é aceito. |
| `doctorId` | integer (int64) | sim | |
| `doctorName` | string | **não** | Snapshot. `null` é aceito. |
| `appointmentDate` | ISO-8601 **sem timezone** (LocalDateTime) | sim | Inicio da consulta neste evento. Nunca nula, nem em `CANCELLED`. |
| `description` | string | não | |

Campo desconhecido no JSON causa falha de desserialização
(`spring.jackson.deserialization.fail-on-unknown-properties=true`, já configurado) — a mensagem vai
para a DLQ em vez de ser silenciosamente truncada.

## 3. Modelo de dados

Tabela única `medical_history`. Nenhuma coluna é atualizada após o insert.

```sql
id             BIGSERIAL PRIMARY KEY
event_id       UUID         NOT NULL UNIQUE   -- deduplicação de redelivery
appointment_id BIGINT       NOT NULL
patient_id     BIGINT       NOT NULL
patient_name   VARCHAR(255) NULL              -- snapshot
doctor_id      BIGINT       NOT NULL
doctor_name    VARCHAR(255) NULL              -- snapshot
description    TEXT         NULL
appointment_date TIMESTAMP  NOT NULL          -- inicio da consulta neste evento (sem tz)
event_status   VARCHAR(20)  NOT NULL
occurred_at    TIMESTAMPTZ  NOT NULL          -- quando o evento ocorreu
recorded_at    TIMESTAMPTZ  NOT NULL DEFAULT now()

INDEX (patient_id, occurred_at DESC)
INDEX (appointment_id, occurred_at ASC)
```

`UNIQUE(event_id)` é o mecanismo de idempotência: com append-only não existe `UPSERT` para absorver
uma reentrega do RabbitMQ, então a duplicata é barrada pelo banco.

## 4. Fluxo de ingestão

```
RabbitMQ (history.queue)
  → AppointmentEventListener        desserializa em AppointmentEventDTO
      → HistoryIngestionService     valida (Bean Validation) → mapeia → save()
          → MedicalHistoryRepository
```

Tratamento de falha:

| Situação | Comportamento | Destino |
|---|---|---|
| JSON malformado / campo desconhecido | Conversão falha antes do listener | DLQ |
| Campo obrigatório ausente ou enum inválido | `ConstraintViolationException` | DLQ |
| `event_id` já existe | `DataIntegrityViolationException` capturada, log em `WARN`, ACK | descartada (sucesso) |
| Banco indisponível | exceção propaga | DLQ |

`spring.rabbitmq.listener.simple.default-requeue-rejected=false` (já configurado) garante que nada
entre em loop infinito de reentrega. A DLQ (`history.queue.dlq`) já está declarada em
`config/RabbitMQConfig.java`.

## 5. Correções de fundação necessárias

O esqueleto atual tem problemas que bloqueiam a fase 1:

- `db/migration/V1__create_medical_history_table.sql` tem vírgula sobrando antes do `)` — **não roda**.
  Como nunca foi aplicada com sucesso, é reescrita no lugar em vez de ganhar uma `V2`.
- `spring.jpa.hibernate.ddl-auto=update` conflita com o Flyway. Passa a `none`.
- `MedicalHistoryRepository` não estende `JpaRepository`.
- `MedicalHistory` usa `@Data` (gera setters) — incompatível com entidade imutável.

## 6. Testes

| Nível | Ferramenta | Cobre |
|---|---|---|
| Desserialização | `@JsonTest` + `JacksonTester` | contrato da mensagem, campos nullable, enums |
| Persistência | `@DataJpaTest` + Testcontainers Postgres | migration, insert, violação do `UNIQUE(event_id)` |
| Serviço | JUnit + Mockito | mapeamento, validação, idempotência |
| Ponta a ponta | `@SpringBootTest` + Testcontainers Postgres **e** RabbitMQ | publica na exchange real → linha no banco |

Testcontainers 2.0.5 (gerenciado pelo Spring Boot 4.1.0): artefatos
`org.testcontainers:testcontainers-postgresql` e `testcontainers-rabbitmq`, pacotes
`org.testcontainers.postgresql` / `org.testcontainers.rabbitmq`.

## 7. Fora de escopo (Fase 2)

- Schema e resolvers GraphQL
- Spring Security / autorização por role
- **Pendência aberta:** `patientHistory(patientId)` devolve a trilha crua ou o último evento de cada
  `appointmentId` (com `appointmentTimeline(appointmentId)` para a trilha)? A decisão não afeta a
  Fase 1 — o modelo append-only suporta as duas leituras.

---

## 8. Fase 2a — decisões de leitura (GraphQL)

Resolvem a pendência da seção 7.

| Decisão | Escolha | Motivo |
|---|---|---|
| `patientHistory(patientId)` | último evento de cada `appointmentId` | uma entrada por consulta é o que serve para tela; a trilha completa fica na segunda query |
| Trilha de uma consulta | `appointmentTimeline(appointmentId)` | devolve as duas queries que o plano do grupo pedia, no lugar da `futureAppointments` descartada |
| Datas no schema | `String` ISO-8601 | `graphql-java-extended-scalars` está na 22.0 enquanto o Boot 4.1 gerencia graphql-java 25.0 — dependência não gerenciada não compensa por um scalar |
| Security | fora desta fase | a Pessoa 1 ainda não publicou o formato do JWT e as roles; chutar o contrato geraria retrabalho |

O ponto de extensão para autorização é o `MedicalHistoryQueryService`: a checagem
"`PATIENT` só acessa o próprio `patientId`" depende do valor do argumento, então mora no service,
não numa anotação de resolver.
