package com.termux.view;

import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

import org.junit.Test;

import static org.junit.Assert.*;

public class TerminalTextLayoutTest {
    private static TerminalRow row(String text, int columns) {
        long style = 0;
        TerminalRow row = new TerminalRow(columns, style);
        int column = 0;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            int width = WcWidth.width(cp);
            row.setChar(width == 0 ? column - 1 : column, cp, style);
            column += width;
            i += Character.charCount(cp);
        }
        return row;
    }

    @Test
    public void arabicCellsReverseButMarksAndStoredTextStayLogical() {
        TerminalRow row = row("سَلَام", 8);
        String original = new String(row.mText, 0, row.getSpaceUsed());
        TerminalTextLayout layout = TerminalTextLayout.forRow(row, 8);
        assertNotNull(layout);
        for (int i = 0; i < 4; i++) {
            assertEquals(3 - i, layout.logicalColumn(i));
            assertEquals(3 - i, layout.visualColumn(i));
            assertEquals(4 - i, layout.selectionEdge(i, false));
            assertEquals(3 - i, layout.selectionEdge(i, true));
            assertEquals(i, layout.selectionColumn(4 - i, false, i));
            assertEquals(i, layout.selectionColumn(3 - i, true, i));
        }
        assertEquals(4, layout.visualColumn(4));
        assertEquals(original, new String(row.mText, 0, row.getSpaceUsed()));
        assertEquals("سَ", new String(row.mText, layout.runs[0].cells[0].start,
            layout.runs[0].cells[0].end - layout.runs[0].cells[0].start));
    }

    @Test
    public void mixedDirectionsKeepPromptDigitsAndUrlReadable() {
        TerminalRow row = row("$ سلام 123 https://a.b", 30);
        TerminalTextLayout layout = TerminalTextLayout.forRow(row, 30);
        StringBuilder visual = new StringBuilder();
        for (int i = 0; i < 30; i++) visual.append(row.mText[row.findStartOfColumn(layout.logicalColumn(i))]);
        assertEquals("$ 123 مالس https://a.b", visual.toString().trim());
        for (int i = 0; i < 30; i++) assertEquals(i, layout.logicalColumn(layout.visualColumn(i)));
    }

    @Test
    public void wideAndSupplementaryCharactersRemainWhole() {
        TerminalRow row = row("😀 سَلام 界", 15);
        TerminalTextLayout layout = TerminalTextLayout.forRow(row, 15);
        assertEquals(0, layout.logicalColumn(0));
        assertEquals(1, layout.logicalColumn(1));
        assertEquals(6, layout.logicalColumn(3));
        assertEquals(3, layout.logicalColumn(6));
        assertEquals(2, layout.selectionEdge(1, true));
        assertEquals(10, layout.selectionEdge(8, true));
    }

    @Test
    public void ltrRowsKeepFastPathAndBitmapCoordinatesStayFixed() {
        assertNull(TerminalTextLayout.forRow(row("ascii 界 😀", 20), 20));
        TerminalRow row = row("سلام سلام", 12);
        row.setChar(4, ' ', TextStyle.encodeTerminalBitmap(0, 0, 0));
        TerminalTextLayout layout = TerminalTextLayout.forRow(row, 12);
        assertEquals(4, layout.logicalColumn(4));
        assertEquals(3, layout.logicalColumn(0));
        assertEquals(0, layout.logicalColumn(3));
        assertEquals(8, layout.logicalColumn(5));
        assertEquals(5, layout.logicalColumn(8));
    }
}
