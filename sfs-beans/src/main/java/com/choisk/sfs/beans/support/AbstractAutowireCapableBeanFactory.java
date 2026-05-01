package com.choisk.sfs.beans.support;

import com.choisk.sfs.beans.AutowireCapableBeanFactory;
import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.BeanFactoryAware;
import com.choisk.sfs.beans.BeanNameAware;
import com.choisk.sfs.beans.BeanPostProcessor;
import com.choisk.sfs.beans.BeanReference;
import com.choisk.sfs.beans.ConfigurableListableBeanFactory;
import com.choisk.sfs.beans.DependencyDescriptor;
import com.choisk.sfs.beans.DisposableBean;
import com.choisk.sfs.beans.InitializingBean;
import com.choisk.sfs.beans.InstantiationAwareBeanPostProcessor;
import com.choisk.sfs.beans.ObjectFactory;
import com.choisk.sfs.beans.PropertyValues;
import com.choisk.sfs.beans.Scope;
import com.choisk.sfs.beans.SmartInstantiationAwareBeanPostProcessor;
import com.choisk.sfs.core.BeanCreationException;
import com.choisk.sfs.core.ReflectionUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * 실제 빈 인스턴스화 + 프로퍼티 주입 + 초기화 로직을 담당.
 * <p>Spring 원본: {@code AbstractAutowireCapableBeanFactory}.
 */
public abstract class AbstractAutowireCapableBeanFactory
        extends AbstractBeanFactory
        implements AutowireCapableBeanFactory {

    @Override
    protected Object createBean(String beanName, BeanDefinition definition) {
        // B-1: InstantiationAware.before (프록시로 조기 종료 가능성)
        Object shortCircuit = resolveBeforeInstantiation(beanName, definition);
        if (shortCircuit != null) {
            return shortCircuit;
        }
        return doCreateBean(beanName, definition);
    }

    /** B-1 단계. 현재는 hook point만 제공; AOP에서 오버라이드. */
    protected Object resolveBeforeInstantiation(String beanName, BeanDefinition def) {
        for (BeanPostProcessor bpp : getBeanPostProcessors()) {
            if (bpp instanceof InstantiationAwareBeanPostProcessor iabpp) {
                Object result = iabpp.postProcessBeforeInstantiation(def.getBeanClass(), beanName);
                if (result != null) return applyBeanPostProcessorsAfterInitialization(result, beanName);
            }
        }
        return null;
    }

    protected Object doCreateBean(String beanName, BeanDefinition definition) {
        // factoryMethod 분기 — BD에 factoryMethodName이 있으면 생성자 대신 팩토리 메서드로 인스턴스화
        // Spring 본가와 동일: 인스턴스화 → 3차 캐시 등록 → populateBean → initializeBean 순서 유지
        // (순환 의존 + SIABPP early reference 훅이 factoryMethod 경로에서도 동작하려면 3차 캐시 등록 필수)
        if (definition.getFactoryMethodName() != null) {
            Object factoryResult = createBeanViaFactoryMethod(beanName, definition);

            // B-3: 3차 캐시에 팩토리 등록 (조기 참조용) — populateBean 이전에 등록해야 순환 의존 시 early reference 반환 가능
            if (definition.isSingleton()) {
                final Object rawForEarly = factoryResult;
                registerSingletonFactory(beanName, () -> getEarlyBeanReference(beanName, rawForEarly));
            }

            // B-4: @Autowired 필드 주입 — @Bean 메서드 반환 인스턴스에도 적용 (순환 의존 시 필요)
            populateBean(beanName, definition, factoryResult);

            // B-5: 초기화 (BeanFactoryAware + BPP after)
            Object initialized = initializeBean(beanName, definition, factoryResult);

            // initializeBean 실패 시 registerDisposableIfNeeded 미호출 — 초기화 미완 빈은 destroy 대상 아님 (Spring 본가와 동일 정책)
            registerDisposableIfNeeded(beanName, definition, initialized);
            return initialized;
        }

        // B-2: 인스턴스화
        Object bean = instantiateBean(beanName, definition);

        // B-3: 3차 캐시에 팩토리 등록 (조기 참조용)
        boolean earlySingletonExposure = definition.isSingleton();
        if (earlySingletonExposure) {
            registerSingletonFactory(beanName, () -> getEarlyBeanReference(beanName, bean));
        }

        // B-4: 프로퍼티 주입
        populateBean(beanName, definition, bean);

        // B-5: 초기화
        Object exposed = initializeBean(beanName, definition, bean);

        // B-7: destroy 등록
        registerDisposableIfNeeded(beanName, definition, exposed);

        return exposed;
    }

    /**
     * factoryMethod 경로로 빈을 생성한다.
     * <p>BD의 factoryBeanName으로 팩토리 빈을 가져온 후, factoryMethodName과 일치하는 메서드를
     * 찾아 인자를 {@link ConfigurableListableBeanFactory#resolveDependency}로 해석하여 호출한다.
     * 동일 이름 오버로드는 하나만 있다고 가정 (학습용 단순화).
     */
    private Object createBeanViaFactoryMethod(String beanName, BeanDefinition definition) {
        Object factoryBean = getBean(definition.getFactoryBeanName());
        // ReflectionUtils.findMethod은 상속 계층까지 탐색 (부모 @Configuration 클래스에 정의된 @Bean도 처리 가능)
        Method m = ReflectionUtils.findMethod(factoryBean.getClass(), definition.getFactoryMethodName());
        if (m == null) {
            throw new BeanCreationException(beanName,
                    "factoryMethod not found: " + definition.getFactoryMethodName());
        }
        Object[] args = resolveFactoryMethodArguments(m, beanName);
        return ReflectionUtils.invokeMethod(m, factoryBean, args);
    }

    /**
     * 메서드 매개변수마다 {@link ConfigurableListableBeanFactory#resolveDependency}를 호출하여
     * 인자 배열을 구성한다.
     * <p>dependency name은 {@code paramType.getSimpleName()} 사용 — {@code -parameters} 컴파일 옵션 의존 회피.
     */
    private Object[] resolveFactoryMethodArguments(Method m, String requestingBeanName) {
        Class<?>[] paramTypes = m.getParameterTypes();
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            DependencyDescriptor desc = new DependencyDescriptor(
                    paramTypes[i], true, paramTypes[i].getSimpleName());
            // 런타임 인스턴스는 ConfigurableListableBeanFactory 구현체(DefaultListableBeanFactory)이므로 캐스팅 — A1에서 인터페이스에 승격됨
            args[i] = ((ConfigurableListableBeanFactory) this).resolveDependency(desc, requestingBeanName);
        }
        return args;
    }

    protected Object instantiateBean(String beanName, BeanDefinition definition) {
        Class<?> beanClass = definition.getBeanClass();
        try {
            if (beanClass.isInterface() || Modifier.isAbstract(beanClass.getModifiers())) {
                throw new BeanCreationException(beanName,
                        "Cannot instantiate interface or abstract class: " + beanClass.getName());
            }
            // 생성자 인자가 명시됐으면 해당 시그니처 찾기
            List<Object> args = definition.getConstructorArgs();
            if (args.isEmpty()) {
                Constructor<?> ctor = beanClass.getDeclaredConstructor();
                ctor.setAccessible(true);
                return ctor.newInstance();
            }
            for (Constructor<?> ctor : beanClass.getDeclaredConstructors()) {
                if (ctor.getParameterCount() == args.size()) {
                    Object[] resolved = resolveConstructorArgs(ctor.getParameterTypes(), args);
                    ctor.setAccessible(true);
                    return ctor.newInstance(resolved);
                }
            }
            throw new BeanCreationException(beanName,
                    "No constructor matching %d args on %s".formatted(args.size(), beanClass.getName()));
        } catch (ReflectiveOperationException e) {
            throw new BeanCreationException(beanName, "Instantiation failed", e);
        }
    }

    private Object[] resolveConstructorArgs(Class<?>[] paramTypes, List<Object> raw) {
        Object[] resolved = new Object[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            Object a = raw.get(i);
            if (a instanceof BeanReference ref) {
                resolved[i] = getBean(ref.beanName());
            } else {
                resolved[i] = a;
            }
        }
        return resolved;
    }

    /** B-3에서 3차 factory가 생산하는 조기 참조. SmartInstantiationAwareBPP를 체인 적용. */
    protected Object getEarlyBeanReference(String beanName, Object rawBean) {
        Object exposed = rawBean;
        for (BeanPostProcessor bpp : getBeanPostProcessors()) {
            if (bpp instanceof SmartInstantiationAwareBeanPostProcessor smart) {
                exposed = smart.getEarlyBeanReference(exposed, beanName);
            }
        }
        return exposed;
    }

    // ── 프로퍼티 주입 ────
    protected void populateBean(String beanName, BeanDefinition definition, Object bean) {
        // InstantiationAwareBPP가 @Autowired 등 프로퍼티 주입을 여기에 꽂음
        boolean continuePopulation = true;
        for (BeanPostProcessor bpp : getBeanPostProcessors()) {
            if (bpp instanceof InstantiationAwareBeanPostProcessor iabpp) {
                if (!iabpp.postProcessAfterInstantiation(bean, beanName)) {
                    continuePopulation = false;
                    break;
                }
            }
        }
        if (!continuePopulation) return;

        PropertyValues pvs = definition.getPropertyValues();
        for (BeanPostProcessor bpp : getBeanPostProcessors()) {
            if (bpp instanceof InstantiationAwareBeanPostProcessor iabpp) {
                pvs = iabpp.postProcessProperties(pvs, bean, beanName);
                if (pvs == null) return;
            }
        }

        // BeanDefinition에 명시된 propertyValues를 리플렉션으로 적용
        applyPropertyValues(beanName, bean, pvs);
    }

    private void applyPropertyValues(String beanName, Object bean, PropertyValues pvs) {
        if (pvs == null || pvs.isEmpty()) return;
        for (var pv : pvs.all()) {
            Object value = pv.value() instanceof BeanReference ref
                    ? getBean(ref.beanName())
                    : pv.value();
            Field field = ReflectionUtils.findField(bean.getClass(), pv.name());
            if (field != null) {
                ReflectionUtils.setField(field, bean, value);
                continue;
            }
            String setter = "set" + Character.toUpperCase(pv.name().charAt(0)) + pv.name().substring(1);
            Method method = ReflectionUtils.findMethod(bean.getClass(), setter, value == null ? Object.class : value.getClass());
            if (method != null) {
                ReflectionUtils.invokeMethod(method, bean, value);
                continue;
            }
            throw new BeanCreationException(beanName,
                    "No property '%s' found on %s".formatted(pv.name(), bean.getClass().getName()));
        }
    }

    // ── 초기화 + destroy 등록 ────
    protected Object initializeBean(String beanName, BeanDefinition definition, Object bean) {
        // B-5 (a) Aware 콜백
        invokeAwareCallbacks(beanName, bean);

        // B-5 (b) BPP before
        Object current = applyBeanPostProcessorsBeforeInitialization(bean, beanName);
        if (current == null) return null;

        // B-5 (c) InitializingBean + init-method
        try {
            if (current instanceof InitializingBean ib) {
                ib.afterPropertiesSet();
            }
            if (definition.getInitMethodName() != null) {
                Method m = ReflectionUtils.findMethod(current.getClass(), definition.getInitMethodName());
                if (m == null) {
                    throw new BeanCreationException(beanName,
                            "Init method '%s' not found on %s".formatted(definition.getInitMethodName(), current.getClass().getName()));
                }
                ReflectionUtils.invokeMethod(m, current);
            }
        } catch (Exception e) {
            throw new BeanCreationException(beanName, "Initialization callback failed", e);
        }

        // B-5 (d) BPP after (AOP 프록시는 여기서 - Phase 2)
        return applyBeanPostProcessorsAfterInitialization(current, beanName);
    }

    private void invokeAwareCallbacks(String beanName, Object bean) {
        if (bean instanceof BeanNameAware bna) bna.setBeanName(beanName);
        if (bean instanceof BeanFactoryAware bfa) bfa.setBeanFactory(this);
    }

    protected void registerDisposableIfNeeded(String beanName, BeanDefinition definition, Object bean) {
        if (!definition.isSingleton()) return;
        boolean hasDisposable = bean instanceof DisposableBean;
        boolean hasDestroyMethod = definition.getDestroyMethodName() != null;
        if (!hasDisposable && !hasDestroyMethod) return;

        registerDisposableBean(beanName, () -> {
            try {
                if (bean instanceof DisposableBean db) db.destroy();
                if (definition.getDestroyMethodName() != null) {
                    Method m = ReflectionUtils.findMethod(bean.getClass(), definition.getDestroyMethodName());
                    if (m != null) ReflectionUtils.invokeMethod(m, bean);
                }
            } catch (Exception e) {
                throw new RuntimeException("Destroy failed for " + beanName, e);
            }
        });
    }

    // --- AutowireCapableBeanFactory 수동 API ---
    @Override
    public Object createBean(Class<?> beanClass) {
        BeanDefinition def = new BeanDefinition(beanClass).setScope(Scope.Prototype.INSTANCE);
        return doCreateBean(beanClass.getName(), def);
    }

    @Override
    public void autowireBean(Object existingBean) {
        BeanDefinition def = new BeanDefinition(existingBean.getClass());
        populateBean(existingBean.getClass().getName(), def, existingBean);
    }

    @Override
    public Object initializeBean(Object existingBean, String beanName) {
        BeanDefinition def = new BeanDefinition(existingBean.getClass());
        return initializeBean(beanName, def, existingBean);
    }

    @Override
    public Object applyBeanPostProcessorsBeforeInitialization(Object existingBean, String beanName) {
        Object result = existingBean;
        for (BeanPostProcessor bpp : getBeanPostProcessors()) {
            result = bpp.postProcessBeforeInitialization(result, beanName);
            if (result == null) return null;
        }
        return result;
    }

    @Override
    public Object applyBeanPostProcessorsAfterInitialization(Object existingBean, String beanName) {
        Object result = existingBean;
        for (BeanPostProcessor bpp : getBeanPostProcessors()) {
            result = bpp.postProcessAfterInitialization(result, beanName);
            if (result == null) return null;
        }
        return result;
    }
}
