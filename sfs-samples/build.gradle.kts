plugins {
    `java-library`
}

dependencies {
    implementation(project(":sfs-context"))
    implementation(project(":sfs-aop"))
    implementation(project(":sfs-tx"))
    implementation(project(":sfs-orm"))
    implementation(libs.h2)
    testImplementation(libs.h2)
}

// Phase 4 ORM demo 실행 태스크 — application 플러그인 도입 없이 JavaExec로 실행
tasks.register<JavaExec>("ormDemo") {
    group = "demo"
    description = "Phase 4 ORM 학습 정점 7가지 console 시연"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.choisk.sfs.samples.orm.OrmDemoApplication")
}
