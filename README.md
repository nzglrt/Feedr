# Feedr (leitor RSS/podcast minimalista)

## Compilar no Linux Mint (4 GB)
sudo apt install openjdk-17-jdk unzip
# Instale as "command line tools" do Android SDK em ~/android-sdk, depois:
export ANDROID_HOME=~/android-sdk
sdkmanager "platforms;android-34" "build-tools;34.0.0"
echo "sdk.dir=$ANDROID_HOME" > local.properties
# Gere o wrapper do Gradle uma vez (precisa do gradle instalado, ex.: via SDKMAN):
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
