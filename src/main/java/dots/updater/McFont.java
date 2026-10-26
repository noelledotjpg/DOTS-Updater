package dots.updater;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

// Bitmap font rendering lifted from vanilla
final class McFont {
    static final int WHITE = 0xFFFFFF;
    static final int GRAY = 0xA0A0A0;
    static final int YELLOW = 0xFFFFA0;
    static final int CELL = 8;
    static final int HEIGHT = 8;     // line height is 9 in game: 8px glyph + 1px gap

    private static final BufferedImage SHEET;
    private static final int[] WIDTH = new int[256];          // glyph pixel width
    private static final Map<Long, BufferedImage> CACHE = new HashMap<>();

    static {
        try (InputStream in = McFont.class.getResourceAsStream("/assets/ascii.png")) {
            if (in == null) throw new IllegalStateException("assets/ascii.png missing from jar");
            SHEET = ImageIO.read(in);
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
        for (int c = 0; c < 256; c++) {
            int cx = (c % 16) * CELL, cy = (c / 16) * CELL, w = 0;
            for (int x = 0; x < CELL; x++)
                for (int y = 0; y < CELL; y++)
                    if ((SHEET.getRGB(cx + x, cy + y) >>> 24) > 8) w = Math.max(w, x + 1);
            WIDTH[c] = w;
        }
    }

    private McFont() {}

    private static int glyphIndex(char ch) {
        if (ch == ' ' || ch == '\u00a0') return ' ';
        if (ch > 32 && ch < 127 && WIDTH[ch] > 0) return ch;
        return '?';
    }

    static int advance(char ch) {
        int g = glyphIndex(ch);
        return g == ' ' ? 4 : WIDTH[g] + 1;
    }

    static int width(String s, int scale) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\u2122') w += (advance('T') + advance('M')) * Math.max(1, scale / 2);
            else w += advance(ch) * scale;
        }
        return w;
    }

    static int lineHeight(int scale) { return 9 * scale; }

    private static BufferedImage glyph(int g, int rgb) {
        long key = ((long) g << 32) | (rgb & 0xFFFFFF);
        BufferedImage cached = CACHE.get(key);
        if (cached != null) return cached;
        BufferedImage out = new BufferedImage(CELL, CELL, BufferedImage.TYPE_INT_ARGB);
        int cx = (g % 16) * CELL, cy = (g / 16) * CELL;
        for (int x = 0; x < CELL; x++)
            for (int y = 0; y < CELL; y++) {
                int a = SHEET.getRGB(cx + x, cy + y) >>> 24;
                if (a > 8) out.setRGB(x, y, (a << 24) | rgb);
            }
        CACHE.put(key, out);
        return out;
    }

    private static int dim(int rgb) {
        return (((rgb >> 16 & 255) / 4) << 16) | (((rgb >> 8 & 255) / 4) << 8) | ((rgb & 255) / 4);
    }

    // trademark symbol handler
    static int draw(Graphics2D g, String s, int x, int y, int scale, int rgb, boolean shadow) {
        Object old = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        int cx = x;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\u2122') {
                int sub = Math.max(1, scale / 2);
                for (char t : new char[] { 'T', 'M' }) {
                    blit(g, t, cx, y, sub, rgb, shadow);
                    cx += advance(t) * sub;
                }
                continue;
            }
            blit(g, ch, cx, y, scale, rgb, shadow);
            cx += advance(ch) * scale;
        }
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                old == null ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : old);
        return cx;
    }

    private static void blit(Graphics2D g, char ch, int x, int y, int scale, int rgb, boolean shadow) {
        int idx = glyphIndex(ch);
        if (idx == ' ') return;
        if (shadow) g.drawImage(glyph(idx, dim(rgb)), x + scale, y + scale, CELL * scale, CELL * scale, null);
        g.drawImage(glyph(idx, rgb), x, y, CELL * scale, CELL * scale, null);
    }

    // word wrap
    static java.util.List<String> wrap(String text, int maxWidth, int scale) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String para : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                String trial = line.length() == 0 ? word : line + " " + word;
                if (line.length() > 0 && width(trial, scale) > maxWidth) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else line = new StringBuilder(trial);
            }
            lines.add(line.toString());
        }
        return lines;
    }
}
