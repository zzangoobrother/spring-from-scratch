package com.choisk.sfs.orm.exception;

public class SfsTransactionRequiredException extends SfsPersistenceException {
    public SfsTransactionRequiredException() {
        super("No transaction in progress — annotate the calling method with @Transactional");
    }
}
