plugins {
    `java-library`
}

dependencies {
    implementation(project(":sfs-tx"))
    implementation(libs.bytebuddy)

    testImplementation(libs.h2)
    testImplementation(project(":sfs-context"))   // 통합 테스트 한정
}
