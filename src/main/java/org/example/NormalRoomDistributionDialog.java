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
 * ★修正: 正しい計算ロジック（ステップ1-12）を実装
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
    private AdaptiveRoomOptimizer.BathCleaningType bathCleaningType;

    private boolean dialogResult = false;

    /**
     * スタッフ割り振り情報（本館・別館個別管理版）
     */
    public static class StaffDistribution {
        public final String staffId;
        public final String staffName;
        public String buildingAssignment;
        public int mainAssignedRooms;
        public int annexAssignedRooms;
        public int assignedRooms;
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

            if ("本館のみ".equals(buildingAssignment)) {
                this.mainAssignedRooms = totalRooms;
                this.annexAssignedRooms = 0;
            } else if ("別館のみ".equals(buildingAssignment)) {
                this.mainAssignedRooms = 0;
                this.annexAssignedRooms = totalRooms;
            } else {
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

        public void updateTotal() {
            this.assignedRooms = this.mainAssignedRooms + this.annexAssignedRooms;
        }
    }

    /**
     * スタッフ制約情報の内部クラス
     */
    private static class StaffConstraintInfo {
        String constraintType;
        String buildingAssignment;
        boolean isBathCleaning;
        int minRooms;
        int maxRooms;

        StaffConstraintInfo(String constraintType, String buildingAssignment,
                            boolean isBathCleaning, int minRooms, int maxRooms) {
            this.constraintType = constraintType;
            this.buildingAssignment = buildingAssignment;
            this.isBathCleaning = isBathCleaning;
            this.minRooms = minRooms;
            this.maxRooms = maxRooms;
        }

        StaffConstraintInfo() {
            this.constraintType = "制限なし";
            this.buildingAssignment = "制限なし";
            this.isBathCleaning = false;
            this.minRooms = 0;
            this.maxRooms = 99;
        }
    }

    public NormalRoomDistributionDialog(JFrame parent,
                                        int totalMainRooms,
                                        int totalAnnexRooms,
                                        List<RoomAssignmentApplication.StaffPointConstraint> staffConstraints,
                                        List<String> staffNames,
                                        AdaptiveRoomOptimizer.BathCleaningType bathCleaningType) {
        super(parent, "通常清掃部屋割り振り設定", true);

        this.totalMainRooms = totalMainRooms;
        this.totalAnnexRooms = totalAnnexRooms;
        this.staffConstraints = staffConstraints;
        this.staffNames = staffNames;
        this.bathCleaningType = bathCleaningType;

        calculateDistributionPatterns();
        this.currentPattern = deepCopyPattern(oneDiffPattern);

        initializeGUI();
        setSize(1000, 600);
        setLocationRelativeTo(parent);
    }

    // 後方互換性のためのコンストラクタ
    public NormalRoomDistributionDialog(JFrame parent,
                                        int totalMainRooms,
                                        int totalAnnexRooms,
                                        List<RoomAssignmentApplication.StaffPointConstraint> staffConstraints,
                                        List<String> staffNames) {
        this(parent, totalMainRooms, totalAnnexRooms, staffConstraints, staffNames,
                AdaptiveRoomOptimizer.BathCleaningType.NORMAL);
    }

    private void initializeGUI() {
        setLayout(new BorderLayout());
        add(createHeaderPanel(), BorderLayout.NORTH);
        add(createTablePanel(), BorderLayout.CENTER);
        add(createButtonPanel(), BorderLayout.SOUTH);
    }

    private JPanel createHeaderPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 5, 10));

        JLabel titleLabel = new JLabel("通常清掃部屋割り振り設定");
        titleLabel.setFont(new Font("MS Gothic", Font.BOLD, 16));
        panel.add(titleLabel, BorderLayout.NORTH);

        JPanel infoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        infoPanel.add(new JLabel(String.format("本館: %d室, 別館: %d室, 合計: %d室",
                totalMainRooms, totalAnnexRooms, totalMainRooms + totalAnnexRooms)));

        infoPanel.add(new JLabel(" | 大浴場清掃: " + bathCleaningType.displayName + " (-" + bathCleaningType.reduction + "部屋)"));

        JPanel selectorPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        selectorPanel.add(new JLabel("割り振りパターン:"));
        patternSelector = new JComboBox<>(new String[]{"1部屋差パターン", "2部屋差パターン"});
        patternSelector.addActionListener(e -> switchPattern());
        selectorPanel.add(patternSelector);

        infoPanel.add(selectorPanel);
        panel.add(infoPanel, BorderLayout.CENTER);

        summaryLabel = new JLabel();
        summaryLabel.setFont(new Font("MS Gothic", Font.PLAIN, 12));
        panel.add(summaryLabel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createTablePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        String[] columnNames = {
                "スタッフ名", "制約タイプ", "お風呂清掃", "本館部屋数", "本館調整",
                "別館部屋数", "別館調整", "合計"
        };

        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 4 || column == 6;
            }
        };

        distributionTable = new JTable(tableModel);
        distributionTable.setRowHeight(25);
        distributionTable.getTableHeader().setReorderingAllowed(false);

        distributionTable.getColumnModel().getColumn(0).setPreferredWidth(100);
        distributionTable.getColumnModel().getColumn(1).setPreferredWidth(120);
        distributionTable.getColumnModel().getColumn(2).setPreferredWidth(80);
        distributionTable.getColumnModel().getColumn(3).setPreferredWidth(80);
        distributionTable.getColumnModel().getColumn(4).setPreferredWidth(80);
        distributionTable.getColumnModel().getColumn(5).setPreferredWidth(80);
        distributionTable.getColumnModel().getColumn(6).setPreferredWidth(80);
        distributionTable.getColumnModel().getColumn(7).setPreferredWidth(60);

        distributionTable.getColumnModel().getColumn(4).setCellRenderer(new ButtonRenderer());
        distributionTable.getColumnModel().getColumn(4).setCellEditor(new ButtonEditor(new JCheckBox(), true));
        distributionTable.getColumnModel().getColumn(6).setCellRenderer(new ButtonRenderer());
        distributionTable.getColumnModel().getColumn(6).setCellEditor(new ButtonEditor(new JCheckBox(), false));

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

        JButton okButton = new JButton("OK");
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

    /**
     * ★修正版: 正しい計算ロジック（ステップ1-12）を実装
     */
    private void calculateDistributionPatterns() {
        oneDiffPattern = calculatePatternWithCorrectLogic(-1);
        twoDiffPattern = calculatePatternWithCorrectLogic(-2);

        System.out.println("=== 1部屋差パターン ===");
        printPatternDebug(oneDiffPattern);
        System.out.println("=== 2部屋差パターン ===");
        printPatternDebug(twoDiffPattern);
    }

    /**
     * ★新規実装: 正しいロジック（ステップ1-12）に従った計算
     */
    private Map<String, StaffDistribution> calculatePatternWithCorrectLogic(int annexDifference) {
        Map<String, StaffDistribution> pattern = new HashMap<>();

        // Step 1: 本館と別館のトータルを計算
        int totalRooms = totalMainRooms + totalAnnexRooms;
        System.out.println("Step 1: 本館" + totalMainRooms + "室 + 別館" + totalAnnexRooms + "室 = 合計" + totalRooms + "室");

        Map<String, StaffConstraintInfo> staffInfo = collectStaffConstraints();
        int totalStaff = staffInfo.size();

        // Step 2: 合計をスタッフのトータルで割る（切り上げ）
        int baseRoomsPerStaff = (int) Math.ceil((double) totalRooms / totalStaff);
        System.out.println("Step 2: 基本部屋数 = " + totalRooms + " ÷ " + totalStaff + " = " + baseRoomsPerStaff + " (切り上げ)");

        // Step 3: 大浴場清掃スタッフに先に割り振る
        Map<String, Integer> bathStaffRooms = new HashMap<>();
        int bathCleaningReduction = bathCleaningType.reduction;

        for (Map.Entry<String, StaffConstraintInfo> entry : staffInfo.entrySet()) {
            if (entry.getValue().isBathCleaning) {
                String staffName = entry.getKey();
                int assignedRooms = baseRoomsPerStaff - bathCleaningReduction;
                bathStaffRooms.put(staffName, Math.max(0, assignedRooms));
                System.out.println("Step 3: " + staffName + " (大浴清掃) = " + assignedRooms + "室 (基本" + baseRoomsPerStaff + " - " + bathCleaningReduction + ")");
            }
        }

        // Step 4: '故障者制限'と'業者制限'がかかるスタッフを割り振る
        for (Map.Entry<String, StaffConstraintInfo> entry : staffInfo.entrySet()) {
            String staffName = entry.getKey();
            StaffConstraintInfo info = entry.getValue();

            if (!info.isBathCleaning && !"制限なし".equals(info.constraintType)) {
                int assignedRooms;
                if ("故障者制限".equals(info.constraintType)) {
                    assignedRooms = info.maxRooms;
                } else {
                    assignedRooms = info.minRooms; // 業者制限の最低値
                }

                if ("本館のみ".equals(info.buildingAssignment)) {
                    pattern.put(staffName, new StaffDistribution("", staffName, info.buildingAssignment,
                            assignedRooms, 0, info.constraintType, false));
                } else if ("別館のみ".equals(info.buildingAssignment)) {
                    pattern.put(staffName, new StaffDistribution("", staffName, info.buildingAssignment,
                            0, assignedRooms, info.constraintType, false));
                } else {
                    pattern.put(staffName, new StaffDistribution("", staffName, "両方",
                            assignedRooms / 2, assignedRooms - assignedRooms / 2, info.constraintType, false));
                }
                System.out.println("Step 4: " + staffName + " (" + info.constraintType + ") = " + assignedRooms + "室");
            }
        }

        // Step 5: 大浴清掃スタッフの担当部屋数の合計からそれぞれの建物の合計を引く
        int bathMainRooms = 0;
        int bathAnnexRooms = 0;

        for (Map.Entry<String, Integer> entry : bathStaffRooms.entrySet()) {
            String staffName = entry.getKey();
            StaffConstraintInfo info = staffInfo.get(staffName);
            int rooms = entry.getValue();

            if ("本館のみ".equals(info.buildingAssignment)) {
                pattern.put(staffName, new StaffDistribution("", staffName, info.buildingAssignment,
                        rooms, 0, info.constraintType, true));
                bathMainRooms += rooms;
            } else if ("別館のみ".equals(info.buildingAssignment)) {
                pattern.put(staffName, new StaffDistribution("", staffName, info.buildingAssignment,
                        0, rooms, info.constraintType, true));
                bathAnnexRooms += rooms;
            } else {
                // 両方の場合は本館に配置
                pattern.put(staffName, new StaffDistribution("", staffName, "本館のみ",
                        rooms, 0, info.constraintType, true));
                bathMainRooms += rooms;
            }
        }

        // 制約スタッフの部屋数も計算
        int constraintMainRooms = 0;
        int constraintAnnexRooms = 0;
        for (StaffDistribution dist : pattern.values()) {
            if (!dist.isBathCleaning && !"制限なし".equals(dist.constraintType)) {
                constraintMainRooms += dist.mainAssignedRooms;
                constraintAnnexRooms += dist.annexAssignedRooms;
            }
        }

        int remainingMainRooms = totalMainRooms - bathMainRooms - constraintMainRooms;
        int remainingAnnexRooms = totalAnnexRooms - bathAnnexRooms - constraintAnnexRooms;

        System.out.println("Step 5: 残り本館=" + remainingMainRooms + "室, 残り別館=" + remainingAnnexRooms + "室");

        // 建物指定のない通常スタッフを取得
        List<String> normalStaff = new ArrayList<>();
        for (Map.Entry<String, StaffConstraintInfo> entry : staffInfo.entrySet()) {
            StaffConstraintInfo info = entry.getValue();
            if (!info.isBathCleaning && "制限なし".equals(info.constraintType) &&
                    !"本館のみ".equals(info.buildingAssignment) && !"別館のみ".equals(info.buildingAssignment)) {
                normalStaff.add(entry.getKey());
            }
        }

        if (normalStaff.isEmpty()) {
            return pattern;
        }

        // Step 6: 本館から計算していく
        int mainStaffNeeded = (int) Math.ceil((double) remainingMainRooms / baseRoomsPerStaff);
        mainStaffNeeded = Math.min(mainStaffNeeded, normalStaff.size());
        System.out.println("Step 6: 本館に必要なスタッフ数 = " + mainStaffNeeded);

        // Step 7: 建物指定のないスタッフから選出してstep2で計算した部屋数を当てる
        for (int i = 0; i < mainStaffNeeded; i++) {
            String staffName = normalStaff.get(i);
            pattern.put(staffName, new StaffDistribution("", staffName, "本館のみ",
                    baseRoomsPerStaff, 0, "制限なし", false));
            System.out.println("Step 7: " + staffName + " (本館) = " + baseRoomsPerStaff + "室");
        }

        // Step 8: 本館スタッフの担当部屋の合計を計算
        int actualMainTotal = pattern.values().stream()
                .filter(d -> d.mainAssignedRooms > 0)
                .mapToInt(d -> d.mainAssignedRooms)
                .sum();

        System.out.println("Step 8: 本館スタッフ合計=" + actualMainTotal + "室, 実際必要=" + totalMainRooms + "室");

        // Step 9: 実際清掃すべき本館の部屋数より多い場合、一人選んで調整
        if (actualMainTotal > totalMainRooms) {
            int excess = actualMainTotal - totalMainRooms;
            for (StaffDistribution dist : pattern.values()) {
                if (dist.mainAssignedRooms > 0 && "制限なし".equals(dist.constraintType) && !dist.isBathCleaning) {
                    int reduction = Math.min(excess, dist.mainAssignedRooms);
                    dist.mainAssignedRooms -= reduction;
                    dist.updateTotal();
                    excess -= reduction;
                    System.out.println("Step 9: " + dist.staffName + " の本館部屋数を" + reduction + "部屋減らす");
                    if (excess <= 0) break;
                }
            }
        }

        // Step 10: 別館に残りのスタッフを割り振る
        int annexRoomsPerStaff = baseRoomsPerStaff + annexDifference;
        for (int i = mainStaffNeeded; i < normalStaff.size(); i++) {
            String staffName = normalStaff.get(i);
            pattern.put(staffName, new StaffDistribution("", staffName, "別館のみ",
                    0, Math.max(0, annexRoomsPerStaff), "制限なし", false));
            System.out.println("Step 10: " + staffName + " (別館) = " + annexRoomsPerStaff + "室");
        }

        // Step 11: 別館スタッフの部屋合計と実際清掃すべき部屋の合計を出す
        int actualAnnexTotal = pattern.values().stream()
                .filter(d -> d.annexAssignedRooms > 0)
                .mapToInt(d -> d.annexAssignedRooms)
                .sum();

        System.out.println("Step 11: 別館スタッフ合計=" + actualAnnexTotal + "室, 実際必要=" + totalAnnexRooms + "室");

        // Step 12: 不足の場合の調整処理
        if (actualAnnexTotal < totalAnnexRooms) {
            adjustForShortfall(pattern, staffInfo, totalAnnexRooms - actualAnnexTotal, baseRoomsPerStaff);
        }

        return pattern;
    }

    /**
     * 別館部屋不足時の調整処理
     */
    private void adjustForShortfall(Map<String, StaffDistribution> pattern,
                                    Map<String, StaffConstraintInfo> staffInfo,
                                    int shortfall, int baseRoomsPerStaff) {

        // 手順1: 業者制限があるスタッフの上限まで割り当て
        for (StaffDistribution dist : pattern.values()) {
            if (shortfall <= 0) break;

            StaffConstraintInfo info = staffInfo.get(dist.staffName);
            if ("業者制限".equals(info.constraintType) && dist.annexAssignedRooms > 0) {
                int maxIncrease = info.maxRooms - dist.assignedRooms;
                int increase = Math.min(shortfall, maxIncrease);
                if (increase > 0) {
                    dist.annexAssignedRooms += increase;
                    dist.updateTotal();
                    shortfall -= increase;
                    System.out.println("手順1: " + dist.staffName + " の別館部屋数を" + increase + "部屋増やす");
                }
            }
        }

        // 手順2: 本館の制限なしスタッフから選出
        if (shortfall > 0) {
            // 別館の業者制限なしスタッフの最大部屋数を取得
            final int maxAnnexRooms = pattern.values().stream()
                    .filter(d -> d.annexAssignedRooms > 0)
                    .filter(d -> "制限なし".equals(staffInfo.get(d.staffName).constraintType))
                    .mapToInt(d -> d.annexAssignedRooms)
                    .max()
                    .orElse(0);

            final int targetRooms = maxAnnexRooms - 1; // -1部屋にする

            List<StaffDistribution> candidates = pattern.values().stream()
                    .filter(d -> d.mainAssignedRooms > 0)
                    .filter(d -> "制限なし".equals(staffInfo.get(d.staffName).constraintType))
                    .filter(d -> !d.isBathCleaning)
                    .filter(d -> d.mainAssignedRooms < baseRoomsPerStaff) // 他より少ない
                    .collect(Collectors.toList());

            for (StaffDistribution candidate : candidates) {
                if (shortfall <= 0) break;

                int roomsToAdd = Math.min(shortfall, targetRooms);
                if (roomsToAdd > 0) {
                    candidate.annexAssignedRooms = roomsToAdd;
                    candidate.buildingAssignment = "両方";
                    candidate.updateTotal();
                    shortfall -= roomsToAdd;
                    System.out.println("手順2: " + candidate.staffName + " に別館" + roomsToAdd + "室を追加（両方担当）");
                }
            }
        }
    }

    private Map<String, StaffConstraintInfo> collectStaffConstraints() {
        Map<String, StaffConstraintInfo> staffInfo = new HashMap<>();

        for (String staffName : staffNames) {
            RoomAssignmentApplication.StaffPointConstraint constraint =
                    staffConstraints.stream()
                            .filter(c -> staffName.equals(c.staffName))
                            .findFirst()
                            .orElse(null);

            if (constraint != null) {
                int minRooms = 0;
                int maxRooms = 99;

                if (constraint.constraintType == RoomAssignmentApplication.StaffPointConstraint.ConstraintType.UPPER_LIMIT) {
                    maxRooms = (int)constraint.upperLimit;
                } else if (constraint.constraintType == RoomAssignmentApplication.StaffPointConstraint.ConstraintType.LOWER_RANGE) {
                    minRooms = (int)constraint.lowerMinLimit;
                    maxRooms = (int)constraint.lowerMaxLimit;
                }

                staffInfo.put(staffName, new StaffConstraintInfo(
                        constraint.constraintType.displayName,
                        constraint.buildingAssignment.displayName,
                        constraint.isBathCleaningStaff,
                        minRooms,
                        maxRooms
                ));
            } else {
                staffInfo.put(staffName, new StaffConstraintInfo());
            }
        }

        return staffInfo;
    }

    private void printPatternDebug(Map<String, StaffDistribution> pattern) {
        int maxMain = pattern.values().stream().mapToInt(d -> d.mainAssignedRooms).max().orElse(0);
        int maxAnnex = pattern.values().stream().mapToInt(d -> d.annexAssignedRooms).max().orElse(0);
        int diff = maxMain - maxAnnex;
        System.out.println("本館最大: " + maxMain + ", 別館最大: " + maxAnnex + ", 差: " + diff);
        pattern.values().forEach(d ->
                System.out.println("  " + d.staffName + ": 本館" + d.mainAssignedRooms + " 別館" + d.annexAssignedRooms)
        );
    }

    private void switchPattern() {
        if (distributionTable.isEditing()) {
            distributionTable.getCellEditor().stopCellEditing();
        }

        String selected = (String) patternSelector.getSelectedItem();
        if ("1部屋差パターン".equals(selected)) {
            currentPattern = deepCopyPattern(oneDiffPattern);
        } else {
            currentPattern = deepCopyPattern(twoDiffPattern);
        }
        updateTable();
    }

    private void updateTable() {
        tableModel.setRowCount(0);

        for (StaffDistribution dist : currentPattern.values()) {
            tableModel.addRow(new Object[]{
                    dist.staffName,
                    dist.constraintType,
                    dist.isBathCleaning ? "○" : "",
                    dist.mainAssignedRooms,
                    "調整",
                    dist.annexAssignedRooms,
                    "調整",
                    dist.assignedRooms
            });
        }

        updateSummary();
    }

    private void updateSummary() {
        int totalAssignedMain = currentPattern.values().stream()
                .mapToInt(d -> d.mainAssignedRooms).sum();
        int totalAssignedAnnex = currentPattern.values().stream()
                .mapToInt(d -> d.annexAssignedRooms).sum();

        int maxMain = currentPattern.values().stream()
                .mapToInt(d -> d.mainAssignedRooms).max().orElse(0);
        int maxAnnex = currentPattern.values().stream()
                .mapToInt(d -> d.annexAssignedRooms).max().orElse(0);

        int roomDiff = maxMain - maxAnnex;

        String summaryText = String.format(
                "割り当て済み: 本館 %d/%d室, 別館 %d/%d室 | 部屋数差: %d室 (本館最大%d - 別館最大%d)",
                totalAssignedMain, totalMainRooms,
                totalAssignedAnnex, totalAnnexRooms,
                roomDiff, maxMain, maxAnnex
        );

        summaryLabel.setText(summaryText);
    }

    private void resetPattern() {
        String selected = (String) patternSelector.getSelectedItem();
        if ("1部屋差パターン".equals(selected)) {
            currentPattern = deepCopyPattern(oneDiffPattern);
        } else {
            currentPattern = deepCopyPattern(twoDiffPattern);
        }
        updateTable();
    }

    private Map<String, StaffDistribution> deepCopyPattern(Map<String, StaffDistribution> original) {
        Map<String, StaffDistribution> copy = new HashMap<>();
        for (Map.Entry<String, StaffDistribution> entry : original.entrySet()) {
            copy.put(entry.getKey(), new StaffDistribution(entry.getValue()));
        }
        return copy;
    }

    public boolean getDialogResult() {
        return dialogResult;
    }

    public Map<String, StaffDistribution> getSelectedPattern() {
        return currentPattern;
    }

    public Map<String, StaffDistribution> getCurrentDistribution() {
        return new HashMap<>(currentPattern);
    }

    // ボタンレンダラーとエディター
    private class ButtonRenderer extends JButton implements TableCellRenderer {
        public ButtonRenderer() {
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus, int row, int column) {
            setText(value != null ? value.toString() : "調整");
            return this;
        }
    }

    private class ButtonEditor extends DefaultCellEditor {
        protected JButton button;
        private String label;
        private boolean clicked;
        private final boolean isMainBuilding;

        public ButtonEditor(JCheckBox checkBox, boolean isMainBuilding) {
            super(checkBox);
            this.isMainBuilding = isMainBuilding;
            button = new JButton();
            button.setOpaque(true);
            button.addActionListener(e -> fireEditingStopped());
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value,
                                                     boolean isSelected, int row, int column) {
            label = value != null ? value.toString() : "調整";
            button.setText(label);
            clicked = true;
            return button;
        }

        @Override
        public Object getCellEditorValue() {
            if (clicked) {
                int currentRow = distributionTable.getSelectedRow();
                if (currentRow >= 0) {
                    String staffName = (String) tableModel.getValueAt(currentRow, 0);
                    StaffDistribution staff = currentPattern.get(staffName);

                    if (staff != null) {
                        String input = JOptionPane.showInputDialog(
                                distributionTable,
                                (isMainBuilding ? "本館" : "別館") + "の部屋数を入力してください:",
                                isMainBuilding ? staff.mainAssignedRooms : staff.annexAssignedRooms
                        );

                        if (input != null) {
                            try {
                                int newRooms = Integer.parseInt(input);
                                if (newRooms >= 0) {
                                    if (isMainBuilding) {
                                        staff.mainAssignedRooms = newRooms;
                                    } else {
                                        staff.annexAssignedRooms = newRooms;
                                    }
                                    staff.updateTotal();
                                    updateTable();
                                }
                            } catch (NumberFormatException ex) {
                                JOptionPane.showMessageDialog(distributionTable, "数値を入力してください。");
                            }
                        }
                    }
                }
                clicked = false;
            }
            return label;
        }

        @Override
        public boolean stopCellEditing() {
            clicked = false;
            return super.stopCellEditing();
        }

        @Override
        protected void fireEditingStopped() {
            super.fireEditingStopped();
        }
    }
}