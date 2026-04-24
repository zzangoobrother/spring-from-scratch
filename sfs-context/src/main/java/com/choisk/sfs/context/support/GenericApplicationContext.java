package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.ConfigurableListableBeanFactory;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 가장 일반적인 ApplicationContext 구현. BeanDefinition을 직접 등록하거나
 * 외부에서 미리 채워진 {@link DefaultListableBeanFactory}를 받을 수 있다.
 *
 * <p>{@code refresh()}는 single-shot — 두 번 호출 시 예외.
 *
 * <p>Spring 원본: {@code GenericApplicationContext}.
 */
public class GenericApplicationContext extends AbstractApplicationContext {

    private final DefaultListableBeanFactory beanFactory;
    private final AtomicBoolean refreshed = new AtomicBoolean(false);

    public GenericApplicationContext() {
        this(new DefaultListableBeanFactory());
    }

    public GenericApplicationContext(DefaultListableBeanFactory bf) {
        this.beanFactory = Objects.requireNonNull(bf, "beanFactory");
    }

    public void registerBeanDefinition(String name, BeanDefinition bd) {
        beanFactory.registerBeanDefinition(name, bd);
    }

    @Override
    protected void refreshBeanFactory() {
        if (!refreshed.compareAndSet(false, true)) {
            throw new IllegalStateException(
                "GenericApplicationContext refresh() already called — single-shot context");
        }
    }

    @Override
    public ConfigurableListableBeanFactory getBeanFactory() {
        return beanFactory;
    }
}
