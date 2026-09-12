package br.com.tech.challenge.appointmentservice.controller;

import br.com.tech.challenge.appointmentservice.dto.AppointmentRequest;
import br.com.tech.challenge.appointmentservice.dto.AppointmentResponse;
import br.com.tech.challenge.appointmentservice.dto.AppointmentStatusRequest;
import br.com.tech.challenge.appointmentservice.service.AppointmentService;
import br.com.tech.challenge.appointmentservice.config.AppointmentAuthorization;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

@RestController
@RequestMapping("/appointments")
@RequiredArgsConstructor
public class AppointmentController {

    private final AppointmentService service;
    private final AppointmentAuthorization authorization;

    @PostMapping
    @PreAuthorize("hasRole('NURSE')")
    @ResponseStatus(HttpStatus.CREATED)
    public AppointmentResponse create(@RequestBody @Valid AppointmentRequest request) {
        return service.create(request);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('DOCTOR', 'NURSE', 'PATIENT')")
    public AppointmentResponse findById(@PathVariable Long id, Authentication authentication) {
        AppointmentResponse appointment = service.findById(id);
        authorization.checkPatientAccess(authentication, appointment.patientId());
        return appointment;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('DOCTOR', 'NURSE')")
    public List<AppointmentResponse> findAll() {
        return service.findAll();
    }

    @GetMapping("/patient/{patientId}")
    @PreAuthorize("hasAnyRole('DOCTOR', 'NURSE', 'PATIENT')")
    public List<AppointmentResponse> findByPatient(@PathVariable Long patientId, Authentication authentication) {
        authorization.checkPatientAccess(authentication, patientId);
        return service.findByPatient(patientId);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('DOCTOR', 'NURSE')")
    public AppointmentResponse update(
            @PathVariable Long id,
            @RequestBody @Valid AppointmentRequest request) {
        return service.update(id, request);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('DOCTOR', 'NURSE')")
    public AppointmentResponse updateStatus(
            @PathVariable Long id,
            @RequestBody @Valid AppointmentStatusRequest request) {
        return service.updateStatus(id, request);
    }
}
