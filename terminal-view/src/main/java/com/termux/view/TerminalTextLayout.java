package com.termux.view;

import android.graphics.Canvas;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

import java.text.Bidi;
import java.util.ArrayList;
import java.util.Arrays;

/** Display-only bidi mapping. Terminal rows, process I/O and copied text stay in logical order. */
final class TerminalTextLayout {
    static final class Cell {
        final int column, width, start, end;
        final boolean bitmap;
        byte level;
        int visualColumn;

        Cell(int column, int width, int start, int end, boolean bitmap) {
            this.column = column;
            this.width = width;
            this.start = start;
            this.end = end;
            this.bitmap = bitmap;
        }

        boolean isRtl() { return (level & 1) != 0; }
    }

    static final class Run {
        final Cell[] cells;

        Run(Cell[] cells) { this.cells = cells; }

        boolean isRtl() { return cells[0].isRtl(); }
    }

    final Run[] runs;
    private final Cell[] mLogicalCells;
    private final int[] mVisualToLogical;

    /** Null means the existing LTR rendering and coordinates can be used without allocations. */
    static TerminalTextLayout forRow(TerminalRow row, int columns) {
        if (!Bidi.requiresBidi(row.mText, 0, row.getSpaceUsed())) return null;
        return new TerminalTextLayout(row, columns);
    }

    private TerminalTextLayout(TerminalRow row, int columns) {
        ArrayList<Cell> cells = new ArrayList<>();
        mLogicalCells = new Cell[columns];
        mVisualToLogical = new int[columns];
        for (int index = 0, column = 0; column < columns;) {
            int start = index;
            int codePoint = Character.codePointAt(row.mText, index, row.getSpaceUsed());
            int width = Math.max(1, WcWidth.width(codePoint));
            index += Character.charCount(codePoint);
            while (index < row.getSpaceUsed() && WcWidth.width(row.mText, index) <= 0) {
                index += Character.charCount(Character.codePointAt(row.mText, index, row.getSpaceUsed()));
            }
            Cell cell = new Cell(column, width, start, index, TextStyle.isTerminalBitmap(row.getStyle(column)));
            cells.add(cell);
            for (int x = column; x < column + width; x++) mLogicalCells[x] = cell;
            column += width;
        }

        Cell[] logical = cells.toArray(new Cell[0]);
        Cell[] visual = logical.clone();
        for (int start = 0; start < logical.length;) {
            if (logical[start].bitmap) {
                start++;
                continue;
            }
            int end = start + 1;
            while (end < logical.length && !logical[end].bitmap) end++;
            // Resolve text between fixed bitmap tiles separately. The terminal's grid
            // and prompt remain left anchored, independently of the UI locale.
            int charStart = logical[start].start;
            Bidi bidi = new Bidi(row.mText, charStart, null, 0, logical[end - 1].end - charStart, Bidi.DIRECTION_LEFT_TO_RIGHT);
            byte[] levels = new byte[end - start];
            for (int i = start; i < end; i++) {
                logical[i].level = levels[i - start] = (byte) bidi.getLevelAt(logical[i].start - charStart);
            }
            Bidi.reorderVisually(levels, 0, visual, start, end - start);
            start = end;
        }
        ArrayList<Run> logicalRuns = new ArrayList<>();
        for (int start = 0; start < logical.length;) {
            int end = start + 1;
            while (end < logical.length && logical[end].level == logical[start].level
                && logical[end].bitmap == logical[start].bitmap) end++;
            logicalRuns.add(new Run(Arrays.copyOfRange(logical, start, end)));
            start = end;
        }
        runs = logicalRuns.toArray(new Run[0]);

        int visualColumn = 0;
        for (Cell cell : visual) {
            cell.visualColumn = visualColumn;
            for (int x = 0; x < cell.width; x++) mVisualToLogical[visualColumn + x] = cell.column + x;
            visualColumn += cell.width;
        }
    }

    int logicalColumn(int visualColumn) {
        return mVisualToLogical[Math.max(0, Math.min(mVisualToLogical.length - 1, visualColumn))];
    }

    int visualColumn(int logicalColumn) {
        Cell cell = mLogicalCells[Math.max(0, Math.min(mLogicalCells.length - 1, logicalColumn))];
        return cell.visualColumn;
    }

    /** Leading/trailing edge of a logical selection endpoint, including wide cells. */
    int selectionEdge(int column, boolean end) {
        Cell cell = mLogicalCells[Math.max(0, Math.min(mLogicalCells.length - 1, column))];
        return cell.visualColumn + (end != cell.isRtl() ? cell.width : 0);
    }

    boolean isRtlAt(int column) {
        return mLogicalCells[Math.max(0, Math.min(mLogicalCells.length - 1, column))].isRtl();
    }

    int selectionColumn(float visualEdge, boolean end, int previous) {
        int result = Math.max(0, Math.min(mLogicalCells.length - 1, previous));
        float distance = Math.abs(selectionEdge(result, end) - visualEdge);
        for (Run run : runs) {
            for (Cell cell : run.cells) {
                float candidate = Math.abs(selectionEdge(cell.column, end) - visualEdge);
                if (candidate < distance) {
                    distance = candidate;
                    result = cell.column;
                }
            }
        }
        return result;
    }

    /** Contextual shaping of a directional run, also supported on API 21–22. */
    static final class ShapedRun {
        final StaticLayout layout;
        final float[] edges;

        ShapedRun(char[] text, Run run, TextPaint paint) {
            int start = run.cells[0].start;
            int end = run.cells[run.cells.length - 1].end;
            StringBuilder display = new StringBuilder(run.isRtl() ? "\u200f\u202e" : "\u200e\u202d");
            for (int i = start; i < end; i++) {
                char c = text[i];
                // Bidi controls have already been resolved. Do not apply them a second time.
                boolean bidiControl = c == '\u061c' || c == '\u200e' || c == '\u200f'
                    || (c >= '\u202a' && c <= '\u202e') || (c >= '\u2066' && c <= '\u2069');
                display.append(bidiControl || c == '\u2028' || c == '\u2029' ? '\u200b' : c);
            }
            display.append('\u202c');
            String shapedText = display.toString();
            layout = new StaticLayout(shapedText, paint,
                Math.max(1, (int) Math.ceil(Layout.getDesiredWidth(shapedText, paint)) + 1),
                Layout.Alignment.ALIGN_NORMAL, 1, 0, false);
            edges = new float[run.cells.length + 1];
            for (int i = 0; i < run.cells.length; i++) {
                edges[i] = layout.getPrimaryHorizontal(2 + run.cells[i].start - start);
            }
            edges[run.cells.length] = layout.getPrimaryHorizontal(2 + end - start);
            // Some fonts give all of a ligature's advance to one character. Divide that
            // cluster between its cells rather than letting a zero-width cell disappear.
            for (int i = 0; i < run.cells.length;) {
                int limit = i + 1;
                while (limit < run.cells.length && edges[limit + 1] == edges[limit]) limit++;
                if (edges[limit] == edges[i] && limit < run.cells.length) limit++;
                float from = edges[i], to = edges[limit];
                for (int j = i + 1; j < limit; j++) edges[j] = from + (to - from) * (j - i) / (limit - i);
                i = limit;
            }
        }

        void drawCell(Canvas canvas, int index, float left, float right, float baseline, TextPaint paint) {
            float sourceLeft = Math.min(edges[index], edges[index + 1]);
            float width = Math.abs(edges[index + 1] - edges[index]);
            if (width == 0) return;
            float top = baseline - layout.getLineBaseline(0);
            canvas.save();
            canvas.clipRect(left, top, right, top + layout.getHeight());
            canvas.translate(left, top);
            canvas.scale((right - left) / width, 1);
            canvas.translate(-sourceLeft, 0);
            TextPaint layoutPaint = layout.getPaint();
            layoutPaint.setColor(paint.getColor());
            layoutPaint.setUnderlineText(paint.isUnderlineText());
            layoutPaint.setStrikeThruText(paint.isStrikeThruText());
            // Clip a complete shaped run: cursor, selection and colour changes must not
            // break Arabic joining or a ligature spanning two differently styled cells.
            layout.draw(canvas);
            canvas.restore();
        }
    }
}
