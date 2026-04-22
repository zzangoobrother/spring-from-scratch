package com.choisk.sfs.beans.support;

import com.choisk.sfs.beans.AutowireCapableBeanFactory;
import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.BeanPostProcessor;
import com.choisk.sfs.beans.BeanReference;
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

        // B-7: destroy 등록 (Task 28에서 확장)
        registerDisposableIfNeeded(beanName, definition, exposed);

        return exposed;
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

    // --- Task 27: populateBean 구현 ---
    protected void populateBean(String beanName, BeanDefinition definition, Object bean) {
        // InstantiationAwareBPP 후킹 (Plan 1B에서 @Autowired 주입이 여기에 꽂힘)
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

    // --- Task 28에서 구현 ---
    protected abstract Object initializeBean(String beanName, BeanDefinition definition, Object bean);

    protected abstract void registerDisposableIfNeeded(String beanName, BeanDefinition definition, Object bean);

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
