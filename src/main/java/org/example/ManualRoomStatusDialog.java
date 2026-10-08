package org.example;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
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
 * 過去のCSVから部屋一覧を読み込み、部屋ごとに状態（チェックアウト/連泊/対象外）とエコ清掃を手動指定する。
 * 結果は「部屋状態を差し替えた一時CSV」として出力し、通常のCSVと同じ流れで処理できるようにする。
 */
public class ManualRoomStatusDialog extends JDialog {
    private static final Logger LOGGER = Logger.getLogger(ManualRoomStatusDialog.class.getName());

    private static final String[] STATUS_ITEMS = {"対象外", "チェックアウト", "連泊"};
    private static final int COL_ROOM = 0, COL_TYPE = 1, COL_BUILDING = 2, COL_PAST = 3, COL_STATUS = 4, COL_ECO = 5;

    /** CSVの1行分（部屋行のみ列を差し替えられるよう保持） */
    private static class CsvLine {
        final String raw;
        final String[] parts;      // 部屋行のみ。それ以外は null
        final String roomNumber;
        final String roomTypeCode;
        final String pastStatus;
        final boolean broken;

        CsvLine(String raw, String[] parts) {
            this.raw = raw;
            this.parts = parts;
            this.roomNumber = parts != null ? parts[1].trim() : "";
            this.roomTypeCode = parts != null ? parts[2].trim() : "";
            this.broken = parts != null && "1".equals(parts[5].trim());
            this.pastStatus = parts != null ? parts[6].trim() : "";
        }
    }

    private final List<CsvLine> lines = new ArrayList<>();
    private final List<CsvLine> roomLines = new ArrayList<>();
    private final File sourceFile;
    private JTable roomTable;
    private DefaultTableModel tableModel;
    private boolean dialogResult = false;
    private File generatedCsv;

    public ManualRoomStatusDialog(JFrame parent, File pastCsv) {
        super(parent, "手動入力モード（過去CSVから部屋状態を選定）", true);
        this.sourceFile = pastCsv;
        loadCsv(pastCsv);
        initializeGUI();
        setSize(780, 560);
        setLocationRelativeTo(parent);
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
        infoPanel.add(new JLabel("<html><div style='padding:8px;'>" +
                "過去CSV（" + sourceFile.getName() + "）の部屋一覧をもとに、本日の状態を部屋ごとに指定します。<br>" +
                "<b>「対象外」の部屋は清掃対象になりません。</b>エコ清掃はチェックを付けた部屋のみ対象です。<br>" +
                "（エコDBは使用せず、ここで指定した内容のみ反映されます）<br>" +
                "部屋数: " + roomLines.size() + "室" +
                "</div></html>"), BorderLayout.CENTER);
        add(infoPanel, BorderLayout.NORTH);

        createRoomTable();
        add(new JScrollPane(roomTable), BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton inheritButton = new JButton("過去の状態を引き継ぐ");
        inheritButton.addActionListener(e -> applyPastStatuses());
        buttonPanel.add(inheritButton);

        JButton clearButton = new JButton("全て対象外にする");
        clearButton.addActionListener(e -> {
            stopEditing();
            for (int row = 0; row < tableModel.getRowCount(); row++) {
                tableModel.setValueAt("対象外", row, COL_STATUS);
                tableModel.setValueAt(false, row, COL_ECO);
            }
        });
        buttonPanel.add(clearButton);

        buttonPanel.add(Box.createHorizontalStrut(20));

        JButton okButton = new JButton("設定完了");
        okButton.setFont(new Font("MS Gothic", Font.BOLD, 12));
        okButton.addActionListener(e -> onOkClicked());
        buttonPanel.add(okButton);

        JButton cancelButton = new JButton("キャンセル");
        cancelButton.addActionListener(e -> dispose());
        buttonPanel.add(cancelButton);

        add(buttonPanel, BorderLayout.SOUTH);
    }

    private void createRoomTable() {
        String[] columnNames = {"部屋番号", "部屋タイプ", "建物", "過去CSVの状態", "本日の状態", "エコ清掃"};

        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public Class<?> getColumnClass(int col) {
                return col == COL_ECO ? Boolean.class : String.class;
            }

            @Override
            public boolean isCellEditable(int row, int col) {
                return col == COL_STATUS || col == COL_ECO;
            }
        };

        for (CsvLine line : roomLines) {
            String building = isAnnexRoom(line.roomNumber) ? "別館" : "本館";
            String type = line.roomTypeCode + (line.broken ? "（故障）" : "");
            tableModel.addRow(new Object[]{
                    line.roomNumber, type, building, statusLabel(line.pastStatus),
                    defaultStatusItem(line), false});
        }

        roomTable = new JTable(tableModel);
        roomTable.setRowHeight(26);
        roomTable.setAutoCreateRowSorter(true);
        roomTable.getColumnModel().getColumn(COL_STATUS)
                .setCellEditor(new DefaultCellEditor(new JComboBox<>(STATUS_ITEMS)));
        roomTable.getColumnModel().getColumn(COL_ROOM).setPreferredWidth(90);
        roomTable.getColumnModel().getColumn(COL_TYPE).setPreferredWidth(110);
        roomTable.getColumnModel().getColumn(COL_BUILDING).setPreferredWidth(60);
        roomTable.getColumnModel().getColumn(COL_PAST).setPreferredWidth(120);
        roomTable.getColumnModel().getColumn(COL_STATUS).setPreferredWidth(130);
        roomTable.getColumnModel().getColumn(COL_ECO).setPreferredWidth(70);
    }

    /** 過去CSVの状態を初期値に引き継ぐ（チェックアウト/連泊のみ。故障部屋は対象外） */
    private String defaultStatusItem(CsvLine line) {
        if (line.broken) return "対象外";
        switch (line.pastStatus) {
            case "2": return "チェックアウト";
            case "3": return "連泊";
            default:  return "対象外";
        }
    }

    private void applyPastStatuses() {
        stopEditing();
        for (int i = 0; i < roomLines.size(); i++) {
            tableModel.setValueAt(defaultStatusItem(roomLines.get(i)), i, COL_STATUS);
            tableModel.setValueAt(false, i, COL_ECO);
        }
    }

    private static String statusLabel(String status) {
        switch (status) {
            case "0": return "0 未販売";
            case "1": return "1 未チェックイン";
            case "2": return "2 チェックアウト";
            case "3": return "3 連泊";
            default:  return status;
        }
    }

    private void stopEditing() {
        if (roomTable.isEditing()) {
            roomTable.getCellEditor().stopCellEditing();
        }
    }

    private void onOkClicked() {
        stopEditing();

        // 対象外なのにエコが付いている部屋は矛盾するため警告
        List<String> invalid = new ArrayList<>();
        int target = 0;
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            boolean excluded = "対象外".equals(tableModel.getValueAt(i, COL_STATUS));
            boolean eco = Boolean.TRUE.equals(tableModel.getValueAt(i, COL_ECO));
            if (excluded && eco) invalid.add((String) tableModel.getValueAt(i, COL_ROOM));
            if (!excluded) target++;
        }
        if (!invalid.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "以下の部屋は「対象外」のためエコ清掃を設定できません。\n" +
                            "状態を設定するか、エコ清掃のチェックを外してください。\n\n" + String.join(", ", invalid),
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
     * 対象外は状態「1」（未チェックイン＝清掃対象外）に置き換える。
     * 故障フラグ等は過去CSVのまま保持する（故障部屋は従来どおり故障部屋設定で扱う）。
     */
    private File writeGeneratedCsv() throws Exception {
        String[] newStatus = new String[roomLines.size()];
        Set<String> ecoRooms = new LinkedHashSet<>();
        for (int i = 0; i < roomLines.size(); i++) {
            String item = (String) tableModel.getValueAt(i, COL_STATUS);
            newStatus[i] = "チェックアウト".equals(item) ? "2" : "連泊".equals(item) ? "3" : "1";
            if (Boolean.TRUE.equals(tableModel.getValueAt(i, COL_ECO))) {
                ecoRooms.add(roomLines.get(i).roomNumber);
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

        // エコ清掃（部屋番号のカンマ区切り）。FileProcessor がDBの代わりに使用する
        System.setProperty("manualEcoRooms", String.join(",", ecoRooms));
        LOGGER.info("手動入力モード: 一時CSVを生成 " + tmp.getAbsolutePath()
                + " (エコ指定: " + ecoRooms.size() + "室)");
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
