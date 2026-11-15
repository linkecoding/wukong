package com.wukong.core

/** Epoch invalidates decisions produced before/during human takeover. */
class ExecutionControl {
    private val lock = Object()
    @Volatile var paused = false; private set
    @Volatile var epoch = 0L; private set
    fun pause(value: Boolean) = synchronized(lock) { paused = value; epoch++; lock.notifyAll() }
    fun awaitAgent(cancellation: Cancellation) = synchronized(lock) {
        while (paused) { cancellation.check(); lock.wait(100) }
        cancellation.check()
    }
}
