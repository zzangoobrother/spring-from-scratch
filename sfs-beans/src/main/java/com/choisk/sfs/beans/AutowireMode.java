package com.choisk.sfs.beans;

/**
 * 의존성 주입 모드. Spring 원본의 int 상수를 enum으로 안전하게 재현.
 */
public enum AutowireMode {
    NO,
    BY_NAME,
    BY_TYPE,
    CONSTRUCTOR
}
