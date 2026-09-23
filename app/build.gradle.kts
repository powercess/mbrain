plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
android {
    ndkVersion = "28.2.13676358"
    namespace = "com.powercess.mbrain"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "com.powercess.mbrain"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 4
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    packaging.resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties")
    packaging.jniLibs.useLegacyPackaging = true
    packaging.jniLibs.keepDebugSymbols += "**/libfrpc.so"
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/frpc/jniLibs"))
}
val buildFrpc by tasks.registering(Exec::class) {
    val output = layout.buildDirectory.dir("generated/frpc/jniLibs")
    inputs.file(rootProject.file("scripts/build_frpc.py"))
    outputs.dir(output)
    val python = providers.environmentVariable("MBRAIN_PYTHON").orElse(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3")
    commandLine(python.get(), rootProject.file("scripts/build_frpc.py").absolutePath,
        "--ndk", android.sdkDirectory.resolve("ndk/${android.ndkVersion}").absolutePath,
        "--output", output.get().asFile.absolutePath,
        "--cache", rootProject.layout.buildDirectory.dir("frpc-source").get().asFile.absolutePath)
}
tasks.named("preBuild").configure { dependsOn(buildFrpc) }
dependencies {
    implementation(project(":droid-mcp-core"))
    implementation(project(":droid-mcp-device"))
    implementation(project(":droid-mcp-server-service"))
    implementation(project(":droid-mcp-root"))
    implementation(project(":droid-mcp-shizuku"))
    implementation(project(":droid-mcp-apps"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform("androidx.compose:compose-bom:2025.03.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material:material-icons-extended")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
