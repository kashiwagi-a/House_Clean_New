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
 * ★残し部屋(事前設定): メイン画面用の残し部屋設定ダイアログ
 * 処理実行の前に、チェックアウト部屋（状態2）から残し部屋（清掃しない部屋）を選択する。
 * 選択された部屋はFileProcessorの読み込み段階で清掃対象から除外され、
 * 通常清掃割り振り設定・最適化には除外後の部屋数が使用される。
 * 除外された部屋は「清掃割り当て調整」画面に「未割り当て発の残し部屋」として引き継がれ、
 * 未割り当てダイアログから解除・手動割り当てが可能。
 */
public class PreExcludedRoomSelectionDialog extends JDialog {
    private static final Logger LOGGER = Logger.getLogger(PreExcludedRoomSelectionDialog.class.getName());

    private JTable roomTable;
    private DefaultTableModel tableModel;
    private Map<String, CheckoutRoomInfo> checkoutRoomData;
    private Set<String> selectedExcludedRooms;
    private boolean dialogResult = false;

    /**
     * チェックアウト部屋情報クラス
     */
    public static class CheckoutRoomInfo {
        public final String roomNumber;
        public final String roomType;
        public final int floor;
        public final String building;
        public final boolean fromPendingSetting;  // 未チェックイン設定でチェックアウトに変更された部屋か
        public boolean isExcluded;

        public CheckoutRoomInfo(String roomNumber, String roomType, int floor, String building,
                                boolean fromPendingSetting) {
            this.roomNumber = roomNumber;
            this.roomType = roomType;
            this.floor = floor;
            this.building = building;
            this.fromPendingSetting = fromPendingSetting;
            this.isExcluded = false;
        }

        public String getStatusDisplay() {
            return fromPendingSetting ? "チェックアウト(設定)" : "チェックアウト";
        }

        @Override
        public String toString() {
            return roomNumber + " (" + roomType + ", " + floor + "階, " + building + ")";
        }
    }

    /**
     * コンストラクタ
     */
    public PreExcludedRoomSelectionDialog(JFrame parent, File roomDataFile) {
        super(parent, "残し部屋設定", true);
        this.checkoutRoomData = new LinkedHashMap<>();
        this.selectedExcludedRooms = new HashSet<>();

        loadCheckoutRoomData(roomDataFile);
        restorePreviousSettings();
        initializeGUI();
        setSize(700, 500);
        setLocationRelativeTo(parent);
        updateSelectionCount();
    }

    /**
     * CSVからチェックアウト部屋（状態「2」）を読み込む
     * ※故障部屋（6列目=1）は対象外
     * ※未チェックイン設定（pendingRoomSettings）でチェックアウトに変更された部屋も対象に含める
     */
    private void loadCheckoutRoomData(File file) {
        if (file == null) {
            LOGGER.warning("部屋データファイルが指定されていません");
            return;
        }

        // 未チェックイン設定を取得（状態1→2に変更された部屋を対象に含めるため）
        Map<String, String> pendingOverrides = getPendingStatusOverrides();

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

                // 未チェックイン設定による状態上書きの適用（状態1→2の部屋を対象に含める）
                boolean fromPendingSetting = false;
                if ("1".equals(roomStatus) && pendingOverrides.containsKey(roomNumber)) {
                    String overrideStatus = pendingOverrides.get(roomNumber);
                    if ("2".equals(overrideStatus)) {
                        roomStatus = "2";
                        fromPendingSetting = true;
                    }
                }

                // チェックアウト（状態2）のみ対象。連泊（状態3）等は残し部屋設定の対象外
                if (!"2".equals(roomStatus)) continue;

                String roomType = determineRoomType(roomTypeCode);
                int floor       = extractFloor(roomNumber);
                String building = isAnnexRoom(roomNumber) ? "別館" : "本館";

                CheckoutRoomInfo info = new CheckoutRoomInfo(roomNumber, roomType, floor, building,
                        fromPendingSetting);
                checkoutRoomData.put(roomNumber, info);

                LOGGER.fine("チェックアウト部屋を検出: " + roomNumber + " (" + roomType + ", " + floor + "階, " + building + ")");
            }

            LOGGER.info("残し部屋設定: チェックアウト部屋データ読み込み完了: " + checkoutRoomData.size() + "室");

        } catch (Exception e) {
            LOGGER.severe("チェックアウト部屋データの読み込みエラー: " + e.getMessage());
            JOptionPane.showMessageDialog(this,
                    "チェックアウト部屋データの読み込みに失敗しました: " + e.getMessage(),
                    "エラー", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * 未チェックイン設定（pendingRoomSettings）から状態上書き情報を取得する
     * 形式: roomNumber:status:eco,...
     * 戻り値: Map<部屋番号, 状態コード>
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
            if (!roomNumber.isEmpty()) {
                overrides.put(roomNumber, status);
            }
        }

        return overrides;
    }

    /**
     * 前回の設定をSystemPropertyから復元する
     */
    private void restorePreviousSettings() {
        String savedSettings = System.getProperty("preExcludedRooms");
        if (savedSettings == null || savedSettings.trim().isEmpty()) return;

        for (String roomNumber : savedSettings.split(",")) {
            String trimmed = roomNumber.trim();
            if (trimmed.isEmpty()) continue;

            CheckoutRoomInfo info = checkoutRoomData.get(trimmed);
            if (info != null) {
                info.isExcluded = true;
                selectedExcludedRooms.add(trimmed);
            }
        }

        LOGGER.info("前回の残し部屋設定を復元: " + selectedExcludedRooms.size() + "室");
    }

    /**
     * GUI初期化
     */
    private void initializeGUI() {
        setLayout(new BorderLayout());

        // 上部：説明パネル
        JPanel infoPanel = new JPanel(new BorderLayout());
        infoPanel.setBorder(BorderFactory.createTitledBorder("残し部屋設定"));

        JLabel infoLabel = new JLabel("<html><div style='padding:10px;'>" +
                "チェックアウト部屋から残し部屋（清掃しない部屋）を選択してください。<br>" +
                "チェックを入れた部屋は処理実行時に清掃対象から除外され、<br>" +
                "通常清掃割り振り設定・最適化には除外後の部屋数が使用されます。<br>" +
                "除外した部屋は清掃指示書の「残し部屋」ブロックに出力されます。<br>" +
                "※連泊部屋は残し部屋設定の対象外です。" +
                "</div></html>");
        infoPanel.add(infoLabel, BorderLayout.CENTER);
        add(infoPanel, BorderLayout.NORTH);

        // 中央：部屋選択テーブル
        createRoomSelectionTable();
        JScrollPane scrollPane = new JScrollPane(roomTable);
        scrollPane.setPreferredSize(new Dimension(650, 300));
        add(scrollPane, BorderLayout.CENTER);

        // 下部：操作ボタン
        JPanel buttonPanel = new JPanel(new FlowLayout());

        JButton selectAllButton = new JButton("全て選択");
        selectAllButton.addActionListener(e -> selectAllRooms(true));
        buttonPanel.add(selectAllButton);

        JButton deselectAllButton = new JButton("全て解除");
        deselectAllButton.addActionListener(e -> selectAllRooms(false));
        buttonPanel.add(deselectAllButton);

        buttonPanel.add(Box.createHorizontalStrut(20));

        JButton okButton = new JButton("設定完了");
        okButton.setFont(new Font("MS Gothic", Font.BOLD, 12));
        okButton.addActionListener(this::onOkClicked);
        buttonPanel.add(okButton);

        JButton cancelButton = new JButton("キャンセル");
        cancelButton.addActionListener(e -> dispose());
        buttonPanel.add(cancelButton);

        add(buttonPanel, BorderLayout.SOUTH);

        // チェックアウト部屋が0の場合の処理
        if (checkoutRoomData.isEmpty()) {
            JLabel noDataLabel = new JLabel("チェックアウト状態の部屋はありません", JLabel.CENTER);
            noDataLabel.setFont(new Font("MS Gothic", Font.PLAIN, 16));
            add(noDataLabel, BorderLayout.CENTER);

            okButton.setText("閉じる");
        }
    }

    /**
     * 部屋選択テーブルの作成
     */
    private void createRoomSelectionTable() {
        String[] columnNames = {"残し部屋", "部屋番号", "部屋タイプ", "階", "建物", "状態"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == 0) return Boolean.class;
                return String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0; // チェックボックスのみ編集可能
            }
        };

        // データを追加（建物→階→部屋番号順でソート）
        List<CheckoutRoomInfo> sortedRooms = new ArrayList<>(checkoutRoomData.values());
        sortedRooms.sort((r1, r2) -> {
            boolean isMain1 = "本館".equals(r1.building);
            boolean isMain2 = "本館".equals(r2.building);
            if (isMain1 != isMain2) {
                return isMain1 ? -1 : 1;
            }
            if (r1.floor != r2.floor) {
                return Integer.compare(r1.floor, r2.floor);
            }
            return compareRoomNumbers(r1.roomNumber, r2.roomNumber);
        });

        for (CheckoutRoomInfo roomInfo : sortedRooms) {
            Object[] row = {
                    roomInfo.isExcluded,
                    roomInfo.roomNumber,
                    roomInfo.roomType,
                    roomInfo.floor + "階",
                    roomInfo.building,
                    roomInfo.getStatusDisplay()
            };
            tableModel.addRow(row);
        }

        roomTable = new JTable(tableModel);
        roomTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        roomTable.setRowHeight(25);

        // 列幅設定
        roomTable.getColumnModel().getColumn(0).setPreferredWidth(70);
        roomTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        roomTable.getColumnModel().getColumn(2).setPreferredWidth(90);
        roomTable.getColumnModel().getColumn(3).setPreferredWidth(60);
        roomTable.getColumnModel().getColumn(4).setPreferredWidth(60);
        roomTable.getColumnModel().getColumn(5).setPreferredWidth(140);

        // チェックボックスのレンダラー設定
        roomTable.getColumnModel().getColumn(0).setCellRenderer(new TableCellRenderer() {
            private final JCheckBox checkBox = new JCheckBox();

            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                           boolean isSelected, boolean hasFocus, int row, int column) {
                checkBox.setSelected(value != null && (Boolean) value);
                checkBox.setHorizontalAlignment(JLabel.CENTER);

                if (isSelected) {
                    checkBox.setBackground(table.getSelectionBackground());
                } else {
                    checkBox.setBackground(table.getBackground());
                }
                checkBox.setOpaque(true);

                return checkBox;
            }
        });

        // チェックボックス変更イベント
        tableModel.addTableModelListener(e -> {
            if (e.getColumn() == 0) { // チェックボックス列
                int row = e.getFirstRow();
                boolean selected = (Boolean) tableModel.getValueAt(row, 0);
                String roomNumber = (String) tableModel.getValueAt(row, 1);

                CheckoutRoomInfo roomInfo = checkoutRoomData.get(roomNumber);
                if (roomInfo != null) {
                    roomInfo.isExcluded = selected;
                    if (selected) {
                        selectedExcludedRooms.add(roomNumber);
                    } else {
                        selectedExcludedRooms.remove(roomNumber);
                    }
                }

                updateSelectionCount();
            }
        });
    }

    /**
     * 部屋番号の比較（数値部分で比較）
     */
    private int compareRoomNumbers(String room1, String room2) {
        try {
            String num1 = room1.replaceAll("[^0-9]", "");
            String num2 = room2.replaceAll("[^0-9]", "");

            if (!num1.isEmpty() && !num2.isEmpty()) {
                int n1 = Integer.parseInt(num1);
                int n2 = Integer.parseInt(num2);
                return Integer.compare(n1, n2);
            }
        } catch (NumberFormatException e) {
            // 文字列比較にフォールバック
        }

        return room1.compareTo(room2);
    }

    /**
     * 全選択/全解除
     */
    private void selectAllRooms(boolean select) {
        for (int row = 0; row < tableModel.getRowCount(); row++) {
            tableModel.setValueAt(select, row, 0);
        }
    }

    /**
     * 選択数の更新
     */
    private void updateSelectionCount() {
        setTitle("残し部屋設定 - " + selectedExcludedRooms.size() + "/" + checkoutRoomData.size() + "室選択中");
    }

    /**
     * OK ボタンクリック時の処理
     */
    private void onOkClicked(ActionEvent e) {
        dialogResult = true;

        // 設定内容をSystemPropertyに保存（形式: roomNumber,roomNumber,...）
        if (selectedExcludedRooms.isEmpty()) {
            System.clearProperty("preExcludedRooms");
        } else {
            System.setProperty("preExcludedRooms", String.join(",", selectedExcludedRooms));
        }

        LOGGER.info("残し部屋設定(事前)完了:");
        LOGGER.info("  残し部屋として設定: " + selectedExcludedRooms.size() + "室");

        for (String roomNumber : selectedExcludedRooms) {
            CheckoutRoomInfo roomInfo = checkoutRoomData.get(roomNumber);
            if (roomInfo != null) {
                LOGGER.info("    " + roomInfo.toString());
            }
        }

        dispose();
    }

    /**
     * ダイアログの結果を取得
     */
    public boolean getDialogResult() {
        return dialogResult;
    }

    /**
     * 残し部屋として選択された部屋番号セットを取得
     */
    public Set<String> getSelectedExcludedRooms() {
        return new HashSet<>(selectedExcludedRooms);
    }

    // ユーティリティメソッド（FileProcessorと同一ロジック）
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