import java.security.MessageDigest
import java.util.Properties
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
val releaseSigningFile=rootProject.file(providers.gradleProperty("mmisSigningProperties").getOrElse("../.local/mmis-signing.properties"))
val releaseSigning=Properties().apply {if(releaseSigningFile.isFile) releaseSigningFile.inputStream().use {load(it)}}
android {
    namespace = "network.mmis.runtime"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = providers.gradleProperty("probeApplicationId").getOrElse("network.mmis.runtime")
        // Normal product floor, accepted on API29/30 emulators and API31/36 hardware.
        // Optional higher-floor laboratory build; never substitutes the native AAR.
        minSdk = providers.gradleProperty("probeMinSdk").map { value ->
            value.toInt().also { require(it in listOf(29,30,31,33)) { "Probe APIs: 29, 30, 31, 33 only" } }
        }.getOrElse(29)
        targetSdk = 36
        versionCode = 13
        versionName = "0.3.3"
        // Resource-only replacement point; used by asset/fallback acceptance builds.
        resValue("string", "launch_animation_resource", providers.gradleProperty("launchAsset").getOrElse("mmis_intro"))
        testInstrumentationRunner = "network.mmis.runtime.ProbeTests"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true; buildConfig = true }
    testBuildType = providers.gradleProperty("probeTestBuildType").getOrElse("debug")
    signingConfigs {
        if(releaseSigningFile.isFile) create("production") {
            storeFile=file(releaseSigning.getProperty("storeFile"))
            storePassword=releaseSigning.getProperty("storePassword")
            keyAlias=releaseSigning.getProperty("keyAlias")
            keyPassword=releaseSigning.getProperty("keyPassword")
            storeType="PKCS12"
        }
    }
    buildTypes {
        getByName("debug") { buildConfigField("String", "REGISTRY_PROFILE", "\"Lab\""); versionNameSuffix="-lab" }
        getByName("release") {
            versionNameSuffix="-demo"
            buildConfigField("String", "REGISTRY_PROFILE", "\"Release\"")
            signingConfig=signingConfigs.findByName("production")
            isDebuggable=false
        }
        create("hackathon") {
            initWith(getByName("release"))
            buildConfigField("String", "REGISTRY_PROFILE", "\"Release\"")
            versionNameSuffix="-demo"
            matchingFallbacks.add("release")
        }
        create("migration") {
            initWith(getByName("debug"))
            buildConfigField("String", "REGISTRY_PROFILE", "\"Release\"")
            versionNameSuffix="-migration"
            matchingFallbacks.add("debug")
        }
    }
    sourceSets.getByName("migration").res.srcDir("src/debug/res")
    sourceSets.getByName("test").resources.srcDirs("../../docs/fixtures")
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
val verifyAar by tasks.registering {
    doLast {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(file("libs/bindings.aar").readBytes()).joinToString("") { "%02x".format(it) }
        check(digest == "e2fca235fa31af4024acbf1ec7a41839aa9b910cd1ecd74b689fb9ee99eebcb3") { "Research 01B AAR hash mismatch" }
    }
}
val verifyRegistryProfiles by tasks.registering {
    doLast {
        for (network in listOf("devnet", "mainnet")) {
            val publicConfig=groovy.json.JsonSlurper().parse(file("../../registry/deployments/$network.json")) as Map<*,*>
            val asset=groovy.json.JsonSlurper().parse(file("src/main/assets/registry-$network.json")) as Map<*,*>
            for (key in listOf("chain","genesisHash","programId","protocolVersion")) check(asset[key]==publicConfig[key]) {"Registry asset mismatch: $network/$key"}
            check(asset.keys.all {it in listOf("chain","genesisHash","programId","protocolVersion","rpc")}) {"Unexpected registry asset field"}
            check(asset["rpc"]==null || asset["rpc"]=="https://api.devnet.solana.com") {"Private RPC must not be packaged"}
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyAar,verifyRegistryProfiles) }
val verifyProductionSigning by tasks.registering {
    doLast {
        check(releaseSigningFile.isFile) {"Production signing requires ignored .local/mmis-signing.properties. Debug does not."}
        check(listOf("storeFile","storePassword","keyAlias","keyPassword").all {!releaseSigning.getProperty(it).isNullOrBlank()}) {"Incomplete local signing configuration"}
        check(file(releaseSigning.getProperty("storeFile")).isFile) {"Configured release keystore is unavailable"}
        check(android.defaultConfig.applicationId=="network.mmis.runtime" && android.defaultConfig.minSdk==29) {"Production package/minSdk are frozen"}
    }
}
tasks.matching {it.name in setOf("preReleaseBuild","preHackathonBuild")}.configureEach {dependsOn(verifyProductionSigning)}
dependencies {
    implementation("com.airbnb.android:lottie-compose:6.7.1")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation(files("libs/bindings.aar"))
    implementation("com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.0.7")
    implementation("com.solanamobile:web3-solana:0.3.0-beta4")
    implementation("io.github.funkatronics:multimult:0.2.3")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
