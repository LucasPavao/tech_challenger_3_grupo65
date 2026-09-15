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
