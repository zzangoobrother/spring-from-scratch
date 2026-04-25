package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanFactoryPostProcessor;
import com.choisk.sfs.beans.ConfigurableListableBeanFactory;
import com.choisk.sfs.context.ApplicationContext;
import com.choisk.sfs.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;

/**
 * refresh() 8단계 템플릿 메서드. 서브클래스는 {@link #refreshBeanFactory()}와
 * {@link #getBeanFactory()}만 채우면 된다.
 *
 * <p>Spring 원본: {@code AbstractApplicationContext}.
 */
public abstract class AbstractApplicationContext implements ConfigurableApplicationContext {

    private final List<BeanFactoryPostProcessor> bfpps = new ArrayList<>();
    private final Object startupShutdownMonitor = new Object();
    private volatile boolean active = false;
    private long startupDate;
    private final String id = String.valueOf(System.identityHashCode(this));
    private Thread shutdownHook;

    @Override public final String getId() { return id; }
    @Override public String getApplicationName() { return ""; }
    @Override public long getStartupDate() { return startupDate; }
    @Override public boolean isActive() { return active; }

    @Override
    public void addBeanFactoryPostProcessor(BeanFactoryPostProcessor postProcessor) {
        bfpps.add(postProcessor);
    }

    protected List<BeanFactoryPostProcessor> getBeanFactoryPostProcessors() {
        return bfpps;
    }

    /** 서브클래스에서 BeanFactory 인스턴스를 신규 생성/재설정. single-shot 정책 강제. */
    protected abstract void refreshBeanFactory();

    @Override
    public abstract ConfigurableListableBeanFactory getBeanFactory();

    @Override
    public void refresh() {
        synchronized (startupShutdownMonitor) {
            prepareRefresh();                                   // 1
            ConfigurableListableBeanFactory bf = obtainFreshBeanFactory(); // 2
            prepareBeanFactory(bf);                             // 3
            try {
                postProcessBeanFactory(bf);                     // 4
                invokeBeanFactoryPostProcessors(bf);            // 5
                registerBeanPostProcessors(bf);                 // 6
                finishBeanFactoryInitialization(bf);            // 7
                finishRefresh();                                // 8
                active = true;
            } catch (RuntimeException ex) {
                destroyBeans();
                cancelRefresh(ex);
                throw ex;
            }
        }
    }
    @Override
    public void close() {
        synchronized (startupShutdownMonitor) {
            if (!active) {
                // refresh 안 했거나 이미 close 호출됨 — idempotent
                if (shutdownHook != null) {
                    tryRemoveShutdownHook();
                }
                return;
            }
            doClose();
            if (shutdownHook != null) {
                tryRemoveShutdownHook();
            }
        }
    }

    @Override
    public void registerShutdownHook() {
        synchronized (startupShutdownMonitor) {
            if (shutdownHook != null) return;  // idempotent
            shutdownHook = new Thread(
                    () -> {
                        synchronized (startupShutdownMonitor) {
                            if (active) doClose();
                        }
                    },
                    "sfs-context-shutdown"
            );
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
    }

    private void tryRemoveShutdownHook() {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignore) {
            // JVM shutdown 진행 중이면 정상 — 무시
        }
        shutdownHook = null;
    }

    private void doClose() {
        active = false;
        destroyBeans();
    }

    @Override public Object getBean(String name) { return getBeanFactory().getBean(name); }
    @Override public <T> T getBean(String name, Class<T> requiredType) { return getBeanFactory().getBean(name, requiredType); }
    @Override public <T> T getBean(Class<T> requiredType) { return getBeanFactory().getBean(requiredType); }
    @Override public boolean containsBean(String name) { return getBeanFactory().containsBean(name); }
    @Override public boolean isSingleton(String name) { return getBeanFactory().isSingleton(name); }
    @Override public boolean isPrototype(String name) { return getBeanFactory().isPrototype(name); }
    @Override public Class<?> getType(String name) { return getBeanFactory().getType(name); }

    protected void prepareRefresh() { startupDate = System.currentTimeMillis(); }
    protected ConfigurableListableBeanFactory obtainFreshBeanFactory() {
        refreshBeanFactory();
        return getBeanFactory();
    }
    protected void prepareBeanFactory(ConfigurableListableBeanFactory bf) { /* no-op (확장점) */ }
    protected void postProcessBeanFactory(ConfigurableListableBeanFactory bf) { /* no-op (서브클래스 hook) */ }
    protected void invokeBeanFactoryPostProcessors(ConfigurableListableBeanFactory bf) {
        for (BeanFactoryPostProcessor bfpp : bfpps) bfpp.postProcessBeanFactory(bf);
    }
    protected void registerBeanPostProcessors(ConfigurableListableBeanFactory bf) { /* no-op (확장점) */ }
    protected void finishBeanFactoryInitialization(ConfigurableListableBeanFactory bf) {
        bf.preInstantiateSingletons();
    }
    protected void finishRefresh() { /* no-op (이벤트 발행 등은 후속 페이즈) */ }
    protected void cancelRefresh(RuntimeException ex) { active = false; }
    protected void destroyBeans() { getBeanFactory().destroySingletons(); }

}
