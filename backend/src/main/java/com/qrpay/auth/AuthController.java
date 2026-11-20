package com.qrpay.auth;

import com.qrpay.auth.AuthDtos.AuthResponse;
import com.qrpay.auth.AuthDtos.LoginRequest;
import com.qrpay.auth.AuthDtos.RegisterCustomerRequest;
import com.qrpay.auth.AuthDtos.RegisterMerchantRequest;
import com.qrpay.auth.AuthDtos.UserView;
import com.qrpay.common.ratelimit.RateLimited;
import com.qrpay.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Register a customer (payer) and open a PIN-protected wallet")
    @PostMapping("/register/customer")
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimited("auth")
    public AuthResponse registerCustomer(@Valid @RequestBody RegisterCustomerRequest request) {
        return authService.registerCustomer(request);
    }

    @Operation(summary = "Onboard a merchant (payee) with a settlement wallet")
    @PostMapping("/register/merchant")
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimited("auth")
    public AuthResponse registerMerchant(@Valid @RequestBody RegisterMerchantRequest request) {
        return authService.registerMerchant(request);
    }

    @Operation(summary = "Exchange email/password for a JWT access token")
    @PostMapping("/login")
    @RateLimited("auth")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @Operation(summary = "Current user profile")
    @GetMapping("/me")
    public UserView me(@AuthenticationPrincipal AuthUser user) {
        return authService.me(user);
    }
}
