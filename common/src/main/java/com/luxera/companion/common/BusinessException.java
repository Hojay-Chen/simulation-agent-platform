package com.luxera.companion.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** 带状态码的业务异常,提示信息不泄露内部细节 */
@Getter
public class BusinessException extends RuntimeException {
    private final HttpStatus status;
    private final String hint;

    public BusinessException(String message) {
        this(HttpStatus.BAD_REQUEST, message, null);
    }

    public BusinessException(HttpStatus status, String message, String hint) {
        super(message);
        this.status = status;
        this.hint = hint;
    }

    public static BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message, null);
    }
}
