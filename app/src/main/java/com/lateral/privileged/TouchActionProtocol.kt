package com.lateral.privileged

/** Android orders UP before MOVE; the existing helper wire protocol orders MOVE before UP. */
internal object TouchActionProtocol {
    fun encode(androidAction: Int): Int = when (androidAction) {
        0 -> 0 // DOWN
        1 -> 2 // UP
        2 -> 1 // MOVE
        else -> 3 // CANCEL
    }
}
