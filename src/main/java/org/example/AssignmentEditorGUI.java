package org.example;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import java.time.format.DateTimeFormatter;

/**
 * 拡張版清掃割り当て編集GUI
 * 既存のUIを維持しながら部屋番号レベルの編集機能を追加
 */
public class AssignmentEditorGUI extends JFrame {

    // 既存のフィールド
    private JTable assignmentTable;
    private DefaultTableModel tableModel;
    private JLabel statusLabel;
    protected Map<String, StaffData> staffDataMap;  // protectedに変更
    protected RoomAssignmentApplication.ProcessingResult processingResult;  // protectedに変更
    protected List<AdaptiveRoomOptimizer.StaffAssignment> currentAssignments;  // protectedに変更
    private JPanel summaryPanel;

    // 新規追加：部屋番号管理
    protected Map<String, List<FileProcessor.Room>> detailedRoomAssignments;  // protectedに変更
    protected RoomNumberAssigner roomAssigner;  // protectedに変更

    // 部屋タイプ別のポイント
    private static final Map<String, Double> ROOM_POINTS = new HashMap<>() {{
        put("S", 1.0);
        put("D", 1.0);
        put("T", 1.67);
        put("FD", 2.0);
        put("ECO", 0.2);
    }};

    /**
     * スタッフデータ拡張版
     */
    static class StaffData {  // staticを削除（内部クラスの場合）
        String id;
        String name;
        List<Integer> floors;
        Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> roomsByFloor;
        Map<Integer, List<FileProcessor.Room>> detailedRoomsByFloor; // 新規追加
        int totalRooms;
        double totalPoints;
        double adjustedScore;
        boolean hasMainBuilding;
        boolean hasAnnexBuilding;
        AdaptiveRoomOptimizer.BathCleaningType bathCleaningType;

        StaffData(AdaptiveRoomOptimizer.StaffAssignment assignment,
                  AdaptiveRoomOptimizer.BathCleaningType bathType) {
            this.id = assignment.staff.id;
            this.name = assignment.staff.name;
            this.floors = new ArrayList<>(assignment.floors);
            this.roomsByFloor = new HashMap<>(assignment.roomsByFloor);
            this.totalRooms = assignment.totalRooms;
            this.totalPoints = assignment.totalPoints;
            this.adjustedScore = assignment.adjustedScore;
            this.hasMainBuilding = assignment.hasMainBuilding;
            this.hasAnnexBuilding = assignment.hasAnnexBuilding;
            this.bathCleaningType = bathType;
            this.detailedRoomsByFloor = new HashMap<>();
        }

        String getWorkerTypeDisplay() {
            if (bathCleaningType != null && bathCleaningType != AdaptiveRoomOptimizer.BathCleaningType.NONE) {
                return bathCleaningType.displayName;
            }
            return "通常";
        }

        // 詳細表示用メソッド
        String getDetailedRoomDisplay() {
            if (detailedRoomsByFloor.isEmpty()) {
                return getRoomDisplay(); // 部屋番号が未割り当ての場合は従来の表示
            }

            StringBuilder sb = new StringBuilder();
            List<Integer> sortedFloors = new ArrayList<>(detailedRoomsByFloor.keySet());
            Collections.sort(sortedFloors);

            for (int i = 0; i < sortedFloors.size(); i++) {
                int floor = sortedFloors.get(i);
                List<FileProcessor.Room> rooms = detailedRoomsByFloor.get(floor);

                if (i > 0) sb.append(" ");
                sb.append(getFloorDisplayName(floor)).append(": ");

                // 部屋番号順にソート
                rooms.sort(Comparator.comparing(r -> r.roomNumber));

                // 部屋番号をグループ化して表示
                Map<String, List<FileProcessor.Room>> byType = rooms.stream()
                        .collect(Collectors.groupingBy(r -> r.roomType));

                boolean first = true;
                for (Map.Entry<String, List<FileProcessor.Room>> entry : byType.entrySet()) {
                    if (!first) sb.append(", ");
                    first = false;

                    List<String> roomNumbers = entry.getValue().stream()
                            .map(r -> r.roomNumber)
                            .collect(Collectors.toList());

                    sb.append(String.join(",", roomNumbers))
                            .append("(").append(entry.getKey()).append(")");
                }
            }

            return sb.toString();
        }

        String getRoomDisplay() {
            StringBuilder sb = new StringBuilder();
            List<Integer> sortedFloors = new ArrayList<>(floors);
            Collections.sort(sortedFloors);

            for (int i = 0; i < sortedFloors.size(); i++) {
                if (i > 0) sb.append(" ");

                int floor = sortedFloors.get(i);
                sb.append(getFloorDisplayName(floor)).append("(");

                AdaptiveRoomOptimizer.RoomAllocation allocation = roomsByFloor.get(floor);
                if (allocation != null) {
                    List<String> roomInfo = new ArrayList<>();
                    for (Map.Entry<String, Integer> entry : allocation.roomCounts.entrySet()) {
                        roomInfo.add(entry.getKey() + ":" + entry.getValue());
                    }
                    if (allocation.ecoRooms > 0) {
                        roomInfo.add("エコ:" + allocation.ecoRooms);
                    }
                    sb.append(String.join(" ", roomInfo));
                }
                sb.append(")");
            }

            return sb.toString();
        }

        private String getFloorDisplayName(int floor) {
            if (floor <= 20) {
                return floor + "階";
            } else {
                int annexFloor = floor - 20;
                return "別館" + annexFloor + "階";
            }
        }
    }

    /**
     * コンストラクタ
     */
    public AssignmentEditorGUI(RoomAssignmentApplication.ProcessingResult result) {
        this.processingResult = result;

        // ProcessingResultにoptimizationResultがある場合は使用
        if (result.optimizationResult != null) {
            this.currentAssignments = new ArrayList<>(result.optimizationResult.assignments);
        } else {
            // ない場合は空のリストを作成
            this.currentAssignments = new ArrayList<>();
        }

        this.staffDataMap = new HashMap<>();
        this.detailedRoomAssignments = new HashMap<>();

        // CleaningDataがある場合のみRoomNumberAssignerを初期化
        if (result.cleaningDataObj != null) {
            this.roomAssigner = new RoomNumberAssigner(result.cleaningDataObj);
        }

        // スタッフデータを初期化
        if (result.optimizationResult != null) {
            for (AdaptiveRoomOptimizer.StaffAssignment assignment : currentAssignments) {
                AdaptiveRoomOptimizer.BathCleaningType bathType =
                        result.optimizationResult.config.bathCleaningAssignments.get(assignment.staff.id);
                staffDataMap.put(assignment.staff.name, new StaffData(assignment, bathType));
            }
        }

        // 部屋番号を自動割り当て
        if (roomAssigner != null) {
            assignRoomNumbers();
        }

        initializeGUI();
        loadAssignmentData();
    }

    /**
     * 静的メソッドとして追加（既存コードとの互換性のため）
     */
    public static void showEditor(RoomAssignmentApplication.ProcessingResult result) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                // デフォルトのルック&フィールを使用
            }

            AssignmentEditorGUI editor = new AssignmentEditorGUI(result);
            editor.setVisible(true);
        });
    }

    /**
     * GUI初期化（既存のUIを維持）
     */
    protected void initializeGUI() {  // protectedに変更
        setTitle("清掃割り当て調整 - " +
                (processingResult.optimizationResult != null ?
                        processingResult.optimizationResult.targetDate.format(
                                DateTimeFormatter.ofPattern("yyyy年MM月dd日")) : ""));
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        // メインパネル
        JPanel mainPanel = new JPanel(new BorderLayout());

        // テーブル設定（既存と同じ）
        String[] columnNames = {
                "スタッフ名", "作業者タイプ", "部屋数", "ポイント", "調整後スコア", "担当階・部屋詳細"
        };

        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        assignmentTable = new JTable(tableModel);
        assignmentTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        assignmentTable.setRowHeight(25);

        // セルレンダラー設定
        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(JLabel.CENTER);

        for (int i = 1; i <= 4; i++) {
            assignmentTable.getColumnModel().getColumn(i).setCellRenderer(centerRenderer);
        }

        // 列幅設定
        assignmentTable.getColumnModel().getColumn(0).setPreferredWidth(100);
        assignmentTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        assignmentTable.getColumnModel().getColumn(2).setPreferredWidth(60);
        assignmentTable.getColumnModel().getColumn(3).setPreferredWidth(80);
        assignmentTable.getColumnModel().getColumn(4).setPreferredWidth(100);
        assignmentTable.getColumnModel().getColumn(5).setPreferredWidth(500);

        JScrollPane scrollPane = new JScrollPane(assignmentTable);
        scrollPane.setPreferredSize(new Dimension(1200, 400));
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // ボタンパネル（拡張版）
        JPanel buttonPanel = createEnhancedButtonPanel();
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        // ステータスバー
        statusLabel = new JLabel("準備完了");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        add(statusLabel, BorderLayout.SOUTH);

        add(mainPanel, BorderLayout.CENTER);

        // サマリーパネル
        summaryPanel = createSummaryPanel();
        add(summaryPanel, BorderLayout.NORTH);

        // ウィンドウ設定
        setSize(1400, 800);
        setLocationRelativeTo(null);
    }

    /**
     * 拡張版ボタンパネル作成
     */
    private JPanel createEnhancedButtonPanel() {
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));

        // 既存のボタン
        JButton moveRoomButton = new JButton("部屋移動");
        moveRoomButton.addActionListener(e -> showMoveRoomDialog());
        buttonPanel.add(moveRoomButton);

        JButton swapFloorsButton = new JButton("階交換");
        swapFloorsButton.addActionListener(e -> showSwapFloorsDialog());
        buttonPanel.add(swapFloorsButton);

        // 新規追加：部屋詳細編集ボタン
        JButton editRoomDetailsButton = new JButton("部屋詳細編集");
        editRoomDetailsButton.setBackground(new Color(100, 149, 237));
        editRoomDetailsButton.setForeground(Color.WHITE);
        editRoomDetailsButton.setFont(new Font("MS Gothic", Font.BOLD, 12));
        editRoomDetailsButton.addActionListener(e -> {
            int selectedRow = assignmentTable.getSelectedRow();
            if (selectedRow >= 0) {
                String staffName = (String) tableModel.getValueAt(selectedRow, 0);
                showRoomDetailEditor(staffName);
            } else {
                JOptionPane.showMessageDialog(this,
                        "スタッフを選択してください", "エラー", JOptionPane.WARNING_MESSAGE);
            }
        });
        buttonPanel.add(editRoomDetailsButton);

        buttonPanel.add(Box.createHorizontalStrut(20));

        JButton recalculateButton = new JButton("ポイント再計算");
        recalculateButton.addActionListener(e -> recalculatePoints());
        buttonPanel.add(recalculateButton);

        JButton exportButton = new JButton("Excel出力");
        exportButton.addActionListener(e -> exportToExcel());
        buttonPanel.add(exportButton);

        JButton finishButton = new JButton("完了");
        finishButton.addActionListener(e -> finishEditing());
        buttonPanel.add(finishButton);

        return buttonPanel;
    }

    /**
     * 部屋詳細編集ダイアログ
     */
    private void showRoomDetailEditor(String staffName) {
        StaffData staffData = staffDataMap.get(staffName);
        if (staffData == null) return;

        JDialog dialog = new JDialog(this, staffName + " - 部屋詳細編集", true);
        dialog.setLayout(new BorderLayout());

        // メインパネル
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // 上部：スタッフ情報
        JPanel infoPanel = new JPanel(new GridLayout(2, 1));
        infoPanel.setBorder(BorderFactory.createTitledBorder("スタッフ情報"));
        infoPanel.add(new JLabel("スタッフ: " + staffName));
        infoPanel.add(new JLabel("作業者タイプ: " + staffData.getWorkerTypeDisplay()));
        mainPanel.add(infoPanel, BorderLayout.NORTH);

        // 中央：部屋リスト
        JPanel roomPanel = new JPanel(new BorderLayout());
        roomPanel.setBorder(BorderFactory.createTitledBorder("担当部屋"));

        // 部屋リストモデル
        DefaultListModel<RoomListItem> listModel = new DefaultListModel<>();

        if (!staffData.detailedRoomsByFloor.isEmpty()) {
            List<Integer> sortedFloors = new ArrayList<>(staffData.detailedRoomsByFloor.keySet());
            Collections.sort(sortedFloors);

            for (int floor : sortedFloors) {
                List<FileProcessor.Room> rooms = staffData.detailedRoomsByFloor.get(floor);
                for (FileProcessor.Room room : rooms) {
                    listModel.addElement(new RoomListItem(room, floor));
                }
            }
        }

        JList<RoomListItem> roomList = new JList<>(listModel);
        roomList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        roomList.setCellRenderer(new RoomListCellRenderer());

        JScrollPane scrollPane = new JScrollPane(roomList);
        scrollPane.setPreferredSize(new Dimension(400, 300));
        roomPanel.add(scrollPane, BorderLayout.CENTER);

        // 部屋移動ボタンパネル
        JPanel roomButtonPanel = new JPanel(new FlowLayout());

        JButton moveSelectedButton = new JButton("選択した部屋を移動");
        moveSelectedButton.addActionListener(e -> {
            List<RoomListItem> selected = roomList.getSelectedValuesList();
            if (!selected.isEmpty()) {
                moveSelectedRooms(staffName, selected, dialog);
            }
        });
        roomButtonPanel.add(moveSelectedButton);

        JButton swapRoomButton = new JButton("部屋交換");
        swapRoomButton.addActionListener(e -> {
            List<RoomListItem> selected = roomList.getSelectedValuesList();
            if (selected.size() == 1) {
                showRoomSwapDialog(staffName, selected.get(0), dialog);
            } else {
                JOptionPane.showMessageDialog(dialog,
                        "交換する部屋を1つ選択してください", "エラー", JOptionPane.WARNING_MESSAGE);
            }
        });
        roomButtonPanel.add(swapRoomButton);

        roomPanel.add(roomButtonPanel, BorderLayout.SOUTH);
        mainPanel.add(roomPanel, BorderLayout.CENTER);

        // 下部：操作ボタン
        JPanel buttonPanel = new JPanel(new FlowLayout());

        JButton optimizeButton = new JButton("移動効率最適化");
        optimizeButton.addActionListener(e -> {
            optimizeRoomProximity(staffName);
            dialog.dispose();
            refreshTable();
        });
        buttonPanel.add(optimizeButton);

        JButton closeButton = new JButton("閉じる");
        closeButton.addActionListener(e -> dialog.dispose());
        buttonPanel.add(closeButton);

        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        dialog.add(mainPanel);
        dialog.setSize(500, 500);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    /**
     * 選択した部屋を移動
     */
    private void moveSelectedRooms(String fromStaff, List<RoomListItem> rooms, JDialog parentDialog) {
        // 移動先スタッフ選択ダイアログ
        String[] staffNames = staffDataMap.keySet().stream()
                .filter(name -> !name.equals(fromStaff))
                .sorted()
                .toArray(String[]::new);

        String toStaff = (String) JOptionPane.showInputDialog(
                parentDialog,
                "移動先スタッフを選択:",
                "部屋移動",
                JOptionPane.QUESTION_MESSAGE,
                null,
                staffNames,
                staffNames[0]
        );

        if (toStaff != null) {
            // 部屋を移動
            for (RoomListItem item : rooms) {
                moveSpecificRoom(fromStaff, toStaff, item.room, item.floor);
            }

            statusLabel.setText(String.format("%sから%sへ%d室を移動しました",
                    fromStaff, toStaff, rooms.size()));

            parentDialog.dispose();
            refreshTable();
        }
    }

    /**
     * 部屋交換ダイアログ
     */
    private void showRoomSwapDialog(String staff1, RoomListItem room1, JDialog parentDialog) {
        JDialog dialog = new JDialog(parentDialog, "部屋交換", true);
        dialog.setLayout(new BorderLayout());

        JPanel panel = new JPanel(new GridLayout(3, 2, 10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        panel.add(new JLabel("交換元:"));
        panel.add(new JLabel(staff1 + " - " + room1));

        panel.add(new JLabel("交換先スタッフ:"));
        String[] otherStaff = staffDataMap.keySet().stream()
                .filter(name -> !name.equals(staff1))
                .sorted()
                .toArray(String[]::new);
        JComboBox<String> staffCombo = new JComboBox<>(otherStaff);
        panel.add(staffCombo);

        panel.add(new JLabel("交換する部屋:"));
        JComboBox<RoomListItem> roomCombo = new JComboBox<>();
        panel.add(roomCombo);

        // スタッフ選択時に部屋リスト更新
        staffCombo.addActionListener(e -> {
            String selected = (String) staffCombo.getSelectedItem();
            if (selected != null) {
                updateRoomCombo(roomCombo, selected, room1.room.roomType);
            }
        });

        // 初期値設定
        if (otherStaff.length > 0) {
            updateRoomCombo(roomCombo, otherStaff[0], room1.room.roomType);
        }

        dialog.add(panel, BorderLayout.CENTER);

        // ボタンパネル
        JPanel buttonPanel = new JPanel(new FlowLayout());

        JButton swapButton = new JButton("交換");
        swapButton.addActionListener(e -> {
            String staff2 = (String) staffCombo.getSelectedItem();
            RoomListItem room2 = (RoomListItem) roomCombo.getSelectedItem();

            if (staff2 != null && room2 != null) {
                swapRooms(staff1, room1, staff2, room2);
                dialog.dispose();
                parentDialog.dispose();
                refreshTable();
            }
        });
        buttonPanel.add(swapButton);

        JButton cancelButton = new JButton("キャンセル");
        cancelButton.addActionListener(e -> dialog.dispose());
        buttonPanel.add(cancelButton);

        dialog.add(buttonPanel, BorderLayout.SOUTH);

        dialog.setSize(400, 200);
        dialog.setLocationRelativeTo(parentDialog);
        dialog.setVisible(true);
    }

    /**
     * 部屋コンボボックス更新
     */
    private void updateRoomCombo(JComboBox<RoomListItem> combo, String staffName, String roomType) {
        combo.removeAllItems();
        StaffData staff = staffDataMap.get(staffName);

        if (staff != null && !staff.detailedRoomsByFloor.isEmpty()) {
            for (Map.Entry<Integer, List<FileProcessor.Room>> entry : staff.detailedRoomsByFloor.entrySet()) {
                int floor = entry.getKey();
                for (FileProcessor.Room room : entry.getValue()) {
                    if (room.roomType.equals(roomType)) {
                        combo.addItem(new RoomListItem(room, floor));
                    }
                }
            }
        }
    }

    /**
     * 特定の部屋を移動
     */
    private void moveSpecificRoom(String fromStaffName, String toStaffName,
                                  FileProcessor.Room room, int floor) {
        StaffData fromStaff = staffDataMap.get(fromStaffName);
        StaffData toStaff = staffDataMap.get(toStaffName);

        if (fromStaff == null || toStaff == null) return;

        // 詳細部屋リストから移動
        List<FileProcessor.Room> fromRooms = fromStaff.detailedRoomsByFloor.get(floor);
        if (fromRooms != null) {
            fromRooms.remove(room);
            if (fromRooms.isEmpty()) {
                fromStaff.detailedRoomsByFloor.remove(floor);
            }
        }

        // 移動先に追加
        toStaff.detailedRoomsByFloor.computeIfAbsent(floor, k -> new ArrayList<>()).add(room);

        // 集計データも更新
        updateRoomAllocation(fromStaff, toStaff, floor, room.roomType, 1);

        // ポイント再計算
        recalculateStaffPoints(fromStaff);
        recalculateStaffPoints(toStaff);
    }

    /**
     * 部屋交換
     */
    private void swapRooms(String staff1Name, RoomListItem room1Item,
                           String staff2Name, RoomListItem room2Item) {
        moveSpecificRoom(staff1Name, staff2Name, room1Item.room, room1Item.floor);
        moveSpecificRoom(staff2Name, staff1Name, room2Item.room, room2Item.floor);

        statusLabel.setText(String.format("%sと%sの部屋を交換しました", staff1Name, staff2Name));
    }

    /**
     * 移動効率最適化
     */
    private void optimizeRoomProximity(String staffName) {
        StaffData staff = staffDataMap.get(staffName);
        if (staff == null || staff.detailedRoomsByFloor.isEmpty()) return;

        // 各階で部屋番号順にソート（連続性を優先）
        for (List<FileProcessor.Room> rooms : staff.detailedRoomsByFloor.values()) {
            rooms.sort(Comparator.comparing(r -> r.roomNumber));
        }

        statusLabel.setText(staffName + "の部屋配置を最適化しました");
    }

    /**
     * 部屋番号自動割り当て
     */
    protected void assignRoomNumbers() {  // protectedに変更
        if (roomAssigner == null) return;

        Map<String, List<FileProcessor.Room>> assignments =
                roomAssigner.assignDetailedRooms(currentAssignments);

        for (Map.Entry<String, List<FileProcessor.Room>> entry : assignments.entrySet()) {
            String staffName = entry.getKey();
            List<FileProcessor.Room> rooms = entry.getValue();

            StaffData staffData = staffDataMap.get(staffName);
            if (staffData != null) {
                // 階ごとにグループ化
                Map<Integer, List<FileProcessor.Room>> byFloor = rooms.stream()
                        .collect(Collectors.groupingBy(r -> r.floor));

                staffData.detailedRoomsByFloor = byFloor;
            }
        }
    }

    // 既存メソッド（一部省略）
    private void showMoveRoomDialog() {
        // 既存の実装
        JOptionPane.showMessageDialog(this, "部屋移動機能", "情報", JOptionPane.INFORMATION_MESSAGE);
    }

    private void showSwapFloorsDialog() {
        // 既存の実装
        JOptionPane.showMessageDialog(this, "階交換機能", "情報", JOptionPane.INFORMATION_MESSAGE);
    }

    private void recalculatePoints() {
        for (StaffData staff : staffDataMap.values()) {
            recalculateStaffPoints(staff);
        }
        refreshTable();
        statusLabel.setText("ポイントを再計算しました");
    }

    private void recalculateStaffPoints(StaffData staff) {
        double totalPoints = 0;
        int totalRooms = 0;

        for (AdaptiveRoomOptimizer.RoomAllocation allocation : staff.roomsByFloor.values()) {
            for (Map.Entry<String, Integer> entry : allocation.roomCounts.entrySet()) {
                String type = entry.getKey();
                int count = entry.getValue();
                totalPoints += ROOM_POINTS.getOrDefault(type, 1.0) * count;
                totalRooms += count;
            }
            totalPoints += ROOM_POINTS.get("ECO") * allocation.ecoRooms;
            totalRooms += allocation.ecoRooms;
        }

        staff.totalPoints = totalPoints;
        staff.totalRooms = totalRooms;
    }

    private void updateRoomAllocation(StaffData fromStaff, StaffData toStaff,
                                      int floor, String roomType, int count) {
        // 移動元から削減
        AdaptiveRoomOptimizer.RoomAllocation fromAlloc = fromStaff.roomsByFloor.get(floor);
        if (fromAlloc != null) {
            if ("エコ".equals(roomType)) {
                fromAlloc = new AdaptiveRoomOptimizer.RoomAllocation(
                        fromAlloc.roomCounts, fromAlloc.ecoRooms - count);
            } else {
                Map<String, Integer> newCounts = new HashMap<>(fromAlloc.roomCounts);
                newCounts.compute(roomType, (k, v) -> v == null ? 0 : Math.max(0, v - count));
                fromAlloc = new AdaptiveRoomOptimizer.RoomAllocation(newCounts, fromAlloc.ecoRooms);
            }

            if (fromAlloc.getTotalRooms() == 0) {
                fromStaff.roomsByFloor.remove(floor);
                fromStaff.floors.remove(Integer.valueOf(floor));
            } else {
                fromStaff.roomsByFloor.put(floor, fromAlloc);
            }
        }

        // 移動先に追加
        if (!toStaff.floors.contains(floor)) {
            toStaff.floors.add(floor);
        }

        AdaptiveRoomOptimizer.RoomAllocation toAlloc = toStaff.roomsByFloor.get(floor);
        if (toAlloc == null) {
            toAlloc = new AdaptiveRoomOptimizer.RoomAllocation(new HashMap<>(), 0);
        }

        if ("エコ".equals(roomType)) {
            toAlloc = new AdaptiveRoomOptimizer.RoomAllocation(
                    toAlloc.roomCounts, toAlloc.ecoRooms + count);
        } else {
            Map<String, Integer> newCounts = new HashMap<>(toAlloc.roomCounts);
            newCounts.compute(roomType, (k, v) -> v == null ? count : v + count);
            toAlloc = new AdaptiveRoomOptimizer.RoomAllocation(newCounts, toAlloc.ecoRooms);
        }

        toStaff.roomsByFloor.put(floor, toAlloc);
    }

    protected void loadAssignmentData() {  // protectedに変更
        tableModel.setRowCount(0);

        for (StaffData staff : staffDataMap.values()) {
            Object[] row = {
                    staff.name,
                    staff.getWorkerTypeDisplay(),
                    staff.totalRooms,
                    String.format("%.2f", staff.totalPoints),
                    String.format("%.2f", staff.adjustedScore),
                    staff.getDetailedRoomDisplay()
            };
            tableModel.addRow(row);
        }
    }

    private void refreshTable() {
        loadAssignmentData();
    }

    private JPanel createSummaryPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setBorder(BorderFactory.createTitledBorder("サマリー"));

        int totalRooms = staffDataMap.values().stream()
                .mapToInt(s -> s.totalRooms)
                .sum();

        double avgRooms = staffDataMap.isEmpty() ? 0 :
                (double) totalRooms / staffDataMap.size();

        panel.add(new JLabel(String.format("総部屋数: %d | ", totalRooms)));
        panel.add(new JLabel(String.format("スタッフ数: %d | ", staffDataMap.size())));
        panel.add(new JLabel(String.format("平均部屋数: %.1f", avgRooms)));

        return panel;
    }

    private void exportToExcel() {
        // Excel出力実装
        JOptionPane.showMessageDialog(this, "Excel出力機能は実装予定です", "情報", JOptionPane.INFORMATION_MESSAGE);
    }

    private void finishEditing() {
        int result = JOptionPane.showConfirmDialog(this,
                "編集を完了してよろしいですか？", "確認",
                JOptionPane.YES_NO_OPTION);

        if (result == JOptionPane.YES_OPTION) {
            dispose();
        }
    }

    /**
     * 部屋リストアイテム
     */
    static class RoomListItem {
        FileProcessor.Room room;
        int floor;

        RoomListItem(FileProcessor.Room room, int floor) {
            this.room = room;
            this.floor = floor;
        }

        @Override
        public String toString() {
            String floorName = floor <= 20 ? floor + "階" : "別館" + (floor - 20) + "階";
            return room.roomNumber + " (" + room.roomType + ") - " + floorName;
        }
    }

    /**
     * 部屋リストセルレンダラー
     */
    static class RoomListCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

            if (value instanceof RoomListItem) {
                RoomListItem item = (RoomListItem) value;
                setText(item.toString());

                // 部屋タイプによって色分け
                switch (item.room.roomType) {
                    case "S":
                        setForeground(Color.BLUE);
                        break;
                    case "D":
                        setForeground(Color.GREEN);
                        break;
                    case "T":
                        setForeground(Color.ORANGE);
                        break;
                    case "FD":
                        setForeground(Color.RED);
                        break;
                    case "エコ":
                        setForeground(Color.GRAY);
                        break;
                    default:
                        setForeground(Color.BLACK);
                }
            }

            return this;
        }
    }
}

/**
 * EnhancedAssignmentEditorGUI - AssignmentEditorGUIの拡張版
 * （別クラスとして同一ファイル内に定義）
 */
class EnhancedAssignmentEditorGUI extends JFrame {  // AssignmentEditorGUIを継承しない独立クラスとして定義

    private RoomAssignmentApplication.ProcessingResult processingResult;

    /**
     * コンストラクタ
     */
    public EnhancedAssignmentEditorGUI() {
        // デフォルトコンストラクタ
    }

    /**
     * エディタを表示（メインメソッド）
     */
    public void showEnhancedEditor(RoomAssignmentApplication.ProcessingResult result) {
        this.processingResult = result;

        // AssignmentEditorGUIを使用して表示
        AssignmentEditorGUI.showEditor(result);
    }
}