package com.choisk.sfs.orm.support;

/**
 * lazy 프록시가 DB fallback 로드가 필요할 때 호출하는 로더 콜백 인터페이스.
 *
 * <p>구현체는 {@link SfsEntityManagerFactory} 생성자에서 람다로 주입된다.
 * context는 loader 내부에서 null로 고정 — fallback 로드 시 lazy 관계를 재귀 채우지 않음
 * (순환 참조 회피, 학습 정점 ③ 설계 신호).
 */
@FunctionalInterface
public interface LazyTargetLoader {

    /**
     * targetClass 엔티티의 pk 행을 DB에서 로드한다.
     *
     * @param targetClass 로드할 엔티티 클래스
     * @param pk          로드할 기본 키 값
     * @return 로드된 엔티티 인스턴스, 없으면 null
     */
    Object load(Class<?> targetClass, Object pk);
}
