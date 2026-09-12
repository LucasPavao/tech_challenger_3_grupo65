package br.com.tech.challenge.authservice.dto;

import br.com.tech.challenge.authservice.entity.RoleEnum;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UserUpdateRequest(
        @NotBlank @Size(max = 50) String name,
        @NotBlank @Email @Size(max = 100) String email,
        @Size(min = 10) String password,
        @NotNull RoleEnum role
) {
}
