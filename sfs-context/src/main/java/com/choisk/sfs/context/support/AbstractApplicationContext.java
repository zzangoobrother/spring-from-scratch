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

    // refresh()/close() 본문은 Task 10/12에서 구현
    @Override public void refresh() { throw new UnsupportedOperationException("Task 10에서 구현"); }
    @Override public void close() { throw new UnsupportedOperationException("Task 12에서 구현"); }
    @Override public void registerShutdownHook() { throw new UnsupportedOperationException("Task 12에서 구현"); }

    // BeanFactory 위임 (1A 인터페이스 만족용 — 실제 구현은 getBeanFactory() 위임)
    @Override public Object getBean(String name) { return getBeanFactory().getBean(name); }
    @Override public <T> T getBean(String name, Class<T> requiredType) { return getBeanFactory().getBean(name, requiredType); }
    @Override public <T> T getBean(Class<T> requiredType) { return getBeanFactory().getBean(requiredType); }
    @Override public boolean containsBean(String name) { return getBeanFactory().containsBean(name); }
    @Override public boolean isSingleton(String name) { return getBeanFactory().isSingleton(name); }
    @Override public boolean isPrototype(String name) { return getBeanFactory().isPrototype(name); }
    @Override public Class<?> getType(String name) { return getBeanFactory().getType(name); }

    // 내부 라이프사이클 보조 (Task 10/11/12에서 채움)
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
    protected void registerBeanPostProcessors(ConfigurableListableBeanFactory bf) {
        // 1B-α: no-op. 1B-β에서 BPP 자동 등록 추가 예정.
    }
    protected void finishBeanFactoryInitialization(ConfigurableListableBeanFactory bf) {
        bf.preInstantiateSingletons();
    }
    protected void finishRefresh() { /* no-op (이벤트 발행 등은 후속 페이즈) */ }
    protected void cancelRefresh(RuntimeException ex) { active = false; }
    protected void destroyBeans() { getBeanFactory().destroySingletons(); }

    // setter for active (Task 10에서 사용)
    protected void setActive(boolean active) { this.active = active; }
    protected Object getStartupShutdownMonitor() { return startupShutdownMonitor; }
    protected Thread getShutdownHook() { return shutdownHook; }
    protected void setShutdownHook(Thread t) { this.shutdownHook = t; }
}
