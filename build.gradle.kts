plugins {
    java
}

allprojects {
    group = "com.choisk.sfs"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(listOf("-Xlint:all", "--enable-preview"))
        options.release = 25
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgs("--enable-preview")
    }

    dependencies {
        val catalog = rootProject.extensions
            .getByType<org.gradle.api.artifacts.VersionCatalogsExtension>()
            .named("libs")
        "testImplementation"(platform(catalog.findLibrary("junit-bom").get()))
        "testImplementation"(catalog.findLibrary("junit-jupiter").get())
        "testImplementation"(catalog.findLibrary("assertj-core").get())
        "testRuntimeOnly"(catalog.findLibrary("junit-platform-launcher").get())
    }
}
