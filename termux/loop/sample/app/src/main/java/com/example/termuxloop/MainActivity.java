package com.example.termuxloop;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final boolean BROKEN = true;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (BROKEN) throw new IllegalStateException("TERMUX_LOOP_DEMO_BUG");
        TextView label = new TextView(this);
        label.setText("TERMUX LOOP SOLVED");
        label.setTextSize(28);
        label.setGravity(Gravity.CENTER);
        setContentView(label);
    }
}
