package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.annotation.SfsCascadeType;
import com.choisk.sfs.orm.annotation.SfsColumn;
import com.choisk.sfs.orm.annotation.SfsEntity;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue;
import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.orm.annotation.SfsJoinColumn;
import com.choisk.sfs.orm.annotation.SfsManyToOne;
import com.choisk.sfs.orm.annotation.SfsOneToMany;
import com.choisk.sfs.orm.exception.SfsEntityMappingException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.choisk.sfs.orm.annotation.SfsGeneratedValue.GenerationType.IDENTITY;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityMetadataAnalyzerBidirectionalTest {

    private final EntityMetadataAnalyzer analyzer = new EntityMetadataAnalyzer();

    @Test
    void mappedBy와_joinColumn_둘_다_지정_시_fail_fast() {
        assertThatThrownBy(() -> analyzer.analyze(BothSpecified.class))
                .isInstanceOf(SfsEntityMappingException.class)
                .hasMessageContaining("정확히 하나");
    }

    @Test
    void mappedBy와_joinColumn_둘_다_누락_시_fail_fast() {
        assertThatThrownBy(() -> analyzer.analyze(NeitherSpecified.class))
                .isInstanceOf(SfsEntityMappingException.class)
                .hasMessageContaining("정확히 하나");
    }

    // ─── fixture ───
    @SfsEntity(name = "child_x")
    static class ChildX {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY) Long id;
        @SfsManyToOne @SfsJoinColumn(name = "owner_id") OwnerBoth owner;
    }

    @SfsEntity(name = "owner_both")
    static class BothSpecified {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY) Long id;
        @SfsColumn String name;
        @SfsOneToMany(joinColumn = "owner_id", mappedBy = "owner")
        List<ChildX> children;
    }

    @SfsEntity(name = "owner_neither")
    static class NeitherSpecified {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY) Long id;
        @SfsOneToMany
        List<ChildX> children;
    }

    // ChildX.owner가 참조하는 OwnerBoth (BothSpecified와 별개 — 컴파일용 최소 엔티티)
    @SfsEntity(name = "owner_both_ref")
    static class OwnerBoth {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY) Long id;
    }
}
