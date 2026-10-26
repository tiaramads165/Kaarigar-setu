package com.kaarigarsetu.console;

/** A failed call to the backend, carrying the HTTP status and the server's short error code (never internals). */
public class ConsoleException extends RuntimeException {
    private final int status;
    private final String code;

    public ConsoleException(int status, String code) {
        super(describe(status, code));
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    private static String describe(int status, String code) {
        return switch (code == null ? "" : code) {
            case "invalid_credentials" -> "Wrong username or password.";
            case "invalid_otp" -> "That code is wrong, expired or locked.";
            case "rate_limited" -> "Too many attempts. Wait a few minutes and try again.";
            case "unauthenticated" -> "Your session has ended. Please sign in again.";
            case "forbidden" -> "Not allowed for this account (for example, you cannot approve your own proposal).";
            case "conflict" -> "That item was already decided, or a proposal for it is already waiting.";
            case "not_found" -> "That item no longer exists (it may have been decided already).";
            case "network" -> "Cannot reach the server. Check the address and your connection.";
            default -> "Request failed (" + (status == 0 ? "no response" : "HTTP " + status) + ").";
        };
    }
}
