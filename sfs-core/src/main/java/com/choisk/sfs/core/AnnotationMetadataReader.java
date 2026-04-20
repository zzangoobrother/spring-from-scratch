package com.choisk.sfs.core;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 바이트코드(byte[])에서 ASM으로 애노테이션 메타데이터를 추출한다.
 * <p>클래스 로드 없이 동작 — {@code static} 초기화 블록이 트리거되지 않는다.
 * <p>Spring 원본: {@code org.springframework.core.type.classreading.SimpleMetadataReader}.
 */
public final class AnnotationMetadataReader {

    private AnnotationMetadataReader() {}

    public static AnnotationMetadata read(byte[] bytecode) {
        Assert.notNull(bytecode, "bytecode");
        var visitor = new MetadataClassVisitor();
        new ClassReader(bytecode).accept(
                visitor,
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return visitor.build();
    }

    private static final class MetadataClassVisitor extends ClassVisitor {

        String className;
        String superName;
        List<String> interfaces = List.of();
        Set<String> annotationTypes = new HashSet<>();
        Map<String, Map<String, Object>> annotationAttrs = new HashMap<>();
        boolean isAbstract;
        boolean isInterface;
        boolean isAnnotation;

        MetadataClassVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            this.className = Type.getObjectType(name).getClassName();
            this.superName = superName != null ? Type.getObjectType(superName).getClassName() : null;
            this.interfaces = interfaces == null
                    ? List.of()
                    : Arrays.stream(interfaces)
                            .map(i -> Type.getObjectType(i).getClassName())
                            .toList();
            this.isAbstract = (access & Opcodes.ACC_ABSTRACT) != 0;
            this.isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
            this.isAnnotation = (access & Opcodes.ACC_ANNOTATION) != 0;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            String annotationName = Type.getType(descriptor).getClassName();
            annotationTypes.add(annotationName);
            var attrs = new LinkedHashMap<String, Object>();
            annotationAttrs.put(annotationName, attrs);
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String name, Object value) {
                    attrs.put(name, value);
                }

                @Override
                public void visitEnum(String name, String descriptor, String value) {
                    attrs.put(name, value);
                }
            };
        }

        AnnotationMetadata build() {
            return new AnnotationMetadata(
                    className, superName, interfaces,
                    Set.copyOf(annotationTypes),
                    Map.copyOf(annotationAttrs),
                    isAbstract, isInterface, isAnnotation
            );
        }
    }
}
