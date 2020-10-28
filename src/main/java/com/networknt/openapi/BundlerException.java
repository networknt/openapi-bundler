package com.networknt.openapi;

public class BundlerException extends RuntimeException {

    public BundlerException(String message) {

        super(message);
    }

    public BundlerException(
            String message,
            Throwable cause) {

        super(
                message,
                cause);
    }
}
