package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.content.Intent;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Log;
import android.view.MotionEvent;

import androidx.test.platform.app.InstrumentationRegistry;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.view.textselection.TextSelectionCursorController;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import static org.junit.Assert.*;

public class ArabicLayoutTest {
    @Test
    public void realWindowUsesHardwareCanvas() throws Exception {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(instrumentation.getContext(), RenderTestActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        RenderTestActivity activity = (RenderTestActivity) instrumentation.startActivitySync(intent);
        try {
            assertTrue("Rendering did not complete", activity.drawn.await(15, TimeUnit.SECONDS));
            assertTrue("Expected a hardware-accelerated window Canvas", activity.hardwareAccelerated);
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    static final class Output extends TerminalOutput {
        final StringBuilder written = new StringBuilder();
        @Override public void write(byte[] data, int offset, int count) { written.append(new String(data, offset, count, StandardCharsets.UTF_8)); }
        @Override public void titleChanged(String oldTitle, String newTitle) { }
        @Override public void onCopyTextToClipboard(String text) { }
        @Override public void onPasteTextFromClipboard() { }
        @Override public void onBell() { }
        @Override public void onColorsChanged() { }
    }

    static void append(TerminalEmulator emulator, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
    }

    // Records drawing commands and geometry only, without creating or reading any images.
    static final class TextCanvas extends Canvas {
        final ArrayList<float[]> rectangles = new ArrayList<>();
        final ArrayList<Integer> colors = new ArrayList<>();
        final ArrayList<String> contexts = new ArrayList<>();
        final ArrayList<float[]> clips = new ArrayList<>();

        @Override public boolean getClipBounds(Rect rect) { rect.set(0, 0, 4096, 4096); return true; }
        @Override public boolean clipRect(float left, float top, float right, float bottom) {
            clips.add(new float[] {left, right});
            return true;
        }
        @Override public void drawRect(float left, float top, float right, float bottom, Paint paint) {
            rectangles.add(new float[] {left, right});
            colors.add(paint.getColor());
        }
        @Override public void drawTextRun(char[] text, int start, int count, int contextStart, int contextCount,
                                          float x, float y, boolean rtl, Paint paint) {
            contexts.add(new String(text, contextStart, contextCount));
        }
        @Override public void drawTextRun(CharSequence text, int start, int end, int contextStart, int contextEnd,
                                          float x, float y, boolean rtl, Paint paint) {
            contexts.add(text.subSequence(contextStart, contextEnd).toString());
        }
    }

    @Test
    public void platformShapesArabicWithLogicalCaretPositions() {
        TextPaint paint = new TextPaint();
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextSize(32);
        for (String word : new String[] {"سلام", "لا", "لَا", "سَلَام", "العربية"}) {
            String text = "\u200f\u202e" + word + "\u202c";
            StaticLayout layout = new StaticLayout(text, paint,
                (int) Math.ceil(Layout.getDesiredWidth(text, paint)) + 1,
                Layout.Alignment.ALIGN_NORMAL, 1, 0, false);
            assertEquals(1, layout.getLineCount());
            assertEquals(-1, layout.getParagraphDirection(0));
            float previous = layout.getPrimaryHorizontal(2);
            StringBuilder advances = new StringBuilder();
            for (int i = 3; i <= word.length() + 2; i++) {
                float position = layout.getPrimaryHorizontal(i);
                advances.append(previous - position).append(' ');
                assertTrue(position <= previous);
                previous = position;
            }
            Log.i("ArabicLayoutTest", word + ": " + advances);
            assertTrue(layout.getPrimaryHorizontal(2) > previous);
            if (word.equals("لا")) {
                assertTrue("Lam-alef should be shaped together", layout.getPrimaryHorizontal(2) - previous
                    < paint.measureText("ل") + paint.measureText("ا"));
            }
        }
    }

    @Test
    public void styledLamAlefKeepsContextAndCursorAndSelectionUseVisualCells() {
        TerminalEmulator emulator = new TerminalEmulator(new Output(), 12, 3, 10, 20, null, null);
        append(emulator, "\033[1;31mل\033[0;3;32mا\033[0m\033[1;1H");
        TerminalRenderer renderer = new TerminalRenderer(32, Typeface.MONOSPACE);
        TextCanvas canvas = new TextCanvas();
        renderer.render(emulator, canvas, 0, 0, 0, 1, 1);
        float width = renderer.getFontWidth();
        int cursorIndex = canvas.colors.indexOf(emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR]);
        assertTrue(cursorIndex >= 0);
        assertArrayEquals(new float[] {width, width * 2}, canvas.rectangles.get(cursorIndex), 0.01f);
        assertEquals(2, canvas.clips.size());
        for (float[] clip : canvas.clips) assertEquals(width, clip[1] - clip[0], 0.01f);
        int fullContexts = 0;
        for (String context : canvas.contexts) if (context.contains("لا")) fullContexts++;
        assertTrue("Both styled cells must draw the complete ligature context", fullContexts >= 2);
        assertEquals("لا", emulator.getScreen().getSelectedText(0, 0, 1, 0));
        assertEquals("ا", emulator.getScreen().getSelectedText(1, 0, 1, 0));
    }

    @Test
    public void hitTestingSelectionAndMouseUseLogicalColumnsAndToggleRestoresGrid() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Output output = new Output();
            TerminalEmulator emulator = new TerminalEmulator(output, 24, 3, 10, 20, null, null);
            append(emulator, "سَلَام https://a.b");
            TerminalView view = new TerminalView(InstrumentationRegistry.getInstrumentation().getTargetContext(), null);
            view.mRenderer = new TerminalRenderer(32, Typeface.MONOSPACE);
            view.mEmulator = emulator;
            float width = view.mRenderer.getFontWidth();
            float y = view.mRenderer.mFontLineSpacingAndAscent + view.mRenderer.mFontLineSpacing / 2f;
            MotionEvent touch = MotionEvent.obtain(1, 1, MotionEvent.ACTION_DOWN, width / 2, y, 0);
            try {
                assertEquals(3, view.getColumnAndRow(touch, true)[0]);
                TextSelectionCursorController selection = new TextSelectionCursorController(view);
                selection.setInitialTextSelectionPosition(touch);
                assertEquals("سَلَام", selection.getSelectedText());
                assertEquals(Math.round(4 * width), view.getSelectionPointX(0, 0, false));
                assertEquals(0, view.getSelectionPointX(3, 0, true));
                assertEquals(0, view.getSelectionColumn(4 * width, 0, false, 0));
                assertEquals(3, view.getSelectionColumn(0, 0, true, 3));
                append(emulator, "\033[?1000h\033[?1006h");
                view.sendMouseEventCode(touch, TerminalEmulator.MOUSE_LEFT_BUTTON, true);
                assertEquals("\033[<0;4;1M", output.written.toString());
                output.written.setLength(0);
                view.doScroll(touch, -3);
                assertEquals("\033[<64;4;1M\033[<64;4;1M\033[<64;4;1M", output.written.toString());
                view.setBidiEnabled(false);
                assertEquals(0, view.getColumnAndRow(touch, true)[0]);
                assertEquals(0, view.getSelectionPointX(0, 0, false));
            } finally {
                touch.recycle();
            }
        });
    }

    @Test
    public void wrappedArabicAndHistoryKeepLogicalCopyAfterResize() {
        TerminalEmulator emulator = new TerminalEmulator(new Output(), 4, 3, 10, 20, null, null);
        append(emulator, "سَلَامعليكم\r\n");
        assertTrue(emulator.getScreen().getTranscriptText().contains("سَلَامعليكم"));
        emulator.resize(6, 3, 10, 20);
        assertTrue(emulator.getScreen().getTranscriptText().contains("سَلَامعليكم"));
        TerminalRenderer renderer = new TerminalRenderer(32, Typeface.MONOSPACE);
        renderer.render(emulator, new TextCanvas(), 0, -1, -1, -1, -1);
        for (int y = -emulator.getScreen().getActiveTranscriptRows(); y < emulator.mRows; y++) {
            TerminalRow row = emulator.getScreen().allocateFullLineIfNecessary(emulator.getScreen().externalToInternalRow(y));
            TerminalTextLayout layout = TerminalTextLayout.forRow(row, emulator.mColumns);
            if (layout == null) continue;
            for (int col = 0; col < emulator.mColumns; col++) assertEquals(col, layout.logicalColumn(layout.visualColumn(col)));
        }
    }

    @Test
    public void selectionGeometryWorksAtDifferentFontSizesAndForWideLtrCells() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            TerminalEmulator emulator = new TerminalEmulator(new Output(), 12, 3, 10, 20, null, null);
            append(emulator, "😀\r\n界");
            TerminalView view = new TerminalView(InstrumentationRegistry.getInstrumentation().getTargetContext(), null);
            view.mEmulator = emulator;
            for (int size : new int[] {12, 32, 64}) {
                view.mRenderer = new TerminalRenderer(size, Typeface.MONOSPACE);
                assertEquals(Math.round(2 * view.mRenderer.getFontWidth()), view.getSelectionPointX(0, 0, true));
                assertEquals(0, view.getSelectionPointX(1, 1, false));
                for (int row = 0; row < 3; row++) {
                    assertEquals(row, view.getCursorY(view.getPointY(row + 1) - view.mRenderer.getFontLineSpacing() / 2f));
                }
            }
        });
    }
}
