package com.choisk.sfs.samples.todo;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationEnhanceDemoTest {

    @Test
    void configurationEnhanceMakesBothFormsConsistent() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        try {
            ConfigurationEnhanceDemo.main(new String[0]);
        } finally {
            System.setOut(original);
        }

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertThat(output)
                .as("매개변수 라우팅 형태는 컨테이너 싱글톤이 라우팅되어 동일")
                .contains("Arg form (매개변수 라우팅): account.user == ctx.user → true");
        assertThat(output)
                .as("Phase 2A enhance 적용으로 직접 호출 형태도 컨테이너 라우팅됨 (Phase 1C 박제 false → true 회수)")
                .contains("Direct call (본문 호출, enhance 적용): account.user == ctx.user → true");
    }
}
