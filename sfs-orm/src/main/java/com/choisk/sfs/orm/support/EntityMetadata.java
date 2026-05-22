package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.annotation.SfsManyToOne.FetchType;

import java.util.List;

/**
 * 엔티티 클래스 분석 결과를 담는 불변 메타데이터 record.
 * EntityMetadataAnalyzer가 생성하며, 영속성 컨텍스트/SQL 실행 전반에서 참조한다.
 */
public record EntityMetadata(
        Class<?> entityClass,
        String tableName,
        FieldMetadata idField,
        IdGeneratorSpec idGeneratorSpec,
        List<FieldMetadata> columns,
        List<RelationMetadata> manyToOnes,
        List<CollectionMetadata> oneToManies,    // 신설 — manyToOnes 인접 (관계 도메인 묶음)
        String insertSql,
        String selectByIdSql,
        String selectAllSql,                      // 신설 — SELECT * 사전 빌드 (Phase 4 SQL 캐싱 패턴)
        String deleteSql
) {
    /** LAZY fetch 연관 필드가 하나라도 있으면 true — 프록시 생성 여부 판단 기준 */
    public boolean hasLazyFields() {
        return manyToOnes.stream().anyMatch(r -> r.fetch() == FetchType.LAZY);
    }
}
