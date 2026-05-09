package com.choisk.sfs.orm.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersistenceContextTest {

    @Test
    void putEntity_then_get_returns_same_instance() {
        var ctx = new PersistenceContext();
        var key = new EntityKey(String.class, 1L);
        ctx.putEntity(key, "alice");
        assertThat(ctx.getEntity(key)).isSameAs("alice");
    }

    @Test
    void getEntity_returns_null_when_missing() {
        var ctx = new PersistenceContext();
        assertThat(ctx.getEntity(new EntityKey(String.class, 99L))).isNull();
    }

    @Test
    void putSnapshot_captures_field_values() {
        var ctx = new PersistenceContext();
        var key = new EntityKey(String.class, 1L);
        ctx.putSnapshot(key, new Object[]{"alice", "a@x.com"});
        assertThat(ctx.getSnapshot(key)).containsExactly("alice", "a@x.com");
    }

    @Test
    void enqueueAction_appends_to_queue_in_order() {
        var ctx = new PersistenceContext();
        ctx.enqueueAction(new InsertAction("a", null));
        ctx.enqueueAction(new InsertAction("b", null));
        assertThat(ctx.actionQueue()).hasSize(2);
        assertThat(ctx.actionQueue().get(0).entity()).isEqualTo("a");
    }

    @Test
    void close_marks_closed_and_clears_state() {
        var ctx = new PersistenceContext();
        ctx.putEntity(new EntityKey(String.class, 1L), "x");
        ctx.enqueueAction(new InsertAction("x", null));
        ctx.close();
        assertThat(ctx.isClosed()).isTrue();
        assertThat(ctx.getEntity(new EntityKey(String.class, 1L))).isNull();
        assertThat(ctx.actionQueue()).isEmpty();
    }

    @Test
    void contains_returns_true_for_managed_entity() {
        var ctx = new PersistenceContext();
        var key = new EntityKey(String.class, 1L);
        ctx.putEntity(key, "alice");
        assertThat(ctx.contains(key)).isTrue();
        assertThat(ctx.contains(new EntityKey(String.class, 99L))).isFalse();
    }
}
