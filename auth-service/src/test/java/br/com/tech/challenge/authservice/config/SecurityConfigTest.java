package br.com.tech.challenge.authservice.config;

import br.com.tech.challenge.authservice.entity.Role;
import br.com.tech.challenge.authservice.entity.RoleEnum;
import br.com.tech.challenge.authservice.entity.User;
import br.com.tech.challenge.authservice.repository.UserRepository;
import br.com.tech.challenge.authservice.service.AuthenticationService;
import br.com.tech.challenge.authservice.service.UserDetailServiceImpl;
import br.com.tech.challenge.authservice.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test exercising the real {@link SecurityConfig} filter chain, without hitting the
 * database: {@link UserService} and {@link UserRepository} are mocked, {@link JwtDecoder} is
 * mocked so bearer tokens never need real signature validation here.
 */
@WebMvcTest(controllers = {
        br.com.tech.challenge.authservice.controller.UserController.class,
        br.com.tech.challenge.authservice.controller.AuthenticationController.class
})
@Import({SecurityConfig.class, UserDetailServiceImpl.class})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private AuthenticationService authenticationService;

    @Test
    void shouldRejectUsersEndpointWithoutCredentials() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectUsersEndpointWithNonAdminBearerToken() throws Exception {
        when(jwtDecoder.decode("patient-token")).thenReturn(jwt("ROLE_PATIENT"));

        mockMvc.perform(get("/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer patient-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldAllowUsersEndpointWithAdminBearerToken() throws Exception {
        when(jwtDecoder.decode("admin-token")).thenReturn(jwt("ROLE_ADMIN"));
        when(userService.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldRejectLoginWithBadBasicAuthCredentials() throws Exception {
        Role role = new Role(1L, RoleEnum.PATIENT);
        User user = new User(1L, "Patient", "patient@hospital.com", "encoded-password", role);
        when(userRepository.findByEmail("patient@hospital.com")).thenReturn(Optional.of(user));

        mockMvc.perform(post("/auth/login")
                        .header(HttpHeaders.AUTHORIZATION, basicAuthHeader("patient@hospital.com", "wrong-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldLoginSuccessfullyWithValidBasicAuthCredentials() throws Exception {
        Role role = new Role(1L, RoleEnum.PATIENT);
        User user = new User(1L, "Patient", "patient@hospital.com",
                passwordEncoder.encode("correct-password"), role);
        when(userRepository.findByEmail("patient@hospital.com")).thenReturn(Optional.of(user));
        when(authenticationService.authenticate(any(Authentication.class))).thenReturn("signed-jwt-token");

        mockMvc.perform(post("/auth/login")
                        .header(HttpHeaders.AUTHORIZATION, basicAuthHeader("patient@hospital.com", "correct-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value("signed-jwt-token"));
    }

    private Jwt jwt(String scope) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("scope", scope)
                .subject("user@hospital.com")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    private String basicAuthHeader(String username, String password) {
        String credentials = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
    }
}
