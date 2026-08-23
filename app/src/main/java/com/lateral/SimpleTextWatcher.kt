package com.lateral

import android.text.Editable
import android.text.TextWatcher

class SimpleTextWatcher(
    private val callback: (String) -> Unit
) : TextWatcher {

    override fun beforeTextChanged(
        s: CharSequence?,
        start: Int,
        count: Int,
        after: Int
    ) {
    }

    override fun onTextChanged(
        s: CharSequence?,
        start: Int,
        before: Int,
        count: Int
    ) {
        callback(
            s?.toString().orEmpty()
        )
    }

    override fun afterTextChanged(
        s: Editable?
    ) {
    }
}