package dev.khronos31.mirakc

/** Admission barrier shared by runtime starts, worker registration and teardown. */
internal class RuntimeOwnerGate {
    private var acceptingOwners = true
    private var activeOwners = 0

    @Synchronized
    fun mayStart(): Boolean = acceptingOwners

    @Synchronized
    fun beginStop() {
        acceptingOwners = false
    }

    @Synchronized
    fun ownerStarted(): Boolean {
        if (!acceptingOwners) return false
        activeOwners++
        return true
    }

    @Synchronized
    fun ownerFinished() {
        check(activeOwners > 0) { "runtime owner count underflow" }
        activeOwners--
    }

    @Synchronized
    fun isQuiescent(): Boolean = activeOwners == 0

    @Synchronized
    fun finishStop() {
        check(!acceptingOwners) { "stop barrier was not started" }
        check(activeOwners == 0) { "cannot release runtime ownership before workers retire" }
        acceptingOwners = true
    }
}
