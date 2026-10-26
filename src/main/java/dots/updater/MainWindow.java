package dots.updater;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.concurrent.CountDownLatch;

final class MainWindow {
    static final String CREATE_NEW = "+ Create new instance";
    private static final int SIZE = 540;
    private static final int PAD = 24;

    private static JFrame frame;
    private static McUi.DirtPanel root;
    private static volatile CountDownLatch waiting;
    private static volatile boolean closed;
    private static volatile boolean busy;      // true while installing: closing the window is ignored
    private static int lastChoice;

    private MainWindow() {}

    // ---- infrastructure ----------------------------------------------------------------
    private static void ensureFrame() {
        McUi.runAndWait(() -> {
            if (frame != null) return;
            frame = new JFrame("DOTS Update Utility");
            McUi.applyIcon(frame);
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) {
                    if (busy) return;
                    closed = true;
                    CountDownLatch l = waiting;
                    if (l != null) l.countDown();
                }
            });
            root = new McUi.DirtPanel(new BorderLayout());
            root.setPreferredSize(new Dimension(SIZE, SIZE));
            frame.setContentPane(root);
            frame.setResizable(false);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    private static void setScreen(JComponent center, JComponent bottom) {   // EDT only
        root.removeAll();
        root.add(center, BorderLayout.CENTER);
        if (bottom != null) root.add(bottom, BorderLayout.SOUTH);
        root.revalidate();
        root.repaint();
    }

    private static void await(CountDownLatch latch) {
        waiting = latch;
        if (closed) return;
        try { latch.await(); } catch (InterruptedException ignored) {}
    }

    private static JPanel column(Component... parts) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(0, 40, 0, 40));
        p.add(Box.createVerticalGlue());
        for (Component c : parts) p.add(c);
        p.add(Box.createVerticalGlue());
        return p;
    }

    private static JPanel bottomRow(JComponent left, JComponent right) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(0, PAD, PAD, PAD));
        if (left != null) p.add(left, BorderLayout.WEST);
        if (right != null) p.add(right, BorderLayout.EAST);
        return p;
    }

    // ---- screens -----------------------------------------------------------------------

    // main
    static int selectInstance(List<String> names) {
        ensureFrame();
        if (closed) return -1;
        busy = false;
        List<String> items = new java.util.ArrayList<>(names);
        items.add(CREATE_NEW);
        int[] result = { -1 };
        CountDownLatch done = new CountDownLatch(1);

        McUi.runAndWait(() -> {
            McUi.McDropdown dropdown = new McUi.McDropdown(items, 180);
            dropdown.setSelectedIndex(lastChoice);

            JPanel center = new JPanel();
            center.setOpaque(false);
            center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
            center.setBorder(BorderFactory.createEmptyBorder(36, 30, 0, 30));

            JLabel icon = new JLabel(new ImageIcon(McUi.ICON));
            icon.setAlignmentX(Component.CENTER_ALIGNMENT);

            center.add(new McUi.McLabel("Welcome to the DOTS Update Utility\u2122!", 2, SIZE - 60));
            center.add(Box.createVerticalStrut(26));
            center.add(icon);
            center.add(Box.createVerticalStrut(26));
            center.add(new McUi.McLabel("Please choose an existing DOTS instance or create a new one", 2, 420));
            center.add(Box.createVerticalStrut(22));
            center.add(dropdown);

            setScreen(center, bottomRow(null, new McUi.McButton("Next", 65, () -> {
                result[0] = dropdown.selectedIndex();
                lastChoice = result[0];
                done.countDown();
            })));
        });

        await(done);
        return closed ? -1 : result[0];
    }

    // question screen
    static boolean confirm(String text, String yesLabel) {
        ensureFrame();
        if (closed) return false;
        busy = false;
        boolean[] result = { false };
        CountDownLatch done = new CountDownLatch(1);
        McUi.runAndWait(() -> setScreen(
                column(new McUi.McLabel(text, 2, SIZE - 100)),
                bottomRow(new McUi.McButton("Back", 65, done::countDown),
                        new McUi.McButton(yesLabel, 65, () -> { result[0] = true; done.countDown(); }))));
        await(done);
        return !closed && result[0];
    }

    // info/error screen
    static void message(String text) {
        ensureFrame();
        if (closed) return;
        busy = false;
        CountDownLatch done = new CountDownLatch(1);
        McUi.runAndWait(() -> setScreen(
                column(new McUi.McLabel(text, 2, SIZE - 100)),
                bottomRow(null, new McUi.McButton("Done", 65, done::countDown))));
        await(done);
    }

    // progress screen
    static PackInstaller.Progress progress(String header) {
        ensureFrame();
        busy = true;
        McUi.McLabel stage = new McUi.McLabel("Starting...", 2, SIZE - 100);
        McUi.McProgress bar = new McUi.McProgress();
        McUi.runAndWait(() -> setScreen(
                column(new McUi.McLabel(header, 2, SIZE - 100), Box.createVerticalStrut(8), stage,
                        Box.createVerticalStrut(12), bar),
                null));
        return (msg, frac) -> SwingUtilities.invokeLater(() -> { stage.setText(msg); bar.setFraction(frac); });
    }
}