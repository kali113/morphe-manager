#!/usr/bin/env bash
set -euo pipefail

VERSION="$1"

echo "Preparing release v${VERSION}..."

# 1. Update the version in app/gradle.properties
sed -i "s/^version\s*=.*/version = ${VERSION}/" app/gradle.properties
echo "Updated app/gradle.properties to version ${VERSION}"

# 2. Build the release APK with the correct version baked in.
#    Stop any existing daemon so a fresh one picks up the updated gradle.properties.
chmod +x ./gradlew
./gradlew --stop || true
./gradlew assembleRelease releaseAbiApks --stacktrace

# 3. Find the built APK and rename it to the expected release name
RELEASE_DIR="app/build/outputs/apk/release"
APK_SRC=$(find "${RELEASE_DIR}" -name '*-release.apk' | head -1)

if [ -z "${APK_SRC}" ]; then
  echo "ERROR: No *-release.apk found in ${RELEASE_DIR}"
  ls -la "${RELEASE_DIR}" || true
  exit 1
fi

APK_DST="${RELEASE_DIR}/morphe-manager-${VERSION}.apk"
mv "${APK_SRC}" "${APK_DST}"
echo "Renamed APK to ${APK_DST}"

# 4. GPG-sign the APK
gpg --armor --detach-sign "${APK_DST}"
echo "Signed ${APK_DST}"

# 5. Name and sign the APK of each ABI. They wait in a folder of their own, out of the release
#    assets semantic-release uploads, as publish-release-json.sh uploads them once the universal
#    APK is live. A manager from before them updates to the first APK a release lists, and GitHub
#    lists assets by name, so they are named after the ABI first to sort behind the universal APK.
ABI_SRC_DIR="app/build/outputs/apk-abi/release"
ABI_DIR="${RELEASE_DIR}/abi"
mkdir -p "${ABI_DIR}"

ABIS=()
shopt -s nullglob
for ABI_SRC in "${ABI_SRC_DIR}"/*.apk; do
  ABI=$(basename "${ABI_SRC}" .apk)
  ABI_DST="${ABI_DIR}/morphe-manager-${ABI}-${VERSION}.apk"
  mv "${ABI_SRC}" "${ABI_DST}"
  gpg --armor --detach-sign "${ABI_DST}"
  ABIS+=("${ABI}")
  echo "Signed ${ABI_DST}"
done

if [ "${#ABIS[@]}" -eq 0 ]; then
  echo "ERROR: No per-ABI APKs found in ${ABI_SRC_DIR}"
  exit 1
fi

# GitHub sorts by name regardless of case. Should a rename ever put an APK per ABI first, a manager
# from before them would update to an APK only some devices install
FIRST_APK=$(for APK in "${APK_DST}" "${ABI_DIR}"/*.apk; do basename "${APK}"; done | LC_ALL=C sort -f | head -1)
if [ "${FIRST_APK}" != "$(basename "${APK_DST}")" ]; then
  echo "ERROR: ${FIRST_APK} sorts before the universal APK, which older managers would update to"
  exit 1
fi

# 6. List them in the release JSON, beside the universal APK it already points at, so the
#    in-app updater can download the one for its device
node - "app-release.json" "${ABIS[@]}" <<'EOF'
const fs = require('fs');
const [file, ...abis] = process.argv.slice(2);
const release = JSON.parse(fs.readFileSync(file, 'utf8'));
const base = release.download_url.slice(0, release.download_url.lastIndexOf('/') + 1);
release.abi_download_urls = Object.fromEntries(
  abis.map((abi) => [abi, `${base}morphe-manager-${abi}-${release.version}.apk`])
);
fs.writeFileSync(file, `${JSON.stringify(release, null, 2)}\n`);
EOF
echo "Listed the APKs of ${ABIS[*]} in app-release.json"

echo "Release v${VERSION} prepared successfully."
