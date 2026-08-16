package org.example;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.util.*;
import java.util.List;
import java.util.logging.Logger;

/**
 * レイトアウト部屋設定ダイアログ
 * 清掃対象の部屋（状態1〜4、故障除く）に対して、アウト予定時刻を自由入力で設定する。
 * 設定した部屋は調整画面の備考に「〇〇以降清掃」として自動登録され、
 * 部屋割り振りでは大浴場清掃スタッフへの割り当てを極力避ける。
 */
public class LateOutRoomSelectionDialog extends JDialog {
    private static final Logger LOGGER = Logger.getLogger(LateOutRoomSelectionDialog.class.getName());

    private JTable roomTable;
    private DefaultTableModel tableModel;
    private Map<String, LateOutRoomInfo> lateOutRoomData;
    private boolean dialogResult = false;

    /**
     * レイトアウト部屋情報クラス
     */
    public static class LateOutRoomInfo {
        public final String roomNumber;
        public final String roomType;
        public final int floor;
        public final String building;
        public final String roomStatus;  // 状態コード（pendingRoomSettings上書き適用後）
        public String lateOutTime;       // アウト予定時刻（空 = 設定なし）

        public LateOutRoomInfo(String roomNumber, String roomType, int floor, String building, String roomStatus) {
            this.roomNumber = roomNumber;
            this.roomType = roomType;
            this.floor = floor;
            this.building = building;
            this.roomStatus = roomStatus;
            this.lateOutTime = "";
        }

        public String getStatusDisplay() {
            switch (roomStatus) {
                case "1": return "未チェックイン";
                case "2": return "チェックアウト";
                case "3": return "連泊";
                case "4": return "時間延長";
                default:  return roomStatus;
            }
        }

        @Override
        public String toString() {
            return roomNumber + " (" + roomType + ", " + floor + "階, " + building + ") → " + lateOutTime;
        }
    }

    /**
     * コンストラクタ
     */
    public LateOutRoomSelectionDialog(JFrame parent, File roomDataFile) {
        super(parent, "レイトアウト部屋設定", true);
        this.lateOutRoomData = new LinkedHashMap<>();

        loadLateOutRoomData(roomDataFile);
        restorePreviousSettings();
        initializeGUI();
        setSize(700, 450);
        setLocationRelativeTo(parent);
    }

    /**
     * CSVから清掃対象の部屋（状態1〜4、故障除く）を読み込む
     */
    private void loadLateOutRoomData(File file) {
        if (file == null) {
            LOGGER.warning("部屋データファイルが指定されていません");
            return;
        }

        // 未チェックイン設定による状態上書きと、残し部屋(事前設定)を考慮する
        Map<String, String> pendingOverrides = getPendingStatusOverrides();
        Set<String> preExcludedRooms = getPreExcludedRoomNumbers();

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

                if (roomNumber.isEmpty()) continue;

                // 故障部屋は対象外
                if ("1".equals(brokenStatus)) continue;

                // 未チェックイン設定で状態が変更されていれば上書き後の状態で判定
                if ("1".equals(roomStatus) && pendingOverrides.containsKey(roomNumber)) {
                    roomStatus = pendingOverrides.get(roomNumber);
                }

                // 清掃対象（状態1〜4）のみ対象
                if (!"1".equals(roomStatus) && !"2".equals(roomStatus)
                        && !"3".equals(roomStatus) && !"4".equals(roomStatus)) continue;

                // 残し部屋(事前設定)済みの部屋は清掃されないため対象外
                if (preExcludedRooms.contains(roomNumber)) continue;

                String roomType = determineRoomType(roomTypeCode);
                int floor       = extractFloor(roomNumber);
                String building = isAnnexRoom(roomNumber) ? "別館" : "本館";

                lateOutRoomData.put(roomNumber,
                        new LateOutRoomInfo(roomNumber, roomType, floor, building, roomStatus));
            }

            LOGGER.info("レイトアウト設定対象の部屋データ読み込み完了: " + lateOutRoomData.size() + "室");

        } catch (Exception e) {
            LOGGER.severe("レイトアウト部屋データの読み込みエラー: " + e.getMessage());
            JOptionPane.showMessageDialog(this,
                    "レイトアウト部屋データの読み込みに失敗しました: " + e.getMessage(),
                    "エラー", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * 未チェックイン設定（pendingRoomSettings）の状態上書きを取得
     * （PreExcludedRoomSelectionDialogと同じパターン）
     */
    private Map<String, String> getPendingStatusOverrides() {
        Map<String, String> overrides = new HashMap<>();
        String savedSettings = System.getProperty("pendingRoomSettings");
        if (savedSettings == null || savedSettings.trim().isEmpty()) return overrides;

        for (String entry : savedSettings.split(",")) {
            String[] tokens = entry.split(":");
            if (tokens.length < 2) continue;
            String roomNumber = tokens[0].trim();
            String status     = tokens[1].trim();
            if (!roomNumber.isEmpty() && !"1".equals(status)) {
                overrides.put(roomNumber, status);
            }
        }
        return overrides;
    }

    /**
     * 残し部屋(事前設定)の部屋番号を取得
     */
    private Set<String> getPreExcludedRoomNumbers() {
        Set<String> excludedRooms = new HashSet<>();
        String preExcludedRoomsStr = System.getProperty("preExcludedRooms");
        if (preExcludedRoomsStr != null && !preExcludedRoomsStr.trim().isEmpty()) {
            for (String roomNumber : preExcludedRoomsStr.split(",")) {
                if (!roomNumber.trim().isEmpty()) {
                    excludedRooms.add(roomNumber.trim());
                }
            }
        }
        return excludedRooms;
    }

    /**
     * 前回の設定をSystemPropertyから復元する
     * 形式: roomNumber=時刻;roomNumber=時刻;...
     */
    private void restorePreviousSettings() {
        String savedSettings = System.getProperty("lateOutRooms");
        if (savedSettings == null || savedSettings.trim().isEmpty()) return;

        for (String entry : savedSettings.split(";")) {
            int sep = entry.indexOf('=');
            if (sep <= 0) continue;
            String roomNumber = entry.substring(0, sep).trim();
            String time = entry.substring(sep + 1).trim();

            LateOutRoomInfo info = lateOutRoomData.get(roomNumber);
            if (info != null) {
                info.lateOutTime = time;
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
        infoPanel.setBorder(BorderFactory.createTitledBorder("レイトアウト部屋設定"));

        JLabel infoLabel = new JLabel("<html><div style='padding:10px;'>" +
                "レイトアウトの部屋に、アウト予定時刻を入力してください（例: 13:00）。<br>" +
                "設定した部屋は備考に「〇〇以降清掃」と表示され、大浴場清掃スタッフへの割り当てを極力避けます。<br>" +
                "時刻欄を空にすると設定解除になります。対象部屋数: " + lateOutRoomData.size() + "室" +
                "</div></html>");
        infoPanel.add(infoLabel, BorderLayout.CENTER);
        add(infoPanel, BorderLayout.NORTH);

        // 中央テーブル
        createRoomTable();
        JScrollPane scrollPane = new JScrollPane(roomTable);
        add(scrollPane, BorderLayout.CENTER);

        // 下部ボタン
        JPanel buttonPanel = new JPanel(new FlowLayout());

        JButton clearAllButton = new JButton("全て解除");
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

        if (lateOutRoomData.isEmpty()) {
            JLabel noDataLabel = new JLabel("レイトアウト設定できる部屋はありません", JLabel.CENTER);
            noDataLabel.setFont(new Font("MS Gothic", Font.PLAIN, 16));
            add(noDataLabel, BorderLayout.CENTER);
            okButton.setText("閉じる");
        }
    }

    /**
     * 部屋テーブルの作成
     */
    private void createRoomTable() {
        String[] columnNames = {"部屋番号", "部屋タイプ", "階", "建物", "状態", "アウト予定時刻"};

        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public Class<?> getColumnClass(int col) {
                return String.class;
            }

            @Override
            public boolean isCellEditable(int row, int col) {
                return col == 5;  // 時刻列のみ編集可能
            }
        };

        // データ追加（部屋番号順）
        List<LateOutRoomInfo> sorted = new ArrayList<>(lateOutRoomData.values());
        sorted.sort(Comparator.comparing(r -> r.roomNumber));

        for (LateOutRoomInfo info : sorted) {
            tableModel.addRow(new Object[]{
                    info.roomNumber,
                    info.roomType,
                    info.floor + "階",
                    info.building,
                    info.getStatusDisplay(),
                    info.lateOutTime
            });
        }

        roomTable = new JTable(tableModel);
        roomTable.setRowHeight(26);
        roomTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // 時刻列: 自由入力テキスト
        roomTable.getColumnModel().getColumn(5).setCellEditor(
                new DefaultCellEditor(new JTextField()));

        // 列幅
        roomTable.getColumnModel().getColumn(0).setPreferredWidth(90);
        roomTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        roomTable.getColumnModel().getColumn(2).setPreferredWidth(60);
        roomTable.getColumnModel().getColumn(3).setPreferredWidth(60);
        roomTable.getColumnModel().getColumn(4).setPreferredWidth(110);
        roomTable.getColumnModel().getColumn(5).setPreferredWidth(140);

        // テーブル変更をlateOutRoomDataに反映
        tableModel.addTableModelListener(e -> {
            int row = e.getFirstRow();
            int col = e.getColumn();
            if (row < 0 || col != 5) return;

            String roomNumber = (String) tableModel.getValueAt(row, 0);
            LateOutRoomInfo info = lateOutRoomData.get(roomNumber);
            if (info == null) return;

            String value = (String) tableModel.getValueAt(row, 5);
            info.lateOutTime = sanitizeTime(value);
        });
    }

    /**
     * 入力時刻のサニタイズ（プロパティ形式の区切り文字を除去）
     */
    private String sanitizeTime(String value) {
        if (value == null) return "";
        return value.replace(";", "").replace("=", "").trim();
    }

    /**
     * 全設定を解除する
     */
    private void clearAllSettings() {
        for (int row = 0; row < tableModel.getRowCount(); row++) {
            tableModel.setValueAt("", row, 5);
        }
        lateOutRoomData.values().forEach(info -> info.lateOutTime = "");
    }

    /**
     * OKボタン
     */
    private void onOkClicked(ActionEvent e) {
        // 編集中のセルを確定
        if (roomTable != null && roomTable.isEditing()) {
            roomTable.getCellEditor().stopCellEditing();
        }

        dialogResult = true;

        // 設定内容をSystemPropertyに保存（形式: roomNumber=時刻;…、0件ならクリア）
        Map<String, String> settings = getLateOutSettings();
        if (settings.isEmpty()) {
            System.clearProperty("lateOutRooms");
        } else {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : settings.entrySet()) {
                if (sb.length() > 0) sb.append(";");
                sb.append(entry.getKey()).append("=").append(entry.getValue());
            }
            System.setProperty("lateOutRooms", sb.toString());
        }

        LOGGER.info("レイトアウト部屋設定完了: " + settings.size() + "室");
        settings.forEach((room, time) -> LOGGER.info("  " + room + " → " + time + "以降清掃"));

        dispose();
    }

    public boolean getDialogResult() { return dialogResult; }

    /**
     * 設定されたレイトアウト部屋（部屋番号 → アウト予定時刻）を取得
     */
    public Map<String, String> getLateOutSettings() {
        Map<String, String> settings = new LinkedHashMap<>();
        for (LateOutRoomInfo info : lateOutRoomData.values()) {
            if (!info.lateOutTime.isEmpty()) {
                settings.put(info.roomNumber, info.lateOutTime);
            }
        }
        return settings;
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
