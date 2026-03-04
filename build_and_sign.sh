#!/bin/bash

# 1. Καθαρισμός και Build του Release APK
./gradlew clean assembleRelease

# 2. Ορισμός διαδρομών
UNSIGNED_APK="app/build/outputs/apk/release/app-release-unsigned.apk"
SIGNED_APK="Hotspot-SSH.apk"
KEYSTORE="my-release-key.jks"
SIGNER="/home/troikas/Android/Sdk/build-tools/34.0.0/apksigner"

# 3. Υπογραφή (θα σου ζητήσει τον κωδικό σου)
echo "Υπογραφή του APK..."
$SIGNER sign --ks $KEYSTORE --out $SIGNED_APK $UNSIGNED_APK

# 4. Έλεγχος
echo "Επαλήθευση..."
$SIGNER verify --verbose $SIGNED_APK

echo "Έτοιμο! Το αρχείο $SIGNED_APK δημιουργήθηκε."
