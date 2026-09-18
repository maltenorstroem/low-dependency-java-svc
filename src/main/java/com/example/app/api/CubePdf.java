package com.example.app.api;

import com.example.app.domain.Cube;
import com.example.app.domain.CubeDefinition;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders a one-page data sheet as a PDF, by hand.
 *
 * <p>A PDF is a plain byte stream of numbered objects followed by a cross-reference table of their
 * offsets (PDF 1.7 / ISO 32000-1 section 7.5), so the smallest useful document is a handful of
 * dictionaries and a text stream. Using one of the base-14 fonts means nothing has to be embedded,
 * which keeps this to a page of code and the service dependency-free.
 */
final class CubePdf {

    private static final String FONT = "Helvetica";
    private static final int TITLE_SIZE = 18;
    private static final int BODY_SIZE = 11;
    private static final int PAGE_WIDTH = 595; // A4 at 72 dpi
    private static final int PAGE_HEIGHT = 842;
    private static final int MARGIN = 64;
    private static final int LINE_HEIGHT = 18;
    private static final int MAX_LINE_LENGTH = 90;

    private CubePdf() {}

    static byte[] render(Cube cube) {
        return document(cube.displayName(), lines(cube));
    }

    private static List<String> lines(Cube cube) {
        CubeDefinition definition = cube.cube();
        List<String> lines = new ArrayList<>();
        lines.add("Id: " + cube.id());
        lines.add("Description: " + (cube.description().isEmpty() ? "-" : cube.description()));
        lines.add("");
        lines.add("Length: " + number(definition.length()));
        lines.add("Breadth: " + number(definition.breadth()));
        lines.add("Height: " + number(definition.height()));
        lines.add("Volume: " + number(round(definition.volume())));
        lines.add("Material: " + definition.material().label());
        lines.add("Colour: rgba(" + definition.colour().red() + ", " + definition.colour().green() + ", "
                + definition.colour().blue() + ", " + number(definition.colour().alpha()) + ")");
        lines.add("");
        lines.add("Version: " + cube.version());
        lines.add("Created: " + cube.createdAt());
        lines.add("Updated: " + cube.updatedAt());
        return lines;
    }

    /** Trims the trailing zeros {@code Double.toString} adds, and caps the volume's precision. */
    private static String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }

    // ---------------------------------------------------------------- PDF assembly

    private static byte[] document(String title, List<String> lines) {
        byte[] content = contentStream(title, lines);
        List<byte[]> objects = List.of(
                object("<< /Type /Catalog /Pages 2 0 R >>"),
                object("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                object("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + PAGE_WIDTH + " " + PAGE_HEIGHT + "]"
                        + " /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>"),
                stream(content),
                object("<< /Type /Font /Subtype /Type1 /BaseFont /" + FONT + " /Encoding /WinAnsiEncoding >>"));

        ByteArrayOutputStream out = new ByteArrayOutputStream(2048);
        write(out, "%PDF-1.7\n%âãÏÓ\n"); // binary comment: marks the file as non-text
        int[] offsets = new int[objects.size() + 1];
        for (int i = 0; i < objects.size(); i++) {
            offsets[i + 1] = out.size();
            write(out, (i + 1) + " 0 obj\n");
            out.writeBytes(objects.get(i));
            write(out, "\nendobj\n");
        }
        int xref = out.size();
        write(out, "xref\n0 " + (objects.size() + 1) + "\n");
        write(out, "0000000000 65535 f \n");
        for (int i = 1; i <= objects.size(); i++) {
            write(out, String.format(Locale.ROOT, "%010d 00000 n \n", offsets[i]));
        }
        write(out, "trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return out.toByteArray();
    }

    private static byte[] contentStream(String title, List<String> lines) {
        StringBuilder text = new StringBuilder();
        text.append("BT\n/F1 ").append(TITLE_SIZE).append(" Tf\n")
                .append(MARGIN).append(' ').append(PAGE_HEIGHT - MARGIN).append(" Td\n")
                .append(literal(title)).append(" Tj\nET\n");
        int y = PAGE_HEIGHT - MARGIN - (LINE_HEIGHT * 2);
        for (String line : lines) {
            if (!line.isEmpty()) {
                text.append("BT\n/F1 ").append(BODY_SIZE).append(" Tf\n")
                        .append(MARGIN).append(' ').append(y).append(" Td\n")
                        .append(literal(line)).append(" Tj\nET\n");
            }
            y -= LINE_HEIGHT;
        }
        return text.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] object(String dictionary) {
        return dictionary.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] stream(byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 64);
        write(out, "<< /Length " + content.length + " >>\nstream\n");
        out.writeBytes(content);
        write(out, "endstream");
        return out.toByteArray();
    }

    /**
     * A PDF string literal. Only the three delimiter characters need escaping; everything outside
     * WinAnsi is dropped rather than mis-rendered, and long lines are truncated to stay on the page.
     */
    private static String literal(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8).append('(');
        int written = 0;
        for (int i = 0; i < value.length() && written < MAX_LINE_LENGTH; i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                continue;
            }
            if (c == '(' || c == ')' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
            written++;
        }
        return out.append(')').toString();
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.ISO_8859_1));
    }
}
