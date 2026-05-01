plugins {
    `java-library`
}

dependencies {
    implementation(project(":sfs-aop"))
    implementation(project(":sfs-beans"))
    implementation(libs.bytebuddy)

    testImplementation(libs.h2)
}
