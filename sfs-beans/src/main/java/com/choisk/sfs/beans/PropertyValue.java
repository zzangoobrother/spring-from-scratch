package com.choisk.sfs.beans;

import com.choisk.sfs.core.Assert;

public record PropertyValue(String name, Object value) {
    public PropertyValue {
        Assert.hasText(name, "name");
    }
}
