package com.choisk.sfs.orm.annotation;

/**
 * cascade 전파 종류 — @SfsOneToMany.cascade()에서 사용.
 *
 * <p>MP-3 범위: PERSIST(persist 전파), REMOVE(remove 전파), ALL(둘 다).
 * MERGE/DETACH/REFRESH는 본 phase 범위 밖(MP-1/향후).
 */
public enum SfsCascadeType { PERSIST, REMOVE, ALL }
