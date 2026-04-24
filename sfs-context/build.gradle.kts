plugins {
    `java-library`
}

dependencies {
    api(project(":sfs-beans"))
    implementation(libs.bytebuddy)  // 1B-α는 미사용, 1B-β의 ConfigurationClassEnhancer가 사용
}
