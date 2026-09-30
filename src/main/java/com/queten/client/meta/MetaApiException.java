package com.queten.client.meta;

import java.io.IOException;

public class MetaApiException extends IOException {

    private static final long serialVersionUID = 1L;

    public MetaApiException(String message) {
        super(message);
    }

    public MetaApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
