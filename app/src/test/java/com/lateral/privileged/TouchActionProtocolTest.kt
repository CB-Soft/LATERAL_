package com.lateral.privileged

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchActionProtocolTest {
    @Test fun tapTerminatesInsteadOfMoving() {
        assertEquals(listOf(0, 2), listOf(0, 1).map(TouchActionProtocol::encode))
    }
    @Test fun dragPreservesMoveAndCancel() {
        assertEquals(listOf(0, 1, 1, 3), listOf(0, 2, 2, 3).map(TouchActionProtocol::encode))
    }
}
