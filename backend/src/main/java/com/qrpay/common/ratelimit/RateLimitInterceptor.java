package com.qrpay.common.ratelimit;

import com.qrpay.common.error.ApiException;
import com.qrpay.common.error.ErrorCode;
import com.qrpay.security.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RedisRateLimiter limiter;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RateLimited rule = method.getMethodAnnotation(RateLimited.class);
        if (rule == null) {
            return true;
        }
        RedisRateLimiter.Decision decision = limiter.tryConsume(rule.value(), subject(request));
        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        if (!decision.allowed()) {
            response.setHeader("Retry-After", String.valueOf(Math.max(1, decision.retryAfterMs() / 1000)));
            throw new ApiException(ErrorCode.RATE_LIMITED);
        }
        return true;
    }

    /** Authenticated calls are limited per user, anonymous ones (login/register) per client IP. */
    private String subject(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUser user) {
            return "u:" + user.userId();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded != null ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
        return "ip:" + ip;
    }
}
