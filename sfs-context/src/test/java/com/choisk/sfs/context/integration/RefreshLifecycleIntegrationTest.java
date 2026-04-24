package com.choisk.sfs.context.integration;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.context.support.GenericApplicationContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshLifecycleIntegrationTest {

    static class Foo {}

    @Test
    void emptyContextRefreshSucceeds() {
        var ctx = new GenericApplicationContext();
        ctx.refresh();  // BFPP/BPP 컬렉션 비어있어도 5/6단계 정상 통과
        assertThat(ctx.isActive()).isTrue();
        ctx.close();
    }

    @Test
    void refreshTwiceThrowsAndKeepsState() {
        var ctx = new GenericApplicationContext();
        ctx.registerBeanDefinition("foo", new BeanDefinition(Foo.class));
        ctx.refresh();
        assertThatThrownBy(ctx::refresh).isInstanceOf(IllegalStateException.class);
        assertThat(ctx.isActive()).isTrue();  // 두 번째 refresh 실패가 첫 성공 상태를 깨면 안 됨
        ctx.close();
    }
}
