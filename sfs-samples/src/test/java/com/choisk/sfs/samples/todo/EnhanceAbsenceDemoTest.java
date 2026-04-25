package com.choisk.sfs.samples.todo;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class EnhanceAbsenceDemoTest {

    @Test
    void enhanceAbsenceShowsBothBranches() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        try {
            EnhanceAbsenceDemo.main(new String[0]);
        } finally {
            System.setOut(original);
        }

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertThat(output)
                .as("매개변수 라우팅 형태는 컨테이너 싱글톤이 라우팅되어 동일")
                .contains("Arg form (매개변수 라우팅): account.user == ctx.user → true");
        assertThat(output)
                .as("직접 호출 형태는 enhance 부재로 매번 새 인스턴스")
                .contains("Direct call (본문 호출, enhance 부재): account.user == ctx.user → false");
    }
}
