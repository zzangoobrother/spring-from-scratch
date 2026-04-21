package com.choisk.sfs.beans;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;

public final class PropertyValues {
    private final LinkedHashMap<String, PropertyValue> values = new LinkedHashMap<>();

    public PropertyValues add(PropertyValue pv) {
        values.put(pv.name(), pv);
        return this;
    }

    public PropertyValue get(String name) {
        return values.get(name);
    }

    public Collection<PropertyValue> all() {
        return Collections.unmodifiableCollection(values.values());
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }
}
