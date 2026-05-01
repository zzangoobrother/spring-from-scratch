package com.choisk.sfs.tx;

import com.choisk.sfs.tx.annotation.Propagation;

/**
 * 트랜잭션 시작 시 의도. {@link com.choisk.sfs.tx.annotation.Transactional}에서
 * 추출되어 TM에 전달된다.
 *
 * <p>{@code isolation}은 시그니처만 (spec § 7 한계).
 */
public record TransactionDefinition(Propagation propagation, int isolation) {

    public static TransactionDefinition required() {
        return new TransactionDefinition(Propagation.REQUIRED, -1);
    }

    public static TransactionDefinition requiresNew() {
        return new TransactionDefinition(Propagation.REQUIRES_NEW, -1);
    }
}
