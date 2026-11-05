# Facet — common tasks. Run `make help`.
SHELL := /bin/bash
IOS_DERIVED := iosApp/build

.PHONY: help android install test test-shared test-native xcodeproj ios clean

help:
	@echo "make android      Build the Android debug APK"
	@echo "make install      Build and install the debug APK on the connected phone (adb)"
	@echo "make test         Shared Kotlin tests (Android JVM + iOS Simulator) and C++ core tests"
	@echo "make ios          Generate the Xcode project and build the iOS app for the Simulator"
	@echo "make xcodeproj    Regenerate iosApp/iosApp.xcodeproj from project.yml (XcodeGen)"
	@echo "make clean        Remove build outputs"

android:
	./gradlew :androidApp:assembleDebug

install:
	./gradlew :androidApp:installDebug

test: test-shared test-native

test-shared:
	./gradlew :shared:allTests

# Host build of native/ with the CMake bundled in the Android SDK.
SDK_DIR := $(shell sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)
CMAKE_VERSION := $(shell sed -n 's/^cmake = "\(.*\)"/\1/p' gradle/libs.versions.toml)
CMAKE_BIN := $(SDK_DIR)/cmake/$(CMAKE_VERSION)/bin
test-native:
	"$(CMAKE_BIN)/cmake" -S native -B native/build-host -G Ninja -DCMAKE_MAKE_PROGRAM="$(CMAKE_BIN)/ninja" -DCMAKE_BUILD_TYPE=Debug
	"$(CMAKE_BIN)/cmake" --build native/build-host
	cd native/build-host && "$(CMAKE_BIN)/ctest" --output-on-failure
	@# New copyright/license notices in compiled third-party code must be credited (see the script).
	NINJA="$(CMAKE_BIN)/ninja" python3 -I native/scripts/scan_notices.py native/build-host

xcodeproj:
	cd iosApp && xcodegen generate --quiet

ios: xcodeproj
	xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
		-destination 'generic/platform=iOS Simulator' -derivedDataPath $(IOS_DERIVED) \
		CODE_SIGNING_ALLOWED=NO -quiet build
	@echo "Built $(IOS_DERIVED)/Build/Products/Debug-iphonesimulator/Facet.app"

clean:
	./gradlew clean
	rm -rf native/build-host $(IOS_DERIVED)
