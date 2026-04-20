package com.choisk.sfs.core;

import java.util.List;

public final class NoUniqueBeanDefinitionException extends BeansException {

    private final List<String> candidates;

    public NoUniqueBeanDefinitionException(String message, List<String> candidates) {
        super(message);
        this.candidates = List.copyOf(candidates);
    }

    public List<String> getCandidates() {
        return candidates;
    }
}
