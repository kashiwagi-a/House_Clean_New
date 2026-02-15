package org.example;

import com.google.ortools.sat.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * CP-SATソルバーを使用した部屋割り振り最適化
 *
 * ★修正版: 未割当部屋対応
 * - 完全解が見つからない場合も処理を継続
 * - 未割当部屋を返却し、手動割り振りを可能にする
 * - 均等性評価を削除してシンプル化
 *
 * 処理フロー:
 * 0. 大浴清掃スタッフ: フロア単位ラウンドロビン（データ順序維持）
 * 1. ツイン割り振り（複数解を列挙）
 * 2. シングル等割り振り（複数解を列挙）
 * 3. ECO割り振り
 * 4. 未割当部屋を計算して返却
 */
public class RoomAssignmentCPSATOptimizer {
    private static final Logger LOGGER = Logger.getLogger(RoomAssignmentCPSATOptimizer.class.getName());

    // ★追加: 進捗リスナー（GUIへのログ転送用）
    private static volatile java.util.function.Consumer<String> progressListener;
    private static java.util.logging.Handler progressHandler;

    public static void setProgressListener(java.util.function.Consumer<String> listener) {
        // 既存のハンドラを除去
        if (progressHandler != null) {
            LOGGER.removeHandler(progressHandler);
            progressHandler = null;
        }

        progressListener = listener;

        // リスナーが設定された場合、LOGGERにハンドラを追加してGUIに転送
        if (listener != null) {
            progressHandler = new java.util.logging.Handler() {
                @Override
                public void publish(java.util.logging.LogRecord record) {
                    java.util.function.Consumer<String> l = progressListener;
                    if (l != null) {
                        l.accept(record.getMessage());
                    }
                }
                @Override public void flush() {}
                @Override public void close() throws SecurityException {}
            };
            progressHandler.setLevel(java.util.logging.Level.ALL);
            LOGGER.addHandler(progressHandler);
        }
    }

    // 探索する解の最大数
    private static final int MAX_SINGLE_SOLUTIONS = 5;
    private static final int MAX_TOTAL_CANDIDATES = 50;

    // ★追加: 複数解の最大数（ユーザーに表示する解の数）
    public static final int MAX_SOLUTIONS_TO_KEEP = 7;

    // フロアあたりの最大スタッフ数（初期値と上限）
    private static final int INITIAL_MAX_STAFF_PER_FLOOR = 2;
    private static final int MAX_STAFF_PER_FLOOR_LIMIT = 5;

    static {
        com.google.ortools.Loader.loadNativeLibraries();
    }

    // ツイン判定
    private static boolean isMainTwin(String roomType) {
        return "T".equals(roomType) || "TW".equals(roomType) || "ツイン".equals(roomType);
    }

    private static boolean isAnnexTwin(String roomType) {
        return "T".equals(roomType) || "TW".equals(roomType) || "ツイン".equals(roomType);
    }

    // ================================================================
    // 未割当部屋クラス
    // ================================================================

    /**
     * フロアごとの未割当情報
     */
    public static class FloorUnassigned {
        public final int floorNumber;
        public final boolean isMainBuilding;
        public final Map<String, Integer> normalRooms;  // 部屋タイプ → 未割当数
        public final int ecoRooms;

        public FloorUnassigned(int floorNumber, boolean isMainBuilding,
                               Map<String, Integer> normalRooms, int ecoRooms) {
            this.floorNumber = floorNumber;
            this.isMainBuilding = isMainBuilding;
            this.normalRooms = new HashMap<>(normalRooms);
            this.ecoRooms = ecoRooms;
        }

        public int getTotalNormalRooms() {
            return normalRooms.values().stream().mapToInt(Integer::intValue).sum();
        }

        public int getTotalRooms() {
            return getTotalNormalRooms() + ecoRooms;
        }

        @Override
        public String toString() {
            String building = isMainBuilding ? "本館" : "別館";
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : normalRooms.entrySet()) {
                if (entry.getValue() > 0) {
                    parts.add(entry.getKey() + "=" + entry.getValue());
                }
            }
            if (ecoRooms > 0) {
                parts.add("ECO=" + ecoRooms);
            }
            return String.format("%s %dF: %s", building, floorNumber, String.join(", ", parts));
        }
    }

    /**
     * 未割当部屋情報クラス
     */
    public static class UnassignedRooms {
        public final List<FloorUnassigned> mainBuilding = new ArrayList<>();
        public final List<FloorUnassigned> annexBuilding = new ArrayList<>();

        public void addMainFloor(int floorNumber, Map<String, Integer> normalRooms, int ecoRooms) {
            int totalNormal = normalRooms.values().stream().mapToInt(Integer::intValue).sum();
            if (totalNormal > 0 || ecoRooms > 0) {
                mainBuilding.add(new FloorUnassigned(floorNumber, true, normalRooms, ecoRooms));
            }
        }

        public void addAnnexFloor(int floorNumber, Map<String, Integer> normalRooms, int ecoRooms) {
            int totalNormal = normalRooms.values().stream().mapToInt(Integer::intValue).sum();
            if (totalNormal > 0 || ecoRooms > 0) {
                annexBuilding.add(new FloorUnassigned(floorNumber, false, normalRooms, ecoRooms));
            }
        }

        public int getMainNormalUnassigned() {
            return mainBuilding.stream().mapToInt(FloorUnassigned::getTotalNormalRooms).sum();
        }

        public int getMainEcoUnassigned() {
            return mainBuilding.stream().mapToInt(f -> f.ecoRooms).sum();
        }

        public int getAnnexNormalUnassigned() {
            return annexBuilding.stream().mapToInt(FloorUnassigned::getTotalNormalRooms).sum();
        }

        public int getAnnexEcoUnassigned() {
            return annexBuilding.stream().mapToInt(f -> f.ecoRooms).sum();
        }

        public int getTotalNormalUnassigned() {
            return getMainNormalUnassigned() + getAnnexNormalUnassigned();
        }

        public int getTotalEcoUnassigned() {
            return getMainEcoUnassigned() + getAnnexEcoUnassigned();
        }

        public int getTotalUnassigned() {
            return getTotalNormalUnassigned() + getTotalEcoUnassigned();
        }

        public boolean hasUnassigned() {
            return getTotalUnassigned() > 0;
        }

        public void printSummary() {
            LOGGER.info("=== 未割当部屋サマリー ===");

            if (!hasUnassigned()) {
                LOGGER.info("未割当部屋はありません。");
                return;
            }

            LOGGER.info(String.format("【本館】通常清掃: %d室, ECO: %d室",
                    getMainNormalUnassigned(), getMainEcoUnassigned()));
            LOGGER.info(String.format("【別館】通常清掃: %d室, ECO: %d室",
                    getAnnexNormalUnassigned(), getAnnexEcoUnassigned()));
            LOGGER.info(String.format("【合計】%d室", getTotalUnassigned()));

            if (!mainBuilding.isEmpty()) {
                LOGGER.info("--- 本館 詳細 ---");
                for (FloorUnassigned floor : mainBuilding) {
                    LOGGER.info("  " + floor);
                }
            }

            if (!annexBuilding.isEmpty()) {
                LOGGER.info("--- 別館 詳細 ---");
                for (FloorUnassigned floor : annexBuilding) {
                    LOGGER.info("  " + floor);
                }
            }
        }
    }

    /**
     * 最適化結果クラス（未割当情報を含む）
     */
    public static class OptimizationResultWithUnassigned {
        public final List<AdaptiveRoomOptimizer.StaffAssignment> assignments;
        public final UnassignedRooms unassignedRooms;
        public final boolean isComplete;

        public OptimizationResultWithUnassigned(
                List<AdaptiveRoomOptimizer.StaffAssignment> assignments,
                UnassignedRooms unassignedRooms) {
            this.assignments = assignments;
            this.unassignedRooms = unassignedRooms;
            this.isComplete = !unassignedRooms.hasUnassigned();
        }
    }

    /**
     * ★追加: 複数解を保持する結果クラス
     */
    public static class MultiSolutionResult {
        public final List<OptimizationResultWithUnassigned> solutions;
        public final int totalSolutionCount;
        public final int currentIndex;

        public MultiSolutionResult(List<OptimizationResultWithUnassigned> solutions) {
            this.solutions = new ArrayList<>(solutions);
            this.totalSolutionCount = solutions.size();
            this.currentIndex = 0;
        }

        public OptimizationResultWithUnassigned getSolution(int index) {
            if (index < 0 || index >= solutions.size()) {
                return solutions.isEmpty() ? null : solutions.get(0);
            }
            return solutions.get(index);
        }

        public OptimizationResultWithUnassigned getFirstSolution() {
            return solutions.isEmpty() ? null : solutions.get(0);
        }

        public boolean hasMultipleSolutions() {
            return solutions.size() > 1;
        }
    }

    // ================================================================
    // 既存クラス
    // ================================================================

    /**
     * ツイン割り振り結果
     */
    public static class FloorTwinAssignment {
        public final Map<Integer, Integer> mainFloorTwins = new HashMap<>();
        public final Map<Integer, Integer> annexFloorTwins = new HashMap<>();
        public final Set<Integer> usedFloors = new HashSet<>();

        public FloorTwinAssignment() {}

        public FloorTwinAssignment(FloorTwinAssignment other) {
            this.mainFloorTwins.putAll(other.mainFloorTwins);
            this.annexFloorTwins.putAll(other.annexFloorTwins);
            this.usedFloors.addAll(other.usedFloors);
        }
    }

    /**
     * ツイン割り振りパターン
     */
    public static class TwinAssignment {
        public int mainTwinRooms;
        public int annexTwinRooms;

        public TwinAssignment(int main, int annex) {
            this.mainTwinRooms = main;
            this.annexTwinRooms = annex;
        }
    }

    /**
     * 部分解の結果クラス
     */
    public static class PartialSolutionResult {
        public final List<AdaptiveRoomOptimizer.StaffAssignment> assignments;
        public final int totalAssignedRooms;
        public final int totalTargetRooms;
        public final int shortage;
        public final int ecoShortage;
        public final Map<String, Integer> staffShortage;

        public PartialSolutionResult(List<AdaptiveRoomOptimizer.StaffAssignment> assignments,
                                     int totalAssigned, int totalTarget,
                                     Map<String, Integer> staffShortage) {
            this(assignments, totalAssigned, totalTarget, staffShortage, 0);
        }

        public PartialSolutionResult(List<AdaptiveRoomOptimizer.StaffAssignment> assignments,
                                     int totalAssigned, int totalTarget,
                                     Map<String, Integer> staffShortage,
                                     int ecoShortage) {
            this.assignments = assignments;
            this.totalAssignedRooms = totalAssigned;
            this.totalTargetRooms = totalTarget;
            this.shortage = totalTarget - totalAssigned;
            this.staffShortage = staffShortage;
            this.ecoShortage = ecoShortage;
        }
    }

    /**
     * ECOフロア情報（内部クラス）
     */
    private static class EcoFloorInfo {
        final int floorNumber;
        final int ecoCount;
        final boolean isMain;

        EcoFloorInfo(int floor, int eco, boolean main) {
            this.floorNumber = floor;
            this.ecoCount = eco;
            this.isMain = main;
        }
    }

    /**
     * 大浴清掃スタッフの部屋目標（4区分）
     */
    private static class BathStaffRoomTarget {
        int mainSingleTarget;
        int mainTwinTarget;
        int annexSingleTarget;
        int annexTwinTarget;

        int mainSingleAssigned = 0;
        int mainTwinAssigned = 0;
        int annexSingleAssigned = 0;
        int annexTwinAssigned = 0;

        BathStaffRoomTarget(int mainSingle, int mainTwin, int annexSingle, int annexTwin) {
            this.mainSingleTarget = mainSingle;
            this.mainTwinTarget = mainTwin;
            this.annexSingleTarget = annexSingle;
            this.annexTwinTarget = annexTwin;
        }

        int getTotalTarget() {
            return mainSingleTarget + mainTwinTarget + annexSingleTarget + annexTwinTarget;
        }

        int getTotalAssigned() {
            return mainSingleAssigned + mainTwinAssigned + annexSingleAssigned + annexTwinAssigned;
        }

        int getMainSingleRemaining() {
            return Math.max(0, mainSingleTarget - mainSingleAssigned);
        }

        int getMainTwinRemaining() {
            return Math.max(0, mainTwinTarget - mainTwinAssigned);
        }

        int getAnnexSingleRemaining() {
            return Math.max(0, annexSingleTarget - annexSingleAssigned);
        }

        int getAnnexTwinRemaining() {
            return Math.max(0, annexTwinTarget - annexTwinAssigned);
        }

        int getMainRemaining() {
            return getMainSingleRemaining() + getMainTwinRemaining();
        }

        int getAnnexRemaining() {
            return getAnnexSingleRemaining() + getAnnexTwinRemaining();
        }

        boolean needsMainRooms() {
            return getMainRemaining() > 0;
        }

        boolean needsAnnexRooms() {
            return getAnnexRemaining() > 0;
        }
    }

    /**
     * 大浴清掃スタッフ事前割り振り結果
     */
    public static class BathStaffPreAssignmentResult {
        public final Map<String, AdaptiveRoomOptimizer.StaffAssignment> bathStaffAssignments;
        public final AdaptiveRoomOptimizer.BuildingData remainingBuildingData;
        public final Map<String, NormalRoomDistributionDialog.StaffDistribution> remainingRoomDistribution;

        public BathStaffPreAssignmentResult(
                Map<String, AdaptiveRoomOptimizer.StaffAssignment> bathStaffAssignments,
                AdaptiveRoomOptimizer.BuildingData remainingBuildingData,
                Map<String, NormalRoomDistributionDialog.StaffDistribution> remainingRoomDistribution) {
            this.bathStaffAssignments = bathStaffAssignments;
            this.remainingBuildingData = remainingBuildingData;
            this.remainingRoomDistribution = remainingRoomDistribution;
        }
    }

    // ================================================================
    // メイン最適化メソッド
    // ================================================================

    /**
     * メイン最適化メソッド（未割当情報を含む結果を返す）
     */
    public static OptimizationResultWithUnassigned optimizeWithUnassigned(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        LOGGER.info("=== CP-SATソルバーによる最適化を開始 ===");

        // 元の建物データを保持（未割当計算用）
        AdaptiveRoomOptimizer.BuildingData originalBuildingData = buildingData;

        // ステップ0: 大浴清掃スタッフへの事前割り振り
        BathStaffPreAssignmentResult bathPreAssignment = preAssignBathCleaningStaff(buildingData, config);

        List<AdaptiveRoomOptimizer.StaffAssignment> assignments;

        if (bathPreAssignment.bathStaffAssignments.isEmpty()) {
            LOGGER.info("大浴清掃スタッフなし。通常のCPSAT最適化を実行します。");
            assignments = optimizeWithCPSAT(buildingData, config, new HashMap<>(), config);

            if (assignments == null) {
                assignments = new ArrayList<>();
            }

            // ★ECOは統合CP-SATで処理済み（assignEcoWithCPSATは不要）
        } else {
            LOGGER.info("=== 大浴清掃スタッフの事前割り振り完了 ===");
            for (Map.Entry<String, AdaptiveRoomOptimizer.StaffAssignment> entry : bathPreAssignment.bathStaffAssignments.entrySet()) {
                LOGGER.info(String.format("  %s: %d室", entry.getKey(), entry.getValue().getTotalRooms()));
            }

            AdaptiveRoomOptimizer.AdaptiveLoadConfig remainingConfig =
                    createRemainingConfig(config, bathPreAssignment);

            List<AdaptiveRoomOptimizer.StaffAssignment> cpSatAssignments =
                    optimizeWithCPSAT(bathPreAssignment.remainingBuildingData,
                            remainingConfig, bathPreAssignment.bathStaffAssignments, config);

            if (cpSatAssignments == null) {
                cpSatAssignments = new ArrayList<>();
            }

            assignments = mergeAssignments(bathPreAssignment.bathStaffAssignments, cpSatAssignments);
            // ★ECOは統合CP-SATで処理済み（assignEcoWithCPSATは不要）
        }

        // 未割当部屋を計算
        UnassignedRooms unassignedRooms = calculateUnassignedRooms(originalBuildingData, assignments);

        if (unassignedRooms.hasUnassigned()) {
            LOGGER.warning("=== 未割当部屋があります ===");
            unassignedRooms.printSummary();
        }

        return new OptimizationResultWithUnassigned(assignments, unassignedRooms);
    }

    /**
     * 従来互換のoptimizeメソッド（List<StaffAssignment>を返す）
     */
    public static List<AdaptiveRoomOptimizer.StaffAssignment> optimize(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        OptimizationResultWithUnassigned result = optimizeWithUnassigned(buildingData, config);
        return result.assignments;
    }

    /**
     * ★追加: 複数解を返す最適化メソッド
     * 最大MAX_SOLUTIONS_TO_KEEP個の異なる解を生成して返す
     */
    public static MultiSolutionResult optimizeMultipleSolutions(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        LOGGER.info("=== CP-SATソルバーによる複数解最適化を開始（最大" + MAX_SOLUTIONS_TO_KEEP + "解）===");

        // 元の建物データを保持（未割当計算用）
        AdaptiveRoomOptimizer.BuildingData originalBuildingData = buildingData;

        // ステップ0: 大浴清掃スタッフへの事前割り振り
        BathStaffPreAssignmentResult bathPreAssignment = preAssignBathCleaningStaff(buildingData, config);

        List<List<AdaptiveRoomOptimizer.StaffAssignment>> allAssignmentsList = new ArrayList<>();

        if (bathPreAssignment.bathStaffAssignments.isEmpty()) {
            LOGGER.info("大浴清掃スタッフなし。通常のCPSAT複数解最適化を実行します。");
            allAssignmentsList = optimizeWithCPSATMultiple(
                    buildingData, config, new HashMap<>(), config, MAX_SOLUTIONS_TO_KEEP);

            // ECOは統合CP-SATで処理済み（assignEcoWithCPSATは不要）
        } else {
            LOGGER.info("=== 大浴清掃スタッフの事前割り振り完了 ===");
            for (Map.Entry<String, AdaptiveRoomOptimizer.StaffAssignment> entry :
                    bathPreAssignment.bathStaffAssignments.entrySet()) {
                LOGGER.info(String.format("  %s: %d室", entry.getKey(), entry.getValue().getTotalRooms()));
            }

            AdaptiveRoomOptimizer.AdaptiveLoadConfig remainingConfig =
                    createRemainingConfig(config, bathPreAssignment);

            List<List<AdaptiveRoomOptimizer.StaffAssignment>> cpSatAssignmentsList =
                    optimizeWithCPSATMultiple(bathPreAssignment.remainingBuildingData,
                            remainingConfig, bathPreAssignment.bathStaffAssignments, config, MAX_SOLUTIONS_TO_KEEP);

            // 各解に大浴清掃スタッフの割り振りをマージしてECO割り振りを適用
            for (List<AdaptiveRoomOptimizer.StaffAssignment> cpSatAssignments : cpSatAssignmentsList) {
                Map<String, AdaptiveRoomOptimizer.StaffAssignment> bathCopy = new HashMap<>();
                for (Map.Entry<String, AdaptiveRoomOptimizer.StaffAssignment> entry :
                        bathPreAssignment.bathStaffAssignments.entrySet()) {
                    bathCopy.put(entry.getKey(), entry.getValue().deepCopy());
                }

                List<AdaptiveRoomOptimizer.StaffAssignment> merged =
                        mergeAssignments(bathCopy, cpSatAssignments);
                // ★ECOは統合CP-SATで処理済み（assignEcoWithCPSATは不要）
                allAssignmentsList.add(merged);
            }
        }

        // 重複を除去して結果を構築
        List<OptimizationResultWithUnassigned> solutions = new ArrayList<>();
        for (List<AdaptiveRoomOptimizer.StaffAssignment> assignments : allAssignmentsList) {
            String pattern = buildFloorPattern(assignments);
            boolean isDuplicate = false;
            for (OptimizationResultWithUnassigned existing : solutions) {
                if (pattern.equals(buildFloorPattern(existing.assignments))) {
                    isDuplicate = true;
                    break;
                }
            }
            if (!isDuplicate) {
                UnassignedRooms unassignedRooms = calculateUnassignedRooms(originalBuildingData, assignments);
                solutions.add(new OptimizationResultWithUnassigned(assignments, unassignedRooms));
                LOGGER.info(String.format("解 %d を採用", solutions.size()));

                if (solutions.size() >= MAX_SOLUTIONS_TO_KEEP) {
                    break;
                }
            }
        }

        if (solutions.isEmpty()) {
            // 解が1つも見つからなかった場合は空の結果を返す
            LOGGER.warning("解が見つかりませんでした。");
            solutions.add(new OptimizationResultWithUnassigned(
                    new ArrayList<>(), calculateUnassignedRooms(originalBuildingData, new ArrayList<>())));
        }

        if (solutions.size() > 0 && solutions.get(0).unassignedRooms.hasUnassigned()) {
            LOGGER.warning("=== 未割当部屋があります ===");
            solutions.get(0).unassignedRooms.printSummary();
        }

        LOGGER.info(String.format("=== 複数解最適化完了: %d個の解を生成 ===", solutions.size()));

        return new MultiSolutionResult(solutions);
    }

    /**
     * ★追加: 複数解を返すCPSAT最適化
     */
    /**
     * CPSAT複数解最適化（フロアあたりスタッフ数制限を段階的に緩和）
     */
    private static List<List<AdaptiveRoomOptimizer.StaffAssignment>> optimizeWithCPSATMultiple(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config,
            Map<String, AdaptiveRoomOptimizer.StaffAssignment> existingAssignments,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig originalConfig,
            int maxSolutions) {

        if (config.extendedStaffInfo.isEmpty()) {
            LOGGER.info("CPSAT最適化対象のスタッフがいません。");
            return new ArrayList<>();
        }

        if (buildingData.mainRoomCount == 0 && buildingData.annexRoomCount == 0) {
            LOGGER.info("CPSAT最適化対象の部屋がありません。");
            return new ArrayList<>();
        }

        LOGGER.info(String.format("=== フロアあたり最大%d人制限でCPSAT複数解最適化を開始 ===",
                INITIAL_MAX_STAFF_PER_FLOOR));

        // フロアあたりのスタッフ数制限を段階的に緩和
        for (int maxStaffPerFloor = INITIAL_MAX_STAFF_PER_FLOOR;
             maxStaffPerFloor <= MAX_STAFF_PER_FLOOR_LIMIT;
             maxStaffPerFloor++) {

            List<List<AdaptiveRoomOptimizer.StaffAssignment>> results =
                    optimizeWithStaffPerFloorLimitMultiple(
                            buildingData, config, existingAssignments,
                            maxStaffPerFloor, originalConfig, maxSolutions);

            if (results != null && !results.isEmpty()) {
                LOGGER.info(String.format("フロアあたり最大%d人制限で%d個の解が見つかりました。",
                        maxStaffPerFloor, results.size()));
                return results;
            }

            if (maxStaffPerFloor < MAX_STAFF_PER_FLOOR_LIMIT) {
                LOGGER.warning(String.format("=== フロアあたり%d人制限では解が見つかりませんでした。%d人制限で再試行します ===",
                        maxStaffPerFloor, maxStaffPerFloor + 1));
            }
        }

        LOGGER.severe(String.format("フロアあたり%d人制限でも解が見つかりませんでした。", MAX_STAFF_PER_FLOOR_LIMIT));
        return new ArrayList<>();
    }
    // ================================================================
    // 未割当計算メソッド
    // ================================================================

    /**
     * 未割当部屋を計算
     */
    private static UnassignedRooms calculateUnassignedRooms(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            List<AdaptiveRoomOptimizer.StaffAssignment> assignments) {

        UnassignedRooms unassigned = new UnassignedRooms();

        // 割り振り済みの部屋数を集計
        Map<Integer, Map<String, Integer>> assignedMainNormal = new HashMap<>();
        Map<Integer, Integer> assignedMainEco = new HashMap<>();
        Map<Integer, Map<String, Integer>> assignedAnnexNormal = new HashMap<>();
        Map<Integer, Integer> assignedAnnexEco = new HashMap<>();

        for (AdaptiveRoomOptimizer.StaffAssignment sa : assignments) {
            // 本館
            for (Map.Entry<Integer, AdaptiveRoomOptimizer.RoomAllocation> entry
                    : sa.mainBuildingAssignments.entrySet()) {
                int floor = entry.getKey();
                AdaptiveRoomOptimizer.RoomAllocation alloc = entry.getValue();

                assignedMainNormal.computeIfAbsent(floor, k -> new HashMap<>());
                for (Map.Entry<String, Integer> roomEntry : alloc.roomCounts.entrySet()) {
                    assignedMainNormal.get(floor).merge(
                            roomEntry.getKey(), roomEntry.getValue(), Integer::sum);
                }
                assignedMainEco.merge(floor, alloc.ecoRooms, Integer::sum);
            }

            // 別館
            for (Map.Entry<Integer, AdaptiveRoomOptimizer.RoomAllocation> entry
                    : sa.annexBuildingAssignments.entrySet()) {
                int floor = entry.getKey();
                AdaptiveRoomOptimizer.RoomAllocation alloc = entry.getValue();

                assignedAnnexNormal.computeIfAbsent(floor, k -> new HashMap<>());
                for (Map.Entry<String, Integer> roomEntry : alloc.roomCounts.entrySet()) {
                    assignedAnnexNormal.get(floor).merge(
                            roomEntry.getKey(), roomEntry.getValue(), Integer::sum);
                }
                assignedAnnexEco.merge(floor, alloc.ecoRooms, Integer::sum);
            }
        }

        // 本館の未割当を計算
        for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
            Map<String, Integer> unassignedNormal = new HashMap<>();
            Map<String, Integer> assigned = assignedMainNormal.getOrDefault(
                    floor.floorNumber, new HashMap<>());

            for (Map.Entry<String, Integer> entry : floor.roomCounts.entrySet()) {
                String roomType = entry.getKey();
                int total = entry.getValue();
                int used = assigned.getOrDefault(roomType, 0);
                int remaining = total - used;
                if (remaining > 0) {
                    unassignedNormal.put(roomType, remaining);
                }
            }

            int assignedEco = assignedMainEco.getOrDefault(floor.floorNumber, 0);
            int unassignedEco = Math.max(0, floor.ecoRooms - assignedEco);

            unassigned.addMainFloor(floor.floorNumber, unassignedNormal, unassignedEco);
        }

        // 別館の未割当を計算
        for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.annexFloors) {
            Map<String, Integer> unassignedNormal = new HashMap<>();
            Map<String, Integer> assigned = assignedAnnexNormal.getOrDefault(
                    floor.floorNumber, new HashMap<>());

            for (Map.Entry<String, Integer> entry : floor.roomCounts.entrySet()) {
                String roomType = entry.getKey();
                int total = entry.getValue();
                int used = assigned.getOrDefault(roomType, 0);
                int remaining = total - used;
                if (remaining > 0) {
                    unassignedNormal.put(roomType, remaining);
                }
            }

            int assignedEco = assignedAnnexEco.getOrDefault(floor.floorNumber, 0);
            int unassignedEco = Math.max(0, floor.ecoRooms - assignedEco);

            unassigned.addAnnexFloor(floor.floorNumber, unassignedNormal, unassignedEco);
        }

        return unassigned;
    }

    // ================================================================
    // 大浴清掃スタッフ事前割り振り
    // ================================================================

    /**
     * 大浴清掃スタッフへの事前割り振り（フロア単位ラウンドロビン方式）
     */
    // ================================================================
// 大浴清掃スタッフ事前割り振り（修正版）
// ================================================================
//
// 【修正内容】
// - フロアを部屋数の少ない順にソートし、目標を満たせる最小フロアを選択（貪欲法）
// - 大浴清掃スタッフは1フロアのみ使用する制限を厳守
//
// 【この部分だけを差し替えてください】
// RoomAssignmentCPSATOptimizer.java の preAssignBathCleaningStaff メソッド全体を
// 以下のコードで置き換えてください。
// ================================================================

    /**
     * 大浴清掃スタッフへの事前割り振り（貪欲法：目標を満たせる最小フロアを選択）
     *
     * 大浴清掃スタッフは1フロアしか使えないため、
     * 目標部屋数を満たせるフロアの中で最も部屋数が少ないフロアを選択する。
     */
    private static BathStaffPreAssignmentResult preAssignBathCleaningStaff(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        LOGGER.info("=== ステップ0: 大浴清掃スタッフへの貪欲法割り振り ===");

        // 大浴清掃スタッフを抽出
        List<AdaptiveRoomOptimizer.ExtendedStaffInfo> bathStaffList = new ArrayList<>();
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : config.extendedStaffInfo) {
            if (staffInfo.bathCleaningType != AdaptiveRoomOptimizer.BathCleaningType.NONE) {
                bathStaffList.add(staffInfo);
                LOGGER.info("大浴清掃スタッフ: " + staffInfo.staff.name);
            }
        }

        // 大浴清掃スタッフがいない場合
        if (bathStaffList.isEmpty()) {
            LOGGER.info("大浴清掃スタッフはいません。");
            return new BathStaffPreAssignmentResult(
                    new HashMap<>(),
                    buildingData,
                    config.roomDistribution != null ? new HashMap<>(config.roomDistribution) : new HashMap<>()
            );
        }

        // 各スタッフの目標部屋数を4区分で取得
        Map<String, BathStaffRoomTarget> staffTargets = new HashMap<>();
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : bathStaffList) {
            String staffName = staffInfo.staff.name;
            if (config.roomDistribution != null && config.roomDistribution.containsKey(staffName)) {
                NormalRoomDistributionDialog.StaffDistribution dist = config.roomDistribution.get(staffName);
                BathStaffRoomTarget target = new BathStaffRoomTarget(
                        dist.mainSingleAssignedRooms,
                        dist.mainTwinAssignedRooms,
                        dist.annexSingleAssignedRooms,
                        dist.annexTwinAssignedRooms
                );
                staffTargets.put(staffName, target);
                LOGGER.info(String.format("  %s: 本館S=%d, 本館T=%d, 別館S=%d, 別館T=%d (合計%d室)",
                        staffName,
                        dist.mainSingleAssignedRooms, dist.mainTwinAssignedRooms,
                        dist.annexSingleAssignedRooms, dist.annexTwinAssignedRooms,
                        target.getTotalTarget()));
            } else {
                staffTargets.put(staffName, new BathStaffRoomTarget(0, 0, 0, 0));
            }
        }

        // スタッフ別の割り振り結果を保持
        Map<String, Map<Integer, AdaptiveRoomOptimizer.RoomAllocation>> staffMainAssignments = new HashMap<>();
        Map<String, Map<Integer, AdaptiveRoomOptimizer.RoomAllocation>> staffAnnexAssignments = new HashMap<>();

        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : bathStaffList) {
            staffMainAssignments.put(staffInfo.staff.name, new TreeMap<>());
            staffAnnexAssignments.put(staffInfo.staff.name, new TreeMap<>());
        }

        // 使用済みフロアを追跡
        Set<Integer> usedMainFloors = new HashSet<>();
        Set<Integer> usedAnnexFloors = new HashSet<>();

        // ================================================================
        // 本館の割り振り（貪欲法）
        // ================================================================
        LOGGER.info("--- 本館フロアの割り振り（貪欲法） ---");

        // 本館で部屋が必要なスタッフを抽出
        List<AdaptiveRoomOptimizer.ExtendedStaffInfo> mainNeedingStaff = new ArrayList<>();
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : bathStaffList) {
            BathStaffRoomTarget target = staffTargets.get(staffInfo.staff.name);
            if (target != null && target.needsMainRooms()) {
                mainNeedingStaff.add(staffInfo);
            }
        }

        // フロアごとの部屋数を計算
        Map<Integer, Integer> mainFloorRoomCounts = new LinkedHashMap<>();
        for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
            int totalRooms = 0;
            for (Map.Entry<String, Integer> entry : floor.roomCounts.entrySet()) {
                totalRooms += entry.getValue();
            }
            if (totalRooms > 0) {
                mainFloorRoomCounts.put(floor.floorNumber, totalRooms);
            }
        }

        // フロアを部屋数の少ない順にソート
        List<Integer> sortedMainFloorNumbers = new ArrayList<>(mainFloorRoomCounts.keySet());
        sortedMainFloorNumbers.sort((a, b) -> mainFloorRoomCounts.get(a) - mainFloorRoomCounts.get(b));

        LOGGER.info("本館フロア（部屋数昇順）: " + sortedMainFloorNumbers.stream()
                .map(f -> f + "階(" + mainFloorRoomCounts.get(f) + "室)")
                .collect(java.util.stream.Collectors.joining(", ")));

        // 各スタッフに対して、目標を満たせる最小フロアを割り当て
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : mainNeedingStaff) {
            String staffName = staffInfo.staff.name;
            BathStaffRoomTarget target = staffTargets.get(staffName);
            int requiredRooms = target.getMainSingleRemaining() + target.getMainTwinRemaining();

            LOGGER.info(String.format("  %s: %d室必要", staffName, requiredRooms));

            // 目標を満たせる最小フロアを探す
            Integer selectedFloor = null;
            for (Integer floorNumber : sortedMainFloorNumbers) {
                if (usedMainFloors.contains(floorNumber)) {
                    continue; // 既に使用済み
                }
                int availableRooms = mainFloorRoomCounts.get(floorNumber);
                if (availableRooms >= requiredRooms) {
                    selectedFloor = floorNumber;
                    break;
                }
            }

            if (selectedFloor == null) {
                LOGGER.warning(String.format("    → %s: 目標(%d室)を満たせるフロアがありません",
                        staffName, requiredRooms));
                continue;
            }

            // 選択したフロアから部屋を割り当て
            AdaptiveRoomOptimizer.FloorInfo floor = null;
            for (AdaptiveRoomOptimizer.FloorInfo f : buildingData.mainFloors) {
                if (f.floorNumber == selectedFloor) {
                    floor = f;
                    break;
                }
            }

            if (floor == null) continue;

            Map<String, Integer> assignedRoomCounts = new HashMap<>();
            int singleAssigned = 0;
            int twinAssigned = 0;
            int singleToAssign = target.getMainSingleRemaining();
            int twinToAssign = target.getMainTwinRemaining();

            for (Map.Entry<String, Integer> roomEntry : floor.roomCounts.entrySet()) {
                String roomType = roomEntry.getKey();
                int roomCount = roomEntry.getValue();

                if (isMainTwin(roomType)) {
                    int toAssign = Math.min(roomCount, twinToAssign - twinAssigned);
                    if (toAssign > 0) {
                        assignedRoomCounts.put(roomType, toAssign);
                        twinAssigned += toAssign;
                    }
                } else {
                    int toAssign = Math.min(roomCount, singleToAssign - singleAssigned);
                    if (toAssign > 0) {
                        assignedRoomCounts.put(roomType, toAssign);
                        singleAssigned += toAssign;
                    }
                }
            }

            if (!assignedRoomCounts.isEmpty()) {
                staffMainAssignments.get(staffName).put(selectedFloor,
                        new AdaptiveRoomOptimizer.RoomAllocation(assignedRoomCounts, 0));
                target.mainSingleAssigned += singleAssigned;
                target.mainTwinAssigned += twinAssigned;
                usedMainFloors.add(selectedFloor);

                LOGGER.info(String.format("    → %d階を選択（%d室中%d室使用）: S=%d, T=%d",
                        selectedFloor, mainFloorRoomCounts.get(selectedFloor),
                        singleAssigned + twinAssigned, singleAssigned, twinAssigned));
            }
        }

        // ================================================================
        // 別館の割り振り（貪欲法）
        // ================================================================
        LOGGER.info("--- 別館フロアの割り振り（貪欲法） ---");

        // 別館で部屋が必要なスタッフを抽出
        List<AdaptiveRoomOptimizer.ExtendedStaffInfo> annexNeedingStaff = new ArrayList<>();
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : bathStaffList) {
            BathStaffRoomTarget target = staffTargets.get(staffInfo.staff.name);
            if (target != null && target.needsAnnexRooms()) {
                annexNeedingStaff.add(staffInfo);
            }
        }

        if (!annexNeedingStaff.isEmpty()) {
            // フロアごとの部屋数を計算
            Map<Integer, Integer> annexFloorRoomCounts = new LinkedHashMap<>();
            for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.annexFloors) {
                int totalRooms = 0;
                for (Map.Entry<String, Integer> entry : floor.roomCounts.entrySet()) {
                    totalRooms += entry.getValue();
                }
                if (totalRooms > 0) {
                    annexFloorRoomCounts.put(floor.floorNumber, totalRooms);
                }
            }

            // フロアを部屋数の少ない順にソート
            List<Integer> sortedAnnexFloorNumbers = new ArrayList<>(annexFloorRoomCounts.keySet());
            sortedAnnexFloorNumbers.sort((a, b) -> annexFloorRoomCounts.get(a) - annexFloorRoomCounts.get(b));

            LOGGER.info("別館フロア（部屋数昇順）: " + sortedAnnexFloorNumbers.stream()
                    .map(f -> f + "階(" + annexFloorRoomCounts.get(f) + "室)")
                    .collect(java.util.stream.Collectors.joining(", ")));

            // 各スタッフに対して、目標を満たせる最小フロアを割り当て
            for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : annexNeedingStaff) {
                String staffName = staffInfo.staff.name;
                BathStaffRoomTarget target = staffTargets.get(staffName);
                int requiredRooms = target.getAnnexSingleRemaining() + target.getAnnexTwinRemaining();

                LOGGER.info(String.format("  %s: %d室必要", staffName, requiredRooms));

                // 目標を満たせる最小フロアを探す
                Integer selectedFloor = null;
                for (Integer floorNumber : sortedAnnexFloorNumbers) {
                    if (usedAnnexFloors.contains(floorNumber)) {
                        continue; // 既に使用済み
                    }
                    int availableRooms = annexFloorRoomCounts.get(floorNumber);
                    if (availableRooms >= requiredRooms) {
                        selectedFloor = floorNumber;
                        break;
                    }
                }

                if (selectedFloor == null) {
                    LOGGER.warning(String.format("    → %s: 目標(%d室)を満たせるフロアがありません",
                            staffName, requiredRooms));
                    continue;
                }

                // 選択したフロアから部屋を割り当て
                AdaptiveRoomOptimizer.FloorInfo floor = null;
                for (AdaptiveRoomOptimizer.FloorInfo f : buildingData.annexFloors) {
                    if (f.floorNumber == selectedFloor) {
                        floor = f;
                        break;
                    }
                }

                if (floor == null) continue;

                Map<String, Integer> assignedRoomCounts = new HashMap<>();
                int singleAssigned = 0;
                int twinAssigned = 0;
                int singleToAssign = target.getAnnexSingleRemaining();
                int twinToAssign = target.getAnnexTwinRemaining();

                for (Map.Entry<String, Integer> roomEntry : floor.roomCounts.entrySet()) {
                    String roomType = roomEntry.getKey();
                    int roomCount = roomEntry.getValue();

                    if (isAnnexTwin(roomType)) {
                        int toAssign = Math.min(roomCount, twinToAssign - twinAssigned);
                        if (toAssign > 0) {
                            assignedRoomCounts.put(roomType, toAssign);
                            twinAssigned += toAssign;
                        }
                    } else {
                        int toAssign = Math.min(roomCount, singleToAssign - singleAssigned);
                        if (toAssign > 0) {
                            assignedRoomCounts.put(roomType, toAssign);
                            singleAssigned += toAssign;
                        }
                    }
                }

                if (!assignedRoomCounts.isEmpty()) {
                    staffAnnexAssignments.get(staffName).put(selectedFloor,
                            new AdaptiveRoomOptimizer.RoomAllocation(assignedRoomCounts, 0));
                    target.annexSingleAssigned += singleAssigned;
                    target.annexTwinAssigned += twinAssigned;
                    usedAnnexFloors.add(selectedFloor);

                    LOGGER.info(String.format("    → %d階を選択（%d室中%d室使用）: S=%d, T=%d",
                            selectedFloor, annexFloorRoomCounts.get(selectedFloor),
                            singleAssigned + twinAssigned, singleAssigned, twinAssigned));
                }
            }
        }

        // ================================================================
        // 結果のStaffAssignmentを作成
        // ================================================================
        LOGGER.info("=== 大浴清掃スタッフの事前割り振り完了 ===");

        Map<String, AdaptiveRoomOptimizer.StaffAssignment> bathStaffAssignments = new HashMap<>();
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : bathStaffList) {
            String staffName = staffInfo.staff.name;
            Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> mainAssignments = staffMainAssignments.get(staffName);
            Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> annexAssignments = staffAnnexAssignments.get(staffName);

            AdaptiveRoomOptimizer.StaffAssignment assignment = new AdaptiveRoomOptimizer.StaffAssignment(
                    staffInfo.staff,
                    mainAssignments,
                    annexAssignments,
                    staffInfo.bathCleaningType
            );
            bathStaffAssignments.put(staffName, assignment);

            BathStaffRoomTarget target = staffTargets.get(staffName);
            LOGGER.info(String.format("  %s: 本館S=%d/%d, 本館T=%d/%d, 別館S=%d/%d, 別館T=%d/%d, 合計=%d室, フロア数=%d",
                    staffName,
                    target.mainSingleAssigned, target.mainSingleTarget,
                    target.mainTwinAssigned, target.mainTwinTarget,
                    target.annexSingleAssigned, target.annexSingleTarget,
                    target.annexTwinAssigned, target.annexTwinTarget,
                    assignment.getTotalRooms(),
                    mainAssignments.size() + annexAssignments.size()));
        }

        // 使用済み部屋を計算
        Map<Integer, Map<String, Integer>> usedMainRooms = new HashMap<>();
        Map<Integer, Map<String, Integer>> usedAnnexRooms = new HashMap<>();

        for (AdaptiveRoomOptimizer.StaffAssignment assignment : bathStaffAssignments.values()) {
            for (Map.Entry<Integer, AdaptiveRoomOptimizer.RoomAllocation> entry : assignment.mainBuildingAssignments.entrySet()) {
                int floorNumber = entry.getKey();
                AdaptiveRoomOptimizer.RoomAllocation allocation = entry.getValue();
                usedMainRooms.computeIfAbsent(floorNumber, k -> new HashMap<>());
                for (Map.Entry<String, Integer> roomEntry : allocation.roomCounts.entrySet()) {
                    usedMainRooms.get(floorNumber).merge(roomEntry.getKey(), roomEntry.getValue(), Integer::sum);
                }
            }
            for (Map.Entry<Integer, AdaptiveRoomOptimizer.RoomAllocation> entry : assignment.annexBuildingAssignments.entrySet()) {
                int floorNumber = entry.getKey();
                AdaptiveRoomOptimizer.RoomAllocation allocation = entry.getValue();
                usedAnnexRooms.computeIfAbsent(floorNumber, k -> new HashMap<>());
                for (Map.Entry<String, Integer> roomEntry : allocation.roomCounts.entrySet()) {
                    usedAnnexRooms.get(floorNumber).merge(roomEntry.getKey(), roomEntry.getValue(), Integer::sum);
                }
            }
        }

        AdaptiveRoomOptimizer.BuildingData remainingBuildingData = createRemainingBuildingData(
                buildingData, usedMainRooms, usedAnnexRooms);

        Map<String, NormalRoomDistributionDialog.StaffDistribution> remainingRoomDistribution = new HashMap<>();
        if (config.roomDistribution != null) {
            for (Map.Entry<String, NormalRoomDistributionDialog.StaffDistribution> entry : config.roomDistribution.entrySet()) {
                String staffName = entry.getKey();
                boolean isBathStaff = false;
                for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : bathStaffList) {
                    if (staffInfo.staff.name.equals(staffName)) {
                        isBathStaff = true;
                        break;
                    }
                }
                if (!isBathStaff) {
                    remainingRoomDistribution.put(staffName, entry.getValue());
                }
            }
        }

        return new BathStaffPreAssignmentResult(bathStaffAssignments, remainingBuildingData, remainingRoomDistribution);
    }
    // ================================================================
    // 残りの建物データ作成
    // ================================================================

    /**
     * 残りの建物データを作成（使用済み部屋を除外）
     */
    private static AdaptiveRoomOptimizer.BuildingData createRemainingBuildingData(
            AdaptiveRoomOptimizer.BuildingData original,
            Map<Integer, Map<String, Integer>> usedMainRooms,
            Map<Integer, Map<String, Integer>> usedAnnexRooms) {

        List<AdaptiveRoomOptimizer.FloorInfo> remainingMainFloors = new ArrayList<>();
        List<AdaptiveRoomOptimizer.FloorInfo> remainingAnnexFloors = new ArrayList<>();

        for (AdaptiveRoomOptimizer.FloorInfo floor : original.mainFloors) {
            Map<String, Integer> remainingRooms = new HashMap<>();
            Map<String, Integer> usedRooms = usedMainRooms.getOrDefault(floor.floorNumber, new HashMap<>());

            for (Map.Entry<String, Integer> entry : floor.roomCounts.entrySet()) {
                String roomType = entry.getKey();
                int total = entry.getValue();
                int used = usedRooms.getOrDefault(roomType, 0);
                int remaining = total - used;
                if (remaining > 0) {
                    remainingRooms.put(roomType, remaining);
                }
            }

            if (!remainingRooms.isEmpty() || floor.ecoRooms > 0) {
                remainingMainFloors.add(new AdaptiveRoomOptimizer.FloorInfo(
                        floor.floorNumber, remainingRooms, floor.ecoRooms, true));
            }
        }

        for (AdaptiveRoomOptimizer.FloorInfo floor : original.annexFloors) {
            Map<String, Integer> remainingRooms = new HashMap<>();
            Map<String, Integer> usedRooms = usedAnnexRooms.getOrDefault(floor.floorNumber, new HashMap<>());

            for (Map.Entry<String, Integer> entry : floor.roomCounts.entrySet()) {
                String roomType = entry.getKey();
                int total = entry.getValue();
                int used = usedRooms.getOrDefault(roomType, 0);
                int remaining = total - used;
                if (remaining > 0) {
                    remainingRooms.put(roomType, remaining);
                }
            }

            if (!remainingRooms.isEmpty() || floor.ecoRooms > 0) {
                remainingAnnexFloors.add(new AdaptiveRoomOptimizer.FloorInfo(
                        floor.floorNumber, remainingRooms, floor.ecoRooms, false));
            }
        }

        int mainRoomCount = remainingMainFloors.stream()
                .mapToInt(f -> f.getTotalNormalRooms())
                .sum();
        int annexRoomCount = remainingAnnexFloors.stream()
                .mapToInt(f -> f.getTotalNormalRooms())
                .sum();

        LOGGER.info(String.format("残り部屋数: 本館=%d室, 別館=%d室", mainRoomCount, annexRoomCount));

        return new AdaptiveRoomOptimizer.BuildingData(
                remainingMainFloors, remainingAnnexFloors, mainRoomCount, annexRoomCount);
    }

    /**
     * 残りスタッフ用の設定を作成（大浴清掃スタッフを除外）
     */
    private static AdaptiveRoomOptimizer.AdaptiveLoadConfig createRemainingConfig(
            AdaptiveRoomOptimizer.AdaptiveLoadConfig original,
            BathStaffPreAssignmentResult bathPreAssignment) {

        List<FileProcessor.Staff> remainingStaff = new ArrayList<>();
        List<AdaptiveRoomOptimizer.ExtendedStaffInfo> remainingExtendedInfo = new ArrayList<>();
        Map<String, AdaptiveRoomOptimizer.BathCleaningType> remainingBathAssignments = new HashMap<>();

        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : original.extendedStaffInfo) {
            if (staffInfo.bathCleaningType == AdaptiveRoomOptimizer.BathCleaningType.NONE) {
                remainingStaff.add(staffInfo.staff);
                remainingExtendedInfo.add(staffInfo);
                remainingBathAssignments.put(staffInfo.staff.name, staffInfo.bathCleaningType);
            }
        }

        return new AdaptiveRoomOptimizer.AdaptiveLoadConfig(
                remainingStaff,
                remainingExtendedInfo,
                remainingBathAssignments,
                original.pointConstraints,
                bathPreAssignment.remainingRoomDistribution
        );
    }

    // ================================================================
    // CPSAT最適化
    // ================================================================

    /**
     * CPSAT最適化（フロアあたりスタッフ数制限を段階的に緩和）
     */
    private static List<AdaptiveRoomOptimizer.StaffAssignment> optimizeWithCPSAT(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config,
            Map<String, AdaptiveRoomOptimizer.StaffAssignment> existingAssignments,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig originalConfig) {

        if (config.extendedStaffInfo.isEmpty()) {
            LOGGER.info("CPSAT最適化対象のスタッフがいません。");
            return new ArrayList<>();
        }

        if (buildingData.mainRoomCount == 0 && buildingData.annexRoomCount == 0) {
            LOGGER.info("CPSAT最適化対象の部屋がありません。");
            return new ArrayList<>();
        }

        LOGGER.info(String.format("=== フロアあたり最大%d人制限でCPSAT最適化を開始 ===",
                INITIAL_MAX_STAFF_PER_FLOOR));

        // フロアあたりのスタッフ数制限を段階的に緩和
        for (int maxStaffPerFloor = INITIAL_MAX_STAFF_PER_FLOOR;
             maxStaffPerFloor <= MAX_STAFF_PER_FLOOR_LIMIT;
             maxStaffPerFloor++) {

            List<AdaptiveRoomOptimizer.StaffAssignment> result =
                    optimizeWithStaffPerFloorLimit(
                            buildingData, config, existingAssignments,
                            maxStaffPerFloor, originalConfig);

            if (result != null && !result.isEmpty()) {
                LOGGER.info(String.format("フロアあたり最大%d人制限で解が見つかりました。", maxStaffPerFloor));
                return result;
            }

            if (maxStaffPerFloor < MAX_STAFF_PER_FLOOR_LIMIT) {
                LOGGER.warning(String.format("=== フロアあたり%d人制限では解が見つかりませんでした。%d人制限で再試行します ===",
                        maxStaffPerFloor, maxStaffPerFloor + 1));
            }
        }

        LOGGER.severe(String.format("フロアあたり%d人制限でも解が見つかりませんでした。", MAX_STAFF_PER_FLOOR_LIMIT));
        LOGGER.warning("通常清掃部屋割り振り設定を見直してください。未割当部屋が発生します。");
        return null;
    }

    /**
     * フロアあたりのスタッフ数制限付きCPSAT最適化
     * ★修正: ツイン＋シングル統合CP-SAT（ラウンドロビン廃止）
     */
    private static List<AdaptiveRoomOptimizer.StaffAssignment> optimizeWithStaffPerFloorLimit(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config,
            Map<String, AdaptiveRoomOptimizer.StaffAssignment> existingAssignments,
            int maxStaffPerFloor,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig originalConfig) {

        LOGGER.info(String.format("=== 残りスタッフのCPSAT最適化（フロアあたり最大%d人）===", maxStaffPerFloor));

        List<Map<String, TwinAssignment>> twinPatterns = generateTwinPatterns(buildingData, config);

        if (twinPatterns.isEmpty()) {
            LOGGER.warning("ツインパターンが生成されませんでした。空の結果を返します。");
            return new ArrayList<>();
        }

        Map<String, TwinAssignment> twinPattern = twinPatterns.get(0);

        // ★統合CP-SAT: ツインのフロア配置もソルバーが決定
        List<PartialSolutionResult> results =
                assignRoomsMultiple(buildingData, config, twinPattern, maxStaffPerFloor, 1);

        for (PartialSolutionResult result : results) {
            if (result.shortage == 0) {
                LOGGER.info("完全解が見つかりました。");
                return result.assignments;
            }
        }

        // 完全解がない場合：最大制限に達していなければnullを返して次の制限で再試行
        if (maxStaffPerFloor < MAX_STAFF_PER_FLOOR_LIMIT) {
            return null;
        }

        // 最大制限でも完全解がない場合は部分解を使用
        if (!results.isEmpty()) {
            results.sort((a, b) -> Integer.compare(a.shortage, b.shortage));
            PartialSolutionResult best = results.get(0);
            LOGGER.warning("=== 完全な解が見つかりませんでした ===");
            LOGGER.warning(String.format("最良の部分解を使用: 割り振り済み=%d室 / 目標=%d室（未割当=%d室）",
                    best.totalAssignedRooms, best.totalTargetRooms, best.shortage));
            return best.assignments;
        }

        return null;
    }

    /**
     * ★修正: 複数解を返すフロアあたりのスタッフ数制限付きCPSAT最適化
     * ツイン＋シングル統合CP-SAT（ラウンドロビン廃止）
     */
    private static List<List<AdaptiveRoomOptimizer.StaffAssignment>> optimizeWithStaffPerFloorLimitMultiple(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config,
            Map<String, AdaptiveRoomOptimizer.StaffAssignment> existingAssignments,
            int maxStaffPerFloor,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig originalConfig,
            int maxSolutions) {

        LOGGER.info(String.format("=== 残りスタッフのCPSAT複数解最適化（フロアあたり最大%d人、最大%d解）===",
                maxStaffPerFloor, maxSolutions));

        // === 各建物・各階の部屋数をログ出力 ===
        LOGGER.info("=== 建物フロア別 部屋数一覧 ===");
        for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("  本館 %dF: ", floor.floorNumber));
            for (Map.Entry<String, Integer> e : floor.roomCounts.entrySet()) {
                if (e.getValue() > 0) {
                    sb.append(String.format("%s=%d ", e.getKey(), e.getValue()));
                }
            }
            sb.append(String.format("(ECO=%d, 合計=%d)", floor.ecoRooms, floor.getTotalNormalRooms()));
            LOGGER.info(sb.toString());
        }
        for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.annexFloors) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("  別館 %dF: ", floor.floorNumber));
            for (Map.Entry<String, Integer> e : floor.roomCounts.entrySet()) {
                if (e.getValue() > 0) {
                    sb.append(String.format("%s=%d ", e.getKey(), e.getValue()));
                }
            }
            sb.append(String.format("(ECO=%d, 合計=%d)", floor.ecoRooms, floor.getTotalNormalRooms()));
            LOGGER.info(sb.toString());
        }

        List<Map<String, TwinAssignment>> twinPatterns = generateTwinPatterns(buildingData, config);

        if (twinPatterns.isEmpty()) {
            LOGGER.warning("ツインパターンが生成されませんでした。空の結果を返します。");
            return new ArrayList<>();
        }

        Map<String, TwinAssignment> twinPattern = twinPatterns.get(0);

        // ★統合CP-SAT: ツインのフロア配置もソルバーが決定（nogoodループで複数解探索）
        List<PartialSolutionResult> allResults =
                assignRoomsMultiple(buildingData, config, twinPattern, maxStaffPerFloor, maxSolutions);

        // 完全解と部分解を分離
        List<List<AdaptiveRoomOptimizer.StaffAssignment>> completeSolutions = new ArrayList<>();
        List<PartialSolutionResult> partialResults = new ArrayList<>();

        for (PartialSolutionResult result : allResults) {
            if (result.shortage == 0) {
                if (completeSolutions.size() < maxSolutions) {
                    if (!isDuplicateSolution(completeSolutions, result.assignments)) {
                        completeSolutions.add(result.assignments);
                        LOGGER.info(String.format("完全解 %d を発見（ECO shortage=%d）",
                                completeSolutions.size(), result.ecoShortage));
                    }
                }
            } else {
                partialResults.add(result);
            }
        }

        // 完全解が見つかった場合はそれを返す
        if (!completeSolutions.isEmpty()) {
            LOGGER.info(String.format("合計 %d 個の完全解を発見", completeSolutions.size()));
            return completeSolutions;
        }

        // 完全解がない場合：最大制限に達していなければnullを返して次の制限で再試行
        if (maxStaffPerFloor < MAX_STAFF_PER_FLOOR_LIMIT) {
            return null;
        }

        // 最大制限でも完全解がない場合は部分解から最良のものを返す
        if (!partialResults.isEmpty()) {
            LOGGER.warning("=== 完全な解が見つかりませんでした。部分解を使用します ===");

            partialResults.sort((a, b) -> Integer.compare(a.shortage, b.shortage));

            List<List<AdaptiveRoomOptimizer.StaffAssignment>> results = new ArrayList<>();
            for (int i = 0; i < Math.min(maxSolutions, partialResults.size()); i++) {
                results.add(partialResults.get(i).assignments);
            }
            return results;
        }

        return null;
    }

    /**
     * ★追加: 解の重複チェック（簡易版：スタッフごとの担当フロアの組み合わせで判定）
     */
    private static boolean isDuplicateSolution(
            List<List<AdaptiveRoomOptimizer.StaffAssignment>> existingSolutions,
            List<AdaptiveRoomOptimizer.StaffAssignment> newSolution) {

        if (existingSolutions.isEmpty()) {
            return false;
        }

        // 新しい解のフロア割り当てパターンを文字列化
        String newPattern = buildFloorPattern(newSolution);

        for (List<AdaptiveRoomOptimizer.StaffAssignment> existing : existingSolutions) {
            String existingPattern = buildFloorPattern(existing);
            if (newPattern.equals(existingPattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * ★追加: 解のフロア割り当てパターンを文字列化
     */
    private static String buildFloorPattern(List<AdaptiveRoomOptimizer.StaffAssignment> assignments) {
        StringBuilder sb = new StringBuilder();

        // スタッフ名でソートして一貫性を保つ
        List<AdaptiveRoomOptimizer.StaffAssignment> sorted = new ArrayList<>(assignments);
        sorted.sort((a, b) -> a.staff.name.compareTo(b.staff.name));

        for (AdaptiveRoomOptimizer.StaffAssignment sa : sorted) {
            sb.append(sa.staff.name).append(":");
            List<Integer> floors = new ArrayList<>(sa.floors);
            Collections.sort(floors);
            for (Integer f : floors) {
                sb.append(f).append(",");
            }
            sb.append(";");
        }
        return sb.toString();
    }

    /**
     * 大浴清掃スタッフの割り振りとCPSAT結果をマージ
     * ★修正: ディープコピーを使用して各解が独立したオブジェクトを持つようにする
     */
    private static List<AdaptiveRoomOptimizer.StaffAssignment> mergeAssignments(
            Map<String, AdaptiveRoomOptimizer.StaffAssignment> bathStaffAssignments,
            List<AdaptiveRoomOptimizer.StaffAssignment> cpSatAssignments) {

        List<AdaptiveRoomOptimizer.StaffAssignment> merged = new ArrayList<>();

        // ★修正: ディープコピーを使用
        for (AdaptiveRoomOptimizer.StaffAssignment sa : bathStaffAssignments.values()) {
            merged.add(sa.deepCopy());
        }
        for (AdaptiveRoomOptimizer.StaffAssignment sa : cpSatAssignments) {
            merged.add(sa.deepCopy());
        }

        LOGGER.info("=== 最終割り振り結果 ===");
        for (AdaptiveRoomOptimizer.StaffAssignment assignment : merged) {
            LOGGER.info(String.format("  %s: %d室 (%s)",
                    assignment.staff.name,
                    assignment.getTotalRooms(),
                    assignment.bathCleaningType.displayName));
        }

        return merged;
    }

    // ================================================================
    // ツインパターン生成
    // ================================================================

    /**
     * ツインパターン生成
     */
    private static List<Map<String, TwinAssignment>> generateTwinPatterns(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        List<Map<String, TwinAssignment>> patterns = new ArrayList<>();
        Map<String, TwinAssignment> basePattern = new HashMap<>();

        LOGGER.info("=== config.roomDistribution の内容 ===");
        for (Map.Entry<String, NormalRoomDistributionDialog.StaffDistribution> entry : config.roomDistribution.entrySet()) {
            NormalRoomDistributionDialog.StaffDistribution dist = entry.getValue();
            // ★追加: シングルも出力
            LOGGER.info(String.format("  %s: 本館S=%d, 本館T=%d, 別館S=%d, 別館T=%d",
                    entry.getKey(),
                    dist.mainSingleAssignedRooms, dist.mainTwinAssignedRooms,
                    dist.annexSingleAssignedRooms, dist.annexTwinAssignedRooms));
        }

        LOGGER.info("=== config.roomDistribution の内容 ===");

        int totalMainTwin = 0, totalAnnexTwin = 0;

        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : config.extendedStaffInfo) {
            String staffName = staffInfo.staff.name;
            NormalRoomDistributionDialog.StaffDistribution dist =
                    config.roomDistribution != null ?
                            config.roomDistribution.get(staffName) : null;

            if (dist != null && (dist.mainTwinAssignedRooms > 0 || dist.annexTwinAssignedRooms > 0)) {
                basePattern.put(staffName, new TwinAssignment(
                        dist.mainTwinAssignedRooms, dist.annexTwinAssignedRooms));
                LOGGER.info("  " + staffName + ": 本館ツイン=" + dist.mainTwinAssignedRooms +
                        ", 別館ツイン=" + dist.annexTwinAssignedRooms);
                totalMainTwin += dist.mainTwinAssignedRooms;
                totalAnnexTwin += dist.annexTwinAssignedRooms;
            } else {
                basePattern.put(staffName, new TwinAssignment(0, 0));
            }
        }

        LOGGER.info("合計: 本館ツイン=" + totalMainTwin + ", 別館ツイン=" + totalAnnexTwin);
        patterns.add(basePattern);
        // ★修正: ユーザー設定のツイン数配分をそのまま使用（スタッフ間の移動パターンは廃止）
        // ツインのフロア配置はCP-SATソルバーが最適化する

        return patterns;
    }

    // ================================================================
    // ツイン＋シングル統合割り振り（CP-SAT）
    // ================================================================

    /**
     * ツインとシングルを一括でCP-SATソルバーに解かせる。
     * ツインのフロア配置もソルバーが最適化する。
     * ユーザー設定のツイン数は変更しない（ハード制約）。
     *
     * ★複数解対応: 解が見つかるたびにそのフロア割り当てパターンを禁止（nogood）して再ソルブ。
     */
    private static List<PartialSolutionResult> assignRoomsMultiple(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config,
            Map<String, TwinAssignment> twinPattern,
            int maxStaffPerFloor,
            int maxSolutions) {

        CpModel model = new CpModel();

        List<AdaptiveRoomOptimizer.ExtendedStaffInfo> staffList = config.extendedStaffInfo;
        List<AdaptiveRoomOptimizer.FloorInfo> allFloors = new ArrayList<>();
        allFloors.addAll(buildingData.mainFloors);
        allFloors.addAll(buildingData.annexFloors);

        Map<String, IntVar> xVars = new HashMap<>();    // シングル変数
        Map<String, IntVar> twinVars = new HashMap<>();  // ツイン変数
        Map<String, IntVar> ecoVars = new HashMap<>();   // ECO変数
        Map<String, BoolVar> yVars = new HashMap<>();    // フロア使用変数

        // === 変数作成 ===
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            NormalRoomDistributionDialog.StaffDistribution dist =
                    config.roomDistribution != null ?
                            config.roomDistribution.get(staffName) : null;
            if (dist == null) continue;

            TwinAssignment ta = twinPattern.get(staffName);
            boolean hasMainSingles = dist.mainSingleAssignedRooms > 0;
            boolean hasAnnexSingles = dist.annexSingleAssignedRooms > 0;
            boolean hasMainTwins = (ta != null && ta.mainTwinRooms > 0);
            boolean hasAnnexTwins = (ta != null && ta.annexTwinRooms > 0);
            boolean hasMainEco = dist.mainEcoAssignedRooms > 0;
            boolean hasAnnexEco = dist.annexEcoAssignedRooms > 0;

            for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
                boolean isMain = floor.isMainBuilding;
                boolean needSingle = (hasMainSingles && isMain) || (hasAnnexSingles && !isMain);
                boolean needTwin = (hasMainTwins && isMain) || (hasAnnexTwins && !isMain);
                boolean needEco = ((hasMainEco && isMain) || (hasAnnexEco && !isMain)) && floor.ecoRooms > 0;

                if (!needSingle && !needTwin && !needEco) continue;

                // シングル変数
                if (needSingle) {
                    for (Map.Entry<String, Integer> entry : floor.roomCounts.entrySet()) {
                        String roomType = entry.getKey();
                        if ((isMain && isMainTwin(roomType)) || (!isMain && isAnnexTwin(roomType))) continue;
                        String varName = String.format("x_%s_%d_%s", staffName, floor.floorNumber, roomType);
                        xVars.put(varName, model.newIntVar(0, entry.getValue(), varName));
                    }
                }

                // ツイン変数
                if (needTwin) {
                    int twinSupply = floor.roomCounts.entrySet().stream()
                            .filter(e -> (isMain && isMainTwin(e.getKey())) || (!isMain && isAnnexTwin(e.getKey())))
                            .mapToInt(Map.Entry::getValue).sum();
                    if (twinSupply > 0) {
                        String tVarName = String.format("t_%s_%d", staffName, floor.floorNumber);
                        twinVars.put(tVarName, model.newIntVar(0, twinSupply, tVarName));
                    }
                }

                // ECO変数
                if (needEco) {
                    String eVarName = String.format("e_%s_%d", staffName, floor.floorNumber);
                    ecoVars.put(eVarName, model.newIntVar(0, floor.ecoRooms, eVarName));
                }

                // フロア使用変数
                String yVarName = String.format("y_%s_%d", staffName, floor.floorNumber);
                if (!yVars.containsKey(yVarName)) {
                    yVars.put(yVarName, model.newBoolVar(yVarName));
                }
            }
        }

        // === ツイン供給制約: 各フロアのツイン合計 ≤ 供給数 ===
        for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
            boolean isMain = floor.isMainBuilding;
            int twinSupply = floor.roomCounts.entrySet().stream()
                    .filter(e -> (isMain && isMainTwin(e.getKey())) || (!isMain && isAnnexTwin(e.getKey())))
                    .mapToInt(Map.Entry::getValue).sum();
            if (twinSupply <= 0) continue;

            List<IntVar> vars = new ArrayList<>();
            for (AdaptiveRoomOptimizer.ExtendedStaffInfo si : staffList) {
                String tVarName = String.format("t_%s_%d", si.staff.name, floor.floorNumber);
                if (twinVars.containsKey(tVarName)) vars.add(twinVars.get(tVarName));
            }
            if (!vars.isEmpty()) {
                model.addLessOrEqual(LinearExpr.sum(vars.toArray(new IntVar[0])), twinSupply);
            }
        }

        // === ツイン目標制約: 各スタッフのツイン合計 = 設定値（ハード制約） ===
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            TwinAssignment ta = twinPattern.get(staffName);
            if (ta == null) continue;

            if (ta.mainTwinRooms > 0) {
                List<IntVar> mainTwinVarsList = new ArrayList<>();
                for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
                    String tVarName = String.format("t_%s_%d", staffName, floor.floorNumber);
                    if (twinVars.containsKey(tVarName)) mainTwinVarsList.add(twinVars.get(tVarName));
                }
                if (!mainTwinVarsList.isEmpty()) {
                    model.addEquality(LinearExpr.sum(mainTwinVarsList.toArray(new IntVar[0])), ta.mainTwinRooms);
                }
            }

            if (ta.annexTwinRooms > 0) {
                List<IntVar> annexTwinVarsList = new ArrayList<>();
                for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.annexFloors) {
                    String tVarName = String.format("t_%s_%d", staffName, floor.floorNumber);
                    if (twinVars.containsKey(tVarName)) annexTwinVarsList.add(twinVars.get(tVarName));
                }
                if (!annexTwinVarsList.isEmpty()) {
                    model.addEquality(LinearExpr.sum(annexTwinVarsList.toArray(new IntVar[0])), ta.annexTwinRooms);
                }
            }
        }

        // === シングル供給制約: 各フロアの部屋数を超えない ===
        for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
            for (Map.Entry<String, Integer> entry : floor.roomCounts.entrySet()) {
                String roomType = entry.getKey();
                if ((floor.isMainBuilding && isMainTwin(roomType)) ||
                        (!floor.isMainBuilding && isAnnexTwin(roomType))) continue;

                List<IntVar> vars = new ArrayList<>();
                for (AdaptiveRoomOptimizer.ExtendedStaffInfo si : staffList) {
                    String vn = String.format("x_%s_%d_%s", si.staff.name, floor.floorNumber, roomType);
                    if (xVars.containsKey(vn)) vars.add(xVars.get(vn));
                }
                if (!vars.isEmpty()) {
                    model.addLessOrEqual(LinearExpr.sum(vars.toArray(new IntVar[0])), entry.getValue());
                }
            }
        }

        // === ソフト制約: スタッフのシングル目標値 ===
        List<IntVar> shortageVars = new ArrayList<>();
        Map<String, IntVar> staffShortageVars = new HashMap<>();

        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            NormalRoomDistributionDialog.StaffDistribution dist =
                    config.roomDistribution != null ?
                            config.roomDistribution.get(staffName) : null;
            if (dist == null) continue;

            if (dist.mainSingleAssignedRooms > 0) {
                List<IntVar> mainVars = new ArrayList<>();
                for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
                    for (String roomType : floor.roomCounts.keySet()) {
                        if (isMainTwin(roomType)) continue;
                        String xVarName = String.format("x_%s_%d_%s", staffName, floor.floorNumber, roomType);
                        if (xVars.containsKey(xVarName)) mainVars.add(xVars.get(xVarName));
                    }
                }
                if (!mainVars.isEmpty()) {
                    IntVar sum = model.newIntVar(0, 1000, "sum_main_" + staffName);
                    model.addEquality(sum, LinearExpr.sum(mainVars.toArray(new IntVar[0])));
                    IntVar shortage = model.newIntVar(0, dist.mainSingleAssignedRooms, "short_main_" + staffName);
                    IntVar diff = model.newIntVar(-1000, 1000, "diff_main_" + staffName);
                    model.addEquality(diff, LinearExpr.newBuilder()
                            .addTerm(sum, -1).add(dist.mainSingleAssignedRooms).build());
                    model.addMaxEquality(shortage, new IntVar[]{diff, model.newConstant(0)});
                    shortageVars.add(shortage);
                    staffShortageVars.put(staffName + "_main", shortage);
                }
            }

            if (dist.annexSingleAssignedRooms > 0) {
                List<IntVar> annexVars = new ArrayList<>();
                for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.annexFloors) {
                    for (String roomType : floor.roomCounts.keySet()) {
                        if (isAnnexTwin(roomType)) continue;
                        String xVarName = String.format("x_%s_%d_%s", staffName, floor.floorNumber, roomType);
                        if (xVars.containsKey(xVarName)) annexVars.add(xVars.get(xVarName));
                    }
                }
                if (!annexVars.isEmpty()) {
                    IntVar sum = model.newIntVar(0, 1000, "sum_annex_" + staffName);
                    model.addEquality(sum, LinearExpr.sum(annexVars.toArray(new IntVar[0])));
                    IntVar shortage = model.newIntVar(0, dist.annexSingleAssignedRooms, "short_annex_" + staffName);
                    IntVar diff = model.newIntVar(-1000, 1000, "diff_annex_" + staffName);
                    model.addEquality(diff, LinearExpr.newBuilder()
                            .addTerm(sum, -1).add(dist.annexSingleAssignedRooms).build());
                    model.addMaxEquality(shortage, new IntVar[]{diff, model.newConstant(0)});
                    shortageVars.add(shortage);
                    staffShortageVars.put(staffName + "_annex", shortage);
                }
            }
        }

        // === フロア担当リンク制約（ツイン+シングル+ECO統合） ===
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
                String yVarName = String.format("y_%s_%d", staffName, floor.floorNumber);
                if (!yVars.containsKey(yVarName)) continue;

                List<IntVar> allRoomVars = new ArrayList<>();
                // シングル
                for (String rt : floor.roomCounts.keySet()) {
                    if ((floor.isMainBuilding && isMainTwin(rt)) ||
                            (!floor.isMainBuilding && isAnnexTwin(rt))) continue;
                    String vn = String.format("x_%s_%d_%s", staffName, floor.floorNumber, rt);
                    if (xVars.containsKey(vn)) allRoomVars.add(xVars.get(vn));
                }
                // ツイン
                String tVarName = String.format("t_%s_%d", staffName, floor.floorNumber);
                if (twinVars.containsKey(tVarName)) allRoomVars.add(twinVars.get(tVarName));
                // ECO
                String eVarName = String.format("e_%s_%d", staffName, floor.floorNumber);
                if (ecoVars.containsKey(eVarName)) allRoomVars.add(ecoVars.get(eVarName));

                if (!allRoomVars.isEmpty()) {
                    IntVar total = model.newIntVar(0, 1000, "total_" + staffName + "_" + floor.floorNumber);
                    model.addEquality(total, LinearExpr.sum(allRoomVars.toArray(new IntVar[0])));
                    BoolVar yVar = yVars.get(yVarName);
                    model.addGreaterThan(total, 0).onlyEnforceIf(yVar);
                    model.addEquality(total, 0).onlyEnforceIf(yVar.not());
                }
            }
        }

        // === フロア制限（建物別） ===
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            int maxMainFloors = 2;
            int maxAnnexFloors = 2;

            // 業者/リライアンスはフロア制限なし
            AdaptiveRoomOptimizer.PointConstraint constraint = config.pointConstraints.get(staffName);
            if (constraint != null) {
                String type = constraint.constraintType;
                if (type.contains("業者") || type.contains("リライアンス")) {
                    maxMainFloors = 99;
                    maxAnnexFloors = 99;
                }
            }

            // 大浴清掃スタッフは1フロア制限（業者設定を上書き）
            if (staffInfo.bathCleaningType != AdaptiveRoomOptimizer.BathCleaningType.NONE) {
                maxMainFloors = 1;
                maxAnnexFloors = 1;
            }

            // 本館＋別館の両方担当の場合は各1フロアに制限
            NormalRoomDistributionDialog.StaffDistribution dist =
                    config.roomDistribution != null ?
                            config.roomDistribution.get(staffName) : null;
            if (dist != null) {
                int mainR = dist.mainSingleAssignedRooms + dist.mainTwinAssignedRooms + dist.mainEcoAssignedRooms;
                int annexR = dist.annexSingleAssignedRooms + dist.annexTwinAssignedRooms + dist.annexEcoAssignedRooms;
                if (mainR > 0 && annexR > 0) {
                    maxMainFloors = Math.min(maxMainFloors, 1);
                    maxAnnexFloors = Math.min(maxAnnexFloors, 1);
                }
            }

            List<BoolVar> mainYVars = new ArrayList<>();
            List<BoolVar> annexYVars = new ArrayList<>();
            for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
                String yVarName = String.format("y_%s_%d", staffName, floor.floorNumber);
                if (!yVars.containsKey(yVarName)) continue;
                if (floor.isMainBuilding) {
                    mainYVars.add(yVars.get(yVarName));
                } else {
                    annexYVars.add(yVars.get(yVarName));
                }
            }

            if (!mainYVars.isEmpty()) {
                model.addLessOrEqual(LinearExpr.sum(mainYVars.toArray(new BoolVar[0])), maxMainFloors);
            }
            if (!annexYVars.isEmpty()) {
                model.addLessOrEqual(LinearExpr.sum(annexYVars.toArray(new BoolVar[0])), maxAnnexFloors);
            }
        }

        // === フロアあたりスタッフ数制限（統合版） ===
        for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
            List<BoolVar> staffOnFloor = new ArrayList<>();
            for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
                String yVarName = String.format("y_%s_%d", staffInfo.staff.name, floor.floorNumber);
                if (yVars.containsKey(yVarName)) {
                    staffOnFloor.add(yVars.get(yVarName));
                }
            }
            if (!staffOnFloor.isEmpty()) {
                model.addLessOrEqual(
                        LinearExpr.sum(staffOnFloor.toArray(new BoolVar[0])),
                        maxStaffPerFloor);
            }
        }

        // === ECO供給制約: 各フロアのECO合計 ≤ ECO供給数 ===
        for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
            if (floor.ecoRooms <= 0) continue;

            List<IntVar> vars = new ArrayList<>();
            for (AdaptiveRoomOptimizer.ExtendedStaffInfo si : staffList) {
                String eVarName = String.format("e_%s_%d", si.staff.name, floor.floorNumber);
                if (ecoVars.containsKey(eVarName)) vars.add(ecoVars.get(eVarName));
            }
            if (!vars.isEmpty()) {
                model.addLessOrEqual(LinearExpr.sum(vars.toArray(new IntVar[0])), floor.ecoRooms);
            }
        }

        // === ECOフロアリンク制約: ECOは担当フロア（y=1）からのみ割り振り可能 ===
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
                String eVarName = String.format("e_%s_%d", staffName, floor.floorNumber);
                if (!ecoVars.containsKey(eVarName)) continue;

                String yVarName = String.format("y_%s_%d", staffName, floor.floorNumber);
                if (yVars.containsKey(yVarName)) {
                    // e_s_f ≤ ecoRooms * y_s_f（y=0ならECO=0）
                    model.addLessOrEqual(ecoVars.get(eVarName),
                            LinearExpr.term(yVars.get(yVarName), floor.ecoRooms));
                }
            }
        }

        // === ECO目標制約（ソフト） ===
        List<IntVar> ecoShortageVars = new ArrayList<>();
        Map<String, IntVar> staffEcoShortageVars = new HashMap<>();

        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            NormalRoomDistributionDialog.StaffDistribution dist =
                    config.roomDistribution != null ?
                            config.roomDistribution.get(staffName) : null;
            if (dist == null) continue;

            if (dist.mainEcoAssignedRooms > 0) {
                List<IntVar> mainEcoVarsList = new ArrayList<>();
                for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
                    if (floor.ecoRooms <= 0) continue;
                    String eVarName = String.format("e_%s_%d", staffName, floor.floorNumber);
                    if (ecoVars.containsKey(eVarName)) mainEcoVarsList.add(ecoVars.get(eVarName));
                }
                if (!mainEcoVarsList.isEmpty()) {
                    IntVar ecoSum = model.newIntVar(0, 1000, "sum_main_eco_" + staffName);
                    model.addEquality(ecoSum, LinearExpr.sum(mainEcoVarsList.toArray(new IntVar[0])));
                    // ECO上限制約（目標値を超えない）
                    model.addLessOrEqual(ecoSum, dist.mainEcoAssignedRooms);
                    IntVar ecoShort = model.newIntVar(0, dist.mainEcoAssignedRooms, "short_main_eco_" + staffName);
                    IntVar ecoDiff = model.newIntVar(-1000, 1000, "diff_main_eco_" + staffName);
                    model.addEquality(ecoDiff, LinearExpr.newBuilder()
                            .addTerm(ecoSum, -1).add(dist.mainEcoAssignedRooms).build());
                    model.addMaxEquality(ecoShort, new IntVar[]{ecoDiff, model.newConstant(0)});
                    ecoShortageVars.add(ecoShort);
                    staffEcoShortageVars.put(staffName + "_main_eco", ecoShort);
                }
            }

            if (dist.annexEcoAssignedRooms > 0) {
                List<IntVar> annexEcoVarsList = new ArrayList<>();
                for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.annexFloors) {
                    if (floor.ecoRooms <= 0) continue;
                    String eVarName = String.format("e_%s_%d", staffName, floor.floorNumber);
                    if (ecoVars.containsKey(eVarName)) annexEcoVarsList.add(ecoVars.get(eVarName));
                }
                if (!annexEcoVarsList.isEmpty()) {
                    IntVar ecoSum = model.newIntVar(0, 1000, "sum_annex_eco_" + staffName);
                    model.addEquality(ecoSum, LinearExpr.sum(annexEcoVarsList.toArray(new IntVar[0])));
                    model.addLessOrEqual(ecoSum, dist.annexEcoAssignedRooms);
                    IntVar ecoShort = model.newIntVar(0, dist.annexEcoAssignedRooms, "short_annex_eco_" + staffName);
                    IntVar ecoDiff = model.newIntVar(-1000, 1000, "diff_annex_eco_" + staffName);
                    model.addEquality(ecoDiff, LinearExpr.newBuilder()
                            .addTerm(ecoSum, -1).add(dist.annexEcoAssignedRooms).build());
                    model.addMaxEquality(ecoShort, new IntVar[]{ecoDiff, model.newConstant(0)});
                    ecoShortageVars.add(ecoShort);
                    staffEcoShortageVars.put(staffName + "_annex_eco", ecoShort);
                }
            }
        }

        // === 目的関数: シングル優先（重み1000）+ ECO（重み1） ===
        LinearExprBuilder objectiveBuilder = LinearExpr.newBuilder();
        boolean hasObjective = false;
        for (IntVar sv : shortageVars) {
            objectiveBuilder.addTerm(sv, 1000);
            hasObjective = true;
        }
        for (IntVar ev : ecoShortageVars) {
            objectiveBuilder.addTerm(ev, 1);
            hasObjective = true;
        }
        if (hasObjective) {
            model.minimize(objectiveBuilder.build());
        }

        // === イテレーティブ解探索（nogoodループ） ===
        List<PartialSolutionResult> results = new ArrayList<>();
        int maxIterations = maxSolutions + 10;  // 部分解も見つかるため余裕を持つ

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            CpSolver solver = new CpSolver();
            solver.getParameters().setMaxTimeInSeconds(5.0);

            CpSolverStatus status = solver.solve(model);

            if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) {
                LOGGER.info(String.format("統合割り振り: イテレーション%dで解なし（ステータス=%s）、探索終了",
                        iteration + 1, status));
                break;
            }

            // shortage計算（シングル）
            int totalShortage = 0;
            for (IntVar shortageVar : shortageVars) {
                totalShortage += (int) solver.value(shortageVar);
            }

            // shortage計算（ECO）
            int totalEcoShortage = 0;
            for (IntVar ecoShortVar : ecoShortageVars) {
                totalEcoShortage += (int) solver.value(ecoShortVar);
            }

            // ソリューション構築（ECO含む）
            List<AdaptiveRoomOptimizer.StaffAssignment> assignments =
                    buildSolutionFromSolver(solver, xVars, twinVars, ecoVars, staffList, buildingData, config);

            int totalAssigned = 0, totalTarget = 0;
            Map<String, Integer> staffShortage = new HashMap<>();

            for (AdaptiveRoomOptimizer.ExtendedStaffInfo si : staffList) {
                String sn = si.staff.name;
                NormalRoomDistributionDialog.StaffDistribution d =
                        config.roomDistribution != null ?
                                config.roomDistribution.get(sn) : null;
                if (d == null) continue;

                int target = d.mainSingleAssignedRooms + d.annexSingleAssignedRooms;
                totalTarget += target;

                int mainShort = 0, annexShort = 0;
                if (staffShortageVars.containsKey(sn + "_main")) {
                    mainShort = (int) solver.value(staffShortageVars.get(sn + "_main"));
                }
                if (staffShortageVars.containsKey(sn + "_annex")) {
                    annexShort = (int) solver.value(staffShortageVars.get(sn + "_annex"));
                }

                int assigned = target - mainShort - annexShort;
                totalAssigned += assigned;
                staffShortage.put(sn, mainShort + annexShort);
            }

            results.add(new PartialSolutionResult(assignments, totalAssigned, totalTarget, staffShortage, totalEcoShortage));
            LOGGER.info(String.format("統合割り振り: イテレーション%d - %s（シングルshortage=%d, ECO shortage=%d）",
                    iteration + 1,
                    (totalShortage == 0 && totalEcoShortage == 0) ? "完全解" : "部分解",
                    totalShortage, totalEcoShortage));

            // 完全解の数をカウント（シングル+ECO両方0）
            long completeCount = results.stream().filter(r -> r.shortage == 0 && r.ecoShortage == 0).count();
            if (completeCount >= maxSolutions) {
                LOGGER.info(String.format("統合割り振り: 目標の%d個の完全解を取得、探索終了", maxSolutions));
                break;
            }

            // === Nogood制約追加: このy変数パターンを禁止 ===
            // 「少なくとも1つのy変数が今回と異なる値を取る」ことを強制
            List<Literal> nogoodLiterals = new ArrayList<>();
            for (Map.Entry<String, BoolVar> entry : yVars.entrySet()) {
                BoolVar y = entry.getValue();
                if (solver.value(y) == 1) {
                    nogoodLiterals.add(y.not());  // 今回1だったものが0になる可能性
                } else {
                    nogoodLiterals.add(y);          // 今回0だったものが1になる可能性
                }
            }
            if (!nogoodLiterals.isEmpty()) {
                model.addBoolOr(nogoodLiterals.toArray(new Literal[0]));
            }
        }

        if (results.isEmpty()) {
            LOGGER.warning("統合割り振りで解が見つかりませんでした");
        } else {
            long completeCount = results.stream().filter(r -> r.shortage == 0 && r.ecoShortage == 0).count();
            long singlesOnlyCount = results.stream().filter(r -> r.shortage == 0 && r.ecoShortage > 0).count();
            LOGGER.info(String.format("統合割り振り: 合計%d個の解（完全解%d個、シングルのみ完全%d個、部分解%d個）",
                    results.size(), completeCount, singlesOnlyCount,
                    results.size() - completeCount - singlesOnlyCount));
        }

        return results;
    }

    /**
     * CpSolverからソリューションを構築（ECO含む）
     */
    private static List<AdaptiveRoomOptimizer.StaffAssignment> buildSolutionFromSolver(
            CpSolver solver,
            Map<String, IntVar> xVars,
            Map<String, IntVar> twinVars,
            Map<String, IntVar> ecoVars,
            List<AdaptiveRoomOptimizer.ExtendedStaffInfo> staffList,
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        List<AdaptiveRoomOptimizer.StaffAssignment> result = new ArrayList<>();

        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;

            Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> mainAssignments = new TreeMap<>();
            Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> annexAssignments = new TreeMap<>();

            // 本館
            for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
                Map<String, Integer> rooms = new HashMap<>();
                // ツイン
                String tVarName = String.format("t_%s_%d", staffName, floor.floorNumber);
                if (twinVars.containsKey(tVarName)) {
                    int v = (int) solver.value(twinVars.get(tVarName));
                    if (v > 0) rooms.put("T", v);
                }
                // シングル
                for (String rt : floor.roomCounts.keySet()) {
                    if (isMainTwin(rt)) continue;
                    String vn = String.format("x_%s_%d_%s", staffName, floor.floorNumber, rt);
                    if (xVars.containsKey(vn)) {
                        int v = (int) solver.value(xVars.get(vn));
                        if (v > 0) rooms.put(rt, v);
                    }
                }
                // ECO
                int ecoCount = 0;
                String eVarName = String.format("e_%s_%d", staffName, floor.floorNumber);
                if (ecoVars.containsKey(eVarName)) {
                    ecoCount = (int) solver.value(ecoVars.get(eVarName));
                }
                if (!rooms.isEmpty() || ecoCount > 0) {
                    mainAssignments.put(floor.floorNumber,
                            new AdaptiveRoomOptimizer.RoomAllocation(rooms, ecoCount));
                }
            }

            // 別館
            for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.annexFloors) {
                Map<String, Integer> rooms = new HashMap<>();
                // ツイン
                String tVarName = String.format("t_%s_%d", staffName, floor.floorNumber);
                if (twinVars.containsKey(tVarName)) {
                    int v = (int) solver.value(twinVars.get(tVarName));
                    if (v > 0) rooms.put("T", v);
                }
                // シングル
                for (String rt : floor.roomCounts.keySet()) {
                    if (isAnnexTwin(rt)) continue;
                    String vn = String.format("x_%s_%d_%s", staffName, floor.floorNumber, rt);
                    if (xVars.containsKey(vn)) {
                        int v = (int) solver.value(xVars.get(vn));
                        if (v > 0) rooms.put(rt, v);
                    }
                }
                // ECO
                int ecoCount = 0;
                String eVarName = String.format("e_%s_%d", staffName, floor.floorNumber);
                if (ecoVars.containsKey(eVarName)) {
                    ecoCount = (int) solver.value(ecoVars.get(eVarName));
                }
                if (!rooms.isEmpty() || ecoCount > 0) {
                    annexAssignments.put(floor.floorNumber,
                            new AdaptiveRoomOptimizer.RoomAllocation(rooms, ecoCount));
                }
            }

            result.add(new AdaptiveRoomOptimizer.StaffAssignment(
                    staffInfo.staff, mainAssignments, annexAssignments, staffInfo.bathCleaningType));
        }

        return result;
    }
    // ================================================================
    // ECO割り振り（CP-SAT）
    // ================================================================

    /**
     * ECO割り振り（CP-SAT）
     */
    private static void assignEcoWithCPSAT(
            List<AdaptiveRoomOptimizer.StaffAssignment> assignments,
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        LOGGER.info("=== ECO割り振り（CP-SAT） ===");

        CpModel model = new CpModel();

        List<AdaptiveRoomOptimizer.FloorInfo> allFloors = new ArrayList<>();
        allFloors.addAll(buildingData.mainFloors);
        allFloors.addAll(buildingData.annexFloors);

        Map<String, IntVar> ecoVars = new HashMap<>();

        // 変数作成
        LOGGER.info("--- ECO変数作成 ---");
        for (AdaptiveRoomOptimizer.StaffAssignment sa : assignments) {
            String staffName = sa.staff.name;
            NormalRoomDistributionDialog.StaffDistribution dist =
                    config.roomDistribution != null ?
                            config.roomDistribution.get(staffName) : null;

            if (dist == null) {
                LOGGER.info(String.format("  %s: 設定なし（スキップ）", staffName));
                continue;
            }

            boolean hasMainEco = dist.mainEcoAssignedRooms > 0;
            boolean hasAnnexEco = dist.annexEcoAssignedRooms > 0;

            LOGGER.info(String.format("  %s: 本館ECO目標=%d, 別館ECO目標=%d",
                    staffName, dist.mainEcoAssignedRooms, dist.annexEcoAssignedRooms));

            for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
                if (floor.ecoRooms <= 0) continue;

                boolean isMain = floor.isMainBuilding;
                if ((hasMainEco && isMain) || (hasAnnexEco && !isMain)) {
                    String ecoVarName = String.format("eco_%s_%d_%s", staffName, floor.floorNumber, isMain ? "main" : "annex");
                    ecoVars.put(ecoVarName, model.newIntVar(0, floor.ecoRooms, ecoVarName));
                }
            }
        }

        // 制約1: 各フロアのECO数を超えない
        LOGGER.info("--- ECOフロア一覧 ---");
        for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
            if (floor.ecoRooms <= 0) continue;
            LOGGER.info(String.format("  %s %dF: ECO=%d室",
                    floor.isMainBuilding ? "本館" : "別館", floor.floorNumber, floor.ecoRooms));

            List<IntVar> ecoVarsOnFloor = new ArrayList<>();
            String suffix = floor.isMainBuilding ? "main" : "annex";
            for (AdaptiveRoomOptimizer.StaffAssignment sa : assignments) {
                String ecoVarName = String.format("eco_%s_%d_%s", sa.staff.name, floor.floorNumber, suffix);
                if (ecoVars.containsKey(ecoVarName)) {
                    ecoVarsOnFloor.add(ecoVars.get(ecoVarName));
                }
            }
            if (!ecoVarsOnFloor.isEmpty()) {
                model.addLessOrEqual(
                        LinearExpr.sum(ecoVarsOnFloor.toArray(new IntVar[0])),
                        floor.ecoRooms);
            }
        }

        // 制約2: フロア制限
        LOGGER.info("--- 通常清掃で使用しているフロア ---");
        Map<String, Set<Integer>> staffUsedMainFloors = new HashMap<>();
        Map<String, Set<Integer>> staffUsedAnnexFloors = new HashMap<>();
        for (AdaptiveRoomOptimizer.StaffAssignment sa : assignments) {
            Set<Integer> mainFloors = new HashSet<>(sa.mainBuildingAssignments.keySet());
            Set<Integer> annexFloors = new HashSet<>(sa.annexBuildingAssignments.keySet());
            staffUsedMainFloors.put(sa.staff.name, mainFloors);
            staffUsedAnnexFloors.put(sa.staff.name, annexFloors);
            LOGGER.info(String.format("  %s: 本館=%s, 別館=%s",
                    sa.staff.name, mainFloors, annexFloors));
        }

        for (AdaptiveRoomOptimizer.StaffAssignment sa : assignments) {
            String staffName = sa.staff.name;
            Set<Integer> usedMainFloors = staffUsedMainFloors.getOrDefault(staffName, new HashSet<>());
            Set<Integer> usedAnnexFloors = staffUsedAnnexFloors.getOrDefault(staffName, new HashSet<>());

            // ★修正: 本館・別館それぞれに個別のフロア上限を設定
            int maxMainFloors = 2;   // 本館の最大フロア数（デフォルト）
            int maxAnnexFloors = 2;  // 別館の最大フロア数（デフォルト）

            AdaptiveRoomOptimizer.PointConstraint constraint = config.pointConstraints.get(staffName);
            if (constraint != null) {
                String type = constraint.constraintType;
                if (type.contains("業者") || type.contains("リライアンス")) {
                    maxMainFloors = 99;
                    maxAnnexFloors = 99;
                }
            }
            if (config.roomDistribution != null) {
                NormalRoomDistributionDialog.StaffDistribution dist = config.roomDistribution.get(staffName);
                if (dist != null) {
                    int mainR = dist.mainSingleAssignedRooms + dist.mainTwinAssignedRooms + dist.mainEcoAssignedRooms;
                    int annexR = dist.annexSingleAssignedRooms + dist.annexTwinAssignedRooms + dist.annexEcoAssignedRooms;
                    // ★両方割り振りの場合、各建物1フロアずつに制限
                    if (mainR > 0 && annexR > 0) {
                        maxMainFloors = 1;
                        maxAnnexFloors = 1;
                    }
                }
            }

            // 本館の新規フロア制限
            List<IntVar> newMainEcoVars = new ArrayList<>();

            for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
                if (floor.ecoRooms <= 0) continue;
                if (usedMainFloors.contains(floor.floorNumber)) continue;

                String ecoVarName = String.format("eco_%s_%d_main", staffName, floor.floorNumber);
                if (ecoVars.containsKey(ecoVarName)) {
                    newMainEcoVars.add(ecoVars.get(ecoVarName));
                }
            }

            // 別館の新規フロア制限
            List<IntVar> newAnnexEcoVars = new ArrayList<>();
            for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.annexFloors) {
                if (floor.ecoRooms <= 0) continue;
                if (usedAnnexFloors.contains(floor.floorNumber)) continue;

                String ecoVarName = String.format("eco_%s_%d_annex", staffName, floor.floorNumber);
                if (ecoVars.containsKey(ecoVarName)) {
                    newAnnexEcoVars.add(ecoVars.get(ecoVarName));
                }
            }

            // 本館の新規フロア数制限
            int remainingMainFloors = Math.max(0, maxMainFloors - usedMainFloors.size());
            if (!newMainEcoVars.isEmpty() && remainingMainFloors == 0) {
                for (IntVar v : newMainEcoVars) {
                    model.addEquality(v, 0);
                }
            } else if (!newMainEcoVars.isEmpty()) {
                List<BoolVar> newFloorUsed = new ArrayList<>();
                for (IntVar v : newMainEcoVars) {
                    BoolVar used = model.newBoolVar("used_" + v.getName());
                    model.addGreaterThan(v, 0).onlyEnforceIf(used);
                    model.addEquality(v, 0).onlyEnforceIf(used.not());
                    newFloorUsed.add(used);
                }
                model.addLessOrEqual(LinearExpr.sum(newFloorUsed.toArray(new BoolVar[0])), remainingMainFloors);
            }

            // 別館の新規フロア数制限
            // 別館の新規フロア数制限
            int remainingAnnexFloors = Math.max(0, maxAnnexFloors - usedAnnexFloors.size());
            if (!newAnnexEcoVars.isEmpty() && remainingAnnexFloors == 0) {
                for (IntVar v : newAnnexEcoVars) {
                    model.addEquality(v, 0);
                }
            } else if (!newAnnexEcoVars.isEmpty()) {
                List<BoolVar> newFloorUsed = new ArrayList<>();
                for (IntVar v : newAnnexEcoVars) {
                    BoolVar used = model.newBoolVar("used_" + v.getName());
                    model.addGreaterThan(v, 0).onlyEnforceIf(used);
                    model.addEquality(v, 0).onlyEnforceIf(used.not());
                    newFloorUsed.add(used);
                }
                model.addLessOrEqual(LinearExpr.sum(newFloorUsed.toArray(new BoolVar[0])), remainingAnnexFloors);
            }

            LOGGER.info(String.format("  %s: 本館フロア=%d（残り%d、上限%d）、別館フロア=%d（残り%d、上限%d）",
                    staffName, usedMainFloors.size(), remainingMainFloors, maxMainFloors,
                    usedAnnexFloors.size(), remainingAnnexFloors, maxAnnexFloors));
        }

        // ソフト制約: スタッフのECO目標値
        List<IntVar> deviationVars = new ArrayList<>();

        for (AdaptiveRoomOptimizer.StaffAssignment sa : assignments) {
            String staffName = sa.staff.name;
            NormalRoomDistributionDialog.StaffDistribution dist =
                    config.roomDistribution != null ?
                            config.roomDistribution.get(staffName) : null;

            if (dist == null) continue;

            // 本館ECO
            if (dist.mainEcoAssignedRooms > 0) {
                List<IntVar> mainEcoVarsList = new ArrayList<>();
                for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
                    if (floor.ecoRooms > 0) {
                        String ecoVarName = String.format("eco_%s_%d_main", staffName, floor.floorNumber);
                        if (ecoVars.containsKey(ecoVarName)) {
                            mainEcoVarsList.add(ecoVars.get(ecoVarName));
                        }
                    }
                }

                if (!mainEcoVarsList.isEmpty()) {
                    IntVar ecoSum = model.newIntVar(0, 1000, "sum_main_eco_" + staffName);
                    model.addEquality(ecoSum, LinearExpr.sum(mainEcoVarsList.toArray(new IntVar[0])));

                    model.addLessOrEqual(ecoSum, dist.mainEcoAssignedRooms);

                    IntVar ecoShortage = model.newIntVar(0, dist.mainEcoAssignedRooms, "short_main_eco_" + staffName);
                    model.addGreaterOrEqual(
                            LinearExpr.sum(new IntVar[]{ecoSum, ecoShortage}),
                            dist.mainEcoAssignedRooms);
                    deviationVars.add(ecoShortage);
                }
            }

            // 別館ECO
            if (dist.annexEcoAssignedRooms > 0) {
                List<IntVar> annexEcoVarsList = new ArrayList<>();
                for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.annexFloors) {
                    if (floor.ecoRooms > 0) {
                        String ecoVarName = String.format("eco_%s_%d_annex", staffName, floor.floorNumber);
                        if (ecoVars.containsKey(ecoVarName)) {
                            annexEcoVarsList.add(ecoVars.get(ecoVarName));
                        }
                    }
                }

                if (!annexEcoVarsList.isEmpty()) {
                    IntVar ecoSum = model.newIntVar(0, 1000, "sum_annex_eco_" + staffName);
                    model.addEquality(ecoSum, LinearExpr.sum(annexEcoVarsList.toArray(new IntVar[0])));

                    model.addLessOrEqual(ecoSum, dist.annexEcoAssignedRooms);

                    IntVar ecoShortage = model.newIntVar(0, dist.annexEcoAssignedRooms, "short_annex_eco_" + staffName);
                    model.addGreaterOrEqual(
                            LinearExpr.sum(new IntVar[]{ecoSum, ecoShortage}),
                            dist.annexEcoAssignedRooms);
                    deviationVars.add(ecoShortage);
                }
            }
        }

        // 目的関数
        List<IntVar> existingFloorEcoVars = new ArrayList<>();
        for (AdaptiveRoomOptimizer.StaffAssignment sa : assignments) {
            String staffName = sa.staff.name;
            Set<Integer> usedMainFloors = staffUsedMainFloors.getOrDefault(staffName, new HashSet<>());
            Set<Integer> usedAnnexFloors = staffUsedAnnexFloors.getOrDefault(staffName, new HashSet<>());

            for (Integer floorNum : usedMainFloors) {
                String ecoVarName = String.format("eco_%s_%d_main", staffName, floorNum);
                if (ecoVars.containsKey(ecoVarName)) {
                    existingFloorEcoVars.add(ecoVars.get(ecoVarName));
                }
            }

            for (Integer floorNum : usedAnnexFloors) {
                String ecoVarName = String.format("eco_%s_%d_annex", staffName, floorNum);
                if (ecoVars.containsKey(ecoVarName)) {
                    existingFloorEcoVars.add(ecoVars.get(ecoVarName));
                }
            }
        }

        List<IntVar> objectiveVars = new ArrayList<>();
        List<Long> objectiveCoeffs = new ArrayList<>();

        for (IntVar shortageVar : deviationVars) {
            objectiveVars.add(shortageVar);
            objectiveCoeffs.add(1000L);
        }
        for (IntVar ecoVar : existingFloorEcoVars) {
            objectiveVars.add(ecoVar);
            objectiveCoeffs.add(-1L);
        }

        if (!objectiveVars.isEmpty()) {
            long[] coeffArray = objectiveCoeffs.stream().mapToLong(Long::longValue).toArray();
            model.minimize(LinearExpr.weightedSum(objectiveVars.toArray(new IntVar[0]), coeffArray));
        }

        // 解を求める
        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(10.0);
        CpSolverStatus status = solver.solve(model);

        if (status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE) {
            LOGGER.info("ECO割り振り: 解が見つかりました");

            // 結果をassignmentsに反映
            for (AdaptiveRoomOptimizer.StaffAssignment sa : assignments) {
                String staffName = sa.staff.name;

                // 本館ECO
                for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
                    if (floor.ecoRooms <= 0) continue;

                    String ecoVarName = String.format("eco_%s_%d_main", staffName, floor.floorNumber);
                    if (ecoVars.containsKey(ecoVarName)) {
                        int ecoCount = (int) solver.value(ecoVars.get(ecoVarName));
                        if (ecoCount > 0) {
                            addEcoToStaff(sa, floor.floorNumber, ecoCount, true);
                        }
                    }
                }

                // 別館ECO
                for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.annexFloors) {
                    if (floor.ecoRooms <= 0) continue;

                    String ecoVarName = String.format("eco_%s_%d_annex", staffName, floor.floorNumber);
                    if (ecoVars.containsKey(ecoVarName)) {
                        int ecoCount = (int) solver.value(ecoVars.get(ecoVarName));
                        if (ecoCount > 0) {
                            addEcoToStaff(sa, floor.floorNumber, ecoCount, false);
                        }
                    }
                }
            }

            // 結果ログ
            LOGGER.info("=== ECO割り振り結果 ===");
            for (AdaptiveRoomOptimizer.StaffAssignment sa : assignments) {
                int mainEco = getMainBuildingEcoRooms(sa);
                int annexEco = getAnnexBuildingEcoRooms(sa);
                if (mainEco > 0 || annexEco > 0) {
                    LOGGER.info(String.format("  %s: 本館ECO=%d, 別館ECO=%d", sa.staff.name, mainEco, annexEco));
                }
            }
        } else {
            LOGGER.warning("ECO割り振り: 解が見つかりませんでした。status=" + status);
        }
    }

    private static void addEcoToStaff(AdaptiveRoomOptimizer.StaffAssignment sa,
                                      int floorNumber, int ecoCount, boolean isMain) {
        Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> fm =
                isMain ? sa.mainBuildingAssignments : sa.annexBuildingAssignments;

        AdaptiveRoomOptimizer.RoomAllocation existing = fm.get(floorNumber);
        AdaptiveRoomOptimizer.RoomAllocation newAllocation;
        if (existing != null) {
            newAllocation = new AdaptiveRoomOptimizer.RoomAllocation(
                    existing.roomCounts, existing.ecoRooms + ecoCount);
        } else {
            newAllocation = new AdaptiveRoomOptimizer.RoomAllocation(new HashMap<>(), ecoCount);
        }
        fm.put(floorNumber, newAllocation);

        sa.roomsByFloor.put(floorNumber, newAllocation);

        if (!sa.floors.contains(floorNumber)) {
            sa.floors.add(floorNumber);
            Collections.sort(sa.floors);
        }
    }

    // ================================================================
    // 補助メソッド
    // ================================================================

    private static int getMainBuildingRooms(AdaptiveRoomOptimizer.StaffAssignment sa) {
        int total = 0;
        for (AdaptiveRoomOptimizer.RoomAllocation allocation : sa.mainBuildingAssignments.values()) {
            total += allocation.roomCounts.values().stream().mapToInt(Integer::intValue).sum();
            total += allocation.ecoRooms;
        }
        return total;
    }

    private static int getAnnexBuildingRooms(AdaptiveRoomOptimizer.StaffAssignment sa) {
        int total = 0;
        for (AdaptiveRoomOptimizer.RoomAllocation allocation : sa.annexBuildingAssignments.values()) {
            total += allocation.roomCounts.values().stream().mapToInt(Integer::intValue).sum();
            total += allocation.ecoRooms;
        }
        return total;
    }

    private static int getMainBuildingRoomsWithoutEco(AdaptiveRoomOptimizer.StaffAssignment sa) {
        int total = 0;
        for (AdaptiveRoomOptimizer.RoomAllocation allocation : sa.mainBuildingAssignments.values()) {
            total += allocation.roomCounts.values().stream().mapToInt(Integer::intValue).sum();
        }
        return total;
    }

    private static int getAnnexBuildingRoomsWithoutEco(AdaptiveRoomOptimizer.StaffAssignment sa) {
        int total = 0;
        for (AdaptiveRoomOptimizer.RoomAllocation allocation : sa.annexBuildingAssignments.values()) {
            total += allocation.roomCounts.values().stream().mapToInt(Integer::intValue).sum();
        }
        return total;
    }

    private static int getMainBuildingEcoRooms(AdaptiveRoomOptimizer.StaffAssignment sa) {
        return sa.mainBuildingAssignments.values().stream()
                .mapToInt(a -> a.ecoRooms)
                .sum();
    }

    private static int getAnnexBuildingEcoRooms(AdaptiveRoomOptimizer.StaffAssignment sa) {
        return sa.annexBuildingAssignments.values().stream()
                .mapToInt(a -> a.ecoRooms)
                .sum();
    }

    /**
     * ★修正: 本館・別館両方割り振りの場合も2フロア制限を維持
     * （各建物1フロアずつ = 合計2フロア）
     */
    private static int getMaxFloors(String staffName, AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {
        int maxFloors = 2;

        AdaptiveRoomOptimizer.PointConstraint constraint = config.pointConstraints.get(staffName);
        if (constraint != null) {
            String type = constraint.constraintType;
            if (type.contains("業者") || type.contains("リライアンス")) {
                maxFloors = 99;
            }
        }

        for (AdaptiveRoomOptimizer.ExtendedStaffInfo si : config.extendedStaffInfo) {
            if (si.staff.name.equals(staffName) &&
                    si.bathCleaningType != AdaptiveRoomOptimizer.BathCleaningType.NONE) {
                return 1;
            }
        }

        // ★修正: 本館・別館両方割り振りでも maxFloors = 2 のまま
        // （各建物で個別に1フロア制限がかかるため、合計2フロアとなる）
        // if (mainR > 0 && annexR > 0) maxFloors = 4; は削除

        return maxFloors;
    }
}