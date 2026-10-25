package org.example;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.TitledBorder;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.ContainerEvent;
import java.awt.event.ContainerListener;
import java.awt.event.WindowEvent;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 読みやすさのため、ウィンドウ内の文字と部品サイズをまとめて拡大するユーティリティ。
 * 指定した親ウィンドウから開かれる設定ウィンドウ（ダイアログ）を、開いた時点で自動的に拡大する。
 */
final class UiScale {

    /** 拡大率（12pt → 15pt 相当） */
    static final float FACTOR = 1.25f;

    /** 拡大後の最低文字サイズ */
    static final float MIN_FONT_SIZE = 15f;

    private static final Set<Window> roots = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<Object> scaled = Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean listenerInstalled = false;

    private UiScale() {
    }

    /**
     * owner から開かれる（owner を祖先に持つ）ウィンドウを、表示時に自動で拡大する。
     * owner 自身は拡大しない。
     */
    static synchronized void enlargeWindowsOwnedBy(Window owner) {
        roots.add(owner);
        if (listenerInstalled) {
            return;
        }
        listenerInstalled = true;
        AWTEventListener listener = event -> {
            if (event.getID() != WindowEvent.WINDOW_OPENED) {
                return;
            }
            Window w = ((WindowEvent) event).getWindow();
            if (!scaled.contains(w) && isOwnedByRoot(w)) {
                enlarge(w);
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.WINDOW_EVENT_MASK);
    }

    private static boolean isOwnedByRoot(Window w) {
        for (Window o = w.getOwner(); o != null; o = o.getOwner()) {
            if (roots.contains(o)) {
                return true;
            }
        }
        return false;
    }

    /**
     * ウィンドウ内の文字・部品だけを拡大する（ウィンドウのサイズは変えない）。
     * このウィンドウから開くダイアログも自動で拡大されるようにする。
     */
    static void enlargeContents(Window w) {
        enlargeTree(w);
        enlargeWindowsOwnedBy(w);
    }

    /** ウィンドウを、表示している画面の作業領域（メニューバー・Dockを除く）いっぱいに広げる */
    static void fitToScreen(Window w) {
        GraphicsConfiguration gc = w.getOwner() != null
                ? w.getOwner().getGraphicsConfiguration() : w.getGraphicsConfiguration();
        Rectangle bounds = gc.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        w.setBounds(bounds.x + insets.left, bounds.y + insets.top,
                bounds.width - insets.left - insets.right,
                bounds.height - insets.top - insets.bottom);
    }

    /** ウィンドウ内の文字・部品を拡大し、ウィンドウ自体も画面内に収まる範囲で広げる */
    static void enlarge(Window w) {
        enlargeTree(w);

        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int width = Math.min(scale(w.getWidth()), screen.width);
        int height = Math.min(scale(w.getHeight()), screen.height);
        w.setSize(width, height);
        w.setLocationRelativeTo(w.getOwner());
        w.validate();
    }

    /** 部品ツリーを再帰的に拡大し、後から追加される部品も拡大されるようにする */
    private static void enlargeTree(Component c) {
        if (!scaled.add(c)) {
            return;
        }

        // 明示的に設定されたフォントだけを拡大（親から継承したフォントの二重拡大を防ぐ）
        if (c.isFontSet() && c.getFont() != null) {
            c.setFont(scaleFont(c.getFont()));
        }

        if (c instanceof JComponent) {
            JComponent jc = (JComponent) c;
            if (jc.isPreferredSizeSet()) {
                jc.setPreferredSize(scale(jc.getPreferredSize()));
            }
            if (jc.isMinimumSizeSet()) {
                jc.setMinimumSize(scale(jc.getMinimumSize()));
            }
            if (jc.isMaximumSizeSet()) {
                jc.setMaximumSize(scale(jc.getMaximumSize()));
            }
            enlargeBorder(jc.getBorder());
        }

        if (c instanceof JTable) {
            enlargeTable((JTable) c);
        }

        if (c instanceof Container) {
            Container container = (Container) c;
            for (Component child : container.getComponents()) {
                enlargeTree(child);
            }
            // removeAll() → 再構築するパネルにも対応
            container.addContainerListener(new ContainerListener() {
                @Override
                public void componentAdded(ContainerEvent e) {
                    enlargeTree(e.getChild());
                }

                @Override
                public void componentRemoved(ContainerEvent e) {
                }
            });
        }
    }

    private static void enlargeTable(JTable table) {
        table.setRowHeight(scale(table.getRowHeight()));
        JTableHeader header = table.getTableHeader();
        if (header != null) {
            enlargeTree(header);
        }
        // セル編集中に表示される部品（コンボボックス・テキスト欄など）
        for (Class<?> type : new Class<?>[]{Object.class, Number.class, Boolean.class}) {
            enlargeEditor(table.getDefaultEditor(type));
        }
        for (int i = 0; i < table.getColumnCount(); i++) {
            enlargeEditor(table.getColumnModel().getColumn(i).getCellEditor());
        }
    }

    private static void enlargeEditor(Object editor) {
        if (editor instanceof DefaultCellEditor) {
            enlargeTree(((DefaultCellEditor) editor).getComponent());
        }
    }

    private static void enlargeBorder(Border border) {
        if (border == null || !scaled.add(border)) {
            return;
        }
        if (border instanceof TitledBorder) {
            TitledBorder tb = (TitledBorder) border;
            Font f = tb.getTitleFont() != null ? tb.getTitleFont() : UIManager.getFont("TitledBorder.font");
            if (f != null) {
                tb.setTitleFont(scaleFont(f));
            }
        } else if (border instanceof javax.swing.border.CompoundBorder) {
            enlargeBorder(((javax.swing.border.CompoundBorder) border).getOutsideBorder());
            enlargeBorder(((javax.swing.border.CompoundBorder) border).getInsideBorder());
        }
    }

    private static Font scaleFont(Font f) {
        // 元が小さすぎる文字（10〜11pt）も読めるよう、最低サイズを保証する
        return f.deriveFont(Math.max(f.getSize2D() * FACTOR, MIN_FONT_SIZE));
    }

    private static Dimension scale(Dimension d) {
        return new Dimension(scale(d.width), scale(d.height));
    }

    /** 0 や Integer.MAX_VALUE（制限なし）はそのまま */
    private static int scale(int v) {
        if (v <= 0 || v >= Short.MAX_VALUE) {
            return v;
        }
        return Math.round(v * FACTOR);
    }
}
