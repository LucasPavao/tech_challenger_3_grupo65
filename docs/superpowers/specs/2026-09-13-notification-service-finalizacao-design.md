# Finalização do notification-service — design

**Data:** 2026-09-13
**Status:** aprovado em chat, aguardando revisão da spec
**Branch:** `feat/notificationService` → PR para `main`
**Fonte dos requisitos:** *Plano de Divisão do Projeto — Tech Challenge Fase 3*, seção 4 (Pessoa 5), seção 8 (testes) e seção 11 (checklist final)

## Contexto

O PDF atribui à Pessoa 5 o notification-service, com estes critérios:

1. Criar o notification-service.
2. Implementar o consumer do RabbitMQ.
3. Receber o `AppointmentEvent`.
4. Processar eventos de criação e edição de consulta.
5. Criar e persistir a notificação.
6. Simular o envio do lembrete por log ou por mecanismo definido pelo grupo.

Testes mínimos exigidos: recebimento do evento, criação e processamento da notificação. O checklist
final cobra que os serviços subam, que o Notification Service receba o evento, que a notificação seja
processada, que a collection esteja atualizada e que a documentação esteja completa.

### Estado de partida

- A `main` já contém o notification-service integrado (PR #8): container no compose da raiz, consumo
  da `appointment.exchange` pela `notification.queue`, contrato de evento atual (`eventStatus`),
  envio simulado por log e 8 testes verdes. Isso foi validado ponta a ponta.
- A `feat/notificationService` contém os 3 commits originais da Pessoa 5 e um merge local de uma
  `main` anterior ao PR #8. Seu notificationservice é idêntico ao commit `1e04d18`, que é ancestral
  do PR #8: tudo o que existe nela já está na `main`, em versão evoluída. Trazer a `main` gera 12
  conflitos add/add, porque os merges por squash cortam a ancestralidade.

### Lacunas em relação aos critérios

| Critério | Situação na `main` | Lacuna |
|---|---|---|
| 1–4, 6 | Atendidos | — |
| 5. Persistir a notificação | Funciona | Schema gerado por `ddl-auto=update`, com Flyway ligado e sem migration; diverge dos outros serviços |
| Testes de recebimento, criação e processamento | Só teste unitário com mocks e `contextLoads` | Faltam recebimento via RabbitMQ, persistência real, DLQ e consulta |
| Robustez do consumer | — | Evento não é validado (payload sem `appointmentDate` seria gravado); sem idempotência (reentrega duplica a notificação) |
| Organização | Pasta `notificationservice` | O PDF e os outros serviços usam `<nome>-service`; `EventType` sem uso |

## Objetivos

1. Cumprir integralmente os critérios e os testes mínimos da Pessoa 5.
2. Garantir que cada evento gere **no máximo uma** notificação gravada (idempotência por `eventId`).
3. Rejeitar eventos inválidos para a DLQ sem gravar nada.
4. Versionar o schema com Flyway, como os demais serviços.
5. Manter o notification-service subindo e integrado via `docker compose` e RabbitMQ, com o
   `make smoke` comprovando que a notificação chega.
6. Entregar a branch pronta para PR na `main`.

## Não-objetivos

- **Autenticação e autorização** no endpoint de notificações. O auth-service (Pessoa 1) ainda não
  está na `main`; proteger a rota agora quebraria a collection e o `make smoke`. Fica documentado
  como ponto de integração.
- **Lembrete agendado** antes da data da consulta. A notificação continua imediata, por evento.
- **Envio real** (e-mail, SMS). Continua simulado por log, atrás da interface `NotificationSender`.
- **`futureAppointments`** no history-service: é critério da Pessoa 3 e não está no schema GraphQL
  atual. Registrado aqui apenas como observação para o checklist do grupo.

## Decisões

| # | Decisão | Alternativas descartadas |
|---|---|---|
| 1 | Notificação imediata por evento | Lembrete agendado; imediata e agendada |
| 2 | Segurança fora deste trabalho, documentada para a Pessoa 1 | Aplicar JWT já; JWT com regra por papel |
| 3 | Renomear `notificationservice` para `notification-service` | Manter o nome atual |
| 4 | Merge da `main` na branch, resolvendo conflitos com a versão da `main` | Recriar a branch com force-push; branch nova a partir da `main` |
| 5 | Idempotência pelo `eventId`, com `UNIQUE(event_id)` no banco, no padrão do history-service | Sem idempotência; chave derivada de `appointmentId` e `eventStatus` |
| 6 | Ambientes existentes recriam só o volume do banco do notification | Migration com `DROP TABLE IF EXISTS`; migration com `IF NOT EXISTS` e `ALTER TABLE` |

## Design

### 1. Reconciliação da branch

- `git merge origin/main` na `feat/notificationService`. Nos 12 arquivos em conflito dentro de
  `notificationservice/`, fica a versão da `main`. Nenhum trabalho da Pessoa 5 se perde: o que só
  existe na versão dela são os desenhos substituídos pelo grupo (exchange própria, DTO mutável com
  `dateTime` e `eventType`).
- Depois do merge, a branch precisa estar idêntica à `main` no notificationservice e com as três
  suítes verdes (appointment, history e notification), antes de qualquer outra alteração.
- `git mv notificationservice notification-service`. Atualizar as referências de caminho no
  `docker-compose.yml` da raiz (2 linhas) e no `README.md` da raiz (4 linhas). O pacote Java
  `br.com.tech.challenge.notificationservice` e o `spring.application.name` não mudam.
- Remover `dto/EventType.java`, sem uso.

### 2. Processamento e idempotência

`NotificationService.processAppointmentEvent(AppointmentEvent)` passa a seguir, nesta ordem:

1. **Evento nulo:** lança `IllegalArgumentException`.
2. **Validação:** valida com o `jakarta.validation.Validator` injetado. Com violações, lança
   `ConstraintViolationException`. O listener não captura: a mensagem é rejeitada e, como
   `default-requeue-rejected=false`, vai para a `notification.queue.dlq`. Nada é gravado.
3. **Deduplicação por `eventId`:** consulta `NotificationRepository.findByEventId(UUID)`.
   - Existe com status `SENT`: registra log WARN e devolve a notificação existente, sem gravar nem
     enviar.
   - Existe com status `PENDING` (envio anterior falhou): reenvia usando **a mesma linha**. Sucesso
     marca `SENT` e salva; falha repete o tratamento do passo 5.
   - Não existe: segue para o passo 4.
4. **Criação:** monta a notificação com `eventId`, `eventStatus`, `appointmentId`, `patientId`,
   `message`, `createdAt` e status `PENDING`, e salva.
   - Se o banco lançar `DataIntegrityViolationException` (outro consumidor gravou o mesmo `eventId`
     entre a consulta e a inserção), registra log WARN, relê com `findByEventId` e devolve a linha
     existente, sem enviar.
5. **Envio:** chama `NotificationSender.send`. Sucesso marca `SENT` e salva. Falha mantém `PENDING`
   e lança `RuntimeException` com a causa, o que manda a mensagem para a DLQ.

O método sempre devolve **a única** notificação associada ao `eventId`.

**Sem `@Transactional`**, pelo mesmo motivo documentado no `HistoryIngestionService`: com uma
transação no método, a violação de `UNIQUE(event_id)` marcaria a transação como rollback-only e o
commit lançaria `UnexpectedRollbackException` fora do `catch`, mandando para a DLQ um evento já
gravado. Cada `save` roda na transação do próprio repositório.

A mensagem gravada no `PENDING` é reaproveitada no reenvio, sem ser recalculada.

**Modelo `Notification`** — os campos do PDF (`id`, `appointmentId`, `patientId`, `message`,
`createdAt`, `status`) mais:

| Campo | Mapeamento | Motivo |
|---|---|---|
| `eventId` (`UUID`) | `event_id`, `nullable=false`, `unique=true`, `updatable=false` | Chave de idempotência |
| `eventStatus` (`AppointmentEventStatus`) | `event_status`, `@Enumerated(STRING)`, `nullable=false` | Registra qual transição gerou a notificação |

`NotificationRepository` ganha `Optional<Notification> findByEventId(UUID eventId)`.

### 3. Persistência

Nova migration `notification-service/src/main/resources/db/migration/V1__create_notifications.sql`:

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

- `message` é `VARCHAR(255)` para casar com o mapeamento padrão de `String` sob
  `ddl-auto=validate`. As mensagens geradas têm menos de 60 caracteres.
- `spring.jpa.hibernate.ddl-auto` muda de `update` para `validate`.
- A constraint `uk_notifications_event_id` é a garantia final da idempotência; a consulta do passo 3
  apenas evita a exceção no caso comum.

#### Ambientes que já rodaram o notification-service

Nesses ambientes existe uma tabela `notifications` criada pelo Hibernate, sem `event_id` e
`event_status`, e sem histórico do Flyway. Com `baseline-on-migrate=true` e `baseline-version=0`, o
Flyway registra o banco como versão 0 e executa a V1, cujo `CREATE TABLE` falha porque a tabela já
existe; o serviço não sobe.

Correção documentada: recriar apenas o volume do banco do notification. Os dados são notificações de
desenvolvimento, e novos agendamentos voltam a gerá-las. Instalações novas não passam por isso.

### 4. Testes

Todos rodam com `./mvnw test` dentro de `notification-service/`, com Testcontainers para Postgres e
RabbitMQ (classes de suporte já existentes) e Awaitility como nova dependência de teste.

| Teste | Tipo | Casos | Critério do PDF |
|---|---|---|---|
| `messaging/NotificationMessageListenerIT` | `@SpringBootTest` com Postgres e RabbitMQ reais | (a) evento publicado na exchange e routing key configuradas vira notificação `SENT` com mensagem do status; (b) o mesmo evento publicado duas vezes gera uma única linha; (c) JSON com campo desconhecido vai para a DLQ e não grava nada; (d) JSON sem `appointmentDate` vai para a DLQ e não grava nada | Recebimento, criação e processamento |
| `service/NotificationServiceTest` (ampliado) | Unitário, Mockito | Mensagem para cada `eventStatus`; `PENDING` no envio e `SENT` ao final; falha no envio mantém `PENDING` e propaga; evento nulo e evento inválido rejeitados sem gravar; `SENT` existente devolvido sem gravar nem enviar; `PENDING` existente reenviado na mesma linha; `DataIntegrityViolationException` na inserção devolve a linha existente sem enviar | Processamento |
| `service/NotificationServicePersistenceTest` | `@DataJpaTest` com Postgres real e transação do teste desligada | Corrida entre consumidores: a segunda inserção do mesmo `eventId` colide com a constraint real; o serviço não lança exceção, devolve a linha existente e não envia de novo; o banco fica com uma única linha | Persistência e idempotência |
| `NotificationSchemaTest` | `@DataJpaTest` com Postgres real | A migration cria todas as colunas; existe exatamente uma constraint única sobre `event_id` | Persistência |
| `controller/NotificationControllerTest` | `@WebMvcTest` | `GET /notifications/patient/{patientId}` devolve 200 com as notificações do serviço para aquele paciente | Consulta das notificações |

Os padrões de referência são `AppointmentEventListenerIT`, `HistoryIngestionServicePersistenceTest`
e `MedicalHistorySchemaTest`, do history-service. O `@BeforeEach` do teste de integração limpa a
tabela e drena a DLQ.

**Como o teste de persistência reproduz a corrida.** Com a deduplicação funcionando, o segundo
processamento encontraria a linha e nunca chegaria ao banco, sem provar nada sobre a constraint.
Seguindo o `IngestaoConfig` do history, o teste registra o serviço como `@Bean` com um repositório
que delega ao real em tudo, exceto em `findByEventId`: as duas primeiras chamadas — a checagem do
primeiro e a do segundo processamento — devolvem vazio, e as seguintes delegam ao repositório real,
para que a releitura após a colisão encontre a linha gravada. O `NotificationSender` é um mock,
para verificar que o envio acontece uma única vez.

### 5. Integração, validação e documentação

**`scripts/smoke-test.sh`** passa a cobrir os dois consumidores:

1. Espera `UP` nos healths do appointment (8080), do history (8081) e do notification (8082).
2. Cria um agendamento com `patientId` aleatório.
3. Espera o evento no history via GraphQL.
4. Espera, em `GET /notifications/patient/{patientId}`, uma notificação com status `SENT`.
5. Só então imprime `SUCESSO`. Cada etapa tem mensagem de falha própria com o comando de diagnóstico.

A descrição do alvo `smoke` no `Makefile` é atualizada para mencionar o notification.

**README da raiz:**

- Caminhos renomeados para `notification-service`.
- Seção "Atualizando de uma versão anterior": depois do `git pull`, a pasta `notificationservice/`
  sobra apenas com arquivos ignorados; o guia confirma com `git ls-files notificationservice`
  (saída vazia) e manda apagá-la. O guia também recria o volume do banco do notification antes do
  `make build`.
- Tabela de problemas: nova linha para a falha da migration por tabela existente, usando a mensagem
  observada na validação do ambiente real.
- Nota de integração: o notification-service ainda não exige JWT; na integração do auth-service,
  aplicar o mesmo `SecurityConfig` de resource server usado no appointment e no history (health
  público, demais rotas autenticadas).

**README do notification-service:** idempotência por `eventId`, validação e DLQ, migration Flyway,
lista atualizada de testes e a mesma nota de integração de segurança.

**Collection:** a pasta `3. Notification (REST)` já atende ao item "Notifications" do PDF e não muda.

## Critérios de aceite deste trabalho

1. As três suítes de testes (appointment, history e notification) passam com `./mvnw test`.
2. Nenhum arquivo em `notificationservice/` é rastreado pelo git; o serviço vive em
   `notification-service/`.
3. Num clone limpo da branch: `make setup` e `make up` deixam 7 containers healthy; `make smoke`
   termina com `SUCESSO` comprovando history **e** notification; a collection roda sem falhas.
4. Publicar o mesmo evento duas vezes na `appointment.exchange` com a routing key
   `notification.created` resulta em exatamente uma linha em `notifications`.
5. Publicar um evento sem `appointmentDate` resulta em mensagem na `notification.queue.dlq` e
   nenhuma linha gravada.
6. Num ambiente que já rodou a versão anterior — verificando antes que a tabela `notifications`
   existe sem a coluna `event_id` e sem a tabela `flyway_schema_history` —: sem o passo de recriar o
   volume, o notification-service não sobe por falha da migration; seguindo o guia, sobe e processa
   eventos.
7. A branch é integrada à `main` por PR.
