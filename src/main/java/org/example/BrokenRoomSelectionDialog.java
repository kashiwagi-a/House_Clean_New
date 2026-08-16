package org.example;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.*;
import java.util.List;
import java.util.logging.Logger;

/**
 * 故障・未販売部屋選択ダイアログ
 * 故障部屋（6列目の値が1の部屋）および未販売部屋（7列目の値が0の部屋）を
 * 清掃対象に含めるかどうかを選択する
 */
public class BrokenRoomSelectionDialog extends JDialog {
    private static final Logger LOGGER = Logger.getLogger(BrokenRoomSelectionDialog.class.getName());

    /** タブインデックス: 故障部屋 */
    public static final int TAB_BROKEN = 0;
    /** タブインデックス: 未販売設定 */
    public static final int TAB_UNSOLD = 1;

    private JTabbedPane tabbedPane;
    private JTable brokenRoomTable;
    private JTable unsoldRoomTable;
    private DefaultTableModel brokenTableModel;
    private DefaultTableModel unsoldTableModel;
    private Map<String, BrokenRoomInfo> brokenRoomData;
    private Set<String> selectedForCleaning;
    private boolean dialogResult = false;

    /**
     * 故障・未販売部屋情報クラス
     */
    public static class BrokenRoomInfo {
        public final String roomNumber;
        public final String roomType;
        public final int floor;
        public final String building;
        public final boolean isBroken;   // ★追加: 故障（6列目=1）
        public final boolean isUnsold;   // ★追加: 未販売（7列目=0）
        public boolean selectedForCleaning;

        public BrokenRoomInfo(String roomNumber, String roomType, int floor, String building,
                              boolean isBroken, boolean isUnsold) {
            this.roomNumber = roomNumber;
            this.roomType = roomType;
            this.floor = floor;
            this.building = building;
            this.isBroken = isBroken;
            this.isUnsold = isUnsold;
            this.selectedForCleaning = false;
        }

        /**
         * ★追加: 区分の表示文字列を取得
         */
        public String getCategoryDisplay() {
            if (isBroken && isUnsold) return "故障＋未販売";
            if (isBroken) return "故障";
            if (isUnsold) return "未販売";
            return "";
        }

        @Override
        public String toString() {
            return roomNumber + " (" + roomType + ", " + floor + "階, " + building + ", " + getCategoryDisplay() + ")";
        }
    }

    /**
     * コンストラクタ（故障部屋タブを初期表示）
     */
    public BrokenRoomSelectionDialog(JFrame parent, File roomDataFile) {
        this(parent, roomDataFile, TAB_BROKEN);
    }

    /**
     * コンストラクタ（初期表示タブを指定）
     */
    public BrokenRoomSelectionDialog(JFrame parent, File roomDataFile, int initialTabIndex) {
        super(parent, "故障・未販売部屋清掃設定", true);
        this.brokenRoomData = new HashMap<>();
        this.selectedForCleaning = new HashSet<>();

        // 故障・未販売部屋データを読み込み
        loadBrokenRoomData(roomDataFile);

        initializeGUI();
        if (initialTabIndex == TAB_UNSOLD) {
            tabbedPane.setSelectedIndex(TAB_UNSOLD);
        }
        setSize(680, 430);
        setLocationRelativeTo(parent);
    }

    /**
     * 故障部屋データの読み込み
     */
    private void loadBrokenRoomData(File file) {
        if (file == null) {
            LOGGER.warning("部屋データファイルが指定されていません");
            return;
        }

        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader(file, java.nio.charset.StandardCharsets.UTF_8));

            String line;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                // ヘッダー行をスキップ
                if (lineNumber <= 4) continue;

                String[] parts = line.split(",");
                if (parts.length < 7) continue;

                String roomNumber = parts.length > 1 ? parts[1].trim() : "";
                String roomTypeCode = parts.length > 2 ? parts[2].trim() : "";
                String brokenStatus = parts.length > 5 ? parts[5].trim() : "";
                String roomStatus = parts.length > 6 ? parts[6].trim() : "";  // ★追加: 清掃状態（7列目）

                boolean isBroken = "1".equals(brokenStatus);
                boolean isUnsold = "0".equals(roomStatus);  // ★追加: 未販売（状態0）

                // 故障部屋（6列目=1）または未販売部屋（7列目=0）を対象
                if ((isBroken || isUnsold) && !roomNumber.isEmpty()) {
                    String roomType = determineRoomType(roomTypeCode);
                    int floor = extractFloor(roomNumber);
                    String building = isAnnexRoom(roomNumber) ? "別館" : "本館";

                    BrokenRoomInfo roomInfo = new BrokenRoomInfo(roomNumber, roomType, floor, building,
                            isBroken, isUnsold);
                    brokenRoomData.put(roomNumber, roomInfo);

                    LOGGER.info("故障・未販売部屋を検出: " + roomNumber + " (" + roomType + ", " + floor + "階, " +
                            building + ", " + roomInfo.getCategoryDisplay() + ")");
                }
            }

            reader.close();
            LOGGER.info("故障・未販売部屋データ読み込み完了: " + brokenRoomData.size() + "室");

        } catch (Exception e) {
            LOGGER.severe("故障・未販売部屋データの読み込みエラー: " + e.getMessage());
            JOptionPane.showMessageDialog(this,
                    "故障・未販売部屋データの読み込みに失敗しました: " + e.getMessage(),
                    "エラー", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * GUI初期化
     */
    private void initializeGUI() {
        setLayout(new BorderLayout());

        // 上部：説明パネル
        JPanel infoPanel = new JPanel(new BorderLayout());
        infoPanel.setBorder(BorderFactory.createTitledBorder("故障・未販売部屋清掃設定"));

        // ★追加: 故障・未販売の内訳件数（両方に該当する部屋は双方にカウント）
        long brokenCount = brokenRoomData.values().stream().filter(r -> r.isBroken).count();
        long unsoldCount = brokenRoomData.values().stream().filter(r -> r.isUnsold).count();

        JLabel infoLabel = new JLabel("<html><div style='padding:10px;'>" +
                "故障・未販売状態の部屋を清掃対象に含めるかどうかを選択してください。<br>" +
                "チェックを入れた部屋は通常の清掃対象として処理されます。<br>" +
                "故障部屋: " + brokenCount + "室 / 未販売部屋: " + unsoldCount + "室（合計 " +
                brokenRoomData.size() + "室）" +
                "</div></html>");
        infoPanel.add(infoLabel, BorderLayout.CENTER);
        add(infoPanel, BorderLayout.NORTH);

        // 中央：故障部屋／未販売設定タブ
        brokenTableModel = createRoomTableModel(r -> r.isBroken);
        brokenRoomTable = createRoomTable(brokenTableModel);
        unsoldTableModel = createRoomTableModel(r -> r.isUnsold);
        unsoldRoomTable = createRoomTable(unsoldTableModel);

        tabbedPane = new JTabbedPane();
        tabbedPane.addTab("故障部屋 (" + brokenCount + ")",
                createTabComponent(brokenRoomTable, "故障状態の部屋はありません"));
        tabbedPane.addTab("未販売設定 (" + unsoldCount + ")",
                createTabComponent(unsoldRoomTable, "未販売状態の部屋はありません"));
        add(tabbedPane, BorderLayout.CENTER);

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

        // 故障・未販売部屋が0の場合の処理
        if (brokenRoomData.isEmpty()) {
            JLabel noDataLabel = new JLabel("故障・未販売状態の部屋はありません", JLabel.CENTER);
            noDataLabel.setFont(new Font("MS Gothic", Font.PLAIN, 16));
            add(noDataLabel, BorderLayout.CENTER);

            // OKボタンを無効化
            okButton.setText("閉じる");
        }
    }

    /**
     * 指定区分の部屋のみを含むテーブルモデルの作成
     */
    private DefaultTableModel createRoomTableModel(java.util.function.Predicate<BrokenRoomInfo> filter) {
        String[] columnNames = {"選択", "部屋番号", "部屋タイプ", "階", "建物", "区分"};
        DefaultTableModel model = new DefaultTableModel(columnNames, 0) {
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

        // データを追加（フィルタに合致する部屋のみ）
        List<BrokenRoomInfo> sortedRooms = new ArrayList<>();
        for (BrokenRoomInfo roomInfo : brokenRoomData.values()) {
            if (filter.test(roomInfo)) {
                sortedRooms.add(roomInfo);
            }
        }
        sortedRooms.sort(Comparator.comparing(r -> r.roomNumber));

        for (BrokenRoomInfo roomInfo : sortedRooms) {
            Object[] row = {
                    false, // 初期状態は未選択
                    roomInfo.roomNumber,
                    roomInfo.roomType,
                    roomInfo.floor + "階",
                    roomInfo.building,
                    roomInfo.getCategoryDisplay()  // 区分（故障／未販売／故障＋未販売）
            };
            model.addRow(row);
        }

        // チェックボックス変更イベント
        model.addTableModelListener(e -> {
            if (e.getColumn() == 0) { // チェックボックス列
                int row = e.getFirstRow();
                boolean selected = (Boolean) model.getValueAt(row, 0);
                String roomNumber = (String) model.getValueAt(row, 1);

                BrokenRoomInfo roomInfo = brokenRoomData.get(roomNumber);
                if (roomInfo != null) {
                    roomInfo.selectedForCleaning = selected;
                    if (selected) {
                        selectedForCleaning.add(roomNumber);
                    } else {
                        selectedForCleaning.remove(roomNumber);
                    }
                }

                // 故障＋未販売の部屋はもう片方のタブにも表示されるため、チェック状態を同期
                syncOtherTable(model, roomNumber, selected);

                updateSelectionCount();
            }
        });

        return model;
    }

    /**
     * テーブルの作成（レンダラー・列幅設定）
     */
    private JTable createRoomTable(DefaultTableModel model) {
        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setRowHeight(25);

        // 列幅設定
        table.getColumnModel().getColumn(0).setPreferredWidth(60);  // 選択
        table.getColumnModel().getColumn(1).setPreferredWidth(100); // 部屋番号
        table.getColumnModel().getColumn(2).setPreferredWidth(100); // 部屋タイプ
        table.getColumnModel().getColumn(3).setPreferredWidth(80);  // 階
        table.getColumnModel().getColumn(4).setPreferredWidth(80);  // 建物
        table.getColumnModel().getColumn(5).setPreferredWidth(110); // 区分

        // チェックボックスのレンダラー設定
        table.getColumnModel().getColumn(0).setCellRenderer(new TableCellRenderer() {
            private final JCheckBox checkBox = new JCheckBox();

            @Override
            public Component getTableCellRendererComponent(JTable t, Object value,
                                                           boolean isSelected, boolean hasFocus, int row, int column) {
                checkBox.setSelected(value != null && (Boolean) value);
                checkBox.setHorizontalAlignment(JLabel.CENTER);

                if (isSelected) {
                    checkBox.setBackground(t.getSelectionBackground());
                } else {
                    checkBox.setBackground(t.getBackground());
                }
                checkBox.setOpaque(true);

                return checkBox;
            }
        });

        return table;
    }

    /**
     * タブ内コンポーネントの作成（該当部屋が無い場合はラベルを表示）
     */
    private JComponent createTabComponent(JTable table, String emptyMessage) {
        if (table.getModel().getRowCount() == 0) {
            JLabel emptyLabel = new JLabel(emptyMessage, JLabel.CENTER);
            emptyLabel.setFont(new Font("MS Gothic", Font.PLAIN, 14));
            return emptyLabel;
        }
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(550, 250));
        return scrollPane;
    }

    /**
     * 故障＋未販売の部屋について、もう片方のタブのチェック状態を同期する
     */
    private void syncOtherTable(DefaultTableModel sourceModel, String roomNumber, boolean selected) {
        DefaultTableModel otherModel = (sourceModel == brokenTableModel) ? unsoldTableModel : brokenTableModel;
        if (otherModel == null) return;

        for (int row = 0; row < otherModel.getRowCount(); row++) {
            if (roomNumber.equals(otherModel.getValueAt(row, 1))) {
                boolean current = Boolean.TRUE.equals(otherModel.getValueAt(row, 0));
                if (current != selected) { // 値が変わる場合のみ更新（相互呼び出しのループ防止）
                    otherModel.setValueAt(selected, row, 0);
                }
                break;
            }
        }
    }

    /**
     * 全選択/全解除（現在表示中のタブに適用）
     */
    private void selectAllRooms(boolean select) {
        DefaultTableModel model = (tabbedPane.getSelectedIndex() == 1) ? unsoldTableModel : brokenTableModel;
        for (int row = 0; row < model.getRowCount(); row++) {
            model.setValueAt(select, row, 0);
        }
    }

    /**
     * 選択数の更新
     */
    private void updateSelectionCount() {
        // タイトルに選択数を表示
        setTitle("故障・未販売部屋清掃設定 - " + selectedForCleaning.size() + "/" + brokenRoomData.size() + "室選択中");
    }

    /**
     * OK ボタンクリック時の処理
     */
    private void onOkClicked(ActionEvent e) {
        dialogResult = true;

        LOGGER.info("故障・未販売部屋清掃設定完了:");
        LOGGER.info("  清掃対象に追加: " + selectedForCleaning.size() + "室");

        for (String roomNumber : selectedForCleaning) {
            BrokenRoomInfo roomInfo = brokenRoomData.get(roomNumber);
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
     * 清掃対象に選択された故障・未販売部屋の部屋番号セットを取得
     */
    public Set<String> getSelectedRoomsForCleaning() {
        return new HashSet<>(selectedForCleaning);
    }

    /**
     * 故障・未販売部屋情報マップを取得
     */
    public Map<String, BrokenRoomInfo> getBrokenRoomData() {
        return new HashMap<>(brokenRoomData);
    }

    // ユーティリティメソッド（FileProcessorから複製）
    private String determineRoomType(String roomTypeCode) {
        if (roomTypeCode.equals("S") || roomTypeCode.equals("NS") ||
                roomTypeCode.equals("ANS") || roomTypeCode.equals("ABF") ||
                roomTypeCode.equals("AKS")) {
            return "S";
        } else if (roomTypeCode.equals("D") || roomTypeCode.equals("ND") ||
                roomTypeCode.equals("AND")) {
            return "D";
        } else if (roomTypeCode.equals("T") || roomTypeCode.equals("NT") ||
                roomTypeCode.equals("ANT") || roomTypeCode.equals("ADT")) {
            return "T";
        } else if (roomTypeCode.equals("FD") || roomTypeCode.equals("NFD")) {
            return "FD";
        } else {
            return roomTypeCode;
        }
    }

    private int extractFloor(String roomNumber) {
        try {
            String numericPart = roomNumber.replaceAll("[^0-9]", "");
            if (numericPart.length() == 3) {
                return Character.getNumericValue(numericPart.charAt(0));
            } else if (numericPart.length() == 4 && numericPart.startsWith("10")) {
                return 10;
            } else if (numericPart.length() == 4) {
                return Character.getNumericValue(numericPart.charAt(1));
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean isAnnexRoom(String roomNumber) {
        String numericPart = roomNumber.replaceAll("[^0-9]", "");
        if (numericPart.length() == 3) {
            return false;
        } else if (numericPart.length() == 4 && numericPart.startsWith("10")) {
            return false;
        } else if (numericPart.length() == 4) {
            return true;
        }
        return false;
    }
}