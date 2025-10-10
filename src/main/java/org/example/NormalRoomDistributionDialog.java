package org.example;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 通常清掃部屋割り振りダイアログ
 * 本館・別館の部屋数差を1部屋差と2部屋差で計算し、選択・編集可能
 * ★改善: 本館・別館を個別に設定可能
 */
public class NormalRoomDistributionDialog extends JDialog {

    private JComboBox<String> patternSelector;
    private JTable distributionTable;
    private DefaultTableModel tableModel;
    private JLabel summaryLabel;

    private Map<String, StaffDistribution> oneDiffPattern;
    private Map<String, StaffDistribution> twoDiffPattern;
    private Map<String, StaffDistribution> currentPattern;

    private int totalMainRooms;
    private int totalAnnexRooms;
    private List<RoomAssignmentApplication.StaffPointConstraint> staffConstraints;
    private List<String> staffNames;

    private boolean dialogResult = false;

    /**
     * スタッフ割り振り情報（本館・別館個別管理版）
     */
    public static class StaffDistribution {
        public final String staffId;
        public final String staffName;
        public String buildingAssignment; // 表示・判定用に保持
        public int mainAssignedRooms;     // ★追加: 本館の部屋数
        public int annexAssignedRooms;    // ★追加: 別館の部屋数
        public int assignedRooms;         // 合計（互換性のため保持）
        public String constraintType;
        public boolean isBathCleaning;

        public StaffDistribution(String staffId, String staffName, String buildingAssignment,
                                 int mainRooms, int annexRooms, String constraintType, boolean isBathCleaning) {
            this.staffId = staffId;
            this.staffName = staffName;
            this.buildingAssignment = buildingAssignment;
            this.mainAssignedRooms = mainRooms;
            this.annexAssignedRooms = annexRooms;
            this.assignedRooms = mainRooms + annexRooms;
            this.constraintType = constraintType;
            this.isBathCleaning = isBathCleaning;
        }

        // 旧形式との互換性コンストラクタ
        public StaffDistribution(String staffId, String staffName, String buildingAssignment,
                                 int totalRooms, String constraintType, boolean isBathCleaning) {
            this.staffId = staffId;
            this.staffName = staffName;
            this.buildingAssignment = buildingAssignment;
            this.constraintType = constraintType;
            this.isBathCleaning = isBathCleaning;

            // 建物に応じて振り分け
            if ("本館のみ".equals(buildingAssignment)) {
                this.mainAssignedRooms = totalRooms;
                this.annexAssignedRooms = 0;
            } else if ("別館のみ".equals(buildingAssignment)) {
                this.mainAssignedRooms = 0;
                this.annexAssignedRooms = totalRooms;
            } else {
                // 両方の場合は均等に分配
                this.mainAssignedRooms = totalRooms / 2;
                this.annexAssignedRooms = totalRooms - this.mainAssignedRooms;
            }
            this.assignedRooms = this.mainAssignedRooms + this.annexAssignedRooms;
        }

        // コピーコンストラクタ
        public StaffDistribution(StaffDistribution other) {
            this.staffId = other.staffId;
            this.staffName = other.staffName;
            this.buildingAssignment = other.buildingAssignment;
            this.mainAssignedRooms = other.mainAssignedRooms;
            this.annexAssignedRooms = other.annexAssignedRooms;
            this.assignedRooms = other.assignedRooms;
            this.constraintType = other.constraintType;
            this.isBathCleaning = other.isBathCleaning;
        }

        // 合計を更新
        public void updateTotal() {
            this.assignedRooms = this.mainAssignedRooms + this.annexAssignedRooms;
        }
    }

    public NormalRoomDistributionDialog(JFrame parent,
                                        int totalMainRooms,
                                        int totalAnnexRooms,
                                        List<RoomAssignmentApplication.StaffPointConstraint> staffConstraints,
                                        List<String> staffNames) {
        super(parent, "通常清掃部屋割り振り設定", true);

        this.totalMainRooms = totalMainRooms;
        this.totalAnnexRooms = totalAnnexRooms;
        this.staffConstraints = staffConstraints;
        this.staffNames = staffNames;

        calculateDistributionPatterns();
        this.currentPattern = deepCopyPattern(oneDiffPattern);

        initializeGUI();
        setSize(1000, 600);
        setLocationRelativeTo(parent);
    }

    private void initializeGUI() {
        setLayout(new BorderLayout());
        add(createTopPanel(), BorderLayout.NORTH);
        add(createCenterPanel(), BorderLayout.CENTER);
        add(createButtonPanel(), BorderLayout.SOUTH);
    }

    private JPanel createTopPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel selectorPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        selectorPanel.add(new JLabel("割り振りパターン:"));

        String[] patterns = {"1部屋差パターン", "2部屋差パターン"};
        patternSelector = new JComboBox<>(patterns);
        patternSelector.addActionListener(e -> switchPattern());
        selectorPanel.add(patternSelector);

        summaryLabel = new JLabel();
        updateSummaryLabel();
        selectorPanel.add(Box.createHorizontalStrut(20));
        selectorPanel.add(summaryLabel);

        panel.add(selectorPanel, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));

        // ★変更: 列構成を本館・別館個別に
        String[] columnNames = {"スタッフ名", "制約タイプ", "大浴場", "本館部屋数", "本館調整", "別館部屋数", "別館調整", "合計"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                // 本館調整（列4）と別館調整（列6）のみ編集可能
                return column == 4 || column == 6;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == 4 || columnIndex == 6) return JButton.class;
                return String.class;
            }
        };

        distributionTable = new JTable(tableModel);
        distributionTable.setRowHeight(35);

        distributionTable.getColumnModel().getColumn(0).setPreferredWidth(120); // スタッフ名
        distributionTable.getColumnModel().getColumn(1).setPreferredWidth(100); // 制約タイプ
        distributionTable.getColumnModel().getColumn(2).setPreferredWidth(60);  // 大浴場
        distributionTable.getColumnModel().getColumn(3).setPreferredWidth(90);  // 本館部屋数
        distributionTable.getColumnModel().getColumn(4).setPreferredWidth(120); // 本館調整
        distributionTable.getColumnModel().getColumn(5).setPreferredWidth(90);  // 別館部屋数
        distributionTable.getColumnModel().getColumn(6).setPreferredWidth(120); // 別館調整
        distributionTable.getColumnModel().getColumn(7).setPreferredWidth(80);  // 合計

        // 本館調整ボタン
        distributionTable.getColumnModel().getColumn(4).setCellRenderer(new ButtonRenderer());
        distributionTable.getColumnModel().getColumn(4).setCellEditor(new ButtonEditor(new JCheckBox(), true));

        // 別館調整ボタン
        distributionTable.getColumnModel().getColumn(6).setCellRenderer(new ButtonRenderer());
        distributionTable.getColumnModel().getColumn(6).setCellEditor(new ButtonEditor(new JCheckBox(), false));

        // 中央揃え
        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(JLabel.CENTER);
        for (int i = 1; i <= 3; i++) {
            distributionTable.getColumnModel().getColumn(i).setCellRenderer(centerRenderer);
        }
        distributionTable.getColumnModel().getColumn(3).setCellRenderer(centerRenderer);
        distributionTable.getColumnModel().getColumn(5).setCellRenderer(centerRenderer);
        distributionTable.getColumnModel().getColumn(7).setCellRenderer(centerRenderer);

        updateTable();

        JScrollPane scrollPane = new JScrollPane(distributionTable);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createButtonPanel() {
        JPanel panel = new JPanel(new FlowLayout());

        JButton resetButton = new JButton("リセット");
        resetButton.addActionListener(e -> resetPattern());
        panel.add(resetButton);

        panel.add(Box.createHorizontalStrut(20));

        JButton okButton = new JButton("設定完了");
        okButton.setFont(new Font("MS Gothic", Font.BOLD, 12));
        okButton.addActionListener(e -> {
            if (distributionTable.isEditing()) {
                distributionTable.getCellEditor().stopCellEditing();
            }
            dialogResult = true;
            dispose();
        });
        panel.add(okButton);

        JButton cancelButton = new JButton("キャンセル");
        cancelButton.addActionListener(e -> dispose());
        panel.add(cancelButton);

        return panel;
    }

    private void calculateDistributionPatterns() {
        oneDiffPattern = calculatePattern(1);
        twoDiffPattern = calculatePattern(2);
    }

    /**
     * ★修正: 要件に基づく正しい割り振り計算
     *
     * 1. 本館と別館の通常部屋数を計算
     * 2. 故障制限スタッフの部屋数を先に決定（業者制限は最低値）
     * 3. 通常スタッフに均等割り振り
     * 4. 本館と別館の部屋数差が目標（1部屋差 or 2部屋差）になるよう調整
     * 5. 各建物内のスタッフ間の部屋差は最大1部屋
     * 6. 業者制限スタッフで微調整
     */
    private Map<String, StaffDistribution> calculatePattern(int targetRoomDiff) {
        Map<String, StaffDistribution> pattern = new HashMap<>();

        // スタッフ分類
        List<String> mainOnlyStaff = new ArrayList<>();
        List<String> annexOnlyStaff = new ArrayList<>();
        List<String> normalMainStaff = new ArrayList<>();
        List<String> normalAnnexStaff = new ArrayList<>();
        List<String> flexibleMainStaff = new ArrayList<>();
        List<String> flexibleAnnexStaff = new ArrayList<>();

        Map<String, String> constraintTypes = new HashMap<>();
        Map<String, Boolean> bathCleaningMap = new HashMap<>();
        Map<String, Integer> fixedRooms = new HashMap<>();
        Map<String, int[]> flexibleRange = new HashMap<>();

        // 制約情報の収集とスタッフ分類
        for (RoomAssignmentApplication.StaffPointConstraint constraint : staffConstraints) {
            String staffName = constraint.staffName;
            String building = constraint.buildingAssignment.displayName;
            constraintTypes.put(staffName, constraint.constraintType.displayName);
            bathCleaningMap.put(staffName, constraint.isBathCleaningStaff);

            boolean isMainOnly = "本館のみ".equals(building) || constraint.isBathCleaningStaff;
            boolean isAnnexOnly = "別館のみ".equals(building);

            if (isMainOnly) {
                mainOnlyStaff.add(staffName);
            } else if (isAnnexOnly) {
                annexOnlyStaff.add(staffName);
            }

            // 制約タイプの処理
            if (constraint.constraintType == RoomAssignmentApplication.StaffPointConstraint.ConstraintType.UPPER_LIMIT) {
                // 故障制限：固定部屋数
                int rooms = (int) Math.floor(constraint.upperLimit);
                fixedRooms.put(staffName, rooms);
            } else if (constraint.constraintType == RoomAssignmentApplication.StaffPointConstraint.ConstraintType.LOWER_RANGE) {
                // 業者制限：最低値で初期化、後で調整可能
                int minRooms = (int) Math.floor(constraint.lowerMinLimit);
                int maxRooms = (int) Math.ceil(constraint.lowerMaxLimit);
                flexibleRange.put(staffName, new int[]{minRooms, maxRooms});

                if (isMainOnly) {
                    flexibleMainStaff.add(staffName);
                } else if (isAnnexOnly) {
                    flexibleAnnexStaff.add(staffName);
                }
            } else {
                // 通常スタッフ
                if (isMainOnly) {
                    normalMainStaff.add(staffName);
                } else if (isAnnexOnly) {
                    normalAnnexStaff.add(staffName);
                }
            }
        }

        // 制約のないスタッフを本館に追加
        for (String staffName : staffNames) {
            if (!constraintTypes.containsKey(staffName)) {
                constraintTypes.put(staffName, "制限なし");
                bathCleaningMap.put(staffName, false);
                mainOnlyStaff.add(staffName);
                normalMainStaff.add(staffName);
            }
        }

        int remainingMain = totalMainRooms;
        int remainingAnnex = totalAnnexRooms;

        // Phase 1: 故障制限スタッフの固定部屋数を割り当て
        for (Map.Entry<String, Integer> entry : fixedRooms.entrySet()) {
            String staffName = entry.getKey();
            int rooms = entry.getValue();

            int mainRooms = 0;
            int annexRooms = 0;
            String building = "両方";

            if (mainOnlyStaff.contains(staffName)) {
                mainRooms = rooms;
                remainingMain -= rooms;
                building = "本館のみ";
            } else if (annexOnlyStaff.contains(staffName)) {
                annexRooms = rooms;
                remainingAnnex -= rooms;
                building = "別館のみ";
            }

            pattern.put(staffName, new StaffDistribution(
                    staffName, staffName, building, mainRooms, annexRooms,
                    constraintTypes.get(staffName), bathCleaningMap.get(staffName)));
        }

        // Phase 2: 業者制限スタッフを最低値で初期化
        for (String staffName : flexibleMainStaff) {
            if (!fixedRooms.containsKey(staffName)) {
                int minRooms = flexibleRange.get(staffName)[0];
                pattern.put(staffName, new StaffDistribution(
                        staffName, staffName, "本館のみ", minRooms, 0,
                        constraintTypes.get(staffName), bathCleaningMap.get(staffName)));
                remainingMain -= minRooms;
            }
        }

        for (String staffName : flexibleAnnexStaff) {
            if (!fixedRooms.containsKey(staffName)) {
                int minRooms = flexibleRange.get(staffName)[0];
                pattern.put(staffName, new StaffDistribution(
                        staffName, staffName, "別館のみ", 0, minRooms,
                        constraintTypes.get(staffName), bathCleaningMap.get(staffName)));
                remainingAnnex -= minRooms;
            }
        }

        // Phase 3: 本館の通常スタッフに均等割り振り
        if (!normalMainStaff.isEmpty() && remainingMain > 0) {
            int avgRooms = remainingMain / normalMainStaff.size();
            int extra = remainingMain % normalMainStaff.size();

            for (int i = 0; i < normalMainStaff.size(); i++) {
                String staffName = normalMainStaff.get(i);
                int rooms = avgRooms + (i < extra ? 1 : 0);

                pattern.put(staffName, new StaffDistribution(
                        staffName, staffName, "本館のみ", rooms, 0,
                        constraintTypes.get(staffName), bathCleaningMap.get(staffName)));
            }
            remainingMain = 0;
        }

        // Phase 4: 別館スタッフに割り振り（本館最大値 - 目標差分）
        if (!normalAnnexStaff.isEmpty() && remainingAnnex > 0) {
            // 本館の最大部屋数を取得
            int maxMainRooms = pattern.values().stream()
                    .mapToInt(d -> d.mainAssignedRooms)
                    .max().orElse(0);

            // 別館の目標部屋数 = 本館最大 - 目標差
            int targetAnnexPerPerson = Math.max(1, maxMainRooms - targetRoomDiff);

            // 通常別館スタッフ全員が目標部屋数を持つ場合の合計
            int idealTotal = targetAnnexPerPerson * normalAnnexStaff.size();

            // 実際の残り部屋数に合わせて調整
            int actualPerPerson = targetAnnexPerPerson;
            if (idealTotal > remainingAnnex) {
                // 残り部屋数が足りない場合は減らす
                actualPerPerson = remainingAnnex / normalAnnexStaff.size();
            } else if (idealTotal < remainingAnnex) {
                // 残りが多い場合は1部屋ずつ増やす（最大1部屋差まで）
                int shortage = remainingAnnex - idealTotal;
                if (shortage <= normalAnnexStaff.size()) {
                    // 余りを一部のスタッフに配分
                    actualPerPerson = targetAnnexPerPerson;
                } else {
                    // 全員に配分しても余る場合
                    actualPerPerson = remainingAnnex / normalAnnexStaff.size();
                }
            }

            int extra = remainingAnnex - (actualPerPerson * normalAnnexStaff.size());

            for (int i = 0; i < normalAnnexStaff.size(); i++) {
                String staffName = normalAnnexStaff.get(i);
                int rooms = actualPerPerson + (i < extra ? 1 : 0);

                pattern.put(staffName, new StaffDistribution(
                        staffName, staffName, "別館のみ", 0, rooms,
                        constraintTypes.get(staffName), bathCleaningMap.get(staffName)));
            }
            remainingAnnex -= (actualPerPerson * normalAnnexStaff.size() + extra);
        }

        // Phase 5: 業者制限スタッフで微調整（部屋数差の最適化）
        adjustWithFlexibleStaff(pattern, flexibleMainStaff, flexibleAnnexStaff,
                flexibleRange, targetRoomDiff, remainingMain, remainingAnnex);

        return pattern;
    }

    /**
     * 業者制限スタッフで部屋数差を調整
     */
    private void adjustWithFlexibleStaff(Map<String, StaffDistribution> pattern,
                                         List<String> flexibleMainStaff,
                                         List<String> flexibleAnnexStaff,
                                         Map<String, int[]> flexibleRange,
                                         int targetRoomDiff,
                                         int remainingMain,
                                         int remainingAnnex) {

        // 現在の本館・別館の最大部屋数を取得
        int maxMain = pattern.values().stream()
                .mapToInt(d -> d.mainAssignedRooms)
                .max().orElse(0);

        int maxAnnex = pattern.values().stream()
                .mapToInt(d -> d.annexAssignedRooms)
                .max().orElse(0);

        int currentDiff = maxMain - maxAnnex;

        // 目標差に近づけるため、業者制限スタッフを1部屋ずつ調整
        if (currentDiff < targetRoomDiff) {
            // 差を広げる必要がある → 本館の業者スタッフを増やすか、別館を減らす
            // 本館を増やす
            for (String staffName : flexibleMainStaff) {
                if (currentDiff >= targetRoomDiff || remainingMain <= 0) break;

                StaffDistribution dist = pattern.get(staffName);
                if (dist != null) {
                    int[] range = flexibleRange.get(staffName);
                    int maxRooms = range[1];

                    while (dist.mainAssignedRooms < maxRooms && currentDiff < targetRoomDiff && remainingMain > 0) {
                        dist.mainAssignedRooms++;
                        dist.updateTotal();
                        remainingMain--;

                        maxMain = Math.max(maxMain, dist.mainAssignedRooms);
                        currentDiff = maxMain - maxAnnex;
                    }
                }
            }
        } else if (currentDiff > targetRoomDiff) {
            // 差を縮める必要がある → 別館の業者スタッフを増やす
            for (String staffName : flexibleAnnexStaff) {
                if (currentDiff <= targetRoomDiff || remainingAnnex <= 0) break;

                StaffDistribution dist = pattern.get(staffName);
                if (dist != null) {
                    int[] range = flexibleRange.get(staffName);
                    int maxRooms = range[1];

                    while (dist.annexAssignedRooms < maxRooms && currentDiff > targetRoomDiff && remainingAnnex > 0) {
                        dist.annexAssignedRooms++;
                        dist.updateTotal();
                        remainingAnnex--;

                        maxAnnex = Math.max(maxAnnex, dist.annexAssignedRooms);
                        currentDiff = maxMain - maxAnnex;
                    }
                }
            }
        }

        // 残り部屋があれば業者制限スタッフに配分（平等に）
        while (remainingMain > 0 && !flexibleMainStaff.isEmpty()) {
            boolean assigned = false;
            for (String staffName : flexibleMainStaff) {
                if (remainingMain <= 0) break;

                StaffDistribution dist = pattern.get(staffName);
                if (dist != null) {
                    int[] range = flexibleRange.get(staffName);
                    int maxRooms = range[1];

                    if (dist.mainAssignedRooms < maxRooms) {
                        dist.mainAssignedRooms++;
                        dist.updateTotal();
                        remainingMain--;
                        assigned = true;
                    }
                }
            }
            if (!assigned) break; // これ以上配分できない
        }

        while (remainingAnnex > 0 && !flexibleAnnexStaff.isEmpty()) {
            boolean assigned = false;
            for (String staffName : flexibleAnnexStaff) {
                if (remainingAnnex <= 0) break;

                StaffDistribution dist = pattern.get(staffName);
                if (dist != null) {
                    int[] range = flexibleRange.get(staffName);
                    int maxRooms = range[1];

                    if (dist.annexAssignedRooms < maxRooms) {
                        dist.annexAssignedRooms++;
                        dist.updateTotal();
                        remainingAnnex--;
                        assigned = true;
                    }
                }
            }
            if (!assigned) break;
        }
    }

    private Map<String, StaffDistribution> deepCopyPattern(Map<String, StaffDistribution> source) {
        Map<String, StaffDistribution> copy = new HashMap<>();
        for (Map.Entry<String, StaffDistribution> entry : source.entrySet()) {
            copy.put(entry.getKey(), new StaffDistribution(entry.getValue()));
        }
        return copy;
    }

    private String getStaffBuilding(String staffName) {
        for (RoomAssignmentApplication.StaffPointConstraint constraint : staffConstraints) {
            if (constraint.staffName.equals(staffName)) {
                return constraint.buildingAssignment.displayName;
            }
        }
        return "両方";
    }

    private void switchPattern() {
        int selected = patternSelector.getSelectedIndex();

        if (selected == 0) {
            currentPattern = deepCopyPattern(oneDiffPattern);
        } else {
            currentPattern = deepCopyPattern(twoDiffPattern);
        }

        updateTable();
        updateSummaryLabel();
    }

    private void updateTable() {
        tableModel.setRowCount(0);

        List<StaffDistribution> sortedStaff = new ArrayList<>(currentPattern.values());
        sortedStaff.sort((a, b) -> {
            // 本館部屋数が多い順にソート
            int mainCompare = Integer.compare(b.mainAssignedRooms, a.mainAssignedRooms);
            if (mainCompare != 0) return mainCompare;
            return Integer.compare(b.annexAssignedRooms, a.annexAssignedRooms);
        });

        for (StaffDistribution dist : sortedStaff) {
            Object[] row = {
                    dist.staffName,
                    dist.constraintType,
                    dist.isBathCleaning ? "担当" : "",
                    dist.mainAssignedRooms + "部屋",
                    "調整", // 本館調整ボタン
                    dist.annexAssignedRooms + "部屋",
                    "調整", // 別館調整ボタン
                    dist.assignedRooms + "部屋"
            };
            tableModel.addRow(row);
        }
    }

    private void updateSummaryLabel() {
        int totalAssigned = currentPattern.values().stream()
                .mapToInt(d -> d.assignedRooms)
                .sum();

        int mainAssigned = currentPattern.values().stream()
                .mapToInt(d -> d.mainAssignedRooms)
                .sum();

        int annexAssigned = currentPattern.values().stream()
                .mapToInt(d -> d.annexAssignedRooms)
                .sum();

        int maxMain = currentPattern.values().stream()
                .mapToInt(d -> d.mainAssignedRooms)
                .max().orElse(0);

        int maxAnnex = currentPattern.values().stream()
                .mapToInt(d -> d.annexAssignedRooms)
                .max().orElse(0);

        int roomDiff = maxMain - maxAnnex;

        summaryLabel.setText(String.format(
                "<html>割当合計: %d/%d部屋 | 本館: %d/%d (最大%d) | 別館: %d/%d (最大%d) | <b>部屋数差: %d</b></html>",
                totalAssigned, totalMainRooms + totalAnnexRooms,
                mainAssigned, totalMainRooms, maxMain,
                annexAssigned, totalAnnexRooms, maxAnnex,
                roomDiff));
    }

    private void resetPattern() {
        switchPattern();
    }

    public boolean getDialogResult() {
        return dialogResult;
    }

    public Map<String, StaffDistribution> getCurrentDistribution() {
        return new HashMap<>(currentPattern);
    }

    /**
     * ボタンレンダラー
     */
    class ButtonRenderer extends JPanel implements TableCellRenderer {
        private JButton minusButton = new JButton("-");
        private JButton plusButton = new JButton("+");

        public ButtonRenderer() {
            setLayout(new FlowLayout(FlowLayout.CENTER, 5, 2));
            minusButton.setPreferredSize(new Dimension(50, 25));
            plusButton.setPreferredSize(new Dimension(50, 25));
            add(minusButton);
            add(plusButton);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus, int row, int column) {
            if (isSelected) {
                setBackground(table.getSelectionBackground());
            } else {
                setBackground(table.getBackground());
            }
            return this;
        }
    }

    /**
     * ★改善: 本館・別館個別対応のボタンエディタ
     */
    class ButtonEditor extends DefaultCellEditor {
        private JPanel panel;
        private JButton plusButton;
        private JButton minusButton;
        private int currentRow;
        private boolean isMainBuilding; // true=本館, false=別館

        public ButtonEditor(JCheckBox checkBox, boolean isMainBuilding) {
            super(checkBox);
            this.isMainBuilding = isMainBuilding;

            panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 2));
            plusButton = new JButton("+");
            minusButton = new JButton("-");

            plusButton.setPreferredSize(new Dimension(50, 25));
            minusButton.setPreferredSize(new Dimension(50, 25));

            plusButton.addActionListener(e -> {
                adjustRoom(currentRow, 1, isMainBuilding);
                fireEditingStopped();
            });

            minusButton.addActionListener(e -> {
                adjustRoom(currentRow, -1, isMainBuilding);
                fireEditingStopped();
            });

            panel.add(minusButton);
            panel.add(plusButton);

            setClickCountToStart(1);
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value,
                                                     boolean isSelected, int row, int column) {
            currentRow = row;
            return panel;
        }

        @Override
        public Object getCellEditorValue() {
            return "調整";
        }

        /**
         * 部屋数調整（本館または別館）
         */
        private void adjustRoom(int row, int delta, boolean isMain) {
            String staffName = (String) tableModel.getValueAt(row, 0);
            StaffDistribution dist = currentPattern.get(staffName);

            if (dist != null) {
                if (isMain) {
                    // 本館の部屋数を調整
                    int newRooms = Math.max(0, dist.mainAssignedRooms + delta);
                    dist.mainAssignedRooms = newRooms;
                    tableModel.setValueAt(newRooms + "部屋", row, 3);
                } else {
                    // 別館の部屋数を調整
                    int newRooms = Math.max(0, dist.annexAssignedRooms + delta);
                    dist.annexAssignedRooms = newRooms;
                    tableModel.setValueAt(newRooms + "部屋", row, 5);
                }

                // 合計を更新
                dist.updateTotal();
                tableModel.setValueAt(dist.assignedRooms + "部屋", row, 7);

                // サマリーを更新
                updateSummaryLabel();
            }
        }
    }
}