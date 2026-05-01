plugins {
    `java-library`
}

dependencies {
    implementation(project(":sfs-aop"))
    implementation(libs.bytebuddy)
}
