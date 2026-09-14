# auth-service na orquestração, JWT em todos os serviços e roteiro de teste — design

**Data:** 2026-09-14
**Branch:** `feat/auth-orquestracao` (a partir da `main` em `11a89b7`)
**Status:** aprovado em conversa, seção por seção

## 1. Objetivo

Subir o auth-service junto com os demais serviços pelo Docker Compose, com todos os serviços
validando o JWT emitido por ele, funcionando igual no Linux, no WSL2 e no Windows nativo
(PowerShell + Docker Desktop). Entregar um README claro e uma collection do Postman que siga
um único roteiro: autenticar e pegar o token → interagir com o appointment-service → listar o
histórico via GraphQL → listar as notificações via API.

## 2. Estado atual na `main` (levantado em 2026-09-14)

| Ponto | Situação |
|---|---|
| `docker-compose.yml` raiz | já inclui `auth-service/docker-compose.yml`, mas não existe `auth-service/.env` até rodar `make setup` |
| aplicação do auth | o README manda rodar pelo Maven; no compose, o `auth-app` não tem actuator (o healthcheck aponta para `/actuator/health`, inexistente e bloqueado pelo `denyAll`) |
| Java do auth | `java.version=25`, mas o Dockerfile usa `eclipse-temurin:21` → o build Docker falha. Só duas linhas usam recurso do Java 22+ (`_` em lambdas: `SecurityConfig.java:45`, `UserService.java:79`) |
| chaves JWT | `scripts/generate-jwt-keys.sh`/`.ps1` exigem `openssl` no host e copiam `app.key`/`app.sub` para `src/main/resources` (ignorados pelo git). As chaves ficam embutidas nas imagens; o notification não recebe a pública |
| leitura das chaves | `@Value("classpath:app.sub")` em auth, appointment e history; `classpath:app.key` no auth. Sem o script, appointment e history não sobem |
| usuários de exemplo | `V4__seed_users.sql` idêntico em `db/migration` **e** em `db/dev-seed`: os usuários são inseridos sempre e o profile `dev` teria duas migrations `V4` |
| notification-service | sem segurança: `GET /notifications/patient/{patientId}` aberto |
| testes (clone sem chaves, JDK 21) | appointment 13 ✅; history não compila (3 arquivos de teste com a assinatura antiga de `patientHistory`); auth não compila no JDK 21 e, no JDK 25, 5 de 24 falham por falta de `app.sub` |
| Windows | README exige WSL2; `auth-service/mvnw` está `100644`; `.env.example` pode virar CRLF num checkout Windows |
| README do auth | porta do banco documentada como 5434 (a do notification); o `.env.example` usa 5435 |
| smoke e collection | não enviam token |

## 3. Decisões

| # | Decisão | Alternativa descartada |
|---|---|---|
| D1 | Chaves geradas dentro do Docker por um serviço `jwt-keys` | manter scripts no host |
| D2 | notification-service protegido por JWT com a mesma regra por role de appointment e history | só exigir token; manter aberto |
| D3 | Windows nativo (PowerShell + Docker Desktop) e WSL2; `make` opcional | só WSL2; paridade total com scripts `.ps1` |
| D4 | Todos os serviços em Java 21 | manter o auth em Java 25 |
| D5 | Collection com um único login, da enfermeira | enfermeira + paciente |
| D6 | Pasta do host `./.jwt-keys/` em vez de volume nomeado | volume nomeado (inacessível para quem roda pela IDE) |
| D7 | appointment, history e notification não dependem do `auth-app` estar no ar, só do `jwt-keys` ter terminado | `depends_on: auth-app` |

## 4. Design

### 4.1 Chaves JWT

Serviço novo em `infra/docker-compose.yml` (arquivo que todo serviço já inclui):

```yaml
  jwt-keys:
    image: alpine:3.22
    volumes:
      - ../.jwt-keys:/keys
    command:
      - sh
      - -c
      - |
        set -e
        if [ -f /keys/app.key ] && [ -f /keys/app.sub ]; then echo "chaves JWT ja existem"; exit 0; fi
        apk add --no-cache openssl >/dev/null
        rm -f /keys/app.key /keys/app.sub
        openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out /keys/app.key
        openssl pkey -pubout -in /keys/app.key -out /keys/app.sub
        chmod 444 /keys/app.key /keys/app.sub
        echo "chaves JWT geradas"
```

- O caminho `../.jwt-keys` é relativo a `infra/`, ou seja, `.jwt-keys/` na raiz do monorepo, em
  qualquer ponto de entrada (raiz ou pasta de um serviço).
- Se só metade do par existir, o par é regerado (as chaves são de desenvolvimento).
- O comando fica no YAML, sem arquivo de script, para não sofrer conversão CRLF no Windows.
- `.jwt-keys/` entra no `.gitignore` da raiz.
- Validado em 2026-09-14: arquivos gerados por root no container com modo 444 são lidos pelo
  usuário `app` (uid 100) da imagem `eclipse-temurin:21-jre-alpine` e pelo usuário do host.

Cada `*-app` monta a pasta só leitura e espera o gerador:

```yaml
    volumes:
      - ../.jwt-keys:/keys:ro
    depends_on:
      jwt-keys:
        condition: service_completed_successfully
```

Variáveis de ambiente no compose de cada app:

| Serviço | Variável | Valor no Docker |
|---|---|---|
| auth, appointment, history, notification | `JWT_PUBLIC_KEY` | `file:/keys/app.sub` |
| auth | `JWT_PRIVATE_KEY` | `file:/keys/app.key` |

Propriedades nas aplicações, com padrão para quem roda pela IDE a partir da pasta do serviço:

```
security.jwt.public-key=${JWT_PUBLIC_KEY:file:../.jwt-keys/app.sub}
security.jwt.private-key=${JWT_PRIVATE_KEY:file:../.jwt-keys/app.key}   # só no auth
security.jwt.issuer=${JWT_ISSUER:auth-service}                          # já existe em appointment e history
```

Nas `SecurityConfig`: `@Value("classpath:app.sub")` → `@Value("${security.jwt.public-key}")` e,
no auth, `@Value("classpath:app.key")` → `@Value("${security.jwt.private-key}")`. O conversor
RSA do Spring Security carrega por `ResourceLoader`, então aceita `file:` e `classpath:`.

Saem: `scripts/generate-jwt-keys.sh`, `scripts/generate-jwt-keys.ps1` e as linhas de
`app.key`/`app.sub` dos `.gitignore` dos serviços (substituídas por `.jwt-keys/` na raiz).

### 4.2 auth-service no compose

- `auth-app` sobe com os demais pelo profile `apps`: porta 8083, banco `auth-postgres` na 5435.
- `pom.xml`: `spring-boot-starter-actuator`; `java.version` 21.
- `SecurityConfig`: `/actuator/health` e `/actuator/health/**` com `permitAll`, antes do
  `denyAll`.
- Os dois `_` em lambdas viram nomes de parâmetro.
- Compose define `SPRING_PROFILES_ACTIVE=dev`, para os usuários de exemplo existirem.
- Remove `db/migration/V4__seed_users.sql`; o seed fica só em `db/dev-seed/V4__seed_users.sql`.
  Bancos que já aplicaram o `V4` antigo continuam válidos: mesma versão e mesmo conteúdo.
- `.dockerignore` igual ao dos outros serviços.
- `auth-app` também entra na rede `shared` (já está) e não depende do RabbitMQ.

Subida esperada: 9 containers healthy (4 apps, 4 Postgres, RabbitMQ) e `jwt-keys` concluído
com código 0.

### 4.3 Segurança no notification-service

- Dependências `spring-boot-starter-security` e `spring-boot-starter-security-oauth2-resource-server`.
- `config/SecurityConfig.java` igual à de appointment e history: CSRF desligado,
  `/actuator/health` e `/actuator/health/**` públicos, demais rotas autenticadas, validador com
  emissor `security.jwt.issuer`, authorities do claim `scope` sem prefixo, `@EnableMethodSecurity`.
- `GET /notifications/patient/{patientId}`: `@PreAuthorize("hasAnyRole('DOCTOR','NURSE','PATIENT')")`.
  Se o token tiver `ROLE_PATIENT`, o `patientId` do caminho precisa ser igual ao claim `user_id`;
  senão `AccessDeniedException` → `403`. Mesma regra de `AppointmentAuthorization` e de
  `MedicalHistoryQueryService.checkPatientAccess`.
- O consumo do RabbitMQ não muda.

### 4.4 Testes

**Par de chaves só de teste**, em `src/test/resources/jwt-test/app.key` e `app.sub` de cada
serviço que precisa, versionado, com um `README` ao lado dizendo que é exclusivo de testes. O
`application` de teste de cada serviço aponta `security.jwt.public-key` (e, no auth,
`security.jwt.private-key`) para `classpath:jwt-test/...`. Assim `./mvnw test` passa num clone
limpo, sem `.jwt-keys/`.

| Serviço | O que muda |
|---|---|
| auth | os 5 testes do `SecurityConfigTest` voltam a carregar o contexto; teste novo: `/actuator/health` sem credencial não devolve 401/403 |
| appointment | `HistoryExchangeInteropIT` carrega com a chave de teste; os 13 continuam verdes |
| history | `GraphQlExceptionResolverTest`, `HistoryQueryControllerTest` e `MedicalHistoryQueryServiceTest` passam a usar `patientHistory(patientId, authentication)`; casos novos: PATIENT vê o próprio histórico e recebe 403 para outro paciente; `HistoryGraphQlIT` envia token assinado com a chave de teste |
| notification | `NotificationControllerTest` (fatia com `@MockitoBean JwtDecoder`): 401 sem token, 200 para NURSE, 200 para PATIENT dono, 403 para PATIENT de outro paciente; testes de contexto completo carregam com a chave de teste; `NotificationMessageListenerIT` segue sem token |

Os testes de fatia seguem o padrão do `SecurityConfigTest` do auth (`@MockitoBean JwtDecoder`).
As contagens finais de cada suíte entram no plano depois de medidas.

### 4.5 Smoke test

`scripts/smoke-test.sh` ganha a etapa de login antes das atuais: `POST /auth/login` com Basic
Auth da enfermeira, extrai `access_token` e envia `Authorization: Bearer` no appointment, no
GraphQL e nas notificações. Health checks passam a incluir 8083. O agendamento é criado para o
paciente 4 (o `user_id` do paciente de exemplo), mantendo a mensagem de sucesso atual.

### 4.6 Windows e Linux

- Caminho universal: `docker compose up -d --build` na raiz, depois de criar os `.env`.
- `.gitattributes` da raiz: `.env.example text eol=lf` (sem barra, o padrão vale em qualquer pasta).
- `auth-service/mvnw` passa para `100755` no índice do git.
- README com o equivalente PowerShell do `make setup` (cria os `.env` que faltam, sem sobrescrever):

```powershell
Get-ChildItem -Path . -Filter .env.example -Recurse -Depth 1 | ForEach-Object {
  $destino = $_.FullName -replace '\.example$', ''
  if (-not (Test-Path $destino)) { Copy-Item $_.FullName $destino; "criado $destino" }
}
```

- `make` continua disponível no Linux, macOS e WSL2; `make smoke` é bash.
- Teste ponta a ponta no Windows: collection no Postman ou `npx --yes newman@6 run ...`.

### 4.7 Collection do Postman

Arquivo `docs/postman/tech-challenge-grupo65.postman_collection.json`, substituindo o atual.

- Autenticação da collection: `Bearer {{token}}`. O login sobrescreve com Basic Auth.
- Variáveis: `authUrl=http://localhost:8083`, `appointmentUrl=http://localhost:8080`,
  `historyUrl=http://localhost:8081`, `notificationUrl=http://localhost:8082`, `patientId=4`,
  `doctorId=2`, `token`, `appointmentId`, `appointmentDate`.

| Pasta | Requisição | Teste |
|---|---|---|
| 1. Autenticação | `POST {{authUrl}}/auth/login` (Basic `maria.santos@hospital.com` / `Enfermeira@123`) | 200; salva `access_token` em `token` |
| 2. Appointment | `POST {{appointmentUrl}}/appointments` para o paciente `{{patientId}}` com data futura gerada no pre-request | 201; salva `appointmentId` |
| | `GET {{appointmentUrl}}/appointments/{{appointmentId}}` | 200; mesmo id |
| | `PUT {{appointmentUrl}}/appointments/{{appointmentId}}` (remarcar) | 200 |
| | `PATCH {{appointmentUrl}}/appointments/{{appointmentId}}/status` → `COMPLETED` | 200; status `COMPLETED` |
| 3. Histórico (GraphQL) | `patientHistory(patientId: {{patientId}})` | 200; sem `errors`; contém `{{appointmentId}}` |
| | `appointmentTimeline(appointmentId: {{appointmentId}})` | 200; sem `errors`; trilha com os eventos |
| 4. Notificações | `GET {{notificationUrl}}/notifications/patient/{{patientId}}` | 200; ao menos uma notificação `SENT` do `{{appointmentId}}` |

Os corpos e campos exatos seguem os DTOs e o schema GraphQL atuais; o plano confere cada um.
Casos de erro e health checks saem da collection e ficam no README. Execução sugerida no
newman com `--delay-request 500`, porque history e notification recebem os eventos de forma
assíncrona.

### 4.8 README

**Raiz**, nesta ordem: descrição; pré-requisitos (Docker com Compose ≥ 2.20; JDK 21 só para
testes ou IDE; `make` opcional); início rápido em dois blocos (Linux/macOS/WSL2 com `make`,
Windows PowerShell com `docker compose`); serviços e portas (8080–8083, 5432–5435, 5672,
15672); usuários de exemplo com roles e o que cada role pode fazer; fluxo de teste nos quatro
passos da collection, com `curl`; arquitetura (compose por serviço, RabbitMQ compartilhado,
`jwt-keys`); rodando pela IDE (chaves em `.jwt-keys/`, `make infra`); problemas comuns
(novas linhas: 401 sem token ou token expirado em 15 min, 403 por role, `chave ausente` ao
rodar pela IDE sem subir o `jwt-keys`); atualizando de uma versão anterior (apagar `app.key`
e `app.sub` antigos de `src/main/resources`, recriar `.env` do auth, `docker compose up -d
--build`); adicionando um novo serviço (mantido, com a montagem da chave pública).

Sai: rodar o auth pelo Maven como passo obrigatório, os scripts de chaves e a nota "ainda não
exigem autenticação".

**Serviços:** auth-service corrige a porta do banco para 5435, remove os scripts e aponta para a
raiz; appointment, history e notification ganham a nota de onde vem a chave pública ao rodar
pela IDE; notification troca a seção "Segurança" pela regra por role.

## 5. Fora de escopo

- Refresh token, troca de chaves em produção, secrets manager.
- Regras de autorização novas em appointment e history além das existentes.
- Paridade de scripts PowerShell para `make` e `make smoke`.
- Validação num Windows real.

## 6. Critérios de aceite

1. `./mvnw test` passa em auth, appointment, history e notification num clone sem `.jwt-keys/`, com JDK 21.
2. Num clone limpo com projeto Compose isolado: `make setup` + `docker compose up -d --build` deixam 9 containers healthy e `jwt-keys` concluído com código 0.
3. Uma segunda subida reaproveita o mesmo par de chaves (conteúdo de `app.sub` igual).
4. `make smoke` termina com a mensagem de sucesso, usando token.
5. `npx --yes newman@6 run docs/postman/tech-challenge-grupo65.postman_collection.json --delay-request 500` termina com 0 falhas.
6. Sem token, appointment, history e notification respondem 401; PATIENT recebe 403 em notificações de outro paciente.
7. Um clone com `core.autocrlf=true` constrói e sobe sem erro.
8. Se `pwsh` estiver disponível, o bloco PowerShell do README cria os `.env` que faltam sem sobrescrever os existentes.
9. Nenhuma chave real, `.env` ou `.jwt-keys/` versionado; só os pares `jwt-test` de teste.
