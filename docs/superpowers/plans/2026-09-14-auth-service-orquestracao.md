# auth-service na orquestração — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Subir o auth-service junto com os demais pelo Docker Compose, com appointment, history e notification validando o JWT dele, igual no Linux, no WSL2 e no Windows nativo, com README e collection do Postman num único roteiro.

**Architecture:** Um serviço `jwt-keys` em `infra/docker-compose.yml` gera o par RSA em `./.jwt-keys/` na primeira subida; cada app monta a pasta só leitura e lê a chave por `security.jwt.public-key` (e `security.jwt.private-key` no auth). Os testes usam um par só de teste em `src/test/resources/jwt-test/`, apontado por `src/test/resources/config/application.properties`. O notification ganha a mesma segurança por role de appointment e history.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Security 7 (resource server JWT, Nimbus), Spring GraphQL, Flyway, PostgreSQL 16, RabbitMQ 4, Testcontainers, Docker Compose ≥ 2.20, Postman/newman 6.

**Spec:** `docs/superpowers/specs/2026-09-14-auth-service-orquestracao-design.md`

## Global Constraints

- Branch `feat/auth-orquestracao`; nunca commitar na `main`; push e PR só com confirmação explícita do mantenedor.
- Java 21 em todos os serviços (`<java.version>21</java.version>`); JDK do host: `/usr/lib/jvm/java-21-openjdk-amd64`.
- Propriedades: `security.jwt.public-key=${JWT_PUBLIC_KEY:file:../.jwt-keys/app.sub}`; no auth também `security.jwt.private-key=${JWT_PRIVATE_KEY:file:../.jwt-keys/app.key}`; `security.jwt.issuer=${JWT_ISSUER:auth-service}`.
- No Docker: `JWT_PUBLIC_KEY: file:/keys/app.sub`, `JWT_PRIVATE_KEY: file:/keys/app.key` (só auth), montagem `../.jwt-keys:/keys:ro`.
- Chave de teste: `src/test/resources/jwt-test/app.key` e `app.sub` + `src/test/resources/config/application.properties`. Nunca versionar `.jwt-keys/`, `.env` nem chave fora de `jwt-test/`.
- Usuários de exemplo (profile `dev`): `admin@hospital.com`/`Admin@123` (ADMIN, id 1), `joao.silva@hospital.com`/`Doutor@123` (DOCTOR, id 2), `maria.santos@hospital.com`/`Enfermeira@123` (NURSE, id 3), `lucas.oliveira@hospital.com`/`Paciente@123` (PATIENT, id 4).
- Portas: appointment 8080/5433, history 8081/5432, notification 8082/5434, auth 8083/5435, RabbitMQ 5672/15672.
- Mensagem de sucesso do smoke, exata: `SUCESSO: login -> appointment -> RabbitMQ -> history e notification funcionando.`
- Commits terminam com:
  ```
  Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_019ij1SRidK2Nd5YVMagZCXb
  ```
- Nunca rodar `make clean`, `docker compose down -v`, `docker volume rm/prune` ou `docker system prune` no projeto `grupo65`. Validações de stack usam `COMPOSE_PROJECT_NAME` isolado e `docker compose -p <projeto>` explícito em cada comando.
- Contagens de testes esperadas ao fim: appointment 13, history 60, notification 26, auth 25.

## Mapa de arquivos

| Task | Arquivos |
|---|---|
| 1 appointment | `appointment-service/src/main/java/.../config/SecurityConfig.java`, `src/main/resources/application.yml`, `.gitignore`, `README.md` |
| 2 history | `history-service/pom.xml`, `.../config/SecurityConfig.java`, `application.properties`, `.gitignore`, `README.md`, testes `GraphQlExceptionResolverTest`, `HistoryQueryControllerTest`, `MedicalHistoryQueryServiceTest`, `HistoryGraphQlIT`, novo `support/TestJwt.java`, `src/test/resources/jwt-test/*`, `src/test/resources/config/application.properties` |
| 3 notification | `notification-service/pom.xml`, novos `config/SecurityConfig.java` e `config/NotificationAuthorization.java`, `controller/NotificationController.java`, `application.properties`, `NotificationControllerTest`, `src/test/resources/jwt-test/*`, `config/application.properties`, `README.md` |
| 4 auth | `auth-service/pom.xml`, `config/SecurityConfig.java`, `service/UserService.java`, `application.yaml`, remove `db/migration/V4__seed_users.sql`, novo `.dockerignore`, `.gitignore`, `mvnw` (modo), `SecurityConfigTest`, `src/test/resources/jwt-test/*`, `config/application.properties` |
| 5 orquestração | `infra/docker-compose.yml`, `*/docker-compose.yml` (4), `.gitignore`, `.gitattributes`, remove `scripts/generate-jwt-keys.sh` e `.ps1` |
| 6 smoke | `scripts/smoke-test.sh`, `Makefile` |
| 7 collection | `docs/postman/tech-challenge-grupo65.postman_collection.json` |
| 8 READMEs | `README.md`, `auth-service/README.md` |
| 9 validação | nenhum arquivo |

---

### Task 1: appointment-service lê a chave pública por propriedade

**Files:**
- Modify: `appointment-service/src/main/java/br/com/tech/challenge/appointmentservice/config/SecurityConfig.java:22`
- Modify: `appointment-service/src/main/resources/application.yml:52-54`
- Modify: `appointment-service/.gitignore:8-9`
- Modify: `appointment-service/README.md` (seção "Integração com Security")

**Interfaces:**
- Produces: propriedade `security.jwt.public-key` lida pelo `SecurityConfig`; variável de ambiente `JWT_PUBLIC_KEY` (usada na Task 5).

O appointment não tem teste de contexto completo com segurança (o `HistoryExchangeInteropIT` carrega só as classes de mensageria), então não precisa de chave de teste.

- [ ] **Step 1: Trocar a origem da chave no `SecurityConfig`**

Em `SecurityConfig.java`, substituir:

```java
    @Value("classpath:app.sub")
```

por:

```java
    @Value("${security.jwt.public-key}")
```

- [ ] **Step 2: Declarar a propriedade no `application.yml`**

Substituir o bloco final:

```yaml
security:
  jwt:
    issuer: ${JWT_ISSUER:auth-service}
```

por:

```yaml
security:
  jwt:
    issuer: ${JWT_ISSUER:auth-service}
    # No Docker vem de JWT_PUBLIC_KEY=file:/keys/app.sub; pela IDE, da pasta .jwt-keys da raiz.
    public-key: ${JWT_PUBLIC_KEY:file:../.jwt-keys/app.sub}
```

- [ ] **Step 3: Remover as chaves antigas do `.gitignore`**

Em `appointment-service/.gitignore`, apagar as duas linhas:

```
src/main/resources/app.key
src/main/resources/app.sub
```

- [ ] **Step 4: Atualizar a seção de segurança do README**

Em `appointment-service/README.md`, substituir a linha:

```markdown
- O health check continua público.
```

por:

```markdown
- O health check continua público.

A chave pública que valida o token vem de `security.jwt.public-key`. No Docker ela é montada de
`.jwt-keys/app.sub` (gerada pelo serviço `jwt-keys` do compose). Pela IDE, rodando a partir desta
pasta, o padrão é `file:../.jwt-keys/app.sub`: suba o ambiente uma vez com `make infra` (ou
`docker compose up -d` na raiz) para a pasta existir, ou aponte `JWT_PUBLIC_KEY` para outro arquivo.
```

- [ ] **Step 5: Rodar os testes**

Run: `cd appointment-service && ./mvnw -B test 2>&1 | grep -E "Tests run:.*Skipped: [0-9]+$|BUILD"`
Expected: `Tests run: 13, Failures: 0, Errors: 0, Skipped: 0` e `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add appointment-service/src/main/java/br/com/tech/challenge/appointmentservice/config/SecurityConfig.java \
        appointment-service/src/main/resources/application.yml appointment-service/.gitignore appointment-service/README.md
git commit -m "feat(appointment): le a chave publica do JWT por propriedade" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019ij1SRidK2Nd5YVMagZCXb"
```

---
### Task 2: history-service — chave por propriedade, testes compilando e acesso do paciente

**Files:**
- Modify: `history-service/pom.xml` (dependência de teste)
- Modify: `history-service/src/main/java/br/com/tech/challenge/historyservice/config/SecurityConfig.java:21`
- Modify: `history-service/src/main/resources/application.properties:62`
- Modify: `history-service/.gitignore` (duas últimas linhas)
- Create: `history-service/src/test/resources/jwt-test/app.key`, `app.sub`, `README.md`
- Create: `history-service/src/test/resources/config/application.properties`
- Create: `history-service/src/test/java/br/com/tech/challenge/historyservice/support/TestJwt.java`
- Modify: `.../graphql/GraphQlExceptionResolverTest.java`, `.../graphql/HistoryQueryControllerTest.java`, `.../services/MedicalHistoryQueryServiceTest.java`, `.../graphql/HistoryGraphQlIT.java`
- Modify: `history-service/README.md`

**Interfaces:**
- Consumes: assinatura atual `MedicalHistoryQueryService.patientHistory(Long patientId, Authentication authentication)`.
- Produces: `TestJwt.token(String scope, long userId)` (só neste serviço); propriedade `security.jwt.public-key`.

Contexto: na `main`, três arquivos de teste ainda chamam `patientHistory(patientId)` com um argumento e não compilam. Nos testes `@GraphQlTest`, o resolver com parâmetro `Authentication` falha com `AuthenticationCredentialsNotFoundException: No Authentication` se não houver usuário no contexto — por isso o `@WithMockUser`. Tudo abaixo foi validado num spike em 2026-09-14 (60 testes verdes).

- [ ] **Step 1: Confirmar a falha atual**

Run: `cd history-service && ./mvnw -B test 2>&1 | grep -E "cannot be applied|BUILD" | head -3`
Expected: `method patientHistory ... cannot be applied to given types` e `BUILD FAILURE`.

- [ ] **Step 2: Gerar o par de chaves só de teste**

```bash
cd history-service
mkdir -p src/test/resources/jwt-test src/test/resources/config
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out src/test/resources/jwt-test/app.key
openssl pkey -pubout -in src/test/resources/jwt-test/app.key -out src/test/resources/jwt-test/app.sub
```

Criar `src/test/resources/jwt-test/README.md`:

```markdown
# Chaves só de teste

Par RSA usado apenas pelos testes automatizados deste serviço. Não vale em nenhum ambiente real:
o ambiente Docker gera o próprio par em `.jwt-keys/`, na raiz do monorepo.
```

Criar `src/test/resources/config/application.properties` (fica em `config/` para somar ao
`application.properties` principal em vez de escondê-lo):

```properties
security.jwt.public-key=classpath:jwt-test/app.sub
```

- [ ] **Step 3: Trocar a origem da chave no código e na configuração**

Em `SecurityConfig.java`, substituir `@Value("classpath:app.sub")` por `@Value("${security.jwt.public-key}")`.

Em `application.properties`, substituir a última linha:

```properties
security.jwt.issuer=${JWT_ISSUER:auth-service}
```

por:

```properties
# JWT emitido pelo auth-service. No Docker a chave vem de JWT_PUBLIC_KEY=file:/keys/app.sub.
security.jwt.issuer=${JWT_ISSUER:auth-service}
security.jwt.public-key=${JWT_PUBLIC_KEY:file:../.jwt-keys/app.sub}
```

Em `history-service/.gitignore`, apagar as linhas `src/main/resources/app.key` e `src/main/resources/app.sub`.

- [ ] **Step 4: Adicionar a dependência de teste de segurança**

Em `history-service/pom.xml`, logo antes de `</dependencies>` (depois do bloco do `awaitility`), inserir com a mesma indentação por tabs do arquivo:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-test</artifactId>
			<scope>test</scope>
		</dependency>
```

- [ ] **Step 5: Corrigir `GraphQlExceptionResolverTest`**

Adicionar os imports:

```java
import org.springframework.security.test.context.support.WithMockUser;
```

Adicionar a anotação logo abaixo de `@GraphQlTest(HistoryQueryController.class)`:

```java
@WithMockUser(roles = "NURSE")
```

Substituir as duas ocorrências de:

```java
        when(queryService.patientHistory(any()))
```

por:

```java
        when(queryService.patientHistory(any(), any()))
```

- [ ] **Step 6: Corrigir `HistoryQueryControllerTest`**

Adicionar os imports:

```java
import org.springframework.security.test.context.support.WithMockUser;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
```

Adicionar `@WithMockUser(roles = "NURSE")` logo abaixo de `@GraphQlTest(HistoryQueryController.class)`.

Aplicar as substituições (todas as ocorrências):

| De | Para |
|---|---|
| `queryService.patientHistory(10L)` | `queryService.patientHistory(eq(10L), any())` |
| `queryService.patientHistory(404L)` | `queryService.patientHistory(eq(404L), any())` |
| `verify(queryService).patientHistory(10L);` | `verify(queryService).patientHistory(eq(10L), any());` |

- [ ] **Step 7: Corrigir e ampliar `MedicalHistoryQueryServiceTest`**

Adicionar os imports:

```java
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
```

Substituições (todas as ocorrências):

| De | Para |
|---|---|
| `service.patientHistory(10L)` | `service.patientHistory(10L, null)` |
| `service.patientHistory(404L)` | `service.patientHistory(404L, null)` |
| `service.patientHistory(null)` | `service.patientHistory(null, null)` |

Antes da última `}` da classe, adicionar:

```java

    private JwtAuthenticationToken token(String role, long userId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("user_id", userId)
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role)));
    }

    @Test
    void pacienteConsultaOProprioHistorico() {
        when(repository.findLatestEventPerAppointment(10L)).thenReturn(List.of(registro()));

        assertThat(service.patientHistory(10L, token("ROLE_PATIENT", 10L))).hasSize(1);
    }

    @Test
    void pacienteNaoConsultaHistoricoDeOutroPaciente() {
        assertThatThrownBy(() -> service.patientHistory(11L, token("ROLE_PATIENT", 10L)))
                .isInstanceOf(AccessDeniedException.class);

        verify(repository, never()).findLatestEventPerAppointment(any());
    }

    @Test
    void enfermeiraConsultaHistoricoDeQualquerPaciente() {
        when(repository.findLatestEventPerAppointment(11L)).thenReturn(List.of(registro()));

        assertThat(service.patientHistory(11L, token("ROLE_NURSE", 3L))).hasSize(1);
    }
```

- [ ] **Step 8: Criar o emissor de token de teste**

Criar `src/test/java/br/com/tech/challenge/historyservice/support/TestJwt.java`:

```java
package br.com.tech.challenge.historyservice.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Assina tokens com o par de teste de src/test/resources/jwt-test, no mesmo formato do
 * auth-service: emissor auth-service, role no claim scope e o id do usuario em user_id.
 */
public final class TestJwt {

    private TestJwt() {
    }

    public static String token(String scope, long userId) {
        try (InputStream publica = new ClassPathResource("jwt-test/app.sub").getInputStream();
             InputStream privada = new ClassPathResource("jwt-test/app.key").getInputStream()) {
            RSAPublicKey publicKey = RsaKeyConverters.x509().convert(publica);
            RSAPrivateKey privateKey = RsaKeyConverters.pkcs8().convert(privada);
            RSAKey jwk = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
            NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
            Instant agora = Instant.now();
            return encoder.encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                    .issuer("auth-service")
                    .subject("teste@hospital.com")
                    .issuedAt(agora)
                    .expiresAt(agora.plusSeconds(300))
                    .claim("scope", scope)
                    .claim("user_id", userId)
                    .build())).getTokenValue();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

- [ ] **Step 9: Enviar token no `HistoryGraphQlIT`**

Adicionar o import:

```java
import br.com.tech.challenge.historyservice.support.TestJwt;
```

Substituir:

```java
    @Autowired
    private HttpGraphQlTester graphQlTester;
```

por:

```java
    @Autowired
    private HttpGraphQlTester testerSemToken;

    private HttpGraphQlTester graphQlTester;
```

Substituir:

```java
    @BeforeEach
    void limparBase() {
        repository.deleteAll();
    }
```

por:

```java
    @BeforeEach
    void limparBase() {
        // O endpoint exige JWT: o token e assinado com a chave de teste que a aplicacao valida.
        graphQlTester = testerSemToken.mutate()
                .header("Authorization", "Bearer " + TestJwt.token("ROLE_NURSE", 3L))
                .build();
        repository.deleteAll();
    }
```

- [ ] **Step 10: Rodar a suíte**

Run: `cd history-service && ./mvnw -B test 2>&1 | grep -E "Tests run:.*Skipped: [0-9]+$|BUILD"`
Expected: `Tests run: 60, Failures: 0, Errors: 0, Skipped: 0` e `BUILD SUCCESS`.

- [ ] **Step 11: Atualizar o README do history**

Em `history-service/README.md`:

1. Substituir o bloco:

```markdown
> O endpoint está **aberto** nesta fase. A autorização por role entra quando a Pessoa 1 publicar o
> formato do JWT.
```

por:

```markdown
> O endpoint exige um JWT do auth-service. Os exemplos acima precisam do header
> `-H "Authorization: Bearer $TOKEN"`, com o token obtido em
> `TOKEN=$(curl -s -X POST http://localhost:8083/auth/login -u maria.santos@hospital.com:Enfermeira@123 | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')`.
> `patientHistory` aceita DOCTOR, NURSE e PATIENT (só o próprio `patientId`); `appointmentTimeline`
> aceita DOCTOR e NURSE. A chave pública vem de `security.jwt.public-key` — pela IDE, o padrão é
> `file:../.jwt-keys/app.sub`, criada ao subir o ambiente pela raiz.
```

2. Nos dois blocos `curl -s -X POST http://localhost:8081/graphql -H 'content-type: application/json' \`, substituir essa linha por:

```bash
curl -s -X POST http://localhost:8081/graphql -H 'content-type: application/json' -H "Authorization: Bearer $TOKEN" \
```

3. Substituir a frase `57 testes: contrato da mensagem,` por `60 testes: contrato da mensagem, regra de acesso do paciente,`.

4. Substituir a seção inteira `### Collection do Postman` até antes de `## Rodar os testes automatizados` por:

```markdown
### Collection do Postman

O roteiro com login, agendamento, histórico e notificações está na collection única da raiz:
[`docs/postman/tech-challenge-grupo65.postman_collection.json`](../docs/postman/tech-challenge-grupo65.postman_collection.json).

```

- [ ] **Step 12: Commit**

```bash
git add history-service
git status --short history-service   # só pom, SecurityConfig, properties, .gitignore, README e testes; nenhum .env
git commit -m "fix(history): testes com JWT, chave por propriedade e acesso do paciente" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019ij1SRidK2Nd5YVMagZCXb"
```

---
### Task 3: notification-service protegido por JWT com regra por role

**Files:**
- Modify: `notification-service/pom.xml`
- Create: `notification-service/src/main/java/br/com/tech/challenge/notificationservice/config/SecurityConfig.java`
- Create: `notification-service/src/main/java/br/com/tech/challenge/notificationservice/config/NotificationAuthorization.java`
- Modify: `notification-service/src/main/java/br/com/tech/challenge/notificationservice/controller/NotificationController.java`
- Modify: `notification-service/src/main/resources/application.properties` (fim do arquivo)
- Create: `notification-service/src/test/resources/jwt-test/app.key`, `app.sub`, `README.md`
- Create: `notification-service/src/test/resources/config/application.properties`
- Modify: `notification-service/src/test/java/br/com/tech/challenge/notificationservice/controller/NotificationControllerTest.java`
- Modify: `notification-service/README.md`

**Interfaces:**
- Produces: `NotificationAuthorization.checkPatientAccess(Authentication authentication, Long patientId)`; `GET /notifications/patient/{patientId}` exige `Authorization: Bearer` com role DOCTOR, NURSE ou PATIENT (PATIENT só o próprio `user_id`); `/actuator/health` público.

Validado em spike em 2026-09-14: 26 testes verdes (`NotificationApplicationTests` e `NotificationMessageListenerIT` carregam a `SecurityConfig` com a chave de teste; o consumo do RabbitMQ não usa token).

- [ ] **Step 1: Adicionar as dependências**

Em `notification-service/pom.xml`, logo depois do bloco do `spring-boot-starter-actuator`, inserir:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
		</dependency>
```

E logo antes de `</dependencies>` (depois do `awaitility`):

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-test</artifactId>
			<scope>test</scope>
		</dependency>
```

- [ ] **Step 2: Gerar o par de chaves só de teste e a configuração de teste**

```bash
cd notification-service
mkdir -p src/test/resources/jwt-test src/test/resources/config
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out src/test/resources/jwt-test/app.key
openssl pkey -pubout -in src/test/resources/jwt-test/app.key -out src/test/resources/jwt-test/app.sub
```

Criar `src/test/resources/jwt-test/README.md`:

```markdown
# Chaves só de teste

Par RSA usado apenas pelos testes automatizados deste serviço. Não vale em nenhum ambiente real:
o ambiente Docker gera o próprio par em `.jwt-keys/`, na raiz do monorepo.
```

Criar `src/test/resources/config/application.properties`:

```properties
security.jwt.public-key=classpath:jwt-test/app.sub
```

- [ ] **Step 3: Escrever o teste do controller com segurança (vai falhar)**

Substituir o conteúdo de `NotificationControllerTest.java` por:

```java
package br.com.tech.challenge.notificationservice.controller;

import br.com.tech.challenge.notificationservice.config.NotificationAuthorization;
import br.com.tech.challenge.notificationservice.config.SecurityConfig;
import br.com.tech.challenge.notificationservice.dto.AppointmentEventStatus;
import br.com.tech.challenge.notificationservice.model.Notification;
import br.com.tech.challenge.notificationservice.model.NotificationStatus;
import br.com.tech.challenge.notificationservice.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fatia web com a SecurityConfig real. O JwtDecoder e mockado: cada "token" da requisicao vira um
 * Jwt com a role e o user_id do cenario, sem precisar de assinatura.
 */
@WebMvcTest(NotificationController.class)
@Import({SecurityConfig.class, NotificationAuthorization.class})
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void tokens() {
        when(jwtDecoder.decode("nurse-token")).thenReturn(jwt("ROLE_NURSE", 3L));
        when(jwtDecoder.decode("patient-10-token")).thenReturn(jwt("ROLE_PATIENT", 10L));
    }

    @Test
    void semTokenDevolve401() throws Exception {
        mockMvc.perform(get("/notifications/patient/10"))
                .andExpect(status().isUnauthorized());

        verify(notificationService, never()).findByPatientId(any());
    }

    @Test
    void enfermeiraListaAsNotificacoesDoPaciente() throws Exception {
        when(notificationService.findByPatientId(10L)).thenReturn(List.of(notificacao()));

        mockMvc.perform(get("/notifications/patient/10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer nurse-token"))
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

        mockMvc.perform(get("/notifications/patient/99")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer nurse-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void pacienteListaAsPropriasNotificacoes() throws Exception {
        when(notificationService.findByPatientId(10L)).thenReturn(List.of(notificacao()));

        mockMvc.perform(get("/notifications/patient/10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer patient-10-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void pacienteNaoListaNotificacoesDeOutroPaciente() throws Exception {
        mockMvc.perform(get("/notifications/patient/11")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer patient-10-token"))
                .andExpect(status().isForbidden());

        verify(notificationService, never()).findByPatientId(any());
    }

    private Notification notificacao() {
        Notification notification = new Notification();
        notification.setId(1L);
        notification.setEventId(UUID.fromString("8f14e45f-ceea-467a-9f4b-1d2c3e4f5a6b"));
        notification.setEventStatus(AppointmentEventStatus.SCHEDULED);
        notification.setAppointmentId(42L);
        notification.setPatientId(10L);
        notification.setMessage("Sua consulta foi agendada para 2030-09-10T14:30");
        notification.setCreatedAt(LocalDateTime.of(2026, 9, 12, 14, 0));
        notification.setStatus(NotificationStatus.SENT);
        return notification;
    }

    private Jwt jwt(String scope, long userId) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("scope", scope)
                .claim("user_id", userId)
                .subject("user@hospital.com")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
```

- [ ] **Step 4: Confirmar a falha**

Run: `cd notification-service && ./mvnw -B test -Dtest=NotificationControllerTest 2>&1 | grep -E "cannot find symbol|BUILD" | head -3`
Expected: `cannot find symbol` (`SecurityConfig`/`NotificationAuthorization` ainda não existem) e `BUILD FAILURE`.

- [ ] **Step 5: Criar `SecurityConfig`**

Criar `config/SecurityConfig.java`:

```java
package br.com.tech.challenge.notificationservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.security.interfaces.RSAPublicKey;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${security.jwt.public-key}")
    private RSAPublicKey publicKey;

    @Value("${security.jwt.issuer}")
    private String issuer;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));

        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("scope");
        authorities.setAuthorityPrefix("");

        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
```

- [ ] **Step 6: Criar `NotificationAuthorization`**

Criar `config/NotificationAuthorization.java`:

```java
package br.com.tech.challenge.notificationservice.config;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * PATIENT so ve as proprias notificacoes: o patientId da rota precisa ser o user_id do token.
 * Mesma regra do AppointmentAuthorization (appointment-service) e do MedicalHistoryQueryService
 * (history-service). DOCTOR e NURSE veem qualquer paciente.
 */
@Component
public class NotificationAuthorization {

    public void checkPatientAccess(Authentication authentication, Long patientId) {
        if (authentication instanceof JwtAuthenticationToken jwt
                && jwt.getAuthorities().stream().anyMatch(a -> Objects.equals(a.getAuthority(), "ROLE_PATIENT"))) {
            Number userId = jwt.getToken().getClaim("user_id");
            if (userId == null || userId.longValue() != patientId) {
                throw new AccessDeniedException("Patient cannot access other patient notifications");
            }
        }
    }
}
```

- [ ] **Step 7: Proteger o controller**

Substituir o conteúdo de `controller/NotificationController.java` por:

```java
package br.com.tech.challenge.notificationservice.controller;

import br.com.tech.challenge.notificationservice.config.NotificationAuthorization;
import br.com.tech.challenge.notificationservice.model.Notification;
import br.com.tech.challenge.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final NotificationAuthorization notificationAuthorization;

    @GetMapping("/patient/{patientId}")
    @PreAuthorize("hasAnyRole('DOCTOR', 'NURSE', 'PATIENT')")
    public List<Notification> findByPatient(@PathVariable Long patientId, Authentication authentication) {
        notificationAuthorization.checkPatientAccess(authentication, patientId);
        return notificationService.findByPatientId(patientId);
    }
}
```

- [ ] **Step 8: Declarar as propriedades**

No fim de `application.properties`, acrescentar:

```properties

# JWT emitido pelo auth-service. No Docker a chave vem de JWT_PUBLIC_KEY=file:/keys/app.sub.
security.jwt.issuer=${JWT_ISSUER:auth-service}
security.jwt.public-key=${JWT_PUBLIC_KEY:file:../.jwt-keys/app.sub}
```

- [ ] **Step 9: Rodar a suíte**

Run: `cd notification-service && ./mvnw -B test 2>&1 | grep -E "Tests run:.*Skipped: [0-9]+$|BUILD"`
Expected: `Tests run: 26, Failures: 0, Errors: 0, Skipped: 0` e `BUILD SUCCESS`.

- [ ] **Step 10: Atualizar o README do notification**

Em `notification-service/README.md`:

1. Substituir a tabela de endpoints:

```markdown
| Método | Rota | Descrição |
|---|---|---|
| GET | `/notifications/patient/{patientId}` | Lista as notificações de um paciente |
```

por:

```markdown
| Método | Rota | Permissão | Descrição |
|---|---|---|---|
| GET | `/notifications/patient/{patientId}` | DOCTOR, NURSE, PATIENT (só o próprio) | Lista as notificações de um paciente |
| GET | `/actuator/health` | pública | Health check |
```

2. Substituir a linha da tabela de testes:

```markdown
| `NotificationControllerTest` | `GET /notifications/patient/{patientId}` |
```

por:

```markdown
| `NotificationControllerTest` | `GET /notifications/patient/{patientId}`: 401 sem token, 200 para NURSE e para o PATIENT dono, 403 para outro paciente |
```

3. Substituir a seção `## Segurança` inteira (título e parágrafo) por:

```markdown
## Segurança

As rotas exigem um JWT do auth-service em `Authorization: Bearer <token>`; só `/actuator/health`
é público. A role vem do claim `scope`: DOCTOR e NURSE listam as notificações de qualquer
paciente, e PATIENT só as próprias — o `patientId` da rota precisa ser o `user_id` do token, senão
a resposta é `403`. O consumo do RabbitMQ é interno e não usa token.

A chave pública vem de `security.jwt.public-key`. No Docker é montada de `.jwt-keys/app.sub`;
pela IDE, rodando a partir desta pasta, o padrão é `file:../.jwt-keys/app.sub`, criada ao subir o
ambiente pela raiz. Os testes usam o par em `src/test/resources/jwt-test/`.
```

4. Na seção "Testar publicando um evento manualmente", substituir:

```bash
curl http://localhost:8082/notifications/patient/10
```

por:

```bash
TOKEN=$(curl -s -X POST http://localhost:8083/auth/login -u maria.santos@hospital.com:Enfermeira@123 \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
curl -H "Authorization: Bearer $TOKEN" http://localhost:8082/notifications/patient/10
```

- [ ] **Step 11: Commit**

```bash
git add notification-service
git status --short notification-service   # nenhum .env
git commit -m "feat(notification): exige JWT e restringe o paciente as proprias notificacoes" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019ij1SRidK2Nd5YVMagZCXb"
```

---
### Task 4: auth-service em Java 21, com actuator e chaves por propriedade

**Files:**
- Modify: `auth-service/pom.xml:30` e dependências
- Modify: `auth-service/src/main/java/br/com/tech/challenge/authservice/config/SecurityConfig.java:32-53`
- Modify: `auth-service/src/main/java/br/com/tech/challenge/authservice/service/UserService.java:79`
- Modify: `auth-service/src/main/resources/application.yaml` (fim)
- Delete: `auth-service/src/main/resources/db/migration/V4__seed_users.sql`
- Create: `auth-service/.dockerignore`
- Modify: `auth-service/.gitignore:7-8`
- Modify: modo de `auth-service/mvnw` (100644 → 100755)
- Create: `auth-service/src/test/resources/jwt-test/app.key`, `app.sub`, `README.md`
- Create: `auth-service/src/test/resources/config/application.properties`
- Modify: `auth-service/src/test/java/br/com/tech/challenge/authservice/config/SecurityConfigTest.java:64`

**Interfaces:**
- Produces: `POST /auth/login` (Basic) → `{"access_token": "..."}`; `/actuator/health` público; propriedades `security.jwt.public-key` e `security.jwt.private-key`; usuários de exemplo só com `SPRING_PROFILES_ACTIVE=dev` (ligado no compose na Task 5).

Contexto: `java.version=25` impede compilar com o JDK 21 e quebra o build Docker (imagem `eclipse-temurin:21`); só dois `_` em lambdas usam recurso do Java 22+. O mesmo `V4__seed_users.sql` existe em `db/migration` e em `db/dev-seed`: com o profile `dev` o Flyway veria duas migrations `V4`. Bancos que já aplicaram o `V4` continuam válidos (mesma versão e conteúdo). Validado em spike: 25 testes verdes com JDK 21.

- [ ] **Step 1: Confirmar a falha atual**

Run: `cd auth-service && ./mvnw -B test 2>&1 | grep -E "release version|BUILD" | head -2`
Expected: `release version 25 not supported` e `BUILD FAILURE`.

- [ ] **Step 2: Java 21 e actuator no `pom.xml`**

Substituir `<java.version>25</java.version>` por `<java.version>21</java.version>`.

Logo depois do bloco do `spring-boot-starter-validation`, inserir:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
```

- [ ] **Step 3: Remover os `_` de lambdas**

Em `SecurityConfig.java`, substituir `.access((authentication, _) -> {` por `.access((authentication, context) -> {`.

Em `UserService.java`, substituir `.ifPresent(_ -> {` por `.ifPresent(existingUser -> {`.

- [ ] **Step 4: Gerar o par de teste e a configuração de teste**

```bash
cd auth-service
mkdir -p src/test/resources/jwt-test src/test/resources/config
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out src/test/resources/jwt-test/app.key
openssl pkey -pubout -in src/test/resources/jwt-test/app.key -out src/test/resources/jwt-test/app.sub
```

Criar `src/test/resources/jwt-test/README.md`:

```markdown
# Chaves só de teste

Par RSA usado apenas pelos testes automatizados deste serviço. Não vale em nenhum ambiente real:
o ambiente Docker gera o próprio par em `.jwt-keys/`, na raiz do monorepo.
```

Criar `src/test/resources/config/application.properties`:

```properties
security.jwt.public-key=classpath:jwt-test/app.sub
security.jwt.private-key=classpath:jwt-test/app.key
```

- [ ] **Step 5: Escrever o teste do health (vai falhar)**

Em `SecurityConfigTest.java`, antes de `void shouldRejectUsersEndpointWithoutCredentials()` (e da anotação `@Test` dele), inserir:

```java
    @Test
    void shouldLetHealthEndpointThroughWithoutCredentials() throws Exception {
        // Nesta fatia o actuator nao existe: 404 prova que a rota passou pelo filtro de seguranca,
        // que antes respondia 401 por causa do denyAll.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isNotFound());
    }

```

Run: `cd auth-service && ./mvnw -B test 2>&1 | grep -E "Tests run:.*Skipped: [0-9]+$|BUILD|app.sub"`
Expected: `BUILD FAILURE` — o contexto do `SecurityConfigTest` ainda procura `classpath:app.sub`.

- [ ] **Step 6: Ler as chaves por propriedade e liberar o health**

Em `SecurityConfig.java`:

| De | Para |
|---|---|
| `@Value("classpath:app.sub")` | `@Value("${security.jwt.public-key}")` |
| `@Value("classpath:app.key")` | `@Value("${security.jwt.private-key}")` |

Substituir:

```java
                        .requestMatchers("/error").permitAll()
```

por:

```java
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/error").permitAll()
```

No fim de `application.yaml`, acrescentar:

```yaml

security:
  jwt:
    # No Docker vem de JWT_PUBLIC_KEY/JWT_PRIVATE_KEY (file:/keys/...); pela IDE, da pasta .jwt-keys da raiz.
    public-key: ${JWT_PUBLIC_KEY:file:../.jwt-keys/app.sub}
    private-key: ${JWT_PRIVATE_KEY:file:../.jwt-keys/app.key}

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

- [ ] **Step 7: Seed só no profile `dev`, `.dockerignore`, `.gitignore` e `mvnw`**

```bash
cd auth-service
git rm src/main/resources/db/migration/V4__seed_users.sql
git update-index --chmod=+x mvnw
chmod +x mvnw
printf 'target/\n.git/\n.idea/\n.env\n*.iml\ndocs/\n' > .dockerignore
```

Em `auth-service/.gitignore`, apagar as linhas `**/src/main/resources/app.key` e `**/src/main/resources/app.sub`.

- [ ] **Step 8: Rodar a suíte**

Run: `cd auth-service && ./mvnw -B test 2>&1 | grep -E "Tests run:.*Skipped: [0-9]+$|BUILD"`
Expected: `Tests run: 25, Failures: 0, Errors: 0, Skipped: 0` e `BUILD SUCCESS`.

Run: `git ls-files -s auth-service/mvnw | cut -c1-6`
Expected: `100755`.

- [ ] **Step 9: Commit**

```bash
git add auth-service
git status --short auth-service   # nenhum .env, nenhuma chave fora de jwt-test
git commit -m "fix(auth): Java 21, actuator, chaves por propriedade e seed so no profile dev" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019ij1SRidK2Nd5YVMagZCXb"
```

O `auth-service/README.md` é reescrito na Task 8.

---
### Task 5: `jwt-keys` no compose e os quatro apps usando a chave montada

**Files:**
- Modify: `infra/docker-compose.yml`
- Modify: `appointment-service/docker-compose.yml`, `history-service/docker-compose.yml`, `notification-service/docker-compose.yml`, `auth-service/docker-compose.yml`
- Modify: `.gitignore`, `.gitattributes`
- Delete: `scripts/generate-jwt-keys.sh`, `scripts/generate-jwt-keys.ps1`

**Interfaces:**
- Consumes: propriedades das Tasks 1–4 (`JWT_PUBLIC_KEY`, `JWT_PRIVATE_KEY`, `SPRING_PROFILES_ACTIVE`).
- Produces: `.jwt-keys/app.key` e `.jwt-keys/app.sub` na raiz, criados na primeira subida; 9 containers healthy (4 apps, 4 Postgres, RabbitMQ) e `jwt-keys` concluído com código 0. A pasta é criada pelo Docker como root no Linux: para regenerar o par, `docker run --rm -v "$(pwd)/.jwt-keys:/keys" alpine:3.22 rm -f /keys/app.key /keys/app.sub`.

Validado em spike (projeto isolado, 2026-09-14): o `jwt-keys` incluído por vários composes vira um só, `../.jwt-keys` resolve para a raiz, os apps esperam `service_completed_successfully`, a segunda subida (inclusive de dentro da pasta de um serviço) reaproveita o par, e o usuário `app` (uid 100) das imagens lê os arquivos com modo 444.

- [ ] **Step 1: Serviço `jwt-keys` em `infra/docker-compose.yml`**

Substituir:

```yaml
services:
  rabbitmq:
```

por:

```yaml
services:
  # Gera o par RSA do JWT na primeira subida, em .jwt-keys/ na raiz do monorepo, e termina.
  # O comando fica aqui (e nao num .sh) para nao sofrer conversao CRLF num checkout do Windows.
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

  rabbitmq:
```

- [ ] **Step 2: Montar a chave nos apps**

Rodar da raiz (cada `assert` garante que o trecho original existe exatamente uma vez):

```bash
python3 - <<'PY'
import pathlib

def troca(caminho, antigo, novo):
    p = pathlib.Path(caminho)
    s = p.read_text()
    assert s.count(antigo) == 1, f"{caminho}: trecho nao encontrado uma unica vez:\n{antigo}"
    p.write_text(s.replace(antigo, novo))

for svc in ("appointment", "history", "notification"):
    f = f"{svc}-service/docker-compose.yml"
    troca(f,
          "      RABBITMQ_PORT: 5672\n    ports:\n",
          "      RABBITMQ_PORT: 5672\n      JWT_PUBLIC_KEY: file:/keys/app.sub\n"
          "    volumes:\n      - ../.jwt-keys:/keys:ro\n    ports:\n")
    troca(f,
          "    depends_on:\n",
          "    depends_on:\n      jwt-keys:\n        condition: service_completed_successfully\n")

f = "auth-service/docker-compose.yml"
troca(f,
      "      DB_PORT: 5432\n      RABBITMQ_HOST: rabbitmq\n      RABBITMQ_PORT: 5672\n    ports:\n",
      "      DB_PORT: 5432\n      SPRING_PROFILES_ACTIVE: dev          # insere os usuarios de exemplo\n"
      "      JWT_PUBLIC_KEY: file:/keys/app.sub\n      JWT_PRIVATE_KEY: file:/keys/app.key\n"
      "    volumes:\n      - ../.jwt-keys:/keys:ro\n    ports:\n")
troca(f,
      "    depends_on:\n",
      "    depends_on:\n      jwt-keys:\n        condition: service_completed_successfully\n")
print("ok")
PY
```

Expected: `ok`. O `auth-app` deixa de receber variáveis do RabbitMQ, que ele não usa.

- [ ] **Step 3: `.gitignore`, `.gitattributes` e scripts antigos**

Acrescentar ao fim de `.gitignore` (garantindo quebra de linha antes):

```
.jwt-keys/
```

Em `.gitattributes`, substituir:

```
mvnw text eol=lf
*.sh text eol=lf
Makefile text eol=lf
```

por:

```
mvnw text eol=lf
*.sh text eol=lf
Makefile text eol=lf
# Um .env copiado de um .env.example com CRLF leva o \r para dentro dos valores.
.env.example text eol=lf
```

```bash
git rm scripts/generate-jwt-keys.sh scripts/generate-jwt-keys.ps1
```

- [ ] **Step 4: Validar a configuração**

```bash
make setup
docker compose config --quiet && echo "config ok"
docker compose config | grep -cE "condition: service_completed_successfully"
docker compose config | grep -E "JWT_PRIVATE_KEY|SPRING_PROFILES_ACTIVE"
```

Expected: `config ok`; `4`; uma linha `JWT_PRIVATE_KEY: file:/keys/app.key` e uma `SPRING_PROFILES_ACTIVE: dev`.

- [ ] **Step 5: Subir num projeto isolado**

Os apps usam as mesmas portas do ambiente do mantenedor. Se houver containers `grupo65` rodando, pare-os preservando volumes antes (`make down`, nunca `make clean`). Cada comando abaixo carrega o projeto explicitamente:

```bash
P=g65task5
docker compose -p $P up -d --build
for i in $(seq 1 72); do
  n=$(docker ps --filter label=com.docker.compose.project=$P --format '{{.Status}}' | grep -c "(healthy)")
  [ "$n" = 9 ] && break; sleep 5
done
echo "healthy: $n/9"
docker compose -p $P ps -a --format '{{.Service}} {{.State}} {{.ExitCode}}' | grep jwt-keys
sha1sum .jwt-keys/app.sub > /tmp/jwt-antes.sha
```

Expected: `healthy: 9/9` e `jwt-keys exited 0`.

- [ ] **Step 6: Conferir autenticação ponta a ponta**

```bash
TOKEN=$(curl -s -X POST http://localhost:8083/auth/login -u maria.santos@hospital.com:Enfermeira@123 \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
echo "token: ${TOKEN:0:20}..."
for u in http://localhost:8080/appointments http://localhost:8082/notifications/patient/4; do
  echo "$u sem token: $(curl -s -o /dev/null -w '%{http_code}' $u) | com token: $(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" $u)"
done
echo "graphql sem token: $(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:8081/graphql -H 'content-type: application/json' -d '{"query":"{ patientHistory(patientId: 4) { appointmentId } }"}')"
echo "health auth: $(curl -s http://localhost:8083/actuator/health)"
```

Expected: token começando com `eyJ`; `401` sem token e `200` com token nas duas URLs; GraphQL sem token `401`; health do auth com `"status":"UP"`.

- [ ] **Step 7: Segunda subida mantém o par**

```bash
P=g65task5
docker compose -p $P down
docker compose -p $P up -d
sleep 20
sha1sum -c /tmp/jwt-antes.sha
```

Expected: `.jwt-keys/app.sub: OK`.

- [ ] **Step 8: Limpar o projeto isolado**

```bash
docker compose -p g65task5 down -v --rmi local
docker ps -aq --filter label=com.docker.compose.project=g65task5 | wc -l
```

Expected: `0`. A pasta `.jwt-keys/` fica (é ignorada pelo git e reaproveitada).

- [ ] **Step 9: Commit**

```bash
git add infra/docker-compose.yml appointment-service/docker-compose.yml history-service/docker-compose.yml \
        notification-service/docker-compose.yml auth-service/docker-compose.yml .gitignore .gitattributes scripts
git status --short   # nenhum .env, nenhum .jwt-keys
git commit -m "feat: gera as chaves JWT no compose e sobe o auth-service com os demais" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019ij1SRidK2Nd5YVMagZCXb"
```

---
### Task 6: `make smoke` autentica antes de testar

**Files:**
- Modify: `scripts/smoke-test.sh` (reescrito)
- Modify: `Makefile:32`

**Interfaces:**
- Consumes: `POST /auth/login` (Basic) → `access_token`; paciente de exemplo com `user_id` 4; rotas protegidas das Tasks 1–3.
- Produces: `make smoke` com 5 etapas; variáveis opcionais `AUTH_URL`, `APPOINTMENT_URL`, `HISTORY_URL`, `NOTIFICATION_URL`, `TIMEOUT_SEGUNDOS`, `LOGIN_EMAIL`, `LOGIN_SENHA`, `PATIENT_ID`.

O paciente passa a ser fixo (4, o paciente de exemplo), então a etapa 4 amarra a verificação ao `appointmentId` criado, para não confundir com execuções anteriores.

- [ ] **Step 1: Reescrever `scripts/smoke-test.sh`**

Substituir o conteúdo por (manter o modo 100755):

```bash
#!/usr/bin/env bash
# Teste ponta a ponta: login no auth-service -> appointment-service -> RabbitMQ ->
# history-service e notification-service. Cria um agendamento autenticado como enfermeira e
# espera o evento aparecer no historico e virar uma notificacao enviada.
set -euo pipefail

AUTH_URL="${AUTH_URL:-http://localhost:8083}"
APPOINTMENT_URL="${APPOINTMENT_URL:-http://localhost:8080}"
HISTORY_URL="${HISTORY_URL:-http://localhost:8081}"
NOTIFICATION_URL="${NOTIFICATION_URL:-http://localhost:8082}"
TIMEOUT_SEGUNDOS="${TIMEOUT_SEGUNDOS:-60}"
LOGIN_EMAIL="${LOGIN_EMAIL:-maria.santos@hospital.com}"
LOGIN_SENHA="${LOGIN_SENHA:-Enfermeira@123}"
# 4 e o user_id do paciente de exemplo (lucas.oliveira@hospital.com)
PATIENT_ID="${PATIENT_ID:-4}"
APPOINTMENT_DATE=$(date -u -d '+30 days' '+%Y-%m-%dT%H:%M:%S' 2>/dev/null || date -u -v+30d '+%Y-%m-%dT%H:%M:%S')

echo "==> 1/5 aguardando os servicos responderem"
for url in "$AUTH_URL/actuator/health" "$APPOINTMENT_URL/actuator/health" \
           "$HISTORY_URL/actuator/health" "$NOTIFICATION_URL/actuator/health"; do
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

echo "==> 2/5 autenticando como $LOGIN_EMAIL"
login=$(curl -s -X POST "$AUTH_URL/auth/login" -u "$LOGIN_EMAIL:$LOGIN_SENHA" || echo '')
TOKEN=$(echo "$login" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
if [ -z "$TOKEN" ]; then
  echo "FALHA: login nao devolveu access_token" >&2
  echo "Resposta: $login" >&2
  echo "Investigue: docker compose logs auth-app | grep -iE 'flyway|seed|key'" >&2
  exit 1
fi
echo "    OK token recebido"
AUTH_HEADER="Authorization: Bearer $TOKEN"

echo "==> 3/5 criando agendamento (patientId=$PATIENT_ID)"
resposta=$(curl -s -w '\n%{http_code}' -X POST "$APPOINTMENT_URL/appointments" \
  -H 'Content-Type: application/json' -H "$AUTH_HEADER" \
  -d "{\"patientId\":$PATIENT_ID,\"doctorId\":2,\"appointmentDate\":\"$APPOINTMENT_DATE\",\"description\":\"Smoke test\"}") \
  || { echo "FALHA: nao consegui falar com $APPOINTMENT_URL" >&2; exit 1; }
http_code=$(echo "$resposta" | tail -n1)
corpo=$(echo "$resposta" | sed '$d')
if [ "$http_code" != "201" ]; then
  echo "FALHA: POST /appointments retornou $http_code" >&2
  echo "Resposta: $corpo" >&2
  exit 1
fi
APPOINTMENT_ID=$(echo "$corpo" | sed -n 's/^{"id":\([0-9]*\).*/\1/p')
echo "    resposta: $corpo"

echo "==> 4/5 aguardando o evento chegar no history-service via RabbitMQ"
consulta="{\"query\":\"{ appointmentTimeline(appointmentId: \\\"$APPOINTMENT_ID\\\") { appointmentId eventStatus description } }\"}"
fim=$(( SECONDS + TIMEOUT_SEGUNDOS ))
while true; do
  historico=$(curl -sf -X POST "$HISTORY_URL/graphql" \
    -H 'Content-Type: application/json' -H "$AUTH_HEADER" -d "$consulta" || echo '')
  if echo "$historico" | grep -q '"eventStatus":"SCHEDULED"'; then
    echo "    OK evento recebido: $historico"
    break
  fi
  if (( SECONDS >= fim )); then
    echo "FALHA: o evento nao chegou ao history-service em ${TIMEOUT_SEGUNDOS}s" >&2
    echo "Ultima resposta do GraphQL: $historico" >&2
    echo "Investigue: docker compose logs history-app | grep -iE 'rabbit|jwt|key'" >&2
    exit 1
  fi
  sleep 2
done

echo "==> 5/5 aguardando a notificacao ser enviada pelo notification-service"
fim=$(( SECONDS + TIMEOUT_SEGUNDOS ))
while true; do
  notificacoes=$(curl -sf -H "$AUTH_HEADER" "$NOTIFICATION_URL/notifications/patient/$PATIENT_ID" || echo '')
  if echo "$notificacoes" | grep -q "\"appointmentId\":$APPOINTMENT_ID,[^}]*\"status\":\"SENT\""; then
    echo "    OK notificacao enviada para o agendamento $APPOINTMENT_ID"
    echo
    echo "SUCESSO: login -> appointment -> RabbitMQ -> history e notification funcionando."
    exit 0
  fi
  if (( SECONDS >= fim )); then
    echo "FALHA: a notificacao nao foi enviada em ${TIMEOUT_SEGUNDOS}s" >&2
    echo "Ultima resposta: $notificacoes" >&2
    echo "Investigue: docker compose logs notification-app | grep -iE 'rabbit|notifica|jwt'" >&2
    exit 1
  fi
  sleep 2
done
```

Observação sobre a etapa 5: a resposta do notification serializa `appointmentId` antes de `status`
no mesmo objeto (`{"appointmentId":2,"createdAt":...,"status":"SENT"}`, visto na validação do
notification em 2026-09-13), por isso o `grep` casa os dois dentro do mesmo `{...}`.

- [ ] **Step 2: Descrição do alvo no `Makefile`**

Substituir:

```make
smoke: ## Teste ponta a ponta: appointment -> RabbitMQ -> history e notification
```

por:

```make
smoke: ## Teste ponta a ponta: login -> appointment -> RabbitMQ -> history e notification
```

- [ ] **Step 3: Validar sintaxe e modo**

Run: `bash -n scripts/smoke-test.sh && git ls-files -s scripts/smoke-test.sh | cut -c1-6`
Expected: nenhuma saída do `bash -n` e `100755`.

- [ ] **Step 4: Rodar contra um projeto isolado**

Com os containers `grupo65` parados (`make down`, se estiverem de pé):

```bash
P=g65task6
docker compose -p $P up -d --build
for i in $(seq 1 72); do
  n=$(docker ps --filter label=com.docker.compose.project=$P --format '{{.Status}}' | grep -c "(healthy)")
  [ "$n" = 9 ] && break; sleep 5
done
echo "healthy: $n/9"
make smoke
LOGIN_SENHA=errada TIMEOUT_SEGUNDOS=10 ./scripts/smoke-test.sh; echo "exit=$?"
docker compose -p $P down -v --rmi local
```

Expected: `healthy: 9/9`; o `make smoke` termina com `SUCESSO: login -> appointment -> RabbitMQ -> history e notification funcionando.`; a execução com senha errada para em `FALHA: login nao devolveu access_token` com `exit=1`.

- [ ] **Step 5: Commit**

```bash
git add scripts/smoke-test.sh Makefile
git commit -m "test: make smoke autentica no auth-service antes do fluxo" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019ij1SRidK2Nd5YVMagZCXb"
```

---
### Task 7: collection do Postman no roteiro login → appointment → histórico → notificações

**Files:**
- Modify: `docs/postman/tech-challenge-grupo65.postman_collection.json` (substituído)

**Interfaces:**
- Consumes: `POST /auth/login`, rotas de appointment, `patientHistory`/`appointmentTimeline`, `GET /notifications/patient/{patientId}`.
- Produces: collection com 4 pastas e 8 requisições; variáveis `authUrl`, `appointmentUrl`, `historyUrl`, `notificationUrl`, `loginEmail`, `loginSenha`, `patientId=4`, `doctorId=2`, `token`, `appointmentId`, `appointmentDate`.

- [ ] **Step 1: Substituir a collection**

Substituir o conteúdo de `docs/postman/tech-challenge-grupo65.postman_collection.json` por:

```json
{
  "info": {
    "name": "Tech Challenge Fase 3 — Grupo 65",
    "description": "Roteiro de teste do projeto, em ordem: 1) autenticar e guardar o token, 2) criar e evoluir um agendamento, 3) consultar o historico via GraphQL, 4) listar as notificacoes.\n\n**Antes de comecar:** suba o ambiente pela raiz (`make up`, ou `docker compose up -d --build` no Windows) e espere os 9 containers ficarem `healthy`.\n\nRode as pastas na ordem, ou a collection inteira no Runner com *Delay* de 500 ms: historico e notificacoes chegam pelo RabbitMQ, de forma assincrona.\n\nA autenticacao Bearer `{{token}}` vale para a collection toda; so o login usa Basic Auth. O token dura 15 minutos: se aparecer 401, rode o login de novo.",
    "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
  },
  "auth": {
    "type": "bearer",
    "bearer": [
      {
        "key": "token",
        "value": "{{token}}",
        "type": "string"
      }
    ]
  },
  "variable": [
    {
      "key": "authUrl",
      "value": "http://localhost:8083"
    },
    {
      "key": "appointmentUrl",
      "value": "http://localhost:8080"
    },
    {
      "key": "historyUrl",
      "value": "http://localhost:8081"
    },
    {
      "key": "notificationUrl",
      "value": "http://localhost:8082"
    },
    {
      "key": "loginEmail",
      "value": "maria.santos@hospital.com"
    },
    {
      "key": "loginSenha",
      "value": "Enfermeira@123"
    },
    {
      "key": "patientId",
      "value": "4"
    },
    {
      "key": "doctorId",
      "value": "2"
    },
    {
      "key": "token",
      "value": ""
    },
    {
      "key": "appointmentId",
      "value": ""
    },
    {
      "key": "appointmentDate",
      "value": ""
    }
  ],
  "item": [
    {
      "name": "1. Autenticação",
      "item": [
        {
          "name": "Login da enfermeira",
          "event": [
            {
              "listen": "test",
              "script": {
                "type": "text/javascript",
                "exec": [
                  "pm.test('status 200', () => pm.response.to.have.status(200));",
                  "const b = pm.response.json();",
                  "pm.test('devolve access_token', () => pm.expect(b.access_token).to.be.a('string').and.not.empty);",
                  "// guarda o token para todas as requisicoes seguintes",
                  "pm.collectionVariables.set('token', b.access_token);"
                ]
              }
            }
          ],
          "request": {
            "auth": {
              "type": "basic",
              "basic": [
                {
                  "key": "username",
                  "value": "{{loginEmail}}",
                  "type": "string"
                },
                {
                  "key": "password",
                  "value": "{{loginSenha}}",
                  "type": "string"
                }
              ]
            },
            "method": "POST",
            "header": [],
            "url": "{{authUrl}}/auth/login",
            "description": "Basic Auth com e-mail e senha da enfermeira de exemplo. Salva o `access_token` na variavel `token`."
          }
        }
      ]
    },
    {
      "name": "2. Appointment",
      "item": [
        {
          "name": "Criar agendamento",
          "event": [
            {
              "listen": "prerequest",
              "script": {
                "type": "text/javascript",
                "exec": [
                  "// appointmentDate precisa estar no futuro (validacao @Future)",
                  "const d = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000);",
                  "pm.collectionVariables.set('appointmentDate', d.toISOString().slice(0, 19));"
                ]
              }
            },
            {
              "listen": "test",
              "script": {
                "type": "text/javascript",
                "exec": [
                  "pm.test('status 201', () => pm.response.to.have.status(201));",
                  "const b = pm.response.json();",
                  "pm.test('nasce SCHEDULED', () => pm.expect(b.status).to.eql('SCHEDULED'));",
                  "pm.collectionVariables.set('appointmentId', b.id);"
                ]
              }
            }
          ],
          "request": {
            "method": "POST",
            "header": [
              {
                "key": "Content-Type",
                "value": "application/json"
              }
            ],
            "url": "{{appointmentUrl}}/appointments",
            "body": {
              "mode": "raw",
              "raw": "{\n  \"patientId\": {{patientId}},\n  \"doctorId\": {{doctorId}},\n  \"appointmentDate\": \"{{appointmentDate}}\",\n  \"description\": \"Consulta de rotina - cardiologia\"\n}",
              "options": {
                "raw": {
                  "language": "json"
                }
              }
            },
            "description": "Somente NURSE cria agendamentos. Salva o `id` em `appointmentId`."
          }
        },
        {
          "name": "Buscar agendamento",
          "event": [
            {
              "listen": "test",
              "script": {
                "type": "text/javascript",
                "exec": [
                  "pm.test('status 200', () => pm.response.to.have.status(200));",
                  "pm.test('e o agendamento criado', () => pm.expect(pm.response.json().id).to.eql(Number(pm.collectionVariables.get('appointmentId'))));"
                ]
              }
            }
          ],
          "request": {
            "method": "GET",
            "header": [],
            "url": "{{appointmentUrl}}/appointments/{{appointmentId}}"
          }
        },
        {
          "name": "Remarcar agendamento",
          "event": [
            {
              "listen": "prerequest",
              "script": {
                "type": "text/javascript",
                "exec": [
                  "// nova data, tambem no futuro: gera o evento RESCHEDULED",
                  "const d = new Date(Date.now() + 45 * 24 * 60 * 60 * 1000);",
                  "pm.collectionVariables.set('appointmentDate', d.toISOString().slice(0, 19));"
                ]
              }
            },
            {
              "listen": "test",
              "script": {
                "type": "text/javascript",
                "exec": [
                  "pm.test('status 200', () => pm.response.to.have.status(200));",
                  "pm.test('data mudou', () => pm.expect(pm.response.json().appointmentDate).to.include(pm.collectionVariables.get('appointmentDate').slice(0, 10)));"
                ]
              }
            }
          ],
          "request": {
            "method": "PUT",
            "header": [
              {
                "key": "Content-Type",
                "value": "application/json"
              }
            ],
            "url": "{{appointmentUrl}}/appointments/{{appointmentId}}",
            "body": {
              "mode": "raw",
              "raw": "{\n  \"patientId\": {{patientId}},\n  \"doctorId\": {{doctorId}},\n  \"appointmentDate\": \"{{appointmentDate}}\",\n  \"description\": \"Consulta remarcada - cardiologia\"\n}",
              "options": {
                "raw": {
                  "language": "json"
                }
              }
            }
          }
        },
        {
          "name": "Concluir atendimento",
          "event": [
            {
              "listen": "test",
              "script": {
                "type": "text/javascript",
                "exec": [
                  "pm.test('status 200', () => pm.response.to.have.status(200));",
                  "pm.test('ficou COMPLETED', () => pm.expect(pm.response.json().status).to.eql('COMPLETED'));"
                ]
              }
            }
          ],
          "request": {
            "method": "PATCH",
            "header": [
              {
                "key": "Content-Type",
                "value": "application/json"
              }
            ],
            "url": "{{appointmentUrl}}/appointments/{{appointmentId}}/status",
            "body": {
              "mode": "raw",
              "raw": "{\n  \"status\": \"COMPLETED\"\n}",
              "options": {
                "raw": {
                  "language": "json"
                }
              }
            }
          }
        }
      ]
    },
    {
      "name": "3. Histórico (GraphQL)",
      "item": [
        {
          "name": "patientHistory — estado atual de cada consulta",
          "event": [
            {
              "listen": "test",
              "script": {
                "type": "text/javascript",
                "exec": [
                  "pm.test('status 200', () => pm.response.to.have.status(200));",
                  "const b = pm.response.json();",
                  "pm.test('sem erros GraphQL', () => pm.expect(b.errors, JSON.stringify(b.errors)).to.be.undefined);",
                  "const id = String(pm.collectionVariables.get('appointmentId'));",
                  "pm.test('contem o agendamento criado', () => pm.expect(b.data.patientHistory.map(r => r.appointmentId)).to.include(id));"
                ]
              }
            }
          ],
          "request": {
            "method": "POST",
            "header": [
              {
                "key": "Content-Type",
                "value": "application/json"
              }
            ],
            "url": "{{historyUrl}}/graphql",
            "body": {
              "mode": "graphql",
              "graphql": {
                "query": "query ($pid: ID!) {\n  patientHistory(patientId: $pid) {\n    appointmentId\n    eventStatus\n    appointmentDate\n    description\n  }\n}",
                "variables": "{\n  \"pid\": \"{{patientId}}\"\n}"
              }
            },
            "description": "Ultimo evento de cada consulta do paciente."
          }
        },
        {
          "name": "appointmentTimeline — trilha da consulta",
          "event": [
            {
              "listen": "test",
              "script": {
                "type": "text/javascript",
                "exec": [
                  "pm.test('status 200', () => pm.response.to.have.status(200));",
                  "const b = pm.response.json();",
                  "pm.test('sem erros GraphQL', () => pm.expect(b.errors, JSON.stringify(b.errors)).to.be.undefined);",
                  "pm.test('comeca pelo SCHEDULED', () => pm.expect(b.data.appointmentTimeline[0].eventStatus).to.eql('SCHEDULED'));"
                ]
              }
            }
          ],
          "request": {
            "method": "POST",
            "header": [
              {
                "key": "Content-Type",
                "value": "application/json"
              }
            ],
            "url": "{{historyUrl}}/graphql",
            "body": {
              "mode": "graphql",
              "graphql": {
                "query": "query ($aid: ID!) {\n  appointmentTimeline(appointmentId: $aid) {\n    eventStatus\n    appointmentDate\n    occurredAt\n  }\n}",
                "variables": "{\n  \"aid\": \"{{appointmentId}}\"\n}"
              }
            },
            "description": "Todos os eventos da consulta, do mais antigo ao mais recente."
          }
        }
      ]
    },
    {
      "name": "4. Notificações",
      "item": [
        {
          "name": "Notificações do paciente",
          "event": [
            {
              "listen": "test",
              "script": {
                "type": "text/javascript",
                "exec": [
                  "pm.test('status 200', () => pm.response.to.have.status(200));",
                  "const b = pm.response.json();",
                  "const id = Number(pm.collectionVariables.get('appointmentId'));",
                  "pm.test('notificacao do agendamento enviada', () => pm.expect(b.some(n => n.appointmentId === id && n.status === 'SENT')).to.be.true);"
                ]
              }
            }
          ],
          "request": {
            "method": "GET",
            "header": [],
            "url": "{{notificationUrl}}/notifications/patient/{{patientId}}",
            "description": "Uma notificacao por evento do agendamento, todas com status SENT."
          }
        }
      ]
    }
  ]
}
```

Run: `python3 -c "import json; json.load(open('docs/postman/tech-challenge-grupo65.postman_collection.json')); print('json valido')"`
Expected: `json valido`.

- [ ] **Step 2: Rodar no newman contra um projeto isolado**

Com os containers `grupo65` parados (`make down`, se estiverem de pé):

```bash
P=g65task7
docker compose -p $P up -d --build
for i in $(seq 1 72); do
  n=$(docker ps --filter label=com.docker.compose.project=$P --format '{{.Status}}' | grep -c "(healthy)")
  [ "$n" = 9 ] && break; sleep 5
done
echo "healthy: $n/9"
npx --yes newman@6 run docs/postman/tech-challenge-grupo65.postman_collection.json --delay-request 500 \
  | grep -E "│\s+(requests|assertions)"
npx --yes newman@6 run docs/postman/tech-challenge-grupo65.postman_collection.json --delay-request 500 \
  | grep -E "│\s+(requests|assertions)"
docker compose -p $P down -v --rmi local
```

Expected: `healthy: 9/9`; nas duas execuções, `requests` com `8` executadas e `0` falhas e `assertions` com `0` falhas (a segunda prova que a collection roda de novo sobre um banco já usado).

- [ ] **Step 3: Commit**

```bash
git add docs/postman/tech-challenge-grupo65.postman_collection.json
git commit -m "docs: collection do Postman no roteiro login, appointment, historico e notificacoes" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019ij1SRidK2Nd5YVMagZCXb"
```

---
### Task 8: README da raiz e do auth-service

**Files:**
- Modify: `README.md` (reescrito)
- Modify: `auth-service/README.md` (reescrito)

**Interfaces:**
- Consumes: comandos, portas, usuários, collection e smoke das Tasks 1–7.

- [ ] **Step 1: Reescrever o `README.md` da raiz**

A seção "Adicionando um novo serviço" é mantida do README atual com dois ajustes: a próxima faixa
de portas passa a ser `8084`/`5436` e o `billing-app` do exemplo ganha a chave pública. Todo o
resto é substituído. Conteúdo completo:

````markdown
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
Get-ChildItem -Path . -Filter .env.example -Recurse -Depth 1 | ForEach-Object {
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

Os mesmos passos com `curl` (no PowerShell, use `curl.exe` e troque `\` por `` ` `` no fim das linhas):

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
`patientHistory(patientId: "4")` devolve só o estado atual de cada consulta. O GraphiQL fica em
<http://localhost:8081/graphiql>.

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
(no PowerShell, `${PWD}` no lugar de `$(pwd)`) e suba o ambiente de novo. Tokens emitidos antes deixam de valer.

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
````

Depois do bloco acima, acrescentar ao fim do arquivo a seção `## Adicionando um novo serviço`
copiada do README atual (do título até o fim do arquivo), com estas substituições:

| De | Para |
|---|---|
| `Usa a próxima faixa de portas livre (8080/5433, 8081/5432 e 8082/5434 já estão tomadas):` | `Usa a próxima faixa de portas livre (8080 a 8083 e 5432 a 5435 já estão tomadas):` |
| `DB_PORT=5435` | `DB_PORT=5436` |
| `SERVER_PORT=8083` | `SERVER_PORT=8084` |
| `      RABBITMQ_PORT: 5672\n    ports: ["${SERVER_PORT}:${SERVER_PORT}"]` | `      RABBITMQ_PORT: 5672\n      JWT_PUBLIC_KEY: file:/keys/app.sub   # chave publica do JWT\n    volumes: ["../.jwt-keys:/keys:ro"]\n    ports: ["${SERVER_PORT}:${SERVER_PORT}"]` |
| `      rabbitmq:              { condition: service_healthy }` | `      rabbitmq:              { condition: service_healthy }\n      jwt-keys:              { condition: service_completed_successfully }` |
| `Não esqueça de atualizar a tabela de **Portas** deste README` | `Não esqueça de atualizar a tabela de serviços do início deste README` |

Para montar o arquivo sem digitar a seção mantida:

```bash
git show HEAD:README.md | sed -n '/^## Adicionando um novo serviço/,$p' > /tmp/readme-novo-servico.md
```

e aplicar as substituições da tabela em `/tmp/readme-novo-servico.md` antes de concatenar ao novo `README.md`.

Run: `grep -cE "8083|jwt-keys|Authorization: Bearer|maria.santos" README.md; grep -c "generate-jwt-keys\|ainda não exigem autenticação\|sete containers" README.md`
Expected: primeiro número maior que 10; segundo `0`.

- [ ] **Step 2: Reescrever o `auth-service/README.md`**

Conteúdo completo:

````markdown
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
````

Run: `grep -c "generate-jwt-keys\|JDK 25\|Java 25\|5434" auth-service/README.md`
Expected: `0`.

- [ ] **Step 3: Commit**

```bash
git add README.md auth-service/README.md
git commit -m "docs: README com auth-service, JWT, Windows e roteiro de teste" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019ij1SRidK2Nd5YVMagZCXb"
```

---
### Task 9: Validação final num clone limpo

**Files:**
- Nenhum arquivo alterado; esta task comprova os critérios de aceite da spec.

**Interfaces:**
- Consumes: branch com as Tasks 1–8.

Cada chamada de shell pode abrir um shell novo: todo comando de Docker abaixo leva `-p <projeto>` explícito e o `cd` para o clone no mesmo comando.

- [ ] **Step 1: Suítes num clone sem `.jwt-keys` (critérios 1 e 9)**

```bash
REPO=$(git rev-parse --show-toplevel)
V=$(mktemp -d)/clone
git clone -q --branch feat/auth-orquestracao "$REPO" "$V"
cd "$V" && test ! -e .jwt-keys && echo "sem .jwt-keys"
for s in auth-service appointment-service history-service notification-service; do
  echo "== $s"; (cd "$V/$s" && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -B test) 2>&1 \
    | grep -E "Tests run:.*Skipped: [0-9]+$|BUILD" | tail -2
done
cd "$V" && git ls-files | grep -E '(^|/)\.env$|\.jwt-keys|app\.(key|sub)$'
echo "$V" > /tmp/g65final-clone-path
```

Expected: `sem .jwt-keys`; auth `25`, appointment `13`, history `60`, notification `26`, todos `Failures: 0, Errors: 0` e `BUILD SUCCESS`; a última listagem mostra só os quatro pares `*/src/test/resources/jwt-test/app.key` e `app.sub` (8 linhas).

- [ ] **Step 2: Subir o clone num projeto isolado (critérios 2 e 3)**

Com os containers `grupo65` parados preservando volumes (`make down` no repositório, se estiverem de pé):

```bash
V=$(cat /tmp/g65final-clone-path); P=g65final
cd "$V" && make setup && docker compose -p $P up -d --build
for i in $(seq 1 72); do
  n=$(docker ps --filter label=com.docker.compose.project=$P --format '{{.Status}}' | grep -c "(healthy)")
  [ "$n" = 9 ] && break; sleep 5
done
echo "healthy: $n/9"
cd "$V" && docker compose -p $P ps -a --format '{{.Service}} {{.State}} {{.ExitCode}}' | grep jwt-keys
cd "$V" && sha1sum .jwt-keys/app.sub > /tmp/g65final.sha
cd "$V" && docker compose -p $P down && docker compose -p $P up -d
for i in $(seq 1 72); do
  n=$(docker ps --filter label=com.docker.compose.project=$P --format '{{.Status}}' | grep -c "(healthy)")
  [ "$n" = 9 ] && break; sleep 5
done
echo "healthy depois de reiniciar: $n/9"
sha1sum -c /tmp/g65final.sha
```

Expected: `make setup` com 6 linhas `criado` (raiz, infra e os quatro serviços); `healthy: 9/9`; `jwt-keys exited 0`; `healthy depois de reiniciar: 9/9`; `.jwt-keys/app.sub: OK`.

- [ ] **Step 3: Smoke, collection e regras de acesso (critérios 4, 5 e 6)**

```bash
V=$(cat /tmp/g65final-clone-path)
cd "$V" && make smoke
cd "$V" && npx --yes newman@6 run docs/postman/tech-challenge-grupo65.postman_collection.json --delay-request 500 \
  | grep -E "│\s+(requests|assertions)"
for u in http://localhost:8080/appointments http://localhost:8082/notifications/patient/4; do
  echo "$u sem token: $(curl -s -o /dev/null -w '%{http_code}' $u)"
done
echo "graphql sem token: $(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:8081/graphql -H 'content-type: application/json' -d '{"query":"{ patientHistory(patientId: 4) { appointmentId } }"}')"
PTOKEN=$(curl -s -X POST http://localhost:8083/auth/login -u lucas.oliveira@hospital.com:Paciente@123 \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
echo "paciente 4 nas proprias: $(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $PTOKEN" http://localhost:8082/notifications/patient/4)"
echo "paciente 4 nas do paciente 3: $(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $PTOKEN" http://localhost:8082/notifications/patient/3)"
```

Expected: `SUCESSO: login -> appointment -> RabbitMQ -> history e notification funcionando.`; newman com `0` falhas em `requests` (8) e `assertions`; `401` nas três chamadas sem token; `200` para as próprias notificações e `403` para as do paciente 3.

- [ ] **Step 4: Limpar o projeto isolado e o clone**

```bash
V=$(cat /tmp/g65final-clone-path)
cd "$V" && docker compose -p g65final ps -a && docker compose -p g65final down -v --rmi local
docker run --rm -v "$V:/w" alpine:3.22 rm -rf /w/.jwt-keys
rm -rf "$(dirname "$V")" /tmp/g65final-clone-path /tmp/g65final.sha
docker ps -aq --filter label=com.docker.compose.project=g65final | wc -l
```

Expected: `0`.

- [ ] **Step 5: Checkout com CRLF, como no Windows (critério 7)**

```bash
REPO=$(git rev-parse --show-toplevel)
W=$(mktemp -d)/crlf
git -c core.autocrlf=true clone -q --branch feat/auth-orquestracao "$REPO" "$W"
cd "$W" && for f in .env.example auth-service/.env.example auth-service/mvnw scripts/smoke-test.sh Makefile; do
  printf "%-28s %s\n" "$f" "$(grep -c $'\r' "$f")"
done
cd "$W" && make setup >/dev/null && docker compose -p g65crlf config --quiet && echo "config ok"
cd "$W" && docker compose -p g65crlf build auth-app appointment-app 2>&1 | tail -3
cd "$W" && docker compose -p g65crlf down --rmi local 2>/dev/null; rm -rf "$(dirname "$W")"
```

Expected: `0` linhas com CR em cada um dos cinco arquivos; `config ok`; build das duas imagens sem erro (sem `./mvnw: not found`).

- [ ] **Step 6: Bloco PowerShell do README (critério 8)**

```bash
if command -v pwsh >/dev/null; then
  T=$(mktemp -d); mkdir -p "$T/svc"
  printf 'A=1\n' > "$T/.env.example"; printf 'B=1\n' > "$T/svc/.env.example"; printf 'mantido\n' > "$T/svc/.env"
  pwsh -NoProfile -Command "Set-Location '$T'; Get-ChildItem -Path . -Filter .env.example -Recurse -Depth 1 | ForEach-Object { \$destino = \$_.FullName -replace '\.example\$', ''; if (-not (Test-Path \$destino)) { Copy-Item \$_.FullName \$destino; \"criado \$destino\" } }"
  echo "svc/.env: $(cat "$T/svc/.env")"; rm -rf "$T"
else
  echo "pwsh indisponivel: criterio 8 nao verificado nesta maquina"
fi
```

Expected: com `pwsh`, uma única linha `criado .../.env` (a da raiz) e `svc/.env: mantido`. Sem `pwsh`, registrar que o critério 8 não foi verificado.

- [ ] **Step 7: Restaurar o ambiente do mantenedor**

```bash
cd "$(git rev-parse --show-toplevel)" && make setup && make build
for i in $(seq 1 72); do
  n=$(docker ps --filter label=com.docker.compose.project=grupo65 --format '{{.Status}}' | grep -c "(healthy)")
  [ "$n" = 9 ] && break; sleep 5
done
echo "grupo65 healthy: $n/9"
```

Expected: `grupo65 healthy: 9/9`.

- [ ] **Step 8: Confirmar com o mantenedor antes de publicar**

Push e PR são ações externas. Apresentar o resumo (tasks, contagens de testes, smoke, collection, CRLF e PowerShell) e **aguardar confirmação explícita** antes de `git push -u origin feat/auth-orquestracao` e de abrir o PR para a `main`. A descrição do PR termina com:

```
🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_019ij1SRidK2Nd5YVMagZCXb
```
