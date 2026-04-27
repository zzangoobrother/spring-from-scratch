package com.choisk.sfs.samples.todo;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TodoDemoApplicationTest {

    @Test
    void demoSequenceProducesExpectedOutput() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        try {
            TodoDemoApplication.main(new String[0]);
        } finally {
            System.setOut(original);
        }

        String[] lines = stdout.toString(StandardCharsets.UTF_8).split("\\R");

        // 기존 8개 라인이 순서대로 등장 — advice 라인이 사이에 끼어도 OK
        assertThat(lines).containsSubsequence(
                "[UserService] @PostConstruct: 기본 사용자 시드 완료",
                "[UserController] User created: Alice (id=2)",
                "[TodoController] Todo created: id=1 owner=2 \"장보기\"",
                "[TodoController] Todo created: id=2 owner=2 \"운동\"",
                "[TodoController] Todos for owner 2: [id=1] 장보기 [TODO], [id=2] 운동 [TODO]",
                "[TodoController] Todo 1 completed",
                "[TodoController] Todos for owner 2: [id=1] 장보기 [DONE], [id=2] 운동 [TODO]",
                "[UserController] @PreDestroy: 2명 사용자 등록 상태로 종료"
        );
        // @Around advice 출력 1개 — TodoController.complete에 @Loggable 부착으로 생성
        assertThat(lines)
                .filteredOn(l -> l.startsWith("[Around id=") && l.contains("complete 실행 시간"))
                .hasSize(1);
    }
}
