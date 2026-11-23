package tn.supcom.coldtrace.report;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A very small PDF 1.4 writer: text in Helvetica / Helvetica-Bold, lines and filled rectangles, several pages.
 * Enough for a printable report, without bundling a PDF library in the WAR.
 *
 * Coordinates are in points (1/72 inch) from the bottom-left corner of the page. Text uses WinAnsiEncoding, so
 * French accents and "°" print correctly; other characters become "?".
 */
public final class SimplePdf {

    public static final float A4_LANDSCAPE_W = 842, A4_LANDSCAPE_H = 595;

    private static final Charset WIN_ANSI = Charset.forName("windows-1252");
    private final float width, height;
    private final List<StringBuilder> pages = new ArrayList<>();
    private StringBuilder page;

    public SimplePdf(float width, float height) {
        this.width = width;
        this.height = height;
        newPage();
    }

    public float width() { return width; }
    public float height() { return height; }
    public int pageCount() { return pages.size(); }

    public void newPage() {
        page = new StringBuilder();
        pages.add(page);
    }

    public void text(float x, float y, float size, boolean bold, String text) {
        page.append("BT /").append(bold ? "F2 " : "F1 ").append(n(size)).append(" Tf ")
                .append(n(x)).append(' ').append(n(y)).append(" Td (").append(escape(text)).append(") Tj ET\n");
    }

    public void gray(float level) {
        page.append(n(level)).append(" g ").append(n(level)).append(" G\n");
    }

    public void rect(float x, float y, float w, float h) {
        page.append(n(x)).append(' ').append(n(y)).append(' ').append(n(w)).append(' ').append(n(h)).append(" re f\n");
    }

    public void line(float x1, float y1, float x2, float y2) {
        page.append("0.5 w ").append(n(x1)).append(' ').append(n(y1)).append(" m ")
                .append(n(x2)).append(' ').append(n(y2)).append(" l S\n");
    }

    /** Text shortened with "..." to fit about {@code maxWidth} points at {@code size} (average Helvetica width). */
    public static String fit(String text, float size, float maxWidth) {
        if (text == null) return "";
        int max = Math.max(1, (int) (maxWidth / (size * 0.52f)));
        return text.length() <= max ? text : text.substring(0, Math.max(1, max - 3)) + "...";
    }

    public byte[] toBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        write(out, "%PDF-1.4\n%âãÏÓ\n");
        int fontRegular = 3, fontBold = 4, firstPage = 5;
        int objects = 4 + pages.size() * 2;

        offsets.add(out.size());
        write(out, "1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n");
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) kids.append(firstPage + i * 2).append(" 0 R ");
        offsets.add(out.size());
        write(out, "2 0 obj << /Type /Pages /Count " + pages.size() + " /Kids [" + kids + "] >> endobj\n");
        offsets.add(out.size());
        write(out, fontRegular + " 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >> endobj\n");
        offsets.add(out.size());
        write(out, fontBold + " 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >> endobj\n");

        for (int i = 0; i < pages.size(); i++) {
            int pageObj = firstPage + i * 2, contentObj = pageObj + 1;
            byte[] content = encode(pages.get(i).toString());
            offsets.add(out.size());
            write(out, pageObj + " 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 " + n(width) + " " + n(height)
                    + "] /Resources << /Font << /F1 " + fontRegular + " 0 R /F2 " + fontBold + " 0 R >> >> /Contents "
                    + contentObj + " 0 R >> endobj\n");
            offsets.add(out.size());
            write(out, contentObj + " 0 obj << /Length " + content.length + " >> stream\n");
            out.writeBytes(content);
            write(out, "\nendstream endobj\n");
        }

        int xref = out.size();
        StringBuilder table = new StringBuilder("xref\n0 " + (objects + 1) + "\n0000000000 65535 f \n");
        for (int offset : offsets) table.append(String.format(Locale.ROOT, "%010d 00000 n \n", offset));
        table.append("trailer << /Size ").append(objects + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        write(out, table.toString());
        return out.toByteArray();
    }

    static String escape(String text) {
        StringBuilder s = new StringBuilder();
        for (char c : (text == null ? "" : text).toCharArray()) {
            if (c == '(' || c == ')' || c == '\\') s.append('\\').append(c);
            else if (c < 32) s.append(' ');
            else s.append(c);
        }
        return s.toString();
    }

    private static byte[] encode(String content) {
        CharsetEncoder encoder = WIN_ANSI.newEncoder().onUnmappableCharacter(CodingErrorAction.REPLACE)
                .onMalformedInput(CodingErrorAction.REPLACE).replaceWith(new byte[]{'?'});
        try {
            var buffer = encoder.encode(java.nio.CharBuffer.wrap(content));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (java.nio.charset.CharacterCodingException e) {
            return content.getBytes(StandardCharsets.US_ASCII);
        }
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String n(float v) {
        return v == Math.rint(v) ? Integer.toString((int) v) : String.format(Locale.ROOT, "%.2f", v);
    }
}
