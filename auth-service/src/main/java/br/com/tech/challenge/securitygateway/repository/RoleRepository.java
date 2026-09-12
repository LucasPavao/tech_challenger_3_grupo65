package br.com.tech.challenge.securitygateway.repository;

import br.com.tech.challenge.securitygateway.entity.Role;
import br.com.tech.challenge.securitygateway.entity.RoleEnum;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByName(RoleEnum name);
}
