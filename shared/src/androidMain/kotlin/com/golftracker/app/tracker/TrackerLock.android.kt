package com.golftracker.app.tracker

actual class TrackerLock actual constructor() {
    private val lock = java.util.concurrent.locks.ReentrantLock()
    actual fun lock() = lock.lock()
    actual fun unlock() = lock.unlock()
}
