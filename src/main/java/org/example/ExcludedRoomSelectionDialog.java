package org.example;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.*;
import java.util.List;
import java.util.logging.Logger;

/**
 * 残し部屋選択ダイアログ
 * 清掃予定の部屋から残し部屋（清掃しない部屋）を選択する
 * ★修正: 元データを使用して設定・解除を自由に行えるように
 */
public class ExcludedRoomSelectionDialog extends JDialog {
    private static final Logger LOGGER = Logger.getLogger(ExcludedRoomSelectionDialog.class.getName());

    private JTable roomTable;
    private DefaultTableModel tableModel;
    private Map<String, AssignedRoomInfo> assignedRoomData;
    private Set<String> selectedExcludedRooms;
    private boolean dialogResult = false;
    private int checkoutRoomCount = 0;  // ★追加: チェックアウト部屋数

    /**
     * 割り当てられた部屋情報クラス
     */
    public static class AssignedRoomInfo {
        public final String roomNumber;
        public final String roomType;
        public final String staffName;
        public final int floor;
        public final String building;
        public final String roomStatus;
        public boolean isExcluded;

        public AssignedRoomInfo(String roomNumber, String roomType, String staffName,
                                int floor, String building, String roomStatus) {
            this.roomNumber = roomNumber;
            this.roomType = roomType;
            this.staffName = staffName;
            this.floor = floor;
            this.building = building;
            this.roomStatus = roomStatus;
            this.isExcluded = false;
        }

        public String getFloorDisplay() {
            if (isMainBuilding()) {
                return floor + "階";
            } else {
                return "別館" + floor + "階";
            }
        }

        public String getStatusDisplay() {
            switch (roomStatus) {
                case "2": return "チェックアウト";
                case "3": return "連泊";
                default: return roomStatus;
            }
        }

        public boolean isMainBuilding() {
            return building.equals("本館");
        }

        @Override
        public String toString() {
            return roomNumber + " (" + roomType + ", " + getFloorDisplay() + ", " + building + ", 担当:" + staffName + ")";
        }
    }

    /**
     * コンストラクタ
     * ★修正: 元データ使用フラグを追加
     */
    public ExcludedRoomSelectionDialog(JFrame parent,
                                       Map<String, AssignmentEditorGUI.StaffData> staffDataMap,
                                       Set<String> currentExcludedRooms,
                                       boolean useOriginalData) {
        super(parent, "残し部屋設定", true);
        this.assignedRoomData = new HashMap<>();
        this.selectedExcludedRooms = new HashSet<>(currentExcludedRooms);

        // 割り当てられた部屋データを構築（元データを使用）
        buildAssignedRoomData(staffDataMap, useOriginalData);

        initializeGUI();
        setSize(800, 600);
        setLocationRelativeTo(parent);
    }

    /**
     * 後方互換性のためのコンストラクタ
     */
    public ExcludedRoomSelectionDialog(JFrame parent,
                                       Map<String, AssignmentEditorGUI.StaffData> staffDataMap,
                                       Set<String> currentExcludedRooms) {
        this(parent, staffDataMap, currentExcludedRooms, false);
    }

    /**
     * 割り当てられた部屋データの構築
     * ★修正: 元データを使用してすべてのチェックアウト部屋を表示
     */
    private void buildAssignedRoomData(Map<String, AssignmentEditorGUI.StaffData> staffDataMap, boolean useOriginalData) {
        for (Map.Entry<String, AssignmentEditorGUI.StaffData> entry : staffDataMap.entrySet()) {
            String staffName = entry.getKey();
            AssignmentEditorGUI.StaffData staffData = entry.getValue();

            // ★修正: 元データがあれば元データを使用
            Map<Integer, List<FileProcessor.Room>> roomsToUse;
            if (useOriginalData && staffData.originalDetailedRoomsByFloor != null
                    && !staffData.originalDetailedRoomsByFloor.isEmpty()) {
                roomsToUse = staffData.originalDetailedRoomsByFloor;
            } else {
                roomsToUse = staffData.detailedRoomsByFloor;
            }

            if (roomsToUse == null) continue;

            // 各スタッフの担当部屋を処理
            for (Map.Entry<Integer, List<FileProcessor.Room>> floorEntry : roomsToUse.entrySet()) {
                int floor = floorEntry.getKey();
                List<FileProcessor.Room> rooms = floorEntry.getValue();

                for (FileProcessor.Room room : rooms) {
                    // ★修正: 実際のroomStatusを使用
                    String roomStatus = room.roomStatus;

                    AssignedRoomInfo roomInfo = new AssignedRoomInfo(
                            room.roomNumber,
                            room.roomType,
                            staffName,
                            floor,
                            room.building,
                            roomStatus
                    );

                    // 既に残し部屋として選択されているかチェック
                    roomInfo.isExcluded = selectedExcludedRooms.contains(room.roomNumber);

                    assignedRoomData.put(room.roomNumber, roomInfo);

                    LOGGER.fine("割り当て部屋を追加: " + roomInfo.toString());
                }
            }
        }

        LOGGER.info("割り当て部屋データ構築完了: " + assignedRoomData.size() + "室");
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
                "チェックを入れた部屋は清掃対象から除外されます。<br>" +
                "チェックを外すと清掃対象に戻ります。<br>" +
                "※連泊部屋は残し部屋設定の対象外です。" +
                "</div></html>");
        infoPanel.add(infoLabel, BorderLayout.CENTER);
        add(infoPanel, BorderLayout.NORTH);

        // 中央：部屋選択テーブル
        createRoomSelectionTable();
        JScrollPane scrollPane = new JScrollPane(roomTable);
        scrollPane.setPreferredSize(new Dimension(750, 400));
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

        // 部屋が0の場合の処理
        if (checkoutRoomCount == 0) {
            JLabel noDataLabel = new JLabel("チェックアウトの部屋がありません", JLabel.CENTER);
            noDataLabel.setFont(new Font("MS Gothic", Font.PLAIN, 16));
            add(noDataLabel, BorderLayout.CENTER);

            okButton.setText("閉じる");
        }

        // ★追加: 初期選択数を表示
        updateSelectionCount();
    }

    /**
     * 部屋選択テーブルの作成
     * ★修正: チェックアウトの部屋のみを表示
     */
    private void createRoomSelectionTable() {
        String[] columnNames = {"残し部屋", "部屋番号", "部屋タイプ", "階", "建物", "状態", "担当スタッフ"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == 0) return Boolean.class;
                return String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0;
            }
        };

        // ★修正: チェックアウトの部屋のみをフィルタリングし、ソート
        List<AssignedRoomInfo> sortedRooms = new ArrayList<>();

        for (AssignedRoomInfo roomInfo : assignedRoomData.values()) {
            if ("2".equals(roomInfo.roomStatus)) {
                sortedRooms.add(roomInfo);
            }
        }

        // 本館→別館、階数順、部屋番号順でソート
        sortedRooms.sort(new Comparator<AssignedRoomInfo>() {
            @Override
            public int compare(AssignedRoomInfo r1, AssignedRoomInfo r2) {
                boolean r1IsMain = r1.isMainBuilding();
                boolean r2IsMain = r2.isMainBuilding();

                if (r1IsMain != r2IsMain) {
                    return r1IsMain ? -1 : 1;
                }

                if (r1.floor != r2.floor) {
                    return Integer.compare(r1.floor, r2.floor);
                }

                return compareRoomNumbers(r1.roomNumber, r2.roomNumber);
            }
        });

        // ★追加: チェックアウト部屋数を保存
        this.checkoutRoomCount = sortedRooms.size();

        LOGGER.info("残し部屋設定: チェックアウト部屋のみ表示 - " + sortedRooms.size() + "室");

        for (AssignedRoomInfo roomInfo : sortedRooms) {
            Object[] row = {
                    roomInfo.isExcluded,
                    roomInfo.roomNumber,
                    roomInfo.roomType,
                    roomInfo.getFloorDisplay(),
                    roomInfo.building,
                    roomInfo.getStatusDisplay(),
                    roomInfo.staffName
            };
            tableModel.addRow(row);
        }

        roomTable = new JTable(tableModel);
        roomTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        roomTable.setRowHeight(25);

        // 列幅設定
        roomTable.getColumnModel().getColumn(0).setPreferredWidth(80);
        roomTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        roomTable.getColumnModel().getColumn(2).setPreferredWidth(80);
        roomTable.getColumnModel().getColumn(3).setPreferredWidth(80);
        roomTable.getColumnModel().getColumn(4).setPreferredWidth(60);
        roomTable.getColumnModel().getColumn(5).setPreferredWidth(100);
        roomTable.getColumnModel().getColumn(6).setPreferredWidth(120);

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
            if (e.getColumn() == 0) {
                int row = e.getFirstRow();
                boolean selected = (Boolean) tableModel.getValueAt(row, 0);
                String roomNumber = (String) tableModel.getValueAt(row, 1);

                AssignedRoomInfo roomInfo = assignedRoomData.get(roomNumber);
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
     * 部屋番号の数値比較
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
     * ★修正: チェックアウト部屋数を母数として表示
     */
    private void updateSelectionCount() {
        setTitle("残し部屋設定 - " + selectedExcludedRooms.size() + "/" + checkoutRoomCount + "室選択中");
    }

    /**
     * OK ボタンクリック時の処理
     */
    private void onOkClicked(ActionEvent e) {
        dialogResult = true;

        LOGGER.info("残し部屋設定完了:");
        LOGGER.info("  残し部屋として設定: " + selectedExcludedRooms.size() + "室");

        for (String roomNumber : selectedExcludedRooms) {
            AssignedRoomInfo roomInfo = assignedRoomData.get(roomNumber);
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

    /**
     * 割り当て部屋情報マップを取得
     */
    public Map<String, AssignedRoomInfo> getAssignedRoomData() {
        return new HashMap<>(assignedRoomData);
    }
}