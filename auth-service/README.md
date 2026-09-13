# Auth Service

Serviço responsável pelo gerenciamento de usuários, autenticação e emissão de tokens JWT para os demais serviços do projeto Tech Challenge FIAP — Fase 3, Grupo 65.

## Responsabilidades

- Autenticar usuários por e-mail e senha.
- Emitir tokens JWT assinados com RSA.
- Gerenciar usuários e suas roles.
- Validar permissões administrativas.
- Disponibilizar a chave pública usada por `appointment-service` e `history-service` para validar os tokens.

## Tecnologias

- Java 25
- Spring Boot 4.1.1
- Spring Security
- OAuth2 Resource Server
- Spring Data JPA
- PostgreSQL
- Flyway
- Bean Validation
- Lombok

## Pré-requisitos

- Docker e Docker Compose.
- JDK 25.
- Maven não é necessário: o projeto possui Maven Wrapper (`mvnw`/`mvnw.cmd`).

## Configuração

Na primeira execução, copie o arquivo de exemplo:

```bash
cp .env.example .env
```

No Windows PowerShell:

```powershell
Copy-Item .env.example .env
```

Valores padrão do `.env`:

| Variável | Valor padrão | Descrição |
|---|---|---|
| `POSTGRES_DB` | `auth_db` | Banco do serviço |
| `POSTGRES_USER` | `postgres` | Usuário do banco |
| `POSTGRES_PASSWORD` | `postgres` | Senha do banco |
| `DB_HOST` | `localhost` | Host usado ao executar a aplicação localmente |
| `DB_PORT` | `5434` | Porta publicada do PostgreSQL |
| `SERVER_PORT` | `8082` | Porta HTTP da aplicação |

O arquivo `.env` não deve ser versionado.

## Chaves JWT

O serviço utiliza:

- `src/main/resources/app.key`: chave privada usada para assinar os tokens;
- `src/main/resources/app.sub`: chave pública usada para validar os tokens.

A chave privada nunca deve ser publicada no GitHub ou incluída em uma imagem pública. Em produção, injete-a como secret e substitua a configuração baseada em arquivo de classpath por uma configuração segura.

Os serviços consumidores precisam possuir a chave pública correspondente. A chave pública pode ser distribuída aos serviços, mas a chave privada deve permanecer exclusivamente no `auth-service`.

## Subir somente o banco

Execute os comandos a partir desta pasta (`auth-service`):

```bash
docker compose up -d
```

O Compose sobe o container `auth-service-postgres`.

Verifique o estado:

```bash
docker compose ps
```

## Executar a aplicação localmente

Com o PostgreSQL em execução:

Linux/macOS:

```bash
./mvnw spring-boot:run
```

Windows PowerShell:

```powershell
./mvnw.cmd spring-boot:run
```

O serviço ficará disponível em `http://localhost:8082`.

O Flyway cria as tabelas e insere as roles e usuários padrão automaticamente na primeira execução.

## Executar pela raiz do projeto

Na raiz do monorepo:

```bash
make setup
```

Para subir todo o ambiente:

```bash
make up
```

Para subir somente a infraestrutura e executar as aplicações pela IDE ou Maven:

```bash
make infra
```

O `auth-service` utiliza a porta `8082` e o PostgreSQL utiliza a porta `5434`.

## Usuários padrão de desenvolvimento

Os usuários abaixo são inseridos pela migration `V4__seed_users.sql`:

| Role | E-mail | Senha |
|---|---|---|
| `ADMIN` | `admin@hospital.com` | `Admin@123` |
| `DOCTOR` | `joao.silva@hospital.com` | `Doutor@123` |
| `NURSE` | `maria.santos@hospital.com` | `Enfermeira@123` |
| `PATIENT` | `lucas.oliveira@hospital.com` | `Paciente@123` |

Essas credenciais são apenas para desenvolvimento e devem ser alteradas ou removidas antes de qualquer implantação real.

## Autenticação

O login utiliza HTTP Basic com o e-mail como usuário e a senha como password. Não envie as credenciais em JSON.

```bash
curl -i -X POST http://localhost:8082/auth/login \
  -u admin@hospital.com:Admin@123
```

Resposta esperada:

```json
{
  "access_token": "eyJ..."
}
```

Use o valor de `access_token` nas chamadas aos serviços protegidos:

```bash
curl -i http://localhost:8080/appointments \
  -H "Authorization: Bearer <access_token>"
```

O token tem validade de 15 minutos e contém, entre outros, os seguintes claims:

| Claim | Descrição |
|---|---|
| `sub` | E-mail do usuário autenticado |
| `user_id` | ID interno do usuário |
| `scope` | Role no formato `ROLE_ADMIN`, `ROLE_DOCTOR`, `ROLE_NURSE` ou `ROLE_PATIENT` |
| `iss` | `auth-service` |
| `exp` | Data de expiração |

## Endpoints

| Método | Endpoint | Permissão | Descrição |
|---|---|---|---|
| `POST` | `/auth/login` | Basic Auth | Gera um JWT |
| `GET` | `/users` | `ADMIN` | Lista usuários |
| `GET` | `/users/{id}` | `ADMIN` | Busca usuário por ID |
| `POST` | `/users` | `ADMIN` | Cria usuário |
| `PUT` | `/users/{id}` | `ADMIN` | Atualiza usuário |
| `DELETE` | `/users/{id}` | `ADMIN` | Remove usuário |

Exemplo de criação de usuário:

```bash
curl -i -X POST http://localhost:8082/users \
  -H "Authorization: Bearer <token-admin>" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Novo Paciente",
    "email": "novo.paciente@hospital.com",
    "password": "Paciente@123",
    "role": "PATIENT"
  }'
```

O e-mail deve ser válido e único. Se já existir, a API responde com `409 CONFLICT`.

## Roles e autorização

- `ADMIN`: gerencia usuários no `auth-service`.
- `DOCTOR`: consulta e edita consultas e acessa o histórico.
- `NURSE`: registra, consulta e edita consultas e acessa o histórico.
- `PATIENT`: visualiza somente suas próprias consultas e seu próprio histórico.

O `auth-service` emite o token, mas a autorização dos endpoints de agendamento e histórico é aplicada localmente por cada serviço consumidor.

## Parar o serviço

Para parar o PostgreSQL preservando os dados:

```bash
docker compose down
```

Para remover também o volume do banco:

```bash
docker compose down -v
```

O segundo comando apaga os dados locais do `auth-service` e deve ser usado somente quando isso for intencional.

## Solução de problemas

| Problema | Solução |
|---|---|
| `Connection refused` na porta `5434` | Execute `docker compose up -d` e aguarde o PostgreSQL ficar saudável |
| Porta `8082` ocupada | Altere `SERVER_PORT` no `.env` e use a nova porta |
| Porta `5434` ocupada | Altere `DB_PORT` no `.env` e mantenha a configuração consistente |
| Erro de `JAVA_HOME` | Instale o JDK 25 e configure `JAVA_HOME` para o diretório correto |
| Token rejeitado nos outros serviços | Confirme a mesma chave pública, o emissor `auth-service` e o header `Authorization: Bearer ...` |

## Segurança

- Não commite `app.key`.
- Não reutilize as credenciais padrão em produção.
- Não registre tokens JWT nos logs.
- Use HTTPS quando o serviço estiver fora de uma rede local confiável.
- Faça rotação do par de chaves quando houver suspeita de exposição da chave privada.
