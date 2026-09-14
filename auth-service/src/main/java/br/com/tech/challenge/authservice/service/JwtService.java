package br.com.tech.challenge.authservice.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import br.com.tech.challenge.authservice.entity.UserAuthenticated;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Collectors;

@Component
public class JwtService {

    private final JwtEncoder jwtEncoder;

    private static final Duration TOKEN_VALIDITY = Duration.ofMinutes(15);

    public JwtService(JwtEncoder jwtEncoder) {
        this.jwtEncoder = jwtEncoder;
    }

    public String generateToken(Authentication authentication) {
        Instant now = Instant.now();

        String scopes = authentication.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(" "));

        var claims = JwtClaimsSet.builder()
                .issuer("auth-service")
                .issuedAt(now)
                .expiresAt(now.plus(TOKEN_VALIDITY))
                .subject(authentication.getName())
                .claim("scope", scopes)
                .build();

        if (authentication.getPrincipal() instanceof UserAuthenticated user) {
            claims = JwtClaimsSet.from(claims)
                    .claim("user_id", user.getUserId())
                    .build();
        }

        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }
}
