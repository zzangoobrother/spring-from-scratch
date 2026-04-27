plugins {
    `java-library`
}

dependencies {
    api(project(":sfs-context"))
    implementation(libs.bytebuddy)
}
