#!/bin/sh
set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GRADLE_VERSION=9.5.0
GRADLE_HOME_BASE=${GRADLE_USER_HOME:-"$HOME/.gradle"}/nodemap-wrapper/$GRADLE_VERSION
GRADLE_HOME=$GRADLE_HOME_BASE/gradle-$GRADLE_VERSION
GRADLE_BIN=$GRADLE_HOME/bin/gradle
DIST_URL=https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip
DIST_FILE=$GRADLE_HOME_BASE/gradle-$GRADLE_VERSION-bin.zip

fetch_file() {
    url=$1
    output=$2
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$url" -o "$output"
    elif command -v wget >/dev/null 2>&1; then
        wget -qO "$output" "$url"
    else
        echo "gradlew: curl or wget is required to bootstrap Gradle" >&2
        exit 1
    fi
}

fetch_text() {
    url=$1
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$url"
    elif command -v wget >/dev/null 2>&1; then
        wget -qO- "$url"
    else
        echo "gradlew: curl or wget is required to bootstrap Gradle" >&2
        exit 1
    fi
}

sha256_file() {
    file=$1
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$file" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$file" | awk '{print $1}'
    else
        echo "gradlew: sha256sum or shasum is required" >&2
        exit 1
    fi
}

if [ ! -x "$GRADLE_BIN" ]; then
    command -v java >/dev/null 2>&1 || {
        echo "gradlew: JDK 17 or newer is required" >&2
        exit 1
    }
    command -v jar >/dev/null 2>&1 || {
        echo "gradlew: the JDK jar tool is required" >&2
        exit 1
    }

    mkdir -p "$GRADLE_HOME_BASE"
    tmp=$DIST_FILE.tmp.$$
    trap 'rm -f "$tmp"' EXIT HUP INT TERM
    fetch_file "$DIST_URL" "$tmp"
    expected=$(fetch_text "$DIST_URL.sha256" | tr -d '[:space:]')
    actual=$(sha256_file "$tmp")
    if [ "$actual" != "$expected" ]; then
        echo "gradlew: Gradle distribution checksum mismatch" >&2
        exit 1
    fi
    mv "$tmp" "$DIST_FILE"
    rm -rf "$GRADLE_HOME"
    (cd "$GRADLE_HOME_BASE" && jar xf "$DIST_FILE")
    chmod +x "$GRADLE_BIN"
    trap - EXIT HUP INT TERM
fi

exec "$GRADLE_BIN" -p "$APP_HOME" "$@"
