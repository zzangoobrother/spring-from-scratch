package com.choisk.sfs.beans;

/**
 * 빈 스코프. Phase 1은 singleton/prototype 두 종류만.
 * Request/Session 같은 웹 스코프는 Phase 5+에서 sealed hierarchy에 추가.
 */
public sealed interface Scope permits Scope.Singleton, Scope.Prototype {

    /** 스코프 식별자 이름을 반환한다. */
    String scopeName();

    enum Singleton implements Scope {
        INSTANCE;
        @Override public String scopeName() { return "singleton"; }
    }

    enum Prototype implements Scope {
        INSTANCE;
        @Override public String scopeName() { return "prototype"; }
    }

    static Scope byName(String name) {
        return switch (name) {
            case "singleton" -> Singleton.INSTANCE;
            case "prototype" -> Prototype.INSTANCE;
            default -> throw new IllegalArgumentException("Unknown scope: " + name);
        };
    }
}
