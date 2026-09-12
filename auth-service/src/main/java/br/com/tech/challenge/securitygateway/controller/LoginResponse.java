package br.com.tech.challenge.securitygateway.controller;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LoginResponse(@JsonProperty("access_token") String accessToken) {
}
