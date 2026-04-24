/**
 * 애노테이션 기반 ApplicationContext 계층.
 *
 * <p>Spring 원본 매핑: {@code spring-context}. {@code @Component}/{@code @Configuration}/
 * {@code @Bean}/{@code @Autowired} 등 메타데이터 처리와 라이프사이클(refresh/close) 책임.
 *
 * <p>이 모듈은 {@code sfs-beans} 위에서 작동한다 (의존 그래프: context → beans → core).
 */
package com.choisk.sfs.context;
