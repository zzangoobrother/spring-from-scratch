package com.choisk.sfs.context;

import com.choisk.sfs.beans.BeanFactory;

/**
 * BeanFactory의 상위 추상화. 1B-α 시점에는 메타 정보 + 식별자 정도만 노출하고,
 * Environment/MessageSource/Resource 등은 후속 페이즈에서 추가.
 *
 * <p>Spring 원본: {@code org.springframework.context.ApplicationContext}.
 */
public interface ApplicationContext extends BeanFactory {
    String getId();
    String getApplicationName();
    long getStartupDate();
}
