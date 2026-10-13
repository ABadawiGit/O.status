plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace="com.catch7ng.ostatus"
 compileSdk=35
 defaultConfig {
  applicationId="com.cameraring.preview"
  minSdk=29
  targetSdk=35
  versionCode = 44
  versionName = "3.1.0-preview"
 }
 compileOptions {
  sourceCompatibility=JavaVersion.VERSION_17
  targetCompatibility=JavaVersion.VERSION_17
 }
 kotlinOptions { jvmTarget="17" }
 lint {
  checkReleaseBuilds = false
 }
}
dependencies {
 implementation("androidx.core:core-ktx:1.15.0")
 implementation("androidx.appcompat:appcompat:1.7.0")
}
