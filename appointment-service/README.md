# Appointment Service

Microsserviço responsável pelo agendamento de consultas do Tech Challenge FIAP - Fase 3 - Grupo 65.

## Responsabilidades

- Criar consultas.
- Consultar uma ou todas as consultas.
- Consultar consultas de um paciente.
- Editar consultas.
- Alterar status da consulta.
- Persistir consultas no PostgreSQL.
- Publicar `AppointmentEvent` no RabbitMQ para o history-service e, quando habilitado, para o notification-service.

## Tecnologias

- Java 21
- Spring Boot 4.1.0
- Spring Web MVC
- Spring Data JPA
- PostgreSQL
- Flyway
- RabbitMQ
- Bean Validation
- Actuator
- Lombok

## Subir infraestrutura

```bash
cp .env.example .env
docker compose up -d
```

O serviço usa a porta `8081` por padrão para não conflitar com o history-service, que atualmente usa `8080`.

## Executar

```bash
./mvnw spring-boot:run
```

Ou, se Maven estiver instalado:

```bash
mvn spring-boot:run
```

## Endpoints

| Método | Endpoint | Descrição |
|---|---|---|
| POST | `/appointments` | Cria consulta |
| GET | `/appointments/{id}` | Busca consulta |
| GET | `/appointments` | Lista consultas |
| GET | `/appointments/patient/{patientId}` | Lista consultas do paciente |
| PUT | `/appointments/{id}` | Edita consulta |
| PATCH | `/appointments/{id}/status` | Altera status |
| GET | `/actuator/health` | Health check |

## Exemplo de criação

```json
{
  "patientId": 10,
  "doctorId": 7,
  "appointmentDate": "2026-10-10T09:00:00",
  "description": "Consulta de rotina - cardiologia"
}
```

## Status

Persistidos na consulta:

- `SCHEDULED`
- `COMPLETED`
- `CANCELLED`

Eventos publicados:

- `SCHEDULED` ao criar.
- `RESCHEDULED` ao alterar data/hora.
- `COMPLETED` ao concluir.
- `CANCELLED` ao cancelar.

## Contrato do evento

O payload segue o contrato usado pelo history-service:

```json
{
  "eventId": "uuid",
  "eventStatus": "SCHEDULED",
  "occurredAt": "2026-09-07T16:00:00Z",
  "appointmentId": 42,
  "patientId": 10,
  "patientName": null,
  "doctorId": 7,
  "doctorName": null,
  "appointmentDate": "2026-10-10T09:00:00",
  "description": "Consulta de rotina - cardiologia"
}
```

`patientName` e `doctorName` ficam nulos porque o appointment-service mantém apenas os IDs de entidades pertencentes a outros serviços.

## RabbitMQ

Por padrão o produtor publica para:

- Exchange: `history.exchange`
- Routing key: `history.created`

O envio para notification-service está preparado, mas desligado por padrão (`PUBLISH_NOTIFICATION=false`) até o grupo definir o contrato/topologia final do serviço de notificações.

## Regras de negócio implementadas

- Não permite criar/editar com data passada.
- Não permite editar consulta cancelada ou concluída.
- Não permite mudar status de consulta cancelada ou concluída.
- Não permite repetir o mesmo status.
- `SCHEDULED -> COMPLETED` é permitido.
- `SCHEDULED -> CANCELLED` é permitido.

## Testes

```bash
./mvnw test
```

Os testes unitários da camada de serviço cobrem criação, consulta, atualização, alteração de status, regras de negócio e publicação dos eventos.

## Integração com Security

A autorização por `@PreAuthorize` deve ser adicionada na etapa de integração com o serviço de Security da Pessoa 1, após o grupo padronizar o JWT e as roles.
