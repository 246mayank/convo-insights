package dev.mayank.convoinsights.ingest;

/** The record can never be processed (bad JSON, failed validation). Not retried; goes to the DLT. */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }

    public InvalidEventException(String message) {
        super(message);
    }
}
