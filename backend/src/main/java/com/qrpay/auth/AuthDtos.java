package com.qrpay.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterCustomerRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Pattern(regexp = "\\d{4,6}", message = "PIN must be 4 to 6 digits") String pin) {}

    public record RegisterMerchantRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 120) String businessName,
            @NotBlank @Pattern(regexp = "\\d{4}", message = "Merchant category code must be 4 digits") String categoryCode) {}

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}

    public record UserView(UUID id, String email, String fullName, Role role, UUID merchantId) {}

    public record AuthResponse(String accessToken, String tokenType, Instant expiresAt, UserView user) {}
}
