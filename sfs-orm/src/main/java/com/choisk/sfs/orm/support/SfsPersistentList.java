package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.exception.SfsLazyInitializationException;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

/**
 * 컬렉션 lazy 발화 wrapper — Hibernate {@code PersistentBag} 동형 패턴.
 *
 * <p>spec § 3.4 정합 — byte-buddy 미사용, 직접 List&lt;T&gt; 구현. 모든 메서드가 lazy init trigger
 * (write-only optimization 미도입 — 학습 정점 집중).
 *
 * <p>학습 정점 ①: {@code initialize()}가 첫 메서드 호출 시점에 정확히 1회 SELECT 발생시킴.
 * size()/iterator()/get(0)/contains(x)/add(e) 어느 것이든 동일 시점에 발화.
 *
 * <p>학습 정점 ②: element들이 모두 identityMap에 등재(loader 책임) → 같은 PK는 같은 인스턴스 보장.
 */
public class SfsPersistentList<T> implements List<T> {

    private final Class<T> elementType;
    private final Object ownerPk;
    private final String joinColumnName;
    private final CollectionLoader loader;
    private final PersistenceContext context;
    /** null = 미초기화 상태. delegate != null 가드로 중복 loader 호출 방지. */
    private List<T> delegate;

    public SfsPersistentList(Class<T> elementType, Object ownerPk, String joinColumnName,
                              CollectionLoader loader, PersistenceContext context) {
        this.elementType = elementType;
        this.ownerPk = ownerPk;
        this.joinColumnName = joinColumnName;
        this.loader = loader;
        this.context = context;
    }

    /**
     * lazy init 1회 발화 — 모든 List 메서드의 첫 진입점.
     *
     * WHY: delegate != null 가드가 핵심 — 두 번째 호출부터는 loader를 재호출하지 않고 캐시를 사용.
     * context.isClosed() 검사를 먼저 해야 closed 상태에서 NullPointerException 대신
     * 의미 있는 예외(SfsLazyInitializationException)를 던질 수 있음.
     */
    private void initialize() {
        if (delegate != null) return;
        if (context.isClosed()) {
            // WHY: elementType.getSimpleName() + "#" + ownerPk 형식으로 어느 엔티티의 컬렉션인지 식별
            throw new SfsLazyInitializationException(
                    elementType.getSimpleName() + "#" + ownerPk + ".collection");
        }
        delegate = loader.loadCollection(elementType, joinColumnName, ownerPk, context);
    }

    /** 테스트 헬퍼 — 초기화 여부 확인용 (delegate null 여부로 판단). */
    public boolean isInitialized() { return delegate != null; }

    // ─── List<T> 위임 (모든 메서드가 initialize() 트리거) ─────────────────────
    @Override public int size() { initialize(); return delegate.size(); }
    @Override public boolean isEmpty() { initialize(); return delegate.isEmpty(); }
    @Override public boolean contains(Object o) { initialize(); return delegate.contains(o); }
    @Override public Iterator<T> iterator() { initialize(); return delegate.iterator(); }
    @Override public Object[] toArray() { initialize(); return delegate.toArray(); }
    @Override public <U> U[] toArray(U[] a) { initialize(); return delegate.toArray(a); }
    @Override public boolean add(T e) { initialize(); return delegate.add(e); }
    @Override public boolean remove(Object o) { initialize(); return delegate.remove(o); }
    @Override public boolean containsAll(Collection<?> c) { initialize(); return delegate.containsAll(c); }
    @Override public boolean addAll(Collection<? extends T> c) { initialize(); return delegate.addAll(c); }
    @Override public boolean addAll(int i, Collection<? extends T> c) { initialize(); return delegate.addAll(i, c); }
    @Override public boolean removeAll(Collection<?> c) { initialize(); return delegate.removeAll(c); }
    @Override public boolean retainAll(Collection<?> c) { initialize(); return delegate.retainAll(c); }
    @Override public void clear() { initialize(); delegate.clear(); }
    @Override public T get(int i) { initialize(); return delegate.get(i); }
    @Override public T set(int i, T e) { initialize(); return delegate.set(i, e); }
    @Override public void add(int i, T e) { initialize(); delegate.add(i, e); }
    @Override public T remove(int i) { initialize(); return delegate.remove(i); }
    @Override public int indexOf(Object o) { initialize(); return delegate.indexOf(o); }
    @Override public int lastIndexOf(Object o) { initialize(); return delegate.lastIndexOf(o); }
    @Override public ListIterator<T> listIterator() { initialize(); return delegate.listIterator(); }
    @Override public ListIterator<T> listIterator(int i) { initialize(); return delegate.listIterator(i); }
    @Override public List<T> subList(int from, int to) { initialize(); return delegate.subList(from, to); }
}
