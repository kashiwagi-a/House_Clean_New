package org.example;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.util.*;
import java.util.List;
import java.util.logging.Logger;

/**
 *
 * 未チェックイン部屋設定ダイアログ
 * 状態「1」（未チェックイン）の部屋に対して、状態（2/3/4）とエコ清掃フラグを設定する
 */
public class PendingRoomSelectionDialog extends JDialog {
    private static final Logger LOGGER = Logger.getLogger(PendingRoomSelectionDialog.class.getName());

    private JTable roomTable;
    private DefaultTableModel tableModel;
    private Map<String, PendingRoomInfo> pendingRoomData;
    private boolean dialogResult = false;

    /**
     * 未チェックイン部屋情報クラス
     */
    public static class PendingRoomInfo {
        public final String roomNumber;
        public final String roomType;
        public final int floor;
        public final String building;
        public String newStatus;   // "1"(未変更) / "2"(チェックアウト) / "3"(連泊) / "4"(清掃要)
        public boolean isEco;      // エコ清掃フラグ

        public PendingRoomInfo(String roomNumber, String roomType, int floor, String building) {
            this.roomNumber = roomNumber;
            this.roomType = roomType;
            this.floor = floor;
            this.building = building;
            this.newStatus = "1";  // デフォルトは未変更
            this.isEco = false;
        }

        public String getStatusDisplay() {
            switch (newStatus) {
                case "2": return "チェックアウト";
                case "3": return "連泊";
                case "4": return "清掃要";
                default:  return "未設定";
            }
        }

        @Override
        public String toString() {
            return roomNumber + " (" + roomType + ", " + floor + "階, " + building + ")";
        }
    }

    /**
     * コンストラクタ
     */
    public PendingRoomSelectionDialog(JFrame parent, File roomDataFile) {
        super(parent, "未チェックイン部屋設定", true);
        this.pendingRoomData = new LinkedHashMap<>();

        loadPendingRoomData(roomDataFile);
        restorePreviousSettings();
        initializeGUI();
        setSize(700, 450);
        setLocationRelativeTo(parent);
    }

    /**
     * CSVから状態「1」の部屋を読み込む
     */
    private void loadPendingRoomData(File file) {
        if (file == null) {
            LOGGER.warning("部屋データファイルが指定されていません");
            return;
        }

        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader(file, java.nio.charset.StandardCharsets.UTF_8))) {

            String line;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber <= 0) continue;

                String[] parts = line.split(",");
                if (parts.length < 7) continue;

                String roomNumber   = parts.length > 1 ? parts[1].trim() : "";
                String roomTypeCode = parts.length > 2 ? parts[2].trim() : "";
                String brokenStatus = parts.length > 5 ? parts[5].trim() : "";
                String roomStatus   = parts.length > 6 ? parts[6].trim() : "";

                // 故障部屋は対象外、状態「1」のみ対象
                if (!"1".equals(roomStatus) || "1".equals(brokenStatus)) continue;
                if (roomNumber.isEmpty()) continue;

                String roomType = determineRoomType(roomTypeCode);
                int floor       = extractFloor(roomNumber);
                String building = isAnnexRoom(roomNumber) ? "別館" : "本館";

                PendingRoomInfo info = new PendingRoomInfo(roomNumber, roomType, floor, building);
                pendingRoomData.put(roomNumber, info);

                LOGGER.info("未チェックイン部屋を検出: " + roomNumber + " (" + roomType + ", " + floor + "階, " + building + ")");
            }

            LOGGER.info("未チェックイン部屋データ読み込み完了: " + pendingRoomData.size() + "室");

        } catch (Exception e) {
            LOGGER.severe("未チェックイン部屋データの読み込みエラー: " + e.getMessage());
            JOptionPane.showMessageDialog(this,
                    "未チェックイン部屋データの読み込みに失敗しました: " + e.getMessage(),
                    "エラー", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * 前回の設定をSystemPropertyから復元する
     */
    private void restorePreviousSettings() {
        String savedSettings = System.getProperty("pendingRoomSettings");
        if (savedSettings == null || savedSettings.trim().isEmpty()) return;

        for (String entry : savedSettings.split(",")) {
            String[] tokens = entry.split(":");
            if (tokens.length < 3) continue;
            String roomNumber = tokens[0].trim();
            String status     = tokens[1].trim();
            boolean eco       = Boolean.parseBoolean(tokens[2].trim());

            PendingRoomInfo info = pendingRoomData.get(roomNumber);
            if (info != null) {
                info.newStatus = status;
                info.isEco     = eco;
            }
        }
    }

    /**
     * GUI初期化
     */
    private void initializeGUI() {
        setLayout(new BorderLayout());

        // 上部説明パネル
        JPanel infoPanel = new JPanel(new BorderLayout());
        infoPanel.setBorder(BorderFactory.createTitledBorder("未チェックイン部屋設定"));

        JLabel infoLabel = new JLabel("<html><div style='padding:10px;'>" +
                "未チェックインの部屋の状態を設定できます。。<br>" +
                "設定しない場合は現状のままで清掃対象に含まれます。<br>" +
                "未チェックイン部屋数: " + pendingRoomData.size() + "室" +
                "</div></html>");
        infoPanel.add(infoLabel, BorderLayout.CENTER);
        add(infoPanel, BorderLayout.NORTH);

        // 中央テーブル
        createRoomTable();
        JScrollPane scrollPane = new JScrollPane(roomTable);
        add(scrollPane, BorderLayout.CENTER);

        // 下部ボタン
        JPanel buttonPanel = new JPanel(new FlowLayout());

        JButton clearAllButton = new JButton("全て未設定に戻す");
        clearAllButton.addActionListener(e -> clearAllSettings());
        buttonPanel.add(clearAllButton);

        buttonPanel.add(Box.createHorizontalStrut(20));

        JButton okButton = new JButton("設定完了");
        okButton.setFont(new Font("MS Gothic", Font.BOLD, 12));
        okButton.addActionListener(this::onOkClicked);
        buttonPanel.add(okButton);

        JButton cancelButton = new JButton("キャンセル");
        cancelButton.addActionListener(e -> dispose());
        buttonPanel.add(cancelButton);

        add(buttonPanel, BorderLayout.SOUTH);

        if (pendingRoomData.isEmpty()) {
            JLabel noDataLabel = new JLabel("未チェックインの部屋はありません", JLabel.CENTER);
            noDataLabel.setFont(new Font("MS Gothic", Font.PLAIN, 16));
            add(noDataLabel, BorderLayout.CENTER);
            okButton.setText("閉じる");
        }
    }

    /**
     * 部屋テーブルの作成
     */
    private void createRoomTable() {
        String[] columnNames = {"部屋番号", "部屋タイプ", "階", "建物", "設定する状態", "エコ清掃"};

        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public Class<?> getColumnClass(int col) {
                if (col == 4) return String.class;   // JComboBox用
                if (col == 5) return Boolean.class;  // チェックボックス用
                return String.class;
            }

            @Override
            public boolean isCellEditable(int row, int col) {
                return col == 4 || col == 5;
            }
        };

        // データ追加（部屋番号順）
        List<PendingRoomInfo> sorted = new ArrayList<>(pendingRoomData.values());
        sorted.sort(Comparator.comparing(r -> r.roomNumber));

        for (PendingRoomInfo info : sorted) {
            tableModel.addRow(new Object[]{
                    info.roomNumber,
                    info.roomType,
                    info.floor + "階",
                    info.building,
                    info.getStatusDisplay(),
                    info.isEco
            });
        }

        roomTable = new JTable(tableModel);
        roomTable.setRowHeight(26);
        roomTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // 状態列: JComboBox
        JComboBox<String> statusCombo = new JComboBox<>(
                new String[]{"未設定", "チェックアウト", "連泊", "清掃要"});
        roomTable.getColumnModel().getColumn(4).setCellEditor(
                new DefaultCellEditor(statusCombo));

        // エコ清掃列: チェックボックス
        roomTable.getColumnModel().getColumn(5).setCellRenderer(new TableCellRenderer() {
            private final JCheckBox cb = new JCheckBox();
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v,
                                                           boolean sel, boolean focus, int row, int col) {
                cb.setSelected(v != null && (Boolean) v);
                cb.setHorizontalAlignment(JLabel.CENTER);
                cb.setBackground(sel ? t.getSelectionBackground() : t.getBackground());
                cb.setOpaque(true);
                return cb;
            }
        });

        // 列幅
        roomTable.getColumnModel().getColumn(0).setPreferredWidth(90);
        roomTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        roomTable.getColumnModel().getColumn(2).setPreferredWidth(60);
        roomTable.getColumnModel().getColumn(3).setPreferredWidth(60);
        roomTable.getColumnModel().getColumn(4).setPreferredWidth(140);
        roomTable.getColumnModel().getColumn(5).setPreferredWidth(80);

        // テーブル変更をpendingRoomDataに反映
        tableModel.addTableModelListener(e -> {
            int row = e.getFirstRow();
            int col = e.getColumn();
            if (row < 0) return;

            String roomNumber = (String) tableModel.getValueAt(row, 0);
            PendingRoomInfo info = pendingRoomData.get(roomNumber);
            if (info == null) return;

            if (col == 4) {
                String display = (String) tableModel.getValueAt(row, 4);
                info.newStatus = displayToStatus(display);
            } else if (col == 5) {
                info.isEco = (Boolean) tableModel.getValueAt(row, 5);
            }
        });
    }

    /**
     * 表示文字列→状態コード変換
     */
    private String displayToStatus(String display) {
        switch (display) {
            case "チェックアウト": return "2";
            case "連泊":          return "3";
            case "清掃要":        return "4";
            default:              return "1";
        }
    }

    /**
     * 全設定を未設定に戻す
     */
    private void clearAllSettings() {
        for (int row = 0; row < tableModel.getRowCount(); row++) {
            tableModel.setValueAt("未設定", row, 4);
            tableModel.setValueAt(false,     row, 5);
        }
        pendingRoomData.values().forEach(info -> {
            info.newStatus = "1";
            info.isEco     = false;
        });
    }

    /**
     * OKボタン
     */
    private void onOkClicked(ActionEvent e) {
        // 編集中のセルを確定
        if (roomTable.isEditing()) {
            roomTable.getCellEditor().stopCellEditing();
        }

        dialogResult = true;

        // 設定内容をSystemPropertyに保存（形式: roomNumber:status:eco,…）
        StringBuilder sb = new StringBuilder();
        for (PendingRoomInfo info : pendingRoomData.values()) {
            if (sb.length() > 0) sb.append(",");
            sb.append(info.roomNumber).append(":").append(info.newStatus)
                    .append(":").append(info.isEco);
        }
        System.setProperty("pendingRoomSettings", sb.toString());

        LOGGER.info("未チェックイン部屋設定完了:");
        pendingRoomData.values().stream()
                .filter(info -> !"1".equals(info.newStatus) || info.isEco)
                .forEach(info -> LOGGER.info("  " + info.roomNumber
                        + " → 状態:" + info.getStatusDisplay()
                        + (info.isEco ? ", エコ清掃" : "")));

        dispose();
    }

    public boolean getDialogResult() { return dialogResult; }

    public Map<String, PendingRoomInfo> getPendingRoomData() {
        return new HashMap<>(pendingRoomData);
    }

    // ユーティリティメソッド（FileProcessorから複製）
    private String determineRoomType(String code) {
        if (code.equals("S") || code.equals("NS") || code.equals("ANS")
                || code.equals("ABF") || code.equals("AKS")) return "S";
        if (code.equals("D") || code.equals("ND") || code.equals("AND")) return "D";
        if (code.equals("T") || code.equals("NT") || code.equals("ANT")
                || code.equals("ADT")) return "T";
        if (code.equals("FD") || code.equals("NFD")) return "FD";
        return code;
    }

    private int extractFloor(String roomNumber) {
        try {
            String n = roomNumber.replaceAll("[^0-9]", "");
            if (n.length() == 3) return Integer.parseInt(n.substring(0, 1));
            if (n.length() == 4 && n.startsWith("10")) return 10;
            if (n.length() == 4) return Integer.parseInt(n.substring(0, 2));
        } catch (Exception ignored) {}
        return 0;
    }

    private boolean isAnnexRoom(String roomNumber) {
        String n = roomNumber.replaceAll("[^0-9]", "");
        if (n.length() == 3) return false;
        if (n.length() == 4 && n.startsWith("10")) return false;
        if (n.length() == 4) {
            try { return Integer.parseInt(n.substring(0, 2)) >= 22; }
            catch (NumberFormatException ignored) {}
        }
        return false;
    }
}