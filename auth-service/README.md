# Auth Service

Serviço de usuários, autenticação e emissão de tokens JWT do Tech Challenge FIAP — Fase 3 — Grupo 65.

## Responsabilidades

- Autenticar usuários por e-mail e senha (HTTP Basic).
- Emitir tokens JWT assinados com RSA, válidos por 15 minutos.
- Gerenciar usuários e suas roles (somente ADMIN).

A autorização das rotas de agendamento, histórico e notificações é aplicada por cada serviço, que
valida o token com a chave pública.

## Tecnologias

Java 21, Spring Boot 4.1, Spring Security (HTTP Basic + OAuth2 Resource Server), Spring Data JPA,
PostgreSQL, Flyway, Bean Validation e Lombok.

## Subindo

O caminho normal é pela raiz do monorepo, junto com os demais serviços — veja o
[Início rápido](../README.md#início-rápido). O compose ativa o profile `dev` (usuários de exemplo)
e monta as chaves geradas pelo serviço `jwt-keys` em `.jwt-keys/`.

| Endereço | O quê |
|---|---|
| <http://localhost:8083/auth/login> | login |
| <http://localhost:8083/actuator/health> | health check público |
| `localhost:5435` | PostgreSQL `auth_db` |

## Rodando pela IDE

1. Na raiz: `make infra` (ou `COMPOSE_PROFILES= docker compose up -d`) — sobe o banco e gera `.jwt-keys/`.
2. Nesta pasta, com o profile `dev`:

```bash
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
```

```powershell
$env:SPRING_PROFILES_ACTIVE="dev"; .\mvnw.cmd spring-boot:run
```

As chaves padrão são `file:../.jwt-keys/app.key` e `file:../.jwt-keys/app.sub`, relativas a esta
pasta. Em outro diretório de trabalho, defina `JWT_PRIVATE_KEY` e `JWT_PUBLIC_KEY` com caminhos absolutos.

## Configuração

| Variável | Padrão | Descrição |
|---|---|---|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `auth_db` / `postgres` / `postgres` | banco |
| `DB_HOST` / `DB_PORT` | `localhost` / `5435` | conexão pela IDE (no Docker: `auth-postgres:5432`) |
| `SERVER_PORT` | `8083` | porta HTTP |
| `JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY` | `file:../.jwt-keys/app.key` / `file:../.jwt-keys/app.sub` | par RSA (no Docker: `file:/keys/...`) |
| `SPRING_PROFILES_ACTIVE` | vazio (no Docker: `dev`) | `dev` insere os usuários de exemplo |

## Usuários de exemplo

Inseridos pela migration `db/dev-seed/V4__seed_users.sql`, só com o profile `dev`. Sem ele, o banco
fica apenas com as roles.

| Role | E-mail | Senha | `user_id` |
|---|---|---|---|
| `ADMIN` | `admin@hospital.com` | `Admin@123` | 1 |
| `DOCTOR` | `joao.silva@hospital.com` | `Doutor@123` | 2 |
| `NURSE` | `maria.santos@hospital.com` | `Enfermeira@123` | 3 |
| `PATIENT` | `lucas.oliveira@hospital.com` | `Paciente@123` | 4 |

São credenciais de desenvolvimento: não use em nenhum ambiente real.

## Autenticação

```bash
curl -s -X POST http://localhost:8083/auth/login -u maria.santos@hospital.com:Enfermeira@123
```

```json
{ "access_token": "eyJ..." }
```

Use o token em `Authorization: Bearer <access_token>` nos outros serviços. Claims:

| Claim | Conteúdo |
|---|---|
| `sub` | e-mail do usuário |
| `user_id` | id do usuário — é o `patientId` de um PATIENT |
| `scope` | `ROLE_ADMIN`, `ROLE_DOCTOR`, `ROLE_NURSE` ou `ROLE_PATIENT` |
| `iss` | `auth-service` |
| `exp` | expiração (15 minutos) |

## Endpoints

| Método | Endpoint | Permissão | Descrição |
|---|---|---|---|
| `POST` | `/auth/login` | Basic Auth | gera um JWT |
| `GET` | `/users` | `ADMIN` | lista usuários |
| `GET` | `/users/{id}` | `ADMIN` | busca usuário |
| `POST` | `/users` | `ADMIN` | cria usuário |
| `PUT` | `/users/{id}` | `ADMIN` | atualiza usuário |
| `DELETE` | `/users/{id}` | `ADMIN` | remove usuário |
| `GET` | `/actuator/health` | pública | health check |

```bash
curl -s -X POST http://localhost:8083/users \
  -H "Authorization: Bearer <token-admin>" -H "Content-Type: application/json" \
  -d '{"name":"Novo Paciente","email":"novo.paciente@hospital.com","password":"Paciente@123","role":"PATIENT"}'
```

O e-mail precisa ser válido e único; se já existir, a resposta é `409 CONFLICT`.

## Testes

```bash
./mvnw test
```

Não precisam de banco nem de `.jwt-keys/`: usam o par só de teste em `src/test/resources/jwt-test/`.

## Solução de problemas

| Problema | Solução |
|---|---|
| login devolve `401` com a senha certa | o profile `dev` não está ativo, então não há usuários |
| `FileNotFoundException ... .jwt-keys/app.key` pela IDE | rode `make infra` na raiz antes, ou defina `JWT_PRIVATE_KEY`/`JWT_PUBLIC_KEY` |
| token recusado nos outros serviços com `401` | token expirado, ou chaves regeneradas depois do login: faça login de novo |
| porta `8083` ou `5435` ocupada | altere `SERVER_PORT` ou `DB_PORT` no `.env` |

## Segurança

- A chave privada fica só em `.jwt-keys/` (ignorada pelo git) e só o auth-service a monta.
- Não reutilize as credenciais de exemplo fora do desenvolvimento.
- Não registre tokens em logs.
