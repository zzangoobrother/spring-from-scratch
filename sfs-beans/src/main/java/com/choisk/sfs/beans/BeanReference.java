package com.choisk.sfs.beans;

import com.choisk.sfs.core.Assert;

public record BeanReference(String beanName) {
    public BeanReference {
        Assert.hasText(beanName, "beanName");
    }
}
