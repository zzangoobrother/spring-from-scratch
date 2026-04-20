package com.choisk.sfs.core;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 클래스패스에서 특정 패키지 하위 {@code .class} 파일을 나열한다.
 * <p>클래스 로드 없이 파일 경로와 바이트코드만 반환하므로, 애노테이션 판단은
 * {@link AnnotationMetadataReader} (Task 8)로 위임한다.
 *
 * <p>Phase 1은 file URL만 지원 (exploded classpath). JAR 지원은 필요 시 별도 태스크.
 */
public final class ClassPathScanner {

    public record ClassInfo(String className, byte[] bytecode) {}

    public List<ClassInfo> scan(String basePackage) {
        Assert.hasText(basePackage, "basePackage");
        String path = basePackage.replace('.', '/');
        ClassLoader loader = ClassUtils.getDefaultClassLoader();
        var results = new ArrayList<ClassInfo>();
        try {
            Enumeration<URL> roots = loader.getResources(path);
            while (roots.hasMoreElements()) {
                URL root = roots.nextElement();
                scanRoot(root, basePackage, results);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan: " + basePackage, e);
        }
        return results;
    }

    private void scanRoot(URL root, String basePackage, List<ClassInfo> out) throws IOException {
        String protocol = root.getProtocol();
        if (!"file".equals(protocol)) {
            // JAR 등은 Phase 1 범위 제외.
            return;
        }
        Path dir = Paths.get(root.getPath().replace("%20", " "));
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.walk(dir)) {
            var classFiles = stream
                    .filter(p -> p.toString().endsWith(".class"))
                    .collect(Collectors.toList());
            for (Path classFile : classFiles) {
                String relative = dir.relativize(classFile).toString()
                        .replace('/', '.').replace('\\', '.');
                String className = basePackage + '.'
                        + relative.substring(0, relative.length() - ".class".length());
                byte[] bytes = Files.readAllBytes(classFile);
                out.add(new ClassInfo(className, bytes));
            }
        }
    }
}
