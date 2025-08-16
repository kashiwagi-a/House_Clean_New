package org.example;

import javax.swing.*;
import javax.swing.table.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 清掃割り当て編集GUI
 * 割り当て結果を視覚的に編集できるインターフェース
 */
public class AssignmentEditorGUI extends JFrame {

    private AdaptiveRoomOptimizer.OptimizationResult originalResult;
    private List<EditableAssignment> editableAssignments;
    private JTable assignmentTable;
    private AssignmentTableModel tableModel;
    private JLabel statusLabel;
    private JTextArea detailArea;
    private Map<String, Color> staffColors;

    // 部屋移動用のコンポーネント
    private JComboBox<String> fromStaffCombo;
    private JComboBox<String> toStaffCombo;
    private JComboBox<RoomInfo> roomCombo;
    private JButton transferButton;

    /**
     * 編集可能な割り当て情報
     */
    private static class EditableAssignment {
        String staffName;
        String staffId;
        AdaptiveRoomOptimizer.WorkerType workerType;
        Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> roomsByFloor;
        double basePoints;
        double movementPenalty;
        double totalScore;
        int totalRooms;
        List<Integer> floors;

        public EditableAssignment(AdaptiveRoomOptimizer.StaffAssignment original) {
            this.staffName = original.staff.name;
            this.staffId = original.staff.id;
            this.workerType = original.workerType;
            this.roomsByFloor = new HashMap<>(original.roomsByFloor);
            this.floors = new ArrayList<>(original.floors);
            recalculate();
        }

        public void recalculate() {
            // 部屋数の再計算
            this.totalRooms = roomsByFloor.values().stream()
                    .mapToInt(AdaptiveRoomOptimizer.RoomAllocation::getTotalRooms)
                    .sum();

            // ポイントの再計算
            this.basePoints = roomsByFloor.values().stream()
                    .mapToDouble(AdaptiveRoomOptimizer.RoomAllocation::getTotalPoints)
                    .sum();

            // 移動ペナルティの再計算
            this.movementPenalty = 0;
            if (floors.size() > 1) {
                this.movementPenalty += AdaptiveRoomOptimizer.FLOOR_CROSSING_PENALTY * (floors.size() - 1);
            }

            // 館跨ぎペナルティ
            boolean hasMainBuilding = floors.stream().anyMatch(f -> f <= 10);
            boolean hasAnnexBuilding = floors.stream().anyMatch(f -> f > 10);
            if (hasMainBuilding && hasAnnexBuilding) {
                this.movementPenalty += AdaptiveRoomOptimizer.BUILDING_CROSSING_PENALTY;
            }

            this.totalScore = this.basePoints + this.movementPenalty;
        }

        public String getFloorsString() {
            return floors.stream()
                    .sorted()
                    .map(String::valueOf)
                    .collect(Collectors.joining(", "));
        }

        public String getRoomDetails() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<Integer, AdaptiveRoomOptimizer.RoomAllocation> entry : roomsByFloor.entrySet()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(entry.getKey()).append("階:");

                AdaptiveRoomOptimizer.RoomAllocation alloc = entry.getValue();
                List<String> details = new ArrayList<>();

                for (Map.Entry<String, Integer> room : alloc.roomCounts.entrySet()) {
                    if (room.getValue() > 0) {
                        details.add(room.getKey() + ":" + room.getValue());
                    }
                }
                if (alloc.ecoRooms > 0) {
                    details.add("エコ:" + alloc.ecoRooms);
                }

                sb.append(String.join(" ", details));
            }
            return sb.toString();
        }
    }

    /**
     * 部屋情報クラス
     */
    private static class RoomInfo {
        int floor;
        String type;
        int count;
        String fromStaff;

        public RoomInfo(int floor, String type, int count, String fromStaff) {
            this.floor = floor;
            this.type = type;
            this.count = count;
            this.fromStaff = fromStaff;
        }

        @Override
        public String toString() {
            return String.format("%d階 %s %d室", floor, type, count);
        }
    }

    /**
     * テーブルモデル
     */
    private class AssignmentTableModel extends AbstractTableModel {
        private final String[] columnNames = {
                "スタッフ", "タイプ", "部屋数", "基本点", "移動点", "合計点", "階", "詳細"
        };

        @Override
        public int getRowCount() {
            return editableAssignments.size();
        }

        @Override
        public int getColumnCount() {
            return columnNames.length;
        }

        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        @Override
        public Object getValueAt(int row, int col) {
            EditableAssignment assignment = editableAssignments.get(row);
            switch (col) {
                case 0: return assignment.staffName;
                case 1: return assignment.workerType.displayName;
                case 2: return assignment.totalRooms;
                case 3: return String.format("%.1f", assignment.basePoints);
                case 4: return String.format("%.1f", assignment.movementPenalty);
                case 5: return String.format("%.1f", assignment.totalScore);
                case 6: return assignment.getFloorsString();
                case 7: return assignment.getRoomDetails();
                default: return "";
            }
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            if (columnIndex == 2) return Integer.class;
            if (columnIndex >= 3 && columnIndex <= 5) return Double.class;
            return String.class;
        }

        public void refresh() {
            fireTableDataChanged();
        }
    }

    /**
     * コンストラクタ
     */
    public AssignmentEditorGUI(AdaptiveRoomOptimizer.OptimizationResult result) {
        super("清掃割り当て編集ツール");
        this.originalResult = result;
        this.editableAssignments = new ArrayList<>();
        this.staffColors = new HashMap<>();

        // 編集可能な形式に変換
        for (AdaptiveRoomOptimizer.StaffAssignment assignment : result.assignments) {
            editableAssignments.add(new EditableAssignment(assignment));
        }

        // スタッフごとに色を割り当て
        assignStaffColors();

        // GUI初期化
        initializeGUI();
        updateStatistics();
    }

    /**
     * スタッフに色を割り当て
     */
    private void assignStaffColors() {
        Color[] colors = {
                new Color(255, 200, 200), // 薄い赤
                new Color(200, 255, 200), // 薄い緑
                new Color(200, 200, 255), // 薄い青
                new Color(255, 255, 200), // 薄い黄
                new Color(255, 200, 255), // 薄い紫
                new Color(200, 255, 255), // 薄いシアン
                new Color(255, 230, 200), // 薄いオレンジ
                new Color(230, 200, 255), // 薄い藤色
        };

        int colorIndex = 0;
        for (EditableAssignment assignment : editableAssignments) {
            staffColors.put(assignment.staffName, colors[colorIndex % colors.length]);
            colorIndex++;
        }
    }

    /**
     * GUI初期化
     */
    private void initializeGUI() {
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        // メインパネル
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setDividerLocation(400);

        // 上部：テーブル
        JPanel tablePanel = createTablePanel();
        splitPane.setTopComponent(tablePanel);

        // 下部：編集パネル
        JPanel editPanel = createEditPanel();
        splitPane.setBottomComponent(editPanel);

        add(splitPane, BorderLayout.CENTER);

        // ステータスバー
        statusLabel = new JLabel("準備完了");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        add(statusLabel, BorderLayout.SOUTH);

        // ツールバー
        add(createToolBar(), BorderLayout.NORTH);

        setSize(1200, 800);
        setLocationRelativeTo(null);
    }

    /**
     * ツールバー作成
     */
    private JToolBar createToolBar() {
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);

        JButton refreshButton = new JButton("再計算");
        refreshButton.addActionListener(e -> recalculateAll());
        toolBar.add(refreshButton);

        toolBar.addSeparator();

        JButton exportButton = new JButton("CSV出力");
        exportButton.addActionListener(e -> exportToCSV());
        toolBar.add(exportButton);

        JButton saveButton = new JButton("変更を保存");
        saveButton.addActionListener(e -> saveChanges());
        toolBar.add(saveButton);

        toolBar.addSeparator();

        JButton autoBalanceButton = new JButton("自動バランス調整");
        autoBalanceButton.addActionListener(e -> autoBalance());
        toolBar.add(autoBalanceButton);

        return toolBar;
    }

    /**
     * テーブルパネル作成
     */
    private JPanel createTablePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("割り当て一覧"));

        // テーブル作成
        tableModel = new AssignmentTableModel();
        assignmentTable = new JTable(tableModel);
        assignmentTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        assignmentTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        // 列幅設定
        assignmentTable.getColumnModel().getColumn(0).setPreferredWidth(80);  // スタッフ
        assignmentTable.getColumnModel().getColumn(1).setPreferredWidth(60);  // タイプ
        assignmentTable.getColumnModel().getColumn(2).setPreferredWidth(60);  // 部屋数
        assignmentTable.getColumnModel().getColumn(3).setPreferredWidth(60);  // 基本点
        assignmentTable.getColumnModel().getColumn(4).setPreferredWidth(60);  // 移動点
        assignmentTable.getColumnModel().getColumn(5).setPreferredWidth(60);  // 合計点
        assignmentTable.getColumnModel().getColumn(6).setPreferredWidth(100); // 階
        assignmentTable.getColumnModel().getColumn(7).setPreferredWidth(400); // 詳細

        // 行の色分け
        assignmentTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                           boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value,
                        isSelected, hasFocus, row, column);

                if (!isSelected) {
                    EditableAssignment assignment = editableAssignments.get(row);

                    // スコアに応じて背景色を設定
                    if (assignment.totalScore > 15) {
                        c.setBackground(new Color(255, 220, 220)); // 薄い赤（高負荷）
                    } else if (assignment.totalScore < 8) {
                        c.setBackground(new Color(220, 255, 220)); // 薄い緑（低負荷）
                    } else {
                        c.setBackground(Color.WHITE);
                    }
                }

                return c;
            }
        });

        // 選択リスナー
        assignmentTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateDetailView();
            }
        });

        JScrollPane scrollPane = new JScrollPane(assignmentTable);
        panel.add(scrollPane, BorderLayout.CENTER);

        // 統計パネル
        JPanel statsPanel = createStatisticsPanel();
        panel.add(statsPanel, BorderLayout.EAST);

        return panel;
    }

    /**
     * 統計パネル作成
     */
    private JPanel createStatisticsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder("統計"));
        panel.setPreferredSize(new Dimension(200, 0));

        detailArea = new JTextArea(10, 15);
        detailArea.setEditable(false);
        detailArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));

        JScrollPane scrollPane = new JScrollPane(detailArea);
        panel.add(scrollPane);

        return panel;
    }

    /**
     * 編集パネル作成
     */
    private JPanel createEditPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("部屋移動"));

        // 移動操作パネル
        JPanel transferPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);

        // From スタッフ
        gbc.gridx = 0; gbc.gridy = 0;
        transferPanel.add(new JLabel("移動元:"), gbc);

        gbc.gridx = 1;
        fromStaffCombo = new JComboBox<>();
        fromStaffCombo.setPreferredSize(new Dimension(150, 25));
        fromStaffCombo.addActionListener(e -> updateRoomCombo());
        transferPanel.add(fromStaffCombo, gbc);

        // 部屋選択
        gbc.gridx = 2;
        transferPanel.add(new JLabel("部屋:"), gbc);

        gbc.gridx = 3;
        roomCombo = new JComboBox<>();
        roomCombo.setPreferredSize(new Dimension(200, 25));
        transferPanel.add(roomCombo, gbc);

        // To スタッフ
        gbc.gridx = 4;
        transferPanel.add(new JLabel("移動先:"), gbc);

        gbc.gridx = 5;
        toStaffCombo = new JComboBox<>();
        toStaffCombo.setPreferredSize(new Dimension(150, 25));
        transferPanel.add(toStaffCombo, gbc);

        // 移動ボタン
        gbc.gridx = 6;
        transferButton = new JButton("移動実行");
        transferButton.addActionListener(e -> executeTransfer());
        transferPanel.add(transferButton, gbc);

        panel.add(transferPanel, BorderLayout.NORTH);

        // プレビューエリア
        JPanel previewPanel = new JPanel(new BorderLayout());
        previewPanel.setBorder(BorderFactory.createTitledBorder("移動プレビュー"));

        JTextArea previewArea = new JTextArea(5, 0);
        previewArea.setEditable(false);
        previewPanel.add(new JScrollPane(previewArea), BorderLayout.CENTER);

        panel.add(previewPanel, BorderLayout.CENTER);

        // コンボボックスの初期化
        updateStaffCombos();

        return panel;
    }

    /**
     * スタッフコンボボックスの更新
     */
    private void updateStaffCombos() {
        fromStaffCombo.removeAllItems();
        toStaffCombo.removeAllItems();

        for (EditableAssignment assignment : editableAssignments) {
            fromStaffCombo.addItem(assignment.staffName);
            toStaffCombo.addItem(assignment.staffName);
        }
    }

    /**
     * 部屋コンボボックスの更新
     */
    private void updateRoomCombo() {
        roomCombo.removeAllItems();

        String selectedStaff = (String) fromStaffCombo.getSelectedItem();
        if (selectedStaff == null) return;

        EditableAssignment assignment = findAssignment(selectedStaff);
        if (assignment == null) return;

        for (Map.Entry<Integer, AdaptiveRoomOptimizer.RoomAllocation> entry : assignment.roomsByFloor.entrySet()) {
            int floor = entry.getKey();
            AdaptiveRoomOptimizer.RoomAllocation alloc = entry.getValue();

            // 各部屋タイプごとに項目を追加
            for (Map.Entry<String, Integer> room : alloc.roomCounts.entrySet()) {
                if (room.getValue() > 0) {
                    roomCombo.addItem(new RoomInfo(floor, room.getKey(), room.getValue(), selectedStaff));
                }
            }

            // エコ部屋
            if (alloc.ecoRooms > 0) {
                roomCombo.addItem(new RoomInfo(floor, "ECO", alloc.ecoRooms, selectedStaff));
            }
        }
    }

    /**
     * 部屋の移動実行
     */
    private void executeTransfer() {
        String fromStaffName = (String) fromStaffCombo.getSelectedItem();
        String toStaffName = (String) toStaffCombo.getSelectedItem();
        RoomInfo roomInfo = (RoomInfo) roomCombo.getSelectedItem();

        if (fromStaffName == null || toStaffName == null || roomInfo == null) {
            JOptionPane.showMessageDialog(this, "移動元、移動先、部屋を選択してください");
            return;
        }

        if (fromStaffName.equals(toStaffName)) {
            JOptionPane.showMessageDialog(this, "同じスタッフには移動できません");
            return;
        }

        // 移動実行
        EditableAssignment fromAssignment = findAssignment(fromStaffName);
        EditableAssignment toAssignment = findAssignment(toStaffName);

        if (fromAssignment == null || toAssignment == null) return;

        // 移動数を確認
        String input = JOptionPane.showInputDialog(this,
                String.format("%d階の%s部屋を何室移動しますか？（最大%d室）",
                        roomInfo.floor, roomInfo.type, roomInfo.count),
                "1");

        if (input == null) return;

        try {
            int moveCount = Integer.parseInt(input);
            if (moveCount <= 0 || moveCount > roomInfo.count) {
                JOptionPane.showMessageDialog(this, "無効な移動数です");
                return;
            }

            // 実際の移動処理
            transferRooms(fromAssignment, toAssignment, roomInfo.floor, roomInfo.type, moveCount);

            // 再計算と表示更新
            fromAssignment.recalculate();
            toAssignment.recalculate();
            tableModel.refresh();
            updateStatistics();
            updateRoomCombo();

            statusLabel.setText(String.format("%sから%sへ%d階の%s部屋を%d室移動しました",
                    fromStaffName, toStaffName, roomInfo.floor, roomInfo.type, moveCount));

        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "数値を入力してください");
        }
    }

    /**
     * 部屋の移動処理
     */
    private void transferRooms(EditableAssignment from, EditableAssignment to,
                               int floor, String roomType, int count) {
        // 移動元から削除
        AdaptiveRoomOptimizer.RoomAllocation fromAlloc = from.roomsByFloor.get(floor);
        if (fromAlloc != null) {
            Map<String, Integer> newFromCounts = new HashMap<>(fromAlloc.roomCounts);
            int newFromEco = fromAlloc.ecoRooms;

            if (roomType.equals("ECO")) {
                newFromEco = Math.max(0, newFromEco - count);
            } else {
                int current = newFromCounts.getOrDefault(roomType, 0);
                newFromCounts.put(roomType, Math.max(0, current - count));
            }

            // 空になったら階を削除
            if (newFromCounts.values().stream().allMatch(v -> v == 0) && newFromEco == 0) {
                from.roomsByFloor.remove(floor);
                from.floors.remove(Integer.valueOf(floor));
            } else {
                from.roomsByFloor.put(floor, new AdaptiveRoomOptimizer.RoomAllocation(newFromCounts, newFromEco));
            }
        }

        // 移動先に追加
        AdaptiveRoomOptimizer.RoomAllocation toAlloc = to.roomsByFloor.get(floor);
        if (toAlloc == null) {
            // 新しい階を追加
            Map<String, Integer> newToCounts = new HashMap<>();
            int newToEco = 0;

            if (roomType.equals("ECO")) {
                newToEco = count;
            } else {
                newToCounts.put(roomType, count);
            }

            to.roomsByFloor.put(floor, new AdaptiveRoomOptimizer.RoomAllocation(newToCounts, newToEco));
            to.floors.add(floor);
            Collections.sort(to.floors);
        } else {
            // 既存の階に追加
            Map<String, Integer> newToCounts = new HashMap<>(toAlloc.roomCounts);
            int newToEco = toAlloc.ecoRooms;

            if (roomType.equals("ECO")) {
                newToEco += count;
            } else {
                int current = newToCounts.getOrDefault(roomType, 0);
                newToCounts.put(roomType, current + count);
            }

            to.roomsByFloor.put(floor, new AdaptiveRoomOptimizer.RoomAllocation(newToCounts, newToEco));
        }
    }

    /**
     * 割り当てを検索
     */
    private EditableAssignment findAssignment(String staffName) {
        return editableAssignments.stream()
                .filter(a -> a.staffName.equals(staffName))
                .findFirst()
                .orElse(null);
    }

    /**
     * 詳細ビューの更新
     */
    private void updateDetailView() {
        int selectedRow = assignmentTable.getSelectedRow();
        if (selectedRow < 0) return;

        EditableAssignment assignment = editableAssignments.get(selectedRow);

        // 詳細情報の表示
        StringBuilder sb = new StringBuilder();
        sb.append("スタッフ: ").append(assignment.staffName).append("\n");
        sb.append("タイプ: ").append(assignment.workerType.displayName).append("\n");
        sb.append("部屋数: ").append(assignment.totalRooms).append("\n");
        sb.append("基本点: ").append(String.format("%.1f", assignment.basePoints)).append("\n");
        sb.append("移動点: ").append(String.format("%.1f", assignment.movementPenalty)).append("\n");
        sb.append("合計点: ").append(String.format("%.1f", assignment.totalScore)).append("\n");
        sb.append("\n階別詳細:\n");

        for (Map.Entry<Integer, AdaptiveRoomOptimizer.RoomAllocation> entry : assignment.roomsByFloor.entrySet()) {
            sb.append(String.format("  %d階: ", entry.getKey()));
            AdaptiveRoomOptimizer.RoomAllocation alloc = entry.getValue();

            List<String> details = new ArrayList<>();
            for (Map.Entry<String, Integer> room : alloc.roomCounts.entrySet()) {
                if (room.getValue() > 0) {
                    details.add(room.getKey() + ":" + room.getValue());
                }
            }
            if (alloc.ecoRooms > 0) {
                details.add("エコ:" + alloc.ecoRooms);
            }

            sb.append(String.join(" ", details)).append("\n");
        }

        detailArea.setText(sb.toString());
    }

    /**
     * 統計情報の更新
     */
    private void updateStatistics() {
        // 統計計算
        double minScore = editableAssignments.stream()
                .mapToDouble(a -> a.totalScore)
                .min().orElse(0);
        double maxScore = editableAssignments.stream()
                .mapToDouble(a -> a.totalScore)
                .max().orElse(0);
        double avgScore = editableAssignments.stream()
                .mapToDouble(a -> a.totalScore)
                .average().orElse(0);

        int totalRooms = editableAssignments.stream()
                .mapToInt(a -> a.totalRooms)
                .sum();

        // ステータス更新
        statusLabel.setText(String.format(
                "総部屋数: %d | スコア範囲: %.1f～%.1f (差: %.1f) | 平均: %.1f",
                totalRooms, minScore, maxScore, maxScore - minScore, avgScore));
    }

    /**
     * 全体再計算
     */
    private void recalculateAll() {
        for (EditableAssignment assignment : editableAssignments) {
            assignment.recalculate();
        }
        tableModel.refresh();
        updateStatistics();
        statusLabel.setText("再計算完了");
    }

    /**
     * 自動バランス調整
     */
    private void autoBalance() {
        // 簡易的な自動バランス調整
        editableAssignments.sort(Comparator.comparingDouble(a -> a.totalScore));

        EditableAssignment minLoad = editableAssignments.get(0);
        EditableAssignment maxLoad = editableAssignments.get(editableAssignments.size() - 1);

        if (maxLoad.totalScore - minLoad.totalScore > 3.0) {
            JOptionPane.showMessageDialog(this,
                    String.format("%s (%.1f点) から %s (%.1f点) へ部屋を移動することを推奨します",
                            maxLoad.staffName, maxLoad.totalScore,
                            minLoad.staffName, minLoad.totalScore),
                    "バランス調整提案",
                    JOptionPane.INFORMATION_MESSAGE);
        } else {
            JOptionPane.showMessageDialog(this,
                    "現在の配分は適切にバランスされています",
                    "バランス確認",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /**
     * CSV出力
     */
    private void exportToCSV() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setSelectedFile(new java.io.File("assignment_edited.csv"));

        if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try (java.io.PrintWriter writer = new java.io.PrintWriter(fileChooser.getSelectedFile())) {
                // ヘッダー
                writer.println("スタッフ,タイプ,部屋数,基本点,移動点,合計点,階,詳細");

                // データ
                for (EditableAssignment assignment : editableAssignments) {
                    writer.printf("%s,%s,%d,%.1f,%.1f,%.1f,\"%s\",\"%s\"\n",
                            assignment.staffName,
                            assignment.workerType.displayName,
                            assignment.totalRooms,
                            assignment.basePoints,
                            assignment.movementPenalty,
                            assignment.totalScore,
                            assignment.getFloorsString(),
                            assignment.getRoomDetails());
                }

                JOptionPane.showMessageDialog(this, "CSVファイルを保存しました");
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "保存エラー: " + e.getMessage());
            }
        }
    }

    /**
     * 変更を保存
     */
    private void saveChanges() {
        int result = JOptionPane.showConfirmDialog(this,
                "変更を保存しますか？",
                "確認",
                JOptionPane.YES_NO_OPTION);

        if (result == JOptionPane.YES_OPTION) {
            // ここで実際の保存処理を実装
            statusLabel.setText("変更を保存しました");
        }
    }

    /**
     * GUIを表示
     */
    public static void showEditor(AdaptiveRoomOptimizer.OptimizationResult result) {
        SwingUtilities.invokeLater(() -> {
            AssignmentEditorGUI editor = new AssignmentEditorGUI(result);
            editor.setVisible(true);
        });
    }
}