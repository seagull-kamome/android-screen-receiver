plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "jp.kamome.screenreceiver"
    compileSdk = 35
    buildToolsVersion = "34.0.0"
    defaultConfig {
        applicationId = "jp.kamome.screenreceiver"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// 送信用のスクリプト（sender.js）と、Chrome 拡張機能（extension.zip）を、アプリの assets に入れる。
// 元は extension/ の 1 か所にあり、Android が配信するページと、拡張機能で、同じスクリプトを使う
val webAssets = layout.buildDirectory.dir("generated/web-assets")
val syncSenderJs = tasks.register<Copy>("syncSenderJs") {
    from(rootProject.file("extension/sender.js"))
    into(webAssets.map { it.dir("js") })
}
val zipExtension = tasks.register<Zip>("zipExtension") {
    from(rootProject.file("extension"))
    archiveFileName.set("extension.zip")
    destinationDirectory.set(webAssets.map { it.dir("zip") })
}
android.sourceSets.getByName("main").assets.srcDir(webAssets.map { it.dir("js") })
android.sourceSets.getByName("main").assets.srcDir(webAssets.map { it.dir("zip") })
tasks.named("preBuild") { dependsOn(syncSenderJs, zipExtension) }

dependencies {
    // HTTP（送信ページの配信）と WebSocket（映像の受信）を同じポートで扱う小さなサーバー
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
    // QR コードの生成（待機画面に、送信ページの URL を QR で表示する）
    implementation("com.google.zxing:core:3.5.3")
    // 共有メニュー（近距離共有など）へ、拡張機能の zip を渡すための FileProvider
    implementation("androidx.core:core:1.13.1")
}
