package com.choisk.sfs.beans;

/**
 * 3-level cache 조회 결과. 호출자는 pattern matching switch로 분기한다.
 * <pre>
 * var result = switch (registry.lookup(name)) {
 *     case CacheLookup.Complete(var bean)        -> bean;
 *     case CacheLookup.EarlyReference(var bean)  -> bean;
 *     case CacheLookup.DeferredFactory(var f)    -> promoteAndInvoke(name, f);
 *     case CacheLookup.Miss() -> createBean(name);
 * };
 * </pre>
 */
public sealed interface CacheLookup {

    record Complete(Object bean) implements CacheLookup {}
    record EarlyReference(Object bean) implements CacheLookup {}
    record DeferredFactory(ObjectFactory<?> factory) implements CacheLookup {}
    record Miss() implements CacheLookup {
        public static final Miss INSTANCE = new Miss();
    }
}
