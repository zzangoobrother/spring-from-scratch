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
        // @Before advice 출력 1개 — complete 호출 직전, args=[1] (Long 1L → Arrays.toString → "[1]")
        assertThat(lines)
                .filteredOn(l -> l.startsWith("[Before] complete 호출") && l.contains("args="))
                .hasSize(1);
        // @After advice 출력 1개 — finally 블록에서 호출, 정상/예외 종료 모두 보장
        assertThat(lines)
                .filteredOn(l -> l.equals("[After] complete 종료"))
                .hasSize(1);
        // 호출 순서 박제: [Before] → 진짜 메서드 → [After](finally) → [Around] 종료
        assertThat(lines).containsSubsequence(
                "[Before] complete 호출 — args=[1]",
                "[TodoController] Todo 1 completed",
                "[After] complete 종료"
        );

        // [After] 다음에 [Around 종료] 순서 박제 (spec § 4.2 합성 순서)
        // [Around id=N] complete 실행 시간 X ms — 동적 id/시간 때문에 인덱스 비교로 순서 검증
        java.util.List<String> lineList = java.util.Arrays.asList(lines);
        int afterIdx = lineList.indexOf("[After] complete 종료");
        int aroundEndIdx = -1;
        for (int i = 0; i < lineList.size(); i++) {
            String line = lineList.get(i);
            if (line.startsWith("[Around id=") && line.contains("complete 실행 시간")) {
                aroundEndIdx = i;
                break;
            }
        }
        assertThat(aroundEndIdx)
                .as("[Around id=...] complete 실행 시간 라인 존재")
                .isGreaterThanOrEqualTo(0);
        assertThat(aroundEndIdx)
                .as("[After] 다음에 [Around 종료]가 와야 함 (spec § 4.2 합성 순서)")
                .isGreaterThan(afterIdx);
    }
}
