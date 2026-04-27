plugins {
    `java-library`
}

dependencies {
    implementation(project(":sfs-context"))
    implementation(libs.bytebuddy)
}
