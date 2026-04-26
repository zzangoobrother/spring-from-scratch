package com.choisk.sfs.beans.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.ConfigurableListableBeanFactory;
import com.choisk.sfs.beans.DependencyDescriptor;
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

    // ── 의존성 해석 ──────────────────────────────────────────────────────────

    /**
     * 타입 기반 의존성 해석 단순판.
     * <p>
     * 4분기:
     * <ol>
     *   <li>단일 매칭 → 해당 빈 반환</li>
     *   <li>0매칭 + required=false → null</li>
     *   <li>0매칭 + required=true → {@link NoSuchBeanDefinitionException}</li>
     *   <li>다수 후보 → 명시적 {@link IllegalStateException} (@Primary/@Qualifier 안내 포함)</li>
     * </ol>
     * BeanDefinition 기반 빈과 registerSingleton으로 직접 등록된 빈 모두 검색한다.
     *
     * @param desc               주입 요청 디스크립터 (타입, required, 이름)
     * @param requestingBeanName 요청 빈 이름 (순환 참조 감지용, 현재 미사용)
     * @return 해석된 빈 인스턴스 또는 null (required=false + 미매칭)
     */
    @Override
    public Object resolveDependency(DependencyDescriptor desc, String requestingBeanName) {
        // BeanDefinition 기반 + 직접 등록 싱글톤을 모두 포함한 타입 매칭 맵 구성
        Map<String, Object> matches = resolveBeansOfType(desc.getDependencyType());
        if (matches.isEmpty()) {
            if (desc.isRequired()) {
                throw new NoSuchBeanDefinitionException(
                        "No bean of type " + desc.getDependencyType().getName()
                        + " found for '" + desc.getDependencyName() + "'");
            }
            return null;
        }
        if (matches.size() == 1) {
            return matches.values().iterator().next();
        }
        // 다수 후보 폴백(@Primary/@Qualifier/이름)은 학습 범위 축소판 보류 —
        // 발견 시 명시적 예외로 *왜 정책이 필요한지* 학습
        throw new IllegalStateException(
                "Multiple beans of type " + desc.getDependencyType().getName()
                + " found: " + matches.keySet()
                + ". This learning-scope plan does not implement multi-candidate resolution. "
                + "Real Spring resolves this with @Primary → @Qualifier → field/parameter name fallback.");
    }

    /**
     * BeanDefinition 맵과 직접 등록 싱글톤 레지스트리를 합쳐 타입에 맞는 빈을 반환한다.
     * <p>{@link #getBeansOfType}은 BeanDefinition 기반만 검색하므로 별도 메서드로 분리.
     */
    private Map<String, Object> resolveBeansOfType(Class<?> type) {
        // 1) BeanDefinition 기반 빈
        Map<String, Object> result = new LinkedHashMap<>(getBeansOfType(type));
        // 2) 직접 등록된 싱글톤 (BeanDefinition 없이 registerSingleton으로 등록된 빈)
        for (String name : getSingletonNames()) {
            if (!result.containsKey(name)) {
                Object singleton = getSingleton(name);
                if (singleton != null && type.isInstance(singleton)) {
                    result.put(name, singleton);
                }
            }
        }
        return result;
    }

    // ── ConfigurableBeanFactory.destroySingletons 위임 ────────────────────

    @Override
    public void destroySingletons() {
        super.destroySingletons();
    }
}
