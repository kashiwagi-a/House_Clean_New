package org.example;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 通常清掃部屋割り振りダイアログ - ECO対応版
 *
 * ★★新機能（追加）★★:
 * 1. 別館ツインの換算処理（12部屋超→1.5倍、18部屋超→1.7倍）をStep1のトータル計算前に実施
 * 2. 「本館部屋数」「別館部屋数」を「本館シングル等」「本館ツイン」「別館シングル等」「別館ツイン」に分割
 *    - 本館ツイン：roomTypeCode = 'T' or 'NT'
 *    - 別館ツイン：roomTypeCode = 'ANT' or 'ADT'
 * 3. ツイン合計値換算（2部屋=3換算、3部屋=5換算、以降+1）
 * 4. 換算値合計と実際合計の分離表示
 * 5. ツイン部屋の均等分配ロジック追加
 * 6. ★★ECO部屋の均等配分機能追加（換算係数0.2）
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

    // ★★追加: ECO部屋数
    private int totalMainEcoRooms;       // 本館ECO部屋数
    private int totalAnnexEcoRooms;      // 別館ECO部屋数

    private List<RoomAssignmentApplication.StaffPointConstraint> staffConstraints;
    private List<String> staffNames;
    private AdaptiveRoomOptimizer.BathCleaningType bathCleaningType;

    private boolean dialogResult = false;
    private JPanel dataPanel;

    /**
     * ★拡張版: スタッフ割り振り情報
     * 本館/別館それぞれにシングル等/ツイン/ECOのフィールドを追加
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

        // ★★追加: ECO部屋数
        public int mainEcoAssignedRooms;      // 本館ECO
        public int annexEcoAssignedRooms;     // 別館ECO

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
            this.mainEcoAssignedRooms = 0;
            this.annexEcoAssignedRooms = 0;
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
            this.mainEcoAssignedRooms = 0;
            this.annexEcoAssignedRooms = 0;
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
            this.mainEcoAssignedRooms = other.mainEcoAssignedRooms;
            this.annexEcoAssignedRooms = other.annexEcoAssignedRooms;
        }

        public void updateTotal() {
            this.mainAssignedRooms = this.mainSingleAssignedRooms + this.mainTwinAssignedRooms;
            this.annexAssignedRooms = this.annexSingleAssignedRooms + this.annexTwinAssignedRooms;
            this.assignedRooms = this.mainAssignedRooms + this.annexAssignedRooms;
        }

        // ★★新規メソッド: 換算値合計を計算（通常清掃のみ）
        // 本館ツイン('T'/'NT')と別館ツイン('ANT'/'ADT')を換算値で計算
        public double getConvertedTotal() {
            double mainTwinConverted = calculateTwinConversion(this.mainTwinAssignedRooms);
            double annexTwinConverted = calculateTwinConversion(this.annexTwinAssignedRooms);
            return this.mainSingleAssignedRooms + mainTwinConverted +
                    this.annexSingleAssignedRooms + annexTwinConverted;
        }

        // ★★追加: ECOを含めた換算値合計を計算
        // ECOは軽作業なので0.2換算
        public double getConvertedTotalWithEco() {
            double baseConverted = getConvertedTotal();
            double ecoConverted = (this.mainEcoAssignedRooms + this.annexEcoAssignedRooms) * 0.2;
            return baseConverted + ecoConverted;
        }

        // ★★追加: ECOを含めた総部屋数
        public int getTotalRoomsWithEco() {
            return this.assignedRooms + this.mainEcoAssignedRooms + this.annexEcoAssignedRooms;
        }

        // ★★追加: ECO部屋数のみ
        public int getTotalEcoRooms() {
            return this.mainEcoAssignedRooms + this.annexEcoAssignedRooms;
        }

        // ★★新規メソッド: ツイン換算計算（2部屋=3換算、3部屋=5換算、それ以降+1）
        public static double calculateTwinConversion(int twinRooms) {
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
     * ★★新規コンストラクタ（ECO対応・推奨）: 6区分の部屋数を受け取る
     *
     * @param totalMainSingleRooms 本館シングル等の部屋数
     * @param totalMainTwinRooms 本館ツイン（'T'/'NT'）の部屋数
     * @param totalMainEcoRooms 本館ECO部屋数
     * @param totalAnnexSingleRooms 別館シングル等の部屋数
     * @param totalAnnexTwinRooms 別館ツイン（'ANT'/'ADT'）の部屋数
     * @param totalAnnexEcoRooms 別館ECO部屋数
     */
    public NormalRoomDistributionDialog(JFrame parent,
                                        int totalMainSingleRooms,
                                        int totalMainTwinRooms,
                                        int totalMainEcoRooms,
                                        int totalAnnexSingleRooms,
                                        int totalAnnexTwinRooms,
                                        int totalAnnexEcoRooms,
                                        List<RoomAssignmentApplication.StaffPointConstraint> staffConstraints,
                                        List<String> staffNames,
                                        AdaptiveRoomOptimizer.BathCleaningType bathCleaningType) {
        super(parent, "通常清掃部屋割り振り設定", true);

        // ★★4区分の部屋数を設定
        this.totalMainSingleRooms = totalMainSingleRooms;
        this.totalMainTwinRooms = totalMainTwinRooms;
        this.totalAnnexSingleRooms = totalAnnexSingleRooms;
        this.totalAnnexTwinRooms = totalAnnexTwinRooms;

        // ★★ECO部屋数を設定
        this.totalMainEcoRooms = totalMainEcoRooms;
        this.totalAnnexEcoRooms = totalAnnexEcoRooms;

        // 後方互換性のため設定
        this.totalMainRooms = totalMainSingleRooms + totalMainTwinRooms;
        this.totalAnnexRooms = totalAnnexSingleRooms + totalAnnexTwinRooms;

        this.staffConstraints = staffConstraints;
        this.staffNames = staffNames;
        this.bathCleaningType = bathCleaningType;

        calculateDistributionPatterns();
        this.currentPattern = deepCopyPattern(oneDiffPattern);

        initializeGUI();
        setSize(1600, 750);  // ECO列追加のため幅を拡大
        setLocationRelativeTo(parent);
    }

    /**
     * ★★4区分コンストラクタ（ECOなし・後方互換）
     */
    public NormalRoomDistributionDialog(JFrame parent,
                                        int totalMainSingleRooms,
                                        int totalMainTwinRooms,
                                        int totalAnnexSingleRooms,
                                        int totalAnnexTwinRooms,
                                        List<RoomAssignmentApplication.StaffPointConstraint> staffConstraints,
                                        List<String> staffNames,
                                        AdaptiveRoomOptimizer.BathCleaningType bathCleaningType) {
        this(parent, totalMainSingleRooms, totalMainTwinRooms, 0,
                totalAnnexSingleRooms, totalAnnexTwinRooms, 0,
                staffConstraints, staffNames, bathCleaningType);
    }

    /**
     * ★既存コンストラクタ（後方互換性のみ）
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
        System.err.println("警告: 旧形式のコンストラクタが使用されています。6区分のコンストラクタの使用を推奨します。");
        this.totalMainSingleRooms = totalMainRooms;
        this.totalMainTwinRooms = 0;
        this.totalAnnexSingleRooms = totalAnnexRooms;
        this.totalAnnexTwinRooms = 0;
        this.totalMainEcoRooms = 0;
        this.totalAnnexEcoRooms = 0;

        this.staffConstraints = staffConstraints;
        this.staffNames = staffNames;
        this.bathCleaningType = bathCleaningType;

        calculateDistributionPatterns();
        this.currentPattern = deepCopyPattern(oneDiffPattern);

        initializeGUI();
        setSize(1600, 750);
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

        // ★★変更: 6区分の内訳表示（ECO含む）
        infoPanel.add(new JLabel(String.format(
                "本館: S%d+T%d+E%d=%d室, 別館: S%d+T%d+E%d=%d室, 合計: %d室",
                totalMainSingleRooms, totalMainTwinRooms, totalMainEcoRooms,
                totalMainRooms + totalMainEcoRooms,
                totalAnnexSingleRooms, totalAnnexTwinRooms, totalAnnexEcoRooms,
                totalAnnexRooms + totalAnnexEcoRooms,
                totalMainRooms + totalAnnexRooms + totalMainEcoRooms + totalAnnexEcoRooms
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

    // ★★変更: テーブルヘッダーにECO列を追加
    private JPanel createTablePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        JPanel tablePanel = new JPanel(new BorderLayout());

        JPanel headerPanel = new JPanel(new GridLayout(1, 12));
        headerPanel.setBackground(UIManager.getColor("TableHeader.background"));
        headerPanel.setBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, Color.GRAY));

        String[] headers = {"スタッフ名", "制限タイプ", "お風呂",
                "本館S", "本館T", "本館ECO",
                "別館S", "別館T", "別館ECO",
                "通常合計", "ECO合計", "換算合計"};
        int[] widths = {100, 100, 50, 80, 80, 80, 80, 80, 80, 80, 80, 100};

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
            JPanel rowPanel = new JPanel(new GridLayout(1, 12));
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

            // 本館シングル等スピナー
            JPanel mainSinglePanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 0));
            mainSinglePanel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            SpinnerNumberModel mainSingleModel = new SpinnerNumberModel(staff.mainSingleAssignedRooms, 0, 99, 1);
            JSpinner mainSingleSpinner = new JSpinner(mainSingleModel);
            mainSingleSpinner.setPreferredSize(new Dimension(55, 25));
            mainSingleSpinner.addChangeListener(e -> {
                staff.mainSingleAssignedRooms = (int) mainSingleSpinner.getValue();
                staff.updateTotal();
                updateRowDisplay(rowPanel, staff);
                updateSummary();
            });
            mainSinglePanel.add(mainSingleSpinner);
            rowPanel.add(mainSinglePanel);

            // 本館ツイン('T'/'NT')スピナー
            JPanel mainTwinPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 0));
            mainTwinPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            SpinnerNumberModel mainTwinModel = new SpinnerNumberModel(staff.mainTwinAssignedRooms, 0, 99, 1);
            JSpinner mainTwinSpinner = new JSpinner(mainTwinModel);
            mainTwinSpinner.setPreferredSize(new Dimension(55, 25));
            mainTwinSpinner.addChangeListener(e -> {
                staff.mainTwinAssignedRooms = (int) mainTwinSpinner.getValue();
                staff.updateTotal();
                updateRowDisplay(rowPanel, staff);
                updateSummary();
            });
            mainTwinPanel.add(mainTwinSpinner);
            rowPanel.add(mainTwinPanel);

            // ★★追加: 本館ECOスピナー
            JPanel mainEcoPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 0));
            mainEcoPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            SpinnerNumberModel mainEcoModel = new SpinnerNumberModel(staff.mainEcoAssignedRooms, 0, 99, 1);
            JSpinner mainEcoSpinner = new JSpinner(mainEcoModel);
            mainEcoSpinner.setPreferredSize(new Dimension(55, 25));
            mainEcoSpinner.addChangeListener(e -> {
                staff.mainEcoAssignedRooms = (int) mainEcoSpinner.getValue();
                updateRowDisplay(rowPanel, staff);
                updateSummary();
            });
            mainEcoPanel.add(mainEcoSpinner);
            rowPanel.add(mainEcoPanel);

            // 別館シングル等スピナー
            JPanel annexSinglePanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 0));
            annexSinglePanel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            SpinnerNumberModel annexSingleModel = new SpinnerNumberModel(staff.annexSingleAssignedRooms, 0, 99, 1);
            JSpinner annexSingleSpinner = new JSpinner(annexSingleModel);
            annexSingleSpinner.setPreferredSize(new Dimension(55, 25));
            annexSingleSpinner.addChangeListener(e -> {
                staff.annexSingleAssignedRooms = (int) annexSingleSpinner.getValue();
                staff.updateTotal();
                updateRowDisplay(rowPanel, staff);
                updateSummary();
            });
            annexSinglePanel.add(annexSingleSpinner);
            rowPanel.add(annexSinglePanel);

            // 別館ツイン('ANT'/'ADT')スピナー
            JPanel annexTwinPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 0));
            annexTwinPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            SpinnerNumberModel annexTwinModel = new SpinnerNumberModel(staff.annexTwinAssignedRooms, 0, 99, 1);
            JSpinner annexTwinSpinner = new JSpinner(annexTwinModel);
            annexTwinSpinner.setPreferredSize(new Dimension(55, 25));
            annexTwinSpinner.addChangeListener(e -> {
                staff.annexTwinAssignedRooms = (int) annexTwinSpinner.getValue();
                staff.updateTotal();
                updateRowDisplay(rowPanel, staff);
                updateSummary();
            });
            annexTwinPanel.add(annexTwinSpinner);
            rowPanel.add(annexTwinPanel);

            // ★★追加: 別館ECOスピナー
            JPanel annexEcoPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 0));
            annexEcoPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            SpinnerNumberModel annexEcoModel = new SpinnerNumberModel(staff.annexEcoAssignedRooms, 0, 99, 1);
            JSpinner annexEcoSpinner = new JSpinner(annexEcoModel);
            annexEcoSpinner.setPreferredSize(new Dimension(55, 25));
            annexEcoSpinner.addChangeListener(e -> {
                staff.annexEcoAssignedRooms = (int) annexEcoSpinner.getValue();
                updateRowDisplay(rowPanel, staff);
                updateSummary();
            });
            annexEcoPanel.add(annexEcoSpinner);
            rowPanel.add(annexEcoPanel);

            // 通常合計（シングル+ツイン）
            JLabel actualTotalLabel = new JLabel(String.valueOf(staff.assignedRooms), JLabel.CENTER);
            actualTotalLabel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            rowPanel.add(actualTotalLabel);

            // ★★追加: ECO合計
            int ecoTotal = staff.mainEcoAssignedRooms + staff.annexEcoAssignedRooms;
            JLabel ecoTotalLabel = new JLabel(String.valueOf(ecoTotal), JLabel.CENTER);
            ecoTotalLabel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            rowPanel.add(ecoTotalLabel);

            // 換算合計（ECO含む）
            double convertedTotal = staff.getConvertedTotalWithEco();
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
        // 通常合計更新（index 9）
        if (components.length > 9) {
            ((JLabel)components[9]).setText(String.valueOf(staff.assignedRooms));
        }
        // ECO合計更新（index 10）
        if (components.length > 10) {
            int ecoTotal = staff.mainEcoAssignedRooms + staff.annexEcoAssignedRooms;
            ((JLabel)components[10]).setText(String.valueOf(ecoTotal));
        }
        // 換算合計更新（index 11）
        if (components.length > 11) {
            ((JLabel)components[11]).setText(String.format("%.1f", staff.getConvertedTotalWithEco()));
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
            if (validateBeforeOk()) {
                dialogResult = true;
                dispose();
            }
        });
        panel.add(okButton);

        JButton cancelButton = new JButton("キャンセル");
        cancelButton.addActionListener(e -> dispose());
        panel.add(cancelButton);

        return panel;
    }

    private boolean validateBeforeOk() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 割り当て済み部屋数の集計
        int assignedMainSingle = 0;
        int assignedMainTwin = 0;
        int assignedMainEco = 0;
        int assignedAnnexSingle = 0;
        int assignedAnnexTwin = 0;
        int assignedAnnexEco = 0;

        for (StaffDistribution dist : currentPattern.values()) {
            assignedMainSingle += dist.mainSingleAssignedRooms;
            assignedMainTwin += dist.mainTwinAssignedRooms;
            assignedMainEco += dist.mainEcoAssignedRooms;
            assignedAnnexSingle += dist.annexSingleAssignedRooms;
            assignedAnnexTwin += dist.annexTwinAssignedRooms;
            assignedAnnexEco += dist.annexEcoAssignedRooms;
        }

        // 部屋数の不一致チェック（警告として処理）
        if (assignedMainSingle != totalMainSingleRooms) {
            warnings.add(String.format("本館シングル等: 割当%d室 ≠ 実際%d室 (差分%d)",
                    assignedMainSingle, totalMainSingleRooms,
                    assignedMainSingle - totalMainSingleRooms));
        }
        if (assignedMainTwin != totalMainTwinRooms) {
            warnings.add(String.format("本館ツイン: 割当%d室 ≠ 実際%d室 (差分%d)",
                    assignedMainTwin, totalMainTwinRooms,
                    assignedMainTwin - totalMainTwinRooms));
        }
        if (assignedMainEco != totalMainEcoRooms) {
            warnings.add(String.format("本館ECO: 割当%d室 ≠ 実際%d室 (差分%d)",
                    assignedMainEco, totalMainEcoRooms,
                    assignedMainEco - totalMainEcoRooms));
        }
        if (assignedAnnexSingle != totalAnnexSingleRooms) {
            warnings.add(String.format("別館シングル等: 割当%d室 ≠ 実際%d室 (差分%d)",
                    assignedAnnexSingle, totalAnnexSingleRooms,
                    assignedAnnexSingle - totalAnnexSingleRooms));
        }
        if (assignedAnnexTwin != totalAnnexTwinRooms) {
            warnings.add(String.format("別館ツイン: 割当%d室 ≠ 実際%d室 (差分%d)",
                    assignedAnnexTwin, totalAnnexTwinRooms,
                    assignedAnnexTwin - totalAnnexTwinRooms));
        }
        if (assignedAnnexEco != totalAnnexEcoRooms) {
            warnings.add(String.format("別館ECO: 割当%d室 ≠ 実際%d室 (差分%d)",
                    assignedAnnexEco, totalAnnexEcoRooms,
                    assignedAnnexEco - totalAnnexEcoRooms));
        }

        // 大浴場スタッフが本館と別館の両方に割り当てがある場合は警告
        for (StaffDistribution dist : currentPattern.values()) {
            if (dist.isBathCleaning) {
                int mainRooms = dist.mainSingleAssignedRooms + dist.mainTwinAssignedRooms + dist.mainEcoAssignedRooms;
                int annexRooms = dist.annexSingleAssignedRooms + dist.annexTwinAssignedRooms + dist.annexEcoAssignedRooms;
                if (mainRooms > 0 && annexRooms > 0) {
                    warnings.add(String.format("%s: 大浴場清掃担当が本館・別館の両方に割り当てられています",
                            dist.staffName));
                }
            }
        }

        // エラーがある場合はダイアログ表示
        if (!errors.isEmpty()) {
            StringBuilder message = new StringBuilder();
            message.append("以下のエラーがあります。修正してください：\n\n");
            for (String error : errors) {
                message.append("• ").append(error).append("\n");
            }

            JOptionPane.showMessageDialog(this,
                    message.toString(),
                    "検証エラー",
                    JOptionPane.ERROR_MESSAGE);
            return false;
        }

        // 警告がある場合は確認ダイアログ
        if (!warnings.isEmpty()) {
            StringBuilder message = new StringBuilder();
            message.append("以下の警告があります：\n\n");
            for (String warning : warnings) {
                message.append("• ").append(warning).append("\n");
            }
            message.append("\n続行しますか？");

            int result = JOptionPane.showConfirmDialog(this,
                    message.toString(),
                    "警告",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);

            return result == JOptionPane.YES_OPTION;
        }

        return true;
    }

    private void calculateDistributionPatterns() {
        oneDiffPattern = calculatePatternWithCorrectLogic(-1);
        twoDiffPattern = calculatePatternWithCorrectLogic(-2);

        // ★★追加: ECO部屋の配分
        Map<String, StaffConstraintInfo> staffInfo = collectStaffConstraints();
        distributeEcoRooms(oneDiffPattern, staffInfo);
        distributeEcoRooms(twoDiffPattern, staffInfo);

        System.out.println("=== 1部屋差パターン (ECO含む) ===");
        printPatternDebug(oneDiffPattern);
        System.out.println("=== 2部屋差パターン (ECO含む) ===");
        printPatternDebug(twoDiffPattern);
    }

    /**
     * ★★追加: ECO部屋を均等に配分するメソッド
     */
    private void distributeEcoRooms(Map<String, StaffDistribution> pattern,
                                    Map<String, StaffConstraintInfo> staffInfo) {

        System.out.println("=== ECO部屋配分開始 ===");
        System.out.println("本館ECO: " + totalMainEcoRooms + "室, 別館ECO: " + totalAnnexEcoRooms + "室");

        // 本館ECOの配分
        if (totalMainEcoRooms > 0) {
            distributeEcoToBuilding(pattern, staffInfo, totalMainEcoRooms, true);
        }

        // 別館ECOの配分
        if (totalAnnexEcoRooms > 0) {
            distributeEcoToBuilding(pattern, staffInfo, totalAnnexEcoRooms, false);
        }

        // 結果をログ出力
        System.out.println("=== ECO部屋配分結果 ===");
        for (StaffDistribution dist : pattern.values()) {
            if (dist.mainEcoAssignedRooms > 0 || dist.annexEcoAssignedRooms > 0) {
                System.out.println("  " + dist.staffName + ": 本館ECO=" + dist.mainEcoAssignedRooms +
                        ", 別館ECO=" + dist.annexEcoAssignedRooms);
            }
        }
    }

    /**
     * ★★追加: 建物別ECO配分
     */
    private void distributeEcoToBuilding(Map<String, StaffDistribution> pattern,
                                         Map<String, StaffConstraintInfo> staffInfo,
                                         int totalEcoRooms, boolean isMain) {

        String buildingName = isMain ? "本館" : "別館";

        // この建物を担当できるスタッフをフィルタ
        List<StaffDistribution> eligibleStaff = pattern.values().stream()
                .filter(d -> {
                    StaffConstraintInfo info = staffInfo.get(d.staffName);
                    // 建物指定をチェック
                    if (isMain) {
                        return !"別館のみ".equals(info.buildingAssignment);
                    } else {
                        return !"本館のみ".equals(info.buildingAssignment);
                    }
                })
                .filter(d -> {
                    // この建物に通常部屋がある、または制限なしのスタッフ
                    if (isMain) {
                        return d.mainSingleAssignedRooms + d.mainTwinAssignedRooms > 0;
                    } else {
                        return d.annexSingleAssignedRooms + d.annexTwinAssignedRooms > 0;
                    }
                })
                .collect(Collectors.toList());

        if (eligibleStaff.isEmpty()) {
            System.out.println(buildingName + "ECO配分: 対象スタッフなし");
            return;
        }

        // 換算合計が少ない順にソート（負荷の低いスタッフに多くECOを配分）
        eligibleStaff.sort(Comparator.comparingDouble(StaffDistribution::getConvertedTotal));

        // 均等配分
        int baseEco = totalEcoRooms / eligibleStaff.size();
        int remainder = totalEcoRooms % eligibleStaff.size();

        System.out.println(buildingName + "ECO配分: " + eligibleStaff.size() + "名に配分 (基本" + baseEco +
                "室, 余り" + remainder + "室)");

        for (int i = 0; i < eligibleStaff.size(); i++) {
            StaffDistribution dist = eligibleStaff.get(i);
            int ecoToAssign = baseEco + (i < remainder ? 1 : 0);

            if (isMain) {
                dist.mainEcoAssignedRooms = ecoToAssign;
            } else {
                dist.annexEcoAssignedRooms = ecoToAssign;
            }

            System.out.println("  " + dist.staffName + ": " + ecoToAssign + "室");
        }
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
     * ★修正版: 正しいロジック（ステップ1-12）に従った計算
     * ツイン均等配分機能を追加
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

        if (!normalStaff.isEmpty()) {
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
        }

        // =====================================
        // ★★最終段階: 全スタッフへツイン均等配分
        // =====================================

        // 本館担当スタッフのリストを作成
        List<String> mainStaffList = new ArrayList<>();
        for (Map.Entry<String, StaffDistribution> entry : pattern.entrySet()) {
            if (entry.getValue().mainAssignedRooms > 0) {
                mainStaffList.add(entry.getKey());
            }
        }

        // 本館担当スタッフ全員に対して、まず全てシングルとして初期化
        for (String staffName : mainStaffList) {
            StaffDistribution dist = pattern.get(staffName);
            dist.mainSingleAssignedRooms = dist.mainAssignedRooms;
            dist.mainTwinAssignedRooms = 0;
        }

        // ★★本館ツインをラウンドロビンで1部屋ずつ全スタッフに均等配分
        int mainTwinRemaining = totalMainTwinRooms;
        int staffIndex = 0;
        while (mainTwinRemaining > 0 && !mainStaffList.isEmpty()) {
            String staffName = mainStaffList.get(staffIndex % mainStaffList.size());
            StaffDistribution dist = pattern.get(staffName);

            // シングルをツインに変換（担当部屋の上限を超えない範囲で）
            if (dist.mainSingleAssignedRooms > 0) {
                dist.mainSingleAssignedRooms--;
                dist.mainTwinAssignedRooms++;
                mainTwinRemaining--;
            }

            staffIndex++;

            // 無限ループ防止：全スタッフの合計部屋数を超えたら終了
            if (staffIndex >= mainStaffList.size() * 100) {
                System.out.println("警告: 本館ツイン配分で想定外のループ");
                break;
            }
        }

        System.out.println("本館ツイン配分完了: " + mainStaffList.size() + "名に均等配分");

        // 別館担当スタッフのリストを作成
        List<String> annexStaffList = new ArrayList<>();
        for (Map.Entry<String, StaffDistribution> entry : pattern.entrySet()) {
            if (entry.getValue().annexAssignedRooms > 0) {
                annexStaffList.add(entry.getKey());
            }
        }

        // 別館担当スタッフ全員に対して、まず全てシングルとして初期化
        for (String staffName : annexStaffList) {
            StaffDistribution dist = pattern.get(staffName);
            dist.annexSingleAssignedRooms = dist.annexAssignedRooms;
            dist.annexTwinAssignedRooms = 0;
        }

        // ★★別館ツインをラウンドロビンで1部屋ずつ全スタッフに均等配分
        int annexTwinRemaining = totalAnnexTwinRooms;
        staffIndex = 0;
        while (annexTwinRemaining > 0 && !annexStaffList.isEmpty()) {
            String staffName = annexStaffList.get(staffIndex % annexStaffList.size());
            StaffDistribution dist = pattern.get(staffName);

            // シングルをツインに変換（担当部屋の上限を超えない範囲で）
            if (dist.annexSingleAssignedRooms > 0) {
                dist.annexSingleAssignedRooms--;
                dist.annexTwinAssignedRooms++;
                annexTwinRemaining--;
            }

            staffIndex++;

            // 無限ループ防止：全スタッフの合計部屋数を超えたら終了
            if (staffIndex >= annexStaffList.size() * 100) {
                System.out.println("警告: 別館ツイン配分で想定外のループ");
                break;
            }
        }

        System.out.println("別館ツイン配分完了: " + annexStaffList.size() + "名に均等配分");

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
                .mapToDouble(StaffDistribution::getConvertedTotalWithEco)
                .sum();

        System.out.println("換算合計(ECO含む): " + totalConverted);
        pattern.values().forEach(d ->
                System.out.println("  " + d.staffName + ": 本館(S" + d.mainSingleAssignedRooms +
                        "+T" + d.mainTwinAssignedRooms + "+E" + d.mainEcoAssignedRooms +
                        ") 別館(S" + d.annexSingleAssignedRooms +
                        "+T" + d.annexTwinAssignedRooms + "+E" + d.annexEcoAssignedRooms +
                        ") = " + d.getTotalRoomsWithEco() +
                        "室 (換算" + String.format("%.1f", d.getConvertedTotalWithEco()) + ")")
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

    // ★★拡張: 6区分の情報を含むサマリー（ECO含む）
    private void updateSummary() {
        int totalAssignedMainSingle = currentPattern.values().stream()
                .mapToInt(d -> d.mainSingleAssignedRooms).sum();
        int totalAssignedMainTwin = currentPattern.values().stream()
                .mapToInt(d -> d.mainTwinAssignedRooms).sum();
        int totalAssignedMainEco = currentPattern.values().stream()
                .mapToInt(d -> d.mainEcoAssignedRooms).sum();
        int totalAssignedAnnexSingle = currentPattern.values().stream()
                .mapToInt(d -> d.annexSingleAssignedRooms).sum();
        int totalAssignedAnnexTwin = currentPattern.values().stream()
                .mapToInt(d -> d.annexTwinAssignedRooms).sum();
        int totalAssignedAnnexEco = currentPattern.values().stream()
                .mapToInt(d -> d.annexEcoAssignedRooms).sum();

        double totalConvertedRooms = currentPattern.values().stream()
                .mapToDouble(StaffDistribution::getConvertedTotalWithEco).sum();

        int totalActual = totalAssignedMainSingle + totalAssignedMainTwin +
                totalAssignedAnnexSingle + totalAssignedAnnexTwin;
        int totalEco = totalAssignedMainEco + totalAssignedAnnexEco;

        String mainSingleText = formatWithColor(totalAssignedMainSingle, totalMainSingleRooms, "S");
        String mainTwinText = formatWithColor(totalAssignedMainTwin, totalMainTwinRooms, "T");
        String mainEcoText = formatWithColor(totalAssignedMainEco, totalMainEcoRooms, "E");
        String annexSingleText = formatWithColor(totalAssignedAnnexSingle, totalAnnexSingleRooms, "S");
        String annexTwinText = formatWithColor(totalAssignedAnnexTwin, totalAnnexTwinRooms, "T");
        String annexEcoText = formatWithColor(totalAssignedAnnexEco, totalAnnexEcoRooms, "E");

        String summaryText = String.format(
                "<html>割当: 本館%s+%s+%s, 別館%s+%s+%s | 通常合計: %d室 | ECO合計: %d室 | 換算合計: %.1f</html>",
                mainSingleText, mainTwinText, mainEcoText,
                annexSingleText, annexTwinText, annexEcoText,
                totalActual, totalEco,
                totalConvertedRooms
        );

        summaryLabel.setText(summaryText);
    }

    /**
     * ★★新規メソッド: 値を比較して色付きテキストを生成
     * @param assignedValue 割り当て済みの値
     * @param targetValue ファイルから読み取った目標値
     * @param prefix 接頭辞（"S", "T", "E"）
     * @return 色付きHTMLテキスト（一致していなければ赤色）
     */
    private String formatWithColor(int assignedValue, int targetValue, String prefix) {
        String text = prefix + assignedValue;
        if (assignedValue != targetValue) {
            // 一致していない場合は赤色
            return "<font color='red'>" + text + "</font>";
        } else {
            // 一致している場合は通常の黒色
            return text;
        }
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