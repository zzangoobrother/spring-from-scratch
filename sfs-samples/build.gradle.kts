plugins {
    `java-library`
}

dependencies {
    implementation(project(":sfs-context"))
    implementation(project(":sfs-aop"))
    implementation(project(":sfs-tx"))
    implementation(libs.h2)
    testImplementation(libs.h2)
}
