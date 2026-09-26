package com.example.gsb.rangelock;

/**
 * Thrown when a range lock (or lock upgrade) cannot be granted within the
 * requested timeout, or the waiting thread is interrupted. A failed attempt
 * never leaves any partially held lock behind.
 */
public class LockTimeoutException extends RuntimeException {

    public LockTimeoutException(String message) {
        super(message);
    }

    public LockTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
