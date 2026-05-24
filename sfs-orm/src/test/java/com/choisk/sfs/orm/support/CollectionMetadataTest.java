package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.annotation.SfsCascadeType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionMetadataTest {

    private CollectionMetadata withCascade(SfsCascadeType... types) {
        return new CollectionMetadata(null, Object.class, "fk", "", Set.of(types), false);
    }

    @Test
    void cascadesPersist_PERSIST_포함_시_true() {
        assertThat(withCascade(SfsCascadeType.PERSIST).cascadesPersist()).isTrue();
        assertThat(withCascade(SfsCascadeType.PERSIST).cascadesRemove()).isFalse();
    }

    @Test
    void cascadesRemove_REMOVE_포함_시_true() {
        assertThat(withCascade(SfsCascadeType.REMOVE).cascadesRemove()).isTrue();
        assertThat(withCascade(SfsCascadeType.REMOVE).cascadesPersist()).isFalse();
    }

    @Test
    void cascadesAll_ALL은_persist와_remove_모두_true() {
        CollectionMetadata all = withCascade(SfsCascadeType.ALL);
        assertThat(all.cascadesPersist()).isTrue();
        assertThat(all.cascadesRemove()).isTrue();
    }

    @Test
    void cascade_없으면_모두_false() {
        assertThat(withCascade().cascadesPersist()).isFalse();
        assertThat(withCascade().cascadesRemove()).isFalse();
    }
}
