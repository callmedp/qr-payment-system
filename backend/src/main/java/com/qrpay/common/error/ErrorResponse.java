package com.qrpay.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorResponse(String code, String message, List<FieldViolation> details, String requestId, Instant timestamp) {

    public record FieldViolation(String field, String message) {}
}
