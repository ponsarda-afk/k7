// Prépare le projet Android généré par « npx cap add android » :
// lecteur natif, permissions, icônes, écran de démarrage, signature, numéro de version.
const fs = require("fs");
const path = require("path");

const A = path.join(__dirname, "android", "app");
const MAIN = path.join(A, "src", "main");
const RES = path.join(MAIN, "res");
const must = (cond, msg) => { if (!cond) { console.error("prepare.cjs : " + msg); process.exit(1); } };
const patch = (file, from, to) => {
  const s = fs.readFileSync(file, "utf8");
  must(from instanceof RegExp ? from.test(s) : s.includes(from), `motif introuvable dans ${path.basename(file)} : ${from}`);
  fs.writeFileSync(file, s.replace(from, to));
};

// 1. code Java (le paquet est déduit de appId)
const pkg = path.join(MAIN, "java", "fr", "kawaiiapp", "k7audiob");
fs.mkdirSync(pkg, { recursive: true });
for (const f of ["MainActivity.java", "K7Player.java", "K7PlaybackService.java"]) fs.copyFileSync(path.join(__dirname, f), path.join(pkg, f));

// 2. manifeste : permissions + service de lecture
patch(path.join(MAIN, "AndroidManifest.xml"), '<uses-permission android:name="android.permission.INTERNET" />', `<uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />`);
patch(path.join(MAIN, "AndroidManifest.xml"), "</application>", `    <service
            android:name=".K7PlaybackService"
            android:foregroundServiceType="mediaPlayback"
            android:exported="true">
            <intent-filter>
                <action android:name="androidx.media3.session.MediaSessionService" />
            </intent-filter>
        </service>
    </application>`);

// 3. build.gradle : Media3, signature, version
const G = path.join(A, "build.gradle");
patch(G, /dependencies\s*\{/, `dependencies {
    implementation "androidx.media3:media3-exoplayer:1.5.1"
    implementation "androidx.media3:media3-session:1.5.1"
    implementation "com.google.guava:guava:33.3.1-android"`);
patch(G, /versionCode\s+\d+/, `versionCode((System.getenv("K7_VC") ?: "1").toInteger())`);
patch(G, /versionName\s+"[^"]*"/, `versionName("1." + (System.getenv("K7_VC") ?: "0"))`);
if (fs.existsSync(path.join(A, "k7.jks"))) {
  patch(G, /android\s*\{/, `android {
    signingConfigs {
        release {
            storeFile file("k7.jks")
            storePassword System.getenv("K7_KS_PASS")
            keyAlias "k7"
            keyPassword System.getenv("K7_KS_PASS")
        }
    }`);
  patch(G, /buildTypes\s*\{\s*release\s*\{/, `buildTypes {
        release {
            signingConfig signingConfigs.release`);
} else console.warn("prepare.cjs : pas de k7.jks, APK non signé");

// 4. icônes : adaptative (cassette + CapyCool sur fond sombre) + classique
for (const d of ["mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"]) {
  const dir = path.join(RES, "mipmap-" + d);
  fs.mkdirSync(dir, { recursive: true });
  fs.copyFileSync(path.join(__dirname, `fg-${d}.png`), path.join(dir, "ic_launcher_foreground.png"));
  fs.copyFileSync(path.join(__dirname, `ic-${d}.png`), path.join(dir, "ic_launcher.png"));
  fs.copyFileSync(path.join(__dirname, `ic-${d}.png`), path.join(dir, "ic_launcher_round.png"));
}
fs.writeFileSync(path.join(RES, "values", "ic_launcher_background.xml"),
  `<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">#14161F</color>\n</resources>\n`);
// le gabarit a un premier plan vectoriel (logo Capacitor) qui masquerait le nôtre
for (const f of ["drawable-v24/ic_launcher_foreground.xml", "drawable/ic_launcher_foreground.xml"]) {
  const p = path.join(RES, f); if (fs.existsSync(p)) fs.unlinkSync(p);
}

// 5. icône de notification (silhouette de cassette)
fs.mkdirSync(path.join(RES, "drawable"), { recursive: true });
fs.copyFileSync(path.join(__dirname, "media3_notification_small_icon.xml"), path.join(RES, "drawable", "media3_notification_small_icon.xml"));

// 6. écran de démarrage sombre avec le logo, au lieu de l'écran Capacitor
for (const d of fs.readdirSync(RES)) {
  const p = path.join(RES, d, "splash.png"); if (fs.existsSync(p)) fs.unlinkSync(p);
}
fs.writeFileSync(path.join(RES, "drawable", "splash.xml"), `<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:drawable="@color/ic_launcher_background" />
    <item android:width="200dp" android:height="200dp" android:gravity="center" android:drawable="@mipmap/ic_launcher_foreground" />
</layer-list>
`);
patch(path.join(RES, "values", "styles.xml"), '<item name="android:background">@drawable/splash</item>',
  `<item name="android:background">@drawable/splash</item>
        <item name="windowSplashScreenBackground">@color/ic_launcher_background</item>`);

console.log("prepare.cjs : projet Android prêt");
