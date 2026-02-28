package org.example;

import com.google.ortools.sat.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * CP-SATソルバーを使用した部屋割り振り最適化
 * ★修正版: 未割当部屋対応
 * - 完全解が見つからない場合も処理を継続
 * - 未割当部屋を返却し、手動割り振りを可能にする
 * - 均等性評価を削除してシンプル化
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

    // ★追加: 複数解の最大数（ユーザーに表示する解の数）
    public static final int MAX_SOLUTIONS_TO_KEEP = 7;

    // フロアあたりの最大スタッフ数（初期値と上限）
    private static final int INITIAL_MAX_STAFF_PER_FLOOR = 2;
    private static final int MAX_STAFF_PER_FLOOR_LIMIT   = 3;

    /**
     * 段階的緩和設定クラス
     * 完全解が見つからない場合に段階的に制約を緩和するための設定を保持する。
     * 本館の緩和ステップ:
     *   Step1: 制約なし（1フロア3人制限のみ）
     *   Step2: 大浴清掃スタッフの1フロア制限を解除（→2フロアまで許可）
     *   Step3: 通常スタッフのうち1人だけ3フロアまで許可（最終手段）
     * 別館の緩和ステップ:
     *   Step1: 制約なし（1フロア3人制限のみ）
     *   Step2: 通常スタッフのうち1人だけ3フロアまで許可（最終手段）
     */
    private static class RelaxationConfig {
        /** 大浴清掃スタッフの1フロア制限を解除するか（本館Step2以降） */
        final boolean releaseBathFloorLimit;
        /** 通常スタッフのうち1人だけ3フロアまで許可するか（最終手段） */
        final boolean allowOneStaffThreeFloors;

        static final RelaxationConfig STEP1        = new RelaxationConfig(false, false);
        static final RelaxationConfig STEP2_MAIN   = new RelaxationConfig(true,  false);
        static final RelaxationConfig STEP3_MAIN   = new RelaxationConfig(true,  true);
        static final RelaxationConfig STEP2_ANNEX  = new RelaxationConfig(false, true);

        RelaxationConfig(boolean releaseBathFloorLimit, boolean allowOneStaffThreeFloors) {
            this.releaseBathFloorLimit    = releaseBathFloorLimit;
            this.allowOneStaffThreeFloors = allowOneStaffThreeFloors;
        }

        @Override
        public String toString() {
            if (!releaseBathFloorLimit && !allowOneStaffThreeFloors) return "Step1(制約なし)";
            if (releaseBathFloorLimit  && !allowOneStaffThreeFloors) return "Step2(大浴制限解除)";
            if (releaseBathFloorLimit  &&  allowOneStaffThreeFloors) return "Step3(1人3フロア許可+大浴制限解除)";
            return "Step2別館(1人3フロア許可)";
        }
    }

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
        LOGGER.info("大浴清掃スタッフを含む全スタッフをCP-SATで統合最適化します。");
        LOGGER.info("（大浴清掃スタッフはmaxFloors=1制約により1フロアのみ担当）");

        // 全スタッフ（大浴清掃スタッフ含む）をCP-SATで最適化
        List<AdaptiveRoomOptimizer.StaffAssignment> assignments =
                optimizeWithCPSAT(buildingData, config, new HashMap<>(), config);

        // 未割当部屋を計算
        UnassignedRooms unassignedRooms = calculateUnassignedRooms(buildingData, assignments);

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
        LOGGER.info("大浴清掃スタッフを含む全スタッフをCP-SATで統合最適化します。");

        // 全スタッフ（大浴清掃スタッフ含む）をCP-SATで複数解最適化
        List<List<AdaptiveRoomOptimizer.StaffAssignment>> allAssignmentsList =
                optimizeWithCPSATMultiple(buildingData, config, new HashMap<>(), config, MAX_SOLUTIONS_TO_KEEP);

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
                UnassignedRooms unassignedRooms = calculateUnassignedRooms(buildingData, assignments);
                AdaptiveRoomOptimizer.assignLinenClosetFloors(assignments);
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
                    new ArrayList<>(), calculateUnassignedRooms(buildingData, new ArrayList<>())));
        }

        if (solutions.size() > 0 && solutions.get(0).unassignedRooms.hasUnassigned()) {
            LOGGER.warning("=== 未割当部屋があります ===");
            solutions.get(0).unassignedRooms.printSummary();
        }

        LOGGER.info(String.format("=== 複数解最適化完了: %d個の解を生成 ===", solutions.size()));

        return new MultiSolutionResult(solutions);
    }

    /**
     * CPSAT複数解最適化（本館・別館を独立して解き、クロス積で複数解を生成）
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

        // === 本館・別館を分離して独立最適化 ===
        AdaptiveRoomOptimizer.BuildingData mainOnlyData = buildingDataMainOnly(buildingData);
        AdaptiveRoomOptimizer.BuildingData annexOnlyData = buildingDataAnnexOnly(buildingData);
        AdaptiveRoomOptimizer.AdaptiveLoadConfig mainConfig = splitConfigForMain(config);
        AdaptiveRoomOptimizer.AdaptiveLoadConfig annexConfig = splitConfigForAnnex(config);

        List<List<AdaptiveRoomOptimizer.StaffAssignment>> mainSolutions = new ArrayList<>();
        List<List<AdaptiveRoomOptimizer.StaffAssignment>> annexSolutions = new ArrayList<>();

        // 本館複数解最適化（2人→3人→緩和ステップ）
        if (!mainConfig.extendedStaffInfo.isEmpty() && mainOnlyData.mainRoomCount > 0) {
            LOGGER.info("=== 本館独立CPSAT複数解最適化を開始 ===");
            // Step1: 1フロア2人→3人で試行
            for (int maxStaffPerFloor = INITIAL_MAX_STAFF_PER_FLOOR;
                 maxStaffPerFloor <= MAX_STAFF_PER_FLOOR_LIMIT; maxStaffPerFloor++) {
                List<List<AdaptiveRoomOptimizer.StaffAssignment>> results =
                        optimizeWithRelaxationMultiple(mainOnlyData, mainConfig,
                                existingAssignments, RelaxationConfig.STEP1, originalConfig, maxSolutions, maxStaffPerFloor);
                if (results != null && !results.isEmpty()) {
                    LOGGER.info(String.format("本館: 1フロア最大%d人制限で%d個の解が見つかりました。", maxStaffPerFloor, results.size()));
                    mainSolutions = results;
                    break;
                }
                LOGGER.warning(String.format("本館: 1フロア%d人制限では完全解なし。", maxStaffPerFloor));
            }
            // Step2以降の緩和（3人でも解なしの場合）
            if (mainSolutions.isEmpty()) {
                RelaxationConfig[] relaxSteps = {
                        RelaxationConfig.STEP2_MAIN,
                        RelaxationConfig.STEP3_MAIN
                };
                for (RelaxationConfig step : relaxSteps) {
                    List<List<AdaptiveRoomOptimizer.StaffAssignment>> results =
                            optimizeWithRelaxationMultiple(mainOnlyData, mainConfig,
                                    existingAssignments, step, originalConfig, maxSolutions, MAX_STAFF_PER_FLOOR_LIMIT);
                    if (results != null && !results.isEmpty()) {
                        LOGGER.info(String.format("本館: %s で%d個の解が見つかりました。", step, results.size()));
                        mainSolutions = results;
                        break;
                    }
                    LOGGER.warning("本館: " + step + " では完全解なし。次のステップへ。");
                }
            }
            if (mainSolutions.isEmpty()) {
                LOGGER.warning("本館: 解が見つかりませんでした。");
                mainSolutions.add(new ArrayList<>());
            }
        } else {
            LOGGER.info("本館: 対象スタッフまたは部屋なし、スキップ");
            mainSolutions.add(new ArrayList<>());
        }

        // 別館複数解最適化（2人→3人→緩和ステップ）
        if (!annexConfig.extendedStaffInfo.isEmpty() && annexOnlyData.annexRoomCount > 0) {
            LOGGER.info("=== 別館独立CPSAT複数解最適化を開始 ===");
            // Step1: 1フロア2人→3人で試行
            for (int maxStaffPerFloor = INITIAL_MAX_STAFF_PER_FLOOR;
                 maxStaffPerFloor <= MAX_STAFF_PER_FLOOR_LIMIT; maxStaffPerFloor++) {
                List<List<AdaptiveRoomOptimizer.StaffAssignment>> results =
                        optimizeWithRelaxationMultiple(annexOnlyData, annexConfig,
                                existingAssignments, RelaxationConfig.STEP1, originalConfig, maxSolutions, maxStaffPerFloor);
                if (results != null && !results.isEmpty()) {
                    LOGGER.info(String.format("別館: 1フロア最大%d人制限で%d個の解が見つかりました。", maxStaffPerFloor, results.size()));
                    annexSolutions = results;
                    break;
                }
                LOGGER.warning(String.format("別館: 1フロア%d人制限では完全解なし。", maxStaffPerFloor));
            }
            // Step2（最終手段）の緩和（3人でも解なしの場合）
            if (annexSolutions.isEmpty()) {
                List<List<AdaptiveRoomOptimizer.StaffAssignment>> results =
                        optimizeWithRelaxationMultiple(annexOnlyData, annexConfig,
                                existingAssignments, RelaxationConfig.STEP2_ANNEX, originalConfig, maxSolutions, MAX_STAFF_PER_FLOOR_LIMIT);
                if (results != null && !results.isEmpty()) {
                    LOGGER.info(String.format("別館: %s で%d個の解が見つかりました。", RelaxationConfig.STEP2_ANNEX, results.size()));
                    annexSolutions = results;
                }
            }
            if (annexSolutions.isEmpty()) {
                LOGGER.warning("別館: 解が見つかりませんでした。");
                annexSolutions.add(new ArrayList<>());
            }
        } else {
            LOGGER.info("別館: 対象スタッフまたは部屋なし、スキップ");
            annexSolutions.add(new ArrayList<>());
        }

        // === 本館解×別館解の全組み合わせを生成し、スコアで上位maxSolutions件を選出 ===
        List<List<AdaptiveRoomOptimizer.StaffAssignment>> allCombinations = new ArrayList<>();
        for (List<AdaptiveRoomOptimizer.StaffAssignment> mainSol : mainSolutions) {
            for (List<AdaptiveRoomOptimizer.StaffAssignment> annexSol : annexSolutions) {
                List<AdaptiveRoomOptimizer.StaffAssignment> merged =
                        mergeMainAnnexAssignments(mainSol, annexSol, config);
                allCombinations.add(merged);
            }
        }
        LOGGER.info(String.format("本館%d解×別館%d解 = 全%d通りの組み合わせを生成、スコアリング開始",
                mainSolutions.size(), annexSolutions.size(), allCombinations.size()));

        // 各組み合わせの換算スコアばらつきを計算してソート
        allCombinations.sort((a, b) -> {
            double scoreA = calculateCombinationSpread(a, config);
            double scoreB = calculateCombinationSpread(b, config);
            return Double.compare(scoreA, scoreB);
        });

        // 上位maxSolutions件を返す
        List<List<AdaptiveRoomOptimizer.StaffAssignment>> combined =
                allCombinations.subList(0, Math.min(maxSolutions, allCombinations.size()));
        LOGGER.info(String.format("スコアリング完了: 上位%d件を選出", combined.size()));
        return new ArrayList<>(combined);
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

    /**
     * CPSAT最適化（本館・別館を独立して解く）
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

        // === 本館・別館を分離して独立最適化 ===
        AdaptiveRoomOptimizer.BuildingData mainOnlyData = buildingDataMainOnly(buildingData);
        AdaptiveRoomOptimizer.BuildingData annexOnlyData = buildingDataAnnexOnly(buildingData);
        AdaptiveRoomOptimizer.AdaptiveLoadConfig mainConfig = splitConfigForMain(config);
        AdaptiveRoomOptimizer.AdaptiveLoadConfig annexConfig = splitConfigForAnnex(config);

        List<AdaptiveRoomOptimizer.StaffAssignment> mainResult = new ArrayList<>();
        List<AdaptiveRoomOptimizer.StaffAssignment> annexResult = new ArrayList<>();

        // 本館最適化（2人→3人→緩和ステップ）
        if (!mainConfig.extendedStaffInfo.isEmpty() && mainOnlyData.mainRoomCount > 0) {
            LOGGER.info("=== 本館独立CPSAT最適化を開始 ===");
            // Step1: 1フロア2人→3人で試行
            for (int maxStaffPerFloor = INITIAL_MAX_STAFF_PER_FLOOR;
                 maxStaffPerFloor <= MAX_STAFF_PER_FLOOR_LIMIT; maxStaffPerFloor++) {
                List<AdaptiveRoomOptimizer.StaffAssignment> result =
                        optimizeWithRelaxation(mainOnlyData, mainConfig,
                                existingAssignments, RelaxationConfig.STEP1, originalConfig, maxStaffPerFloor);
                if (result != null && !result.isEmpty()) {
                    LOGGER.info(String.format("本館: 1フロア最大%d人制限で完全解が見つかりました。", maxStaffPerFloor));
                    mainResult = result;
                    break;
                }
                LOGGER.warning(String.format("本館: 1フロア%d人制限では完全解なし。", maxStaffPerFloor));
            }
            // Step2以降の緩和（3人でも解なしの場合）
            if (mainResult.isEmpty()) {
                RelaxationConfig[] relaxSteps = {
                        RelaxationConfig.STEP2_MAIN,
                        RelaxationConfig.STEP3_MAIN
                };
                for (RelaxationConfig step : relaxSteps) {
                    List<AdaptiveRoomOptimizer.StaffAssignment> result =
                            optimizeWithRelaxation(mainOnlyData, mainConfig,
                                    existingAssignments, step, originalConfig, MAX_STAFF_PER_FLOOR_LIMIT);
                    if (result != null && !result.isEmpty()) {
                        LOGGER.info("本館: " + step + " で完全解が見つかりました。");
                        mainResult = result;
                        break;
                    }
                    LOGGER.warning("本館: " + step + " では完全解なし。次のステップへ。");
                }
            }
            if (mainResult.isEmpty()) {
                LOGGER.warning("本館: 全ステップで完全解が見つかりませんでした。未割当が発生します。");
            }
        } else {
            LOGGER.info("本館: 対象スタッフまたは部屋なし、スキップ");
        }

        // 別館最適化（2人→3人→緩和ステップ）
        if (!annexConfig.extendedStaffInfo.isEmpty() && annexOnlyData.annexRoomCount > 0) {
            LOGGER.info("=== 別館独立CPSAT最適化を開始 ===");
            // Step1: 1フロア2人→3人で試行
            for (int maxStaffPerFloor = INITIAL_MAX_STAFF_PER_FLOOR;
                 maxStaffPerFloor <= MAX_STAFF_PER_FLOOR_LIMIT; maxStaffPerFloor++) {
                List<AdaptiveRoomOptimizer.StaffAssignment> result =
                        optimizeWithRelaxation(annexOnlyData, annexConfig,
                                existingAssignments, RelaxationConfig.STEP1, originalConfig, maxStaffPerFloor);
                if (result != null && !result.isEmpty()) {
                    LOGGER.info(String.format("別館: 1フロア最大%d人制限で完全解が見つかりました。", maxStaffPerFloor));
                    annexResult = result;
                    break;
                }
                LOGGER.warning(String.format("別館: 1フロア%d人制限では完全解なし。", maxStaffPerFloor));
            }
            // Step2（最終手段）の緩和（3人でも解なしの場合）
            if (annexResult.isEmpty()) {
                List<AdaptiveRoomOptimizer.StaffAssignment> result =
                        optimizeWithRelaxation(annexOnlyData, annexConfig,
                                existingAssignments, RelaxationConfig.STEP2_ANNEX, originalConfig, MAX_STAFF_PER_FLOOR_LIMIT);
                if (result != null && !result.isEmpty()) {
                    LOGGER.info("別館: " + RelaxationConfig.STEP2_ANNEX + " で完全解が見つかりました。");
                    annexResult = result;
                }
            }
            if (annexResult.isEmpty()) {
                LOGGER.warning("別館: 全ステップで完全解が見つかりませんでした。未割当が発生します。");
            }
        } else {
            LOGGER.info("別館: 対象スタッフまたは部屋なし、スキップ");
        }

        return mergeMainAnnexAssignments(mainResult, annexResult, config);
    }

    /**
     * 緩和設定付きCPSAT最適化（単一解）
     * 完全解が見つかった場合のみ返す。見つからない場合はnullを返し呼び出し側が次のステップへ進む。
     * 全ステップで完全解なしの場合は最終ステップで部分解を使用する。
     */
    private static List<AdaptiveRoomOptimizer.StaffAssignment> optimizeWithRelaxation(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config,
            Map<String, AdaptiveRoomOptimizer.StaffAssignment> existingAssignments,
            RelaxationConfig relaxConfig,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig originalConfig,
            int maxStaffPerFloor) {

        LOGGER.info(String.format("=== CPSAT最適化（%s, 1フロア最大%d人）===", relaxConfig, maxStaffPerFloor));

        List<Map<String, TwinAssignment>> twinPatterns = generateTwinPatterns(buildingData, config);
        if (twinPatterns.isEmpty()) {
            LOGGER.warning("ツインパターンが生成されませんでした。空の結果を返します。");
            return new ArrayList<>();
        }

        Map<String, TwinAssignment> twinPattern = twinPatterns.get(0);
        List<PartialSolutionResult> results =
                assignRoomsMultiple(buildingData, config, twinPattern, maxStaffPerFloor, relaxConfig, 1);

        for (PartialSolutionResult result : results) {
            if (result.shortage == 0) {
                LOGGER.info("完全解が見つかりました。");
                return result.assignments;
            }
        }

        // 最終手段ステップ（allowOneStaffThreeFloors=true）でも解なしの場合は部分解を返す
        if (relaxConfig.allowOneStaffThreeFloors && !results.isEmpty()) {
            results.sort((a, b) -> Integer.compare(a.shortage, b.shortage));
            PartialSolutionResult best = results.get(0);
            LOGGER.warning("=== 完全な解が見つかりませんでした（最終手段まで試行済み）===");
            LOGGER.warning(String.format("最良の部分解を使用: 割り振り済み=%d室 / 目標=%d室（未割当=%d室）",
                    best.totalAssignedRooms, best.totalTargetRooms, best.shortage));
            return best.assignments;
        }

        // 完全解なし → 次のステップへ
        return null;
    }

    /**
     * 緩和設定付きCPSAT最適化（複数解）
     * 完全解が見つかった場合のみリストを返す。見つからない場合はnullを返し呼び出し側が次のステップへ進む。
     * 最終手段ステップでも完全解なしの場合は部分解リストを返す。
     */
    private static List<List<AdaptiveRoomOptimizer.StaffAssignment>> optimizeWithRelaxationMultiple(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config,
            Map<String, AdaptiveRoomOptimizer.StaffAssignment> existingAssignments,
            RelaxationConfig relaxConfig,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig originalConfig,
            int maxSolutions,
            int maxStaffPerFloor) {

        LOGGER.info(String.format("=== CPSAT複数解最適化（%s, 1フロア最大%d人, 最大%d解）===",
                relaxConfig, maxStaffPerFloor, maxSolutions));

        // === 各建物・各階の部屋数をログ出力 ===
        LOGGER.info("=== 建物フロア別 部屋数一覧 ===");
        for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("  本館 %dF: ", floor.floorNumber));
            for (Map.Entry<String, Integer> e : floor.roomCounts.entrySet()) {
                if (e.getValue() > 0) sb.append(String.format("%s=%d ", e.getKey(), e.getValue()));
            }
            sb.append(String.format("(ECO=%d, 合計=%d)", floor.ecoRooms, floor.getTotalNormalRooms()));
            LOGGER.info(sb.toString());
        }
        for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.annexFloors) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("  別館 %dF: ", floor.floorNumber));
            for (Map.Entry<String, Integer> e : floor.roomCounts.entrySet()) {
                if (e.getValue() > 0) sb.append(String.format("%s=%d ", e.getKey(), e.getValue()));
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
        List<PartialSolutionResult> allResults =
                assignRoomsMultiple(buildingData, config, twinPattern, maxStaffPerFloor, relaxConfig, maxSolutions);

        // 完全解・ECO部分解・部分解を分離
        List<List<AdaptiveRoomOptimizer.StaffAssignment>> completeSolutions = new ArrayList<>();
        List<PartialSolutionResult> ecoPartialResults = new ArrayList<>();
        List<PartialSolutionResult> partialResults    = new ArrayList<>();

        for (PartialSolutionResult result : allResults) {
            if (result.shortage == 0 && result.ecoShortage == 0) {
                if (completeSolutions.size() < maxSolutions &&
                        !isDuplicateSolution(completeSolutions, result.assignments)) {
                    completeSolutions.add(result.assignments);
                    LOGGER.info(String.format("完全解 %d を発見", completeSolutions.size()));
                }
            } else if (result.shortage == 0) {
                ecoPartialResults.add(result);
            } else {
                partialResults.add(result);
            }
        }

        // 完全解が見つかった場合はそれを返す
        if (!completeSolutions.isEmpty()) {
            LOGGER.info(String.format("合計 %d 個の完全解を発見", completeSolutions.size()));
            return completeSolutions;
        }

        // 最終手段ステップ以外は null を返して次のステップへ
        if (!relaxConfig.allowOneStaffThreeFloors) {
            return null;
        }

        // 最終手段ステップでも完全解なし → ECO部分解、次いで部分解を返す
        if (!ecoPartialResults.isEmpty()) {
            LOGGER.warning("=== ECO完全解が見つかりませんでした。ECO部分解（シングルOK）を使用します ===");
            ecoPartialResults.sort((a, b) -> Integer.compare(a.ecoShortage, b.ecoShortage));
            List<List<AdaptiveRoomOptimizer.StaffAssignment>> results = new ArrayList<>();
            for (int i = 0; i < Math.min(maxSolutions, ecoPartialResults.size()); i++) {
                LOGGER.warning(String.format("  ECO部分解 %d: ECO shortage=%d", i + 1, ecoPartialResults.get(i).ecoShortage));
                results.add(ecoPartialResults.get(i).assignments);
            }
            return results;
        }

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
     *  解の重複チェック（簡易版：スタッフごとの担当フロアの組み合わせで判定）
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
     *  解のフロア割り当てパターンを文字列化
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
        // ユーザー設定のツイン数配分をそのまま使用（スタッフ間の移動パターンは廃止）
        // ツインのフロア配置はCP-SATソルバーが最適化する

        return patterns;
    }

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
            RelaxationConfig relaxConfig,
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
            // ★修正: ECO変数はスタッフのECO事前割り当て数に関わらず、
            // そのビルに通常室を持つ全スタッフが対象。CP-SATが自由に配分する。
            boolean hasMainWork = hasMainSingles || hasMainTwins || dist.mainEcoAssignedRooms > 0;
            boolean hasAnnexWork = hasAnnexSingles || hasAnnexTwins || dist.annexEcoAssignedRooms > 0;

            for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
                boolean isMain = floor.isMainBuilding;
                boolean needSingle = (hasMainSingles && isMain) || (hasAnnexSingles && !isMain);
                boolean needTwin = (hasMainTwins && isMain) || (hasAnnexTwins && !isMain);
                // ★修正: ECO室があるフロアで、そのビルで働くスタッフ全員にECO変数を作成
                boolean needEco = ((hasMainWork && isMain) || (hasAnnexWork && !isMain)) && floor.ecoRooms > 0;

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

        // === フロア制限（建物別）+ 緩和設定適用 ===
        // "1人だけ3フロア許可"用のextended変数（本館・別館それぞれ最大1人）
        List<BoolVar> extendedMainVars  = new ArrayList<>();
        List<BoolVar> extendedAnnexVars = new ArrayList<>();

        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            int maxMainFloors  = 2;
            int maxAnnexFloors = 2;

            // 業者/リライアンスはフロア制限なし
            AdaptiveRoomOptimizer.PointConstraint constraint = config.pointConstraints.get(staffName);
            boolean isVendor = false;
            if (constraint != null) {
                String type = constraint.constraintType;
                if (type.contains("業者") || type.contains("リライアンス")) {
                    maxMainFloors  = 99;
                    maxAnnexFloors = 99;
                    isVendor = true;
                }
            }

            // 大浴清掃スタッフのフロア制限
            boolean isBathStaff = staffInfo.bathCleaningType != AdaptiveRoomOptimizer.BathCleaningType.NONE;
            if (isBathStaff) {
                if (relaxConfig.releaseBathFloorLimit) {
                    // Step2以降: 大浴スタッフも最大2フロアまで許可
                    maxMainFloors  = Math.min(maxMainFloors, 2);
                    maxAnnexFloors = Math.min(maxAnnexFloors, 2);
                    LOGGER.info(String.format("大浴スタッフ %s: フロア制限緩和（最大2フロア）", staffName));
                } else {
                    // Step1: 大浴スタッフは1フロアのみ（業者設定を上書き）
                    maxMainFloors  = 1;
                    maxAnnexFloors = 1;
                }
            }

            // 本館＋別館の両方担当の場合は各1フロアに制限
            NormalRoomDistributionDialog.StaffDistribution dist =
                    config.roomDistribution != null ? config.roomDistribution.get(staffName) : null;
            if (dist != null) {
                int mainR  = dist.mainSingleAssignedRooms + dist.mainTwinAssignedRooms;
                int annexR = dist.annexSingleAssignedRooms + dist.annexTwinAssignedRooms;
                if (mainR > 0 && annexR > 0) {
                    maxMainFloors  = Math.min(maxMainFloors, 1);
                    maxAnnexFloors = Math.min(maxAnnexFloors, 1);
                }
            }

            // yVarsを本館・別館に仕分け
            List<BoolVar> mainYVarsList  = new ArrayList<>();
            List<BoolVar> annexYVarsList = new ArrayList<>();
            for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
                String yVarName = String.format("y_%s_%d", staffName, floor.floorNumber);
                if (!yVars.containsKey(yVarName)) continue;
                if (floor.isMainBuilding) mainYVarsList.add(yVars.get(yVarName));
                else                      annexYVarsList.add(yVars.get(yVarName));
            }

            // "1人だけ3フロア許可"の適用対象:
            //   業者・リライアンス以外 かつ 本館のmaxFloors==2（両方担当の1フロア制限スタッフは除外）
            boolean eligibleMain  = !isVendor && !isBathStaff && maxMainFloors  == 2;
            boolean eligibleAnnex = !isVendor && !isBathStaff && maxAnnexFloors == 2;

            if (relaxConfig.allowOneStaffThreeFloors && eligibleMain && !mainYVarsList.isEmpty()) {
                // このスタッフが「3フロア担当スタッフ」に選ばれた場合、maxFloors=3を許可
                BoolVar extMain = model.newBoolVar("ext_main_" + staffName);
                extendedMainVars.add(extMain);
                // sum(mainY) ≤ 2 + extMain  （extMain=1なら3フロアOK、0なら2フロアまで）
                model.addLessOrEqual(
                        LinearExpr.sum(mainYVarsList.toArray(new BoolVar[0])),
                        LinearExpr.newBuilder().add(2).addTerm(extMain, 1).build());
            } else if (!mainYVarsList.isEmpty()) {
                model.addLessOrEqual(
                        LinearExpr.sum(mainYVarsList.toArray(new BoolVar[0])), maxMainFloors);
            }

            if (relaxConfig.allowOneStaffThreeFloors && eligibleAnnex && !annexYVarsList.isEmpty()) {
                BoolVar extAnnex = model.newBoolVar("ext_annex_" + staffName);
                extendedAnnexVars.add(extAnnex);
                model.addLessOrEqual(
                        LinearExpr.sum(annexYVarsList.toArray(new BoolVar[0])),
                        LinearExpr.newBuilder().add(2).addTerm(extAnnex, 1).build());
            } else if (!annexYVarsList.isEmpty()) {
                model.addLessOrEqual(
                        LinearExpr.sum(annexYVarsList.toArray(new BoolVar[0])), maxAnnexFloors);
            }
        }

        // 本館・別館それぞれで「3フロア担当スタッフ」は最大1人に限定
        if (relaxConfig.allowOneStaffThreeFloors) {
            if (extendedMainVars.size() >= 2) {
                model.addLessOrEqual(LinearExpr.sum(extendedMainVars.toArray(new BoolVar[0])), 1);
                LOGGER.info("本館: 3フロア担当スタッフを最大1人に制限");
            }
            if (extendedAnnexVars.size() >= 2) {
                model.addLessOrEqual(LinearExpr.sum(extendedAnnexVars.toArray(new BoolVar[0])), 1);
                LOGGER.info("別館: 3フロア担当スタッフを最大1人に制限");
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

        // === 指定階制約: 各指定階で最低1部屋の通常清掃（シングル or ツイン）を取る ===
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            NormalRoomDistributionDialog.StaffDistribution dist =
                    config.roomDistribution != null ?
                            config.roomDistribution.get(staffName) : null;
            if (dist == null || dist.preferredFloors == null || dist.preferredFloors.isEmpty()) continue;

            LOGGER.info(String.format("指定階制約: %s → 指定階=%s（各階で通常清掃≥1）", staffName, dist.getPreferredFloorsText()));

            for (int preferredFloor : dist.preferredFloors) {
                // 指定階に対応するフロアを探す
                for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
                    int actualFloorNumber = !floor.isMainBuilding && floor.floorNumber > 20
                            ? floor.floorNumber - 20
                            : floor.floorNumber;

                    if (actualFloorNumber != preferredFloor) continue;

                    // このフロアの通常清掃変数（シングル＋ツイン）を集める
                    List<IntVar> normalVars = new ArrayList<>();
                    for (String roomType : floor.roomCounts.keySet()) {
                        if ((floor.isMainBuilding && isMainTwin(roomType)) ||
                                (!floor.isMainBuilding && isAnnexTwin(roomType))) continue;
                        String vn = String.format("x_%s_%d_%s", staffName, floor.floorNumber, roomType);
                        if (xVars.containsKey(vn)) normalVars.add(xVars.get(vn));
                    }
                    String tVarName = String.format("t_%s_%d", staffName, floor.floorNumber);
                    if (twinVars.containsKey(tVarName)) normalVars.add(twinVars.get(tVarName));

                    // 通常清掃の合計 ≥ 1 を強制
                    if (!normalVars.isEmpty()) {
                        model.addGreaterOrEqual(
                                LinearExpr.sum(normalVars.toArray(new IntVar[0])), 1);
                        LOGGER.info(String.format("  → %s: %s%dFで通常清掃≥1を強制",
                                staffName, floor.isMainBuilding ? "本館" : "別館", actualFloorNumber));
                    }
                }
            }
        }

        // === ECO目標制約（フロア単位・CP-SAT自由配分版） ===
        // ★修正: スタッフごとのECO事前割り当て数による上限・shortageを廃止。
        // ECOはフロアの供給制約（上のECO供給制約）の中でCP-SATが自由に配分する。
        // 目的関数では各フロアの未割当ECO室数を最小化する。
        List<IntVar> ecoShortageVars = new ArrayList<>();

        for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
            if (floor.ecoRooms <= 0) continue;

            // フロアの割り当て済みECO合計
            List<IntVar> floorEcoVarsList = new ArrayList<>();
            for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
                String eVarName = String.format("e_%s_%d", staffInfo.staff.name, floor.floorNumber);
                if (ecoVars.containsKey(eVarName)) floorEcoVarsList.add(ecoVars.get(eVarName));
            }
            if (floorEcoVarsList.isEmpty()) continue;

            // floorEcoSum = 各スタッフのECO合計
            IntVar floorEcoSum = model.newIntVar(0, floor.ecoRooms,
                    "floor_eco_sum_" + floor.floorNumber);
            model.addEquality(floorEcoSum,
                    LinearExpr.sum(floorEcoVarsList.toArray(new IntVar[0])));

            // 未割当ECO = supply - assigned（ソフト制約、目的関数で最小化）
            IntVar floorEcoShort = model.newIntVar(0, floor.ecoRooms,
                    "floor_eco_short_" + floor.floorNumber);
            model.addEquality(floorEcoShort, LinearExpr.newBuilder()
                    .add(floor.ecoRooms).addTerm(floorEcoSum, -1).build());
            ecoShortageVars.add(floorEcoShort);
        }

        // staffEcoShortageVars は使わないが後続コードとの互換性のため空Mapを定義
        Map<String, IntVar> staffEcoShortageVars = new HashMap<>();

        // === 大浴清掃スタッフの2フロア目使用ペナルティ ===
        // 大浴清掃スタッフがECOのために2フロア目を使う場合にペナルティを課す。
        // 優先順位: シングル全室(1000) > 1フロアに収める(10) > ECO全室(1)
        // → ECO1室を諦める(コスト1)より2フロア目を使う(コスト10)方が高コスト
        // → ECO2室以上を諦める(コスト2+)なら2フロア目を使う(コスト10未満)方が安い
        List<BoolVar> bathSecondFloorVars = new ArrayList<>();
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            if (staffInfo.bathCleaningType == AdaptiveRoomOptimizer.BathCleaningType.NONE) continue;

            String staffName = staffInfo.staff.name;

            // 本館: 大浴清掃スタッフが本館で2フロア目を使っているかを表すBoolVar
            List<BoolVar> mainYVarsList = new ArrayList<>();
            for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
                String yVarName = String.format("y_%s_%d", staffName, floor.floorNumber);
                if (yVars.containsKey(yVarName)) {
                    mainYVarsList.add(yVars.get(yVarName));
                }
            }
            if (mainYVarsList.size() >= 2) {
                // sum(y_main) >= 2 ならペナルティ変数=1
                // sum(y_main) - 1 >= 1 ↔ sum(y_main) >= 2
                BoolVar bathMain2nd = model.newBoolVar("bath_main_2nd_" + staffName);
                // sum(y) >= 2 ↔ sum(y) - 1 >= 1
                IntVar mainYSum = model.newIntVar(0, mainYVarsList.size(),
                        "main_y_sum_" + staffName);
                model.addEquality(mainYSum,
                        LinearExpr.sum(mainYVarsList.toArray(new BoolVar[0])));
                // bathMain2nd=1 ↔ mainYSum >= 2
                model.addGreaterOrEqual(mainYSum, 2).onlyEnforceIf(bathMain2nd);
                model.addLessOrEqual(mainYSum, 1).onlyEnforceIf(bathMain2nd.not());
                bathSecondFloorVars.add(bathMain2nd);
            }

            // 別館: 同様
            List<BoolVar> annexYVarsList = new ArrayList<>();
            for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.annexFloors) {
                String yVarName = String.format("y_%s_%d", staffName, floor.floorNumber);
                if (yVars.containsKey(yVarName)) {
                    annexYVarsList.add(yVars.get(yVarName));
                }
            }
            if (annexYVarsList.size() >= 2) {
                BoolVar bathAnnex2nd = model.newBoolVar("bath_annex_2nd_" + staffName);
                IntVar annexYSum = model.newIntVar(0, annexYVarsList.size(),
                        "annex_y_sum_" + staffName);
                model.addEquality(annexYSum,
                        LinearExpr.sum(annexYVarsList.toArray(new BoolVar[0])));
                model.addGreaterOrEqual(annexYSum, 2).onlyEnforceIf(bathAnnex2nd);
                model.addLessOrEqual(annexYSum, 1).onlyEnforceIf(bathAnnex2nd.not());
                bathSecondFloorVars.add(bathAnnex2nd);
            }
        }

        // === スコア均等化: 建物ごとに通常清掃スタッフ間の換算スコア差を最小化 ===
        // スコア = 通常室 × 5 + ECO室 × 1（ECO5室 ≒ 通常室1室の換算）
        // 対象①: 制限なし（非大浴場）スタッフ（本館・別館それぞれ）
        // 対象②: 大浴場スタッフ同士（本館固定のためまとめて均等化）
        // 均等化指標: グループ内スコアの「最大 - 最小」を最小化（range minimization）
        List<IntVar> scoreRangeVars = new ArrayList<>();

        // --- 大浴場スタッフ同士のスコア均等化（本館固定） ---
        // スコア式: 通常室×10 + ツイン換算値×10 + リネン庫×4 + ECO×2
        // （convertedTotalRoomsと同一ロジック、×10スケールで整数化）
        {
            List<IntVar> bathScoreVars = new ArrayList<>();
            for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
                String staffName = staffInfo.staff.name;
                if (staffInfo.bathCleaningType == AdaptiveRoomOptimizer.BathCleaningType.NONE) continue;

                NormalRoomDistributionDialog.StaffDistribution dist =
                        config.roomDistribution != null ? config.roomDistribution.get(staffName) : null;
                if (dist == null || dist.mainSingleAssignedRooms <= 0) continue;

                // 大浴場スタッフの本館ECO合計変数を収集
                List<IntVar> staffMainEcoList = new ArrayList<>();
                for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
                    String eVarName = String.format("e_%s_%d", staffName, floor.floorNumber);
                    if (ecoVars.containsKey(eVarName)) staffMainEcoList.add(ecoVars.get(eVarName));
                }

                // baseScore = 通常室×10 + ツイン換算×10 + リネン庫×4
                int baseScore = dist.mainSingleAssignedRooms * 10
                        + (int)(AdaptiveRoomOptimizer.calculateTwinConversion(dist.mainTwinAssignedRooms) * 10)
                        + staffInfo.linenClosetFloorCount * 4;
                int maxPossibleEco = buildingData.mainFloors.stream().mapToInt(f -> f.ecoRooms).sum();
                // ECO×2を動的に加算
                IntVar scoreVar = model.newIntVar(baseScore, baseScore + maxPossibleEco * 2,
                        "score_bath_" + staffName);
                if (staffMainEcoList.isEmpty()) {
                    model.addEquality(scoreVar, baseScore);
                } else {
                    LinearExprBuilder eb = LinearExpr.newBuilder().add(baseScore);
                    for (IntVar ev : staffMainEcoList) eb.addTerm(ev, 2);
                    model.addEquality(scoreVar, eb.build());
                }
                bathScoreVars.add(scoreVar);
                LOGGER.info(String.format("スコア均等化(大浴場): %s 通常室=%d ツイン=%d baseScore=%d",
                        staffName, dist.mainSingleAssignedRooms, dist.mainTwinAssignedRooms, baseScore));
            }
            if (bathScoreVars.size() >= 2) {
                IntVar minScore = model.newIntVar(0, 10000, "min_score_bath");
                IntVar maxScore = model.newIntVar(0, 10000, "max_score_bath");
                model.addMinEquality(minScore, bathScoreVars.toArray(new IntVar[0]));
                model.addMaxEquality(maxScore, bathScoreVars.toArray(new IntVar[0]));
                IntVar rangeVar = model.newIntVar(0, 10000, "range_bath");
                model.addEquality(rangeVar, LinearExpr.newBuilder()
                        .addTerm(maxScore, 1).addTerm(minScore, -1).build());
                scoreRangeVars.add(rangeVar);
                LOGGER.info("スコア均等化(大浴場): " + bathScoreVars.size() + "名対象, range最小化を目的関数に追加");
            }
        }

        // --- 本館スコア均等化（制限なし通常スタッフ） ---
        // スコア式: 通常室×10 + ツイン換算値×10 + リネン庫×4 + ECO×2
        {
            List<IntVar> mainScoreVars = new ArrayList<>();
            for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
                String staffName = staffInfo.staff.name;
                if (staffInfo.bathCleaningType != AdaptiveRoomOptimizer.BathCleaningType.NONE) continue;
                AdaptiveRoomOptimizer.PointConstraint pc = config.pointConstraints.get(staffName);
                if (pc != null && !"制限なし".equals(pc.constraintType)) continue;

                NormalRoomDistributionDialog.StaffDistribution dist =
                        config.roomDistribution != null ? config.roomDistribution.get(staffName) : null;
                if (dist == null || dist.mainSingleAssignedRooms <= 0) continue;

                // このスタッフの本館ECO合計変数を収集
                List<IntVar> staffMainEcoList = new ArrayList<>();
                for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
                    String eVarName = String.format("e_%s_%d", staffName, floor.floorNumber);
                    if (ecoVars.containsKey(eVarName)) staffMainEcoList.add(ecoVars.get(eVarName));
                }

                // baseScore = 通常室×10 + ツイン換算×10 + リネン庫×4
                int baseScore = dist.mainSingleAssignedRooms * 10
                        + (int)(AdaptiveRoomOptimizer.calculateTwinConversion(dist.mainTwinAssignedRooms) * 10)
                        + staffInfo.linenClosetFloorCount * 4;
                int maxPossibleEco = buildingData.mainFloors.stream().mapToInt(f -> f.ecoRooms).sum();
                IntVar scoreVar = model.newIntVar(baseScore, baseScore + maxPossibleEco * 2,
                        "score_main_" + staffName);
                if (staffMainEcoList.isEmpty()) {
                    model.addEquality(scoreVar, baseScore);
                } else {
                    LinearExprBuilder eb = LinearExpr.newBuilder().add(baseScore);
                    for (IntVar ev : staffMainEcoList) eb.addTerm(ev, 2);
                    model.addEquality(scoreVar, eb.build());
                }
                mainScoreVars.add(scoreVar);
                LOGGER.info(String.format("スコア均等化(本館): %s 通常室=%d ツイン=%d baseScore=%d",
                        staffName, dist.mainSingleAssignedRooms, dist.mainTwinAssignedRooms, baseScore));
            }
            if (mainScoreVars.size() >= 2) {
                IntVar minScore = model.newIntVar(0, 10000, "min_score_main");
                IntVar maxScore = model.newIntVar(0, 10000, "max_score_main");
                model.addMinEquality(minScore, mainScoreVars.toArray(new IntVar[0]));
                model.addMaxEquality(maxScore, mainScoreVars.toArray(new IntVar[0]));
                IntVar rangeVar = model.newIntVar(0, 10000, "range_main");
                model.addEquality(rangeVar, LinearExpr.newBuilder()
                        .addTerm(maxScore, 1).addTerm(minScore, -1).build());
                scoreRangeVars.add(rangeVar);
                LOGGER.info("スコア均等化(本館): " + mainScoreVars.size() + "名対象, range最小化を目的関数に追加");
            }
        }

        // --- 別館スコア均等化（制限なし通常スタッフ） ---
        // スコア式: 通常室×10 + ツイン換算値×10 + ECO×2
        {
            List<IntVar> annexScoreVars = new ArrayList<>();
            for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
                String staffName = staffInfo.staff.name;
                if (staffInfo.bathCleaningType != AdaptiveRoomOptimizer.BathCleaningType.NONE) continue;
                AdaptiveRoomOptimizer.PointConstraint pc = config.pointConstraints.get(staffName);
                if (pc != null && !"制限なし".equals(pc.constraintType)) continue;

                NormalRoomDistributionDialog.StaffDistribution dist =
                        config.roomDistribution != null ? config.roomDistribution.get(staffName) : null;
                if (dist == null || dist.annexSingleAssignedRooms <= 0) continue;

                List<IntVar> staffAnnexEcoList = new ArrayList<>();
                for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.annexFloors) {
                    String eVarName = String.format("e_%s_%d", staffName, floor.floorNumber);
                    if (ecoVars.containsKey(eVarName)) staffAnnexEcoList.add(ecoVars.get(eVarName));
                }

                // baseScore = 通常室×10 + ツイン換算×10
                int baseScore = dist.annexSingleAssignedRooms * 10
                        + (int)(AdaptiveRoomOptimizer.calculateTwinConversion(dist.annexTwinAssignedRooms) * 10);
                int maxPossibleEco = buildingData.annexFloors.stream().mapToInt(f -> f.ecoRooms).sum();
                IntVar scoreVar = model.newIntVar(baseScore, baseScore + maxPossibleEco * 2,
                        "score_annex_" + staffName);
                if (staffAnnexEcoList.isEmpty()) {
                    model.addEquality(scoreVar, baseScore);
                } else {
                    LinearExprBuilder eb = LinearExpr.newBuilder().add(baseScore);
                    for (IntVar ev : staffAnnexEcoList) eb.addTerm(ev, 2);
                    model.addEquality(scoreVar, eb.build());
                }
                annexScoreVars.add(scoreVar);
                LOGGER.info(String.format("スコア均等化(別館): %s 通常室=%d ツイン=%d baseScore=%d",
                        staffName, dist.annexSingleAssignedRooms, dist.annexTwinAssignedRooms, baseScore));
            }
            if (annexScoreVars.size() >= 2) {
                IntVar minScore = model.newIntVar(0, 10000, "min_score_annex");
                IntVar maxScore = model.newIntVar(0, 10000, "max_score_annex");
                model.addMinEquality(minScore, annexScoreVars.toArray(new IntVar[0]));
                model.addMaxEquality(maxScore, annexScoreVars.toArray(new IntVar[0]));
                IntVar rangeVar = model.newIntVar(0, 10000, "range_annex");
                model.addEquality(rangeVar, LinearExpr.newBuilder()
                        .addTerm(maxScore, 1).addTerm(minScore, -1).build());
                scoreRangeVars.add(rangeVar);
                LOGGER.info("スコア均等化(別館): " + annexScoreVars.size() + "名対象, range最小化を目的関数に追加");
            }
        }

        // === 目的関数: シングル優先（重み1000）+ 大浴2フロア目ペナルティ（重み10）+ ECO未割当（重み100）+ スコア均等化（重み1） ===
        // 優先順位: 通常室全室埋める > ECO全室埋める > 大浴1フロア維持 > スコア均等化
        LinearExprBuilder objectiveBuilder = LinearExpr.newBuilder();
        boolean hasObjective = false;
        for (IntVar sv : shortageVars) {
            objectiveBuilder.addTerm(sv, 1000);
            hasObjective = true;
        }
        for (BoolVar bv : bathSecondFloorVars) {
            objectiveBuilder.addTerm(bv, 10);
            hasObjective = true;
        }
        for (IntVar ev : ecoShortageVars) {
            objectiveBuilder.addTerm(ev, 100);  // 1→100: ECO全室埋めをスコア均等化より優先
            hasObjective = true;
        }
        for (IntVar rv : scoreRangeVars) {
            objectiveBuilder.addTerm(rv, 1);    // スコアrange最小化（ECO埋めより低優先）
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
                    staffInfo.staff, mainAssignments, annexAssignments, staffInfo.bathCleaningType, staffInfo.isLinenClosetCleaning, staffInfo.linenClosetFloorCount));
        }

        return result;
    }

    /**
     * 本館・別館分離用ヘルパー: 本館のみのBuildingDataを生成
     */
    private static AdaptiveRoomOptimizer.BuildingData buildingDataMainOnly(
            AdaptiveRoomOptimizer.BuildingData data) {
        int mainCount = data.mainFloors.stream()
                .mapToInt(AdaptiveRoomOptimizer.FloorInfo::getTotalNormalRooms).sum();
        return new AdaptiveRoomOptimizer.BuildingData(
                data.mainFloors, new ArrayList<>(), mainCount, 0);
    }

    /**
     * 本館・別館分離用ヘルパー: 別館のみのBuildingDataを生成
     */
    private static AdaptiveRoomOptimizer.BuildingData buildingDataAnnexOnly(
            AdaptiveRoomOptimizer.BuildingData data) {
        int annexCount = data.annexFloors.stream()
                .mapToInt(AdaptiveRoomOptimizer.FloorInfo::getTotalNormalRooms).sum();
        return new AdaptiveRoomOptimizer.BuildingData(
                new ArrayList<>(), data.annexFloors, 0, annexCount);
    }

    /**
     * 本館・別館分離用ヘルパー: 本館部屋を持つスタッフだけを含むconfigを生成
     * 各スタッフの別館フィールドは0にセット
     */
    private static AdaptiveRoomOptimizer.AdaptiveLoadConfig splitConfigForMain(
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        List<FileProcessor.Staff> staffList = new ArrayList<>();
        List<AdaptiveRoomOptimizer.ExtendedStaffInfo> extList = new ArrayList<>();
        Map<String, AdaptiveRoomOptimizer.BathCleaningType> bathMap = new HashMap<>();
        Map<String, NormalRoomDistributionDialog.StaffDistribution> distMap = new HashMap<>();

        for (AdaptiveRoomOptimizer.ExtendedStaffInfo info : config.extendedStaffInfo) {
            String name = info.staff.name;
            NormalRoomDistributionDialog.StaffDistribution dist =
                    config.roomDistribution != null ? config.roomDistribution.get(name) : null;
            if (dist == null) continue;

            // ★修正: ECO事前割り当てではなく通常室のみで本館担当かを判定
            int mainTotal = dist.mainSingleAssignedRooms + dist.mainTwinAssignedRooms;
            if (mainTotal == 0) continue; // 本館通常室なし → スキップ

            staffList.add(info.staff);
            extList.add(info);
            bathMap.put(name, info.bathCleaningType);

            // 別館フィールドをゼロにしたコピーを作成
            NormalRoomDistributionDialog.StaffDistribution mainDist =
                    new NormalRoomDistributionDialog.StaffDistribution(dist);
            mainDist.annexSingleAssignedRooms = 0;
            mainDist.annexTwinAssignedRooms = 0;
            mainDist.annexEcoAssignedRooms = 0;
            mainDist.annexAssignedRooms = 0;
            mainDist.assignedRooms = mainDist.mainAssignedRooms;
            distMap.put(name, mainDist);
        }

        return new AdaptiveRoomOptimizer.AdaptiveLoadConfig(
                staffList, extList, bathMap, config.pointConstraints, distMap);
    }

    /**
     * 本館・別館分離用ヘルパー: 別館部屋を持つスタッフだけを含むconfigを生成
     * 各スタッフの本館フィールドは0にセット
     */
    private static AdaptiveRoomOptimizer.AdaptiveLoadConfig splitConfigForAnnex(
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        List<FileProcessor.Staff> staffList = new ArrayList<>();
        List<AdaptiveRoomOptimizer.ExtendedStaffInfo> extList = new ArrayList<>();
        Map<String, AdaptiveRoomOptimizer.BathCleaningType> bathMap = new HashMap<>();
        Map<String, NormalRoomDistributionDialog.StaffDistribution> distMap = new HashMap<>();

        for (AdaptiveRoomOptimizer.ExtendedStaffInfo info : config.extendedStaffInfo) {
            String name = info.staff.name;
            NormalRoomDistributionDialog.StaffDistribution dist =
                    config.roomDistribution != null ? config.roomDistribution.get(name) : null;
            if (dist == null) continue;

            // ★修正: ECO事前割り当てではなく通常室のみで別館担当かを判定
            int annexTotal = dist.annexSingleAssignedRooms + dist.annexTwinAssignedRooms;
            if (annexTotal == 0) continue; // 別館通常室なし → スキップ

            staffList.add(info.staff);
            extList.add(info);
            bathMap.put(name, info.bathCleaningType);

            // 本館フィールドをゼロにしたコピーを作成
            NormalRoomDistributionDialog.StaffDistribution annexDist =
                    new NormalRoomDistributionDialog.StaffDistribution(dist);
            annexDist.mainSingleAssignedRooms = 0;
            annexDist.mainTwinAssignedRooms = 0;
            annexDist.mainEcoAssignedRooms = 0;
            annexDist.mainAssignedRooms = 0;
            annexDist.assignedRooms = annexDist.annexAssignedRooms;
            distMap.put(name, annexDist);
        }

        return new AdaptiveRoomOptimizer.AdaptiveLoadConfig(
                staffList, extList, bathMap, config.pointConstraints, distMap);
    }

    /**
     * 本館結果と別館結果をマージ
     * 両建物に部屋があるスタッフは mainBuildingAssignments と annexBuildingAssignments を結合する
     */
    /**
     * 組み合わせのスコアばらつきを計算
     * 大浴清掃・本館通常清掃・別館通常清掃の3グループ内で
     * convertedTotalRooms の max-min を合計した値を返す（小さいほど均等）
     * 故障者制限・業者制限スタッフは評価対象外
     */
    private static double calculateCombinationSpread(
            List<AdaptiveRoomOptimizer.StaffAssignment> assignments,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        List<Double> bathScores   = new ArrayList<>();
        List<Double> mainScores   = new ArrayList<>();
        List<Double> annexScores  = new ArrayList<>();

        for (AdaptiveRoomOptimizer.StaffAssignment sa : assignments) {
            String name = sa.staff.name;

            // 故障者制限・業者制限は除外
            AdaptiveRoomOptimizer.PointConstraint pc = config.pointConstraints.get(name);
            if (pc != null && !"制限なし".equals(pc.constraintType)) continue;

            double score = AdaptiveRoomOptimizer.calculateConvertedScore(sa);

            if (sa.bathCleaningType != AdaptiveRoomOptimizer.BathCleaningType.NONE) {
                bathScores.add(score);
            } else if (!sa.mainBuildingAssignments.isEmpty()) {
                mainScores.add(score);
            } else if (!sa.annexBuildingAssignments.isEmpty()) {
                annexScores.add(score);
            }
        }

        return groupSpread(bathScores) + groupSpread(mainScores) + groupSpread(annexScores);
    }

    /** グループ内のmax-min（1人以下なら0） */
    private static double groupSpread(List<Double> scores) {
        if (scores.size() < 2) return 0.0;
        double min = scores.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = scores.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        return max - min;
    }

    private static List<AdaptiveRoomOptimizer.StaffAssignment> mergeMainAnnexAssignments(
            List<AdaptiveRoomOptimizer.StaffAssignment> mainList,
            List<AdaptiveRoomOptimizer.StaffAssignment> annexList,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig originalConfig) {

        Map<String, AdaptiveRoomOptimizer.StaffAssignment> mainMap = new HashMap<>();
        for (AdaptiveRoomOptimizer.StaffAssignment sa : mainList) {
            mainMap.put(sa.staff.name, sa);
        }
        Map<String, AdaptiveRoomOptimizer.StaffAssignment> annexMap = new HashMap<>();
        for (AdaptiveRoomOptimizer.StaffAssignment sa : annexList) {
            annexMap.put(sa.staff.name, sa);
        }

        List<AdaptiveRoomOptimizer.StaffAssignment> result = new ArrayList<>();

        for (AdaptiveRoomOptimizer.ExtendedStaffInfo info : originalConfig.extendedStaffInfo) {
            String name = info.staff.name;
            AdaptiveRoomOptimizer.StaffAssignment mainSa = mainMap.get(name);
            AdaptiveRoomOptimizer.StaffAssignment annexSa = annexMap.get(name);

            if (mainSa != null && annexSa != null) {
                // 両建物に部屋があるスタッフ: main側の本館割当 + annex側の別館割当を結合
                Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> mergedMain =
                        new HashMap<>(mainSa.mainBuildingAssignments);
                Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> mergedAnnex =
                        new HashMap<>(annexSa.annexBuildingAssignments);
                result.add(new AdaptiveRoomOptimizer.StaffAssignment(
                        info.staff, mergedMain, mergedAnnex,
                        info.bathCleaningType, info.isLinenClosetCleaning, info.linenClosetFloorCount));
            } else if (mainSa != null) {
                result.add(mainSa);
            } else if (annexSa != null) {
                result.add(annexSa);
            } else {
                // 割当なし（空）
                result.add(new AdaptiveRoomOptimizer.StaffAssignment(
                        info.staff, new HashMap<>(), new HashMap<>(),
                        info.bathCleaningType, info.isLinenClosetCleaning, info.linenClosetFloorCount));
            }
        }

        LOGGER.info("=== 本館・別館マージ結果 ===");
        for (AdaptiveRoomOptimizer.StaffAssignment sa : result) {
            LOGGER.info(String.format("  %s: %d室", sa.staff.name, sa.getTotalRooms()));
        }
        return result;
    }

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

        // 本館・別館両方割り振りでも maxFloors = 2 のまま
        // （各建物で個別に1フロア制限がかかるため、合計2フロアとなる）
        // if (mainR > 0 && annexR > 0) maxFloors = 4; は削除

        return maxFloors;
    }
}