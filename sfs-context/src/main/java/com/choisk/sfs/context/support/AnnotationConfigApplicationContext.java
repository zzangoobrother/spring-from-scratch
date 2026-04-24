package com.choisk.sfs.context.support;

/**
 * 애노테이션 기반 컨테이너 진입점. 두 가지 사용 패턴 지원:
 * <ul>
 *   <li>{@code new AnnotationConfigApplicationContext(MyConfig.class)} — 명시 등록 + refresh</li>
 *   <li>{@code new AnnotationConfigApplicationContext("com.example")} — 패키지 스캔 + refresh</li>
 * </ul>
 *
 * <p>Spring 원본: {@code AnnotationConfigApplicationContext}.
 */
public class AnnotationConfigApplicationContext extends GenericApplicationContext {

    private final AnnotatedBeanDefinitionReader reader;
    private final ClassPathBeanDefinitionScanner scanner;

    public AnnotationConfigApplicationContext() {
        this.reader = new AnnotatedBeanDefinitionReader(getBeanFactory());
        this.scanner = new ClassPathBeanDefinitionScanner(getBeanFactory());
        // 1B-β 첫 Task에서 처리기 3종 자동 등록 추가:
        //   addBeanFactoryPostProcessor(new ConfigurationClassPostProcessor());
        //   getBeanFactory().addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(getBeanFactory()));
        //   getBeanFactory().addBeanPostProcessor(new CommonAnnotationBeanPostProcessor(getBeanFactory()));
    }

    public AnnotationConfigApplicationContext(Class<?>... componentClasses) {
        this();
        register(componentClasses);
        refresh();
    }

    public AnnotationConfigApplicationContext(String... basePackages) {
        this();
        scan(basePackages);
        refresh();
    }

    public void register(Class<?>... componentClasses) {
        reader.register(componentClasses);
    }

    public int scan(String... basePackages) {
        return scanner.scan(basePackages);
    }
}
