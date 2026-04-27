package com.pynapli.shared.platform

import platform.UIKit.UIDevice

actual class PlatformInfoProvider {
    actual fun platformName(): String = UIDevice.currentDevice.systemName()
}

