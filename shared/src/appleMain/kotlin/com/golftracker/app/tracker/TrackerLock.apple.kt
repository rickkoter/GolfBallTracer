package com.golftracker.app.tracker

import platform.Foundation.NSRecursiveLock

actual class TrackerLock actual constructor() {
    private val lock = NSRecursiveLock()
    actual fun lock() = lock.lock()
    actual fun unlock() = lock.unlock()
}
