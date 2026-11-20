package com.qrpay.notification;

import com.qrpay.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "Notifications")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final SseHub hub;

    /**
     * Clients read this with fetch() + a ReadableStream so the JWT travels in the Authorization header.
     * (Browser EventSource cannot set headers; putting the token in the query string would leak it into
     * proxy and access logs.)
     */
    @Operation(summary = "Server-Sent Events stream of payment updates for the caller")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal AuthUser user, HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no"); // disable proxy buffering (nginx)
        response.setHeader("Cache-Control", "no-cache");
        return hub.register(user.userId());
    }
}
