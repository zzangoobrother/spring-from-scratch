// BeanFactoryAware.java
package com.choisk.sfs.beans;
public interface BeanFactoryAware extends Aware {
    void setBeanFactory(BeanFactory beanFactory);
}
