package com.choisk.sfs.beans;

import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.core.NoSuchBeanDefinitionException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DefaultListableBeanFactory.resolveDependency 단순판 검증.
 * <ul>
 *   <li>단일 매칭 빈 반환</li>
 *   <li>required=false 시 0매칭 → null</li>
 *   <li>required=true 시 0매칭 → NoSuchBeanDefinitionException</li>
 *   <li>다수 후보 → IllegalStateException (@Primary/@Qualifier 안내 메시지)</li>
 * </ul>
 */
class ResolveDependencyTest {

    static class Repo {}

    @Test
    void resolvesSingleMatch() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("repo", new Repo());
        Object result = bf.resolveDependency(new DependencyDescriptor(Repo.class, true, "repo"), null);
        assertThat(result).isInstanceOf(Repo.class);
    }

    @Test
    void requiredFalseReturnsNullWhenNoMatch() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        Object result = bf.resolveDependency(new DependencyDescriptor(Repo.class, false, "repo"), null);
        assertThat(result).isNull();
    }

    @Test
    void requiredTrueThrowsWhenNoMatch() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        assertThatThrownBy(() ->
                bf.resolveDependency(new DependencyDescriptor(Repo.class, true, "repo"), null))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
    }

    @Test
    void multipleCandidatesThrowExplicitlyWithLearningMessage() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("a", new Repo());
        bf.registerSingleton("b", new Repo());
        assertThatThrownBy(() ->
                bf.resolveDependency(new DependencyDescriptor(Repo.class, true, "repo"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@Primary")
                .hasMessageContaining("@Qualifier");
    }
}
