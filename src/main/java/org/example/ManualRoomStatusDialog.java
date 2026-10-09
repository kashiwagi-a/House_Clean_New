package org.example;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 手動入力モードダイアログ
 * 外部システムエラーで当日の部屋状態CSV・エコDBが取得できない場合に、
 * 過去のCSVから部屋一覧を読み込み、部屋ごとに状態（チェックアウト/連泊/空白=対象外）とエコ清掃を手動指定する。
 * 結果は「部屋状態を差し替えた一時CSV」として出力し、通常のCSVと同じ流れで処理できるようにする。
 */
public class ManualRoomStatusDialog extends JDialog {
    private static final Logger LOGGER = Logger.getLogger(ManualRoomStatusDialog.class.getName());

    private static final String[] STATUS_ITEMS = {"", "チェックアウト", "連泊"};
    private static final String[] ECO_ITEMS = {"", "エコ", "エコドア"};
    private static final int COL_ROOM = 0, COL_TYPE = 1, COL_BUILDING = 2, COL_STATUS = 3, COL_ECO = 4, COL_DUVET = 5;

    /** CSVの1行分（部屋行のみ列を差し替えられるよう保持） */
    private static class CsvLine {
        final String raw;
        final String[] parts;      // 部屋行のみ。それ以外は null
        final String roomNumber;
        final String roomTypeCode;
        final boolean broken;

        CsvLine(String raw, String[] parts) {
            this.raw = raw;
            this.parts = parts;
            this.roomNumber = parts != null ? parts[1].trim() : "";
            this.roomTypeCode = parts != null ? parts[2].trim() : "";
            this.broken = parts != null && "1".equals(parts[5].trim());
        }
    }

    private final List<CsvLine> lines = new ArrayList<>();
    private final List<CsvLine> roomLines = new ArrayList<>();
    private final File sourceFile;
    private static final Font TABLE_FONT = new Font("MS Gothic", Font.PLAIN, 18);
    private static final Font HEADER_FONT = new Font("MS Gothic", Font.BOLD, 16);
    private static final Font UI_FONT = new Font("MS Gothic", Font.PLAIN, 15);
    // 選択セルを含む行の強調色（セル選択色とは別）／選択不可のエコセルの色
    private static final Color ROW_HIGHLIGHT_COLOR = new Color(255, 242, 170);
    private static final Color ROW_HIGHLIGHT_DISABLED_COLOR = new Color(222, 214, 160);
    private static final Color DISABLED_COLOR = new Color(225, 225, 225);

    private JTable roomTable;
    private JLabel countLabel;
    private DefaultTableModel tableModel;
    private boolean dialogResult = false;
    private File generatedCsv;

    public ManualRoomStatusDialog(JFrame parent, File pastCsv) {
        super(parent, "手動入力モード（過去CSVから部屋状態を選定）", true);
        this.sourceFile = pastCsv;
        loadCsv(pastCsv);
        initializeGUI();
        // 高さはモニターの作業領域（タスクバーを除く）いっぱい、幅は表に合わせて中央に配置する
        GraphicsConfiguration gc = parent != null && parent.getGraphicsConfiguration() != null
                ? parent.getGraphicsConfiguration()
                : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        Rectangle screen = gc.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        int usableWidth = screen.width - insets.left - insets.right;
        int width = Math.min(usableWidth, Math.max(roomTable.getColumnModel().getTotalColumnWidth() + 70, 740));
        setBounds(screen.x + insets.left + (usableWidth - width) / 2, screen.y + insets.top,
                width, screen.height - insets.top - insets.bottom);
    }

    private void loadCsv(File file) {
        try {
            for (String raw : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String[] parts = raw.split(",", -1);
                // FileProcessor と同じ判定: 7列以上かつ部屋番号が空でない行のみ部屋行
                if (parts.length >= 7 && !parts[1].trim().isEmpty()) {
                    CsvLine line = new CsvLine(raw, parts);
                    lines.add(line);
                    roomLines.add(line);
                } else {
                    lines.add(new CsvLine(raw, null));
                }
            }
            LOGGER.info("手動入力モード: 過去CSVから " + roomLines.size() + "室を読み込み: " + file.getName());
        } catch (Exception e) {
            LOGGER.severe("過去CSVの読み込みエラー: " + e.getMessage());
            JOptionPane.showMessageDialog(this, "過去CSVの読み込みに失敗しました: " + e.getMessage(),
                    "エラー", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void initializeGUI() {
        setLayout(new BorderLayout());

        JPanel infoPanel = new JPanel(new BorderLayout());
        infoPanel.setBorder(BorderFactory.createTitledBorder("手動入力モード"));
        JLabel descLabel = new JLabel("<html><div style='padding:8px;'>" +
                "<b>本日の状態が空白の部屋は清掃対象になりません。</b><br>" +
                "エコ清掃は「エコ」または「エコドア」を選択します。<br>" +
                "デュベ（布団カバー交換）はチェックを付けた部屋が対象です。<br>" +
                "チェックアウトの部屋はエコ・デュベを選べません。エコ選択中の部屋もデュベを選べません。" +
                "</div></html>");
        descLabel.setFont(UI_FONT);
        infoPanel.add(descLabel, BorderLayout.CENTER);

        // 部屋数と、本日の清掃（チェックアウト/連泊）の部屋数を同じ行に表示
        countLabel = new JLabel();
        countLabel.setFont(new Font("MS Gothic", Font.BOLD, 18));
        countLabel.setBorder(BorderFactory.createEmptyBorder(2, 10, 8, 10));
        infoPanel.add(countLabel, BorderLayout.SOUTH);
        add(infoPanel, BorderLayout.NORTH);

        createRoomTable();
        tableModel.addTableModelListener(e -> updateCounts());
        updateCounts();
        add(new JScrollPane(roomTable), BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton clearButton = new JButton("全て空白に戻す");
        clearButton.setFont(UI_FONT);
        clearButton.addActionListener(e -> {
            stopEditing();
            for (int row = 0; row < tableModel.getRowCount(); row++) {
                tableModel.setValueAt("", row, COL_STATUS);
                tableModel.setValueAt("", row, COL_ECO);
                tableModel.setValueAt(false, row, COL_DUVET);
            }
        });
        buttonPanel.add(clearButton);

        buttonPanel.add(Box.createHorizontalStrut(20));

        JButton okButton = new JButton("設定完了");
        okButton.setFont(new Font("MS Gothic", Font.BOLD, 15));
        okButton.addActionListener(e -> onOkClicked());
        buttonPanel.add(okButton);

        JButton cancelButton = new JButton("キャンセル");
        cancelButton.setFont(UI_FONT);
        cancelButton.addActionListener(e -> dispose());
        buttonPanel.add(cancelButton);

        add(buttonPanel, BorderLayout.SOUTH);
    }

    private void createRoomTable() {
        String[] columnNames = {"部屋番号", "タイプ", "建物", "本日の状態", "エコ清掃", "デュベ"};

        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public Class<?> getColumnClass(int col) {
                return col == COL_DUVET ? Boolean.class : String.class;
            }

            @Override
            public boolean isCellEditable(int row, int col) {
                if (col == COL_ECO) return !isCheckout(row);                         // チェックアウトはエコ不可
                if (col == COL_DUVET) return !isCheckout(row) && !isEcoSelected(row); // チェックアウト・エコ選択中はデュベ不可
                return col == COL_STATUS;
            }

            @Override
            public void setValueAt(Object value, int row, int col) {
                boolean hasValue = value instanceof String && !((String) value).isEmpty();
                if (col == COL_ECO && hasValue && isCheckout(row)) {
                    return;  // チェックアウトの部屋はエコにできない
                }
                if (col == COL_DUVET && Boolean.TRUE.equals(value) && (isCheckout(row) || isEcoSelected(row))) {
                    return;  // チェックアウト・エコ選択中の部屋はデュベにできない
                }
                super.setValueAt(value, row, col);
                if (col == COL_ECO && hasValue) {
                    super.setValueAt(false, row, COL_DUVET);  // エコ選択時はデュベを解除
                }
                if (col == COL_STATUS && "チェックアウト".equals(value)) {
                    super.setValueAt("", row, COL_ECO);        // チェックアウト選択時はエコ・デュベを解除
                    super.setValueAt(false, row, COL_DUVET);
                }
            }

            private boolean isCheckout(int row) {
                return "チェックアウト".equals(getValueAt(row, COL_STATUS));
            }

            private boolean isEcoSelected(int row) {
                Object eco = getValueAt(row, COL_ECO);
                return eco instanceof String && !((String) eco).isEmpty();
            }
        };

        for (CsvLine line : roomLines) {
            String building = isAnnexRoom(line.roomNumber) ? "別館" : "本館";
            String type = line.roomTypeCode + (line.broken ? "（故障）" : "");
            tableModel.addRow(new Object[]{
                    line.roomNumber, type, building, "", "", false});
        }

        roomTable = new JTable(tableModel) {
            // 選択セルを含む行を、セル選択色とは別の色で強調表示する
            @Override
            public Component prepareRenderer(javax.swing.table.TableCellRenderer renderer, int row, int column) {
                Component c = super.prepareRenderer(renderer, row, column);
                if (!isCellSelected(row, column)) {
                    boolean disabledEco = convertColumnIndexToModel(column) == COL_ECO
                            && !getModel().isCellEditable(convertRowIndexToModel(row), COL_ECO);
                    if (isRowSelected(row)) {
                        c.setBackground(disabledEco ? ROW_HIGHLIGHT_DISABLED_COLOR : ROW_HIGHLIGHT_COLOR);
                    } else {
                        c.setBackground(disabledEco ? DISABLED_COLOR : getBackground());
                    }
                }
                return c;
            }
        };
        roomTable.setFont(TABLE_FONT);
        roomTable.setRowHeight(34);
        roomTable.getTableHeader().setFont(HEADER_FONT);
        roomTable.setAutoCreateRowSorter(true);
        // 列幅を固定して広がりすぎないようにする
        roomTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        JComboBox<String> statusCombo = new JComboBox<>(STATUS_ITEMS);
        statusCombo.setFont(TABLE_FONT);
        JComboBox<String> ecoCombo = new JComboBox<>(ECO_ITEMS);
        ecoCombo.setFont(TABLE_FONT);
        roomTable.getColumnModel().getColumn(COL_STATUS).setCellEditor(new DefaultCellEditor(statusCombo));
        roomTable.getColumnModel().getColumn(COL_ECO).setCellEditor(new DefaultCellEditor(ecoCombo));
        // デュベ列: 選択不可（チェックアウト・エコ選択中）はチェックボックスを無効表示にする
        roomTable.getColumnModel().getColumn(COL_DUVET).setCellRenderer(new javax.swing.table.TableCellRenderer() {
            private final JCheckBox cb = new JCheckBox();
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v,
                                                           boolean sel, boolean focus, int row, int col) {
                int modelRow = t.convertRowIndexToModel(row);
                cb.setSelected(Boolean.TRUE.equals(v));
                cb.setEnabled(t.getModel().isCellEditable(modelRow, COL_DUVET));
                cb.setHorizontalAlignment(JLabel.CENTER);
                cb.setBackground(sel ? t.getSelectionBackground() : t.getBackground());
                cb.setOpaque(true);
                return cb;
            }
        });
        roomTable.getColumnModel().getColumn(COL_ROOM).setPreferredWidth(100);
        roomTable.getColumnModel().getColumn(COL_TYPE).setPreferredWidth(130);
        roomTable.getColumnModel().getColumn(COL_BUILDING).setPreferredWidth(80);
        roomTable.getColumnModel().getColumn(COL_STATUS).setPreferredWidth(160);
        roomTable.getColumnModel().getColumn(COL_ECO).setPreferredWidth(120);
        roomTable.getColumnModel().getColumn(COL_DUVET).setPreferredWidth(80);

        installClipboardSupport();
    }

    /**
     * Ctrl+C / Ctrl+V 対応。
     * コピー: 選択セルをタブ/改行区切りのテキストとしてクリップボードへ（Excelへの貼り付けも可）。
     * 貼り付け: 1つの値を複数の選択セルへ一括貼り付け、または複数行を選択セルの先頭から順に貼り付け。
     * 編集できない列（部屋番号など）を起点にした場合は「本日の状態」列から貼り付ける。
     */
    private void installClipboardSupport() {
        roomTable.setCellSelectionEnabled(true);
        roomTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        roomTable.getActionMap().put("copy", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                copySelection();
            }
        });
        roomTable.getActionMap().put("paste", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                pasteToSelection();
            }
        });
    }

    private void copySelection() {
        int[] rows = roomTable.getSelectedRows();
        int[] cols = roomTable.getSelectedColumns();
        if (rows.length == 0 || cols.length == 0) return;

        StringBuilder sb = new StringBuilder();
        for (int r : rows) {
            for (int j = 0; j < cols.length; j++) {
                if (j > 0) sb.append('\t');
                Object v = roomTable.getValueAt(r, cols[j]);
                if (v instanceof Boolean) sb.append(((Boolean) v) ? "TRUE" : "FALSE");
                else sb.append(v == null ? "" : v.toString());
            }
            sb.append('\n');
        }
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(sb.toString()), null);
    }

    private void pasteToSelection() {
        String text;
        try {
            text = (String) Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(DataFlavor.stringFlavor);
        } catch (Exception ex) {
            return;
        }
        if (text == null || text.isEmpty()) return;

        stopEditing();
        int[] rows = roomTable.getSelectedRows();
        int[] cols = roomTable.getSelectedColumns();
        if (rows.length == 0 || cols.length == 0) return;

        text = text.replace("\r\n", "\n").replace('\r', '\n');
        if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
        String[] lineArr = text.split("\n", -1);
        String[][] grid = new String[lineArr.length][];
        for (int i = 0; i < lineArr.length; i++) grid[i] = lineArr[i].split("\t", -1);

        List<String> invalid = new ArrayList<>();
        boolean single = grid.length == 1 && grid[0].length == 1;

        if (single && rows.length * cols.length > 1) {
            // 1つの値を選択中の全セルへ一括貼り付け
            for (int r : rows) {
                for (int c : cols) setCellFromText(r, c, grid[0][0], invalid);
            }
        } else {
            int startCol = cols[0];
            int modelCol = roomTable.convertColumnIndexToModel(startCol);
            if (modelCol != COL_STATUS && modelCol != COL_ECO && modelCol != COL_DUVET) {
                startCol = roomTable.convertColumnIndexToView(COL_STATUS);
            }
            for (int i = 0; i < grid.length; i++) {
                int r = rows[0] + i;
                if (r >= roomTable.getRowCount()) break;
                for (int j = 0; j < grid[i].length; j++) {
                    int c = startCol + j;
                    if (c >= roomTable.getColumnCount()) break;
                    setCellFromText(r, c, grid[i][j], invalid);
                }
            }
        }

        if (!invalid.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "貼り付けできない値があったため、" + invalid.size() + "件を貼り付けませんでした。\n" +
                            "状態: 空白 / チェックアウト / 連泊\n" +
                            "エコ清掃: 空白 / エコ / エコドア\n" +
                            "デュベ: TRUE / FALSE\n" +
                            "（チェックアウトの部屋はエコ・デュベ不可、エコ選択中の部屋はデュベ不可）\n\n" +
                            String.join(", ", invalid.subList(0, Math.min(invalid.size(), 10))),
                    "貼り付けの警告", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** 貼り付け値をセルへ反映する（編集対象外の列は無視、認識できない値は invalid に記録） */
    private void setCellFromText(int viewRow, int viewCol, String raw, List<String> invalid) {
        int modelCol = roomTable.convertColumnIndexToModel(viewCol);
        String v = raw == null ? "" : raw.trim();
        if (modelCol == COL_STATUS) {
            String status = parseStatus(v);
            if (status == null) invalid.add(v);
            else roomTable.setValueAt(status, viewRow, viewCol);
        } else if (modelCol == COL_ECO) {
            String eco = parseEco(v);
            if (eco == null) {
                invalid.add(v);
            } else if (!eco.isEmpty() && !roomTable.getModel().isCellEditable(
                    roomTable.convertRowIndexToModel(viewRow), COL_ECO)) {
                invalid.add("エコ（チェックアウトの部屋）");
            } else {
                roomTable.setValueAt(eco, viewRow, viewCol);
            }
        } else if (modelCol == COL_DUVET) {
            Boolean duvet = parseDuvet(v);
            if (duvet == null) {
                invalid.add(v);
            } else if (duvet && !roomTable.getModel().isCellEditable(
                    roomTable.convertRowIndexToModel(viewRow), COL_DUVET)) {
                invalid.add("デュベ（チェックアウト・エコ選択中の部屋）");
            } else {
                roomTable.setValueAt(duvet, viewRow, viewCol);
            }
        }
    }

    private static String parseStatus(String v) {
        switch (v.toLowerCase()) {
            case "":
            case "対象外":
            case "空白":
            case "1":
                return "";
            case "チェックアウト":
            case "co":
            case "c/o":
            case "2":
                return "チェックアウト";
            case "連泊":
            case "3":
                return "連泊";
            default:
                return null;
        }
    }

    private static String parseEco(String v) {
        switch (v.toLowerCase()) {
            case "":
            case "false":
            case "0":
            case "-":
                return "";
            case "エコ":
            case "eco":
            case "true":
            case "1":
            case "○":
            case "〇":
                return "エコ";
            case "エコドア":
            case "ecodoor":
            case "eco door":
                return "エコドア";
            default:
                return null;
        }
    }

    private static Boolean parseDuvet(String v) {
        switch (v.toLowerCase()) {
            case "true": case "1": case "○": case "〇": case "デュベ": case "✓": case "☑":
                return Boolean.TRUE;
            case "": case "false": case "0": case "-": case "☐":
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    /** 部屋数と、本日の清掃（チェックアウト/連泊）の部屋数を表示する */
    private void updateCounts() {
        int checkout = 0, stay = 0;
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            Object st = tableModel.getValueAt(i, COL_STATUS);
            if ("チェックアウト".equals(st)) checkout++;
            else if ("連泊".equals(st)) stay++;
        }
        countLabel.setText("部屋数: " + tableModel.getRowCount() + "室　　本日の清掃: "
                + (checkout + stay) + "室（チェックアウト " + checkout + "室 / 連泊 " + stay + "室）");
    }

    private void stopEditing() {
        if (roomTable.isEditing()) {
            roomTable.getCellEditor().stopCellEditing();
        }
    }

    private void onOkClicked() {
        stopEditing();

        // 状態が空白なのにエコ/デュベが付いている部屋は矛盾するため警告
        List<String> invalid = new ArrayList<>();
        int target = 0;
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            String st = (String) tableModel.getValueAt(i, COL_STATUS);
            boolean excluded = st == null || st.isEmpty();
            String eco = (String) tableModel.getValueAt(i, COL_ECO);
            boolean hasEco = eco != null && !eco.isEmpty();
            boolean duvet = Boolean.TRUE.equals(tableModel.getValueAt(i, COL_DUVET));
            if (excluded && (hasEco || duvet)) invalid.add((String) tableModel.getValueAt(i, COL_ROOM));
            if (!excluded) target++;
        }
        if (!invalid.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "以下の部屋は状態が空白（清掃対象外）のためエコ清掃・デュベを設定できません。\n" +
                            "状態を設定するか、エコ清掃・デュベの指定を外してください。\n\n" + String.join(", ", invalid),
                    "設定エラー", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (target == 0) {
            int ans = JOptionPane.showConfirmDialog(this,
                    "清掃対象の部屋が1室もありません。このまま確定しますか？",
                    "確認", JOptionPane.YES_NO_OPTION);
            if (ans != JOptionPane.YES_OPTION) return;
        }

        try {
            generatedCsv = writeGeneratedCsv();
        } catch (Exception e) {
            LOGGER.severe("手動入力CSVの生成に失敗: " + e.getMessage());
            JOptionPane.showMessageDialog(this, "一時CSVの生成に失敗しました: " + e.getMessage(),
                    "エラー", JOptionPane.ERROR_MESSAGE);
            return;
        }
        dialogResult = true;
        dispose();
    }

    /**
     * 画面の指定内容を反映した一時CSVを出力する。
     * 空白（対象外）は状態「1」（未チェックイン＝清掃対象外）に置き換える。
     * 故障フラグ等は過去CSVのまま保持する（故障部屋は従来どおり故障部屋設定で扱う）。
     */
    private File writeGeneratedCsv() throws Exception {
        String[] newStatus = new String[roomLines.size()];
        Set<String> ecoRooms = new LinkedHashSet<>();     // 「部屋番号:エコ」「部屋番号:エコドア」
        Set<String> duvetRooms = new LinkedHashSet<>();
        for (int i = 0; i < roomLines.size(); i++) {
            String item = (String) tableModel.getValueAt(i, COL_STATUS);
            newStatus[i] = "チェックアウト".equals(item) ? "2" : "連泊".equals(item) ? "3" : "1";
            String eco = (String) tableModel.getValueAt(i, COL_ECO);
            if (eco != null && !eco.isEmpty()) {
                ecoRooms.add(roomLines.get(i).roomNumber + ":" + eco);
            }
            if (Boolean.TRUE.equals(tableModel.getValueAt(i, COL_DUVET))) {
                duvetRooms.add(roomLines.get(i).roomNumber);
            }
        }

        List<String> out = new ArrayList<>();
        int idx = 0;
        for (CsvLine line : lines) {
            if (line.parts == null) {
                out.add(line.raw);
                continue;
            }
            String[] parts = line.parts.clone();
            parts[6] = newStatus[idx++];
            out.add(String.join(",", parts));
        }

        File tmp = File.createTempFile("manual_rooms_", ".csv");
        tmp.deleteOnExit();
        Files.write(tmp.toPath(), out, StandardCharsets.UTF_8);

        // エコ清掃（部屋番号:種別のカンマ区切り）・デュベ（部屋番号のカンマ区切り）。
        // FileProcessor がDBの代わりに使用する
        System.setProperty("manualEcoRooms", String.join(",", ecoRooms));
        System.setProperty("manualDuvetRooms", String.join(",", duvetRooms));
        LOGGER.info("手動入力モード: 一時CSVを生成 " + tmp.getAbsolutePath()
                + " (エコ指定: " + ecoRooms.size() + "室, デュベ指定: " + duvetRooms.size() + "室)");
        return tmp;
    }

    public boolean getDialogResult() { return dialogResult; }

    public File getGeneratedCsv() { return generatedCsv; }

    private boolean isAnnexRoom(String roomNumber) {
        String n = roomNumber.replaceAll("[^0-9]", "");
        if (n.length() == 4 && !n.startsWith("10")) {
            try { return Integer.parseInt(n.substring(0, 2)) >= 22; }
            catch (NumberFormatException ignored) {}
        }
        return false;
    }
}
