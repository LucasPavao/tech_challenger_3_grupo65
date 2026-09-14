package br.com.tech.challenge.authservice.service;

import br.com.tech.challenge.authservice.dto.UserRequest;
import br.com.tech.challenge.authservice.dto.UserResponse;
import br.com.tech.challenge.authservice.dto.UserUpdateRequest;
import br.com.tech.challenge.authservice.entity.Role;
import br.com.tech.challenge.authservice.entity.RoleEnum;
import br.com.tech.challenge.authservice.entity.User;
import br.com.tech.challenge.authservice.exception.EmailAlreadyExistsException;
import br.com.tech.challenge.authservice.exception.RoleNotFoundException;
import br.com.tech.challenge.authservice.exception.UserNotFoundException;
import br.com.tech.challenge.authservice.repository.RoleRepository;
import br.com.tech.challenge.authservice.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    private Role doctorRole;

    @BeforeEach
    void setUp() {
        doctorRole = role(3L, RoleEnum.DOCTOR);
    }

    @Test
    void shouldCreateUserWithEncodedPasswordAndRole() {
        UserRequest request = new UserRequest(
                "Dr. João Silva", "joao@hospital.com", "password", RoleEnum.DOCTOR);

        when(userRepository.findByEmail(request.email())).thenReturn(Optional.empty());
        when(roleRepository.findByName(RoleEnum.DOCTOR)).thenReturn(Optional.of(doctorRole));
        when(passwordEncoder.encode("password")).thenReturn("encoded-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(10L);
            return saved;
        });

        UserResponse response = userService.create(request);

        assertThat(response).isEqualTo(new UserResponse(
                10L, "Dr. João Silva", "joao@hospital.com", RoleEnum.DOCTOR));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPassword()).isEqualTo("encoded-password");
        assertThat(userCaptor.getValue().getRole()).isEqualTo(doctorRole);
    }

    @Test
    void shouldRejectCreationWhenEmailAlreadyExists() {
        User existing = user(10L, "existing@hospital.com", doctorRole);
        UserRequest request = new UserRequest(
                "Outro usuário", "existing@hospital.com", "password", RoleEnum.DOCTOR);

        when(userRepository.findByEmail(request.email())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> userService.create(request))
                .isInstanceOf(EmailAlreadyExistsException.class)
                .hasMessage("Email already exists: existing@hospital.com");

        verify(userRepository, never()).save(any(User.class));
        verifyNoInteractions(roleRepository, passwordEncoder);
    }

    @Test
    void shouldUpdateUserAndEncodeNewPassword() {
        User existing = user(10L, "old@hospital.com", role(2L, RoleEnum.PATIENT));
        UserUpdateRequest request = new UserUpdateRequest(
                "Dr. João Silva", "joao@hospital.com", "new-password", RoleEnum.DOCTOR);

        when(userRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(userRepository.findByEmail(request.email())).thenReturn(Optional.empty());
        when(roleRepository.findByName(RoleEnum.DOCTOR)).thenReturn(Optional.of(doctorRole));
        when(passwordEncoder.encode("new-password")).thenReturn("encoded-new-password");
        when(userRepository.save(existing)).thenReturn(existing);

        UserResponse response = userService.update(10L, request);

        assertThat(response).isEqualTo(new UserResponse(
                10L, "Dr. João Silva", "joao@hospital.com", RoleEnum.DOCTOR));
        assertThat(existing.getPassword()).isEqualTo("encoded-new-password");
        assertThat(existing.getRole()).isEqualTo(doctorRole);
        verify(userRepository).save(existing);
    }

    @Test
    void shouldAllowUpdatingUserKeepingOwnEmail() {
        User existing = user(10L, "same@hospital.com", doctorRole);
        UserUpdateRequest request = new UserUpdateRequest(
                "Updated name", "same@hospital.com", null, RoleEnum.DOCTOR);

        when(userRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(userRepository.findByEmail(request.email())).thenReturn(Optional.of(existing));
        when(roleRepository.findByName(RoleEnum.DOCTOR)).thenReturn(Optional.of(doctorRole));
        when(userRepository.save(existing)).thenReturn(existing);

        userService.update(10L, request);

        assertThat(existing.getName()).isEqualTo("Updated name");
        assertThat(existing.getPassword()).isEqualTo("encoded-password");
        verify(userRepository).save(existing);
    }

    @Test
    void shouldPreservePasswordWhenUpdatePasswordIsBlank() {
        User existing = user(10L, "same@hospital.com", doctorRole);
        existing.setPassword("old-encoded-password");
        UserUpdateRequest request = new UserUpdateRequest(
                "Updated name", "same@hospital.com", "   ", RoleEnum.DOCTOR);

        when(userRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(userRepository.findByEmail(request.email())).thenReturn(Optional.of(existing));
        when(roleRepository.findByName(RoleEnum.DOCTOR)).thenReturn(Optional.of(doctorRole));
        when(userRepository.save(existing)).thenReturn(existing);

        userService.update(10L, request);

        assertThat(existing.getPassword()).isEqualTo("old-encoded-password");
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void shouldRejectUpdateWhenEmailBelongsToAnotherUser() {
        User current = user(10L, "current@hospital.com", doctorRole);
        User other = user(20L, "other@hospital.com", doctorRole);
        UserUpdateRequest request = new UserUpdateRequest(
                "Updated name", "other@hospital.com", "new-password", RoleEnum.DOCTOR);

        when(userRepository.findById(10L)).thenReturn(Optional.of(current));
        when(userRepository.findByEmail(request.email())).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> userService.update(10L, request))
                .isInstanceOf(EmailAlreadyExistsException.class);

        assertThat(current.getName()).isEqualTo("User 10");
        verify(userRepository, never()).save(any(User.class));
        verifyNoInteractions(roleRepository, passwordEncoder);
    }

    @Test
    void shouldRejectCreationWhenRoleDoesNotExist() {
        UserRequest request = new UserRequest(
                "Novo usuário", "new@hospital.com", "password", RoleEnum.NURSE);

        when(userRepository.findByEmail(request.email())).thenReturn(Optional.empty());
        when(roleRepository.findByName(RoleEnum.NURSE)).thenReturn(Optional.empty());
        when(passwordEncoder.encode("password")).thenReturn("encoded-password");

        assertThatThrownBy(() -> userService.create(request))
                .isInstanceOf(RoleNotFoundException.class)
                .hasMessage("Role not found: NURSE");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void shouldFindAllUsers() {
        User first = user(10L, "first@hospital.com", doctorRole);
        User second = user(20L, "second@hospital.com", role(4L, RoleEnum.NURSE));
        when(userRepository.findAll()).thenReturn(List.of(first, second));

        List<UserResponse> response = userService.findAll();

        assertThat(response).containsExactly(
                new UserResponse(10L, "User 10", "first@hospital.com", RoleEnum.DOCTOR),
                new UserResponse(20L, "User 20", "second@hospital.com", RoleEnum.NURSE));
    }

    @Test
    void shouldFindUserById() {
        User existing = user(10L, "user@hospital.com", doctorRole);
        when(userRepository.findById(10L)).thenReturn(Optional.of(existing));

        assertThat(userService.findById(10L)).isEqualTo(
                new UserResponse(10L, "User 10", "user@hospital.com", RoleEnum.DOCTOR));
    }

    @Test
    void shouldRejectFindByIdWhenUserDoesNotExist() {
        when(userRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.findById(404L))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessage("User not found: 404");
    }

    @Test
    void shouldDeleteExistingUser() {
        User existing = user(10L, "user@hospital.com", doctorRole);
        when(userRepository.findById(10L)).thenReturn(Optional.of(existing));

        userService.delete(10L);

        verify(userRepository).delete(existing);
    }

    @Test
    void shouldRejectDeleteWhenUserDoesNotExist() {
        when(userRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.delete(404L))
                .isInstanceOf(UserNotFoundException.class);

        verify(userRepository, never()).delete(any(User.class));
    }

    private User user(Long id, String email, Role role) {
        User user = new User();
        user.setId(id);
        user.setName("User " + id);
        user.setEmail(email);
        user.setPassword("encoded-password");
        user.setRole(role);
        return user;
    }

    private Role role(Long id, RoleEnum name) {
        Role role = new Role();
        role.setId(id);
        role.setName(name);
        return role;
    }
}
