package com.termux.view;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import com.termux.terminal.TerminalEmulator;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

/** Exercises the real window Canvas; records only timing and acceleration status. */
public class RenderTestActivity extends Activity {
    final CountDownLatch drawn = new CountDownLatch(1);
    volatile boolean hardwareAccelerated;
    private final long[] renderTimes = new long[30];

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        TerminalRenderer renderer = new TerminalRenderer(20, Typeface.MONOSPACE);
        TerminalEmulator emulator = new TerminalEmulator(new ArabicLayoutTest.Output(), 80, 24, 12, 24, null, null);
        for (int i = 0; i < 24; i++) ArabicLayoutTest.append(emulator, "user@termux: سَلَام بالعربية 123 لا\r\n");
        setContentView(new View(this) {
            int frame;
            boolean dense;
            @Override protected void onDraw(Canvas canvas) {
                hardwareAccelerated = canvas.isHardwareAccelerated();
                long start = System.nanoTime();
                renderer.render(emulator, canvas, 0, 0, 0, 14, 20);
                if (frame < renderTimes.length) renderTimes[frame++] = System.nanoTime() - start;
                if (frame < renderTimes.length) {
                    postInvalidateOnAnimation();
                } else if (drawn.getCount() != 0) {
                    Arrays.sort(renderTimes);
                    Log.i("ArabicLayoutTest", "80x24 " + (dense ? "dense Arabic" : "mixed text") + ": hardwareCanvas=" + hardwareAccelerated
                        + ", render p50=" + renderTimes[15] / 1000000.0 + " ms, p95=" + renderTimes[28] / 1000000.0 + " ms");
                    if (dense) {
                        drawn.countDown();
                    } else {
                        dense = true;
                        frame = 0;
                        ArabicLayoutTest.append(emulator, "\033[2J\033[H");
                        for (int i = 0; i < 24 * 20; i++) ArabicLayoutTest.append(emulator, "سَلَام");
                        postInvalidateOnAnimation();
                    }
                }
            }
        });
    }
}
