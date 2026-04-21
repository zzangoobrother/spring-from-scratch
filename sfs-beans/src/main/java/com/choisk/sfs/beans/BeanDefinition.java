package com.choisk.sfs.beans;

import com.choisk.sfs.core.Assert;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 빈 하나의 메타데이터. mutable class (BeanFactoryPostProcessor가 수정 가능해야 함).
 * <p>Spring 원본: {@code AbstractBeanDefinition} 계열을 단순화.
 */
public class BeanDefinition {

    private Class<?> beanClass;
    private Scope scope = Scope.Singleton.INSTANCE;
    private boolean lazyInit = false;
    private boolean primary = false;
    private String qualifier;
    private AutowireMode autowireMode = AutowireMode.NO;

    private final List<Object> constructorArgs = new ArrayList<>();
    private final PropertyValues propertyValues = new PropertyValues();

    private String initMethodName;
    private String destroyMethodName;

    private String factoryBeanName;
    private String factoryMethodName;

    public BeanDefinition(Class<?> beanClass) {
        this.beanClass = Assert.notNull(beanClass, "beanClass");
    }

    // --- getters ---
    public Class<?> getBeanClass() { return beanClass; }
    public Scope getScope() { return scope; }
    public boolean isLazyInit() { return lazyInit; }
    public boolean isPrimary() { return primary; }
    public String getQualifier() { return qualifier; }
    public AutowireMode getAutowireMode() { return autowireMode; }
    public List<Object> getConstructorArgs() { return Collections.unmodifiableList(constructorArgs); }
    public PropertyValues getPropertyValues() { return propertyValues; }
    public String getInitMethodName() { return initMethodName; }
    public String getDestroyMethodName() { return destroyMethodName; }
    public String getFactoryBeanName() { return factoryBeanName; }
    public String getFactoryMethodName() { return factoryMethodName; }

    public boolean isSingleton() { return scope instanceof Scope.Singleton; }
    public boolean isPrototype() { return scope instanceof Scope.Prototype; }

    // --- fluent setters (BFPP가 수정할 때 사용) ---
    public BeanDefinition setBeanClass(Class<?> beanClass) { this.beanClass = Assert.notNull(beanClass, "beanClass"); return this; }
    public BeanDefinition setScope(Scope scope) { this.scope = Assert.notNull(scope, "scope"); return this; }
    public BeanDefinition setLazyInit(boolean lazyInit) { this.lazyInit = lazyInit; return this; }
    public BeanDefinition setPrimary(boolean primary) { this.primary = primary; return this; }
    public BeanDefinition setQualifier(String qualifier) { this.qualifier = qualifier; return this; }
    public BeanDefinition setAutowireMode(AutowireMode mode) { this.autowireMode = Assert.notNull(mode, "autowireMode"); return this; }
    public BeanDefinition setInitMethodName(String name) { this.initMethodName = name; return this; }
    public BeanDefinition setDestroyMethodName(String name) { this.destroyMethodName = name; return this; }
    public BeanDefinition setFactoryBeanName(String name) { this.factoryBeanName = name; return this; }
    public BeanDefinition setFactoryMethodName(String name) { this.factoryMethodName = name; return this; }

    public BeanDefinition addConstructorArg(Object arg) { this.constructorArgs.add(arg); return this; }
    public BeanDefinition addPropertyValue(String name, Object value) {
        this.propertyValues.add(new PropertyValue(name, value));
        return this;
    }
}
