package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.annotation.SfsColumn;
import com.choisk.sfs.orm.annotation.SfsEntity;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue.GenerationType;
import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.orm.annotation.SfsJoinColumn;
import com.choisk.sfs.orm.annotation.SfsManyToOne;
import com.choisk.sfs.orm.exception.SfsEntityMappingException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityMetadataAnalyzerTest {

    @SfsEntity(name = "valid_users")
    static class ValidEntity {
        @SfsId
        @SfsGeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @SfsColumn
        String name;
    }

    @Test
    void analyzes_valid_entity_successfully() {
        EntityMetadata md = new EntityMetadataAnalyzer().analyze(ValidEntity.class);
        assertThat(md.tableName()).isEqualTo("valid_users");
        assertThat(md.idField().field().getName()).isEqualTo("id");
        assertThat(md.columns()).hasSize(1);
    }

    static class MissingEntityAnnotation {
        @SfsId Long id;
    }

    @Test
    void throws_when_class_not_annotated_with_SfsEntity() {
        assertThatThrownBy(() -> new EntityMetadataAnalyzer().analyze(MissingEntityAnnotation.class))
                .isInstanceOf(SfsEntityMappingException.class)
                .hasMessageContaining("@SfsEntity");
    }

    @SfsEntity
    static class NoIdField {
        @SfsColumn String name;
    }

    @Test
    void throws_when_no_SfsId_field() {
        assertThatThrownBy(() -> new EntityMetadataAnalyzer().analyze(NoIdField.class))
                .isInstanceOf(SfsEntityMappingException.class)
                .hasMessageContaining("@SfsId");
    }

    @SfsEntity
    static class TwoIdFields {
        @SfsId Long id1;
        @SfsId Long id2;
    }

    @Test
    void throws_when_multiple_SfsId_fields() {
        assertThatThrownBy(() -> new EntityMetadataAnalyzer().analyze(TwoIdFields.class))
                .isInstanceOf(SfsEntityMappingException.class)
                .hasMessageContaining("multiple");
    }

    @SfsEntity
    static class SequenceWithoutName {
        @SfsId
        @SfsGeneratedValue(strategy = GenerationType.SEQUENCE)
        Long id;
    }

    @Test
    void throws_when_SEQUENCE_strategy_missing_sequenceName() {
        assertThatThrownBy(() -> new EntityMetadataAnalyzer().analyze(SequenceWithoutName.class))
                .isInstanceOf(SfsEntityMappingException.class)
                .hasMessageContaining("sequenceName");
    }

    @SfsEntity
    static class ManyToOneWithoutEntityType {
        @SfsId
        @SfsGeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @SfsManyToOne
        @SfsJoinColumn(name = "user_id")
        String notAnEntity;
    }

    @Test
    void throws_when_ManyToOne_target_not_SfsEntity() {
        assertThatThrownBy(() -> new EntityMetadataAnalyzer().analyze(ManyToOneWithoutEntityType.class))
                .isInstanceOf(SfsEntityMappingException.class)
                .hasMessageContaining("@SfsEntity");
    }

    @SfsEntity
    static class JoinColumnMissing {
        @SfsId
        @SfsGeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @SfsManyToOne ValidEntity user;   // @SfsJoinColumn 누락
    }

    @Test
    void throws_when_ManyToOne_missing_JoinColumn() {
        assertThatThrownBy(() -> new EntityMetadataAnalyzer().analyze(JoinColumnMissing.class))
                .isInstanceOf(SfsEntityMappingException.class)
                .hasMessageContaining("@SfsJoinColumn");
    }

    @Test
    void caches_analyzed_metadata_per_class() {
        EntityMetadataAnalyzer analyzer = new EntityMetadataAnalyzer();
        EntityMetadata md1 = analyzer.analyze(ValidEntity.class);
        EntityMetadata md2 = analyzer.analyze(ValidEntity.class);
        assertThat(md1).isSameAs(md2);
    }
}
