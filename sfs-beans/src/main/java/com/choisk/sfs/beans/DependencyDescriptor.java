package com.choisk.sfs.beans;

/**
 * 의존 주입 요청을 서술하는 단순판 디스크립터.
 * 타입, required 여부, 필드/파라미터 이름을 캡슐화한다.
 * 제네릭 추출(ResolvableType)은 보류 — 컬렉션 주입을 이 학습 범위에서 다루지 않음.
 */
public class DependencyDescriptor {
    private final Class<?> dependencyType;
    private final boolean required;
    private final String dependencyName;

    public DependencyDescriptor(Class<?> dependencyType, boolean required, String dependencyName) {
        this.dependencyType = dependencyType;
        this.required = required;
        this.dependencyName = dependencyName;
    }

    public Class<?> getDependencyType() { return dependencyType; }
    public boolean isRequired() { return required; }
    public String getDependencyName() { return dependencyName; }
}
