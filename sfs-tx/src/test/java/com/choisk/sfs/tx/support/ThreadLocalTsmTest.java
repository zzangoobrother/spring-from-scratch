package com.choisk.sfs.tx.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThreadLocalTsmTest {

    private final ThreadLocalTsm tsm = new ThreadLocalTsm();

    @AfterEach
    void clearThreadState() {
        tsm.clearAll();
    }

    @Test
    void bindResourceIsRetrievable() {
        Object key = new Object();
        Object value = "conn#1";

        tsm.bindResource(key, value);

        assertThat(tsm.getResource(key)).isEqualTo("conn#1");
    }

    @Test
    void unbindResourceReturnsAndRemoves() {
        Object key = new Object();
        tsm.bindResource(key, "conn#1");

        Object removed = tsm.unbindResource(key);

        assertThat(removed).isEqualTo("conn#1");
        assertThat(tsm.getResource(key)).isNull();
    }

    @Test
    void getResourceReturnsNullWhenNotBound() {
        assertThat(tsm.getResource(new Object())).isNull();
    }

    @Test
    void resourcesAreIsolatedAcrossThreads() throws Exception {
        Object key = new Object();
        tsm.bindResource(key, "main-thread-conn");

        Object[] otherThreadValue = new Object[1];
        Thread other = new Thread(() -> otherThreadValue[0] = tsm.getResource(key));
        other.start();
        other.join();

        assertThat(otherThreadValue[0]).isNull();
        assertThat(tsm.getResource(key)).isEqualTo("main-thread-conn");
    }
}
