#!/usr/bin/env bash
set -e

# Configuration (relative paths used for root directory project files)
SRC_APK="app/build/outputs/apk/debug/app-debug.apk"
DEST_DIR="/Users/rek/Library/CloudStorage/GoogleDrive-rickkoter@gmail.com/My Drive/KoterApps"
ARCHIVE_DIR="$DEST_DIR/old"

# 1. Ensure source APK exists
if [ ! -f "$SRC_APK" ]; then
    echo "Error: Source APK file not found at '$SRC_APK'."
    echo "Please build the project first using: ./gradlew assembleDebug"
    exit 1
fi

# 2. Ensure destination and archive directories exist
mkdir -p "$DEST_DIR"
mkdir -p "$ARCHIVE_DIR"

APK_NAME="$(basename "$SRC_APK")"
DEST_APK="$DEST_DIR/$APK_NAME"

# 3. If APK already exists in destination, archive it with its modification date
if [ -f "$DEST_APK" ]; then
    DATE_STAMP="$(date -r "$DEST_APK" "+%Y-%m-%d_%H-%M-%S")"
    FILE_BASE="${APK_NAME%.*}"
    FILE_EXT="${APK_NAME##*.}"
    ARCHIVED_NAME="${FILE_BASE}_${DATE_STAMP}.${FILE_EXT}"
    ARCHIVED_PATH="$ARCHIVE_DIR/$ARCHIVED_NAME"

    echo "Archiving existing destination APK to '$ARCHIVED_PATH'..."
    mv "$DEST_APK" "$ARCHIVED_PATH"
fi

# 4. Copy current built APK to destination
echo "Copying '$SRC_APK' to '$DEST_APK'..."
cp "$SRC_APK" "$DEST_APK"

echo "Done! APK successfully deployed to Google Drive."
