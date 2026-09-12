package com.lateral

/** One offer per eligible connection; losing either prerequisite always exits. */
internal class PhoneViewPolicy {
    var eligible = false
        private set
    var active = false
        private set
    private var offered = false

    fun update(keyboard: Boolean, externalDisplay: Boolean) {
        eligible = keyboard && !externalDisplay
        if (!eligible) {
            active = false
            offered = false
        }
    }

    fun takeOffer(): Boolean {
        if (!eligible || active || offered) return false
        offered = true
        return true
    }

    fun setActive(value: Boolean): Boolean {
        active = value && eligible
        if (eligible) offered = true
        return active
    }
}
