package com.choisk.sfs.orm.support;

public interface IdentifierGenerator {
    /**
     * post-insert (IDENTITY)면 generate()가 INSERT까지 수행하고 generated key 반환.
     * pre-insert (SEQUENCE)면 INSERT 전에 호출되어 ID만 반환.
     */
    Object generate(Object entity, EntityMetadata md);

    boolean isPostInsert();
}
