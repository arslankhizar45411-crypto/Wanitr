package com.collabcode.app.data

/** Pushed to the user's repo as .github/workflows/build.yml. Builds a debug APK. */
const val BUILD_WORKFLOW = """
name: Build APK

on:
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - uses: android-actions/setup-android@v3

      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.9'

      - name: Build debug APK
        run: gradle assembleDebug --no-daemon

      - uses: actions/upload-artifact@v4
        with:
          name: debug-apk
          path: build/outputs/apk/debug/*.apk
"""
