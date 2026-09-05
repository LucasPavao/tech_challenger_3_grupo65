package br.com.tech.challenge.notificationservice.controller;

import br.com.tech.challenge.notificationservice.model.Notification;
import br.com.tech.challenge.notificationservice.service.NotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(
            NotificationService notificationService) {

        this.notificationService = notificationService;
    }

    @GetMapping("/patient/{patientId}")
    public List<Notification> findByPatient(
            @PathVariable Long patientId) {

        return notificationService
                .findByPatientId(patientId);
    }
}
