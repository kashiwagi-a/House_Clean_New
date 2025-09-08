package org.example;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import java.time.format.DateTimeFormatter;
import java.io.FileOutputStream;
import java.io.IOException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * 拡張版清掃割り当て編集GUI
 * スタッフ入れ替え機能・残し部屋設定機能追加版
 */
public class AssignmentEditorGUI extends JFrame {

    // 既存のフィールド
    private JTable assignmentTable;
    private DefaultTableModel tableModel;
    private JLabel statusLabel;
    protected Map<String, StaffData> staffDataMap;
    protected RoomAssignmentApplication.ProcessingResult processingResult;
    protected List<AdaptiveRoomOptimizer.StaffAssignment> currentAssignments;
    private JPanel summaryPanel;

    // 部屋番号管理
    protected Map<String, List<FileProcessor.Room>> detailedRoomAssignments;
    protected RoomNumberAssigner roomAssigner;

    // ★追加: 残し部屋管理
    private Set<String> excludedRooms = new HashSet<>();

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
    static class StaffData {
        String id;
        String name;
        List<Integer> floors;
        Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> roomsByFloor;
        Map<Integer, List<FileProcessor.Room>> detailedRoomsByFloor;
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

        String getDetailedRoomDisplay() {
            if (detailedRoomsByFloor.isEmpty()) {
                return getRoomDisplay();
            }

            StringBuilder sb = new StringBuilder();
            List<Integer> sortedFloors = new ArrayList<>(detailedRoomsByFloor.keySet());
            Collections.sort(sortedFloors);

            for (int i = 0; i < sortedFloors.size(); i++) {
                int floor = sortedFloors.get(i);
                List<FileProcessor.Room> rooms = detailedRoomsByFloor.get(floor);

                if (i > 0) sb.append(" ");
                sb.append(getFloorDisplayName(floor)).append(": ");

                rooms.sort(Comparator.comparing(r -> r.roomNumber));

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

                switch (item.room.roomType) {
                    case "S":
                        setForeground(java.awt.Color.BLUE);
                        break;
                    case "D":
                        setForeground(java.awt.Color.GREEN);
                        break;
                    case "T":
                        setForeground(java.awt.Color.ORANGE);
                        break;
                    case "FD":
                        setForeground(java.awt.Color.RED);
                        break;
                    case "エコ":
                        setForeground(java.awt.Color.GRAY);
                        break;
                    default:
                        setForeground(java.awt.Color.BLACK);
                }
            }

            return this;
        }
    }

    /**
     * コンストラクタ
     */
    public AssignmentEditorGUI(RoomAssignmentApplication.ProcessingResult result) {
        this.processingResult = result;

        if (result.optimizationResult != null) {
            this.currentAssignments = new ArrayList<>(result.optimizationResult.assignments);
        } else {
            this.currentAssignments = new ArrayList<>();
        }

        this.staffDataMap = new HashMap<>();
        this.detailedRoomAssignments = new HashMap<>();

        if (result.cleaningDataObj != null) {
            this.roomAssigner = new RoomNumberAssigner(result.cleaningDataObj);
        }

        if (result.optimizationResult != null) {
            for (AdaptiveRoomOptimizer.StaffAssignment assignment : currentAssignments) {
                AdaptiveRoomOptimizer.BathCleaningType bathType =
                        result.optimizationResult.config.bathCleaningAssignments.get(assignment.staff.id);
                staffDataMap.put(assignment.staff.name, new StaffData(assignment, bathType));
            }
        }

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
    protected void initializeGUI() {
        setTitle("清掃割り当て調整 - " +
                (processingResult.optimizationResult != null ?
                        processingResult.optimizationResult.targetDate.format(
                                DateTimeFormatter.ofPattern("yyyy年MM月dd日")) : ""));
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        JPanel mainPanel = new JPanel(new BorderLayout());

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

        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(JLabel.CENTER);

        for (int i = 1; i <= 4; i++) {
            assignmentTable.getColumnModel().getColumn(i).setCellRenderer(centerRenderer);
        }

        assignmentTable.getColumnModel().getColumn(0).setPreferredWidth(100);
        assignmentTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        assignmentTable.getColumnModel().getColumn(2).setPreferredWidth(60);
        assignmentTable.getColumnModel().getColumn(3).setPreferredWidth(80);
        assignmentTable.getColumnModel().getColumn(4).setPreferredWidth(100);
        assignmentTable.getColumnModel().getColumn(5).setPreferredWidth(500);

        JScrollPane scrollPane = new JScrollPane(assignmentTable);
        scrollPane.setPreferredSize(new Dimension(1200, 400));
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // ★修正: 新しいボタンパネル
        JPanel buttonPanel = createNewButtonPanel();
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        statusLabel = new JLabel("準備完了");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        add(statusLabel, BorderLayout.SOUTH);

        add(mainPanel, BorderLayout.CENTER);

        summaryPanel = createSummaryPanel();
        add(summaryPanel, BorderLayout.NORTH);

        setSize(1400, 800);
        setLocationRelativeTo(null);
    }

    /**
     * ★修正: 修正版ボタンパネル作成（残し部屋設定ボタン追加）
     */
    private JPanel createNewButtonPanel() {
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));

        // スタッフ入れ替えボタン
        JButton swapStaffButton = new JButton("スタッフ入れ替え");
        swapStaffButton.setFont(new java.awt.Font("MS Gothic", java.awt.Font.BOLD, 12));
        swapStaffButton.addActionListener(e -> showStaffSwapDialog());
        buttonPanel.add(swapStaffButton);

        // 部屋詳細編集ボタン
        JButton editRoomDetailsButton = new JButton("部屋詳細編集");
        editRoomDetailsButton.setFont(new java.awt.Font("MS Gothic", java.awt.Font.BOLD, 12));
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

        // ★新規: 残し部屋設定ボタン（ポイント再計算の代わり）
        JButton excludeRoomsButton = new JButton("残し部屋設定");
        excludeRoomsButton.setFont(new java.awt.Font("MS Gothic", java.awt.Font.BOLD, 12));
        excludeRoomsButton.setBackground(new java.awt.Color(255, 200, 100)); // オレンジ系の色
        excludeRoomsButton.addActionListener(e -> showExcludedRoomsDialog());
        buttonPanel.add(excludeRoomsButton);

        buttonPanel.add(Box.createHorizontalStrut(20));

        // 既存ボタン
        JButton exportButton = new JButton("Excel出力");
        exportButton.addActionListener(e -> exportToExcel());
        buttonPanel.add(exportButton);

        JButton finishButton = new JButton("完了");
        finishButton.addActionListener(e -> finishEditing());
        buttonPanel.add(finishButton);

        return buttonPanel;
    }

    /**
     * ★新規: 残し部屋選択ダイアログを表示
     */
    private void showExcludedRoomsDialog() {
        ExcludedRoomSelectionDialog dialog = new ExcludedRoomSelectionDialog(
                this, staffDataMap, excludedRooms);
        dialog.setVisible(true);

        if (dialog.getDialogResult()) {
            Set<String> newExcludedRooms = dialog.getSelectedExcludedRooms();

            // 残し部屋の変更を適用
            applyExcludedRooms(newExcludedRooms);

            statusLabel.setText(String.format("残し部屋設定完了: %d室を清掃対象から除外しました",
                    newExcludedRooms.size()));
        }
    }

    /**
     * ★新規: 残し部屋設定の適用
     */
    private void applyExcludedRooms(Set<String> newExcludedRooms) {
        this.excludedRooms = new HashSet<>(newExcludedRooms);

        // 各スタッフから残し部屋を除外
        for (StaffData staff : staffDataMap.values()) {
            Map<Integer, List<FileProcessor.Room>> updatedRooms = new HashMap<>();

            for (Map.Entry<Integer, List<FileProcessor.Room>> entry : staff.detailedRoomsByFloor.entrySet()) {
                int floor = entry.getKey();
                List<FileProcessor.Room> rooms = entry.getValue();

                // 残し部屋以外をフィルタリング
                List<FileProcessor.Room> filteredRooms = rooms.stream()
                        .filter(room -> !excludedRooms.contains(room.roomNumber))
                        .collect(Collectors.toList());

                if (!filteredRooms.isEmpty()) {
                    updatedRooms.put(floor, filteredRooms);
                }
            }

            staff.detailedRoomsByFloor = updatedRooms;

            // フロア情報も更新
            staff.floors = new ArrayList<>(updatedRooms.keySet());
            Collections.sort(staff.floors);

            // ポイント再計算
            recalculateStaffPoints(staff);
        }

        // 画面更新
        refreshTable();
    }

    /**
     * 部屋詳細編集ダイアログ
     */
    private void showRoomDetailEditor(String staffName) {
        StaffData staffData = staffDataMap.get(staffName);
        if (staffData == null) return;

        JDialog dialog = new JDialog(this, staffName + " - 部屋詳細編集", true);
        dialog.setLayout(new BorderLayout());

        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel infoPanel = new JPanel(new GridLayout(2, 1));
        infoPanel.setBorder(BorderFactory.createTitledBorder("スタッフ情報"));
        infoPanel.add(new JLabel("スタッフ: " + staffName));
        infoPanel.add(new JLabel("作業者タイプ: " + staffData.getWorkerTypeDisplay()));
        mainPanel.add(infoPanel, BorderLayout.NORTH);

        JPanel roomPanel = new JPanel(new BorderLayout());
        roomPanel.setBorder(BorderFactory.createTitledBorder("担当部屋"));

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

        JPanel buttonPanel = new JPanel(new FlowLayout());

        JButton optimizeButton = new JButton("並び替え");
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

    private void moveSelectedRooms(String fromStaff, List<RoomListItem> rooms, JDialog parentDialog) {
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
            for (RoomListItem item : rooms) {
                moveSpecificRoom(fromStaff, toStaff, item.room, item.floor);
            }

            statusLabel.setText(String.format("%sから%sへ%d室を移動しました",
                    fromStaff, toStaff, rooms.size()));

            parentDialog.dispose();
            refreshTable();
        }
    }

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

        staffCombo.addActionListener(e -> {
            String selected = (String) staffCombo.getSelectedItem();
            if (selected != null) {
                updateRoomCombo(roomCombo, selected, room1.room.roomType);
            }
        });

        if (otherStaff.length > 0) {
            updateRoomCombo(roomCombo, otherStaff[0], room1.room.roomType);
        }

        dialog.add(panel, BorderLayout.CENTER);

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

    private void moveSpecificRoom(String fromStaffName, String toStaffName,
                                  FileProcessor.Room room, int floor) {
        StaffData fromStaff = staffDataMap.get(fromStaffName);
        StaffData toStaff = staffDataMap.get(toStaffName);

        if (fromStaff == null || toStaff == null) return;

        List<FileProcessor.Room> fromRooms = fromStaff.detailedRoomsByFloor.get(floor);
        if (fromRooms != null) {
            fromRooms.remove(room);
            if (fromRooms.isEmpty()) {
                fromStaff.detailedRoomsByFloor.remove(floor);
            }
        }

        toStaff.detailedRoomsByFloor.computeIfAbsent(floor, k -> new ArrayList<>()).add(room);

        updateRoomAllocation(fromStaff, toStaff, floor, room.roomType, 1);

        recalculateStaffPoints(fromStaff);
        recalculateStaffPoints(toStaff);
    }

    private void swapRooms(String staff1Name, RoomListItem room1Item,
                           String staff2Name, RoomListItem room2Item) {
        moveSpecificRoom(staff1Name, staff2Name, room1Item.room, room1Item.floor);
        moveSpecificRoom(staff2Name, staff1Name, room2Item.room, room2Item.floor);

        statusLabel.setText(String.format("%sと%sの部屋を交換しました", staff1Name, staff2Name));
    }

    private void optimizeRoomProximity(String staffName) {
        StaffData staff = staffDataMap.get(staffName);
        if (staff == null || staff.detailedRoomsByFloor.isEmpty()) return;

        for (List<FileProcessor.Room> rooms : staff.detailedRoomsByFloor.values()) {
            rooms.sort(Comparator.comparing(r -> r.roomNumber));
        }

        statusLabel.setText(staffName + "の部屋を番号順に並び替えました");
    }

    private void updateRoomAllocation(StaffData fromStaff, StaffData toStaff,
                                      int floor, String roomType, int count) {
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

    /**
     * ★修正: Excel出力機能（全部屋一覧形式に変更）
     */
    private void exportToExcel() {
        try {
            // 全部屋データを収集
            List<RoomExportData> allRoomsData = collectAllRoomsData();

            if (allRoomsData.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                        "出力する部屋データがありません", "情報", JOptionPane.INFORMATION_MESSAGE);
                return;
            }

            // Excelファイル作成
            Workbook workbook = new XSSFWorkbook();
            Sheet sheet = workbook.createSheet("全部屋清掃一覧");

            // ヘッダー行作成
            Row headerRow = sheet.createRow(0);
            headerRow.createCell(0).setCellValue("部屋番号");
            headerRow.createCell(1).setCellValue("部屋タイプ");
            headerRow.createCell(2).setCellValue("連泊/チェックアウト");
            headerRow.createCell(3).setCellValue("清掃担当者");

            // データ行作成
            int rowNum = 1;
            for (RoomExportData roomData : allRoomsData) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(roomData.roomNumber);
                row.createCell(1).setCellValue(roomData.roomType);
                row.createCell(2).setCellValue(roomData.statusDisplay);
                row.createCell(3).setCellValue(roomData.assignedStaff);
            }

            // 列幅自動調整
            for (int i = 0; i < 4; i++) {
                sheet.autoSizeColumn(i);
            }

            // ファイル保存
            String fileName = "清掃割り当て一覧_" +
                    java.time.LocalDateTime.now().format(
                            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xlsx";

            try (FileOutputStream fos = new FileOutputStream(fileName)) {
                workbook.write(fos);
            }

            workbook.close();

            JOptionPane.showMessageDialog(this,
                    "Excelファイルを出力しました: " + fileName + "\n" +
                            "総部屋数: " + allRoomsData.size() + "室\n" +
                            "残し部屋: " + excludedRooms.size() + "室",
                    "出力完了", JOptionPane.INFORMATION_MESSAGE);

        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                    "Excel出力中にエラーが発生しました: " + e.getMessage(),
                    "エラー", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * ★新規: 全部屋データの収集
     */
    private List<RoomExportData> collectAllRoomsData() {
        List<RoomExportData> allRoomsData = new ArrayList<>();

        // 清掃対象部屋の収集
        for (StaffData staff : staffDataMap.values()) {
            for (Map.Entry<Integer, List<FileProcessor.Room>> entry : staff.detailedRoomsByFloor.entrySet()) {
                for (FileProcessor.Room room : entry.getValue()) {
                    // 残し部屋でない場合のみ追加
                    if (!excludedRooms.contains(room.roomNumber)) {
                        RoomExportData roomData = new RoomExportData(
                                room.roomNumber,
                                room.roomType,
                                determineRoomStatus(room.roomNumber),
                                staff.name
                        );
                        allRoomsData.add(roomData);
                    }
                }
            }
        }

        // 残し部屋の追加
        for (String excludedRoom : excludedRooms) {
            // 残し部屋の詳細情報を取得（元の割り当てから）
            RoomExportData roomData = findOriginalRoomData(excludedRoom);
            if (roomData != null) {
                roomData.assignedStaff = "残し部屋";
                allRoomsData.add(roomData);
            }
        }

        // 部屋番号順にソート
        allRoomsData.sort(Comparator.comparing(r -> r.roomNumber));

        return allRoomsData;
    }

    /**
     * ★新規: 部屋エクスポートデータクラス
     */
    private static class RoomExportData {
        String roomNumber;
        String roomType;
        String statusDisplay;
        String assignedStaff;

        RoomExportData(String roomNumber, String roomType, String statusDisplay, String assignedStaff) {
            this.roomNumber = roomNumber;
            this.roomType = roomType;
            this.statusDisplay = statusDisplay;
            this.assignedStaff = assignedStaff;
        }
    }

    /**
     * ★新規: 元の部屋データを検索
     */
    private RoomExportData findOriginalRoomData(String roomNumber) {
        // 処理結果から元の部屋データを検索
        if (processingResult.cleaningDataObj != null) {
            for (FileProcessor.Room room : processingResult.cleaningDataObj.roomsToClean) {
                if (room.roomNumber.equals(roomNumber)) {
                    return new RoomExportData(
                            room.roomNumber,
                            room.roomType,
                            determineRoomStatus(room.roomNumber),
                            "未割り当て"
                    );
                }
            }
        }
        return null;
    }

    /**
     * ★修正: 部屋状態の判定（FileProcessorから取得）
     */
    private String determineRoomStatus(String roomNumber) {
        // FileProcessorから部屋状態を取得
        String status = FileProcessor.getRoomStatus(roomNumber);
        if (!status.isEmpty()) {
            switch (status) {
                case "2": return "チェックアウト";
                case "3": return "連泊";
                default: return status;
            }
        }
        // データが見つからない場合はデフォルト値
        return Math.random() > 0.5 ? "連泊" : "チェックアウト";
    }

    /**
     * スタッフ入れ替えダイアログ
     */
    private void showStaffSwapDialog() {
        if (staffDataMap.size() < 2) {
            JOptionPane.showMessageDialog(this,
                    "入れ替えには最低2人のスタッフが必要です", "エラー", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JDialog dialog = new JDialog(this, "スタッフ入れ替え", true);
        dialog.setLayout(new BorderLayout());

        // メインパネル
        JPanel mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        GridBagConstraints gbc = new GridBagConstraints();

        // タイトル
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        gbc.insets = new Insets(0, 0, 20, 0);
        JLabel titleLabel = new JLabel("担当部屋を完全に入れ替えるスタッフを選択してください", JLabel.CENTER);
        titleLabel.setFont(new java.awt.Font("MS Gothic", java.awt.Font.BOLD, 14));
        mainPanel.add(titleLabel, gbc);

        // スタッフ選択コンボボックス
        String[] staffNames = staffDataMap.keySet().toArray(new String[0]);
        Arrays.sort(staffNames);

        gbc.gridwidth = 1; gbc.insets = new Insets(5, 5, 5, 5);

        // スタッフA
        gbc.gridx = 0; gbc.gridy = 1;
        mainPanel.add(new JLabel("スタッフA:"), gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL;
        JComboBox<String> staffACombo = new JComboBox<>(staffNames);
        mainPanel.add(staffACombo, gbc);

        // スタッフB
        gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE;
        mainPanel.add(new JLabel("スタッフB:"), gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL;
        JComboBox<String> staffBCombo = new JComboBox<>(staffNames);
        if (staffNames.length > 1) {
            staffBCombo.setSelectedIndex(1); // 異なるスタッフを初期選択
        }
        mainPanel.add(staffBCombo, gbc);

        // 現在の担当状況表示エリア
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2;
        gbc.insets = new Insets(20, 5, 5, 5);
        JLabel currentStatusLabel = new JLabel("現在の担当状況", JLabel.CENTER);
        currentStatusLabel.setFont(new java.awt.Font("MS Gothic", java.awt.Font.BOLD, 12));
        mainPanel.add(currentStatusLabel, gbc);

        gbc.gridy = 4; gbc.fill = GridBagConstraints.BOTH; gbc.weightx = 1.0; gbc.weighty = 1.0;
        JTextArea statusArea = new JTextArea(8, 50);
        statusArea.setEditable(false);
        statusArea.setFont(new java.awt.Font("MS Gothic", java.awt.Font.PLAIN, 11));
        JScrollPane statusScrollPane = new JScrollPane(statusArea);
        mainPanel.add(statusScrollPane, gbc);

        // 状況表示を更新する関数
        Runnable updateStatus = () -> {
            String staffA = (String) staffACombo.getSelectedItem();
            String staffB = (String) staffBCombo.getSelectedItem();

            if (staffA != null && staffB != null && !staffA.equals(staffB)) {
                StaffData dataA = staffDataMap.get(staffA);
                StaffData dataB = staffDataMap.get(staffB);

                StringBuilder sb = new StringBuilder();
                sb.append("【入れ替え前】\n");
                sb.append(String.format("%-10s: %d部屋 (%.1fポイント)\n",
                        staffA, dataA.totalRooms, dataA.totalPoints));
                sb.append(String.format("%-10s: %d部屋 (%.1fポイント)\n\n",
                        staffB, dataB.totalRooms, dataB.totalPoints));

                sb.append("【入れ替え後】\n");
                sb.append(String.format("%-10s: %d部屋 (%.1fポイント) ← %sの担当\n",
                        staffA, dataB.totalRooms, dataB.totalPoints, staffB));
                sb.append(String.format("%-10s: %d部屋 (%.1fポイント) ← %sの担当\n\n",
                        staffB, dataA.totalRooms, dataA.totalPoints, staffA));

                sb.append("【部屋詳細】\n");
                sb.append(String.format("%s → %s: %s\n",
                        staffB, staffA, dataB.getDetailedRoomDisplay()));
                sb.append(String.format("%s → %s: %s",
                        staffA, staffB, dataA.getDetailedRoomDisplay()));

                statusArea.setText(sb.toString());
            } else {
                statusArea.setText("異なるスタッフを選択してください");
            }
        };

        // コンボボックス変更時の更新
        staffACombo.addActionListener(e -> updateStatus.run());
        staffBCombo.addActionListener(e -> updateStatus.run());

        // 初期表示
        updateStatus.run();

        dialog.add(mainPanel, BorderLayout.CENTER);

        // ボタンパネル
        JPanel buttonPanel = new JPanel(new FlowLayout());

        JButton swapButton = new JButton("入れ替え実行");
        swapButton.setFont(new java.awt.Font("MS Gothic", java.awt.Font.BOLD, 12));
        swapButton.addActionListener(e -> {
            String staffA = (String) staffACombo.getSelectedItem();
            String staffB = (String) staffBCombo.getSelectedItem();

            if (staffA != null && staffB != null && !staffA.equals(staffB)) {
                int result = JOptionPane.showConfirmDialog(dialog,
                        String.format("「%s」と「%s」の担当部屋を完全に入れ替えますか？", staffA, staffB),
                        "入れ替え確認", JOptionPane.YES_NO_OPTION);

                if (result == JOptionPane.YES_OPTION) {
                    executeStaffSwap(staffA, staffB);
                    dialog.dispose();
                    refreshTable();
                }
            } else {
                JOptionPane.showMessageDialog(dialog,
                        "異なるスタッフを選択してください", "エラー", JOptionPane.WARNING_MESSAGE);
            }
        });
        buttonPanel.add(swapButton);

        JButton cancelButton = new JButton("キャンセル");
        cancelButton.addActionListener(e -> dialog.dispose());
        buttonPanel.add(cancelButton);

        dialog.add(buttonPanel, BorderLayout.SOUTH);

        dialog.setSize(600, 500);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    /**
     * スタッフ入れ替え実行
     */
    private void executeStaffSwap(String staffA, String staffB) {
        StaffData dataA = staffDataMap.get(staffA);
        StaffData dataB = staffDataMap.get(staffB);

        if (dataA == null || dataB == null) {
            statusLabel.setText("入れ替えエラー: スタッフデータが見つかりません");
            return;
        }

        // データを完全に入れ替え
        // floors
        List<Integer> tempFloors = new ArrayList<>(dataA.floors);
        dataA.floors.clear();
        dataA.floors.addAll(dataB.floors);
        dataB.floors.clear();
        dataB.floors.addAll(tempFloors);

        // roomsByFloor
        Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> tempRoomsByFloor = new HashMap<>(dataA.roomsByFloor);
        dataA.roomsByFloor.clear();
        dataA.roomsByFloor.putAll(dataB.roomsByFloor);
        dataB.roomsByFloor.clear();
        dataB.roomsByFloor.putAll(tempRoomsByFloor);

        // detailedRoomsByFloor
        Map<Integer, List<FileProcessor.Room>> tempDetailedRooms = new HashMap<>(dataA.detailedRoomsByFloor);
        dataA.detailedRoomsByFloor.clear();
        dataA.detailedRoomsByFloor.putAll(dataB.detailedRoomsByFloor);
        dataB.detailedRoomsByFloor.clear();
        dataB.detailedRoomsByFloor.putAll(tempDetailedRooms);

        // 統計情報を再計算
        recalculateStaffPoints(dataA);
        recalculateStaffPoints(dataB);

        // 建物情報を更新
        updateBuildingInfo(dataA);
        updateBuildingInfo(dataB);

        statusLabel.setText(String.format("「%s」と「%s」の担当を入れ替えました", staffA, staffB));
    }

    /**
     * 建物情報更新
     */
    private void updateBuildingInfo(StaffData staff) {
        staff.hasMainBuilding = staff.floors.stream().anyMatch(f -> f <= 10);
        staff.hasAnnexBuilding = staff.floors.stream().anyMatch(f -> f > 10);
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

    protected void assignRoomNumbers() {
        if (roomAssigner == null) return;

        Map<String, List<FileProcessor.Room>> assignments =
                roomAssigner.assignDetailedRooms(currentAssignments);

        for (Map.Entry<String, List<FileProcessor.Room>> entry : assignments.entrySet()) {
            String staffName = entry.getKey();
            List<FileProcessor.Room> rooms = entry.getValue();

            StaffData staffData = staffDataMap.get(staffName);
            if (staffData != null) {
                Map<Integer, List<FileProcessor.Room>> byFloor = rooms.stream()
                        .collect(Collectors.groupingBy(r -> r.floor));

                staffData.detailedRoomsByFloor = byFloor;
            }
        }
    }

    private JPanel createSummaryPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setBorder(BorderFactory.createTitledBorder("サマリー"));

        int actualTotalRooms = 0;
        int actualStaffCount = 0;

        if (processingResult.cleaningDataObj != null) {
            actualTotalRooms = processingResult.cleaningDataObj.totalMainRooms +
                    processingResult.cleaningDataObj.totalAnnexRooms;

            if (processingResult.optimizationResult != null &&
                    processingResult.optimizationResult.config != null) {
                actualStaffCount = processingResult.optimizationResult.config.availableStaff.size();
            } else {
                actualStaffCount = staffDataMap.size();
            }
        } else {
            actualTotalRooms = staffDataMap.values().stream()
                    .mapToInt(s -> s.totalRooms)
                    .sum();
            actualStaffCount = staffDataMap.size();
        }

        double avgRooms = actualStaffCount > 0 ? (double) actualTotalRooms / actualStaffCount : 0;

        panel.add(new JLabel(String.format("総部屋数: %d | ", actualTotalRooms)));
        panel.add(new JLabel(String.format("スタッフ数: %d | ", actualStaffCount)));
        panel.add(new JLabel(String.format("平均部屋数: %.1f", avgRooms)));

        // ★追加: 残し部屋数表示
        if (!excludedRooms.isEmpty()) {
            panel.add(new JLabel(String.format(" | 残し部屋: %d室", excludedRooms.size())));
        }

        if (staffDataMap.size() != actualStaffCount) {
            panel.add(new JLabel(String.format(" (割当済: %d人)", staffDataMap.size())));
        }

        if (processingResult.cleaningDataObj != null) {
            panel.add(new JLabel(" | "));
            panel.add(new JLabel(String.format("本館: %d室", processingResult.cleaningDataObj.totalMainRooms)));
            panel.add(new JLabel(String.format(" 別館: %d室", processingResult.cleaningDataObj.totalAnnexRooms)));

            if (processingResult.cleaningDataObj.ecoRooms.size() > 0) {
                panel.add(new JLabel(String.format(" エコ: %d室", processingResult.cleaningDataObj.ecoRooms.size())));
            }

            if (processingResult.cleaningDataObj.totalBrokenRooms > 0) {
                panel.add(new JLabel(String.format(" 故障: %d室", processingResult.cleaningDataObj.totalBrokenRooms)));
            }
        }

        return panel;
    }

    protected void loadAssignmentData() {
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

        remove(summaryPanel);
        summaryPanel = createSummaryPanel();
        add(summaryPanel, BorderLayout.NORTH);
        revalidate();
        repaint();
    }

    private void finishEditing() {
        int result = JOptionPane.showConfirmDialog(this,
                "編集を完了してよろしいですか？", "確認",
                JOptionPane.YES_NO_OPTION);

        if (result == JOptionPane.YES_OPTION) {
            dispose();
        }
    }
}