package br.com.tech.challenge.authservice.dto;

import br.com.tech.challenge.authservice.entity.RoleEnum;

public record UserResponse(Long id, String name, String email, RoleEnum role) {
}
