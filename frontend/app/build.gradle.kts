plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
 namespace = "de.ovgu.imiq.companion"
 compileSdk = 36
 defaultConfig { applicationId = "de.ovgu.imiq.companion"; minSdk = 26; targetSdk = 36; versionCode = 148; versionName = "0.4.137" }
 buildFeatures { compose = true; buildConfig = true }
 sourceSets.getByName("main").assets.srcDir("../../contracts")
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
 implementation(platform("androidx.compose:compose-bom:2025.10.00"))
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.ui:ui-tooling-preview")
 implementation("androidx.compose.foundation:foundation")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.activity:activity-compose:1.11.0")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
 implementation("org.maplibre.gl:android-sdk-opengl:13.5.3")
 implementation("com.google.android.gms:play-services-location:21.4.0")
 debugImplementation("androidx.compose.ui:ui-tooling")
}
