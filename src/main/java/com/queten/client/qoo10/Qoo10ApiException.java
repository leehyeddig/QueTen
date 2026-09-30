package com.queten.client.qoo10;

import java.io.IOException;

public class Qoo10ApiException extends IOException {

    private static final long serialVersionUID = 1L;

    private final String apiMethod;
    private final String resultCode;

    public Qoo10ApiException(String apiMethod, String resultCode, String message) {
        super("Qoo10 " + apiMethod + " 실패: code=" + resultCode + ", message=" + message);
        this.apiMethod = apiMethod;
        this.resultCode = resultCode;
    }

    public String getApiMethod() {
        return apiMethod;
    }

    public String getResultCode() {
        return resultCode;
    }
}
