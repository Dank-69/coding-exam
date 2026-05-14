package com.oncall.common.exception;

public record ApiError(
        String error,
        String code,
        String timestamp
) {
}
