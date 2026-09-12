package br.com.tech.challenge.securitygateway.dto;

import br.com.tech.challenge.securitygateway.entity.RoleEnum;

public record UserResponse(Long id, String name, String email, RoleEnum role) {
}
