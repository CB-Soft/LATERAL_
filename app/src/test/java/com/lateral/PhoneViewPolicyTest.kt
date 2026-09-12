package com.lateral

import org.junit.Assert.*
import org.junit.Test

class PhoneViewPolicyTest {
    @Test fun keyboardWithoutDisplayOffersOnceAndAllowsManualEntryAfterDecline() {
        val policy = PhoneViewPolicy()
        policy.update(true, false)
        assertTrue(policy.takeOffer())
        assertFalse(policy.takeOffer())
        assertTrue(policy.setActive(true))
        policy.setActive(false)
        assertFalse(policy.takeOffer())
        assertTrue(policy.setActive(true))
    }

    @Test fun keyboardRemovalExitsAndReconnectOffersAgain() {
        val policy = PhoneViewPolicy()
        policy.update(true, false)
        policy.setActive(true)
        policy.update(false, false)
        assertFalse(policy.active)
        assertFalse(policy.setActive(true))
        policy.update(true, false)
        assertTrue(policy.takeOffer())
    }

    @Test fun externalDisplayAlwaysWinsIncludingSimultaneousKeyboardConnection() {
        val policy = PhoneViewPolicy()
        policy.update(true, false)
        policy.setActive(true)
        policy.update(true, true)
        assertFalse(policy.active)
        assertFalse(policy.takeOffer())
        assertFalse(policy.setActive(true))
        policy.update(false, true)
        assertFalse(policy.eligible)
        policy.update(true, false)
        assertTrue(policy.takeOffer())
    }

    @Test fun repeatedDeviceNotificationsDoNotExitOrOfferAgain() {
        val policy = PhoneViewPolicy()
        policy.update(true, false)
        policy.setActive(true)
        repeat(10) { policy.update(true, false) }
        assertTrue(policy.active)
        assertFalse(policy.takeOffer())
    }
}
