package br.com.tech.challenge.notificationservice.controller;

import br.com.tech.challenge.notificationservice.model.Notification;
import br.com.tech.challenge.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/patient/{patientId}")
    public List<Notification> findByPatient(
            @PathVariable Long patientId) {

        return notificationService
                .findByPatientId(patientId);
    }
}
