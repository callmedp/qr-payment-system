package com.qrpay.auth;

import com.qrpay.auth.AuthDtos.AuthResponse;
import com.qrpay.auth.AuthDtos.LoginRequest;
import com.qrpay.auth.AuthDtos.RegisterCustomerRequest;
import com.qrpay.auth.AuthDtos.RegisterMerchantRequest;
import com.qrpay.auth.AuthDtos.UserView;
import com.qrpay.common.error.ApiException;
import com.qrpay.common.error.ErrorCode;
import com.qrpay.config.QrPayProperties;
import com.qrpay.merchant.Merchant;
import com.qrpay.merchant.MerchantService;
import com.qrpay.security.AuthUser;
import com.qrpay.security.JwtService;
import com.qrpay.wallet.Wallet;
import com.qrpay.wallet.WalletService;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final WalletService walletService;
    private final MerchantService merchantService;
    private final QrPayProperties props;

    /** Creates the user, opens a PIN-protected wallet and credits the demo signup bonus - atomically. */
    @Transactional
    public AuthResponse registerCustomer(RegisterCustomerRequest request) {
        UserAccount user = createUser(request.email(), request.password(), request.fullName(), Role.CUSTOMER);
        Wallet wallet = walletService.openUserWallet(user.getId(), request.pin());
        long bonus = props.demo().signupBonus();
        if (bonus > 0) {
            walletService.topUp(wallet.getId(), bonus, "Signup bonus");
        }
        return issueToken(user, null);
    }

    @Transactional
    public AuthResponse registerMerchant(RegisterMerchantRequest request) {
        UserAccount user = createUser(request.email(), request.password(), request.fullName(), Role.MERCHANT);
        Merchant merchant = merchantService.onboard(user, request.businessName(), request.categoryCode());
        walletService.openMerchantWallet(merchant.getId());
        return issueToken(user, merchant.getId());
    }

    //@Transactional(readOnly = true) -> bcrypt will take more time, which will make connection starve
    public AuthResponse login(LoginRequest request) {
        UserAccount user = users.findByEmailIgnoreCase(request.email().trim())
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS));
        UUID merchantId = user.getRole() == Role.MERCHANT ? merchantService.getByUserId(user.getId()).getId() : null;
        return issueToken(user, merchantId);
    }

    @Transactional(readOnly = true)
    public UserView me(AuthUser principal) {
        UserAccount user = users.findById(principal.userId()).orElseThrow(() -> ApiException.notFound("User"));
        return toView(user, principal.merchantId());
    }

    private UserAccount createUser(String email, String rawPassword, String fullName, Role role) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        }
        return users.save(UserAccount.create(normalizedEmail, passwordEncoder.encode(rawPassword), fullName.trim(), role));
    }

    private AuthResponse issueToken(UserAccount user, UUID merchantId) {
        var principal = new AuthUser(user.getId(), user.getEmail(), user.getRole(), merchantId);
        JwtService.IssuedToken token = jwtService.issue(principal);
        return new AuthResponse(token.token(), "Bearer", token.expiresAt(), toView(user, merchantId));
    }

    private static UserView toView(UserAccount user, UUID merchantId) {
        return new UserView(user.getId(), user.getEmail(), user.getFullName(), user.getRole(), merchantId);
    }
}
