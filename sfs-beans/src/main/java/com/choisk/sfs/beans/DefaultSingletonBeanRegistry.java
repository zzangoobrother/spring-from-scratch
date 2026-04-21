package com.choisk.sfs.beans;

import com.choisk.sfs.core.Assert;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultSingletonBeanRegistry {

    /** 1차: 완성된 싱글톤. */
    protected final Map<String, Object> singletonObjects = new ConcurrentHashMap<>();
    /** 2차: 조기 노출된 참조 (AOP 프록시가 씌워졌을 수도 있음). */
    protected final Map<String, Object> earlySingletonObjects = new ConcurrentHashMap<>();
    /** 3차: 조기 참조 팩토리 (getEarlyBeanReference 훅 포함). */
    protected final Map<String, ObjectFactory<?>> singletonFactories = new ConcurrentHashMap<>();

    private final Object singletonLock = new Object();

    public void registerSingleton(String name, Object bean) {
        Assert.hasText(name, "name");
        Assert.notNull(bean, "bean");
        synchronized (singletonLock) {
            Object existing = singletonObjects.get(name);
            if (existing != null) {
                throw new IllegalStateException(
                        "Singleton '%s' already exists (%s)".formatted(name, existing.getClass().getName()));
            }
            singletonObjects.put(name, bean);
            earlySingletonObjects.remove(name);
            singletonFactories.remove(name);
        }
    }

    public void registerSingletonFactory(String name, ObjectFactory<?> factory) {
        Assert.hasText(name, "name");
        Assert.notNull(factory, "factory");
        synchronized (singletonLock) {
            if (!singletonObjects.containsKey(name)) {
                singletonFactories.put(name, factory);
                earlySingletonObjects.remove(name);
            }
        }
    }

    /**
     * 3-level 조회를 sealed result로 반환. 호출자는 switch pattern matching.
     * <p>3차 hit 시에도 여기서는 <b>승격하지 않음</b> - 승격 시점은 {@link #promoteToEarlyReference} 호출에서 명시적으로 결정.
     * 이렇게 나눈 이유는 호출자(AbstractBeanFactory)가 SmartBPP 체인 실행 여부를 제어할 수 있어야 하기 때문.
     */
    public CacheLookup lookup(String name) {
        Object complete = singletonObjects.get(name);
        if (complete != null) return new CacheLookup.Complete(complete);

        Object early = earlySingletonObjects.get(name);
        if (early != null) return new CacheLookup.EarlyReference(early);

        ObjectFactory<?> factory = singletonFactories.get(name);
        if (factory != null) return new CacheLookup.DeferredFactory(factory);

        return CacheLookup.Miss.INSTANCE;
    }

    /**
     * 3차 → 2차 승격. factory를 실행하여 결과를 earlySingletonObjects에 저장하고 3차에서 제거.
     * <p>이 메서드는 <b>정확히 한 번만</b> factory를 실행해야 한다. 동시 호출 시 synchronized 보장.
     */
    public Object promoteToEarlyReference(String name) {
        synchronized (singletonLock) {
            Object existing = earlySingletonObjects.get(name);
            if (existing != null) return existing;

            ObjectFactory<?> factory = singletonFactories.get(name);
            if (factory == null) {
                throw new IllegalStateException("No singleton factory registered for: " + name);
            }
            Object produced = factory.getObject();
            earlySingletonObjects.put(name, produced);
            singletonFactories.remove(name);
            return produced;
        }
    }

    public boolean containsSingleton(String name) {
        return singletonObjects.containsKey(name)
                || earlySingletonObjects.containsKey(name)
                || singletonFactories.containsKey(name);
    }

    public String[] getSingletonNames() {
        return singletonObjects.keySet().toArray(new String[0]);
    }

    public Object getSingleton(String name) {
        return singletonObjects.get(name);
    }
}
