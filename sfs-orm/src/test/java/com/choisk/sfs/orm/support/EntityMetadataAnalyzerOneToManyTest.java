package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.annotation.SfsColumn;
import com.choisk.sfs.orm.annotation.SfsEntity;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue;
import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.orm.annotation.SfsOneToMany;
import com.choisk.sfs.orm.exception.SfsEntityMappingException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.choisk.sfs.orm.annotation.SfsGeneratedValue.GenerationType.IDENTITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityMetadataAnalyzerOneToManyTest {

    private final EntityMetadataAnalyzer analyzer = new EntityMetadataAnalyzer();

    @Test
    void analyze_OneToMany_성공_시_oneToManies에_CollectionMetadata_등재() {
        EntityMetadata md = analyzer.analyze(ParentEntity.class);

        assertThat(md.oneToManies()).hasSize(1);
        CollectionMetadata col = md.oneToManies().get(0);
        assertThat(col.field().getName()).isEqualTo("children");
        assertThat(col.elementType()).isEqualTo(ChildEntity.class);
        assertThat(col.joinColumnName()).isEqualTo("parent_id");
    }

    @Test
    void analyze_OneToMany_List_외_타입은_fail_fast() {
        assertThatThrownBy(() -> analyzer.analyze(SetCollectionEntity.class))
                .isInstanceOf(SfsEntityMappingException.class)
                .hasMessageContaining("must be List<T>");
    }

    @Test
    void analyze_OneToMany_raw_List는_fail_fast() {
        assertThatThrownBy(() -> analyzer.analyze(RawListEntity.class))
                .isInstanceOf(SfsEntityMappingException.class)
                .hasMessageContaining("generic type parameter");
    }

    @Test
    void analyze_OneToMany_elementType이_비엔티티이면_fail_fast() {
        assertThatThrownBy(() -> analyzer.analyze(NonEntityElementEntity.class))
                .isInstanceOf(SfsEntityMappingException.class)
                .hasMessageContaining("not annotated with @SfsEntity");
    }

    // ─── 테스트 fixture 엔티티 ──────────────────────────────────────────

    @SfsEntity(name = "parent")
    static class ParentEntity {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY)
        Long id;
        @SfsColumn String name;
        @SfsOneToMany(joinColumn = "parent_id")
        List<ChildEntity> children;
    }

    @SfsEntity(name = "child")
    static class ChildEntity {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY)
        Long id;
    }

    @SfsEntity(name = "set_owner")
    static class SetCollectionEntity {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY)
        Long id;
        @SfsOneToMany(joinColumn = "owner_id")
        Set<ChildEntity> tags;
    }

    @SfsEntity(name = "raw_owner")
    static class RawListEntity {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY)
        Long id;
        @SuppressWarnings("rawtypes")
        @SfsOneToMany(joinColumn = "owner_id")
        List orders;
    }

    @SfsEntity(name = "nonentity_owner")
    static class NonEntityElementEntity {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY)
        Long id;
        @SfsOneToMany(joinColumn = "owner_id")
        List<String> tags;
    }
}
