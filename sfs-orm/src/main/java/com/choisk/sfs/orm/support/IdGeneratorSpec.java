package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.annotation.SfsGeneratedValue.GenerationType;

/**
 * 식별자 생성 전략 명세 — analyzer가 어노테이션을 분석해 만드는 불변 spec record.
 * (실제 IdentifierGenerator 인스턴스 생성은 E1에서 EntityManagerFactory가 담당)
 */
public record IdGeneratorSpec(GenerationType strategy, String sequenceName) { }
