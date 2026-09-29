package com.oori.minebot;

public final class MineBotCommandException extends IllegalArgumentException {
    private final String code;

    public MineBotCommandException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return this.code;
    }
}
