package br.com.tech.challenge.authservice.service;

import br.com.tech.challenge.authservice.entity.Role;
import br.com.tech.challenge.authservice.entity.RoleEnum;
import br.com.tech.challenge.authservice.entity.User;
import br.com.tech.challenge.authservice.entity.UserAuthenticated;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.*;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtServiceTest {

    @Mock
    private JwtEncoder jwtEncoder;

    @Mock
    private Authentication authentication;

    @Test
    void shouldGenerateTokenWithExpectedClaimsAndFifteenMinutesValidity() {
        User user = new User(10L, "Dr. Lucas", "doctor@hospital.com", "encoded-password",
                new Role(1L, RoleEnum.DOCTOR));
        UserAuthenticated principal = new UserAuthenticated(user);
        ArgumentCaptor<JwtEncoderParameters> parametersCaptor =
                ArgumentCaptor.forClass(JwtEncoderParameters.class);

        doReturn(List.of(new SimpleGrantedAuthority("ROLE_DOCTOR")))
                .when(authentication).getAuthorities();
        when(authentication.getName()).thenReturn("doctor@hospital.com");
        when(authentication.getPrincipal()).thenReturn(principal);
        when(jwtEncoder.encode(any(JwtEncoderParameters.class))).thenReturn(jwt("generated-token"));

        String token = new JwtService(jwtEncoder).generateToken(authentication);

        assertThat(token).isEqualTo("generated-token");
        verify(jwtEncoder).encode(parametersCaptor.capture());

        JwtClaimsSet claims = parametersCaptor.getValue().getClaims();
        assertThat(claims.getClaim("iss").toString()).isEqualTo("auth-service");
        assertThat(claims.getSubject()).isEqualTo("doctor@hospital.com");
        assertThat(claims.getClaimAsString("scope")).isEqualTo("ROLE_DOCTOR");
        Object userId = claims.getClaim("user_id");
        assertThat(userId).isEqualTo(10L);
        assertThat(claims.getExpiresAt()).isEqualTo(
                claims.getIssuedAt().plus(Duration.ofMinutes(15)));
    }

    @Test
    void shouldJoinAllAuthenticationAuthoritiesInScope() {
        doReturn(List.of(
                new SimpleGrantedAuthority("ROLE_DOCTOR"),
                new SimpleGrantedAuthority("ROLE_NURSE")))
                .when(authentication).getAuthorities();
        when(authentication.getName()).thenReturn("user@hospital.com");
        when(authentication.getPrincipal()).thenReturn("user@hospital.com");
        when(jwtEncoder.encode(any(JwtEncoderParameters.class))).thenReturn(jwt("token"));

        ArgumentCaptor<JwtEncoderParameters> parametersCaptor =
                ArgumentCaptor.forClass(JwtEncoderParameters.class);
        new JwtService(jwtEncoder).generateToken(authentication);

        verify(jwtEncoder).encode(parametersCaptor.capture());
        assertThat(parametersCaptor.getValue().getClaims().getClaimAsString("scope"))
                .isEqualTo("ROLE_DOCTOR ROLE_NURSE");
        Object userId = parametersCaptor.getValue().getClaims().getClaim("user_id");
        assertThat(userId).isNull();
    }

    @Test
    void shouldAcceptTokenBeforeExpiration() throws Exception {
        KeyPair keyPair = keyPair();
        JwtEncoder encoder = encoder(keyPair);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((java.security.interfaces.RSAPublicKey) keyPair.getPublic()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("auth-service"));

        Instant now = Instant.now();
        String token = encoder.encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                .issuer("auth-service")
                .subject("user@hospital.com")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .build())).getTokenValue();

        assertThat(decoder.decode(token).getSubject()).isEqualTo("user@hospital.com");
    }

    @Test
    void shouldRejectExpiredToken() throws Exception {
        KeyPair keyPair = keyPair();
        JwtEncoder encoder = encoder(keyPair);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((java.security.interfaces.RSAPublicKey) keyPair.getPublic()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("auth-service"));

        Instant now = Instant.now();
        String token = encoder.encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                .issuer("auth-service")
                .subject("user@hospital.com")
                .issuedAt(now.minusSeconds(120))
                .expiresAt(now.minusSeconds(60))
                .build())).getTokenValue();

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    private Jwt jwt(String token) {
        return Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .claim("scope", "ROLE_DOCTOR")
                .build();
    }

    private KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private JwtEncoder encoder(KeyPair keyPair) {
        RSAKey jwk = new RSAKey.Builder((java.security.interfaces.RSAPublicKey) keyPair.getPublic())
                .privateKey((java.security.interfaces.RSAPrivateKey) keyPair.getPrivate())
                .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
    }
}
