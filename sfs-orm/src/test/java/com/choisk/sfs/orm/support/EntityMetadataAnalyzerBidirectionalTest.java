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
import static org.assertj.core.api.Assertions.assertThat;
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

    // ─── Task 4: 양방향 mappedBy FK 해석 테스트 ───

    @Test
    void mappedBy_정상_시_owning_SfsJoinColumn에서_FK_도출_및_cascade_orphan_채움() {
        EntityMetadata md = analyzer.analyze(GoodOwner.class);
        CollectionMetadata cm = md.oneToManies().get(0);
        assertThat(cm.joinColumnName()).isEqualTo("owner_id");   // GoodChild.owner의 @SfsJoinColumn
        assertThat(cm.mappedBy()).isEqualTo("owner");
        assertThat(cm.cascadesPersist()).isTrue();
        assertThat(cm.orphanRemoval()).isTrue();
    }

    @Test
    void mappedBy가_가리키는_필드_부재_시_fail_fast() {
        assertThatThrownBy(() -> analyzer.analyze(BadMappedByName.class))
                .isInstanceOf(SfsEntityMappingException.class)
                .hasMessageContaining("mappedBy");
    }

    @Test
    void mappedBy_대상이_owner타입과_불일치_시_fail_fast() {
        assertThatThrownBy(() -> analyzer.analyze(MismatchOwner.class))
                .isInstanceOf(SfsEntityMappingException.class)
                .hasMessageContaining("targetEntity");
    }

    // ─── fixture (정상) ───
    @SfsEntity(name = "good_child")
    static class GoodChild {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY) Long id;
        @SfsManyToOne @SfsJoinColumn(name = "owner_id") GoodOwner owner;
    }
    @SfsEntity(name = "good_owner")
    static class GoodOwner {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY) Long id;
        @SfsOneToMany(mappedBy = "owner",
                cascade = {SfsCascadeType.PERSIST, SfsCascadeType.REMOVE}, orphanRemoval = true)
        List<GoodChild> children;
    }

    // ─── fixture (mappedBy 이름 오타) ───
    @SfsEntity(name = "bad_name_child")
    static class BadNameChild {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY) Long id;
        @SfsManyToOne @SfsJoinColumn(name = "owner_id") BadMappedByName owner;
    }
    @SfsEntity(name = "bad_name_owner")
    static class BadMappedByName {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY) Long id;
        @SfsOneToMany(mappedBy = "nonexistent") List<BadNameChild> children;
    }

    // ─── fixture (owning 타입 불일치) ───
    @SfsEntity(name = "other_entity")
    static class OtherEntity {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY) Long id;
    }
    @SfsEntity(name = "mismatch_child")
    static class MismatchChild {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY) Long id;
        @SfsManyToOne @SfsJoinColumn(name = "other_id") OtherEntity owner;  // MismatchOwner 아님
    }
    @SfsEntity(name = "mismatch_owner")
    static class MismatchOwner {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY) Long id;
        @SfsOneToMany(mappedBy = "owner") List<MismatchChild> children;
    }
}
