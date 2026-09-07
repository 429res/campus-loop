package edu.campusloop.web.auth.dto;
import jakarta.validation.constraints.*;
public record LoginRequest(@NotBlank @Size(max=64) String username, @NotBlank @Size(max=72) String password) {}
