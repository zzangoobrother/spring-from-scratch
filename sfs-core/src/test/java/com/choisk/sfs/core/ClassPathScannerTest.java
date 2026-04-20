package com.choisk.sfs.core;

import com.choisk.sfs.core.ClassPathScanner.ClassInfo;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ClassPathScannerTest {

    @Test
    void scanPackage_findsClassesInThisPackage() {
        var scanner = new ClassPathScanner();
        var classes = scanner.scan("com.choisk.sfs.core");
        assertThat(classes)
                .extracting(ClassInfo::className)
                .contains("com.choisk.sfs.core.Assert",
                          "com.choisk.sfs.core.BeansException");
    }

    @Test
    void scanPackage_emptyForNonexistent() {
        var scanner = new ClassPathScanner();
        assertThat(scanner.scan("com.nonexistent.pkg")).isEmpty();
    }
}
