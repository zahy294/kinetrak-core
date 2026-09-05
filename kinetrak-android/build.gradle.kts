repositories {
    flatDir {
        dirs("libs")
    }
}

dependencies {
    // Qualcomm SNPE Java API
    implementation(name = "snpe-release", ext = "aar")

    // Google ARCore
    implementation("com.google.ar:core:1.54.0")
}