package com.choisk.sfs.beans;

import com.choisk.sfs.core.Assert;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultSingletonBeanRegistry {

    /** 1차: 완성된 싱글톤. */
    protected final Map<String, Object> singletonObjects = new ConcurrentHashMap<>();

    public void registerSingleton(String name, Object bean) {
        Assert.hasText(name, "name");
        Assert.notNull(bean, "bean");
        synchronized (singletonObjects) {
            Object existing = singletonObjects.get(name);
            if (existing != null) {
                throw new IllegalStateException(
                        "Singleton '%s' already exists (%s)".formatted(name, existing.getClass().getName()));
            }
            singletonObjects.put(name, bean);
        }
    }

    public Object getSingleton(String name) {
        return singletonObjects.get(name);
    }

    public boolean containsSingleton(String name) {
        return singletonObjects.containsKey(name);
    }

    public String[] getSingletonNames() {
        return singletonObjects.keySet().toArray(new String[0]);
    }
}
