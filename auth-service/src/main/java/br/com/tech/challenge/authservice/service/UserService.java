package br.com.tech.challenge.authservice.service;

import br.com.tech.challenge.authservice.dto.UserRequest;
import br.com.tech.challenge.authservice.dto.UserResponse;
import br.com.tech.challenge.authservice.dto.UserUpdateRequest;
import br.com.tech.challenge.authservice.entity.Role;
import br.com.tech.challenge.authservice.entity.RoleEnum;
import br.com.tech.challenge.authservice.entity.User;
import br.com.tech.challenge.authservice.exception.RoleNotFoundException;
import br.com.tech.challenge.authservice.exception.EmailAlreadyExistsException;
import br.com.tech.challenge.authservice.exception.UserNotFoundException;
import br.com.tech.challenge.authservice.repository.RoleRepository;
import br.com.tech.challenge.authservice.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository,
                       RoleRepository roleRepository,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> findAll() {
        return userRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public UserResponse findById(Long id) {
        return toResponse(findUser(id));
    }

    public UserResponse create(UserRequest request) {
        validateEmailAvailable(request.email(), null);
        User user = new User();
        apply(user, request.name(), request.email(), request.password(), request.role());
        return toResponse(userRepository.save(user));
    }

    public UserResponse update(Long id, UserUpdateRequest request) {
        User user = findUser(id);
        validateEmailAvailable(request.email(), id);
        apply(user, request.name(), request.email(), request.password(), request.role());
        return toResponse(userRepository.save(user));
    }

    public void delete(Long id) {
        User user = findUser(id);
        userRepository.delete(user);
    }

    private void apply(User user, String name, String email, String password, RoleEnum roleName) {
        user.setName(name);
        user.setEmail(email);
        if (password != null && !password.isBlank()) {
            user.setPassword(passwordEncoder.encode(password));
        }
        Role role = roleRepository.findByName(roleName)
                .orElseThrow(() -> new RoleNotFoundException("Role not found: " + roleName));
        user.setRole(role);
    }

    private void validateEmailAvailable(String email, Long currentUserId) {
        userRepository.findByEmail(email)
                .filter(existingUser -> !existingUser.getId().equals(currentUserId))
                .ifPresent(existingUser -> {
                    throw new EmailAlreadyExistsException(email);
                });
    }

    private User findUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + id));
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole().getName());
    }
}
