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
            this.detailedRoomsByFloor = new HashMap<>();
            this.bathCleaningType = bathType;
            this.hasMainBuilding = assignment.floors.stream().anyMatch(f -> f <= 10);
            this.hasAnnexBuilding = assignment.floors.stream().anyMatch(f -> f > 10);

            calculatePoints();
        }

        private void calculatePoints() {
            double totalPoints = 0;
            int totalRooms = 0;

            for (AdaptiveRoomOptimizer.RoomAllocation allocation : roomsByFloor.values()) {
                for (Map.Entry<String, Integer> entry : allocation.roomCounts.entrySet()) {
                    String type = entry.getKey();
                    int count = entry.getValue();
                    totalPoints += ROOM_POINTS.getOrDefault(type, 1.0) * count;
                    totalRooms += count;
                }
                totalPoints += ROOM_POINTS.get("ECO") * allocation.ecoRooms;
                totalRooms += allocation.ecoRooms;
            }

            // 大浴場清掃のポイント調整
            if (bathCleaningType != AdaptiveRoomOptimizer.BathCleaningType.NONE) {
                totalPoints += bathCleaningType.reduction;
            }

            this.totalPoints = totalPoints;
            this.totalRooms = totalRooms;
            this.adjustedScore = totalPoints;
        }

        String getWorkerTypeDisplay() {
            if (bathCleaningType != AdaptiveRoomOptimizer.BathCleaningType.NONE) {
                return bathCleaningType.displayName;
            }
            return "通常";
        }

        String getDetailedRoomDisplay() {
            StringBuilder sb = new StringBuilder();
            List<Integer> sortedFloors = new ArrayList<>(floors);
            Collections.sort(sortedFloors);

            for (int i = 0; i < sortedFloors.size(); i++) {
                if (i > 0) sb.append(" ");

                int floor = sortedFloors.get(i);
                sb.append(getFloorDisplayName(floor)).append("(");

                // 詳細部屋情報がある場合
                if (detailedRoomsByFloor.containsKey(floor)) {
                    List<FileProcessor.Room> rooms = detailedRoomsByFloor.get(floor);
                    List<String> roomNumbers = rooms.stream()
                            .map(room -> room.roomNumber)
                            .sorted()
                            .collect(Collectors.toList());
                    sb.append(String.join(",", roomNumbers));
                } else {
                    // 基本情報のみ
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
                }
                sb.append(")");
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
            String floorName = floor <= 20 ?
                    floor + "階" : "別館" + (floor - 20) + "階";
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
     * ★修正版: サマリーパネルの作成（エコ清掃部屋数を常に表示）
     */
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

        double avgRooms = actualStaffCount > 0 ?
                (double) actualTotalRooms / actualStaffCount : 0;

        panel.add(new JLabel(String.format("総部屋数: %d | ", actualTotalRooms)));
        panel.add(new JLabel(String.format("スタッフ数: %d | ", actualStaffCount)));
        panel.add(new JLabel(String.format("平均部屋数: %.1f", avgRooms)));

        // 残し部屋数表示
        if (!excludedRooms.isEmpty()) {
            panel.add(new JLabel(String.format(" | 残し部屋: %d室", excludedRooms.size())));
        }

        if (staffDataMap.size() != actualStaffCount) {
            panel.add(new JLabel(String.format(" (割当済: %d人)", staffDataMap.size())));
        }

        if (processingResult.cleaningDataObj != null) {
            panel.add(new JLabel(" | "));

            // ★修正: エコ清掃部屋の建物別集計
            int mainEcoRooms = countEcoRoomsInBuilding(true);   // 本館のエコ部屋数
            int annexEcoRooms = countEcoRoomsInBuilding(false); // 別館のエコ部屋数

            // ★修正: 本館表示（エコ部屋数を内訳として常に表示）
            panel.add(new JLabel(String.format("本館: %d室（内エコ；%d）",
                    processingResult.cleaningDataObj.totalMainRooms, mainEcoRooms)));

            // ★修正: 別館表示（エコ部屋数を内訳として常に表示）
            panel.add(new JLabel(String.format(" 別館: %d室（内エコ；%d）",
                    processingResult.cleaningDataObj.totalAnnexRooms, annexEcoRooms)));

            // 故障部屋数表示
            if (processingResult.cleaningDataObj.totalBrokenRooms > 0) {
                panel.add(new JLabel(String.format(" 故障: %d室",
                        processingResult.cleaningDataObj.totalBrokenRooms)));
            }
        }

        return panel;
    }

    /**
     * ★追加: エコ清掃部屋を建物別に集計するメソッド
     * @param isMainBuilding true=本館、false=別館
     * @return 指定建物のエコ清掃部屋数
     */
    private int countEcoRoomsInBuilding(boolean isMainBuilding) {
        if (processingResult.cleaningDataObj == null ||
                processingResult.cleaningDataObj.ecoRooms == null) {
            return 0;
        }

        int count = 0;
        for (FileProcessor.Room ecoRoom : processingResult.cleaningDataObj.ecoRooms) {
            // 部屋番号から階数を抽出（例：201 → 2階）
            int floor = extractFloorFromRoomNumber(ecoRoom.roomNumber);

            // 建物判定（10階以下は本館、11階以上は別館）
            boolean roomIsMainBuilding = (floor <= 10);

            if (roomIsMainBuilding == isMainBuilding) {
                count++;
            }
        }

        return count;
    }

    /**
     * ★追加: 部屋番号から階数を抽出するヘルパーメソッド
     * @param roomNumber 部屋番号（例："201", "1201"）
     * @return 階数（例：2, 12）
     */
    private int extractFloorFromRoomNumber(String roomNumber) {
        try {
            // 数字以外を削除
            String numericPart = roomNumber.replaceAll("[^0-9]", "");

            if (numericPart.isEmpty()) {
                return 1; // デフォルト
            }

            // 3桁の数字（本館）- 例：201 → 2階
            if (numericPart.length() == 3) {
                return Integer.parseInt(numericPart.substring(0, 1));
            }
            // 4桁の数字で10で始まる場合（本館10階）- 例：1001 → 10階
            else if (numericPart.length() == 4 && numericPart.startsWith("10")) {
                return 10;
            }
            // 4桁の数字（別館）- 例：1201 → 12階
            else if (numericPart.length() == 4) {
                return Integer.parseInt(numericPart.substring(0, 2));
            }
            // その他の場合
            else if (numericPart.length() >= 2) {
                // 最初の1〜2桁を階数として取得
                if (numericPart.length() >= 3) {
                    return Integer.parseInt(numericPart.substring(0, numericPart.length() - 2));
                } else {
                    return Integer.parseInt(numericPart.substring(0, 1));
                }
            }

            return 1; // デフォルト
        } catch (NumberFormatException e) {
            // エラー時のデフォルト
            return 1;
        }
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

        // ボタンパネル
        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton executeButton = new JButton("入れ替え実行");
        executeButton.addActionListener(e -> {
            String staffA = (String) staffACombo.getSelectedItem();
            String staffB = (String) staffBCombo.getSelectedItem();

            if (staffA.equals(staffB)) {
                JOptionPane.showMessageDialog(dialog,
                        "異なるスタッフを選択してください", "エラー", JOptionPane.WARNING_MESSAGE);
                return;
            }

            int confirm = JOptionPane.showConfirmDialog(dialog,
                    String.format("「%s」と「%s」の担当を入れ替えますか？", staffA, staffB),
                    "確認", JOptionPane.YES_NO_OPTION);

            if (confirm == JOptionPane.YES_OPTION) {
                swapStaffAssignments(staffA, staffB);
                refreshTable();
                dialog.dispose();
            }
        });

        JButton cancelButton = new JButton("キャンセル");
        cancelButton.addActionListener(e -> dialog.dispose());

        buttonPanel.add(executeButton);
        buttonPanel.add(cancelButton);

        dialog.add(mainPanel, BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    /**
     * スタッフ割り当て入れ替え
     */
    private void swapStaffAssignments(String staffA, String staffB) {
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

    /**
     * 部屋詳細編集ダイアログ（部屋入れ替え機能付き）
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

        JButton closeButton = new JButton("閉じる");
        closeButton.addActionListener(e -> dialog.dispose());
        mainPanel.add(closeButton, BorderLayout.SOUTH);

        dialog.add(mainPanel);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    /**
     * 選択した部屋を他のスタッフに移動する
     */
    private void moveSelectedRooms(String fromStaff, List<RoomListItem> selectedRooms, JDialog parentDialog) {
        String[] staffNames = staffDataMap.keySet().stream()
                .filter(name -> !name.equals(fromStaff))
                .toArray(String[]::new);

        if (staffNames.length == 0) {
            JOptionPane.showMessageDialog(parentDialog, "移動先のスタッフがいません", "エラー", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String targetStaff = (String) JOptionPane.showInputDialog(
                parentDialog,
                "移動先のスタッフを選択してください:",
                "部屋移動",
                JOptionPane.QUESTION_MESSAGE,
                null,
                staffNames,
                staffNames[0]
        );

        if (targetStaff != null) {
            moveRoomsToStaff(fromStaff, targetStaff, selectedRooms);
            parentDialog.dispose();
            statusLabel.setText(String.format("%d室を %s から %s に移動しました",
                    selectedRooms.size(), fromStaff, targetStaff));
            refreshTable();
        }
    }

    /**
     * 部屋交換ダイアログを表示
     */
    private void showRoomSwapDialog(String staffName, RoomListItem roomToSwap, JDialog parentDialog) {
        // 他のスタッフの部屋リストを作成
        DefaultListModel<RoomListItem> swapCandidates = new DefaultListModel<>();
        Map<String, StaffData> otherStaff = new HashMap<>();

        for (Map.Entry<String, StaffData> entry : staffDataMap.entrySet()) {
            if (!entry.getKey().equals(staffName)) {
                StaffData staff = entry.getValue();
                otherStaff.put(entry.getKey(), staff);

                for (Map.Entry<Integer, List<FileProcessor.Room>> floorEntry : staff.detailedRoomsByFloor.entrySet()) {
                    int floor = floorEntry.getKey();
                    for (FileProcessor.Room room : floorEntry.getValue()) {
                        RoomListItem item = new RoomListItem(room, floor);
                        swapCandidates.addElement(item);
                    }
                }
            }
        }

        if (swapCandidates.isEmpty()) {
            JOptionPane.showMessageDialog(parentDialog, "交換できる部屋がありません", "エラー", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JDialog swapDialog = new JDialog(parentDialog, "部屋交換", true);
        swapDialog.setLayout(new BorderLayout());

        JPanel infoPanel = new JPanel(new GridLayout(2, 1));
        infoPanel.setBorder(BorderFactory.createTitledBorder("交換する部屋"));
        infoPanel.add(new JLabel("現在の部屋: " + roomToSwap.toString()));
        infoPanel.add(new JLabel("担当スタッフ: " + staffName));
        swapDialog.add(infoPanel, BorderLayout.NORTH);

        JList<RoomListItem> candidateList = new JList<>(swapCandidates);
        candidateList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        candidateList.setCellRenderer(new RoomListCellRenderer());

        JScrollPane scrollPane = new JScrollPane(candidateList);
        scrollPane.setPreferredSize(new Dimension(400, 200));
        scrollPane.setBorder(BorderFactory.createTitledBorder("交換先の部屋を選択"));
        swapDialog.add(scrollPane, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton executeButton = new JButton("交換実行");
        executeButton.addActionListener(e -> {
            RoomListItem selectedCandidate = candidateList.getSelectedValue();
            if (selectedCandidate != null) {
                // 交換先のスタッフを特定
                String targetStaff = findStaffForRoom(selectedCandidate.room);
                if (targetStaff != null) {
                    swapRoomsBetweenStaff(staffName, roomToSwap, targetStaff, selectedCandidate);
                    swapDialog.dispose();
                    parentDialog.dispose();
                    statusLabel.setText(String.format("部屋交換完了: %s ↔ %s",
                            roomToSwap.room.roomNumber, selectedCandidate.room.roomNumber));
                    refreshTable();
                }
            } else {
                JOptionPane.showMessageDialog(swapDialog, "交換先の部屋を選択してください", "エラー", JOptionPane.WARNING_MESSAGE);
            }
        });

        JButton cancelButton = new JButton("キャンセル");
        cancelButton.addActionListener(e -> swapDialog.dispose());

        buttonPanel.add(executeButton);
        buttonPanel.add(cancelButton);
        swapDialog.add(buttonPanel, BorderLayout.SOUTH);

        swapDialog.pack();
        swapDialog.setLocationRelativeTo(parentDialog);
        swapDialog.setVisible(true);
    }

    /**
     * 指定した部屋を担当しているスタッフを見つける
     */
    private String findStaffForRoom(FileProcessor.Room targetRoom) {
        for (Map.Entry<String, StaffData> entry : staffDataMap.entrySet()) {
            StaffData staff = entry.getValue();
            for (List<FileProcessor.Room> rooms : staff.detailedRoomsByFloor.values()) {
                for (FileProcessor.Room room : rooms) {
                    if (room.roomNumber.equals(targetRoom.roomNumber)) {
                        return entry.getKey();
                    }
                }
            }
        }
        return null;
    }

    /**
     * 部屋をスタッフ間で移動
     */
    private void moveRoomsToStaff(String fromStaff, String toStaff, List<RoomListItem> roomsToMove) {
        StaffData fromData = staffDataMap.get(fromStaff);
        StaffData toData = staffDataMap.get(toStaff);

        if (fromData == null || toData == null) return;

        // 移動する部屋を削除
        for (RoomListItem roomItem : roomsToMove) {
            removeRoomFromStaff(fromData, roomItem.room);
            addRoomToStaff(toData, roomItem.room, roomItem.floor);
        }

        // ポイント再計算
        recalculateStaffPoints(fromData);
        recalculateStaffPoints(toData);
    }

    /**
     * 2つのスタッフ間で部屋を交換
     */
    private void swapRoomsBetweenStaff(String staff1, RoomListItem room1, String staff2, RoomListItem room2) {
        StaffData data1 = staffDataMap.get(staff1);
        StaffData data2 = staffDataMap.get(staff2);

        if (data1 == null || data2 == null) return;

        // 部屋を削除
        removeRoomFromStaff(data1, room1.room);
        removeRoomFromStaff(data2, room2.room);

        // 部屋を追加（交換）
        addRoomToStaff(data1, room2.room, room2.floor);
        addRoomToStaff(data2, room1.room, room1.floor);

        // ポイント再計算
        recalculateStaffPoints(data1);
        recalculateStaffPoints(data2);
    }

    /**
     * スタッフから部屋を削除
     */
    private void removeRoomFromStaff(StaffData staff, FileProcessor.Room room) {
        for (Map.Entry<Integer, List<FileProcessor.Room>> entry : staff.detailedRoomsByFloor.entrySet()) {
            List<FileProcessor.Room> rooms = entry.getValue();
            rooms.removeIf(r -> r.roomNumber.equals(room.roomNumber));
        }

        // 空になったフロアを削除
        staff.detailedRoomsByFloor.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        staff.floors = new ArrayList<>(staff.detailedRoomsByFloor.keySet());
        Collections.sort(staff.floors);
    }

    /**
     * スタッフに部屋を追加
     */
    private void addRoomToStaff(StaffData staff, FileProcessor.Room room, int floor) {
        staff.detailedRoomsByFloor.computeIfAbsent(floor, k -> new ArrayList<>()).add(room);

        if (!staff.floors.contains(floor)) {
            staff.floors.add(floor);
            Collections.sort(staff.floors);
        }
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

    private void exportToExcel() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Excelファイルとして保存");
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Excel Files", "xlsx"));

        if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                String filePath = fileChooser.getSelectedFile().getAbsolutePath();
                if (!filePath.endsWith(".xlsx")) {
                    filePath += ".xlsx";
                }
                createExcelFile(filePath);
                statusLabel.setText("Excelファイルが保存されました: " + filePath);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this,
                        "ファイル保存中にエラーが発生しました: " + e.getMessage(),
                        "エラー", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void createExcelFile(String filePath) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("清掃割り当て");

            // ヘッダー作成
            Row headerRow = sheet.createRow(0);
            String[] headers = {"スタッフ名", "作業者タイプ", "部屋数", "ポイント", "調整後スコア", "担当階・部屋詳細"};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
            }

            // データ行作成
            int rowNum = 1;
            for (StaffData staff : staffDataMap.values()) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(staff.name);
                row.createCell(1).setCellValue(staff.getWorkerTypeDisplay());
                row.createCell(2).setCellValue(staff.totalRooms);
                row.createCell(3).setCellValue(staff.totalPoints);
                row.createCell(4).setCellValue(staff.adjustedScore);
                row.createCell(5).setCellValue(staff.getDetailedRoomDisplay());
            }

            // 列幅自動調整
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            try (FileOutputStream fileOut = new FileOutputStream(filePath)) {
                workbook.write(fileOut);
            }
        }
    }

    private String getRoomStatusDisplay(String status) {
        return "3".equals(status) ? "連泊" : "チェックアウト";
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