package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanFactoryPostProcessor;
import com.choisk.sfs.beans.BeanPostProcessor;
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

    @Override
    public List<BeanFactoryPostProcessor> getBeanFactoryPostProcessors() {
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
                tryRemoveShutdownHook();
                return;
            }
            doClose();
            tryRemoveShutdownHook();
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
        if (shutdownHook == null) return;
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
    /**
     * BeanDefinition 중 {@link BeanPostProcessor} 구현체를 *우선 생성*하여 BeanFactory의 BPP 체인에 등록.
     * <p>이 단계에서 BPP를 먼저 생성하는 이유: {@link #finishBeanFactoryInitialization} 시점(일반 빈 생성) 전에
     * BPP 체인이 완성되어야 모든 일반 빈에 후처리가 적용된다 (Spring 본가 패턴 동일).
     */
    protected void registerBeanPostProcessors(ConfigurableListableBeanFactory bf) {
        String[] bppNames = bf.getBeanNamesForType(BeanPostProcessor.class);
        for (String name : bppNames) {
            Object bpp = bf.getBean(name);
            // contains 체크: addBeanPostProcessor는 remove+add이므로 이미 등록된 BPP를 재추가하면 리스트 끝으로 이동해 순서 변경 발생
            if (!bf.getBeanPostProcessors().contains(bpp)) {
                bf.addBeanPostProcessor((BeanPostProcessor) bpp);
            }
        }
    }
    protected void finishBeanFactoryInitialization(ConfigurableListableBeanFactory bf) {
        bf.preInstantiateSingletons();
    }
    protected void finishRefresh() { /* no-op (이벤트 발행 등은 후속 페이즈) */ }
    protected void cancelRefresh(RuntimeException ex) { /* no-op: active=true는 try 마지막에만 설정 — catch 진입 시 active는 이미 false (서브클래스 hook) */ }
    protected void destroyBeans() { getBeanFactory().destroySingletons(); }

}
