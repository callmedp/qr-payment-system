package com.qrpay.security;

import com.qrpay.auth.Role;
import java.util.UUID;

/** Authenticated principal reconstructed from the JWT on every request (no session, no DB hit). */
public record AuthUser(UUID userId, String email, Role role, UUID merchantId) {

    public boolean isMerchant() {
        return role == Role.MERCHANT;
    }
}
