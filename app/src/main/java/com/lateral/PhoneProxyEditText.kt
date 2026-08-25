package com.lateral

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText

/** Phone-owned input used only for Beast launcher search. */
class PhoneProxyEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatEditText(context, attrs)
