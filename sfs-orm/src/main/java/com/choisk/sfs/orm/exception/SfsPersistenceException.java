package com.choisk.sfs.orm.exception;

public class SfsPersistenceException extends RuntimeException {
    public SfsPersistenceException(String message) {
        super(message);
    }

    public SfsPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
