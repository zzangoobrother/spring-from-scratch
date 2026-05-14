package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.annotation.SfsColumn;
import com.choisk.sfs.orm.annotation.SfsEntity;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue.GenerationType;
import com.choisk.sfs.orm.annotation.SfsId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LazyProxyFactory 단위 테스트.
 * <p>
 * 검증 대상:
 * 1. byte-buddy 서브클래싱 — 반환 인스턴스가 targetClass의 서브클래스임
 * 2. enhanced 클래스 캐싱 — 같은 targetClass는 같은 enhanced Class 객체를 재사용
 * 3. getId 비-인터셉트 — PK getter는 인터셉터를 거치지 않고 hidden field에서 즉시 반환
 *    (closed PersistenceContext로도 예외 없이 동작)
 */
class LazyProxyFactoryTest {

    @SfsEntity
    static class LazyTarget {
        @SfsId
        @SfsGeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @SfsColumn
        String name;

        public Long getId() { return id; }
        public String getName() { return name; }
    }

    @Test
    void createProxy_returns_subclass_instance_of_target() {
        var factory = new LazyProxyFactory();
        Object proxy = factory.createProxy(LazyTarget.class, 1L, new PersistenceContext());
        assertThat(proxy).isInstanceOf(LazyTarget.class);
        assertThat(proxy.getClass()).isNotEqualTo(LazyTarget.class); // 서브클래스
    }

    @Test
    void createProxy_caches_enhanced_class_per_target() {
        var factory = new LazyProxyFactory();
        Object p1 = factory.createProxy(LazyTarget.class, 1L, new PersistenceContext());
        Object p2 = factory.createProxy(LazyTarget.class, 2L, new PersistenceContext());
        assertThat(p1.getClass()).isSameAs(p2.getClass()); // 같은 enhanced 클래스
    }

    @Test
    void getId_does_not_trigger_initialization() {
        var factory = new LazyProxyFactory();
        var ctx = new PersistenceContext();
        ctx.close(); // closed 영속성 컨텍스트 — getId 외 호출 시 예외

        Object proxy = factory.createProxy(LazyTarget.class, 42L, ctx);
        // getId()는 hidden field에서 즉시 반환 (인터셉트 안 됨)
        Long id = ((LazyTarget) proxy).getId();
        assertThat(id).isEqualTo(42L);
    }
}
