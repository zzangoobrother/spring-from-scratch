package com.choisk.sfs.tx;

/**
 * 모든 트랜잭션 예외의 루트. sealed로 hierarchy 제한 — sfs-core의
 * {@code BeansException} 패턴과 동일.
 */
public sealed class TransactionException extends RuntimeException
        permits TransactionException.CommitFailedException,
                TransactionException.RollbackFailedException,
                TransactionException.NoTransactionManagerException {

    public TransactionException(String message) { super(message); }

    public TransactionException(String message, Throwable cause) { super(message, cause); }

    public static final class CommitFailedException extends TransactionException {
        public CommitFailedException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class RollbackFailedException extends TransactionException {
        public RollbackFailedException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class NoTransactionManagerException extends TransactionException {
        public NoTransactionManagerException(String message) { super(message); }
    }
}
