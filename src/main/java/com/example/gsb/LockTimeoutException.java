package com.example.gsb;

/** Thrown when a range lock cannot be acquired within the requested timeout. */
public final class LockTimeoutException extends RuntimeException {
    public LockTimeoutException(String message) {
        super(message);
    }
}
