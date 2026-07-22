package org.example;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.text.ParseException;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import com.google.ortools.sat.*;

/**
 * 通常清掃部屋割り振りダイアログ - ECO対応版
 * ★★新機能（追加）★★:
 * 1. 別館ツインの換算処理（12部屋超→1.5倍、18部屋超→1.7倍）をStep1のトータル計算前に実施
 * 2. 「本館部屋数」「別館部屋数」を「本館シングル等」「本館ツイン」「別館シングル等」「別館ツイン」に分割
 *    - 本館ツイン：roomTypeCode = 'T' or 'NT'
 *    - 別館ツイン：roomTypeCode = 'ANT' or 'ADT'
 * 3. ツイン合計値換算（2部屋=3換算、3部屋=5換算、以降+1）
 * 4. 換算値合計と実際合計の分離表示
 * 5. ツイン部屋の均等分配ロジック追加
 * 6. ★★ECO部屋の均等配分機能追加（換算係数0.2）
 * 7. ★★「清掃割り当て調整」画面から戻る機能追加（初期パターン指定版コンストラクタ）
 * 8. ★★★本館ツイン統合（新アーキテクチャ）:
 *    - 本館は「シングル等」「ツイン」の区別を廃止し、画面上は「本館」1列（合計値）のみ表示
 *    - 本館のツイン配分はこの画面では行わない（mainTwinAssignedRooms は常に0、
 *      合計は mainSingleAssignedRooms に一本化）
 *    - 本館ツインの実際の割り当ては部屋番号割り振り段階（RoomNumberAssigner）で
 *      部屋番号順に自然に決まり、「1人1部屋まで」に正規化される
 *    - 別館は従来どおり2列（シングル等/ツイン）＋ツイン均等配分を維持
 */
public class NormalRoomDistributionDialog extends JDialog {

    private JComboBox<String> patternSelector;
    private JLabel summaryLabel;
    private JLabel warningLabel; // ★条件未達（過制約）の警告表示

    private Map<String, Map<String, StaffDistribution>> patternMap = new java.util.LinkedHashMap<>(); // ラベル→パターン（解なしは値null）
    private String defaultPatternLabel;
    private Map<String, StaffDistribution> currentPattern;

    // ★A案: 割り振りパターンのラベル（1部屋差/2部屋差 × 大浴場 下段/上段 の4種）
    // ★★表示名変更: パターン1=1部屋差・大浴場下段, パターン2=1部屋差・大浴場上段,
    //               パターン3=2部屋差・大浴場下段, パターン4=2部屋差・大浴場上段
    static final String PAT_1B = "パターン1";
    static final String PAT_1T = "パターン2";
    static final String PAT_2B = "パターン3";
    static final String PAT_2T = "パターン4";
    // ★★館またぎ: 両建物1人を最初から立てて解くパターン（5つ目）
    static final String PAT_SPLIT = "館またぎ";
    private static final String[] PATTERN_LABELS = { PAT_1B, PAT_1T, PAT_2B, PAT_2T, PAT_SPLIT };

    // ★★館またぎパターンで採用された基準の目標換算差（1 or 2）。警告判定(currentDesiredConvDiff)で使用。
    private int splitPatternConvDiff = 1;

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
    // ★★追加: 「スタッフ選択に戻る」が押されたか（初回フロー用）
    private boolean backToStaffSelection = false;
    private JButton backToStaffButton;  // 既定は非表示（初回フローでのみ表示）
    private JPanel dataPanel;
    private JLabel availableFloorsLabel;  // ★★追加: 売れている階数表示用

    // ★★追加: 利用可能なフロア番号（バリデーション用）
    private Set<Integer> availableMainFloors = new HashSet<>();
    private Set<Integer> availableAnnexFloors = new HashSet<>();
    private int totalAvailableFloors = 0;  // ★★追加: 売れている階の総数（リネン庫用）

    // ★★追加: 階別の手動割り当て（任意機能）
    private Map<Integer, ManualFloorAssignmentDialog.FloorInv> manualInventory = new HashMap<>();
    private Map<String, ManualFloorAssignmentDialog.StaffManual> manualLayout = null;
    // ★★追加: 「階別の手動割り当て」を実際に確定したか（初期レイアウト反映と区別するため）
    private boolean manualLayoutUsed = false;

    // ★★追加(入れ替え機能): 入れ替え対象選択用チェックボックス（スタッフ名→チェックボックス）
    private final Map<String, JCheckBox> swapSelectionBoxes = new LinkedHashMap<>();
    // ★★追加(入れ替え機能): 「選択」列の固定幅（狭くするためGridLayoutから独立させる）
    private static final int SELECT_COLUMN_WIDTH = 30;
    // ★★追加(並べ替え機能): ドラッグ＆ドロップによる手動の表示順（null=自動ソート）
    //   パターン切替・リセット時に null に戻し、自動ソートへ復帰する
    private List<String> displayOrder = null;

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

        // ECO部屋数（常に0: ECO配分はCP-SATが自動実施）
        public int mainEcoAssignedRooms = 0;
        public int annexEcoAssignedRooms = 0;

        // ★★追加: ECO割り振り上限（-1=上限なし、0=ECOなし、1以上=上限室数）
        public int ecoUpperLimit = -1;

        // ★★追加: 指定階（空の場合は制限なし）
        public Set<Integer> preferredFloors;

        // ★★追加: リネン庫清掃
        public boolean isLinenClosetCleaning;
        public int linenClosetFloorCount;

        public String constraintType;
        public boolean isBathCleaning;
        // ★追加: 備品発注担当フラグ（通常清掃から-6室。大浴場清掃とは排他）
        public boolean isSuppliesOrder = false;

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

            this.preferredFloors = new HashSet<>();
            this.isLinenClosetCleaning = false;
            this.linenClosetFloorCount = 0;
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
            this.isSuppliesOrder = other.isSuppliesOrder;
            this.mainSingleAssignedRooms = other.mainSingleAssignedRooms;
            this.mainTwinAssignedRooms = other.mainTwinAssignedRooms;
            this.annexSingleAssignedRooms = other.annexSingleAssignedRooms;
            this.annexTwinAssignedRooms = other.annexTwinAssignedRooms;
            this.preferredFloors = new HashSet<>(other.preferredFloors);
            this.isLinenClosetCleaning = other.isLinenClosetCleaning;
            this.linenClosetFloorCount = other.linenClosetFloorCount;
            this.ecoUpperLimit = other.ecoUpperLimit;  // ★★追加: ECO上限のコピー
        }

        public void updateTotal() {
            this.mainAssignedRooms = this.mainSingleAssignedRooms + this.mainTwinAssignedRooms;
            this.annexAssignedRooms = this.annexSingleAssignedRooms + this.annexTwinAssignedRooms;
            this.assignedRooms = this.mainAssignedRooms + this.annexAssignedRooms;
        }

        // ★★追加: 指定階の文字列表現を取得（"2,3,5" 形式）
        public String getPreferredFloorsText() {
            if (preferredFloors == null || preferredFloors.isEmpty()) {
                return "";
            }
            return preferredFloors.stream()
                    .sorted()
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));
        }

        // ★★追加: 指定階をテキストから設定（"2,3,5" 形式）
        public void setPreferredFloorsFromText(String text) {
            if (preferredFloors == null) {
                preferredFloors = new HashSet<>();
            }
            preferredFloors.clear();
            if (text == null || text.trim().isEmpty()) {
                return;
            }
            String[] parts = text.split("[,、\\s]+");
            for (String part : parts) {
                part = part.trim();
                if (!part.isEmpty()) {
                    try {
                        preferredFloors.add(Integer.parseInt(part));
                    } catch (NumberFormatException e) {
                        // 無効な入力は無視
                    }
                }
            }
        }

        // ★★新規メソッド: 換算値合計を計算（通常清掃のみ）
        // 本館ツイン('T'/'NT')と別館ツイン('ANT'/'ADT')を換算値で計算
        public double getConvertedTotal() {
            double mainTwinConverted = calculateTwinConversion(this.mainTwinAssignedRooms);
            double annexTwinConverted = calculateTwinConversion(this.annexTwinAssignedRooms);
            return this.mainSingleAssignedRooms + mainTwinConverted +
                    this.annexSingleAssignedRooms + annexTwinConverted;
        }

        // 後方互換: ECOは常に0なのでassignedRoomsと同じ
        public int getTotalRoomsWithEco() {
            return this.assignedRooms;
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
        boolean isSuppliesOrder;   // ★追加: 備品発注担当
        int minRooms;
        int maxRooms;

        StaffConstraintInfo(String constraintType, String buildingAssignment,
                            boolean isBathCleaning, int minRooms, int maxRooms) {
            this(constraintType, buildingAssignment, isBathCleaning, false, minRooms, maxRooms);
        }

        // ★追加: 備品発注フラグ付きコンストラクタ
        StaffConstraintInfo(String constraintType, String buildingAssignment,
                            boolean isBathCleaning, boolean isSuppliesOrder, int minRooms, int maxRooms) {
            this.constraintType = constraintType;
            this.buildingAssignment = buildingAssignment;
            this.isBathCleaning = isBathCleaning;
            this.isSuppliesOrder = isSuppliesOrder;
            this.minRooms = minRooms;
            this.maxRooms = maxRooms;
        }

        StaffConstraintInfo() {
            this.constraintType = "制限なし";
            this.buildingAssignment = "制限なし";
            this.isBathCleaning = false;
            this.isSuppliesOrder = false;
            this.minRooms = 0;
            this.maxRooms = 99;
        }
    }

    /**
     * 推奨コンストラクタ: 4区分の部屋数を受け取る（ECOはCP-SATが自動配分）
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

        this.totalMainSingleRooms = totalMainSingleRooms;
        this.totalMainTwinRooms = totalMainTwinRooms;
        this.totalAnnexSingleRooms = totalAnnexSingleRooms;
        this.totalAnnexTwinRooms = totalAnnexTwinRooms;
        this.totalMainRooms = totalMainSingleRooms + totalMainTwinRooms;
        this.totalAnnexRooms = totalAnnexSingleRooms + totalAnnexTwinRooms;

        this.staffConstraints = staffConstraints;
        this.staffNames = staffNames;
        this.bathCleaningType = bathCleaningType;

        calculateDistributionPatterns();
        this.defaultPatternLabel = firstAvailableLabel();
        this.currentPattern = (patternMap.get(defaultPatternLabel) != null)
                ? deepCopyPattern(patternMap.get(defaultPatternLabel)) : null;

        initializeGUI();
        setSize(1690, Math.min(950, Toolkit.getDefaultToolkit().getScreenSize().height - 60));
        setLocationRelativeTo(parent);
    }

    /**
     * ★★新規コンストラクタ（初期パターン指定版）
     * 「清掃割り当て調整」画面から戻る際に使用
     *
     * @param initialPattern 初期表示するパターン（元の設定値）
    /**
     * 初期パターン指定版コンストラクタ（「清掃割り当て調整」画面から戻る際に使用）
     */
    public NormalRoomDistributionDialog(JFrame parent,
                                        int totalMainSingleRooms,
                                        int totalMainTwinRooms,
                                        int totalAnnexSingleRooms,
                                        int totalAnnexTwinRooms,
                                        List<RoomAssignmentApplication.StaffPointConstraint> staffConstraints,
                                        List<String> staffNames,
                                        AdaptiveRoomOptimizer.BathCleaningType bathCleaningType,
                                        Map<String, StaffDistribution> initialPattern) {
        super(parent, "通常清掃部屋割り振り設定", true);

        this.totalMainSingleRooms = totalMainSingleRooms;
        this.totalMainTwinRooms = totalMainTwinRooms;
        this.totalAnnexSingleRooms = totalAnnexSingleRooms;
        this.totalAnnexTwinRooms = totalAnnexTwinRooms;
        this.totalMainRooms = totalMainSingleRooms + totalMainTwinRooms;
        this.totalAnnexRooms = totalAnnexSingleRooms + totalAnnexTwinRooms;

        this.staffConstraints = staffConstraints;
        this.staffNames = staffNames;
        this.bathCleaningType = bathCleaningType;

        if (initialPattern != null && !initialPattern.isEmpty()) {
            this.currentPattern = deepCopyPattern(initialPattern);
            // ★★★本館ツイン統合: 復帰データに本館ツイン数が入っていても（調整画面の実部屋集計等）、
            //   本館は区別なしのためシングル等へ合算して正規化する（合計・換算は不変）
            normalizeMainTwinIntoSingle(this.currentPattern);
            for (String label : PATTERN_LABELS) patternMap.put(label, deepCopyPattern(this.currentPattern));
            this.defaultPatternLabel = PAT_1B;
        } else {
            calculateDistributionPatterns();
            this.defaultPatternLabel = firstAvailableLabel();
            this.currentPattern = (patternMap.get(defaultPatternLabel) != null)
                    ? deepCopyPattern(patternMap.get(defaultPatternLabel)) : null;
        }

        initializeGUI();
        setSize(1690, Math.min(950, Toolkit.getDefaultToolkit().getScreenSize().height - 60));
        setLocationRelativeTo(parent);
    }

    /**
     * 後方互換コンストラクタ（mainRooms/annexRoomsのみ）
     */
    @Deprecated
    public NormalRoomDistributionDialog(JFrame parent,
                                        int totalMainRooms,
                                        int totalAnnexRooms,
                                        List<RoomAssignmentApplication.StaffPointConstraint> staffConstraints,
                                        List<String> staffNames,
                                        AdaptiveRoomOptimizer.BathCleaningType bathCleaningType) {
        this(parent, totalMainRooms, 0, totalAnnexRooms, 0, staffConstraints, staffNames, bathCleaningType);
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

        // 4区分の内訳表示（ECOはCP-SATが自動配分するため非表示）
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

        // ★★追加: 売れている階数表示（リネン庫用）- setAvailableFloors後に更新
        availableFloorsLabel = new JLabel("");
        infoPanel.add(availableFloorsLabel);

        JPanel selectorPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        selectorPanel.add(new JLabel("割り振りパターン:"));
        patternSelector = new JComboBox<>(PATTERN_LABELS);
        patternSelector.addActionListener(e -> switchPattern());
        selectorPanel.add(patternSelector);

        infoPanel.add(selectorPanel);
        panel.add(infoPanel, BorderLayout.CENTER);

        summaryLabel = new JLabel();
        summaryLabel.setFont(new Font("MS Gothic", Font.PLAIN, 12));

        warningLabel = new JLabel();
        warningLabel.setFont(new Font("MS Gothic", Font.PLAIN, 12));
        warningLabel.setForeground(new Color(200, 0, 0));

        JPanel southPanel = new JPanel();
        southPanel.setLayout(new BoxLayout(southPanel, BoxLayout.Y_AXIS));
        southPanel.add(summaryLabel);
        southPanel.add(warningLabel);
        panel.add(southPanel, BorderLayout.SOUTH);

        return panel;
    }

    // ★★変更: テーブルヘッダーにECO列を追加
    private JPanel createTablePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        JPanel tablePanel = new JPanel(new BorderLayout());

        // ★★変更: 「選択」列を固定幅(30px)で狭くするため、
        //   BorderLayout(WEST=選択列, CENTER=従来の11列グリッド)構成に変更
        //   ※GridLayoutは全列均等幅になるため、選択列だけ独立させる
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(UIManager.getColor("TableHeader.background"));
        headerPanel.setBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, Color.GRAY));

        JLabel selectHeaderLabel = new JLabel("選択", JLabel.CENTER);
        selectHeaderLabel.setFont(new Font("MS Gothic", Font.BOLD, 11));
        selectHeaderLabel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
        selectHeaderLabel.setPreferredSize(new Dimension(SELECT_COLUMN_WIDTH, 25));
        headerPanel.add(selectHeaderLabel, BorderLayout.WEST);

        JPanel headerCells = new JPanel(new GridLayout(1, 11));  // ★★★変更: 12→11列（本館S/Tを「本館」1列に統合）
        headerCells.setOpaque(false);

        // ECO列・通常合計列を削除（ECOはCP-SATが自動配分）
        // ★★追加: ECO上限列（CP-SATのECO自動配分に対する上限。空欄=制限なし）
        // ★★★本館ツイン統合: 「本館S」「本館T」を「本館」1列（合計値）に統合
        String[] headers = {"スタッフ名", "制限タイプ", "お風呂", "リネン庫", "リネン庫階数", "ECO上限", "指定階",
                "本館",
                "別館S", "別館T",
                "換算合計"};
        int[] widths = {100, 100, 50, 55, 70, 60, 100, 80, 80, 80, 100};

        for (int i = 0; i < headers.length; i++) {
            JLabel headerLabel = new JLabel(headers[i], JLabel.CENTER);
            headerLabel.setFont(new Font("MS Gothic", Font.BOLD, 11));
            headerLabel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            headerLabel.setPreferredSize(new Dimension(widths[i], 25));
            headerCells.add(headerLabel);
        }
        headerPanel.add(headerCells, BorderLayout.CENTER);

        tablePanel.add(headerPanel, BorderLayout.NORTH);

        dataPanel = new JPanel();
        dataPanel.setLayout(new BoxLayout(dataPanel, BoxLayout.Y_AXIS));

        // 初期表示パターンをセレクタに反映（解なしを避け、最初に解があるパターンを選ぶ）
        if (defaultPatternLabel != null) patternSelector.setSelectedItem(defaultPatternLabel);
        updateDataPanel();

        JScrollPane scrollPane = new JScrollPane(dataPanel);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        tablePanel.add(scrollPane, BorderLayout.CENTER);

        panel.add(tablePanel, BorderLayout.CENTER);

        return panel;
    }

    private void updateDataPanel() {
        if (currentPattern == null) { showNoSolution(); return; }
        dataPanel.removeAll();
        swapSelectionBoxes.clear();  // ★★追加: 行再構築のため入れ替え選択状態をクリア

        List<StaffDistribution> sortedStaff = new ArrayList<>(currentPattern.values());

        sortedStaff.sort((s1, s2) -> {
            if (s1.isBathCleaning != s2.isBathCleaning) {
                return s1.isBathCleaning ? -1 : 1;
            }
            // ★追加: 備品発注担当を大浴場清掃の次に並べる
            if (s1.isSuppliesOrder != s2.isSuppliesOrder) {
                return s1.isSuppliesOrder ? -1 : 1;
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
            // ★★追加: 「本館のみ」「別館のみ」グループ内は換算合計の小さい順に並べる
            //   （大浴場清掃・備品発注・館またぎは対象外＝従来どおりの並び）
            if (!s1.isBathCleaning && !s1.isSuppliesOrder
                    && (s1BuildingPriority == 1 || s1BuildingPriority == 3)) {
                int convCompare = Double.compare(s1.getConvertedTotal(), s2.getConvertedTotal());
                if (convCompare != 0) {
                    return convCompare;
                }
                return s1.staffName.compareTo(s2.staffName);
            }
            boolean s1IsVendor = "業者制限".equals(s1.constraintType);
            boolean s2IsVendor = "業者制限".equals(s2.constraintType);
            if (s1IsVendor != s2IsVendor) {
                return s1IsVendor ? -1 : 1;
            }
            return s1.staffName.compareTo(s2.staffName);
        });

        // ★★追加: ドラッグ＆ドロップで並べ替え済みの場合はその順序を優先（自動ソートを上書き）
        //   displayOrder に無いスタッフは自動ソート順のまま末尾に並ぶ（安定ソート）
        if (displayOrder != null && !displayOrder.isEmpty()) {
            Map<String, Integer> orderIndex = new HashMap<>();
            for (int i = 0; i < displayOrder.size(); i++) {
                orderIndex.put(displayOrder.get(i), i);
            }
            sortedStaff.sort(Comparator.comparingInt(
                    s -> orderIndex.getOrDefault(s.staffName, Integer.MAX_VALUE)));
        }

        for (StaffDistribution staff : sortedStaff) {
            // ★★変更: 行を BorderLayout(WEST=選択列(固定幅30px), CENTER=従来の11列グリッド) に変更
            //   これにより「選択」列だけを狭くできる（GridLayoutは全列均等幅のため）
            JPanel rowContainer = new JPanel(new BorderLayout());
            rowContainer.setBorder(BorderFactory.createMatteBorder(0, 1, 1, 1, Color.GRAY));
            rowContainer.setMaximumSize(new Dimension(Integer.MAX_VALUE, 35));
            rowContainer.setPreferredSize(new Dimension(0, 35));
            // ★★追加: ドラッグ並べ替え後の順序記録用にスタッフ名を行に紐づける
            rowContainer.putClientProperty("staffName", staff.staffName);

            // ★★追加: 入れ替え対象の選択チェックボックス（固定幅で狭く表示）
            JPanel selectPanel = new JPanel(new GridBagLayout());
            selectPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            selectPanel.setPreferredSize(new Dimension(SELECT_COLUMN_WIDTH, 35));
            JCheckBox selectBox = new JCheckBox();
            if (staff.isBathCleaning || staff.isSuppliesOrder) {
                // ★★追加: 大浴場清掃担当・備品発注担当は入れ替え不可（選択自体を無効化）
                selectBox.setEnabled(false);
                selectBox.setToolTipText(staff.isBathCleaning
                        ? "大浴場清掃担当のため入れ替えできません"
                        : "備品発注担当のため入れ替えできません");
            } else {
                selectBox.setToolTipText("入れ替え対象として選択（2人選んで「選択した2人を入れ替え」）");
            }
            swapSelectionBoxes.put(staff.staffName, selectBox);
            selectPanel.add(selectBox);
            rowContainer.add(selectPanel, BorderLayout.WEST);

            // 従来の列部分（スタッフ名〜換算合計）。既存リスナーはこのrowPanelを参照する
            JPanel rowPanel = new JPanel(new GridLayout(1, 11));  // ★★★変更: 12→11列（本館S/T統合）

            // スタッフ名（★★追加: ドラッグで行の並べ替えが可能）
            JLabel nameLabel = new JLabel(staff.staffName, JLabel.CENTER);
            nameLabel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            nameLabel.setToolTipText("ドラッグで行を並べ替え");
            nameLabel.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            installRowDragHandler(nameLabel, rowContainer);
            rowPanel.add(nameLabel);

            // 制限タイプ
            JLabel constraintLabel = new JLabel(staff.constraintType, JLabel.CENTER);
            constraintLabel.setFont(new Font("MS Gothic", Font.PLAIN, 10));
            constraintLabel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            rowPanel.add(constraintLabel);

            // お風呂清掃
            // お風呂清掃 / 備品発注（排他のためどちらか一方を表示）
            String dutyMark = staff.isBathCleaning ? "○" : (staff.isSuppliesOrder ? "備" : "");
            JLabel bathLabel = new JLabel(dutyMark, JLabel.CENTER);
            bathLabel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            rowPanel.add(bathLabel);

            // ★★追加: リネン庫チェックボックス
            JPanel linenCheckPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
            linenCheckPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            JCheckBox linenCheckBox = new JCheckBox();
            linenCheckBox.setSelected(staff.isLinenClosetCleaning);
            linenCheckPanel.add(linenCheckBox);
            rowPanel.add(linenCheckPanel);

            // ★★追加: リネン庫階数スピナー（最大値=売れている階数）
            JPanel linenFloorPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 0));
            linenFloorPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            int maxLinenFloors = totalAvailableFloors > 0 ? totalAvailableFloors : 20;
            SpinnerNumberModel linenFloorModel = new SpinnerNumberModel(
                    Math.min(staff.linenClosetFloorCount, maxLinenFloors), 0, maxLinenFloors, 1);
            JSpinner linenFloorSpinner = new JSpinner(linenFloorModel);
            linenFloorSpinner.setPreferredSize(new Dimension(50, 25));
            applyZeroAsBlankFormatter(linenFloorSpinner);
            linenFloorSpinner.setEnabled(staff.isLinenClosetCleaning);
            linenFloorSpinner.addChangeListener(e -> {
                staff.linenClosetFloorCount = (int) linenFloorSpinner.getValue();
                updateRowDisplay(rowPanel, staff);
                updateSummary();
            });
            linenFloorPanel.add(linenFloorSpinner);
            rowPanel.add(linenFloorPanel);

            // リネン庫チェックボックスの変更リスナー（複数人選択可能）
            linenCheckBox.addActionListener(e -> {
                boolean selected = linenCheckBox.isSelected();
                staff.isLinenClosetCleaning = selected;
                if (selected) {
                    // ★★修正: チェック時に階数を自動で1に設定
                    staff.linenClosetFloorCount = 1;
                    linenFloorSpinner.setValue(1);
                } else {
                    staff.linenClosetFloorCount = 0;
                    linenFloorSpinner.setValue(0);
                }
                linenFloorSpinner.setEnabled(selected);
                updateRowDisplay(rowPanel, staff);
                updateSummary();
            });

            // ★★追加: ECO上限スピナー（空欄=制限なし、0=ECOなし、1以上=上限室数）
            JPanel ecoLimitPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 0));
            ecoLimitPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            SpinnerNumberModel ecoLimitModel = new SpinnerNumberModel(staff.ecoUpperLimit, -1, 99, 1);
            JSpinner ecoLimitSpinner = new JSpinner(ecoLimitModel);
            ecoLimitSpinner.setPreferredSize(new Dimension(55, 25));
            applyMinusOneAsBlankFormatter(ecoLimitSpinner);
            ecoLimitSpinner.setToolTipText("ECO割り振り上限（空欄=制限なし、0=ECOなし）");
            ecoLimitSpinner.addChangeListener(e -> {
                staff.ecoUpperLimit = (int) ecoLimitSpinner.getValue();
            });
            ecoLimitPanel.add(ecoLimitSpinner);
            rowPanel.add(ecoLimitPanel);

            // ★★追加: 指定階テキストフィールド
            JPanel floorPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 0));
            floorPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            JTextField floorField = new JTextField(staff.getPreferredFloorsText(), 8);
            floorField.setFont(new Font("MS Gothic", Font.PLAIN, 11));
            floorField.setHorizontalAlignment(JTextField.CENTER);
            floorField.setToolTipText("階数をカンマ区切りで入力 (例: 2,3,5)");
            floorField.addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent e) {
                    staff.setPreferredFloorsFromText(floorField.getText());
                    // 入力を正規化して表示
                    floorField.setText(staff.getPreferredFloorsText());
                }
            });
            floorField.addActionListener(e -> {
                staff.setPreferredFloorsFromText(floorField.getText());
                floorField.setText(staff.getPreferredFloorsText());
            });
            floorPanel.add(floorField);
            rowPanel.add(floorPanel);

            // ★★★本館ツイン統合: 「本館」合計スピナー（シングル等＋ツインの合計を1列で扱う）
            //   内部的には mainSingleAssignedRooms に合計を集約し、mainTwinAssignedRooms は常に0とする。
            //   （本館ツインの実配分は部屋番号割り振り段階で部屋番号順に決まる）
            JPanel mainTotalPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 0));
            mainTotalPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            SpinnerNumberModel mainTotalModel = new SpinnerNumberModel(
                    staff.mainSingleAssignedRooms + staff.mainTwinAssignedRooms, 0, 99, 1);
            JSpinner mainTotalSpinner = new JSpinner(mainTotalModel);
            mainTotalSpinner.setPreferredSize(new Dimension(55, 25));
            applyZeroAsBlankFormatter(mainTotalSpinner);
            mainTotalSpinner.addChangeListener(e -> {
                // 合計値をシングル等へ一本化（ツインは常に0＝区別なし）
                staff.mainSingleAssignedRooms = (int) mainTotalSpinner.getValue();
                staff.mainTwinAssignedRooms = 0;
                staff.updateTotal();
                updateRowDisplay(rowPanel, staff);
                updateSummary();
            });
            mainTotalPanel.add(mainTotalSpinner);
            rowPanel.add(mainTotalPanel);

            // 別館シングル等スピナー
            JPanel annexSinglePanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 0));
            annexSinglePanel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            SpinnerNumberModel annexSingleModel = new SpinnerNumberModel(staff.annexSingleAssignedRooms, 0, 99, 1);
            JSpinner annexSingleSpinner = new JSpinner(annexSingleModel);
            annexSingleSpinner.setPreferredSize(new Dimension(55, 25));
            applyZeroAsBlankFormatter(annexSingleSpinner);
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
            applyZeroAsBlankFormatter(annexTwinSpinner);
            annexTwinSpinner.addChangeListener(e -> {
                staff.annexTwinAssignedRooms = (int) annexTwinSpinner.getValue();
                staff.updateTotal();
                updateRowDisplay(rowPanel, staff);
                updateSummary();
            });
            annexTwinPanel.add(annexTwinSpinner);
            rowPanel.add(annexTwinPanel);

            // 換算合計（通常室のみ、ECOはCP-SATが自動配分）
            double convertedTotal = staff.getConvertedTotal();
            JLabel convertedLabel = new JLabel(String.valueOf((int) convertedTotal), JLabel.CENTER);
            convertedLabel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));
            convertedLabel.setFont(new Font("MS Gothic", Font.BOLD, 11));
            rowPanel.add(convertedLabel);

            rowContainer.add(rowPanel, BorderLayout.CENTER);
            dataPanel.add(rowContainer);
        }

        dataPanel.revalidate();
        dataPanel.repaint();
        updateSummary();
    }

    private void updateRowDisplay(JPanel rowPanel, StaffDistribution staff) {
        Component[] components = rowPanel.getComponents();
        // 換算合計更新（★★★変更: 本館S/T統合により 11列グリッド内の index 10 に移動）
        if (components.length > 10) {
            ((JLabel)components[10]).setText(String.valueOf((int) staff.getConvertedTotal()));
        }
    }

    /**
     * ★★追加: ドラッグ＆ドロップによる行並べ替えハンドラをスタッフ名ラベルに設定する。
     * スタッフ名ラベルをドラッグすると行が上下に移動し、離した時点の順序を手動並び順として記録する。
     * 手動並び順はパターン切替・リセットで破棄され、自動ソートに戻る。
     */
    private void installRowDragHandler(JLabel dragHandle, JPanel rowPanel) {
        MouseAdapter handler = new MouseAdapter() {
            private boolean moved = false;

            @Override
            public void mousePressed(MouseEvent e) {
                moved = false;
                // ドラッグ中の行を青枠で強調
                rowPanel.setBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, new Color(0, 120, 215)));
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                Point p = SwingUtilities.convertPoint(dragHandle, e.getPoint(), dataPanel);
                int targetIndex = rowIndexAtY(p.y);
                int currentIndex = dataPanel.getComponentZOrder(rowPanel);
                if (targetIndex >= 0 && currentIndex >= 0 && targetIndex != currentIndex) {
                    dataPanel.setComponentZOrder(rowPanel, targetIndex);
                    dataPanel.revalidate();
                    dataPanel.repaint();
                    moved = true;
                }
                // スクロール領域外へドラッグした場合の自動スクロール
                dataPanel.scrollRectToVisible(new Rectangle(0, p.y - 30, 1, 60));
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                rowPanel.setBorder(BorderFactory.createMatteBorder(0, 1, 1, 1, Color.GRAY));
                if (moved) {
                    captureDisplayOrderFromPanel();
                }
            }
        };
        dragHandle.addMouseListener(handler);
        dragHandle.addMouseMotionListener(handler);
    }

    /** ★★追加: dataPanel内のY座標から行インデックスを求める（範囲外は端の行に丸める） */
    private int rowIndexAtY(int y) {
        Component[] comps = dataPanel.getComponents();
        if (comps.length == 0) return -1;
        for (int i = 0; i < comps.length; i++) {
            Component c = comps[i];
            if (y >= c.getY() && y < c.getY() + c.getHeight()) {
                return i;
            }
        }
        if (y < 0) return 0;
        Component last = comps[comps.length - 1];
        if (y >= last.getY() + last.getHeight()) return comps.length - 1;
        return -1;
    }

    /** ★★追加: 現在のdataPanelの行順を手動並び順として記録する（ドラッグ完了時に呼ぶ） */
    private void captureDisplayOrderFromPanel() {
        List<String> order = new ArrayList<>();
        for (Component c : dataPanel.getComponents()) {
            if (c instanceof JComponent) {
                Object name = ((JComponent) c).getClientProperty("staffName");
                if (name != null) order.add(name.toString());
            }
        }
        if (!order.isEmpty()) {
            displayOrder = order;
        }
    }

    /**
     * ★★変更: 選択チェックボックスで選んだ2人を「名前だけ」入れ替える。
     * 実装上は名前・ID以外のフィールド（部屋数・制限タイプ・建物割り当て・指定階・リネン庫）を
     * 2人の間で交換する。これにより表の行内容（制限タイプ・部屋数など）は行に固定されたまま、
     * 担当者名だけが入れ替わって見える。
     * ★★追加: 大浴場清掃担当・備品発注担当のスタッフは入れ替え不可
     * （チェックボックス無効化に加え、ここでも二重にチェックする）。
     * 入れ替え先の行の換算値が本人の登録上限（業者制限等）を超える場合は確認ダイアログを表示する。
     */
    private void swapSelectedStaff() {
        if (currentPattern == null) {
            JOptionPane.showMessageDialog(this,
                    "選択中のパターンは解なしのため入れ替えできません。",
                    "入れ替え", JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<String> selected = new ArrayList<>();
        for (Map.Entry<String, JCheckBox> entry : swapSelectionBoxes.entrySet()) {
            if (entry.getValue().isSelected()) {
                selected.add(entry.getKey());
            }
        }
        if (selected.size() != 2) {
            JOptionPane.showMessageDialog(this,
                    "入れ替えるスタッフを2人選択してください。（現在の選択: " + selected.size() + "人）",
                    "入れ替え", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        StaffDistribution a = currentPattern.get(selected.get(0));
        StaffDistribution b = currentPattern.get(selected.get(1));
        if (a == null || b == null) return;

        // ★★追加: 大浴場清掃担当・備品発注担当は入れ替え不可
        List<String> blocked = new ArrayList<>();
        for (StaffDistribution d : new StaffDistribution[]{ a, b }) {
            if (d.isBathCleaning) {
                blocked.add(d.staffName + "（大浴場清掃担当）");
            } else if (d.isSuppliesOrder) {
                blocked.add(d.staffName + "（備品発注担当）");
            }
        }
        if (!blocked.isEmpty()) {
            StringBuilder msg = new StringBuilder("以下のスタッフは担当が固定されているため入れ替えできません：\n\n");
            for (String s : blocked) {
                msg.append("• ").append(s).append("\n");
            }
            JOptionPane.showMessageDialog(this, msg.toString(),
                    "入れ替え", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // 交換後の換算値で本人の登録上限（業者制限等）超過をチェック
        // （換算値は部屋数のみで決まるため、交換後の換算値＝相手の現在の換算値）
        Map<String, StaffConstraintInfo> staffInfo = collectStaffConstraints();
        List<String> overLimit = new ArrayList<>();
        checkSwapUpperLimit(a, b.getConvertedTotal(), staffInfo, overLimit);
        checkSwapUpperLimit(b, a.getConvertedTotal(), staffInfo, overLimit);
        if (!overLimit.isEmpty()) {
            StringBuilder msg = new StringBuilder("入れ替えると以下の上限超過が発生します：\n\n");
            for (String s : overLimit) {
                msg.append("• ").append(s).append("\n");
            }
            msg.append("\nこのまま入れ替えますか？");
            int result = JOptionPane.showConfirmDialog(this, msg.toString(),
                    "上限超過の確認", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (result != JOptionPane.YES_OPTION) return;
        }

        // ★★変更: 「名前だけの入れ替え」＝名前・ID以外の全項目を交換し、行の内容を固定する
        // 部屋数の交換
        int tmp;
        tmp = a.mainSingleAssignedRooms;  a.mainSingleAssignedRooms  = b.mainSingleAssignedRooms;  b.mainSingleAssignedRooms  = tmp;
        tmp = a.mainTwinAssignedRooms;   a.mainTwinAssignedRooms   = b.mainTwinAssignedRooms;   b.mainTwinAssignedRooms   = tmp;
        tmp = a.annexSingleAssignedRooms; a.annexSingleAssignedRooms = b.annexSingleAssignedRooms; b.annexSingleAssignedRooms = tmp;
        tmp = a.annexTwinAssignedRooms;  a.annexTwinAssignedRooms  = b.annexTwinAssignedRooms;  b.annexTwinAssignedRooms  = tmp;
        // 建物割り当ての交換
        String tmpBuilding = a.buildingAssignment;
        a.buildingAssignment = b.buildingAssignment;
        b.buildingAssignment = tmpBuilding;
        // ★★追加: 制限タイプ表示の交換（行に固定するため）
        String tmpConstraint = a.constraintType;
        a.constraintType = b.constraintType;
        b.constraintType = tmpConstraint;
        // ※お風呂（大浴場清掃）・備品発注は入れ替え不可のため交換しない（上の事前チェックで弾かれる）
        // 指定階の交換
        Set<Integer> tmpFloors = a.preferredFloors;
        a.preferredFloors = b.preferredFloors;
        b.preferredFloors = tmpFloors;
        // リネン庫の交換
        boolean tmpLinen = a.isLinenClosetCleaning;
        a.isLinenClosetCleaning = b.isLinenClosetCleaning;
        b.isLinenClosetCleaning = tmpLinen;
        int tmpLinenCount = a.linenClosetFloorCount;
        a.linenClosetFloorCount = b.linenClosetFloorCount;
        b.linenClosetFloorCount = tmpLinenCount;
        // ★★追加: ECO上限の交換（行の内容に固定するため、他項目と同様に交換する）
        int tmpEcoLimit = a.ecoUpperLimit;
        a.ecoUpperLimit = b.ecoUpperLimit;
        b.ecoUpperLimit = tmpEcoLimit;
        // ※ECO部屋数(mainEco/annexEco)は常に0のため交換不要
        a.updateTotal();
        b.updateTotal();

        // 手動並び順がある場合は2人の位置も入れ替える
        // → 行の内容（お風呂○・制限タイプ・部屋数等）は元の位置に固定されたまま、名前だけが入れ替わる
        // （自動ソート時は行内容と一緒にソートキーも移動するため、位置交換なしで同じ見た目になる）
        if (displayOrder != null) {
            int ia = displayOrder.indexOf(a.staffName);
            int ib = displayOrder.indexOf(b.staffName);
            if (ia >= 0 && ib >= 0) {
                Collections.swap(displayOrder, ia, ib);
            }
        }

        updateDataPanel();  // 再描画（サマリー更新・選択クリア含む）
    }

    /** ★★追加: 交換後の換算値が上限（業者制限・故障者制限等）を超えるかチェックし、超える場合はoutに追記 */
    private void checkSwapUpperLimit(StaffDistribution staff, double newConv,
                                     Map<String, StaffConstraintInfo> staffInfo, List<String> out) {
        StaffConstraintInfo info = staffInfo.get(staff.staffName);
        if (info != null && !"制限なし".equals(info.constraintType)
                && info.maxRooms > 0 && newConv > info.maxRooms + 1e-9) {
            out.add(String.format("%s（%s）: 換算 %d が上限 %d を超えます",
                    staff.staffName, info.constraintType, (int) Math.round(newConv), info.maxRooms));
        }
    }

    /**
     * 0を空白で表示するカスタムフォーマッターをスピナーに適用
     */
    private void applyZeroAsBlankFormatter(JSpinner spinner) {
        JSpinner.NumberEditor editor = (JSpinner.NumberEditor) spinner.getEditor();
        JFormattedTextField textField = editor.getTextField();

        NumberFormatter formatter = new NumberFormatter() {
            @Override
            public String valueToString(Object value) throws ParseException {
                if (value == null || (value instanceof Number && ((Number) value).intValue() == 0)) {
                    return "";
                }
                return super.valueToString(value);
            }

            @Override
            public Object stringToValue(String text) throws ParseException {
                if (text == null || text.trim().isEmpty()) {
                    return 0;
                }
                return super.stringToValue(text);
            }
        };

        formatter.setValueClass(Integer.class);
        formatter.setMinimum(0);
        formatter.setMaximum(99);

        DefaultFormatterFactory factory = new DefaultFormatterFactory(formatter);
        textField.setFormatterFactory(factory);
        textField.setHorizontalAlignment(JTextField.CENTER);
    }

    /**
     * ★★追加: -1を空白で表示するカスタムフォーマッターをスピナーに適用（ECO上限用）
     * 空欄=-1（上限なし）、0=ECOなし、1以上=上限室数
     */
    private void applyMinusOneAsBlankFormatter(JSpinner spinner) {
        JSpinner.NumberEditor editor = (JSpinner.NumberEditor) spinner.getEditor();
        JFormattedTextField textField = editor.getTextField();

        NumberFormatter formatter = new NumberFormatter() {
            @Override
            public String valueToString(Object value) throws ParseException {
                if (value == null || (value instanceof Number && ((Number) value).intValue() == -1)) {
                    return "";
                }
                return super.valueToString(value);
            }

            @Override
            public Object stringToValue(String text) throws ParseException {
                if (text == null || text.trim().isEmpty()) {
                    return -1;
                }
                return super.stringToValue(text);
            }
        };

        formatter.setValueClass(Integer.class);
        formatter.setMinimum(-1);
        formatter.setMaximum(99);

        DefaultFormatterFactory factory = new DefaultFormatterFactory(formatter);
        textField.setFormatterFactory(factory);
        textField.setHorizontalAlignment(JTextField.CENTER);
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

        // ★★追加: スタッフ選択（ポイント制限設定）に戻るボタン（初回フローでのみ表示）
        backToStaffButton = new JButton("スタッフ選択に戻る");
        backToStaffButton.addActionListener(e -> {
            backToStaffSelection = true;
            dialogResult = false;   // OK扱いにはしない
            dispose();
        });
        backToStaffButton.setVisible(false);  // 既定は非表示
        panel.add(backToStaffButton);

        // ★★追加: 選択した2人の名前だけを入れ替える（行の内容＝部屋数・制限・お風呂等は行に固定）
        JButton swapButton = new JButton("選択した2人を入れ替え");
        swapButton.addActionListener(e -> swapSelectedStaff());
        panel.add(swapButton);

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

        // ★★追加: 階別の手動割り当てボタン（任意機能）
        JButton manualButton = new JButton("階別の手動割り当て");
        manualButton.addActionListener(e -> openManualAssignment());
        panel.add(manualButton);

        return panel;
    }

    /** ★★追加: 階別の手動割り当てダイアログを開く（「割り当て済み数」ウィンドウも同時に開く） */
    private void openManualAssignment() {
        ManualFloorAssignmentDialog mdlg = new ManualFloorAssignmentDialog(
                this, currentPattern, manualInventory, manualLayout);
        mdlg.setVisible(true);   // 表示と同時に「割り当て済み数」ウィンドウが開く
        if (mdlg.isConfirmed()) {
            manualLayout = mdlg.getResultLayout();
            manualLayoutUsed = true;   // ★★追加: 実際に手動割り当てを確定した
            JOptionPane.showMessageDialog(this,
                    "手動割り当てを保存しました。\nこのままOKで確定すると、この割り当てが使用されます（CP-SATは使いません）。",
                    "階別の手動割り当て", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private boolean validateBeforeOk() {
        if (currentPattern == null) {
            JOptionPane.showMessageDialog(this,
                    "選択中のパターンは解なしのため確定できません。別のパターンを選択してください。",
                    "確定できません", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 割り当て済み部屋数の集計
        int assignedMainSingle = 0;
        int assignedMainTwin = 0;
        int assignedAnnexSingle = 0;
        int assignedAnnexTwin = 0;

        for (StaffDistribution dist : currentPattern.values()) {
            assignedMainSingle += dist.mainSingleAssignedRooms;
            assignedMainTwin += dist.mainTwinAssignedRooms;
            assignedAnnexSingle += dist.annexSingleAssignedRooms;
            assignedAnnexTwin += dist.annexTwinAssignedRooms;
        }

        // 部屋数の不一致チェック（警告として処理）
        // ★★★本館ツイン統合: 本館はシングル/ツインの区別なしで「合計」で照合する
        int assignedMainTotal = assignedMainSingle + assignedMainTwin;
        if (assignedMainTotal != totalMainRooms) {
            warnings.add(String.format("本館: 割当%d室 ≠ 実際%d室 (差分%d)",
                    assignedMainTotal, totalMainRooms,
                    assignedMainTotal - totalMainRooms));
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

        // ★★追加: リネン庫のバリデーション
        int totalLinenFloors = 0;
        for (StaffDistribution dist : currentPattern.values()) {
            if (dist.isLinenClosetCleaning) {
                if (dist.linenClosetFloorCount <= 0) {
                    errors.add(String.format("%s: リネン庫清掃担当に指定されていますが、階数が0です",
                            dist.staffName));
                }
                totalLinenFloors += dist.linenClosetFloorCount;
            }
        }
        if (totalAvailableFloors > 0 && totalLinenFloors > totalAvailableFloors) {
            warnings.add(String.format("リネン庫担当の合計階数(%d)が売れている階数(%d)を超えています",
                    totalLinenFloors, totalAvailableFloors));
        }

        // 大浴場スタッフが本館と別館の両方に割り当てがある場合は警告
        for (StaffDistribution dist : currentPattern.values()) {
            if (dist.isBathCleaning) {
                int mainRooms = dist.mainSingleAssignedRooms + dist.mainTwinAssignedRooms;
                int annexRooms = dist.annexSingleAssignedRooms + dist.annexTwinAssignedRooms;
                if (mainRooms > 0 && annexRooms > 0) {
                    warnings.add(String.format("%s: 大浴場清掃担当が本館・別館の両方に割り当てられています",
                            dist.staffName));
                }
            }
        }

        // ★★追加: 指定階のバリデーション
        // ※別館フロアは内部的に+20（別館2F=22等）。ユーザー入力は実際の階数（2等）
        Set<Integer> allAvailableFloors = new HashSet<>();
        allAvailableFloors.addAll(availableMainFloors);
        allAvailableFloors.addAll(availableAnnexFloors);

        // 別館フロアを実際の階数に変換したセット（表示・比較用）
        Set<Integer> availableAnnexFloorsActual = availableAnnexFloors.stream()
                .map(f -> f > 20 ? f - 20 : f)
                .collect(Collectors.toSet());

        for (StaffDistribution dist : currentPattern.values()) {
            if (dist.preferredFloors == null || dist.preferredFloors.isEmpty()) continue;

            int mainRooms = dist.mainSingleAssignedRooms + dist.mainTwinAssignedRooms;
            int annexRooms = dist.annexSingleAssignedRooms + dist.annexTwinAssignedRooms;

            for (int floor : dist.preferredFloors) {
                // フロア情報が未設定の場合はスキップ（後方互換）
                if (allAvailableFloors.isEmpty()) {
                    continue;
                }

                boolean valid = false;
                if (mainRooms > 0 && annexRooms == 0) {
                    // 本館のみ割り振り → 本館フロアのみチェック（番号そのまま）
                    valid = availableMainFloors.contains(floor);
                } else if (annexRooms > 0 && mainRooms == 0) {
                    // 別館のみ割り振り → 実際の階数でチェック
                    valid = availableAnnexFloorsActual.contains(floor);
                } else {
                    // 両方割り振り → 本館フロアまたは別館実際の階数でチェック
                    valid = availableMainFloors.contains(floor) || availableAnnexFloorsActual.contains(floor);
                }

                if (!valid) {
                    String building;
                    String availableStr;
                    if (mainRooms > 0 && annexRooms == 0) {
                        building = "本館";
                        availableStr = availableMainFloors.stream().sorted()
                                .map(String::valueOf).collect(Collectors.joining(","));
                    } else if (annexRooms > 0 && mainRooms == 0) {
                        building = "別館";
                        availableStr = availableAnnexFloorsActual.stream().sorted()
                                .map(String::valueOf).collect(Collectors.joining(","));
                    } else {
                        building = "本館・別館";
                        Set<Integer> combined = new TreeSet<>(availableMainFloors);
                        combined.addAll(availableAnnexFloorsActual);
                        availableStr = combined.stream()
                                .map(String::valueOf).collect(Collectors.joining(","));
                    }
                    errors.add(String.format("%s: 指定階 %dF は%sに存在しません（利用可能: %s）",
                            dist.staffName, floor, building, availableStr));
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
        // ★A案: 1部屋差/2部屋差 × 大浴場 下段/上段 の4パターンを、自動切替なしで固定計算する。
        // ★A案: 1部屋差/2部屋差 × 大浴場 下段/上段 の4パターンを、自動切替なしで固定計算する。
        patternMap.put(PAT_1B, calculatePatternForcedPeg(-1, true));
        patternMap.put(PAT_1T, calculatePatternForcedPeg(-1, false));
        patternMap.put(PAT_2B, calculatePatternForcedPeg(-2, true));
        patternMap.put(PAT_2T, calculatePatternForcedPeg(-2, false));
        // ★★館またぎ: 4基準すべてを両建物1人ありで解き、換算スコア最良を採用
        patternMap.put(PAT_SPLIT, calculatePatternSplit());

        for (String label : PATTERN_LABELS) {
            System.out.println("=== " + label + " ===");
            Map<String, StaffDistribution> p = patternMap.get(label);
            if (p == null) {
                System.out.println("（解なし: この基準では条件を満たす割り振りがありません）");
            } else {
                printPatternDebug(p);
            }
        }
    }

    /** 最初に解が存在するパターンのラベルを返す（全て解なしなら先頭ラベル）。 */
    private String firstAvailableLabel() {
        for (String label : PATTERN_LABELS) {
            if (patternMap.get(label) != null) return label;
        }
        return PATTERN_LABELS[0];
    }

    /**
     * ★A案: 大浴場の下段/上段を固定して割り振りパターンを生成する（差≧3の自動切替は行わない）。
     * CP-SATがこの基準で実行不能なら null（＝解なし）を返し、呼び出し側はその旨を表示する。
     * OR-Tools自体が使えない（例外）ときのみ、従来ヒューリスティックにフォールバックする。
     * @param annexDifference 別館と本館の目標差（-1 または -2）
     * @param pegToBottom true=大浴場/備品を下段(mainBottom)基準、false=上段(mainTop)基準
     */
    private Map<String, StaffDistribution> calculatePatternForcedPeg(int annexDifference, boolean pegToBottom) {
        Map<String, StaffDistribution> base;
        try {
            base = buildBaseAssignmentCPSATForcedPeg(annexDifference, pegToBottom);
            // base == null は「この基準では解なし」。A案では別pegにすり替えず、そのまま null を返す。
        } catch (Throwable t) {
            System.out.println("CP-SAT例外: " + t + " → ヒューリスティックにフォールバック");
            base = buildBaseAssignmentHeuristic(annexDifference); // OR-Tools不可時の保険
        }
        if (base != null) distributeTwins(base);
        return base;
    }

    /**
     * ★固定peg版のCP-SAT求解。換算ベースで最良の配分を探索する。
     * 通常配分→実行不能なら両建物1人(条件7)で再試行→それでも不能なら null。
     * @param annexDifference 換算での目標差（-1=1部屋差, -2=2部屋差）
     */
    private Map<String, StaffDistribution> buildBaseAssignmentCPSATForcedPeg(int annexDifference, boolean pegToBottom) {
        int desiredConvDiff = -annexDifference; // 1 または 2（換算での本館-別館差の目標）
        CPSATSolution s = solveBest(desiredConvDiff, null, pegToBottom);
        if (s != null) return s.pattern;
        String splitName = pickSplitCandidate();
        if (splitName != null) {
            System.out.println("CP-SAT: 通常配分が実行不能 → 両建物1人(" + splitName + ")で再試行 (peg="
                    + (pegToBottom ? "下段" : "上段") + ", 換算差=" + desiredConvDiff + ")");
            s = solveBest(desiredConvDiff, splitName, pegToBottom);
            if (s != null) return s.pattern;
        }
        return null;
    }

    /**
     * ★★館またぎパターン（5つ目）: またぎスタッフ(pickSplitCandidate)を最初から立てた状態で
     * 4基準（1部屋差/2部屋差 × 大浴場 下段/上段）すべてをsolveBestで解き、
     * conversionScore（各基準の目標換算差に対する評価）が最良の解を採用する。
     * 同点はCP目的関数値で決める（solveBestと同方式）。
     * 候補スタッフなし・全基準解なし・OR-Tools不可の場合は null（＝解なし表示）。
     * ※またぎスタッフの総室数=別館最小-1のハード制約は既存(条件7)のまま。
     * ※既存4パターンの計算パス・フォールバックには影響しない。
     */
    private Map<String, StaffDistribution> calculatePatternSplit() {
        String splitName = pickSplitCandidate();
        if (splitName == null) {
            System.out.println("館またぎ: 候補スタッフなし → 解なし");
            return null;
        }

        Map<String, StaffDistribution> best = null;
        double bestScore = Double.MAX_VALUE;
        double bestObjective = Double.MAX_VALUE;
        int bestConvDiff = 1;

        for (int desiredConvDiff = 1; desiredConvDiff <= 2; desiredConvDiff++) {
            for (boolean pegToBottom : new boolean[]{ true, false }) {
                CPSATSolution s;
                try {
                    s = solveBest(desiredConvDiff, splitName, pegToBottom);
                } catch (Throwable t) {
                    System.out.println("館またぎ CP-SAT例外: " + t
                            + " (換算差=" + desiredConvDiff
                            + ", peg=" + (pegToBottom ? "下段" : "上段") + ")");
                    continue;
                }
                if (s == null) continue;
                double score = conversionScore(s.pattern, desiredConvDiff);
                if (score < bestScore - 1e-9
                        || (Math.abs(score - bestScore) < 1e-9 && s.objective < bestObjective)) {
                    best = s.pattern;
                    bestScore = score;
                    bestObjective = s.objective;
                    bestConvDiff = desiredConvDiff;
                }
            }
        }

        if (best == null) {
            System.out.println("館またぎ: 全基準で解なし");
            return null;
        }

        this.splitPatternConvDiff = bestConvDiff;
        System.out.println("館またぎ採用: またぎ=" + splitName
                + ", 目標換算差=" + bestConvDiff
                + ", score=" + String.format("%.2f", bestScore));
        distributeTwins(best);  // ツイン配分はここで一度だけ（既存パターンと同タイミング）
        return best;
    }

    /** 別館人数の探索窓（ヒューリスティック中心±）。 */
    private static final int HEADCOUNT_WINDOW = 2;
    /** 室差の探索窓（別館ツインの換算膨張ぶん、室では少し多めに差をつける必要があるため）。 */
    private static final int ROOMDIFF_WINDOW = 2;
    // 換算ベース選択の重み
    private static final double W_REVERSAL = 12.0; // 換算での本館<別館の逆転
    private static final double W_VENDOR   = 12.0; // 業者の換算が上限超過
    private static final double W_CONVDIFF = 10.0; // 換算差が目標からずれる

    /**
     * ★換算ベース探索: 別館人数(fa)と内部の室差(roomDiff)を振って解き、
     * 「ツイン配分後の換算」で評価して最良の配分を選ぶ。
     *  - 換算での本館<別館の逆転を避ける
     *  - 換算での本館-別館差が目標(desiredConvDiff)に近い
     *  - 業者の換算が上限を超えない
     * これらを重み付き合計で最小化し、同点はCP目的関数で決める。
     * 室差を目標換算差より大きめにも振るのは、別館ツインの換算膨張ぶん、
     * 室では少し多めに差をつけないと換算で目標差にならないため。
     */
    private CPSATSolution solveBest(int desiredConvDiff, String splitName, boolean pegToBottom) {
        // 中心人数とfreeCountを取得（プローブ）。解なしでもフィールドは設定される。
        solveDistributionCPSAT(-desiredConvDiff, splitName, pegToBottom, -1);
        int center = lastHeuristicAnnex;
        int freeCount = lastFreeCount;
        // ★片建物限定対応: 故障者(両方)が居る場合、半々見積もりからのズレ
        //   （実際は全量が片建物に寄る）を吸収するため探索窓を+1広げる
        int window = HEADCOUNT_WINDOW + (lastFaultBothCount > 0 ? 1 : 0);
        int loFa = Math.max(0, center - window);
        int hiFa = Math.min(freeCount, center + window);

        CPSATSolution best = null;
        double bestScore = Double.MAX_VALUE;
        for (int roomDiff = desiredConvDiff; roomDiff <= desiredConvDiff + ROOMDIFF_WINDOW; roomDiff++) {
            for (int fa = loFa; fa <= hiFa; fa++) {
                CPSATSolution s = solveDistributionCPSAT(-roomDiff, splitName, pegToBottom, fa);
                if (s == null) continue;
                double score = conversionScore(s.pattern, desiredConvDiff);
                if (score < bestScore - 1e-9
                        || (Math.abs(score - bestScore) < 1e-9 && (best == null || s.objective < best.objective))) {
                    best = s;
                    bestScore = score;
                }
            }
        }
        if (best != null) {
            System.out.println("換算ベース探索: 目標換算差=" + desiredConvDiff + ", 人数中心=" + center
                    + ", 採用score=" + String.format("%.2f", bestScore) + ", objective=" + best.objective);
        }
        return best;
    }

    /**
     * ★ツイン配分後の換算でパターンを評価し、スコア（小さいほど良い）を返す。
     * 通常スタッフ（制限なし・非大浴場・非備品）で本館・別館の換算レベルを測り、
     * 逆転・目標差からのズレ・業者の換算上限超過を重み付けで合算する。
     * 評価はコピーに対して行い、元のパターンには影響しない。
     */
    private double conversionScore(Map<String, StaffDistribution> prePattern, int desiredConvDiff) {
        Map<String, StaffDistribution> copy = deepCopyPattern(prePattern);
        distributeTwins(copy);
        Map<String, StaffConstraintInfo> staffInfo = collectStaffConstraints();

        double mainMaxConv = -1, mainMinConv = Double.MAX_VALUE;
        double annexMaxConv = -1;
        double vendorOver = 0;
        for (StaffDistribution d : copy.values()) {
            double conv = d.getConvertedTotal();
            boolean isVendor = !"制限なし".equals(d.constraintType)
                    && !"故障者制限".equals(d.constraintType)
                    && !d.isBathCleaning && !d.isSuppliesOrder;
            if (isVendor) {
                StaffConstraintInfo info = staffInfo.get(d.staffName);
                if (info != null && info.maxRooms > 0) {
                    vendorOver += Math.max(0.0, conv - info.maxRooms); // 業者の換算が上限超過
                }
                continue;
            }
            // 通常スタッフ（制限なし・非大浴場・非備品）のみでレベルを測る
            if (!"制限なし".equals(d.constraintType)) continue;
            if (d.isBathCleaning || d.isSuppliesOrder) continue;
            boolean pureMain = d.mainAssignedRooms > 0 && d.annexAssignedRooms == 0;
            boolean pureAnnex = d.annexAssignedRooms > 0 && d.mainAssignedRooms == 0;
            if (pureMain) { mainMaxConv = Math.max(mainMaxConv, conv); mainMinConv = Math.min(mainMinConv, conv); }
            if (pureAnnex) { annexMaxConv = Math.max(annexMaxConv, conv); }
        }

        double score = W_VENDOR * vendorOver;
        if (mainMaxConv >= 0 && annexMaxConv >= 0) {
            double convDiff = mainMaxConv - annexMaxConv;                  // 換算での本館最大-別館最大
            double reversal = Math.max(0.0, annexMaxConv - mainMinConv);   // 別館が本館を上回る量
            score += W_CONVDIFF * Math.abs(convDiff - desiredConvDiff) + W_REVERSAL * reversal;
        }
        return score;
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
     * ★フォールバック用（従来ロジック）: 建物別独立計算＋ラウンドロビン端数調整
     * CP-SAT（buildBaseAssignmentCPSAT）が解けない/例外/実行不能のときに使用。
     * 挙動は従来と同一。ツイン配分は呼び出し側（calculatePatternWithCorrectLogic）で行う。
     */
    private Map<String, StaffDistribution> buildBaseAssignmentHeuristic(int annexDifference) {
        Map<String, StaffDistribution> pattern = new HashMap<>();
        Map<String, StaffConstraintInfo> staffInfo = collectStaffConstraints();
        int bathReduction = bathCleaningType.reduction;
        // ★追加: 備品発注担当の削減数（通常清掃から-6室）
        int suppliesReduction = AdaptiveRoomOptimizer.SUPPLIES_ORDER_REDUCTION;
        // ★追加: 備品発注担当の名前集合（建物バケツに入れた上で-6を適用するため）
        Set<String> suppliesSet = new HashSet<>();

        // ===== Step 1: スタッフを分類 =====
        List<String> bathStaffNames = new ArrayList<>();
        List<String> constraintStaffNames = new ArrayList<>();
        List<String> normalFreeNames = new ArrayList<>();        // 制限なし + 建物指定なし
        List<String> normalMainOnlyNames = new ArrayList<>();    // 制限なし + 本館のみ（非大浴場）
        List<String> normalAnnexOnlyNames = new ArrayList<>();   // 制限なし + 別館のみ
        // ★片建物限定: 故障者(両方)は分割せず、後段で残室数の多い側の建物へまとめる（決定待ちリスト）
        List<String> faultBothPending = new ArrayList<>();

        int constraintMainRooms = 0;
        int constraintAnnexRooms = 0;

        for (Map.Entry<String, StaffConstraintInfo> entry : staffInfo.entrySet()) {
            String name = entry.getKey();
            StaffConstraintInfo info = entry.getValue();

            if (info.isBathCleaning) {
                bathStaffNames.add(name);
            } else if (info.isSuppliesOrder) {
                // ★備品発注担当: 建物設定に従って本館/別館バケツへ。「両方」は本館側に置く（既定方針）
                suppliesSet.add(name);
                if ("別館のみ".equals(info.buildingAssignment)) {
                    normalAnnexOnlyNames.add(name);
                } else {
                    normalMainOnlyNames.add(name);
                }
            } else if (!"制限なし".equals(info.constraintType)) {
                constraintStaffNames.add(name);
                int rooms = "故障者制限".equals(info.constraintType) ? info.maxRooms : info.minRooms;
                if ("本館のみ".equals(info.buildingAssignment)) {
                    constraintMainRooms += rooms;
                } else if ("別館のみ".equals(info.buildingAssignment)) {
                    constraintAnnexRooms += rooms;
                } else if ("故障者制限".equals(info.constraintType)) {
                    // ★片建物限定: 故障者(両方)の建物はループ後に決定（ここでは加算を保留）
                    faultBothPending.add(name);
                } else {
                    // 業者制限(両方)は従来どおり半々で分割
                    constraintMainRooms += rooms / 2;
                    constraintAnnexRooms += rooms - rooms / 2;
                }
            } else if ("本館のみ".equals(info.buildingAssignment)) {
                normalMainOnlyNames.add(name);
            } else if ("別館のみ".equals(info.buildingAssignment)) {
                normalAnnexOnlyNames.add(name);
            } else {
                normalFreeNames.add(name);
            }
        }

        // ★片建物限定: 故障者(両方)は「制約控除後の残室数」が多い側の建物へまとめて配属する。
        //   部屋数(max)の大きい故障者から順に決めることで、片側への過度な偏りを抑える。
        Map<String, String> faultBothBuilding = new HashMap<>();
        faultBothPending.sort((a, b) -> Integer.compare(staffInfo.get(b).maxRooms, staffInfo.get(a).maxRooms));
        for (String name : faultBothPending) {
            int rooms = staffInfo.get(name).maxRooms;
            int remMain = totalMainRooms - constraintMainRooms;
            int remAnnex = totalAnnexRooms - constraintAnnexRooms;
            if (remMain >= remAnnex) {
                faultBothBuilding.put(name, "本館のみ");
                constraintMainRooms += rooms;
            } else {
                faultBothBuilding.put(name, "別館のみ");
                constraintAnnexRooms += rooms;
            }
            System.out.println("Step 1: " + name + " (故障者制限・両方) → " +
                    faultBothBuilding.get(name) + "にまとめて配属");
        }

        int bathCount = bathStaffNames.size();
        int fixedMainCount = normalMainOnlyNames.size();
        int fixedAnnexCount = normalAnnexOnlyNames.size();
        int freeCount = normalFreeNames.size();

        // ★追加: 備品発注担当の建物別人数（-6の足し戻しに使用）
        int mainSuppliesCount = (int) normalMainOnlyNames.stream().filter(suppliesSet::contains).count();
        int annexSuppliesCount = (int) normalAnnexOnlyNames.stream().filter(suppliesSet::contains).count();

        // 制約で確定した部屋を除いた有効部屋数
        int effectiveMainRooms = totalMainRooms - constraintMainRooms;
        int effectiveAnnexRooms = totalAnnexRooms - constraintAnnexRooms;

        System.out.println("Step 1: 分類完了 - 大浴場" + bathCount + "名, 制約" + constraintStaffNames.size() +
                "名, 本館固定" + fixedMainCount + "名, 別館固定" + fixedAnnexCount + "名, 自由" + freeCount + "名");
        System.out.println("  有効本館: " + effectiveMainRooms + "室, 有効別館: " + effectiveAnnexRooms + "室");

        // ===== Step 2: 自由スタッフの最適な本館/別館配分を決定 =====
        // 目標: 別館base ≈ 本館normalBase + annexDifference（annexDiffは-1または-2）
        // 本館: normalBase = (effectiveMain + bathCount × reduction) / (normalMainWorkers + bathCount)
        // 別館: annexBase = effectiveAnnex / annexWorkers
        int bestFreeAnnex = 0;
        double bestDiff = Double.MAX_VALUE;

        for (int freeAnnex = 0; freeAnnex <= freeCount; freeAnnex++) {
            int freeMain = freeCount - freeAnnex;
            int mainWorkers = freeMain + fixedMainCount + bathCount;
            int annexWorkers = freeAnnex + fixedAnnexCount;

            if (mainWorkers <= 0 && effectiveMainRooms > 0) continue;
            if (annexWorkers <= 0 && effectiveAnnexRooms > 0) continue;

            double mainBase = mainWorkers > 0 ?
                    (double) (effectiveMainRooms + bathCount * bathReduction + mainSuppliesCount * suppliesReduction) / mainWorkers : 0;
            double annexBase = annexWorkers > 0 ?
                    (double) (effectiveAnnexRooms + annexSuppliesCount * suppliesReduction) / annexWorkers : 0;

            double diff = Math.abs(annexBase - (mainBase + annexDifference));
            if (diff < bestDiff) {
                bestDiff = diff;
                bestFreeAnnex = freeAnnex;
            }
        }

        int freeMain = freeCount - bestFreeAnnex;
        int totalMainWorkers = freeMain + fixedMainCount + bathCount;
        int totalAnnexWorkers = bestFreeAnnex + fixedAnnexCount;

        // ===== Step 3: 建物ごとの基本部屋数を計算 =====
        int mainNormalBase = totalMainWorkers > 0 ?
                (int) Math.ceil((double) (effectiveMainRooms + bathCount * bathReduction + mainSuppliesCount * suppliesReduction) / totalMainWorkers) : 0;
        int bathBaseRooms = Math.max(0, mainNormalBase - bathReduction);
        // ★追加: 備品発注担当（本館側）の部屋数 = 通常本館base - 6
        int mainSuppliesBaseRooms = Math.max(0, mainNormalBase - suppliesReduction);
        int annexBaseRooms = totalAnnexWorkers > 0 ?
                (int) Math.ceil((double) (effectiveAnnexRooms + annexSuppliesCount * suppliesReduction) / totalAnnexWorkers) : 0;
        // ★追加: 備品発注担当（別館側）の部屋数 = 通常別館base - 6
        int annexSuppliesBaseRooms = Math.max(0, annexBaseRooms - suppliesReduction);

        System.out.println("Step 2-3: 本館" + totalMainWorkers + "名(normalBase=" + mainNormalBase +
                ", bathBase=" + bathBaseRooms + ", 備品発注base=" + mainSuppliesBaseRooms +
                "), 別館" + totalAnnexWorkers + "名(annexBase=" + annexBaseRooms +
                ", 備品発注base=" + annexSuppliesBaseRooms + ")");

        // ===== Step 4: 各スタッフに部屋を割り当て =====

        // 大浴場スタッフ → 本館（UIの仕様で大浴場は本館固定）
        for (String name : bathStaffNames) {
            StaffConstraintInfo info = staffInfo.get(name);
            pattern.put(name, new StaffDistribution("", name, "本館のみ",
                    bathBaseRooms, 0, info.constraintType, true));
            System.out.println("Step 4: " + name + " (大浴場) → 本館" + bathBaseRooms + "室");
        }

        // 制約スタッフ
        for (String name : constraintStaffNames) {
            StaffConstraintInfo info = staffInfo.get(name);
            int rooms = "故障者制限".equals(info.constraintType) ? info.maxRooms : info.minRooms;
            // ★片建物限定: 故障者(両方)はStep1で決定した建物へまとめて配属
            String forcedBldg = faultBothBuilding.get(name);
            if ("本館のみ".equals(info.buildingAssignment) || "本館のみ".equals(forcedBldg)) {
                pattern.put(name, new StaffDistribution("", name, "本館のみ", rooms, 0, info.constraintType, false));
            } else if ("別館のみ".equals(info.buildingAssignment) || "別館のみ".equals(forcedBldg)) {
                pattern.put(name, new StaffDistribution("", name, "別館のみ", 0, rooms, info.constraintType, false));
            } else {
                // 業者制限(両方)は従来どおり半々で分割
                pattern.put(name, new StaffDistribution("", name, "両方",
                        rooms / 2, rooms - rooms / 2, info.constraintType, false));
            }
            System.out.println("Step 4: " + name + " (" + info.constraintType + ") → " + rooms + "室");
        }

        // 本館固定スタッフ（制限なし + 本館のみ）＋ 備品発注担当（本館側）
        for (String name : normalMainOnlyNames) {
            if (suppliesSet.contains(name)) {
                StaffDistribution sd = new StaffDistribution("", name, "本館のみ",
                        mainSuppliesBaseRooms, 0, "制限なし", false);
                sd.isSuppliesOrder = true;
                pattern.put(name, sd);
                System.out.println("Step 4: " + name + " (備品発注/本館) → 本館" + mainSuppliesBaseRooms + "室");
            } else {
                pattern.put(name, new StaffDistribution("", name, "本館のみ",
                        mainNormalBase, 0, "制限なし", false));
                System.out.println("Step 4: " + name + " (本館固定) → 本館" + mainNormalBase + "室");
            }
        }

        // 別館固定スタッフ（制限なし + 別館のみ）＋ 備品発注担当（別館側）
        for (String name : normalAnnexOnlyNames) {
            if (suppliesSet.contains(name)) {
                StaffDistribution sd = new StaffDistribution("", name, "別館のみ",
                        0, annexSuppliesBaseRooms, "制限なし", false);
                sd.isSuppliesOrder = true;
                pattern.put(name, sd);
                System.out.println("Step 4: " + name + " (備品発注/別館) → 別館" + annexSuppliesBaseRooms + "室");
            } else {
                pattern.put(name, new StaffDistribution("", name, "別館のみ",
                        0, annexBaseRooms, "制限なし", false));
                System.out.println("Step 4: " + name + " (別館固定) → 別館" + annexBaseRooms + "室");
            }
        }

        // 自由スタッフ: 先頭から本館、残りを別館
        for (int i = 0; i < freeMain; i++) {
            String name = normalFreeNames.get(i);
            pattern.put(name, new StaffDistribution("", name, "本館のみ",
                    mainNormalBase, 0, "制限なし", false));
            System.out.println("Step 4: " + name + " (自由→本館) → 本館" + mainNormalBase + "室");
        }
        for (int i = freeMain; i < freeCount; i++) {
            String name = normalFreeNames.get(i);
            pattern.put(name, new StaffDistribution("", name, "別館のみ",
                    0, annexBaseRooms, "制限なし", false));
            System.out.println("Step 4: " + name + " (自由→別館) → 別館" + annexBaseRooms + "室");
        }

        // ===== Step 5: 本館の合計を実際の部屋数に端数調整 =====
        adjustBuildingTotal(pattern, staffInfo, true, totalMainRooms);

        // ===== Step 6: 別館の合計を実際の部屋数に端数調整 =====
        adjustBuildingTotal(pattern, staffInfo, false, totalAnnexRooms);

        // ツイン配分は呼び出し側（calculatePatternWithCorrectLogic）で一度だけ実施
        return pattern;
    }

    /**
     * ★割り振りパターン生成のエントリポイント。
     * まずCP-SATで目標室数を解き（buildBaseAssignmentCPSAT）、
     * 解けない/例外/実行不能なら従来ヒューリスティック（buildBaseAssignmentHeuristic）にフォールバック。
     * 最後にツイン均等配分（distributeTwins）を一度だけ実施する。
     * @param annexDifference 別館と本館の目標差（-1 または -2）
     */
    private Map<String, StaffDistribution> calculatePatternWithCorrectLogic(int annexDifference) {
        Map<String, StaffDistribution> base = null;
        try {
            base = buildBaseAssignmentCPSAT(annexDifference);
        } catch (Throwable t) {
            System.out.println("CP-SAT例外: " + t + " → ヒューリスティックにフォールバック");
            base = null;
        }
        if (base == null) {
            System.out.println("CP-SAT解なし → ヒューリスティックにフォールバック (annexDiff=" + annexDifference + ")");
            base = buildBaseAssignmentHeuristic(annexDifference);
        }
        distributeTwins(base);
        return base;
    }

    /**
     * ★CP-SATで目標室数（ツイン前の本館/別館室数）を確定する。
     * 1) まず両建物1人なしで求解。
     * 2) 実行不能なら、両建物1人（条件7）を立てて再求解。
     * 3) それでも不能なら null を返す（呼び出し側がヒューリスティックにフォールバック）。
     */
    private Map<String, StaffDistribution> buildBaseAssignmentCPSAT(int annexDifference) {
        CPSATSolution s = solveWithPegRule(annexDifference, null);
        if (s != null) return s.pattern;
        // 条件7: 両建物に触れる1人を選出して再試行
        String splitName = pickSplitCandidate();
        if (splitName != null) {
            System.out.println("CP-SAT: 通常配分が実行不能 → 両建物1人(" + splitName + ")で再試行");
            s = solveWithPegRule(annexDifference, splitName);
            if (s != null) return s.pattern;
        }
        return null;
    }

    /** CP-SATの求解結果ホルダー。spanValid=trueのとき span=本館最大-別館最小 が有効。 */
    private static class CPSATSolution {
        final Map<String, StaffDistribution> pattern;
        final int mainTop;
        final int annexBottom;
        final boolean spanValid;
        final double objective; // 目的関数値（別館人数の探索で最良解の選定に使う）
        CPSATSolution(Map<String, StaffDistribution> p, int mainTop, int annexBottom, boolean spanValid, double objective) {
            this.pattern = p; this.mainTop = mainTop; this.annexBottom = annexBottom;
            this.spanValid = spanValid; this.objective = objective;
        }
        int span() { return mainTop - annexBottom; }
    }

    // ★案B: solveDistributionCPSAT が直近の求解で用いた自由人数情報（別館人数探索の範囲決定に使用）
    private int lastFreeCount = 0;
    private int lastHeuristicAnnex = 0;
    // ★片建物限定対応: 直近の求解での「故障者(両方)」人数（探索窓の拡張判定に使用）
    private int lastFaultBothCount = 0;

    /**
     * ★条件①: 大浴場・備品を「下段(mainBottom)基準」で解き、本館最大-別館最小の差で採否を決める。
     *  - 差 ≦ 2 … 下段基準を採用
     *  - 差 ≧ 3 … 不採用。上段(mainTop)基準で解き直す
     * （レベル集合が空などで差が評価できない場合は下段基準をそのまま採用）
     */
    private CPSATSolution solveWithPegRule(int annexDifference, String splitName) {
        CPSATSolution bottom = solveDistributionCPSAT(annexDifference, splitName, true);
        if (bottom != null) {
            if (!bottom.spanValid || bottom.span() <= 2) {
                return bottom; // 下段基準を採用
            }
            System.out.println("CP-SAT: 本館最大-別館最小=" + bottom.span() + "(≧3) → 上段基準で解き直し");
            CPSATSolution top = solveDistributionCPSAT(annexDifference, splitName, false);
            return (top != null) ? top : bottom; // 上段が不能なら下段にフォールバック
        }
        // 下段が実行不能なら上段で試す
        return solveDistributionCPSAT(annexDifference, splitName, false);
    }

    /** ★条件7: 両建物に触れる候補（制限なしスタッフを優先的に1名）。なければ null。 */
    private String pickSplitCandidate() {
        Map<String, StaffConstraintInfo> staffInfo = collectStaffConstraints();
        String fallback = null;
        for (Map.Entry<String, StaffConstraintInfo> e : staffInfo.entrySet()) {
            StaffConstraintInfo info = e.getValue();
            if (info.isBathCleaning || info.isSuppliesOrder) continue;
            if (!"制限なし".equals(info.constraintType)) continue;
            String bldg = info.buildingAssignment;
            if (!"本館のみ".equals(bldg) && !"別館のみ".equals(bldg)) {
                return e.getKey(); // 建物指定なし（自由）を最優先
            }
            if (fallback == null) fallback = e.getKey();
        }
        return fallback;
    }

    /**
     * ★CP-SAT本体: 目標室数（ツイン前）を求める。解が無ければ null。
     * splitName != null のとき、そのスタッフを「両建物担当（条件7）」として扱い、
     * 総室数 = 別館の制限なし最小室数 - 1 とする（内訳はソルバーに委ねる）。
     * pegToBottom=true のとき大浴場・備品を下段(mainBottom/annexBottom)基準、
     * false のとき上段(mainTop/annexTop)基準にする（条件①）。
     *
     * 制約の割り当て:
     *  - 大浴場 = 基準レベル - 削減(4/5)、備品発注 = 基準レベル - 6（ハード）
     *  - 故障者: 1 ≤ r ≤ min(maxRooms, 同建物の制限なし最大)、かつ r ≥ min(maxRooms-2, 同建物の制限なし最大)
     *           さらに目的関数で設定値(max)へ引き上げる（条件②）
     *  - 業者: minRooms ≤ r ≤ maxRooms
     *  - 建物ごとの合計 = 実部屋数（ハード）
     *  - 目的: 各建物のばらつき最小化（重み大）＋ 故障者の引き上げ＋ |本館レベル-別館レベル-d| 最小化
     */
    /** 旧シグネチャ互換: 別館人数はヒューリスティック任せ（forcedFreeAnnex=-1）。 */
    private CPSATSolution solveDistributionCPSAT(int annexDifference, String splitName, boolean pegToBottom) {
        return solveDistributionCPSAT(annexDifference, splitName, pegToBottom, -1);
    }

    /**
     * @param forcedFreeAnnex 0以上なら自由スタッフの別館人数をこの値に固定（案Bの探索用）。
     *                        -1のとき従来どおりヒューリスティックで決定する。
     */
    private CPSATSolution solveDistributionCPSAT(int annexDifference, String splitName, boolean pegToBottom, int forcedFreeAnnex) {
        com.google.ortools.Loader.loadNativeLibraries();

        Map<String, StaffConstraintInfo> staffInfo = collectStaffConstraints();
        final int bathReduction = bathCleaningType.reduction;
        final int suppliesReduction = AdaptiveRoomOptimizer.SUPPLIES_ORDER_REDUCTION;
        final int d = -annexDifference; // -1→1, -2→2
        final int BIG = totalMainRooms + totalAnnexRooms + 10;

        // ---- 分類（splitName は通常分類から除外し、後で両建物担当として別扱い）----
        List<String> bathStaff = new ArrayList<>();
        List<String> suppliesMain = new ArrayList<>();
        List<String> suppliesAnnex = new ArrayList<>();
        List<String> faultMain = new ArrayList<>();
        List<String> faultAnnex = new ArrayList<>();
        List<String> vendorMain = new ArrayList<>();
        List<String> vendorAnnex = new ArrayList<>();
        List<String> normalMain = new ArrayList<>();
        List<String> normalAnnex = new ArrayList<>();
        List<String> freeNames = new ArrayList<>();
        List<String> faultBoth = new ArrayList<>();   // 両方の故障者 → ★片建物限定: どちらの建物かはソルバーが決定（分割はしない）
        List<String> vendorBoth = new ArrayList<>();  // 両方の業者 → 建物配分はソルバーが決定

        for (Map.Entry<String, StaffConstraintInfo> e : staffInfo.entrySet()) {
            String name = e.getKey();
            if (name.equals(splitName)) continue;
            StaffConstraintInfo info = e.getValue();
            String bldg = info.buildingAssignment;
            if (info.isBathCleaning) {
                bathStaff.add(name);
            } else if (info.isSuppliesOrder) {
                if ("別館のみ".equals(bldg)) suppliesAnnex.add(name); else suppliesMain.add(name);
            } else if ("故障者制限".equals(info.constraintType)) {
                if ("本館のみ".equals(bldg)) faultMain.add(name);
                else if ("別館のみ".equals(bldg)) faultAnnex.add(name);
                else faultBoth.add(name);   // 両方: ★片建物限定: 本館/別館のどちらにまとめるかをソルバーに委ねる（分割はしない）
            } else if (!"制限なし".equals(info.constraintType)) {
                // 業者制限（enum LOWER_RANGE / displayNameは"リライアンス用"）。
                // 元ロジックと同じく「制限なし・故障者以外」をすべて業者(min〜max)として扱う。
                if ("本館のみ".equals(bldg)) vendorMain.add(name);
                else if ("別館のみ".equals(bldg)) vendorAnnex.add(name);
                else vendorBoth.add(name);  // 両方: 建物配分はソルバーに委ねる
            } else if ("本館のみ".equals(bldg)) {
                normalMain.add(name);
            } else if ("別館のみ".equals(bldg)) {
                normalAnnex.add(name);
            } else {
                freeNames.add(name);
            }
        }

        // ---- 自由スタッフの本館/別館配分（従来Step2と同じ基準で人数を決定）----
        // ※両方の制約スタッフはソルバーが建物を決めるが、ここでは自由人数決定のための概算として半々で見積もる
        int constraintMainRooms = 0, constraintAnnexRooms = 0;
        for (String n : faultMain) constraintMainRooms += staffInfo.get(n).maxRooms;
        for (String n : faultAnnex) constraintAnnexRooms += staffInfo.get(n).maxRooms;
        for (String n : vendorMain) constraintMainRooms += staffInfo.get(n).minRooms;
        for (String n : vendorAnnex) constraintAnnexRooms += staffInfo.get(n).minRooms;
        for (String n : faultBoth) { int r = staffInfo.get(n).maxRooms; constraintMainRooms += r / 2; constraintAnnexRooms += r - r / 2; }
        for (String n : vendorBoth) { int r = staffInfo.get(n).minRooms; constraintMainRooms += r / 2; constraintAnnexRooms += r - r / 2; }

        int bathCount = bathStaff.size();
        int mainSuppliesCount = suppliesMain.size();
        int annexSuppliesCount = suppliesAnnex.size();
        int fixedMainCount = normalMain.size() + suppliesMain.size();
        int fixedAnnexCount = normalAnnex.size() + suppliesAnnex.size();
        int freeCount = freeNames.size();
        int effectiveMainRooms = totalMainRooms - constraintMainRooms;
        int effectiveAnnexRooms = totalAnnexRooms - constraintAnnexRooms;

        int bestFreeAnnex = 0;
        double bestDiff = Double.MAX_VALUE;
        for (int fa = 0; fa <= freeCount; fa++) {
            int fm = freeCount - fa;
            int mainWorkers = fm + fixedMainCount + bathCount;
            int annexWorkers = fa + fixedAnnexCount;
            if (mainWorkers <= 0 && effectiveMainRooms > 0) continue;
            if (annexWorkers <= 0 && effectiveAnnexRooms > 0) continue;
            double mainBase = mainWorkers > 0 ?
                    (double) (effectiveMainRooms + bathCount * bathReduction + mainSuppliesCount * suppliesReduction) / mainWorkers : 0;
            double annexBase = annexWorkers > 0 ?
                    (double) (effectiveAnnexRooms + annexSuppliesCount * suppliesReduction) / annexWorkers : 0;
            double diff = Math.abs(annexBase - (mainBase + annexDifference));
            if (diff < bestDiff) { bestDiff = diff; bestFreeAnnex = fa; }
        }
        // ★案B: 探索用に「ヒューリスティック中心値」と freeCount を記録（解なしでも有効）
        this.lastHeuristicAnnex = bestFreeAnnex;
        this.lastFreeCount = freeCount;
        // ★片建物限定対応: 故障者(両方)は実際には片建物にまとまるため、
        //   上の半々見積もりとズレる。solveBest側で探索窓を広げて吸収する。
        this.lastFaultBothCount = faultBoth.size();
        // forcedFreeAnnex が指定されていれば、その別館人数で固定する
        if (forcedFreeAnnex >= 0) {
            bestFreeAnnex = Math.min(forcedFreeAnnex, freeCount);
        }
        int freeMainN = freeCount - bestFreeAnnex;
        List<String> freeMain = new ArrayList<>(freeNames.subList(0, freeMainN));
        List<String> freeAnnex = new ArrayList<>(freeNames.subList(freeMainN, freeCount));

        // ---- CP-SATモデル構築 ----
        CpModel model = new CpModel();
        Map<String, IntVar> mv = new HashMap<>();
        Map<String, IntVar> av = new HashMap<>();

        List<String> mainMembers = new ArrayList<>();
        mainMembers.addAll(bathStaff); mainMembers.addAll(suppliesMain);
        mainMembers.addAll(faultMain); mainMembers.addAll(vendorMain);
        mainMembers.addAll(normalMain); mainMembers.addAll(freeMain);
        List<String> annexMembers = new ArrayList<>();
        annexMembers.addAll(suppliesAnnex); annexMembers.addAll(faultAnnex);
        annexMembers.addAll(vendorAnnex); annexMembers.addAll(normalAnnex);
        annexMembers.addAll(freeAnnex);

        for (String n : mainMembers) mv.put(n, model.newIntVar(0, BIG, "m_" + n));
        for (String n : annexMembers) av.put(n, model.newIntVar(0, BIG, "a_" + n));

        // 両方の制約スタッフ: 本館・別館の両変数を持ち、配分はソルバーが決定
        List<String> bothMembers = new ArrayList<>();
        bothMembers.addAll(faultBoth); bothMembers.addAll(vendorBoth);
        for (String n : bothMembers) {
            mv.put(n, model.newIntVar(0, BIG, "m_" + n));
            av.put(n, model.newIntVar(0, BIG, "a_" + n));
        }

        // レベル集合（制限なしの素のスタッフ＝本館/別館の基準レベル）
        List<IntVar> levelMainVars = new ArrayList<>();
        for (String n : normalMain) levelMainVars.add(mv.get(n));
        for (String n : freeMain) levelMainVars.add(mv.get(n));
        List<IntVar> levelAnnexVars = new ArrayList<>();
        for (String n : normalAnnex) levelAnnexVars.add(av.get(n));
        for (String n : freeAnnex) levelAnnexVars.add(av.get(n));

        IntVar mainTop = model.newIntVar(0, BIG, "mainTop");
        IntVar mainBottom = model.newIntVar(0, BIG, "mainBottom");
        if (!levelMainVars.isEmpty()) {
            model.addMaxEquality(mainTop, levelMainVars.toArray(new IntVar[0]));
            model.addMinEquality(mainBottom, levelMainVars.toArray(new IntVar[0]));
        } else {
            model.addEquality(mainBottom, mainTop); // ばらつき0
        }
        IntVar annexTop = model.newIntVar(0, BIG, "annexTop");
        IntVar annexBottom = model.newIntVar(0, BIG, "annexBottom");
        if (!levelAnnexVars.isEmpty()) {
            model.addMaxEquality(annexTop, levelAnnexVars.toArray(new IntVar[0]));
            model.addMinEquality(annexBottom, levelAnnexVars.toArray(new IntVar[0]));
        } else {
            model.addEquality(annexBottom, annexTop);
        }

        // 大浴場・備品の基準レベル（条件①: 下段=mainBottom/annexBottom, 上段=mainTop/annexTop）
        IntVar mainPeg = pegToBottom ? mainBottom : mainTop;
        IntVar annexPeg = pegToBottom ? annexBottom : annexTop;

        // 大浴場 = 基準レベル - 削減（4 or 5）
        for (String n : bathStaff) {
            model.addEquality(mv.get(n),
                    LinearExpr.newBuilder().addTerm(mainPeg, 1).add(-bathReduction).build());
        }
        // 備品発注（本館）= 基準レベル - 6
        for (String n : suppliesMain) {
            model.addEquality(mv.get(n),
                    LinearExpr.newBuilder().addTerm(mainPeg, 1).add(-suppliesReduction).build());
        }
        // 備品発注（別館）= 基準レベル - 6
        for (String n : suppliesAnnex) {
            model.addEquality(av.get(n),
                    LinearExpr.newBuilder().addTerm(annexPeg, 1).add(-suppliesReduction).build());
        }
        // 故障者制限（本館）: 1 ≤ r ≤ min(maxRooms, mainTop), r ≥ min(maxRooms-2, mainTop)
        List<IntVar> faultVars = new ArrayList<>(); // 条件②: 目的関数で設定値(max)へ引き上げる対象
        for (String n : faultMain) {
            int max = staffInfo.get(n).maxRooms;
            IntVar v = mv.get(n);
            model.addLessOrEqual(v, max);
            model.addLessOrEqual(v, mainTop);
            IntVar lo = model.newIntVar(-BIG, BIG, "flo_" + n);
            model.addMinEquality(lo, new IntVar[]{ model.newConstant(max - 2), mainTop });
            model.addGreaterOrEqual(v, lo);
            model.addGreaterOrEqual(v, 1);
            faultVars.add(v);
        }
        for (String n : faultAnnex) {
            int max = staffInfo.get(n).maxRooms;
            IntVar v = av.get(n);
            model.addLessOrEqual(v, max);
            model.addLessOrEqual(v, annexTop);
            IntVar lo = model.newIntVar(-BIG, BIG, "flo_" + n);
            model.addMinEquality(lo, new IntVar[]{ model.newConstant(max - 2), annexTop });
            model.addGreaterOrEqual(v, lo);
            model.addGreaterOrEqual(v, 1);
            faultVars.add(v);
        }
        // 業者制限: minRooms ≤ r ≤ maxRooms
        for (String n : vendorMain) {
            StaffConstraintInfo info = staffInfo.get(n);
            model.addGreaterOrEqual(mv.get(n), info.minRooms);
            model.addLessOrEqual(mv.get(n), info.maxRooms);
        }
        for (String n : vendorAnnex) {
            StaffConstraintInfo info = staffInfo.get(n);
            model.addGreaterOrEqual(av.get(n), info.minRooms);
            model.addLessOrEqual(av.get(n), info.maxRooms);
        }
        // 両方の故障者: どちらの建物にするかはソルバーが決定。
        // ★片建物限定: 本館・別館のどちらか一方にまとめて配属（両建物への分割は禁止）。
        // 取り分は通常レベル以下、合計≤max(≥1)、設定値へ引き上げ
        for (String n : faultBoth) {
            int max = staffInfo.get(n).maxRooms;
            IntVar m = mv.get(n), a = av.get(n);
            model.addLessOrEqual(m, mainTop);
            model.addLessOrEqual(a, annexTop);
            // ★片建物限定: useMain=true なら別館分を0に、false なら本館分を0に強制
            BoolVar useMain = model.newBoolVar("fbUseMain_" + n);
            model.addEquality(a, 0).onlyEnforceIf(useMain);
            model.addEquality(m, 0).onlyEnforceIf(useMain.not());
            LinearExpr total = LinearExpr.newBuilder().addTerm(m, 1).addTerm(a, 1).build();
            model.addLessOrEqual(total, max);
            model.addGreaterOrEqual(total, 1);
            faultVars.add(m); faultVars.add(a); // 合計を設定値へ引き上げ（条件②）
        }
        // 両方の業者: 合計が minRooms〜maxRooms。建物配分はソルバー任せ
        for (String n : vendorBoth) {
            StaffConstraintInfo info = staffInfo.get(n);
            IntVar m = mv.get(n), a = av.get(n);
            LinearExpr total = LinearExpr.newBuilder().addTerm(m, 1).addTerm(a, 1).build();
            model.addGreaterOrEqual(total, info.minRooms);
            model.addLessOrEqual(total, info.maxRooms);
        }
        // 制限なし（本館/別館/自由）: 1室以上
        for (String n : normalMain) model.addGreaterOrEqual(mv.get(n), 1);
        for (String n : freeMain) model.addGreaterOrEqual(mv.get(n), 1);
        for (String n : normalAnnex) model.addGreaterOrEqual(av.get(n), 1);
        for (String n : freeAnnex) model.addGreaterOrEqual(av.get(n), 1);

        // 両建物担当（条件7）
        IntVar splitMain = null, splitAnnex = null;
        if (splitName != null) {
            if (levelAnnexVars.isEmpty()) return null; // 別館最小の基準が無いので不可
            splitMain = model.newIntVar(0, BIG, "split_m");
            splitAnnex = model.newIntVar(0, BIG, "split_a");
            model.addGreaterOrEqual(splitMain, 1);
            model.addGreaterOrEqual(splitAnnex, 1);
            // 総室数 = 別館最小(annexBottom) - 1
            model.addEquality(
                    LinearExpr.newBuilder().addTerm(splitMain, 1).addTerm(splitAnnex, 1).build(),
                    LinearExpr.newBuilder().addTerm(annexBottom, 1).add(-1).build());
        }

        // 建物ごとの合計（ハード）
        LinearExprBuilder mainSum = LinearExpr.newBuilder();
        for (String n : mainMembers) mainSum.addTerm(mv.get(n), 1);
        for (String n : bothMembers) mainSum.addTerm(mv.get(n), 1);
        if (splitMain != null) mainSum.addTerm(splitMain, 1);
        model.addEquality(mainSum.build(), totalMainRooms);

        LinearExprBuilder annexSum = LinearExpr.newBuilder();
        for (String n : annexMembers) annexSum.addTerm(av.get(n), 1);
        for (String n : bothMembers) annexSum.addTerm(av.get(n), 1);
        if (splitAnnex != null) annexSum.addTerm(splitAnnex, 1);
        model.addEquality(annexSum.build(), totalAnnexRooms);

        // 目的関数: ばらつき最小化（重み大）＋ |本館レベル-別館レベル-d| 最小化（重み小）
        IntVar spreadMain = model.newIntVar(0, BIG, "spreadMain");
        model.addEquality(spreadMain,
                LinearExpr.newBuilder().addTerm(mainTop, 1).addTerm(mainBottom, -1).build());
        IntVar spreadAnnex = model.newIntVar(0, BIG, "spreadAnnex");
        model.addEquality(spreadAnnex,
                LinearExpr.newBuilder().addTerm(annexTop, 1).addTerm(annexBottom, -1).build());
        IntVar dDiff = model.newIntVar(-BIG, BIG, "dDiff");
        model.addEquality(dDiff,
                LinearExpr.newBuilder().addTerm(mainTop, 1).addTerm(annexTop, -1).add(-d).build());
        IntVar dNeg = model.newIntVar(-BIG, BIG, "dNeg");
        model.addEquality(dNeg, LinearExpr.newBuilder().addTerm(dDiff, -1).build());
        IntVar dAbs = model.newIntVar(0, BIG, "dAbs");
        model.addMaxEquality(dAbs, new IntVar[]{ dDiff, dNeg }); // dAbs = |dDiff|

        final int W_SPREAD = 10; // 各建物のばらつき（最優先）
        final int W_FAULT = 4;   // 故障者を設定値(max)へ引き上げる（ばらつきより弱く、dより強い）
        final int W_D = 3;       // |本館レベル-別館レベル-d|
        LinearExprBuilder objective = LinearExpr.newBuilder()
                .addTerm(spreadMain, W_SPREAD)
                .addTerm(spreadAnnex, W_SPREAD)
                .addTerm(dAbs, W_D);
        // 故障者は r を増やすほどコストを下げる（= 設定値maxへ引き上げ）。必要が無い限り下限へ落とさない。
        for (IntVar fv : faultVars) {
            objective.addTerm(fv, -W_FAULT);
        }
        model.minimize(objective.build());

        // ---- 求解 ----
        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(5.0);
        CpSolverStatus status = solver.solve(model);
        if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) {
            System.out.println("CP-SAT status=" + status + " (annexDiff=" + annexDifference + ", split=" + splitName + ")");
            return null;
        }

        // ---- 結果をStaffDistributionへ（ツイン前）----
        Map<String, StaffDistribution> pattern = new HashMap<>();
        for (String n : bathStaff) {
            pattern.put(n, new StaffDistribution("", n, "本館のみ",
                    (int) solver.value(mv.get(n)), 0, staffInfo.get(n).constraintType, true));
        }
        for (String n : suppliesMain) {
            StaffDistribution sd = new StaffDistribution("", n, "本館のみ",
                    (int) solver.value(mv.get(n)), 0, "制限なし", false);
            sd.isSuppliesOrder = true; pattern.put(n, sd);
        }
        for (String n : suppliesAnnex) {
            StaffDistribution sd = new StaffDistribution("", n, "別館のみ",
                    0, (int) solver.value(av.get(n)), "制限なし", false);
            sd.isSuppliesOrder = true; pattern.put(n, sd);
        }
        for (String n : faultMain)  putMainDist(pattern, n, (int) solver.value(mv.get(n)), staffInfo.get(n).constraintType);
        for (String n : vendorMain) putMainDist(pattern, n, (int) solver.value(mv.get(n)), staffInfo.get(n).constraintType);
        for (String n : normalMain) putMainDist(pattern, n, (int) solver.value(mv.get(n)), "制限なし");
        for (String n : freeMain)   putMainDist(pattern, n, (int) solver.value(mv.get(n)), "制限なし");
        for (String n : faultAnnex)  putAnnexDist(pattern, n, (int) solver.value(av.get(n)), staffInfo.get(n).constraintType);
        for (String n : vendorAnnex) putAnnexDist(pattern, n, (int) solver.value(av.get(n)), staffInfo.get(n).constraintType);
        for (String n : normalAnnex) putAnnexDist(pattern, n, (int) solver.value(av.get(n)), "制限なし");
        for (String n : freeAnnex)   putAnnexDist(pattern, n, (int) solver.value(av.get(n)), "制限なし");
        for (String n : bothMembers) {
            putBothDist(pattern, n, (int) solver.value(mv.get(n)), (int) solver.value(av.get(n)),
                    staffInfo.get(n).constraintType);
        }
        if (splitName != null) {
            pattern.put(splitName, new StaffDistribution("", splitName, "両方",
                    (int) solver.value(splitMain), (int) solver.value(splitAnnex),
                    staffInfo.get(splitName).constraintType, false));
        }

        boolean spanValid = !levelMainVars.isEmpty() && !levelAnnexVars.isEmpty();
        int mainTopVal = (int) solver.value(mainTop);
        int annexBottomVal = (int) solver.value(annexBottom);
        System.out.println("CP-SAT求解成功: status=" + status + ", annexDiff=" + annexDifference +
                ", split=" + splitName + ", peg=" + (pegToBottom ? "下段" : "上段") +
                ", mainTop=" + mainTopVal + ", annexBottom=" + annexBottomVal +
                ", obj=" + solver.objectiveValue());
        return new CPSATSolution(pattern, mainTopVal, annexBottomVal, spanValid, solver.objectiveValue());
    }

    private void putMainDist(Map<String, StaffDistribution> p, String n, int rooms, String ct) {
        p.put(n, new StaffDistribution("", n, "本館のみ", rooms, 0, ct, false));
    }

    private void putAnnexDist(Map<String, StaffDistribution> p, String n, int rooms, String ct) {
        p.put(n, new StaffDistribution("", n, "別館のみ", 0, rooms, ct, false));
    }

    /** 両方の制約スタッフ: 実際の配分結果から建物指定を決定して格納する。 */
    private void putBothDist(Map<String, StaffDistribution> p, String n, int main, int annex, String ct) {
        String bldg = (main > 0 && annex > 0) ? "両方" : (annex > 0 ? "別館のみ" : "本館のみ");
        p.put(n, new StaffDistribution("", n, bldg, main, annex, ct, false));
    }

    /**
     * ★共通ヘルパー: ツイン振り分け（条件③）。
     * CP-SAT/ヒューリスティック両方の結果に一度だけ適用する。
     * 基礎分 floor(総ツイン/人数) を全員に均等配分し、余りはリライアンス用（業者）が
     * 優先吸収する。これにより通常スタッフ同士のツイン数が揃う。
     *
     * ★★★本館ツイン統合: 本館側の事前ツイン配分は廃止（totalTwin=0で呼び出し、
     * 全室シングル等として初期化のみ行う）。本館ツインの実配分は部屋番号割り振り段階
     * （RoomNumberAssigner）で部屋番号順に自然に決まり「1人1部屋まで」に正規化される。
     * 別館は従来どおり均等配分を行う（換算膨張・業者吸収の仕組みを維持）。
     */
    private void distributeTwins(Map<String, StaffDistribution> pattern) {
        allocateTwins(pattern, true, 0);  // ★★★本館: ツイン事前配分なし（初期化のみ）
        allocateTwins(pattern, false, totalAnnexTwinRooms);
    }

    private void allocateTwins(Map<String, StaffDistribution> pattern, boolean isMain, int totalTwin) {
        // 対象スタッフ（その建物に1室以上持つ）
        List<String> participants = new ArrayList<>();
        for (Map.Entry<String, StaffDistribution> e : pattern.entrySet()) {
            int rooms = isMain ? e.getValue().mainAssignedRooms : e.getValue().annexAssignedRooms;
            if (rooms > 0) participants.add(e.getKey());
        }
        // 初期化: 全てシングル
        for (String n : participants) {
            StaffDistribution d = pattern.get(n);
            if (isMain) { d.mainSingleAssignedRooms = d.mainAssignedRooms; d.mainTwinAssignedRooms = 0; }
            else        { d.annexSingleAssignedRooms = d.annexAssignedRooms; d.annexTwinAssignedRooms = 0; }
        }
        String label = isMain ? "本館" : "別館";
        if (participants.isEmpty() || totalTwin <= 0) {
            System.out.println(label + "ツイン配分完了: " + participants.size() + "名（ツイン0）");
            return;
        }

        int n = participants.size();
        int base = totalTwin / n;
        int remaining = totalTwin;
        // 基礎分（全員均等。シングル残数で上限）
        for (String nm : participants) {
            int give = Math.min(base, singleCount(pattern, nm, isMain));
            addTwin(pattern, nm, give, isMain);
            remaining -= give;
        }
        // 余りはリライアンス用（業者）が優先吸収 → 通常スタッフは均等のまま
        List<String> vendors = new ArrayList<>();
        for (String nm : participants) {
            if ("リライアンス用".equals(pattern.get(nm).constraintType)) vendors.add(nm);
        }
        remaining = roundRobinTwin(pattern, vendors, remaining, isMain);
        // 業者が居ない/吸収しきれない分は全員へラウンドロビン（従来動作）
        if (remaining > 0) {
            remaining = roundRobinTwin(pattern, participants, remaining, isMain);
        }
        System.out.println(label + "ツイン配分完了: " + n + "名, 基礎=" + base + "/人, 業者吸収後残=" + remaining);
    }

    /** 指定リストへツインをラウンドロビンで割り当て、割り当てきれなかった残数を返す。 */
    private int roundRobinTwin(Map<String, StaffDistribution> pattern, List<String> list, int remaining, boolean isMain) {
        if (list.isEmpty() || remaining <= 0) return remaining;
        int guard = 0, maxGuard = list.size() * 1000 + 10;
        boolean anyCapacity = true;
        while (remaining > 0 && anyCapacity) {
            anyCapacity = false;
            for (int i = 0; i < list.size() && remaining > 0; i++) {
                String nm = list.get(i);
                if (singleCount(pattern, nm, isMain) > 0) {
                    addTwin(pattern, nm, 1, isMain);
                    remaining--;
                    anyCapacity = true;
                }
            }
            if (++guard > maxGuard) { System.out.println("警告: ツイン配分ループ上限"); break; }
        }
        return remaining;
    }

    private int singleCount(Map<String, StaffDistribution> pattern, String name, boolean isMain) {
        StaffDistribution d = pattern.get(name);
        return isMain ? d.mainSingleAssignedRooms : d.annexSingleAssignedRooms;
    }

    private void addTwin(Map<String, StaffDistribution> pattern, String name, int k, boolean isMain) {
        if (k <= 0) return;
        StaffDistribution d = pattern.get(name);
        if (isMain) { d.mainSingleAssignedRooms -= k; d.mainTwinAssignedRooms += k; }
        else        { d.annexSingleAssignedRooms -= k; d.annexTwinAssignedRooms += k; }
    }

    /**
     * ★★修正: 建物の合計部屋数を端数調整するユーティリティ
     * 制約スタッフも制限範囲内で調整対象にする
     * - 故障者制限: 設定値以下なら減らせる（増やすのは設定値まで）
     * - 業者制限: minRooms〜maxRoomsの範囲内で増減可能
     * - 制限なし（非大浴場）: 自由に増減
     * - 大浴場: 調整対象外
     */
    private void adjustBuildingTotal(Map<String, StaffDistribution> pattern,
                                     Map<String, StaffConstraintInfo> staffInfo,
                                     boolean isMain, int targetTotal) {
        int actual = pattern.values().stream()
                .mapToInt(d -> isMain ? d.mainAssignedRooms : d.annexAssignedRooms)
                .sum();

        if (actual == targetTotal) return;

        int excess = actual - targetTotal; // 正=超過, 負=不足

        // 調整対象: この建物に部屋がある非大浴場・非備品発注スタッフ
        List<StaffDistribution> adjustable = pattern.values().stream()
                .filter(d -> (isMain ? d.mainAssignedRooms : d.annexAssignedRooms) > 0)
                .filter(d -> !d.isBathCleaning && !d.isSuppliesOrder)
                .sorted(excess > 0 ?
                        // 超過: 部屋数が多い順（多い人から減らす）
                        (a, b) -> Integer.compare(
                                isMain ? b.mainAssignedRooms : b.annexAssignedRooms,
                                isMain ? a.mainAssignedRooms : a.annexAssignedRooms) :
                        // 不足: 部屋数が少ない順（少ない人から増やす）
                        (a, b) -> Integer.compare(
                                isMain ? a.mainAssignedRooms : a.annexAssignedRooms,
                                isMain ? b.mainAssignedRooms : b.annexAssignedRooms))
                .collect(Collectors.toList());

        if (adjustable.isEmpty()) return;

        // 調整優先順位: まず制限なしスタッフ → 次に業者制限 → 最後に故障者制限
        final boolean isExcess = excess > 0;
        adjustable.sort((a, b) -> {
            int priorityA = getAdjustPriority(a.constraintType);
            int priorityB = getAdjustPriority(b.constraintType);
            if (priorityA != priorityB) return Integer.compare(priorityA, priorityB);
            // 同じ優先度なら部屋数順
            if (isExcess) {
                return Integer.compare(
                        isMain ? b.mainAssignedRooms : b.annexAssignedRooms,
                        isMain ? a.mainAssignedRooms : a.annexAssignedRooms);
            } else {
                return Integer.compare(
                        isMain ? a.mainAssignedRooms : a.annexAssignedRooms,
                        isMain ? b.mainAssignedRooms : b.annexAssignedRooms);
            }
        });

        String building = isMain ? "本館" : "別館";
        int idx = 0;
        int noChangeCount = 0;
        while (excess != 0 && noChangeCount < adjustable.size()) {
            StaffDistribution d = adjustable.get(idx % adjustable.size());
            StaffConstraintInfo info = staffInfo.get(d.staffName);
            int currentRooms = d.assignedRooms; // 通常合計（本館+別館）
            boolean changed = false;

            if (excess > 0) {
                // 超過 → 減らす
                int rooms = isMain ? d.mainAssignedRooms : d.annexAssignedRooms;
                if (rooms > 1 && canDecrease(info, currentRooms)) {
                    if (isMain) d.mainAssignedRooms--;
                    else d.annexAssignedRooms--;
                    d.assignedRooms = d.mainAssignedRooms + d.annexAssignedRooms;
                    excess--;
                    changed = true;
                }
            } else {
                // 不足 → 増やす
                if (canIncrease(info, currentRooms)) {
                    if (isMain) d.mainAssignedRooms++;
                    else d.annexAssignedRooms++;
                    d.assignedRooms = d.mainAssignedRooms + d.annexAssignedRooms;
                    excess++;
                    changed = true;
                }
            }

            if (changed) {
                noChangeCount = 0;
            } else {
                noChangeCount++;
            }
            idx++;
        }

        int adjusted = pattern.values().stream()
                .mapToInt(d -> isMain ? d.mainAssignedRooms : d.annexAssignedRooms).sum();
        System.out.println("Step 5-6: " + building + "調整 " + actual + "→" + adjusted + "室 (目標" + targetTotal + ")");
    }

    /**
     * ★★新規: 調整優先度（低い値=優先的に調整）
     */
    private int getAdjustPriority(String constraintType) {
        if ("制限なし".equals(constraintType)) return 0;  // 最優先
        if ("業者制限".equals(constraintType)) return 1;   // 次点（範囲内で調整可能）
        if ("故障者制限".equals(constraintType)) return 2;  // 最後（上限以下でのみ調整可能）
        return 3;
    }

    /**
     * ★★新規: 制約範囲内で部屋数を減らせるか判定
     */
    private boolean canDecrease(StaffConstraintInfo info, int currentRooms) {
        if ("制限なし".equals(info.constraintType)) {
            return true; // 制限なしは自由
        }
        if ("故障者制限".equals(info.constraintType)) {
            // 故障者制限: 設定値以下なので、さらに減らせる（最低1室は残す）
            return currentRooms > 1;
        }
        if ("業者制限".equals(info.constraintType)) {
            // 業者制限: minRooms以上を維持
            return currentRooms > info.minRooms;
        }
        return false;
    }

    /**
     * ★★新規: 制約範囲内で部屋数を増やせるか判定
     */
    private boolean canIncrease(StaffConstraintInfo info, int currentRooms) {
        if ("制限なし".equals(info.constraintType)) {
            return true; // 制限なしは自由
        }
        if ("故障者制限".equals(info.constraintType)) {
            // 故障者制限: maxRooms（設定値）まで増やせる
            return currentRooms < info.maxRooms;
        }
        if ("業者制限".equals(info.constraintType)) {
            // 業者制限: maxRoomsまで増やせる
            return currentRooms < info.maxRooms;
        }
        return false;
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
                        constraint.isSuppliesOrderStaff,
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
        pattern.values().forEach(d -> {
                    String floorInfo = (d.preferredFloors != null && !d.preferredFloors.isEmpty())
                            ? " 指定階=" + d.getPreferredFloorsText() : "";
                    String linenInfo = d.isLinenClosetCleaning
                            ? " リネン庫=" + d.linenClosetFloorCount + "階" : "";
                    System.out.println("  " + d.staffName + ": 本館(S" + d.mainSingleAssignedRooms +
                            "+T" + d.mainTwinAssignedRooms +
                            ") 別館(S" + d.annexSingleAssignedRooms +
                            "+T" + d.annexTwinAssignedRooms +
                            ") = " + d.assignedRooms +
                            "室 (換算" + String.format("%.1f", d.getConvertedTotal()) + ")" +
                            floorInfo + linenInfo);
                }
        );
    }

    private void switchPattern() {
        applySelectedPattern();
    }

    /** 選択中ラベルのパターンを currentPattern に反映する。解なし(null)なら専用表示。 */
    private void applySelectedPattern() {
        // ★★追加: パターン切替・リセット時は手動並び順を破棄して自動ソートに戻す
        displayOrder = null;
        String selected = (String) patternSelector.getSelectedItem();
        Map<String, StaffDistribution> p = (selected != null) ? patternMap.get(selected) : null;
        if (p == null) {
            currentPattern = null;
            showNoSolution();
        } else {
            currentPattern = deepCopyPattern(p);
            updateDataPanel();
        }
    }

    /** 選択中パターンが解なしのときの表示（空表＋メッセージ）。 */
    private void showNoSolution() {
        if (dataPanel == null) return;
        dataPanel.removeAll();
        swapSelectionBoxes.clear();  // ★★追加: 行が無いため入れ替え選択もクリア
        JLabel msg = new JLabel("この基準（大浴場 下段／上段）では条件を満たす割り振りが見つかりませんでした。別のパターンを選択してください。");
        msg.setFont(new Font("MS Gothic", Font.PLAIN, 13));
        dataPanel.add(msg);
        dataPanel.revalidate();
        dataPanel.repaint();
        if (summaryLabel != null) summaryLabel.setText("");
        if (warningLabel != null) warningLabel.setText("");
    }

    // ★★拡張: 6区分の情報を含むサマリー（ECO・リネン庫含む）
    private void updateSummary() {
        if (currentPattern == null) {
            if (summaryLabel != null) summaryLabel.setText("");
            if (warningLabel != null) warningLabel.setText("");
            return;
        }
        int totalAssignedMainSingle = currentPattern.values().stream()
                .mapToInt(d -> d.mainSingleAssignedRooms).sum();
        int totalAssignedMainTwin = currentPattern.values().stream()
                .mapToInt(d -> d.mainTwinAssignedRooms).sum();
        int totalAssignedAnnexSingle = currentPattern.values().stream()
                .mapToInt(d -> d.annexSingleAssignedRooms).sum();
        int totalAssignedAnnexTwin = currentPattern.values().stream()
                .mapToInt(d -> d.annexTwinAssignedRooms).sum();

        // ECOはCP-SATが自動配分するため表示しない
        double totalConvertedRooms = currentPattern.values().stream()
                .mapToDouble(StaffDistribution::getConvertedTotal).sum();

        // リネン庫情報
        int totalLinenFloorCount = 0;
        for (StaffDistribution dist : currentPattern.values()) {
            if (dist.isLinenClosetCleaning && dist.linenClosetFloorCount > 0) {
                totalLinenFloorCount += dist.linenClosetFloorCount;
            }
        }
        String linenInfo = "";
        int totalFloors = totalAvailableFloors > 0 ? totalAvailableFloors : 15;
        if (totalLinenFloorCount > 0) {
            linenInfo = String.format(" | リネン庫: %d/%d", totalLinenFloorCount, totalFloors);
        }

        // ★★★本館ツイン統合: 本館は合計表記（区別なし）、別館は従来どおりS+T表記
        String mainTotalText = formatWithColor(
                totalAssignedMainSingle + totalAssignedMainTwin, totalMainRooms, "");
        String annexSingleText = formatWithColor(totalAssignedAnnexSingle, totalAnnexSingleRooms, "S");
        String annexTwinText = formatWithColor(totalAssignedAnnexTwin, totalAnnexTwinRooms, "T");

        String summaryText = String.format(
                "<html>割当: 本館%s, 別館%s+%s | 換算合計: %d%s</html>",
                mainTotalText,
                annexSingleText, annexTwinText,
                (int) totalConvertedRooms, linenInfo
        );

        summaryLabel.setText(summaryText);
        updateWarningLabel();
    }

    /** ★警告メッセージ表示は無効化（ユーザー要望により非表示） */
    private void updateWarningLabel() {
        if (warningLabel == null) return;
        warningLabel.setText("");
    }

    /** 現在選択中のパターンラベルから目標換算差（1 or 2）を返す。 */
    private int currentDesiredConvDiff() {
        Object sel = (patternSelector != null) ? patternSelector.getSelectedItem() : null;
        String label = (sel != null) ? sel.toString() : defaultPatternLabel;
        if (PAT_2B.equals(label) || PAT_2T.equals(label)) return 2;
        if (PAT_SPLIT.equals(label)) return splitPatternConvDiff; // ★★館またぎ: 採用基準の換算差
        return 1; // PAT_1B / PAT_1T / 不明時は1部屋差扱い
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * ★過制約（条件未達）の検出。ツイン配分後のパターンを点検し、満たせなかった項目を返す。
     * 1.本館通常の差≧2 / 2.別館通常の差≧2 / 3.換算逆転 / 4.業者の換算上限超過 / 5.換算の部屋差が目標と不一致。
     */
    private java.util.List<String> computeWarnings(Map<String, StaffDistribution> pattern, int desiredConvDiff) {
        java.util.List<String> warnings = new java.util.ArrayList<>();
        if (pattern == null || pattern.isEmpty()) return warnings;
        Map<String, StaffConstraintInfo> staffInfo = collectStaffConstraints();

        double mainMaxConv = -1, mainMinConv = Double.MAX_VALUE;
        double annexMaxConv = -1, annexMinConv = Double.MAX_VALUE;
        for (StaffDistribution d : pattern.values()) {
            double conv = d.getConvertedTotal();
            boolean isVendor = !"制限なし".equals(d.constraintType)
                    && !"故障者制限".equals(d.constraintType)
                    && !d.isBathCleaning && !d.isSuppliesOrder;
            if (isVendor) {
                StaffConstraintInfo info = staffInfo.get(d.staffName);
                if (info != null && info.maxRooms > 0 && conv > info.maxRooms + 1e-9) {
                    warnings.add(String.format("⚠ 業者（%s）の換算 %d が上限 %d を超えています",
                            d.staffName, (int) Math.round(conv), info.maxRooms));
                }
                continue;
            }
            // 通常スタッフ（制限なし・非大浴場・非備品）のみでレベルを測る（故障者は対象外）
            if (!"制限なし".equals(d.constraintType)) continue;
            if (d.isBathCleaning || d.isSuppliesOrder) continue;
            boolean pureMain = d.mainAssignedRooms > 0 && d.annexAssignedRooms == 0;
            boolean pureAnnex = d.annexAssignedRooms > 0 && d.mainAssignedRooms == 0;
            if (pureMain)  { mainMaxConv  = Math.max(mainMaxConv, conv);  mainMinConv  = Math.min(mainMinConv, conv); }
            if (pureAnnex) { annexMaxConv = Math.max(annexMaxConv, conv); annexMinConv = Math.min(annexMinConv, conv); }
        }

        // 1. 本館通常スタッフ内の差
        if (mainMaxConv >= 0 && mainMinConv != Double.MAX_VALUE) {
            int spread = (int) Math.round(mainMaxConv - mainMinConv);
            if (spread >= 2) {
                warnings.add(String.format("⚠ 本館通常清掃スタッフ内に %d 部屋差があります（人数の都合で締められません）", spread));
            }
        }
        // 2. 別館通常スタッフ内の差
        if (annexMaxConv >= 0 && annexMinConv != Double.MAX_VALUE) {
            int spread = (int) Math.round(annexMaxConv - annexMinConv);
            if (spread >= 2) {
                warnings.add(String.format("⚠ 別館通常清掃スタッフ内に %d 部屋差があります", spread));
            }
        }
        // 3. 換算での本館＜別館の逆転
        if (mainMinConv != Double.MAX_VALUE && annexMaxConv >= 0 && annexMaxConv > mainMinConv + 1e-9) {
            warnings.add(String.format("⚠ 換算で本館＜別館の逆転があります（別館最大 %d ＞ 本館最小 %d）",
                    (int) Math.round(annexMaxConv), (int) Math.round(mainMinConv)));
        }
        // 5. 換算の本館−別館差が目標と不一致
        if (mainMaxConv >= 0 && annexMaxConv >= 0) {
            int convDiff = (int) Math.round(mainMaxConv - annexMaxConv);
            if (convDiff != desiredConvDiff) {
                warnings.add(String.format("⚠ 換算の本館−別館差が %d で、目標 %d と一致しません", convDiff, desiredConvDiff));
            }
        }
        return warnings;
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
        applySelectedPattern();
    }

    /**
     * ★★★本館ツイン統合: パターン内の本館ツイン数をシングル等へ合算して0にする。
     * 本館合計（mainAssignedRooms）・換算合計（ツイン1部屋=換算1.0のため）は変化しない。
     * 復帰データや旧データとの互換のための正規化。
     */
    private void normalizeMainTwinIntoSingle(Map<String, StaffDistribution> pattern) {
        if (pattern == null) return;
        for (StaffDistribution d : pattern.values()) {
            if (d.mainTwinAssignedRooms != 0) {
                d.mainSingleAssignedRooms += d.mainTwinAssignedRooms;
                d.mainTwinAssignedRooms = 0;
                d.updateTotal();
            }
        }
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

    /** ★★追加: 「スタッフ選択に戻る」が押されたか */
    public boolean isBackToStaffSelection() {
        return backToStaffSelection;
    }

    /** ★★追加: 「スタッフ選択に戻る」ボタンを表示する（初回フロー用） */
    public void showBackToStaffSelectionButton() {
        if (backToStaffButton != null) {
            backToStaffButton.setVisible(true);
        }
    }

    public Map<String, StaffDistribution> getCurrentDistribution() {
        return (currentPattern != null) ? new HashMap<>(currentPattern) : new HashMap<>();
    }

    /** ★★追加: 階別在庫を受け取る（手動割り当ての検証・選択肢用） */
    public void setManualInventory(Map<Integer, ManualFloorAssignmentDialog.FloorInv> inv) {
        this.manualInventory = (inv != null) ? inv : new HashMap<>();
    }

    /** ★★追加: 手動レイアウトを取得（未使用時は null） */
    public Map<String, ManualFloorAssignmentDialog.StaffManual> getManualLayout() {
        return manualLayout;
    }

    /**
     * ★★追加: 「階別の手動割り当て」ダイアログを実際に開いて確定したかどうか。
     * setInitialManualLayout による初期反映だけでは true にならない。
     */
    public boolean isManualLayoutUsed() {
        return manualLayoutUsed;
    }

    /**
     * ★★追加: 初期の手動レイアウトを設定
     * 「清掃割り当て調整」画面から戻る際に、既存の割り当て状態を
     * 「階別の手動割り当て」ダイアログの初期表示として使用する。
     * 最初のフロー（CP-SAT自動配分）からはこのメソッドを呼ばないため影響なし。
     */
    public void setInitialManualLayout(Map<String, ManualFloorAssignmentDialog.StaffManual> layout) {
        this.manualLayout = layout;
    }

    /**
     * ★★追加: 利用可能なフロア番号を設定（バリデーション用）
     * @param mainFloors 本館のフロア番号セット
     * @param annexFloors 別館のフロア番号セット
     */
    public void setAvailableFloors(Set<Integer> mainFloors, Set<Integer> annexFloors) {
        this.availableMainFloors = mainFloors != null ? new HashSet<>(mainFloors) : new HashSet<>();
        this.availableAnnexFloors = annexFloors != null ? new HashSet<>(annexFloors) : new HashSet<>();
        this.totalAvailableFloors = this.availableMainFloors.size() + this.availableAnnexFloors.size();
        // ★★追加: ヘッダーの売れている階数表示を更新
        if (availableFloorsLabel != null && totalAvailableFloors > 0) {
            availableFloorsLabel.setText(" | 売れている階: " + totalAvailableFloors + "階");
        }
        // ★★追加: フロア情報更新後にUIを再描画（リネン庫スピナー最大値を反映）
        updateDataPanel();
    }
}