package com.choisk.sfs.tx.annotation;

/**
 * 트랜잭션 전파 동작. 본 phase는 REQUIRED + REQUIRES_NEW 두 종류만 박제.
 *
 * <p>SUPPORTS, NOT_SUPPORTED, MANDATORY, NEVER, NESTED는 의도된 비목표
 * (spec § 7 한계). 후속 phase 회수 후보.
 */
public enum Propagation {
    /**
     * 현재 트랜잭션이 있으면 join, 없으면 새로 시작. Spring 본가 default.
     */
    REQUIRED,

    /**
     * 항상 새 트랜잭션 시작. 현재 트랜잭션이 있으면 suspend.
     * suspend/resume 메커니즘이 transaction synchronization의 본질.
     */
    REQUIRES_NEW
}
