package com.choisk.sfs.beans.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.BeanFactory;
import com.choisk.sfs.beans.BeanPostProcessor;
import com.choisk.sfs.beans.CacheLookup;
import com.choisk.sfs.beans.ConfigurableBeanFactory;
import com.choisk.sfs.beans.DefaultSingletonBeanRegistry;
import com.choisk.sfs.beans.FactoryBean;
import com.choisk.sfs.beans.ObjectFactory;
import com.choisk.sfs.core.BeanCreationException;
import com.choisk.sfs.core.BeanIsNotAFactoryException;
import com.choisk.sfs.core.BeanNotOfRequiredTypeException;
import com.choisk.sfs.core.BeansException;
import com.choisk.sfs.core.FactoryBeanNotInitializedException;
import com.choisk.sfs.core.NoSuchBeanDefinitionException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * BeanFactory의 기본 구현. getBean 템플릿 메서드, FactoryBean/& 접두사 분기,
 * 싱글톤/프로토타입 라우팅을 담당. 실제 빈 생성은 서브클래스에서 {@link #createBean}.
 */
public abstract class AbstractBeanFactory
        extends DefaultSingletonBeanRegistry
        implements ConfigurableBeanFactory {

    private final List<BeanPostProcessor> beanPostProcessors = new ArrayList<>();
    private BeanFactory parentBeanFactory;

    @Override
    public Object getBean(String name) {
        return doGetBean(name, null);
    }

    @Override
    public <T> T getBean(String name, Class<T> requiredType) {
        Object bean = doGetBean(name, requiredType);
        if (requiredType != null && !requiredType.isInstance(bean)) {
            throw new BeanNotOfRequiredTypeException(name, requiredType, bean.getClass());
        }
        return requiredType.cast(bean);
    }

    @Override
    public <T> T getBean(Class<T> requiredType) {
        String name = resolveBeanNameByType(requiredType);
        return getBean(name, requiredType);
    }

    /** 이름→타입 해결은 DefaultListableBeanFactory에서 오버라이드 (BeanDefinition 맵 필요). */
    protected abstract String resolveBeanNameByType(Class<?> requiredType);

    /**
     * 핵심 진입점. Java 25 pattern matching으로 3-level cache 결과 분기.
     */
    protected Object doGetBean(String name, Class<?> requiredType) {
        String beanName = transformBeanName(name);
        boolean isFactoryDereference = isFactoryDereference(name);

        Object sharedInstance = switch (lookup(beanName)) {
            case CacheLookup.Complete(var bean)        -> bean;
            case CacheLookup.EarlyReference(var bean)  -> bean;
            case CacheLookup.DeferredFactory(var ignored) -> promoteToEarlyReference(beanName);
            case CacheLookup.Miss() -> null;
        };

        if (sharedInstance != null) {
            return resolveFactoryBean(beanName, sharedInstance, isFactoryDereference);
        }

        BeanDefinition definition = getBeanDefinition(beanName);
        if (definition == null) {
            throw new NoSuchBeanDefinitionException(
                    buildNoSuchBeanMessage(beanName, requiredType));
        }

        if (definition.isSingleton()) {
            Object created = getSingletonOrCreate(beanName, () -> createBean(beanName, definition));
            return resolveFactoryBean(beanName, created, isFactoryDereference);
        } else if (definition.isPrototype()) {
            Object created = createBean(beanName, definition);
            return resolveFactoryBean(beanName, created, isFactoryDereference);
        } else {
            throw new IllegalStateException("Unsupported scope: " + definition.getScope());
        }
    }

    /**
     * 싱글톤 캐시 조회 + 미존재 시 factory 실행 + 캐시 승격을 원자적으로.
     */
    protected Object getSingletonOrCreate(String beanName, ObjectFactory<?> factory) {
        Object existing = getSingleton(beanName);
        if (existing != null) return existing;

        beforeSingletonCreation(beanName);
        try {
            Object created = factory.getObject();
            addSingletonCommitted(beanName, created);
            return created;
        } finally {
            afterSingletonCreation(beanName);
        }
    }

    private void addSingletonCommitted(String name, Object bean) {
        // 이미 3-level 캐시의 2차에 있을 수 있음 (조기 참조로 노출됨).
        // 그 경우 기존 earlyReference를 신뢰하고 1차로 그대로 승격해야 동일 인스턴스 보장.
        Object early = earlySingletonObjects.get(name);
        if (early != null) {
            registerSingleton(name, early);
        } else {
            registerSingleton(name, bean);
        }
    }

    public String transformBeanName(String name) {
        return isFactoryDereference(name) ? name.substring(FACTORY_BEAN_PREFIX.length()) : name;
    }

    public boolean isFactoryDereference(String name) {
        return name != null && name.startsWith(FACTORY_BEAN_PREFIX);
    }

    /**
     * FactoryBean 분기 처리.
     * <ul>
     *   <li>{@code &name} 조회: FactoryBean 자신을 반환</li>
     *   <li>{@code name} 조회: FactoryBean이면 {@code getObject()} 결과(싱글톤은 캐시), 아니면 그대로</li>
     * </ul>
     */
    protected Object resolveFactoryBean(String beanName, Object sharedInstance, boolean isFactoryDereference) {
        // & 접두사: FactoryBean 자신을 반환
        if (isFactoryDereference) {
            if (!(sharedInstance instanceof FactoryBean<?>)) {
                throw new BeanIsNotAFactoryException(beanName, sharedInstance.getClass());
            }
            return sharedInstance;
        }

        // 일반 조회: FactoryBean이면 getObject() 결과, 아니면 그대로
        if (!(sharedInstance instanceof FactoryBean<?> factory)) {
            return sharedInstance;
        }

        // FactoryBean 결과 캐시 (싱글톤 FactoryBean의 경우)
        // 사용자 노출 이름과 충돌하지 않도록 "&__fb_obj__" 네임스페이스 사용
        String cacheKey = "&__fb_obj__" + beanName;
        Object cached = getSingleton(cacheKey);
        if (cached != null) return cached;

        try {
            Object produced = factory.getObject();
            if (produced == null) {
                throw new FactoryBeanNotInitializedException(beanName);
            }
            if (factory.isSingleton()) {
                registerSingleton(cacheKey, produced);
            }
            return produced;
        } catch (BeansException e) {
            throw e;
        } catch (Exception e) {
            throw new BeanCreationException(beanName, "FactoryBean.getObject() threw exception", e);
        }
    }

    protected abstract BeanDefinition getBeanDefinition(String beanName);

    protected abstract Object createBean(String beanName, BeanDefinition definition);

    private String buildNoSuchBeanMessage(String name, Class<?> requiredType) {
        var candidates = Arrays.asList(getSingletonNames());
        var similar = candidates.stream()
                .filter(existing -> levenshtein(existing, name) <= 3)
                .limit(3)
                .toList();
        var sb = new StringBuilder("No bean named '").append(name).append("' found");
        if (requiredType != null) {
            sb.append(" (required type: ").append(requiredType.getName()).append(")");
        }
        if (!similar.isEmpty()) {
            sb.append(". Did you mean: ").append(similar).append("?");
        }
        sb.append("\nPossible solutions:")
          .append("\n  - Register the bean via registerBeanDefinition or register a @Component class")
          .append("\n  - Check bean name spelling");
        return sb.toString();
    }

    private static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    // --- BeanFactory ---
    @Override
    public boolean containsBean(String name) {
        String beanName = transformBeanName(name);
        return containsSingleton(beanName) || containsBeanDefinition(beanName);
    }

    @Override
    public boolean isSingleton(String name) {
        String beanName = transformBeanName(name);
        BeanDefinition def = getBeanDefinition(beanName);
        return def != null && def.isSingleton();
    }

    @Override
    public boolean isPrototype(String name) {
        String beanName = transformBeanName(name);
        BeanDefinition def = getBeanDefinition(beanName);
        return def != null && def.isPrototype();
    }

    @Override
    public Class<?> getType(String name) {
        String beanName = transformBeanName(name);
        Object singleton = getSingleton(beanName);
        if (singleton != null) return singleton.getClass();
        BeanDefinition def = getBeanDefinition(beanName);
        return def != null ? def.getBeanClass() : null;
    }

    protected abstract boolean containsBeanDefinition(String beanName);

    // --- HierarchicalBeanFactory ---
    @Override
    public BeanFactory getParentBeanFactory() { return parentBeanFactory; }

    public void setParentBeanFactory(BeanFactory parent) { this.parentBeanFactory = parent; }

    @Override
    public boolean containsLocalBean(String name) {
        String beanName = transformBeanName(name);
        return containsSingleton(beanName) || containsBeanDefinition(beanName);
    }

    // --- ConfigurableBeanFactory ---
    @Override
    public void addBeanPostProcessor(BeanPostProcessor processor) {
        beanPostProcessors.remove(processor);
        beanPostProcessors.add(processor);
    }

    @Override
    public int getBeanPostProcessorCount() { return beanPostProcessors.size(); }

    public List<BeanPostProcessor> getBeanPostProcessors() {
        return Collections.unmodifiableList(beanPostProcessors);
    }
}
