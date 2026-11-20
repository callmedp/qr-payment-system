package com.qrpay.qr;

import com.qrpay.common.ratelimit.RateLimited;
import com.qrpay.qr.QrDtos.CreateDynamicQrRequest;
import com.qrpay.qr.QrDtos.QrView;
import com.qrpay.qr.QrDtos.ResolveQrRequest;
import com.qrpay.qr.QrDtos.ResolvedQr;
import com.qrpay.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "QR codes")
@RestController
@RequestMapping("/api/v1/qr-codes")
@RequiredArgsConstructor
public class QrController {

    private final QrService qrService;

    @Operation(summary = "Create a single-use dynamic QR for a fixed amount (merchant)")
    @PostMapping("/dynamic")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MERCHANT')")
    @RateLimited("qr-create")
    public QrView createDynamic(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody CreateDynamicQrRequest request) {
        return qrService.createDynamic(user, request);
    }

    @Operation(summary = "Get (or lazily create) the merchant's reusable static QR")
    @GetMapping("/static")
    @PreAuthorize("hasRole('MERCHANT')")
    public QrView staticQr(@AuthenticationPrincipal AuthUser user) {
        return qrService.getOrCreateStatic(user);
    }

    @Operation(summary = "Recent dynamic QR codes of the merchant (no images)")
    @GetMapping
    @PreAuthorize("hasRole('MERCHANT')")
    public List<QrView> recent(@AuthenticationPrincipal AuthUser user) {
        return qrService.recentDynamic(user);
    }

    @Operation(summary = "QR status - polling fallback when the SSE stream is unavailable")
    @GetMapping("/{qrId}")
    @PreAuthorize("hasRole('MERCHANT')")
    public QrView get(@AuthenticationPrincipal AuthUser user, @PathVariable UUID qrId) {
        return qrService.get(user, qrId);
    }

    @Operation(summary = "Resolve a scanned payload into payee + amount (customer)")
    @PostMapping("/resolve")
    @PreAuthorize("hasRole('CUSTOMER')")
    @RateLimited("qr-resolve")
    public ResolvedQr resolve(@Valid @RequestBody ResolveQrRequest request) {
        return qrService.resolve(request.payload());
    }
}
