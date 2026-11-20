package com.qrpay.wallet;

import com.qrpay.common.idempotency.IdempotencyService;
import com.qrpay.common.web.PageResponse;
import com.qrpay.security.AuthUser;
import com.qrpay.wallet.WalletDtos.LedgerEntryView;
import com.qrpay.wallet.WalletDtos.TopUpRequest;
import com.qrpay.wallet.WalletDtos.WalletView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Wallets")
@RestController
@RequestMapping("/api/v1/wallets/me")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final IdempotencyService idempotency;

    @Operation(summary = "Balance of the caller's wallet (customer wallet or merchant settlement wallet)")
    @GetMapping
    public WalletView myWallet(@AuthenticationPrincipal AuthUser user) {
        return walletService.myWallet(user);
    }

    @Operation(summary = "Paginated ledger (statement) of the caller's wallet, newest first")
    @GetMapping("/ledger")
    public PageResponse<LedgerEntryView> ledger(@AuthenticationPrincipal AuthUser user,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return walletService.myLedger(user, page, size);
    }

    @Operation(summary = "Demo top-up from the system settlement account")
    @PostMapping("/top-up")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<WalletView> topUp(@AuthenticationPrincipal AuthUser user,
                                            @RequestHeader("Idempotency-Key") String idempotencyKey,
                                            @Valid @RequestBody TopUpRequest request) {
        var result = idempotency.execute("top-up", user.userId(), idempotencyKey, request, WalletView.class,
                () -> walletService.topUpMine(user, request.amount()));
        return ResponseEntity.ok().header("Idempotent-Replayed", String.valueOf(result.replayed())).body(result.body());
    }
}
