package org.example;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 通常清掃部屋割り振りダイアログ - 最終版
 *
 * ★★新機能（追加）★★:
 * 1. 別館ツインの換算処理（12部屋超→1.5倍、18部屋超→1.7倍）をStep1のトータル計算前に実施
 * 2. 「本館部屋数」「別館部屋数」を「本館シングル等」「本館ツイン」「別館シングル等」「別館ツイン」に分割
 *    - 本館ツイン：roomTypeCode = 'T' or 'NT'
 *    - 別館ツイン：roomTypeCode = 'ANT' or 'ADT'
 * 3. ツイン合計値換算（2部屋=3換算、3部屋=5換算、以降+1）
 * 4. 換算値合計と実際合計の分離表示
 * 5. ツイン部屋の均等分配ロジック追加
 */
public class NormalRoomDistributionDialog extends JDialog {

    private JComboBox<String> patternSelector;
    private JLabel summaryLabel;

    private Map<String, StaffDistribution> oneDiffPattern;
    private Map<String, StaffDistribution> twoDiffPattern;
    private Map<String, StaffDistribution> currentPattern;

    // ★既存フィールド（後方互換性のため保持）
    private int totalMainRooms;
    private int totalAnnexRooms;

    // ★★新規フィールド（追加）: 4区分の部屋数
    // ツイン判定：本館ツイン='T'/'NT', 別館ツイン='ANT'/'ADT'
    private int totalMainSingleRooms;    // 本館シングル等（ツイン以外）
    private int totalMainTwinRooms;      // 本館ツイン（'T'/'NT'）
    private int totalAnnexSingleRooms;   // 別館シングル等（ツイン以外）
    private int totalAnnexTwinRooms;     // 別館ツイン（'ANT'/'ADT'）

    private List<RoomAssignmentApplication.StaffPointConstraint> staffConstraints;
    private List<String> staffNames;
    private AdaptiveRoomOptimizer.BathCleaningType bathCleaningType;

    private boolean dialogResult = false;
    private JPanel dataPanel;

    /**
     * ★拡張版: スタッフ割り振り情報
     * 本館/別館それぞれにシングル等/ツインのフィールドを追加
     */
    public static class StaffDistribution {
        public final String staffId;
        public final String staffName;
        public String buildingAssignment;

        // ★既存フィールド（後方互換性のため保持）
        public int mainAssignedRooms;
        public int annexAssignedRooms;
        public int assignedRooms;

        // ★★新規フィールド（追加）: 4区分
        // 本館ツイン='T'/'NT', 別館ツイン='ANT'/'ADT'で判定
        public int mainSingleAssignedRooms;   // 本館シングル等
        public int mainTwinAssignedRooms;     // 本館ツイン（'T'/'NT'）
        public int annexSingleAssignedRooms;  // 別館シングル等
        public int annexTwinAssignedRooms;    // 別館ツイン（'ANT'/'ADT'）

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

            // ★★初期値: 後で正しく設定される
            this.mainSingleAssignedRooms = 0;
            this.mainTwinAssignedRooms = 0;
            this.annexSingleAssignedRooms = 0;
            this.annexTwinAssignedRooms = 0;
        }

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
            this.mainSingleAssignedRooms = 0;
            this.mainTwinAssignedRooms = 0;
            this.annexSingleAssignedRooms = 0;
            this.annexTwinAssignedRooms = 0;
        }

        public StaffDistribution(StaffDistribution other) {
            this.staffId = other.staffId;
            this.staffName = other.staffName;
            this.buildingAssignment = other.buildingAssignment;
            this.mainAssignedRooms = other.mainAssignedRooms;
            this.annexAssignedRooms = other.annexAssignedRooms;
            this.assignedRooms = other.assignedRooms;
            this.constraintType = other.constraintType;
            this.isBathCleaning = other.isBathCleaning;
            this.mainSingleAssignedRooms = other.mainSingleAssignedRooms;
            this.mainTwinAssignedRooms = other.mainTwinAssignedRooms;
            this.annexSingleAssignedRooms = other.annexSingleAssignedRooms;
            this.annexTwinAssignedRooms = other.annexTwinAssignedRooms;
        }

        public void updateTotal() {
            this.mainAssignedRooms = this.mainSingleAssignedRooms + this.mainTwinAssignedRooms;
            this.annexAssignedRooms = this.annexSingleAssignedRooms + this.annexTwinAssignedRooms;
            this.assignedRooms = this.mainAssignedRooms + this.annexAssignedRooms;
        }

        // ★★新規メソッド: 換算値合計を計算
        // 本館ツイン('T'/'NT')と別館ツイン('ANT'/'ADT')を換算値で計算
        public double getConvertedTotal() {
            double mainTwinConverted = calculateTwinConversion(this.mainTwinAssignedRooms);
            double annexTwinConverted = calculateTwinConversion(this.annexTwinAssignedRooms);
            return this.mainSingleAssignedRooms + mainTwinConverted +
                    this.annexSingleAssignedRooms + annexTwinConverted;
        }

        // ★★新規メソッド: ツイン換算計算（2部屋=3換算、3部屋=5換算、それ以降+1）
        public static double calculateTwinConversion(int twinRooms) {
            if (twinRooms == 0) return 0.0;
            if (twinRooms == 1) return 1.0;
            if (twinRooms == 2) return 3.0;
            if (twinRooms == 3) return 5.0;
            return 5.0 + (twinRooms - 3);
        }
    }

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

    /**
     * ★★新規コンストラクタ（推奨）: 4区分の部屋数を受け取る
     *
     * @param totalMainSingleRooms 本館シングル等の部屋数
     * @param totalMainTwinRooms 本館ツイン（'T'/'NT'）の部屋数
     * @param totalAnnexSingleRooms 別館シングル等の部屋数
     * @param totalAnnexTwinRooms 別館ツイン（'ANT'/'ADT'）の部屋数
     *
     * 呼び出し側でFileProcessorを使用してツイン判定を行い、4区分に集計してから渡すこと：
     * - 本館ツイン：roomTypeCode.equals("T") || roomTypeCode.equals("NT")
     * - 別館ツイン：roomTypeCode.equals("ANT") || roomTypeCode.equals("ADT")
     */
    public NormalRoomDistributionDialog(JFrame parent,
                                        int totalMainSingleRooms,
                                        int totalMainTwinRooms,
                                        int totalAnnexSingleRooms,
                                        int totalAnnexTwinRooms,
                                        List<RoomAssignmentApplication.StaffPointConstraint> staffConstraints,
                                        List<String> staffNames,
                                        AdaptiveRoomOptimizer.BathCleaningType bathCleaningType) {
        super(parent, "通常清掃部屋割り振り設定", true);

        // ★★4区分の部屋数を設定
        this.totalMainSingleRooms = totalMainSingleRooms;
        this.totalMainTwinRooms = totalMainTwinRooms;
        this.totalAnnexSingleRooms = totalAnnexSingleRooms;
        this.totalAnnexTwinRooms = totalAnnexTwinRooms;

        // 後方互換性のため設定
        this.totalMainRooms = totalMainSingleRooms + totalMainTwinRooms;
        this.totalAnnexRooms = totalAnnexSingleRooms + totalAnnexTwinRooms;

        this.staffConstraints = staffConstraints;
        this.staffNames = staffNames;
        this.bathCleaningType = bathCleaningType;

        calculateDistributionPatterns();
        this.currentPattern = deepCopyPattern(oneDiffPattern);

        initializeGUI();
        setSize(1400, 750);
        setLocationRelativeTo(parent);
    }

    /**
     * ★既存コンストラクタ（後方互換性のみ）
     * 注意：このコンストラクタでは4区分の詳細が不明なため、
     * 新しい機能（ツイン換算、均等分配）が正しく動作しません。
     * 可能な限り4区分のコンストラクタを使用してください。
     */
    @Deprecated
    public NormalRoomDistributionDialog(JFrame parent,
                                        int totalMainRooms,
                                        int totalAnnexRooms,
                                        List<RoomAssignmentApplication.StaffPointConstraint> staffConstraints,
                                        List<String> staffNames,
                                        AdaptiveRoomOptimizer.BathCleaningType bathCleaningType) {
        super(parent, "通常清掃部屋割り振り設定", true);

        this.totalMainRooms = totalMainRooms;
        this.totalAnnexRooms = totalAnnexRooms;

        // ★★警告：詳細不明のため全てシングル等として扱う
        // 正しく動作させるには4区分のコンストラクタを使用すること
        System.err.println("警告: 旧形式のコンストラクタが使用されています。4区分のコンストラクタの使用を推奨します。");
        this.totalMainSingleRooms = totalMainRooms;
        this.totalMainTwinRooms = 0;
        this.totalAnnexSingleRooms = totalAnnexRooms;
        this.totalAnnexTwinRooms = 0;

        this.staffConstraints = staffConstraints;
        this.staffNames = staffNames;
        this.bathCleaningType = bathCleaningType;

        calculateDistributionPatterns();
        this.currentPattern = deepCopyPattern(oneDiffPattern);

        initializeGUI();
        setSize(1400, 750);
        setLocationRelativeTo(parent);
    }

    @Deprecated
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

        // ★★変更: 4区分の内訳表示
        // 本館ツイン='T'/'NT', 別館ツイン='ANT'/'ADT'
        infoPanel.add(new JLabel(String.format(
                "本館: S%d+T%d=%d室, 別館: S%d+T%d=%d室, 合計: %d室",
                totalMainSingleRooms, totalMainTwinRooms, totalMainRooms,
                totalAnnexSingleRooms, totalAnnexTwinRooms, totalAnnexRooms,
                totalMainRooms + totalAnnexRooms
        )));

        // ★★追加: 別館ツイン('ANT'/'ADT')の換算情報表示
        if (totalAnnexTwinRooms > 0) {
            double multiplier = getAnnexTwinMultiplier(totalAnnexTwinRooms);
            if (multiplier > 1.0) {
                infoPanel.add(new JLabel(String.format(" | 別館ツイン負荷: ×%.1f", multiplier)));
            }
        }

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

    // ★★変更: テーブルヘッダーを4列に変更
    private JPanel createTablePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        JPanel tablePanel = new JPanel(new BorderLayout());

        JPanel headerPanel = new JPanel(new GridLayout(1, 9));
        headerPanel.setBackground(UIManager.getColor("TableHeader.background"));
        headerPanel.setBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, Color.GRAY));

        String[] headers = {"スタッフ名", "制限タイプ", "お風呂", "本館シングル等", "本館ツイン", "別館シングル等", "別館ツイン", "実際合計", "換算合計"};
        int[] widths = {100, 100, 60, 120, 100, 120, 100, 80, 100};

        for (int i = 0; i < headers.length; i++) {
            JLabel headerLabel = new JLabel(headers[i], JLabel.CENTER);
            headerLabel.setFont(new Font("MS Gothic", Font.BOLD, 11));
            headerLabel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            headerLabel.setPreferredSize(new Dimension(widths[i], 25));
            headerPanel.add(headerLabel);
        }

        tablePanel.add(headerPanel, BorderLayout.NORTH);

        dataPanel = new JPanel();
        dataPanel.setLayout(new BoxLayout(dataPanel, BoxLayout.Y_AXIS));

        updateDataPanel();

        JScrollPane scrollPane = new JScrollPane(dataPanel);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        tablePanel.add(scrollPane, BorderLayout.CENTER);

        panel.add(tablePanel, BorderLayout.CENTER);

        return panel;
    }

    private void updateDataPanel() {
        dataPanel.removeAll();

        List<StaffDistribution> sortedStaff = new ArrayList<>(currentPattern.values());

        sortedStaff.sort((s1, s2) -> {
            if (s1.isBathCleaning != s2.isBathCleaning) {
                return s1.isBathCleaning ? -1 : 1;
            }
            boolean s1IsBroken = "故障者制限".equals(s1.constraintType);
            boolean s2IsBroken = "故障者制限".equals(s2.constraintType);
            if (s1IsBroken != s2IsBroken) {
                return s1IsBroken ? -1 : 1;
            }
            int s1BuildingPriority = getBuildingPriority(s1);
            int s2BuildingPriority = getBuildingPriority(s2);
            if (s1BuildingPriority != s2BuildingPriority) {
                return Integer.compare(s1BuildingPriority, s2BuildingPriority);
            }
            boolean s1IsVendor = "業者制限".equals(s1.constraintType);
            boolean s2IsVendor = "業者制限".equals(s2.constraintType);
            if (s1IsVendor != s2IsVendor) {
                return s1IsVendor ? -1 : 1;
            }
            return s1.staffName.compareTo(s2.staffName);
        });

        for (StaffDistribution staff : sortedStaff) {
            JPanel rowPanel = new JPanel(new GridLayout(1, 9));
            rowPanel.setBorder(BorderFactory.createMatteBorder(0, 1, 1, 1, Color.GRAY));
            rowPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 35));
            rowPanel.setPreferredSize(new Dimension(0, 35));

            // スタッフ名
            JLabel nameLabel = new JLabel(staff.staffName, JLabel.CENTER);
            nameLabel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            rowPanel.add(nameLabel);

            // 制限タイプ
            JLabel constraintLabel = new JLabel(staff.constraintType, JLabel.CENTER);
            constraintLabel.setFont(new Font("MS Gothic", Font.PLAIN, 10));
            constraintLabel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            rowPanel.add(constraintLabel);

            // お風呂清掃
            JLabel bathLabel = new JLabel(staff.isBathCleaning ? "○" : "", JLabel.CENTER);
            bathLabel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            rowPanel.add(bathLabel);

            // ★★追加: 本館シングル等スピナー
            JPanel mainSinglePanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 3, 0));
            mainSinglePanel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            SpinnerNumberModel mainSingleModel = new SpinnerNumberModel(staff.mainSingleAssignedRooms, 0, 99, 1);
            JSpinner mainSingleSpinner = new JSpinner(mainSingleModel);
            mainSingleSpinner.setPreferredSize(new Dimension(70, 25));
            mainSingleSpinner.addChangeListener(e -> {
                staff.mainSingleAssignedRooms = (int) mainSingleSpinner.getValue();
                staff.updateTotal();
                updateRowDisplay(rowPanel, staff);
                updateSummary();
            });
            mainSinglePanel.add(mainSingleSpinner);
            rowPanel.add(mainSinglePanel);

            // ★★追加: 本館ツイン('T'/'NT')スピナー
            JPanel mainTwinPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 3, 0));
            mainTwinPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            SpinnerNumberModel mainTwinModel = new SpinnerNumberModel(staff.mainTwinAssignedRooms, 0, 99, 1);
            JSpinner mainTwinSpinner = new JSpinner(mainTwinModel);
            mainTwinSpinner.setPreferredSize(new Dimension(70, 25));
            mainTwinSpinner.addChangeListener(e -> {
                staff.mainTwinAssignedRooms = (int) mainTwinSpinner.getValue();
                staff.updateTotal();
                updateRowDisplay(rowPanel, staff);
                updateSummary();
            });
            mainTwinPanel.add(mainTwinSpinner);
            rowPanel.add(mainTwinPanel);

            // ★★追加: 別館シングル等スピナー
            JPanel annexSinglePanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 3, 0));
            annexSinglePanel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            SpinnerNumberModel annexSingleModel = new SpinnerNumberModel(staff.annexSingleAssignedRooms, 0, 99, 1);
            JSpinner annexSingleSpinner = new JSpinner(annexSingleModel);
            annexSingleSpinner.setPreferredSize(new Dimension(70, 25));
            annexSingleSpinner.addChangeListener(e -> {
                staff.annexSingleAssignedRooms = (int) annexSingleSpinner.getValue();
                staff.updateTotal();
                updateRowDisplay(rowPanel, staff);
                updateSummary();
            });
            annexSinglePanel.add(annexSingleSpinner);
            rowPanel.add(annexSinglePanel);

            // ★★追加: 別館ツイン('ANT'/'ADT')スピナー
            JPanel annexTwinPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 3, 0));
            annexTwinPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            SpinnerNumberModel annexTwinModel = new SpinnerNumberModel(staff.annexTwinAssignedRooms, 0, 99, 1);
            JSpinner annexTwinSpinner = new JSpinner(annexTwinModel);
            annexTwinSpinner.setPreferredSize(new Dimension(70, 25));
            annexTwinSpinner.addChangeListener(e -> {
                staff.annexTwinAssignedRooms = (int) annexTwinSpinner.getValue();
                staff.updateTotal();
                updateRowDisplay(rowPanel, staff);
                updateSummary();
            });
            annexTwinPanel.add(annexTwinSpinner);
            rowPanel.add(annexTwinPanel);

            // ★★追加: 実際合計
            JLabel actualTotalLabel = new JLabel(String.valueOf(staff.assignedRooms), JLabel.CENTER);
            actualTotalLabel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            rowPanel.add(actualTotalLabel);

            // ★★追加: 換算合計
            double convertedTotal = staff.getConvertedTotal();
            JLabel convertedLabel = new JLabel(String.format("%.1f", convertedTotal), JLabel.CENTER);
            convertedLabel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            convertedLabel.setFont(new Font("MS Gothic", Font.BOLD, 11));
            rowPanel.add(convertedLabel);

            dataPanel.add(rowPanel);
        }

        dataPanel.revalidate();
        dataPanel.repaint();
        updateSummary();
    }

    private void updateRowDisplay(JPanel rowPanel, StaffDistribution staff) {
        Component[] components = rowPanel.getComponents();
        // 実際合計更新（index 7）
        if (components.length > 7) {
            ((JLabel)components[7]).setText(String.valueOf(staff.assignedRooms));
        }
        // 換算合計更新（index 8）
        if (components.length > 8) {
            ((JLabel)components[8]).setText(String.format("%.1f", staff.getConvertedTotal()));
        }
    }

    private int getBuildingPriority(StaffDistribution staff) {
        if (staff.mainAssignedRooms > 0 && staff.annexAssignedRooms == 0) {
            return 1;
        } else if (staff.mainAssignedRooms > 0 && staff.annexAssignedRooms > 0) {
            return 2;
        } else if (staff.mainAssignedRooms == 0 && staff.annexAssignedRooms > 0) {
            return 3;
        }
        return 9;
    }

    private JPanel createButtonPanel() {
        JPanel panel = new JPanel(new FlowLayout());

        JButton resetButton = new JButton("リセット");
        resetButton.addActionListener(e -> resetPattern());
        panel.add(resetButton);

        JButton okButton = new JButton("OK");
        okButton.addActionListener(e -> {
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
        oneDiffPattern = calculatePatternWithCorrectLogic(-1);
        twoDiffPattern = calculatePatternWithCorrectLogic(-2);

        System.out.println("=== 1部屋差パターン ===");
        printPatternDebug(oneDiffPattern);
        System.out.println("=== 2部屋差パターン ===");
        printPatternDebug(twoDiffPattern);
    }

    /**
     * ★★新規: 別館ツイン('ANT'/'ADT')換算係数取得
     * 12部屋以下: 1.0倍
     * 12超~18以下: 1.5倍
     * 18超: 1.7倍
     */
    private double getAnnexTwinMultiplier(int annexTwinRooms) {
        if (annexTwinRooms <= 12) return 1.0;
        if (annexTwinRooms <= 18) return 1.5;
        return 1.7;
    }

    /**
     * ★★拡張: Step1に別館ツイン('ANT'/'ADT')換算処理を追加
     * トータル計算の前段階で別館ツインに換算係数を適用
     */
    private Map<String, StaffDistribution> calculatePatternWithCorrectLogic(int annexDifference) {
        Map<String, StaffDistribution> pattern = new HashMap<>();

        // ★★Step 1を拡張: 別館ツイン('ANT'/'ADT')の負荷換算を追加（トータル計算の前段階）
        // 別館ツインの換算係数を適用
        double annexTwinMultiplier = getAnnexTwinMultiplier(totalAnnexTwinRooms);
        double convertedAnnexTwin = totalAnnexTwinRooms * annexTwinMultiplier;

        // 換算後のトータル計算
        // 本館シングル等 + 本館ツイン('T'/'NT') + 別館シングル等 + 別館ツイン('ANT'/'ADT')×換算係数
        double convertedTotalRooms = totalMainSingleRooms + totalMainTwinRooms +
                totalAnnexSingleRooms + convertedAnnexTwin;
        int actualTotalRooms = totalMainRooms + totalAnnexRooms;

        System.out.println("Step 1 (拡張): 本館S" + totalMainSingleRooms + "+T" + totalMainTwinRooms +
                " + 別館S" + totalAnnexSingleRooms + "+T" + totalAnnexTwinRooms +
                "×" + annexTwinMultiplier + "=" + convertedAnnexTwin +
                " = 換算合計" + convertedTotalRooms + "室, 実際合計" + actualTotalRooms + "室");

        Map<String, StaffConstraintInfo> staffInfo = collectStaffConstraints();
        int totalStaff = staffInfo.size();

        // Step 2: 換算合計をスタッフ数で割る
        int baseRoomsPerStaff = (int) Math.ceil(convertedTotalRooms / totalStaff);
        System.out.println("Step 2: 基本部屋数(換算後) = " + convertedTotalRooms + " ÷ " + totalStaff + " = " + baseRoomsPerStaff + " (切り上げ)");

        // Step 3-12: 既存ロジックを保持
        Map<String, Integer> bathStaffRooms = new HashMap<>();
        int bathCleaningReduction = bathCleaningType.reduction;

        for (Map.Entry<String, StaffConstraintInfo> entry : staffInfo.entrySet()) {
            if (entry.getValue().isBathCleaning) {
                String staffName = entry.getKey();
                int assignedRooms = baseRoomsPerStaff - bathCleaningReduction;
                bathStaffRooms.put(staffName, Math.max(0, assignedRooms));
            }
        }

        for (Map.Entry<String, StaffConstraintInfo> entry : staffInfo.entrySet()) {
            String staffName = entry.getKey();
            StaffConstraintInfo info = entry.getValue();

            if (!info.isBathCleaning && !"制限なし".equals(info.constraintType)) {
                int assignedRooms;
                if ("故障者制限".equals(info.constraintType)) {
                    assignedRooms = info.maxRooms;
                } else {
                    assignedRooms = info.minRooms;
                }

                StaffDistribution dist;
                if ("本館のみ".equals(info.buildingAssignment)) {
                    dist = new StaffDistribution("", staffName, info.buildingAssignment,
                            assignedRooms, 0, info.constraintType, false);
                    // ★★本館のみ：ツイン('T'/'NT')を均等分配
                    dist.mainTwinAssignedRooms = Math.min(assignedRooms / 2, totalMainTwinRooms);
                    dist.mainSingleAssignedRooms = assignedRooms - dist.mainTwinAssignedRooms;
                } else if ("別館のみ".equals(info.buildingAssignment)) {
                    dist = new StaffDistribution("", staffName, info.buildingAssignment,
                            0, assignedRooms, info.constraintType, false);
                    // ★★別館のみ：ツイン('ANT'/'ADT')を均等分配
                    dist.annexTwinAssignedRooms = Math.min(assignedRooms / 2, totalAnnexTwinRooms);
                    dist.annexSingleAssignedRooms = assignedRooms - dist.annexTwinAssignedRooms;
                } else {
                    dist = new StaffDistribution("", staffName, "両方",
                            assignedRooms / 2, assignedRooms - assignedRooms / 2, info.constraintType, false);
                    dist.mainTwinAssignedRooms = Math.min(dist.mainAssignedRooms / 2, totalMainTwinRooms / 2);
                    dist.mainSingleAssignedRooms = dist.mainAssignedRooms - dist.mainTwinAssignedRooms;
                    dist.annexTwinAssignedRooms = Math.min(dist.annexAssignedRooms / 2, totalAnnexTwinRooms / 2);
                    dist.annexSingleAssignedRooms = dist.annexAssignedRooms - dist.annexTwinAssignedRooms;
                }
                pattern.put(staffName, dist);
            }
        }

        int bathMainRooms = 0;
        int bathAnnexRooms = 0;

        for (Map.Entry<String, Integer> entry : bathStaffRooms.entrySet()) {
            String staffName = entry.getKey();
            StaffConstraintInfo info = staffInfo.get(staffName);
            int rooms = entry.getValue();

            StaffDistribution dist;
            if ("本館のみ".equals(info.buildingAssignment)) {
                dist = new StaffDistribution("", staffName, info.buildingAssignment,
                        rooms, 0, info.constraintType, true);
                dist.mainTwinAssignedRooms = Math.min(rooms / 2, totalMainTwinRooms);
                dist.mainSingleAssignedRooms = rooms - dist.mainTwinAssignedRooms;
                bathMainRooms += rooms;
            } else if ("別館のみ".equals(info.buildingAssignment)) {
                dist = new StaffDistribution("", staffName, info.buildingAssignment,
                        0, rooms, info.constraintType, true);
                dist.annexTwinAssignedRooms = Math.min(rooms / 2, totalAnnexTwinRooms);
                dist.annexSingleAssignedRooms = rooms - dist.annexTwinAssignedRooms;
                bathAnnexRooms += rooms;
            } else {
                dist = new StaffDistribution("", staffName, "本館のみ",
                        rooms, 0, info.constraintType, true);
                dist.mainTwinAssignedRooms = Math.min(rooms / 2, totalMainTwinRooms);
                dist.mainSingleAssignedRooms = rooms - dist.mainTwinAssignedRooms;
                bathMainRooms += rooms;
            }
            pattern.put(staffName, dist);
        }

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

        int mainStaffNeeded = (int) Math.ceil((double) remainingMainRooms / baseRoomsPerStaff);
        mainStaffNeeded = Math.min(mainStaffNeeded, normalStaff.size());

        // ★★拡張: 本館ツイン('T'/'NT')と別館ツイン('ANT'/'ADT')の均等分配
        int totalMainTwinRemaining = totalMainTwinRooms;
        int totalAnnexTwinRemaining = totalAnnexTwinRooms;

        for (int i = 0; i < mainStaffNeeded; i++) {
            String staffName = normalStaff.get(i);
            StaffDistribution dist = new StaffDistribution("", staffName, "本館のみ",
                    baseRoomsPerStaff, 0, "制限なし", false);

            // ★★本館ツイン('T'/'NT')を均等分配
            int remainingStaff = normalStaff.size();
            int mainTwinForThis = Math.min(
                    (int) Math.ceil((double) totalMainTwinRemaining / remainingStaff),
                    baseRoomsPerStaff
            );
            totalMainTwinRemaining -= mainTwinForThis;

            dist.mainTwinAssignedRooms = mainTwinForThis;
            dist.mainSingleAssignedRooms = baseRoomsPerStaff - mainTwinForThis;

            pattern.put(staffName, dist);
        }

        int actualMainTotal = pattern.values().stream()
                .filter(d -> d.mainAssignedRooms > 0)
                .mapToInt(d -> d.mainAssignedRooms)
                .sum();

        if (actualMainTotal > totalMainRooms) {
            int excess = actualMainTotal - totalMainRooms;
            for (StaffDistribution dist : pattern.values()) {
                if (dist.mainAssignedRooms > 0 && "制限なし".equals(dist.constraintType) && !dist.isBathCleaning) {
                    int reduction = Math.min(excess, dist.mainAssignedRooms);
                    // シングル等から優先的に減らす
                    if (dist.mainSingleAssignedRooms >= reduction) {
                        dist.mainSingleAssignedRooms -= reduction;
                    } else {
                        int remaining = reduction - dist.mainSingleAssignedRooms;
                        dist.mainSingleAssignedRooms = 0;
                        dist.mainTwinAssignedRooms -= remaining;
                    }
                    dist.updateTotal();
                    excess -= reduction;
                    if (excess <= 0) break;
                }
            }
        }

        int annexRoomsPerStaff = baseRoomsPerStaff + annexDifference;

        for (int i = mainStaffNeeded; i < normalStaff.size(); i++) {
            String staffName = normalStaff.get(i);
            StaffDistribution dist = new StaffDistribution("", staffName, "別館のみ",
                    0, Math.max(0, annexRoomsPerStaff), "制限なし", false);

            // ★★別館ツイン('ANT'/'ADT')を均等分配
            int remainingStaff = normalStaff.size() - i;
            int annexTwinForThis = Math.min(
                    (int) Math.ceil((double) totalAnnexTwinRemaining / remainingStaff),
                    annexRoomsPerStaff
            );
            totalAnnexTwinRemaining -= annexTwinForThis;

            dist.annexTwinAssignedRooms = annexTwinForThis;
            dist.annexSingleAssignedRooms = annexRoomsPerStaff - annexTwinForThis;

            pattern.put(staffName, dist);
        }

        int actualAnnexTotal = pattern.values().stream()
                .filter(d -> d.annexAssignedRooms > 0)
                .mapToInt(d -> d.annexAssignedRooms)
                .sum();

        if (actualAnnexTotal < totalAnnexRooms) {
            adjustForShortfall(pattern, staffInfo, totalAnnexRooms - actualAnnexTotal, baseRoomsPerStaff);
        }

        return pattern;
    }

    private void adjustForShortfall(Map<String, StaffDistribution> pattern,
                                    Map<String, StaffConstraintInfo> staffInfo,
                                    int shortfall, int baseRoomsPerStaff) {
        for (StaffDistribution dist : pattern.values()) {
            if (shortfall <= 0) break;

            StaffConstraintInfo info = staffInfo.get(dist.staffName);
            if ("業者制限".equals(info.constraintType) && dist.annexAssignedRooms > 0) {
                int maxIncrease = info.maxRooms - dist.assignedRooms;
                int increase = Math.min(shortfall, maxIncrease);
                if (increase > 0) {
                    dist.annexSingleAssignedRooms += increase;
                    dist.updateTotal();
                    shortfall -= increase;
                }
            }
        }

        if (shortfall > 0) {
            final int maxAnnexRooms = pattern.values().stream()
                    .filter(d -> d.annexAssignedRooms > 0)
                    .filter(d -> "制限なし".equals(staffInfo.get(d.staffName).constraintType))
                    .mapToInt(d -> d.annexAssignedRooms)
                    .max()
                    .orElse(0);

            final int targetRooms = maxAnnexRooms - 1;

            List<StaffDistribution> candidates = pattern.values().stream()
                    .filter(d -> d.mainAssignedRooms > 0)
                    .filter(d -> "制限なし".equals(staffInfo.get(d.staffName).constraintType))
                    .filter(d -> !d.isBathCleaning)
                    .filter(d -> d.mainAssignedRooms < baseRoomsPerStaff)
                    .collect(Collectors.toList());

            for (StaffDistribution candidate : candidates) {
                if (shortfall <= 0) break;

                int roomsToAdd = Math.min(shortfall, targetRooms);
                if (roomsToAdd > 0) {
                    candidate.annexSingleAssignedRooms = roomsToAdd;
                    candidate.buildingAssignment = "両方";
                    candidate.updateTotal();
                    shortfall -= roomsToAdd;
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
        double totalConverted = pattern.values().stream()
                .mapToDouble(StaffDistribution::getConvertedTotal)
                .sum();

        System.out.println("換算合計: " + totalConverted);
        pattern.values().forEach(d ->
                System.out.println("  " + d.staffName + ": 本館(S" + d.mainSingleAssignedRooms +
                        "+T" + d.mainTwinAssignedRooms + ") 別館(S" + d.annexSingleAssignedRooms +
                        "+T" + d.annexTwinAssignedRooms + ") = " + d.assignedRooms +
                        "室 (換算" + String.format("%.1f", d.getConvertedTotal()) + ")")
        );
    }

    private void switchPattern() {
        String selected = (String) patternSelector.getSelectedItem();
        if ("1部屋差パターン".equals(selected)) {
            currentPattern = deepCopyPattern(oneDiffPattern);
        } else {
            currentPattern = deepCopyPattern(twoDiffPattern);
        }
        updateDataPanel();
    }

    // ★★拡張: 4区分の情報を含むサマリー
    private void updateSummary() {
        int totalAssignedMainSingle = currentPattern.values().stream()
                .mapToInt(d -> d.mainSingleAssignedRooms).sum();
        int totalAssignedMainTwin = currentPattern.values().stream()
                .mapToInt(d -> d.mainTwinAssignedRooms).sum();
        int totalAssignedAnnexSingle = currentPattern.values().stream()
                .mapToInt(d -> d.annexSingleAssignedRooms).sum();
        int totalAssignedAnnexTwin = currentPattern.values().stream()
                .mapToInt(d -> d.annexTwinAssignedRooms).sum();

        double totalConvertedRooms = currentPattern.values().stream()
                .mapToDouble(StaffDistribution::getConvertedTotal).sum();

        int totalActual = totalAssignedMainSingle + totalAssignedMainTwin +
                totalAssignedAnnexSingle + totalAssignedAnnexTwin;

        String summaryText = String.format(
                "割り当て済み: 本館S%d+T%d, 別館S%d+T%d | 実際合計: %d室, 換算合計: %.1f室",
                totalAssignedMainSingle, totalAssignedMainTwin,
                totalAssignedAnnexSingle, totalAssignedAnnexTwin,
                totalActual,
                totalConvertedRooms
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
        updateDataPanel();
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
}