package br.com.tech.challenge.notificationservice.repository;

import br.com.tech.challenge.notificationservice.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByPatientId(Long patientId);

    Optional<Notification> findByEventId(UUID eventId);
}
