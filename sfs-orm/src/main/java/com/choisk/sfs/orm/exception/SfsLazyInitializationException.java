package com.choisk.sfs.orm.exception;

public class SfsLazyInitializationException extends SfsPersistenceException {
    public SfsLazyInitializationException(String entityKey) {
        super("Cannot initialize lazy proxy after persistence context closed: " + entityKey);
    }
}
