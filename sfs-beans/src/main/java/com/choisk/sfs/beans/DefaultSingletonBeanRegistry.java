package com.choisk.sfs.beans;

import com.choisk.sfs.core.Assert;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultSingletonBeanRegistry {

    /** 1차: 완성된 싱글톤. */
    protected final Map<String, Object> singletonObjects = new ConcurrentHashMap<>();
    /** 2차: 조기 노출된 참조 (AOP 프록시가 씌워졌을 수도 있음). */
    protected final Map<String, Object> earlySingletonObjects = new ConcurrentHashMap<>();
    /** 3차: 조기 참조 팩토리 (getEarlyBeanReference 훅 포함). */
    protected final Map<String, ObjectFactory<?>> singletonFactories = new ConcurrentHashMap<>();

    private final Object singletonLock = new Object();

    public void registerSingleton(String name, Object bean) {
        Assert.hasText(name, "name");
        Assert.notNull(bean, "bean");
        synchronized (singletonLock) {
            Object existing = singletonObjects.get(name);
            if (existing != null) {
                throw new IllegalStateException(
                        "Singleton '%s' already exists (%s)".formatted(name, existing.getClass().getName()));
            }
            singletonObjects.put(name, bean);
            earlySingletonObjects.remove(name);
            singletonFactories.remove(name);
            // DisposableBean 감지를 같은 lock 안에서 처리 — singletonObjects.put과 disposableBeans.put이
            // 분리된 critical section에 있으면 그 사이에 destroySingletons이 끼어들어 destroy 콜백 누락 가능.
            // atomic하게 등록함으로써 race condition 제거.
            if (bean instanceof DisposableBean disposable) {
                disposableBeans.put(name, () -> {
                    try {
                        disposable.destroy();
                    } catch (Exception e) {
                        // Runnable이 체크 예외를 허용하지 않아 어댑팅 — destroySingletons의 개별 실패 격리 정책과 맞물림
                        System.err.println("[sfs-beans] DisposableBean.destroy failed for '" + name + "': " + e);
                    }
                });
            }
        }
    }

    public void registerSingletonFactory(String name, ObjectFactory<?> factory) {
        Assert.hasText(name, "name");
        Assert.notNull(factory, "factory");
        synchronized (singletonLock) {
            if (!singletonObjects.containsKey(name)) {
                singletonFactories.put(name, factory);
                earlySingletonObjects.remove(name);
            }
        }
    }

    /**
     * 3-level 조회를 sealed result로 반환. 호출자는 switch pattern matching.
     * <p>3차 hit 시에도 여기서는 <b>승격하지 않음</b> - 승격 시점은 {@link #promoteToEarlyReference} 호출에서 명시적으로 결정.
     * 이렇게 나눈 이유는 호출자(AbstractBeanFactory)가 SmartBPP 체인 실행 여부를 제어할 수 있어야 하기 때문.
     */
    public CacheLookup lookup(String name) {
        Object complete = singletonObjects.get(name);
        if (complete != null) return new CacheLookup.Complete(complete);

        Object early = earlySingletonObjects.get(name);
        if (early != null) return new CacheLookup.EarlyReference(early);

        ObjectFactory<?> factory = singletonFactories.get(name);
        if (factory != null) return new CacheLookup.DeferredFactory(factory);

        return CacheLookup.Miss.INSTANCE;
    }

    /**
     * 3차 → 2차 승격. factory를 실행하여 결과를 earlySingletonObjects에 저장하고 3차에서 제거.
     * <p>이 메서드는 <b>정확히 한 번만</b> factory를 실행해야 한다. 동시 호출 시 synchronized 보장.
     */
    public Object promoteToEarlyReference(String name) {
        synchronized (singletonLock) {
            Object existing = earlySingletonObjects.get(name);
            if (existing != null) return existing;

            ObjectFactory<?> factory = singletonFactories.get(name);
            if (factory == null) {
                throw new IllegalStateException("No singleton factory registered for: " + name);
            }
            Object produced = factory.getObject();
            earlySingletonObjects.put(name, produced);
            singletonFactories.remove(name);
            return produced;
        }
    }

    /**
     * 1차/2차/3차 캐시 어디라도 빈이 등장하면 true 반환.
     * {@link com.choisk.sfs.context.support.BeanMethodInterceptor}가 인터셉트 분기 판단에 사용.
     *
     * <p>학습 범위 한계: 2차 캐시 hit 시 <em>조기 노출 참조</em>가 반환되므로
     * 순환 참조 시나리오에서 BeanMethodInterceptor가 미완성 빈을 반환할 가능성 존재.
     * 본 phase는 단방향 의존만 가정하므로 발생 안 함 — 후속 phase에서 박제 보강 가능.
     */
    public boolean containsSingleton(String name) {
        return singletonObjects.containsKey(name)
                || earlySingletonObjects.containsKey(name)
                || singletonFactories.containsKey(name);
    }

    public String[] getSingletonNames() {
        return singletonObjects.keySet().toArray(new String[0]);
    }

    public Object getSingleton(String name) {
        return singletonObjects.get(name);
    }

    /**
     * 1차 캐시 조회 + 미존재 시 factory 1회 실행 + 캐시 승격을 원자적으로.
     * <p>
     * {@link #beforeSingletonCreation}의 ThreadLocal은 <b>같은 스레드</b>의 재진입(생성자 순환)만 막는다.
     * 서로 다른 스레드가 동시에 같은 이름을 요청하면 {@code ThreadLocal}로는 막을 수 없으므로
     * {@code synchronized(singletonLock)} 안에서 check-then-act 시퀀스의 원자성을 보장한다.
     * <p>
     * 2차 캐시({@code earlySingletonObjects})에 조기 노출된 참조가 있으면 단일 인스턴스 보장을 위해
     * 그것을 1차로 승격한다 (순환 참조 시나리오에서 동일 인스턴스가 보장되어야 하기 때문).
     */
    public Object getOrCreateSingleton(String name, ObjectFactory<?> factory) {
        Assert.hasText(name, "name");
        Assert.notNull(factory, "factory");

        Object existing = singletonObjects.get(name);
        if (existing != null) return existing;

        synchronized (singletonLock) {
            existing = singletonObjects.get(name);
            if (existing != null) return existing;

            beforeSingletonCreation(name);
            Object created;
            try {
                created = factory.getObject();
            } finally {
                afterSingletonCreation(name);
            }

            // 2차에 조기 노출된 참조가 있으면 그것을 신뢰 (단일 인스턴스 보장).
            // early와 created가 다른 인스턴스이면 (BPP after enhance 등): early에 created 필드를 반영해 최신 상태로 동기화.
            // 이렇게 해야 순환 의존에서 early reference로 받은 빈이 최종 초기화 상태의 필드를 갖게 된다.
            Object early = earlySingletonObjects.get(name);
            Object toStore;
            if (early != null) {
                if (early != created) {
                    // enhanced proxy가 원본과 다른 인스턴스 — early에 created 필드 복사로 동기화
                    copyFieldsToEarlyReference(early, created);
                }
                toStore = early;
            } else {
                toStore = created;
            }
            singletonObjects.put(name, toStore);
            earlySingletonObjects.remove(name);
            singletonFactories.remove(name);
            return toStore;
        }
    }

    /**
     * early reference(2차 캐시)와 최종 created 인스턴스가 다를 때 (BPP enhance로 다른 인스턴스 생성된 경우),
     * created 인스턴스의 필드를 early reference에 복사해 최신 상태로 동기화한다.
     * <p>early는 순환 의존 측에 이미 주입된 인스턴스이므로 교체할 수 없다 — 필드만 업데이트.
     */
    private void copyFieldsToEarlyReference(Object early, Object created) {
        // created의 superclass 체인 전체를 순회 (enhanced 서브클래스의 부모 필드도 포함)
        Class<?> clazz = created.getClass().getSuperclass(); // ByteBuddy 서브클래스라면 부모가 원본 클래스
        if (clazz == null || clazz == Object.class) {
            clazz = created.getClass(); // 원본 클래스라면 그대로 사용
        }
        // early의 원본 클래스 체인과 created의 체인이 일치하는 지점까지 복사
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                try {
                    f.set(early, f.get(created));
                } catch (IllegalAccessException e) {
                    // 접근 불가 필드는 skip — final 필드 등
                }
            }
        }
    }

    /** 현재 스레드에서 생성 중인 빈 이름들 (생성자 순환 감지용). 순서 유지를 위해 LinkedHashSet. */
    private final ThreadLocal<LinkedHashSet<String>> currentlyInCreation =
            ThreadLocal.withInitial(LinkedHashSet::new);

    public void beforeSingletonCreation(String name) {
        if (!currentlyInCreation.get().add(name)) {
            throw new IllegalStateException(
                    "Bean '%s' is already being created in this thread — circular reference".formatted(name));
        }
    }

    public void afterSingletonCreation(String name) {
        currentlyInCreation.get().remove(name);
    }

    public boolean isCurrentlyInCreation(String name) {
        return currentlyInCreation.get().contains(name);
    }

    public List<String> getCurrentCreationChain() {
        return List.copyOf(currentlyInCreation.get());
    }

    /** destroy 콜백. 등록 순서의 역순으로 실행되도록 LinkedHashMap. */
    private final LinkedHashMap<String, Runnable> disposableBeans = new LinkedHashMap<>();

    public void registerDisposableBean(String name, Runnable callback) {
        synchronized (singletonLock) {
            disposableBeans.put(name, callback);
        }
    }

    public void destroySingletons() {
        String[] names;
        synchronized (singletonLock) {
            names = disposableBeans.keySet().toArray(new String[0]);
        }
        // 역순 실행 (LIFO)
        for (int i = names.length - 1; i >= 0; i--) {
            String name = names[i];
            Runnable callback;
            synchronized (singletonLock) {
                callback = disposableBeans.remove(name);
            }
            if (callback == null) continue;
            try {
                callback.run();
            } catch (Throwable t) {
                // 한 개 실패가 전체 destroy를 막지 않도록 로그만 (System.err 사용 - 로깅 프레임워크 의존 회피)
                System.err.println("[sfs-beans] Failed to destroy singleton '" + name + "': " + t);
            }
        }
        synchronized (singletonLock) {
            singletonObjects.clear();
            earlySingletonObjects.clear();
            singletonFactories.clear();
        }
    }
}
