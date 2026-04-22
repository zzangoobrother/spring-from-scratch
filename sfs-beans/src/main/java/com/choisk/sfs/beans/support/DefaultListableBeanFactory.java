package com.choisk.sfs.beans.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.ConfigurableListableBeanFactory;
import com.choisk.sfs.core.Assert;
import com.choisk.sfs.core.NoSuchBeanDefinitionException;
import com.choisk.sfs.core.NoUniqueBeanDefinitionException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BeanDefinition 레지스트리 + ListableBeanFactory + preInstantiateSingletons 구현.
 * <p>Spring 원본: {@code DefaultListableBeanFactory}.
 */
public class DefaultListableBeanFactory
        extends AbstractAutowireCapableBeanFactory
        implements ConfigurableListableBeanFactory {

    /** BeanDefinition 저장소 (이름 → 정의). */
    private final Map<String, BeanDefinition> beanDefinitionMap = new ConcurrentHashMap<>();
    /** 등록 순서 보존 목록. synchronized(beanDefinitionNames) 로 접근. */
    private final List<String> beanDefinitionNames = Collections.synchronizedList(new ArrayList<>());

    // ── ConfigurableListableBeanFactory ──────────────────────────────────

    @Override
    public void registerBeanDefinition(String name, BeanDefinition definition) {
        Assert.hasText(name, "name");
        Assert.notNull(definition, "definition");
        BeanDefinition existing = beanDefinitionMap.put(name, definition);
        if (existing == null) {
            beanDefinitionNames.add(name);
        }
        // 덮어쓰기 허용. 순서는 기존 유지.
    }

    @Override
    public BeanDefinition getBeanDefinition(String name) {
        return beanDefinitionMap.get(name);
    }

    @Override
    public void preInstantiateSingletons() {
        String[] names;
        synchronized (beanDefinitionNames) {
            names = beanDefinitionNames.toArray(new String[0]);
        }
        for (String name : names) {
            BeanDefinition def = beanDefinitionMap.get(name);
            if (def != null && def.isSingleton() && !def.isLazyInit()) {
                getBean(name);
            }
        }
    }

    // ── ListableBeanFactory ───────────────────────────────────────────────

    @Override
    public boolean containsBeanDefinition(String name) {
        return beanDefinitionMap.containsKey(name);
    }

    @Override
    public int getBeanDefinitionCount() {
        return beanDefinitionMap.size();
    }

    @Override
    public String[] getBeanDefinitionNames() {
        synchronized (beanDefinitionNames) {
            return beanDefinitionNames.toArray(new String[0]);
        }
    }

    @Override
    public String[] getBeanNamesForType(Class<?> type) {
        var matches = new ArrayList<String>();
        for (var entry : beanDefinitionMap.entrySet()) {
            if (type.isAssignableFrom(entry.getValue().getBeanClass())) {
                matches.add(entry.getKey());
            }
        }
        return matches.toArray(new String[0]);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Map<String, T> getBeansOfType(Class<T> type) {
        var result = new LinkedHashMap<String, T>();
        for (var name : getBeanNamesForType(type)) {
            result.put(name, (T) getBean(name));
        }
        return result;
    }

    // ── AbstractBeanFactory 추상 메서드 구현 ──────────────────────────────

    /**
     * 타입으로 빈 이름을 해결한다. @Primary 우선, 복수 후보 시 NoUniqueBeanDefinitionException.
     */
    @Override
    protected String resolveBeanNameByType(Class<?> type) {
        var matches = getBeanNamesForType(type);
        if (matches.length == 0) {
            throw new NoSuchBeanDefinitionException(
                    "No bean of type " + type.getName() + " registered");
        }
        if (matches.length == 1) {
            return matches[0];
        }

        // @Primary 우선 해결
        var primaries = new ArrayList<String>();
        for (var name : matches) {
            if (beanDefinitionMap.get(name).isPrimary()) {
                primaries.add(name);
            }
        }
        if (primaries.size() == 1) {
            return primaries.get(0);
        }
        if (primaries.size() > 1) {
            throw new NoUniqueBeanDefinitionException(
                    "Multiple @Primary beans of type " + type.getName() + ": " + primaries,
                    primaries);
        }
        throw new NoUniqueBeanDefinitionException(
                "Multiple beans of type " + type.getName() + ": " + Arrays.asList(matches)
                        + "\nPossible solutions: annotate one with @Primary or inject by name",
                Arrays.asList(matches));
    }

    // ── ConfigurableBeanFactory.destroySingletons 위임 ────────────────────

    @Override
    public void destroySingletons() {
        super.destroySingletons();
    }
}
