package com.qrpay.common.error;

/** Business exception carrying a stable {@link ErrorCode}; mapped to an HTTP response by {@link GlobalExceptionHandler}. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code) {
        this(code, code.defaultMessage());
    }

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public static ApiException notFound(String resource) {
        return new ApiException(ErrorCode.NOT_FOUND, resource + " not found");
    }

    public ErrorCode code() {
        return code;
    }
}
