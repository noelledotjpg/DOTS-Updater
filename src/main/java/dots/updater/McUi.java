package dots.updater;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

final class McUi {
    private McUi() {}

    static final int GUI = 2;
    static final int DIRT_SCALE = 4;

    static final BufferedImage DIRT = load("/assets/dirt_background_grayscale.png");
    static final BufferedImage ICON = load("/assets/icon.png");
    static final BufferedImage BUTTON = load("/assets/button.png");
    static final BufferedImage BUTTON_HL = load("/assets/button_highlighted.png");
    static final BufferedImage DROPDOWN = load("/assets/button_dropdown.png");
    static final BufferedImage DROPDOWN_HL = load("/assets/button_dropdown_highlighted.png");
    static final BufferedImage ARROW_DOWN = load("/assets/dropdown_arrow_down.png");
    static final BufferedImage ARROW_SIDE = load("/assets/dropdown_arrow_side.png");

    static BufferedImage load(String path) {
        try (InputStream in = McUi.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException(path + " missing from jar");
            return ImageIO.read(in);
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    static void applyIcon(Window w) { w.setIconImage(ICON); }

    static void runAndWait(Runnable r) {
        try {
            if (SwingUtilities.isEventDispatchThread()) r.run(); else SwingUtilities.invokeAndWait(r);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void nearest(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
    }

    private static void drawWidget(Graphics2D g, BufferedImage tex, int w, int h) {
        nearest(g);
        int tw = tex.getWidth(), th = tex.getHeight();
        int wu = w / GUI, left = (wu + 1) / 2, right = wu / 2;
        g.drawImage(tex, 0, 0, left * GUI, h, 0, 0, left, th, null);
        g.drawImage(tex, left * GUI, 0, left * GUI + right * GUI, h, tw - right, 0, tw, th, null);
    }

    static class DirtPanel extends JPanel {
        DirtPanel(LayoutManager lm) { super(lm); setOpaque(true); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            nearest(g);
            int t = DIRT.getWidth() * DIRT_SCALE;
            for (int y = 0; y < getHeight(); y += t)
                for (int x = 0; x < getWidth(); x += t)
                    g.drawImage(DIRT, x, y, t, t, null);
        }
    }

    static class McLabel extends JComponent {
        private final int scale; private final int color; private final int maxWidth;
        private List<String> lines;
        McLabel(String text, int scale, int maxWidth) { this(text, scale, McFont.WHITE, maxWidth); }
        McLabel(String text, int scale, int color, int maxWidth) {
            this.scale = scale; this.color = color; this.maxWidth = maxWidth; setText(text);
            setOpaque(false); setAlignmentX(Component.CENTER_ALIGNMENT);
        }
        void setText(String t) {
            lines = McFont.wrap(t, maxWidth, scale);
            int w = 0; for (String l : lines) w = Math.max(w, McFont.width(l, scale));
            Dimension d = new Dimension(w + scale, lines.size() * McFont.lineHeight(scale) + scale);
            setPreferredSize(d); setMinimumSize(d); setMaximumSize(new Dimension(Integer.MAX_VALUE, d.height));
            revalidate(); repaint();
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            int y = 0;
            for (String l : lines) {
                int x = (getWidth() - McFont.width(l, scale)) / 2;
                McFont.draw(g, l, x, y, scale, color, true);
                y += McFont.lineHeight(scale);
            }
        }
    }

    static class McButton extends JComponent {
        private final String text; private boolean hover, down;
        McButton(String text, int widthUnits, Runnable action) {
            this.text = text; Dimension d = new Dimension(widthUnits * GUI, 20 * GUI);
            setPreferredSize(d); setMinimumSize(d); setMaximumSize(d); setFocusable(false);
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; down = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) { down = true; repaint(); }
                @Override public void mouseReleased(MouseEvent e) {
                    boolean fire = down && hover; down = false; repaint();
                    if (fire && isEnabled()) action.run();
                }
            });
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            boolean on = isEnabled();
            drawWidget(g, on && hover ? BUTTON_HL : BUTTON, getWidth(), getHeight());
            int tw = McFont.width(text, GUI);
            McFont.draw(g, text, (getWidth() - tw) / 2, (getHeight() - 8 * GUI) / 2, GUI,
                    on ? McFont.WHITE : McFont.GRAY, true);
        }
    }

    static class McDropdown extends JComponent {
        private final List<String> items; private int selected;
        private boolean hover, open;
        private static final int SHADOW = 2;
        private static final Color SHADOW_COLOR = new Color(0, 0, 0, 110);
        private JLayeredPane layer; private JComponent catcher; private PopupList popup;

        McDropdown(List<String> items, int widthUnits) {
            this.items = items;
            Dimension d = new Dimension(widthUnits * GUI, 20 * GUI);
            setPreferredSize(d); setMinimumSize(d); setMaximumSize(d); setAlignmentX(Component.CENTER_ALIGNMENT);
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) {
                    openPopup();
                }
            });
        }
        int selectedIndex() { return selected; }
        void setSelectedIndex(int i) { if (i >= 0 && i < items.size()) { selected = i; repaint(); } }

        private void openPopup() {
            if (popup != null || getRootPane() == null) return;
            layer = getRootPane().getLayeredPane();
            int rowH = getHeight(), w = getWidth(), total = items.size() * rowH;
            Point p = SwingUtilities.convertPoint(this, 0, 0, layer);

            int below = layer.getHeight() - (p.y + rowH) - SHADOW - 4, above = p.y - 4;
            boolean down = below >= Math.min(total, 3 * rowH) || below >= above;
            int viewH = Math.max(rowH, Math.min(total, (down ? below : above) / rowH * rowH));
            int y = down ? p.y + rowH : p.y - viewH;

            catcher = new JComponent() {};
            catcher.setBounds(0, 0, layer.getWidth(), layer.getHeight());
            catcher.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { closePopup(); }
            });
            popup = new PopupList(w, rowH, viewH);
            popup.setBounds(p.x, y, w + SHADOW, viewH + SHADOW);
            popup.scrollTo(selected * rowH - (viewH - rowH) / 2);
            popup.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "close");
            popup.getActionMap().put("close", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { closePopup(); }
            });
            layer.add(catcher, JLayeredPane.POPUP_LAYER, 0);
            layer.add(popup, JLayeredPane.POPUP_LAYER, 0);
            open = true; repaint(); layer.repaint();
        }

        private void closePopup() {
            if (popup == null) return;
            layer.remove(popup); layer.remove(catcher);
            popup = null; catcher = null; open = false;
            layer.repaint(); repaint();
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            drawWidget(g, hover || open ? BUTTON_HL : BUTTON, getWidth(), getHeight());
            int reserve = 16 * GUI;
            String shown = fit(items.get(selected), getWidth() - 2 * reserve);
            McFont.draw(g, shown, (getWidth() - McFont.width(shown, GUI)) / 2, (getHeight() - 8 * GUI) / 2, GUI, McFont.WHITE, true);
            BufferedImage arrow = open ? ARROW_DOWN : ARROW_SIDE;
            int aw = arrow.getWidth() * GUI, ah = arrow.getHeight() * GUI;
            nearest(g);
            g.drawImage(arrow, getWidth() - 12 * GUI - aw / 2, (getHeight() - ah) / 2, aw, ah, null);
        }

        private static String fit(String s, int maxW) {
            while (s.length() > 3 && McFont.width(s, GUI) > maxW) s = s.substring(0, s.length() - 4) + "...";
            return s;
        }

        private final class PopupList extends JComponent {
            private static final int BAR_W = 3 * GUI;
            private final int w, rowH, viewH, total;
            private int scroll, hoverRow = -1, dragGrab = -1;

            PopupList(int w, int rowH, int viewH) {
                this.w = w; this.rowH = rowH; this.viewH = viewH; this.total = items.size() * rowH;
                setOpaque(false);
                MouseAdapter m = new MouseAdapter() {
                    @Override public void mouseMoved(MouseEvent e) { hoverRow = rowAt(e); repaint(); }
                    @Override public void mouseExited(MouseEvent e) { hoverRow = -1; repaint(); }
                    @Override public void mousePressed(MouseEvent e) {
                        if (e.getX() >= w - BAR_W && e.getX() < w && maxScroll() > 0) {
                            int ty = thumbY(), th = thumbH();
                            dragGrab = (e.getY() >= ty && e.getY() < ty + th) ? e.getY() - ty : th / 2;
                            dragTo(e.getY());
                        }
                    }
                    @Override public void mouseDragged(MouseEvent e) {
                        if (dragGrab >= 0) dragTo(e.getY()); else { hoverRow = rowAt(e); repaint(); }
                    }
                    @Override public void mouseReleased(MouseEvent e) {
                        boolean wasDrag = dragGrab >= 0; dragGrab = -1;
                        int r = rowAt(e);
                        if (!wasDrag && r >= 0) { selected = r; closePopup(); }
                    }
                    @Override public void mouseWheelMoved(MouseWheelEvent e) {
                        scrollTo(scroll + (int) Math.round(e.getPreciseWheelRotation() * rowH / 2.0));
                        hoverRow = rowAt(e);
                    }
                };
                addMouseListener(m); addMouseMotionListener(m); addMouseWheelListener(m);
            }

            private int maxScroll() { return Math.max(0, total - viewH); }
            private int thumbH() { return Math.max(rowH / 2, (int) ((long) viewH * viewH / total)); }
            private int thumbY() { int m = maxScroll(); return m == 0 ? 0 : (int) ((long) scroll * (viewH - thumbH()) / m); }
            private void dragTo(int y) {
                int span = viewH - thumbH();
                scrollTo(span <= 0 ? 0 : (int) ((long) (y - dragGrab) * maxScroll() / span));
            }
            void scrollTo(int s) { scroll = Math.max(0, Math.min(maxScroll(), s)); repaint(); }
            private int rowAt(MouseEvent e) {
                if (e.getX() < 0 || e.getX() >= w || e.getY() < 0 || e.getY() >= viewH) return -1;
                int r = (e.getY() + scroll) / rowH;
                return r < items.size() ? r : -1;
            }

            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setColor(SHADOW_COLOR);
                g.fillRect(SHADOW, SHADOW, w, viewH);
                g.clipRect(0, 0, w, viewH);
                for (int i = scroll / rowH; i < items.size() && i * rowH - scroll < viewH; i++) {
                    Graphics2D rg = (Graphics2D) g.create();
                    rg.translate(0, i * rowH - scroll);
                    drawWidget(rg, i == hoverRow ? DROPDOWN_HL : DROPDOWN, w, rowH);
                    String shown = fit(items.get(i), w - 2 * 16 * GUI);
                    McFont.draw(rg, shown, (w - McFont.width(shown, GUI)) / 2, (rowH - 8 * GUI) / 2, GUI, McFont.WHITE, true);
                    rg.dispose();
                }
                if (maxScroll() > 0) {
                    g.setColor(new Color(0, 0, 0, 150));
                    g.fillRect(w - BAR_W, 0, BAR_W, viewH);
                    int ty = thumbY(), th = thumbH();
                    g.setColor(new Color(128, 128, 128));
                    g.fillRect(w - BAR_W, ty, BAR_W, th);
                    g.setColor(new Color(192, 192, 192));
                    g.fillRect(w - BAR_W, ty, BAR_W - GUI, th - GUI);
                }
                g.dispose();
            }
        }
    }

    static class McProgress extends JComponent {
        private static final int W = 100, H = 2, SHADOW = 1;
        private int progress;

        McProgress() {
            Dimension d = new Dimension((W + SHADOW) * GUI, (H + SHADOW) * GUI);
            setPreferredSize(d); setMinimumSize(d); setMaximumSize(d); setAlignmentX(Component.CENTER_ALIGNMENT);
        }
        void setFraction(double f) { progress = (int) Math.max(0, Math.min(W, f * W)); repaint(); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            g.setColor(Color.BLACK);
            g.fillRect(SHADOW * GUI, SHADOW * GUI, W * GUI, H * GUI);
            g.setColor(new Color(128, 128, 128));
            g.fillRect(0, 0, W * GUI, H * GUI);
            g.setColor(new Color(128, 255, 128));
            g.fillRect(0, 0, progress * GUI, H * GUI);
        }
    }
}