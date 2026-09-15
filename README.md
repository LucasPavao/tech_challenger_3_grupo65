# tech_challenger_3_grupo65

Backend do Tech Challenge FIAP — Fase 3 — Grupo 65: agendamento de consultas com autenticação JWT,
histórico em GraphQL e notificações, integrados por RabbitMQ.

| Serviço | Papel | App | Postgres |
|---|---|---|---|
| auth-service | login e emissão do JWT, cadastro de usuários | 8083 | 5435 |
| appointment-service | agendamentos (serviço principal), publica os eventos | 8080 | 5433 |
| history-service | histórico das consultas, consulta via GraphQL | 8081 | 5432 |
| notification-service | notificações ao paciente | 8082 | 5434 |
| RabbitMQ | broker compartilhado | 5672 | Management em 15672 |

## Pré-requisitos

| Ferramenta | Para quê |
|---|---|
| Docker Desktop (Windows/macOS) ou Docker Engine (Linux), com Compose **2.20 ou superior** | subir o projeto. Confira com `docker compose version` — o antigo `docker-compose`, com hífen, não funciona |
| `make` (opcional) | atalhos no Linux, macOS e WSL2 |
| JDK 21 (opcional) | só para rodar testes ou uma aplicação fora do Docker |
| Node.js (opcional) | só para rodar a collection pela linha de comando com `npx newman` |

As portas da tabela acima precisam estar livres. Um PostgreSQL instalado localmente costuma ocupar a 5432.

## Início rápido

### Linux, macOS ou WSL2

```bash
git clone https://github.com/LucasPavao/tech_challenger_3_grupo65
cd tech_challenger_3_grupo65
make setup   # cria os .env que faltam a partir dos .env.example
make build   # constrói as imagens e sobe tudo
make ps      # espere os 9 containers ficarem healthy
make smoke   # teste ponta a ponta com login
```

### Windows (PowerShell + Docker Desktop)

```powershell
git clone https://github.com/LucasPavao/tech_challenger_3_grupo65
Set-Location tech_challenger_3_grupo65

# cria os .env que faltam, sem sobrescrever os existentes
Get-ChildItem -Path . -Filter .env.example -Recurse -Depth 1 -Force | ForEach-Object {
  $destino = $_.FullName -replace '\.example$', ''
  if (-not (Test-Path $destino)) { Copy-Item $_.FullName $destino; "criado $destino" }
}

docker compose up -d --build
docker compose ps   # espere os 9 containers ficarem healthy
```

No Windows, o teste ponta a ponta é a collection do Postman (veja [Fluxo de teste](#fluxo-de-teste)).

A **primeira** subida demora alguns minutos: baixa as imagens base e as dependências Maven dos
quatro serviços. As seguintes reaproveitam o cache.

Na primeira subida, o serviço `jwt-keys` gera o par de chaves do JWT em `.jwt-keys/` e termina
(aparece como `exited (0)` — é o esperado). As subidas seguintes reaproveitam o mesmo par.

Sobre os `.env`:

- **Não são versionados.** Sem eles, qualquer `docker compose` falha com `stat .../.env: no such file or directory`.
- **Nunca são sobrescritos** pelo `make setup` nem pelo bloco do PowerShell. Se um `.env.example`
  mudar, apague o `.env` correspondente e rode o setup de novo.

## Usuários de exemplo

Criados automaticamente no ambiente Docker (profile `dev` do auth-service):

| Role | E-mail | Senha | `user_id` | O que pode fazer |
|---|---|---|---|---|
| ADMIN | `admin@hospital.com` | `Admin@123` | 1 | gerenciar usuários (`/users`) |
| DOCTOR | `joao.silva@hospital.com` | `Doutor@123` | 2 | consultar e editar agendamentos, ver histórico e notificações de qualquer paciente |
| NURSE | `maria.santos@hospital.com` | `Enfermeira@123` | 3 | criar, consultar e editar agendamentos, ver histórico e notificações de qualquer paciente |
| PATIENT | `lucas.oliveira@hospital.com` | `Paciente@123` | 4 | ver só os próprios agendamentos, histórico e notificações (o `patientId` precisa ser o seu `user_id`) |

O token dura 15 minutos. Sem token os serviços respondem `401`; com uma role sem permissão, `403`.
Só `/actuator/health` é público.

## Fluxo de teste

A collection [`docs/postman/tech-challenge-grupo65.postman_collection.json`](docs/postman/tech-challenge-grupo65.postman_collection.json)
segue o roteiro abaixo, em quatro pastas. Importe no Postman e rode as pastas na ordem, ou a
collection inteira no **Runner** com *Delay* de 500 ms. Pela linha de comando, em qualquer sistema:

```bash
npx --yes newman@6 run docs/postman/tech-challenge-grupo65.postman_collection.json --delay-request 500
```

Os mesmos passos com `curl`, para Linux, macOS e WSL2. No PowerShell, o login e uma consulta ficam assim:

```powershell
$basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('maria.santos@hospital.com:Enfermeira@123'))
$token = (Invoke-RestMethod -Method Post -Uri http://localhost:8083/auth/login -Headers @{ Authorization = "Basic $basic" }).access_token
Invoke-RestMethod -Uri http://localhost:8082/notifications/patient/4 -Headers @{ Authorization = "Bearer $token" }
```

Os passos abaixo usam `curl`:

### 1. Autenticar e guardar o token

```bash
TOKEN=$(curl -s -X POST http://localhost:8083/auth/login -u maria.santos@hospital.com:Enfermeira@123 \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
```

O login é HTTP Basic (e-mail e senha), e a resposta é `{"access_token":"eyJ..."}`.

### 2. Criar e evoluir um agendamento

```bash
curl -s -X POST http://localhost:8080/appointments \
  -H 'content-type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d '{"patientId":4,"doctorId":2,"appointmentDate":"2030-12-01T09:00:00","description":"Consulta de rotina"}'
```

Responde `201` com o `id` gerado — use-o no lugar de `1` abaixo. A data precisa estar no futuro.

```bash
curl -s -X PATCH http://localhost:8080/appointments/1/status \
  -H 'content-type: application/json' -H "Authorization: Bearer $TOKEN" -d '{"status":"COMPLETED"}'
```

Status aceitos: `SCHEDULED`, `COMPLETED`, `CANCELLED`; `COMPLETED` e `CANCELLED` são finais (`422` ao alterar depois).

### 3. Consultar o histórico via GraphQL

```bash
curl -s -X POST http://localhost:8081/graphql \
  -H 'content-type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"{ appointmentTimeline(appointmentId: \"1\") { eventStatus occurredAt appointmentDate } }"}'
```

`appointmentTimeline` devolve a trilha completa (o `SCHEDULED` da criação e o `COMPLETED`);
`patientHistory(patientId: "4")` devolve só o estado atual de cada consulta.
O GraphiQL (`/graphiql`) também exige o token, então o jeito mais simples de explorar é a pasta 3
da collection.

### 4. Listar as notificações

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8082/notifications/patient/4
```

Uma notificação por evento do agendamento, com `status` `SENT`.

### Atalho: `make smoke`

No Linux, macOS e WSL2, `make smoke` faz login, cria um agendamento e espera o evento chegar ao
histórico e virar notificação, falhando com diagnóstico em cada etapa.

## Arquitetura Docker

Cada serviço é autocontido: tem seu próprio `docker-compose.yml`, banco e `.env`. O
`docker-compose.yml` da raiz agrega os serviços via `include`; `infra/docker-compose.yml`
define uma vez o RabbitMQ e o `jwt-keys`.

| Peça | Como funciona |
|---|---|
| `jwt-keys` | container Alpine que gera `.jwt-keys/app.key` e `app.sub` se não existirem e termina. Os apps esperam ele concluir |
| auth-service | lê a chave privada e a pública de `/keys` e assina o token |
| appointment, history, notification | montam `/keys` só leitura e validam o token com a chave pública e o emissor `auth-service` |
| RabbitMQ | exchange única `appointment.exchange`; cada consumidor declara a própria fila e DLQ |

| Comando | O que faz |
|---|---|
| `make setup` | cria os `.env` que faltam |
| `make up` / `make build` | sobe tudo (`build` reconstrói as imagens) |
| `make infra` | sobe só bancos, RabbitMQ e `jwt-keys`, para rodar as apps pela IDE |
| `make smoke` | teste ponta a ponta com login |
| `make logs` / `make ps` | logs e estado |
| `make down` / `make clean` | derruba tudo (`clean` também apaga os volumes dos bancos) |

Sem `make`, o equivalente é `docker compose up -d --build`, `COMPOSE_PROFILES= docker compose up -d`
(só infra), `docker compose logs -f`, `docker compose ps` e `docker compose down`.

**Cuidado:** todos os serviços formam um único projeto Compose (`name: grupo65`): `docker compose down`
de dentro da pasta de um serviço derruba o projeto inteiro. Para parar um só, use
`docker compose stop <serviço>-app <serviço>-postgres`.

**Cuidado:** espere os containers ficarem `healthy` antes de testar. Um evento publicado antes de
history e notification declararem suas filas é descartado em silêncio pelo RabbitMQ.

## Rodando uma aplicação pela IDE

1. Suba a infra pela raiz: `make infra` (ou `COMPOSE_PROFILES= docker compose up -d`). Isso também gera `.jwt-keys/`.
2. Rode a aplicação a partir da pasta do serviço (`./mvnw spring-boot:run`, ou `.\mvnw.cmd spring-boot:run` no Windows).
   O padrão das chaves é `file:../.jwt-keys/app.sub` (e `app.key` no auth), relativo a essa pasta.
   Se a IDE usar outro diretório de trabalho, defina `JWT_PUBLIC_KEY=file:/caminho/absoluto/.jwt-keys/app.sub`
   (e `JWT_PRIVATE_KEY` no auth).
3. Para ter os usuários de exemplo rodando o auth pela IDE, ative o profile `dev` (`SPRING_PROFILES_ACTIVE=dev`).

Os testes (`./mvnw test`) não precisam de `.jwt-keys/`: cada serviço usa um par só de teste em
`src/test/resources/jwt-test/`. Os testes de integração usam Testcontainers, então o Docker precisa estar rodando.

Para regenerar o par de chaves: `docker run --rm -v "$(pwd)/.jwt-keys:/keys" alpine:3.22 rm -f /keys/app.key /keys/app.sub`
(no PowerShell, `${PWD}` no lugar de `$(pwd)`) e recrie os containers com `docker compose up -d --force-recreate` — só `up -d` roda o `jwt-keys` de novo, mas as aplicações continuariam com o par antigo. Tokens emitidos antes deixam de valer.

## Problemas comuns

| Sintoma | Causa | Solução |
|---|---|---|
| `stat .../.env: no such file or directory` | os `.env` não foram criados | `make setup` ou o bloco PowerShell do [Início rápido](#início-rápido) |
| `401` nas rotas | sem header `Authorization: Bearer`, ou token expirado (15 min) | fazer login de novo |
| `403` nas rotas | a role não tem permissão, ou PATIENT consultando outro `patientId` | usar a enfermeira, ou o `patientId` igual ao `user_id` do paciente |
| login devolve `401` com a senha certa | auth-service sem o profile `dev`, sem usuários | subir pelo compose da raiz, que já ativa o profile |
| app não sobe com `FileNotFoundException ... .jwt-keys/app.sub` | rodando pela IDE sem ter gerado as chaves, ou em outro diretório de trabalho | `make infra` antes, ou definir `JWT_PUBLIC_KEY`/`JWT_PRIVATE_KEY` |
| `sh: ./mvnw: not found` no build | checkout antigo com fim de linha CRLF | clonar de novo (o `.gitattributes` força LF) |
| `failed to bind host port ... address already in use` | outra aplicação na porta | parar o processo, ou trocar `DB_PORT`/`SERVER_PORT` no `.env` do serviço |
| `make: command not found` | Windows fora do WSL2 | usar os comandos `docker compose` do [Início rápido](#início-rápido) |
| erro sobre `include` | Compose anterior à 2.20, ou `docker-compose` v1 | atualizar o Docker |
| alterações de código não aparecem | `make up` reaproveita as imagens | `make build` |
| agendamento criado, nada no histórico nem nas notificações | `.env` antigo com outra exchange, ou evento publicado antes de os consumidores subirem | [Atualizando de uma versão anterior](#atualizando-de-uma-versão-anterior) |
| `PRECONDITION_FAILED - inequivalent arg 'x-dead-letter-exchange'` | fila criada por uma versão anterior | [Atualizando de uma versão anterior](#atualizando-de-uma-versão-anterior) |
| notification-app não sobe com `relation "notifications" already exists` | tabela criada pelo Hibernate numa versão anterior | [Atualizando de uma versão anterior](#atualizando-de-uma-versão-anterior) |

## Atualizando de uma versão anterior

Quem já rodou o projeto antes pode ter restos que impedem a subida ou a comunicação:

1. **`.env` antigos.** O setup não sobrescreve arquivos existentes; um `.env` antigo do auth, por
   exemplo, não existia ou usava outra porta.
2. **Imagens antigas.** `make up` não recompila.
3. **Chaves antigas em `src/main/resources`.** `app.key` e `app.sub` gerados pelos scripts antigos
   não são mais lidos e podem ser apagados.
4. **Filas antigas no RabbitMQ** e **tabela antiga do notification**, de versões anteriores à exchange única e à migration Flyway.
5. **Pasta antiga `notificationservice`**, que sobra só com arquivos ignorados depois do `git pull`.

Na raiz, nesta ordem (Linux, macOS ou WSL2):

```bash
rm -f appointment-service/.env history-service/.env notification-service/.env auth-service/.env
rm -f */src/main/resources/app.key */src/main/resources/app.sub
rm -rf notificationservice
make setup
docker compose rm -sf notification-app notification-postgres
docker volume rm grupo65_notification-postgres-data
make build
docker compose exec rabbitmq rabbitmqctl delete_queue history.queue
docker compose exec rabbitmq rabbitmqctl delete_queue history.queue.dlq
docker compose restart history-app
make smoke
```

A ordem importa: se as filas forem apagadas antes de as aplicações serem recriadas, a versão
antiga, ainda rodando, as recria com a configuração velha. Para começar do zero, perdendo os dados
dos bancos, `make clean && make build` substitui os passos de `docker compose rm`, `docker volume rm`
e `delete_queue`; apagar os `.env`, as chaves antigas e a pasta `notificationservice` continua necessário.

## Adicionando um novo serviço

Exemplo com um `billing-service`. São quatro arquivos novos e **uma** edição fora
da pasta do serviço.

### 1. `billing-service/docker-compose.yml`

```yaml
name: grupo65                       # convenção: mesmo projeto para todos

include:
  - path: ../infra/docker-compose.yml
    env_file: ../infra/.env

services:
  billing-postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: ${POSTGRES_DB}
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    ports: ["${DB_PORT}:5432"]
    volumes: [billing-postgres-data:/var/lib/postgresql/data]
    networks: [billing-net]         # só a rede privada do serviço
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}"]
      interval: 5s
      retries: 10

  billing-app:
    build: .
    profiles: [apps]
    env_file:
      - .env
      - ../infra/.env
    environment:
      DB_HOST: billing-postgres     # sobrescreve o localhost do .env
      DB_PORT: 5432                      # porta interna, não a publicada
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_PORT: 5672
      JWT_PUBLIC_KEY: file:/keys/app.sub   # chave publica do JWT
    volumes: ["../.jwt-keys:/keys:ro"]
    ports: ["${SERVER_PORT}:${SERVER_PORT}"]
    networks: [billing-net, shared] # rede privada + `shared` para o broker
    restart: on-failure
    depends_on:
      billing-postgres: { condition: service_healthy }
      rabbitmq:              { condition: service_healthy }
      jwt-keys:              { condition: service_completed_successfully }
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:${SERVER_PORT}/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 40s

networks:
  billing-net:
    driver: bridge

volumes:
  billing-postgres-data:
```

### 2. `billing-service/.env.example`

Usa a próxima faixa de portas livre (8080 a 8083 e 5432 a 5435 já estão tomadas):

```
COMPOSE_PROFILES=apps
POSTGRES_DB=billing_db
POSTGRES_USER=postgres
POSTGRES_PASSWORD=postgres
DB_HOST=localhost
DB_PORT=5436
SERVER_PORT=8084
```

### 3. `Dockerfile` e `.dockerignore`

Copiados de qualquer serviço existente, sem alteração — o `mvnw` e o `pom.xml` vêm do
contexto de build.

### 4. Uma entrada no `docker-compose.yml` da raiz

```yaml
  - path: ./billing-service/docker-compose.yml
    env_file: ./billing-service/.env
```

Depois, `make setup && make up`.

Não esqueça de atualizar a tabela de serviços do início deste README com a faixa usada pelo
novo serviço — é a outra edição central que esta receita não cobre sozinha.

### O que não se toca

`infra/`, o compose dos outros serviços, o `Makefile` (o `ENVS` é derivado dos
`.env.example` existentes, então não precisa de edição), nenhum `.env` alheio. O único
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
