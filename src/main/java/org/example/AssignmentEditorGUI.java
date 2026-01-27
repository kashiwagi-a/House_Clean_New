package org.example;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import java.time.format.DateTimeFormatter;
import java.io.FileOutputStream;
import java.io.IOException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.util.OptionalInt;

/**
 * 拡張版清掃割り当て編集GUI
 * スタッフ入れ替え機能・残し部屋設定機能追加版
 * ★修正: エコ清掃部屋のポイント計算問題を修正
 * ★修正: 表示順序変更と列変更（ポイント→内ツイン数、調整後スコア→内エコ部屋数、総部屋数追加）
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

    // ★追加: グループ境界の行インデックス（罫線表示用）
    private Set<Integer> groupBoundaryRows = new HashSet<>();

    // ★修正: 部屋タイプ別のポイント（ECOは削除）
    private static final Map<String, Double> ROOM_POINTS = new HashMap<>() {{
        put("S", 1.0);
        put("D", 1.0);
        put("T", 1.67);
        put("FD", 2.0);
        // put("ECO", 0.2); ← 削除（エコ清掃は部屋タイプではない）
    }};

    /**
     * スタッフデータ拡張版
     * ★修正: ツイン数、エコ部屋数、換算値、制約タイプを追加
     */
    static class StaffData {
        String id;
        String name;
        List<Integer> floors;
        Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> roomsByFloor;
        Map<Integer, List<FileProcessor.Room>> detailedRoomsByFloor;
        Map<Integer, List<FileProcessor.Room>> originalDetailedRoomsByFloor;  // ★追加: 元の部屋データ保持用
        int totalRooms;
        double totalPoints;
        double adjustedScore;
        boolean hasMainBuilding;
        boolean hasAnnexBuilding;
        AdaptiveRoomOptimizer.BathCleaningType bathCleaningType;

        // ★追加: 新しいフィールド
        int twinRoomCount;           // 内ツイン数
        int ecoRoomCount;            // 内エコ部屋数
        int singleRoomCount;         // 通常清掃シングル数（エコ・ツイン除く）
        double convertedTotalRooms;  // 総部屋数（換算値）
        String constraintType;       // 制約タイプ

        StaffData(AdaptiveRoomOptimizer.StaffAssignment assignment,
                  AdaptiveRoomOptimizer.BathCleaningType bathType,
                  String constraintType) {
            this.id = assignment.staff.id;
            this.name = assignment.staff.name;
            this.floors = new ArrayList<>(assignment.floors);
            this.roomsByFloor = new HashMap<>(assignment.roomsByFloor);
            this.detailedRoomsByFloor = new HashMap<>();
            this.originalDetailedRoomsByFloor = new HashMap<>();  // ★追加
            this.bathCleaningType = bathType;
            this.constraintType = constraintType != null ? constraintType : "制限なし";
            this.hasMainBuilding = assignment.floors.stream().anyMatch(f -> f <= 10);
            this.hasAnnexBuilding = assignment.floors.stream().anyMatch(f -> f > 10);

            calculatePoints();
        }

        /**
         * ★修正: ポイント計算メソッド（エコ清掃問題を修正）
         */
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
                // ★修正: エコ部屋は別途処理しない（本来の部屋タイプでカウント済み）
                totalRooms += allocation.ecoRooms;  // 部屋数にだけ加算
            }

            // 大浴場清掃のポイント調整
            if (bathCleaningType != AdaptiveRoomOptimizer.BathCleaningType.NONE) {
                totalPoints += bathCleaningType.reduction;
            }

            this.totalPoints = totalPoints;
            this.totalRooms = totalRooms;
            this.adjustedScore = totalPoints;

            // ★追加: 新しい値を計算
            calculateExtendedMetrics();
        }

        /**
         * ★追加: 拡張メトリクスを計算（ツイン数、エコ部屋数、シングル数、換算値）
         * ★修正: ツイン数はエコ部屋を含めない
         */
        private void calculateExtendedMetrics() {
            this.twinRoomCount = 0;
            this.ecoRoomCount = 0;
            this.singleRoomCount = 0;

            // ★修正: detailedRoomsByFloorから集計（ツイン数、エコ部屋数、シングル数）
            for (Map.Entry<Integer, List<FileProcessor.Room>> entry : detailedRoomsByFloor.entrySet()) {
                for (FileProcessor.Room room : entry.getValue()) {
                    if (room.isEco) {
                        // エコ部屋はエコ数にのみカウント
                        this.ecoRoomCount++;
                    } else if (isTwinRoom(room.roomType)) {
                        // エコではないツインのみをツイン数にカウント
                        this.twinRoomCount++;
                    } else {
                        // エコでもツインでもない部屋はシングルとしてカウント
                        this.singleRoomCount++;
                    }
                }
            }

            // 換算値計算
            this.convertedTotalRooms = calculateConvertedTotal();
        }

        /**
         * ★追加: ツイン部屋判定
         */
        private boolean isTwinRoom(String roomType) {
            return "T".equals(roomType) || "NT".equals(roomType) ||
                    "ANT".equals(roomType) || "ADT".equals(roomType);
        }

        /**
         * ★追加: 換算値合計を計算
         * ツイン換算: 2部屋=3換算、3部屋=5換算、4部屋以降=5+(部屋数-3)
         */
        private double calculateConvertedTotal() {
            // ★修正: detailedRoomsByFloorから実際の部屋数を取得
            int actualTotalRooms = 0;
            for (List<FileProcessor.Room> rooms : detailedRoomsByFloor.values()) {
                actualTotalRooms += rooms.size();
            }

            // detailedRoomsByFloorが空の場合はtotalRoomsを使用（フォールバック）
            if (actualTotalRooms == 0) {
                actualTotalRooms = this.totalRooms;
            }

            // ★★★ 換算計算（エコ部屋は0.2換算）★★★
            // 通常部屋数 = 全部屋数 - ツイン部屋数 - エコ部屋数
            int normalRooms = actualTotalRooms - this.twinRoomCount - this.ecoRoomCount;

            // ツイン換算値
            double twinConverted = calculateTwinConversion(this.twinRoomCount);

            // ★エコ部屋は0.2換算（1部屋 = 0.2）
            double ecoConverted = this.ecoRoomCount * 0.2;

            // 合計 = 通常部屋×1.0 + ツイン換算値 + エコ部屋×0.2
            return normalRooms + twinConverted + ecoConverted;
        }


        /**
         * ★追加: ツイン換算計算（NormalRoomDistributionDialogと同じロジック）
         */
        private static double calculateTwinConversion(int twinRooms) {
            if (twinRooms == 0) return 0.0;
            if (twinRooms == 1) return 1.0;
            if (twinRooms == 2) return 3.0;
            if (twinRooms == 3) return 5.0;
            if (twinRooms == 4) return 6.0;
            if (twinRooms == 5) return 8.0;
            if (twinRooms == 6) return 10.0;
            if (twinRooms == 7) return 11.0;
            if (twinRooms == 8) return 12.0;
            // 9部屋以降は+1ずつ増加
            return 12.0 + (twinRooms - 8);
        }

        String getWorkerTypeDisplay() {
            StringBuilder sb = new StringBuilder();

            // 大浴場清掃タイプを表示
            if (bathCleaningType != AdaptiveRoomOptimizer.BathCleaningType.NONE) {
                sb.append(bathCleaningType.displayName);
            } else {
                sb.append("通常");
            }

            // 制約タイプを追加表示（制限なし以外の場合）
            if (constraintType != null && !"制限なし".equals(constraintType)) {
                sb.append("/").append(constraintType);
            }

            return sb.toString();
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

        private String getRoomColor(FileProcessor.Room room) {
            // エコ清掃部屋は青
            if (room.isEco) {
                return "#0000FF";  // 青
            }

            // ツインは黄色（ゴールド系で見やすい色）
            // 本館ツイン: T, NT / 別館ツイン: ANT, ADT
            String type = room.roomType;
            if ("T".equals(type) || "NT".equals(type) || "ANT".equals(type) || "ADT".equals(type)) {
                return "#CC9900";  // 黄色（ゴールド系）
            }

            // シングル等は黒（デフォルト）
            return "#000000";
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

        String getColoredDetailedRoomDisplay() {
            StringBuilder sb = new StringBuilder("<html>");
            List<Integer> sortedFloors = new ArrayList<>(floors);
            Collections.sort(sortedFloors);

            for (int i = 0; i < sortedFloors.size(); i++) {
                if (i > 0) sb.append(" ");

                int floor = sortedFloors.get(i);
                sb.append(getFloorDisplayNameShort(floor)).append("（");

                // 詳細部屋情報がある場合は集計形式で表示
                if (detailedRoomsByFloor.containsKey(floor)) {
                    List<FileProcessor.Room> rooms = detailedRoomsByFloor.get(floor);

                    // 集計: シングル（エコではない非ツイン）、ツイン（エコではないツイン）、エコ
                    int singleCount = 0;
                    int twinCount = 0;
                    int ecoCount = 0;

                    for (FileProcessor.Room room : rooms) {
                        if (room.isEco) {
                            ecoCount++;
                        } else if (isTwinRoom(room.roomType)) {
                            twinCount++;
                        } else {
                            singleCount++;
                        }
                    }

                    List<String> summaryParts = new ArrayList<>();
                    if (singleCount > 0) {
                        summaryParts.add("シングル" + singleCount + "部屋");
                    }
                    if (twinCount > 0) {
                        summaryParts.add("<font color='#CC9900'>ツイン" + twinCount + "部屋</font>");
                    }
                    if (ecoCount > 0) {
                        summaryParts.add("<font color='#0000FF'>エコ" + ecoCount + "部屋</font>");
                    }
                    sb.append(String.join("、", summaryParts));
                } else {
                    // 基本情報のみ（色分けなし）
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
                sb.append("）");
            }

            sb.append("</html>");
            return sb.toString();
        }

        /**
         * 階数を短い形式で表示（2F、別館3F など）
         */
        private String getFloorDisplayNameShort(int floor) {
            if (floor <= 20) {
                return floor + "F";
            } else {
                int annexFloor = floor - 20;
                return "別館" + annexFloor + "F";
            }
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
            // エコ部屋の場合は「エコ」を表示
            String typeDisplay = room.isEco ? room.roomType + "/エコ" : room.roomType;
            return room.roomNumber + " (" + typeDisplay + ") - " + floorName;
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

                // エコ部屋は最優先で青色
                if (item.room.isEco) {
                    setForeground(java.awt.Color.BLUE);
                } else {
                    // 通常部屋は部屋タイプで色分け
                    switch (item.room.roomType) {
                        case "T":
                        case "NT":
                        case "ANT":
                        case "ADT":
                            setForeground(new java.awt.Color(204, 153, 0)); // ゴールド（ツイン）
                            break;
                        case "FD":
                            setForeground(java.awt.Color.RED);
                            break;
                        default:
                            setForeground(java.awt.Color.BLACK); // シングル等
                    }
                }
            }

            return this;
        }
    }

    /**
     * ★追加: グループ境界に罫線を描画するカスタムレンダラー
     */
    class GroupSeparatorCellRenderer extends DefaultTableCellRenderer {
        private final Set<Integer> boundaryRows;
        private final boolean centerAlign;

        public GroupSeparatorCellRenderer(Set<Integer> boundaryRows, boolean centerAlign) {
            this.boundaryRows = boundaryRows;
            this.centerAlign = centerAlign;
            if (centerAlign) {
                setHorizontalAlignment(JLabel.CENTER);
            }
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            // グループ境界の行の場合、上部に太い罫線を描画
            if (boundaryRows.contains(row)) {
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(2, 0, 0, 0, new java.awt.Color(100, 100, 100)),
                        BorderFactory.createEmptyBorder(0, 2, 0, 2)
                ));
            } else {
                setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
            }

            return c;
        }
    }

    /**
     * ★追加: 担当階・部屋詳細列用のHTML対応カスタムレンダラー
     */
    class GroupSeparatorHtmlCellRenderer extends DefaultTableCellRenderer {
        private final Set<Integer> boundaryRows;

        public GroupSeparatorHtmlCellRenderer(Set<Integer> boundaryRows) {
            this.boundaryRows = boundaryRows;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            // グループ境界の行の場合、上部に太い罫線を描画
            if (boundaryRows.contains(row)) {
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(2, 0, 0, 0, new java.awt.Color(100, 100, 100)),
                        BorderFactory.createEmptyBorder(0, 2, 0, 2)
                ));
            } else {
                setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
            }

            return c;
        }
    }

    /**
     * コンストラクタ
     * ★修正: bathCleaningAssignments → bathAssignments に変更
     * ★修正: staff.id → staff.name に変更（bathAssignmentsのキーはスタッフ名）
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
                // ★修正: bathCleaningAssignments → bathAssignments
                // ★修正: staff.id → staff.name（キーがスタッフ名になっている）
                AdaptiveRoomOptimizer.BathCleaningType bathType =
                        result.optimizationResult.config.bathAssignments.getOrDefault(
                                assignment.staff.name,
                                AdaptiveRoomOptimizer.BathCleaningType.NONE);

                // ★追加: 制約タイプを取得
                String constraintType = getConstraintType(assignment.staff.name);

                staffDataMap.put(assignment.staff.name, new StaffData(assignment, bathType, constraintType));
            }
        }

        if (roomAssigner != null) {
            assignRoomNumbers();
        }

        initializeGUI();
        loadAssignmentData();
    }

    /**
     * ★追加: スタッフの制約タイプを取得
     */
    private String getConstraintType(String staffName) {
        if (processingResult.optimizationResult != null &&
                processingResult.optimizationResult.config != null &&
                processingResult.optimizationResult.config.pointConstraints != null) {

            AdaptiveRoomOptimizer.PointConstraint constraint =
                    processingResult.optimizationResult.config.pointConstraints.get(staffName);

            if (constraint != null) {
                return constraint.constraintType;
            }
        }
        return "制限なし";
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
     * ★修正: GUI初期化（列名変更）
     */
    protected void initializeGUI() {
        setTitle("清掃割り当て調整 - " +
                (processingResult.optimizationResult != null ?
                        processingResult.optimizationResult.targetDate.format(
                                DateTimeFormatter.ofPattern("yyyy年MM月dd日")) : ""));
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        JPanel mainPanel = new JPanel(new BorderLayout());

        // ★修正: 列名変更（部屋数→シングル数、ポイント→内ツイン数、調整後スコア→内エコ部屋数、総部屋数追加）
        String[] columnNames = {
                "スタッフ名", "作業者タイプ", "シングル数", "内ツイン数", "内エコ部屋数", "総部屋数", "担当階・部屋詳細"
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

        // ★修正: グループ境界罫線対応のカスタムレンダラーを使用
        // 中央揃えの列用レンダラー
        GroupSeparatorCellRenderer centerRenderer = new GroupSeparatorCellRenderer(groupBoundaryRows, true);
        // 左揃えの列用レンダラー
        GroupSeparatorCellRenderer leftRenderer = new GroupSeparatorCellRenderer(groupBoundaryRows, false);
        // HTML対応の詳細列用レンダラー
        GroupSeparatorHtmlCellRenderer htmlRenderer = new GroupSeparatorHtmlCellRenderer(groupBoundaryRows);

        // ★修正: 列インデックス調整（各列にカスタムレンダラーを適用）
        assignmentTable.getColumnModel().getColumn(0).setCellRenderer(leftRenderer);   // スタッフ名
        for (int i = 1; i <= 5; i++) {
            assignmentTable.getColumnModel().getColumn(i).setCellRenderer(centerRenderer);
        }
        assignmentTable.getColumnModel().getColumn(6).setCellRenderer(htmlRenderer);   // 担当階・部屋詳細

        assignmentTable.getColumnModel().getColumn(0).setPreferredWidth(100);
        assignmentTable.getColumnModel().getColumn(1).setPreferredWidth(130);  // 作業者タイプ列を拡張
        assignmentTable.getColumnModel().getColumn(2).setPreferredWidth(60);
        assignmentTable.getColumnModel().getColumn(3).setPreferredWidth(80);
        assignmentTable.getColumnModel().getColumn(4).setPreferredWidth(90);
        assignmentTable.getColumnModel().getColumn(5).setPreferredWidth(80);
        assignmentTable.getColumnModel().getColumn(6).setPreferredWidth(500);

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
     * ★修正: availableStaff → staff に変更
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
                // ★修正: availableStaff → staff
                actualStaffCount = processingResult.optimizationResult.config.staff.size();
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
     *
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
     * 部屋番号から階数を抽出
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
                // 最初の1～2桁を階数として取得
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
     * 修正版ボタンパネル作成（残し部屋設定ボタン追加）
     */
    private JPanel createNewButtonPanel() {
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));

        // ★新規: 未割り当てボタン（スタッフ入れ替えの左隣）
        JButton unassignedRoomsButton = new JButton("未割り当て");
        unassignedRoomsButton.setFont(new java.awt.Font("MS Gothic", java.awt.Font.BOLD, 12));
        unassignedRoomsButton.setBackground(new java.awt.Color(255, 150, 150));
        unassignedRoomsButton.addActionListener(e -> showUnassignedRoomsDialog());
        buttonPanel.add(unassignedRoomsButton);

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
     * ★修正: 残し部屋選択ダイアログを表示（元データを使用）
     */
    private void showExcludedRoomsDialog() {
        // ★修正: 元データ使用フラグ(true)を追加
        ExcludedRoomSelectionDialog dialog = new ExcludedRoomSelectionDialog(
                this, staffDataMap, excludedRooms, true);
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
     * 残し部屋設定の適用
     * ★修正: 元データから再構築することで、設定の解除も可能に
     */
    private void applyExcludedRooms(Set<String> newExcludedRooms) {
        this.excludedRooms = new HashSet<>(newExcludedRooms);

        // 各スタッフの部屋データを元データから再構築
        for (StaffData staff : staffDataMap.values()) {
            // 元データがない場合はスキップ
            if (staff.originalDetailedRoomsByFloor == null || staff.originalDetailedRoomsByFloor.isEmpty()) {
                continue;
            }

            Map<Integer, List<FileProcessor.Room>> updatedRooms = new HashMap<>();

            // 元データから残し部屋を除外してフィルタリング
            for (Map.Entry<Integer, List<FileProcessor.Room>> entry : staff.originalDetailedRoomsByFloor.entrySet()) {
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

                // ★追加: 元データを保存（初回のみ）
                if (staffData.originalDetailedRoomsByFloor == null || staffData.originalDetailedRoomsByFloor.isEmpty()) {
                    staffData.originalDetailedRoomsByFloor = deepCopyRoomsByFloor(byFloor);
                }

                // ★追加: 拡張メトリクスを再計算
                staffData.calculateExtendedMetrics();
            }
        }
    }

    /**
     * ★追加: 部屋データのディープコピーを作成
     */
    private Map<Integer, List<FileProcessor.Room>> deepCopyRoomsByFloor(Map<Integer, List<FileProcessor.Room>> original) {
        Map<Integer, List<FileProcessor.Room>> copy = new HashMap<>();
        for (Map.Entry<Integer, List<FileProcessor.Room>> entry : original.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
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
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.insets = new Insets(0, 0, 20, 0);
        JLabel titleLabel = new JLabel("担当部屋を完全に入れ替えるスタッフを選択してください", JLabel.CENTER);
        titleLabel.setFont(new java.awt.Font("MS Gothic", java.awt.Font.BOLD, 14));
        mainPanel.add(titleLabel, gbc);

        // スタッフ選択コンボボックス
        String[] staffNames = staffDataMap.keySet().toArray(new String[0]);
        Arrays.sort(staffNames);

        gbc.gridwidth = 1;
        gbc.insets = new Insets(5, 5, 5, 5);

        // スタッフA
        gbc.gridx = 0;
        gbc.gridy = 1;
        mainPanel.add(new JLabel("スタッフA:"), gbc);

        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JComboBox<String> staffACombo = new JComboBox<>(staffNames);
        mainPanel.add(staffACombo, gbc);

        // スタッフB
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.fill = GridBagConstraints.NONE;
        mainPanel.add(new JLabel("スタッフB:"), gbc);

        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
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

    /**
     * detailedRoomsByFloorからroomsByFloorを再構築
     * エコ清掃部屋も本来の部屋タイプでポイント計算する
     */
    private void rebuildRoomsByFloor(StaffData staff) {
        staff.roomsByFloor.clear();

        for (Map.Entry<Integer, List<FileProcessor.Room>> entry : staff.detailedRoomsByFloor.entrySet()) {
            int floor = entry.getKey();
            List<FileProcessor.Room> rooms = entry.getValue();

            Map<String, Integer> roomCounts = new HashMap<>();
            int ecoRoomCount = 0;

            for (FileProcessor.Room room : rooms) {
                // 部屋タイプでカウント
                roomCounts.merge(room.roomType, 1, Integer::sum);
                // ★追加: エコ部屋をカウント
                if (room.isEco) {
                    ecoRoomCount++;
                }
            }

            // ★修正: エコ部屋数を正しく設定
            staff.roomsByFloor.put(floor, new AdaptiveRoomOptimizer.RoomAllocation(roomCounts, ecoRoomCount));
        }

        staff.floors = new ArrayList<>(staff.roomsByFloor.keySet());
        Collections.sort(staff.floors);
    }
    /**
     * ポイント再計算メソッド
     */
    private void recalculateStaffPoints(StaffData staff) {
        // まずroomsByFloorを再構築
        rebuildRoomsByFloor(staff);

        int totalRooms = 0;

        // detailedRoomsByFloorから部屋数をカウント（エコ部屋は除外）
        if (!staff.detailedRoomsByFloor.isEmpty()) {
            for (List<FileProcessor.Room> rooms : staff.detailedRoomsByFloor.values()) {
                for (FileProcessor.Room room : rooms) {
                    if (!room.isEco) {
                        totalRooms++;
                    }
                }
            }
        } else {
            // フォールバック: roomsByFloorから計算
            for (AdaptiveRoomOptimizer.RoomAllocation allocation : staff.roomsByFloor.values()) {
                for (int count : allocation.roomCounts.values()) {
                    totalRooms += count;
                }
                // エコ部屋は含めない
            }
        }

        // 大浴場清掃の調整（部屋数換算で調整）
        double adjustment = 0;
        if (staff.bathCleaningType != AdaptiveRoomOptimizer.BathCleaningType.NONE) {
            adjustment = staff.bathCleaningType.reduction;
        }

        staff.totalRooms = totalRooms;
        staff.totalPoints = totalRooms + adjustment;  // 互換性のため
        staff.adjustedScore = totalRooms + adjustment;

        // 拡張メトリクスを再計算（ツイン数、エコ数、換算値）
        staff.calculateExtendedMetrics();
    }

    private String mapRoomTypeForPoints(String roomType) {
        switch (roomType.toUpperCase()) {
            case "S": case "NS": case "ANS": case "ABF": case "AKS":
                return "S";
            case "D": case "ND": case "AND":
                return "D";
            case "T": case "NT": case "ANT": case "ADT":
                return "T";
            case "FD": case "NFD":
                return "FD";
            default:
                return roomType;
        }
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
                    statusLabel.setText(String.format("部屋交換完了: %s ⇔ %s",
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

    /**
     * ★修正: テーブルデータ読み込み（新しい列に対応、グループ境界計算追加）
     */
    protected void loadAssignmentData() {
        tableModel.setRowCount(0);
        groupBoundaryRows.clear();

        List<StaffData> sortedStaff = getSortedStaffList();

        int previousGroupKey = -1;
        int rowIndex = 0;

        for (StaffData staff : sortedStaff) {
            // グループ境界の検出（大浴場清掃と制約優先度の組み合わせで判定）
            int currentGroupKey = calculateGroupKey(staff);
            if (previousGroupKey != -1 && currentGroupKey != previousGroupKey) {
                groupBoundaryRows.add(rowIndex);
            }
            previousGroupKey = currentGroupKey;

            Object[] row = {
                    staff.name,
                    staff.getWorkerTypeDisplay(),
                    staff.singleRoomCount,
                    staff.twinRoomCount,
                    staff.ecoRoomCount,
                    String.format("%.1f", staff.convertedTotalRooms),
                    staff.getColoredDetailedRoomDisplay()  // ★ここが変更点
            };
            tableModel.addRow(row);
            rowIndex++;
        }

        // テーブルの再描画を要求
        assignmentTable.repaint();
    }

    /**
     * ★追加: グループキーを計算（大浴場清掃タイプと制約優先度の組み合わせ）
     */
    private int calculateGroupKey(StaffData staff) {
        // 大浴場清掃は別グループ（0-9）
        if (staff.bathCleaningType != AdaptiveRoomOptimizer.BathCleaningType.NONE) {
            return 0; // 大浴場清掃グループ
        }
        // 通常スタッフは制約優先度でグループ分け（10以上）
        return 10 + getConstraintPriority(staff);
    }

    /**
     * ★修正: ソート済みスタッフリストを取得
     * 表示順序: 大浴場清掃 → 本館制限 → 本館 → 両方 → 別館制限 → 別館 → 業者制限
     */
    private List<StaffData> getSortedStaffList() {
        List<StaffData> sortedStaff = new ArrayList<>(staffDataMap.values());

        sortedStaff.sort((s1, s2) -> {
            // 1. 大浴場清掃が最優先
            if (s1.bathCleaningType != AdaptiveRoomOptimizer.BathCleaningType.NONE &&
                    s2.bathCleaningType == AdaptiveRoomOptimizer.BathCleaningType.NONE) {
                return -1;
            }
            if (s1.bathCleaningType == AdaptiveRoomOptimizer.BathCleaningType.NONE &&
                    s2.bathCleaningType != AdaptiveRoomOptimizer.BathCleaningType.NONE) {
                return 1;
            }

            // 2. 制約タイプによる優先度
            int constraintPriority1 = getConstraintPriority(s1);
            int constraintPriority2 = getConstraintPriority(s2);

            if (constraintPriority1 != constraintPriority2) {
                return Integer.compare(constraintPriority1, constraintPriority2);
            }

            // 3. 建物分類による優先度
            int buildingPriority1 = getBuildingPriority(s1);
            int buildingPriority2 = getBuildingPriority(s2);

            if (buildingPriority1 != buildingPriority2) {
                return Integer.compare(buildingPriority1, buildingPriority2);
            }

            // 4. 同じ建物分類内では担当最小階数で比較
            int minFloor1 = getRepresentativeFloor(s1);
            int minFloor2 = getRepresentativeFloor(s2);

            if (minFloor1 != minFloor2) {
                return Integer.compare(minFloor1, minFloor2);
            }

            // 5. 階数も同じ場合はスタッフ名で比較
            return s1.name.compareTo(s2.name);
        });

        return sortedStaff;
    }

    private int getConstraintPriority(StaffData staff) {
        boolean isRestricted = !"制限なし".equals(staff.constraintType);
        boolean isVendor = "業者制限".equals(staff.constraintType);

        // 業者制限は最後
        if (isVendor) {
            return 6;
        }

        // 建物による分類
        if (staff.hasMainBuilding && !staff.hasAnnexBuilding) {
            // 本館のみ
            return isRestricted ? 1 : 2;
        } else if (staff.hasMainBuilding && staff.hasAnnexBuilding) {
            // 両方
            return 3;
        } else if (!staff.hasMainBuilding && staff.hasAnnexBuilding) {
            // 別館のみ
            return isRestricted ? 4 : 5;
        }

        return 99; // その他
    }

    /**
     * @param staff スタッフデータ
     * @return 1:本館専任, 2:館跨ぎ, 3:別館専任
     */
    private int getBuildingPriority(StaffData staff) {
        if (staff.hasMainBuilding && !staff.hasAnnexBuilding) {
            return 1; // 本館専任
        } else if (staff.hasMainBuilding && staff.hasAnnexBuilding) {
            return 2; // 館跨ぎ
        } else if (!staff.hasMainBuilding && staff.hasAnnexBuilding) {
            return 3; // 別館専任
        } else {
            return 4; // 未分類（通常は発生しない）
        }
    }

    /**
     * ★変更不要: 代表階数を取得（ソート用）
     *
     * @param staff スタッフデータ
     * @return ソート用の代表階数
     */
    private int getRepresentativeFloor(StaffData staff) {
        if (staff.floors.isEmpty()) {
            return Integer.MAX_VALUE;
        }

        int buildingPriority = getBuildingPriority(staff);

        switch (buildingPriority) {
            case 1: // 本館専任
                return getMinFloorInRange(staff.floors, 1, 10);

            case 3: // 別館専任
                return getMinFloorInRange(staff.floors, 11, Integer.MAX_VALUE);

            case 2: // 館跨ぎ
                // 館跨ぎの場合は本館の最小階を優先
                int mainMin = getMinFloorInRange(staff.floors, 1, 10);
                if (mainMin != Integer.MAX_VALUE) {
                    return mainMin;
                }
                return getMinFloorInRange(staff.floors, 11, Integer.MAX_VALUE);

            default:
                return staff.floors.stream().mapToInt(Integer::intValue).min().orElse(Integer.MAX_VALUE);
        }
    }

    /**
     * ★変更不要: 指定範囲内の最小階数を取得
     */
    private int getMinFloorInRange(List<Integer> floors, int minRange, int maxRange) {
        OptionalInt result = floors.stream()
                .filter(floor -> floor >= minRange && floor <= maxRange)
                .mapToInt(Integer::intValue)
                .min();

        return result.orElse(Integer.MAX_VALUE);
    }

    // ★変更不要：既存のまま
    private void refreshTable() {
        loadAssignmentData();  // 修正済みのloadAssignmentDataが呼ばれる

        remove(summaryPanel);
        summaryPanel = createSummaryPanel();
        add(summaryPanel, BorderLayout.NORTH);
        revalidate();
        repaint();
    }

    // ★変更不要：既存のまま
    private void exportToExcel() {
        // フォルダ選択ダイアログ
        JFileChooser folderChooser = new JFileChooser();
        folderChooser.setDialogTitle("出力先フォルダを選択");
        folderChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        folderChooser.setAcceptAllFileFilterUsed(false);

        if (folderChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                String folderPath = folderChooser.getSelectedFile().getAbsolutePath();

                // ファイル名を生成（日付付き）
                String dateStr = "";
                if (processingResult.optimizationResult != null &&
                        processingResult.optimizationResult.targetDate != null) {
                    dateStr = processingResult.optimizationResult.targetDate
                            .format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                } else {
                    dateStr = java.time.LocalDate.now()
                            .format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                }

                String fileName = "本日清掃_" + dateStr + ".xlsx";
                String filePath = folderPath + java.io.File.separator + fileName;

                // ファイルが既に存在する場合は確認
                java.io.File outputFile = new java.io.File(filePath);
                if (outputFile.exists()) {
                    int result = JOptionPane.showConfirmDialog(this,
                            "ファイルが既に存在します。上書きしますか？\n" + fileName,
                            "確認", JOptionPane.YES_NO_OPTION);
                    if (result != JOptionPane.YES_OPTION) {
                        return;
                    }
                }

                createCleaningExcelFile(filePath);
                statusLabel.setText("Excelファイルを作成しました: " + filePath);

                JOptionPane.showMessageDialog(this,
                        "ファイルを作成しました:\n" + filePath,
                        "出力完了", JOptionPane.INFORMATION_MESSAGE);

            } catch (IOException e) {
                JOptionPane.showMessageDialog(this,
                        "ファイル出力中にエラーが発生しました: " + e.getMessage(),
                        "エラー", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void createCleaningExcelFile(String filePath) throws IOException {
        final String SHEET_NAME = "本日清掃";

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(SHEET_NAME);

            // ソート済みスタッフリストを取得
            List<StaffData> sortedStaff = getSortedStaffList();

            int currentRow = 0;  // 現在の行（0始まり）

            for (StaffData staff : sortedStaff) {
                // 担当者が変わる場合、次のセット境界に移動
                if (!isAtSetBoundary(currentRow)) {
                    currentRow = getNextSetBoundary(currentRow);
                }

                // 部屋を通常清掃とEcoに分類
                List<FileProcessor.Room> normalRooms = new ArrayList<>();
                List<FileProcessor.Room> ecoRooms = new ArrayList<>();

                if (staff.detailedRoomsByFloor != null) {
                    for (List<FileProcessor.Room> rooms : staff.detailedRoomsByFloor.values()) {
                        for (FileProcessor.Room room : rooms) {
                            if (room.isEcoClean) {
                                ecoRooms.add(room);
                            } else {
                                normalRooms.add(room);
                            }
                        }
                    }
                }

                // 部屋番号順にソート
                normalRooms.sort(Comparator.comparing(r -> r.roomNumber));
                ecoRooms.sort(Comparator.comparing(r -> r.roomNumber));

                // 通常清掃部屋を出力
                for (FileProcessor.Room room : normalRooms) {
                    Row row = sheet.createRow(currentRow);
                    writeRoomData(row, staff.name, room);
                    currentRow++;
                }

                // Eco部屋がある場合、1行空けて出力
                if (!ecoRooms.isEmpty()) {
                    if (!normalRooms.isEmpty()) {
                        currentRow++;  // 空行
                    }
                    for (FileProcessor.Room room : ecoRooms) {
                        Row row = sheet.createRow(currentRow);
                        writeRoomData(row, staff.name, room);
                        currentRow++;
                    }
                }
            }

            // 列幅を調整
            sheet.setColumnWidth(0, 12 * 256);  // 担当: 12文字幅
            sheet.setColumnWidth(1, 8 * 256);   // 部屋: 8文字幅
            sheet.setColumnWidth(2, 6 * 256);   // 連泊: 6文字幅
            sheet.setColumnWidth(3, 6 * 256);   // エコ: 6文字幅

            // ファイルに保存
            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                workbook.write(fos);
            }
        }
    }


    /**
     * シートの既存データをクリア
     */
    private void clearSheetData(Sheet sheet) {
        int lastRow = sheet.getLastRowNum();
        for (int i = lastRow; i >= 0; i--) {
            Row row = sheet.getRow(i);
            if (row != null) {
                sheet.removeRow(row);
            }
        }
    }

    /**
     * 行を取得（なければ作成）
     */
    private Row getOrCreateRow(Sheet sheet, int rowIndex) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) {
            row = sheet.createRow(rowIndex);
        }
        return row;
    }


    private void writeRoomData(Row row, String staffName, FileProcessor.Room room) {
        // A列: 担当
        Cell cellStaff = row.createCell(0);
        cellStaff.setCellValue(staffName);

        // B列: 部屋番号
        Cell cellRoom = row.createCell(1);
        cellRoom.setCellValue(room.roomNumber);

        // C列: 連泊（状態が"3"なら連泊）
        Cell cellStay = row.createCell(2);
        if ("3".equals(room.roomStatus)) {
            cellStay.setCellValue("連泊");
        }

        // D列: エコ
        Cell cellEco = row.createCell(3);
        if (room.isEcoClean) {
            cellEco.setCellValue("エコ");
        }
    }

    private boolean isAtSetBoundary(int row) {
        // セット1-4の境界（0, 14, 28, 42）
        if (row < 56) {
            return row % 14 == 0;
        }
        // セット5以降の境界（56, 72, 88, ...）
        return (row - 56) % 16 == 0;
    }

    private int getNextSetBoundary(int currentRow) {
        // セット1-4の範囲内（行0-55）
        if (currentRow < 56) {
            int nextBoundary = ((currentRow / 14) + 1) * 14;
            // 56を超えない
            return Math.min(nextBoundary, 56);
        }
        // セット5以降
        int rowInLargeSets = currentRow - 56;
        return 56 + ((rowInLargeSets / 16) + 1) * 16;
    }

    // ★変更不要：既存のまま
    private void finishEditing() {
        int result = JOptionPane.showConfirmDialog(this,
                "編集を完了してよろしいですか？", "確認",
                JOptionPane.YES_NO_OPTION);

        if (result == JOptionPane.YES_OPTION) {
            dispose();
        }
    }
    /**
     * ★新規: 未割り当て部屋を取得
     */
    private List<FileProcessor.Room> getUnassignedRooms() {
        List<FileProcessor.Room> unassignedRooms = new ArrayList<>();

        if (processingResult.cleaningDataObj == null) {
            return unassignedRooms;
        }

        // 全清掃対象部屋を取得
        List<FileProcessor.Room> allRooms = new ArrayList<>(processingResult.cleaningDataObj.roomsToClean);

        // 割り当て済み部屋番号を収集
        Set<String> assignedRoomNumbers = new HashSet<>();
        for (StaffData staff : staffDataMap.values()) {
            if (staff.detailedRoomsByFloor != null) {
                for (List<FileProcessor.Room> rooms : staff.detailedRoomsByFloor.values()) {
                    for (FileProcessor.Room room : rooms) {
                        assignedRoomNumbers.add(room.roomNumber);
                    }
                }
            }
        }

        // 残し部屋も除外
        assignedRoomNumbers.addAll(excludedRooms);

        // 未割り当て部屋を特定
        for (FileProcessor.Room room : allRooms) {
            if (!assignedRoomNumbers.contains(room.roomNumber)) {
                unassignedRooms.add(room);
            }
        }

        // 部屋番号でソート
        unassignedRooms.sort(Comparator.comparing(r -> r.roomNumber));

        return unassignedRooms;
    }

    /**
     * ★新規: 未割り当て部屋ダイアログを表示
     */
    private void showUnassignedRoomsDialog() {
        List<FileProcessor.Room> unassignedRooms = getUnassignedRooms();

        if (unassignedRooms.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "未割り当ての部屋はありません。\nすべての部屋が割り当て済みです。",
                    "未割り当て部屋", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JDialog dialog = new JDialog(this, "未割り当て部屋", true);
        dialog.setLayout(new BorderLayout());

        // 上部パネル
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JLabel headerLabel = new JLabel(String.format(
                "未割り当て部屋: %d室 （チェックを入れてスタッフに割り当てできます）",
                unassignedRooms.size()));
        headerLabel.setFont(new java.awt.Font("MS Gothic", java.awt.Font.BOLD, 14));
        headerPanel.add(headerLabel, BorderLayout.CENTER);
        dialog.add(headerPanel, BorderLayout.NORTH);

        // 中央パネル
        JPanel listPanel = new JPanel(new BorderLayout());
        listPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        String[] columnNames = {"選択", "部屋番号", "部屋タイプ", "建物", "階", "状態", "エコ清掃"};
        DefaultTableModel unassignedTableModel = new DefaultTableModel(columnNames, 0) {
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

        for (FileProcessor.Room room : unassignedRooms) {
            Object[] rowData = {
                    false,
                    room.roomNumber,
                    room.roomType,
                    room.building,
                    room.floor + "階",
                    room.getStatusDisplay(),
                    room.isEcoClean ? "エコ" : ""
            };
            unassignedTableModel.addRow(rowData);
        }

        JTable unassignedTable = new JTable(unassignedTableModel);
        unassignedTable.setRowHeight(25);
        unassignedTable.getColumnModel().getColumn(0).setPreferredWidth(50);
        unassignedTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        unassignedTable.getColumnModel().getColumn(2).setPreferredWidth(80);
        unassignedTable.getColumnModel().getColumn(3).setPreferredWidth(60);
        unassignedTable.getColumnModel().getColumn(4).setPreferredWidth(60);
        unassignedTable.getColumnModel().getColumn(5).setPreferredWidth(100);
        unassignedTable.getColumnModel().getColumn(6).setPreferredWidth(70);

        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(JLabel.CENTER);
        for (int i = 1; i < columnNames.length; i++) {
            unassignedTable.getColumnModel().getColumn(i).setCellRenderer(centerRenderer);
        }

        JScrollPane scrollPane = new JScrollPane(unassignedTable);
        scrollPane.setPreferredSize(new Dimension(600, 300));
        listPanel.add(scrollPane, BorderLayout.CENTER);

        // 全選択/全解除
        JPanel selectPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton selectAllButton = new JButton("全選択");
        selectAllButton.addActionListener(e -> {
            for (int i = 0; i < unassignedTableModel.getRowCount(); i++) {
                unassignedTableModel.setValueAt(true, i, 0);
            }
        });
        JButton deselectAllButton = new JButton("全解除");
        deselectAllButton.addActionListener(e -> {
            for (int i = 0; i < unassignedTableModel.getRowCount(); i++) {
                unassignedTableModel.setValueAt(false, i, 0);
            }
        });
        selectPanel.add(selectAllButton);
        selectPanel.add(deselectAllButton);
        listPanel.add(selectPanel, BorderLayout.SOUTH);

        dialog.add(listPanel, BorderLayout.CENTER);

        // 下部パネル
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel staffLabel = new JLabel("割り当て先スタッフ:");
        staffLabel.setFont(new java.awt.Font("MS Gothic", java.awt.Font.PLAIN, 12));
        bottomPanel.add(staffLabel);

        String[] staffNames = staffDataMap.keySet().toArray(new String[0]);
        Arrays.sort(staffNames);
        JComboBox<String> staffCombo = new JComboBox<>(staffNames);
        staffCombo.setFont(new java.awt.Font("MS Gothic", java.awt.Font.PLAIN, 12));
        bottomPanel.add(staffCombo);

        JButton assignButton = new JButton("選択部屋を割り当て");
        assignButton.setFont(new java.awt.Font("MS Gothic", java.awt.Font.BOLD, 12));
        assignButton.setBackground(new java.awt.Color(100, 200, 100));
        assignButton.addActionListener(e -> {
            String selectedStaff = (String) staffCombo.getSelectedItem();
            if (selectedStaff == null) {
                JOptionPane.showMessageDialog(dialog,
                        "スタッフを選択してください", "エラー", JOptionPane.WARNING_MESSAGE);
                return;
            }

            List<FileProcessor.Room> selectedRooms = new ArrayList<>();
            for (int i = 0; i < unassignedTableModel.getRowCount(); i++) {
                Boolean selected = (Boolean) unassignedTableModel.getValueAt(i, 0);
                if (selected != null && selected) {
                    String roomNumber = (String) unassignedTableModel.getValueAt(i, 1);
                    for (FileProcessor.Room room : unassignedRooms) {
                        if (room.roomNumber.equals(roomNumber)) {
                            selectedRooms.add(room);
                            break;
                        }
                    }
                }
            }

            if (selectedRooms.isEmpty()) {
                JOptionPane.showMessageDialog(dialog,
                        "割り当てる部屋を選択してください", "エラー", JOptionPane.WARNING_MESSAGE);
                return;
            }

            int confirm = JOptionPane.showConfirmDialog(dialog,
                    String.format("%d室を「%s」に割り当てますか？", selectedRooms.size(), selectedStaff),
                    "確認", JOptionPane.YES_NO_OPTION);

            if (confirm == JOptionPane.YES_OPTION) {
                assignRoomsToStaff(selectedStaff, selectedRooms);
                refreshTable();
                dialog.dispose();
                statusLabel.setText(String.format("%d室を「%s」に割り当てました",
                        selectedRooms.size(), selectedStaff));
            }
        });
        bottomPanel.add(assignButton);

        JButton closeButton = new JButton("閉じる");
        closeButton.addActionListener(e -> dialog.dispose());
        bottomPanel.add(closeButton);

        dialog.add(bottomPanel, BorderLayout.SOUTH);

        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    /**
     * ★新規: 部屋をスタッフに割り当て
     */
    private void assignRoomsToStaff(String staffName, List<FileProcessor.Room> rooms) {
        StaffData staffData = staffDataMap.get(staffName);
        if (staffData == null) {
            JOptionPane.showMessageDialog(this,
                    "スタッフデータが見つかりません: " + staffName,
                    "エラー", JOptionPane.ERROR_MESSAGE);
            return;
        }

        for (FileProcessor.Room room : rooms) {
            int floor = room.floor;

            if (staffData.detailedRoomsByFloor == null) {
                staffData.detailedRoomsByFloor = new HashMap<>();
            }

            List<FileProcessor.Room> floorRooms = staffData.detailedRoomsByFloor
                    .computeIfAbsent(floor, k -> new ArrayList<>());

            boolean alreadyExists = floorRooms.stream()
                    .anyMatch(r -> r.roomNumber.equals(room.roomNumber));
            if (!alreadyExists) {
                floorRooms.add(room);
            }

            if (!staffData.floors.contains(floor)) {
                staffData.floors.add(floor);
                Collections.sort(staffData.floors);
            }
        }

        staffData.calculateExtendedMetrics();
        recalculateStaffPoints(staffData);
    }
}