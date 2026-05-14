package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.annotation.SfsColumn;
import com.choisk.sfs.orm.annotation.SfsEntity;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue;
import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.orm.exception.SfsLazyInitializationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LazyInterceptorTest {

    @SfsEntity
    static class Tgt {
        @SfsId
        @SfsGeneratedValue(strategy = SfsGeneratedValue.GenerationType.IDENTITY)
        Long id;

        @SfsColumn
        String name;

        public String getName() {
            return name;
        }
    }

    /**
     * 테스트 1: PersistenceContext가 닫혀 있을 때 SfsLazyInitializationException 발생
     */
    @Test
    void intercept_throws_when_persistenceContext_closed() throws Throwable {
        // 닫힌 컨텍스트 생성
        var ctx = new PersistenceContext();
        ctx.close();
        var interceptor = new LazyInterceptor(Tgt.class, 1L, ctx);

        // 메서드 호출 시 lazy init 예외 발생해야 함
        assertThatThrownBy(() -> interceptor.intercept(
                Tgt.class.getMethod("getName"), new Object[0]))
            .isInstanceOf(SfsLazyInitializationException.class);
    }

    /**
     * 테스트 2: 첫 호출 시 PersistenceContext의 identityMap에서 target 초기화 후 메서드 위임
     */
    @Test
    void intercept_initializes_target_from_context_on_first_call() throws Throwable {
        var ctx = new PersistenceContext();
        Tgt original = new Tgt();
        original.id = 5L;
        original.name = "alice";
        ctx.putEntity(new EntityKey(Tgt.class, 5L), original);

        var interceptor = new LazyInterceptor(Tgt.class, 5L, ctx);
        // 첫 intercept 호출 — target null 상태에서 identityMap 조회 후 채워짐
        Object result = interceptor.intercept(
            Tgt.class.getMethod("getName"), new Object[0]);

        assertThat(result).isEqualTo("alice");
    }

    /**
     * 테스트 3: 같은 PK에 대한 두 인터셉터가 같은 identityMap을 공유해 동일 인스턴스 반환
     * 학습 정점 ③ — lazy proxy의 identity 보장: 1 PK = 1 인스턴스
     */
    @Test
    void intercept_preserves_identity_via_persistenceContext() throws Throwable {
        var ctx = new PersistenceContext();
        Tgt original = new Tgt();
        original.id = 7L;
        original.name = "bob";
        ctx.putEntity(new EntityKey(Tgt.class, 7L), original);

        var interceptor1 = new LazyInterceptor(Tgt.class, 7L, ctx);
        var interceptor2 = new LazyInterceptor(Tgt.class, 7L, ctx);

        // 두 인터셉터 모두 같은 PersistenceContext의 identityMap에서 같은 인스턴스를 가져옴
        interceptor1.intercept(Tgt.class.getMethod("getName"), new Object[0]);
        interceptor2.intercept(Tgt.class.getMethod("getName"), new Object[0]);

        // identity 보장: 같은 PK → isSameAs
        assertThat(interceptor1.target()).isSameAs(interceptor2.target());
    }
}
