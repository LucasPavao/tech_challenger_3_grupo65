package br.com.tech.challenge.authservice.entity;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import static org.assertj.core.api.Assertions.assertThat;

class UserAuthenticatedTest {

    @Test
    void shouldExposeUserIdentityAndRoleAsSpringAuthority() {
        Role role = new Role();
        role.setName(RoleEnum.PATIENT);

        User user = new User();
        user.setId(42L);
        user.setEmail("patient@hospital.com");
        user.setPassword("encoded-password");
        user.setRole(role);

        UserAuthenticated authenticated = new UserAuthenticated(user);

        assertThat(authenticated.getUserId()).isEqualTo(42L);
        assertThat(authenticated.getUsername()).isEqualTo("patient@hospital.com");
        assertThat(authenticated.getPassword()).isEqualTo("encoded-password");
        assertThat(authenticated.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_PATIENT");
    }
}
