package org.example;

import com.google.ortools.sat.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * CP-SATソルバーを使用した部屋割り振り最適化
 * 処理フロー:
 * 0. 大浴清掃スタッフ: フロア単位ラウンドロビン（データ順序維持）
 * 1. ツイン割り振り（複数解を列挙）
 * 2. シングル等割り振り（複数解を列挙）
 * 3. ECO割り振り
 * 4. 未割当部屋を計算して返却
 */
public class RoomAssignmentCPSATOptimizer {
    private static final Logger LOGGER = Logger.getLogger(RoomAssignmentCPSATOptimizer.class.getName());

    // 進捗通知用リスナー（GUI表示用）
    private static java.util.function.Consumer<String> progressListener;
    private static java.util.logging.Handler guiHandler;

    /**
     * GUI進捗リスナーを設定する。
     * LOGGERの出力を自動的にGUIに転送する。
     */
    public static void setProgressListener(java.util.function.Consumer<String> listener) {
        // 既存のハンドラがあれば除去
        if (guiHandler != null) {
            LOGGER.removeHandler(guiHandler);
            guiHandler = null;
        }
        progressListener = listener;
        if (listener != null) {
            guiHandler = new java.util.logging.Handler() {
                @Override
                public void publish(java.util.logging.LogRecord record) {
                    if (progressListener != null && record.getMessage() != null) {
                        try {
                            String msg = record.getMessage();
                            // ツインパターン進捗とECOフロア制約の詳細は多すぎるのでフィルタ
                            if (msg.contains("ECOフロア使用制約追加")) {
                                return;
                            }
                            progressListener.accept(msg);
                        } catch (Exception e) {
                            // GUI通知失敗は無視
                        }
                    }
                }
                @Override public void flush() {}
                @Override public void close() throws SecurityException {}
            };
            guiHandler.setLevel(java.util.logging.Level.INFO);
            LOGGER.addHandler(guiHandler);
        }
    }

    // 探索する解の最大数
    private static final int MAX_SINGLE_SOLUTIONS = 5;

    // タイムアウトは無効（全パターン探索を保証）

    // ★追加: 複数解の最大数（ユーザーに表示する解の数）
    public static final int MAX_SOLUTIONS_TO_KEEP = 7;

    // フロアあたりの最大スタッフ数（初期値と上限）
    private static final int INITIAL_MAX_STAFF_PER_FLOOR = 2;
    private static final int MAX_STAFF_PER_FLOOR_LIMIT = 7;

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
     * ツイン割り振り結果
     */
    public static class FloorTwinAssignment {
        public final Map<Integer, Integer> mainFloorTwins = new HashMap<>();
        public final Map<Integer, Integer> annexFloorTwins = new HashMap<>();
        public final Set<Integer> usedFloors = new HashSet<>();

        public FloorTwinAssignment() {}
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
        public final Map<String, Integer> staffShortage;

        public PartialSolutionResult(List<AdaptiveRoomOptimizer.StaffAssignment> assignments,
                                     int totalAssigned, int totalTarget,
                                     Map<String, Integer> staffShortage) {
            this.assignments = assignments;
            this.totalAssignedRooms = totalAssigned;
            this.totalTargetRooms = totalTarget;
            this.shortage = totalTarget - totalAssigned;
            this.staffShortage = staffShortage;
        }
    }

    /**
     * ★追加: 部分解を含む最適化結果
     */
    private static class OptimizationResultWithPartials {
        final List<List<AdaptiveRoomOptimizer.StaffAssignment>> completeSolutions;
        final List<PartialSolutionResult> partialResults;
        final boolean timedOut;

        OptimizationResultWithPartials(
                List<List<AdaptiveRoomOptimizer.StaffAssignment>> completeSolutions,
                List<PartialSolutionResult> partialResults,
                boolean timedOut) {
            this.completeSolutions = completeSolutions;
            this.partialResults = partialResults;
            this.timedOut = timedOut;
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

            if (!assignments.isEmpty()) {
                assignEcoWithCPSAT(assignments, buildingData, config);
            }
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
            assignEcoWithCPSAT(assignments, originalBuildingData, config);
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

        List<OptimizationResultWithUnassigned> solutions = new ArrayList<>();

        // ★★★ 最初の解は必ず元のoptimizeWithUnassigned()で取得（元の動作と同じ結果を保証）★★★
        LOGGER.info("=== 解1: 元のoptimize()と同等の解を生成 ===");
        OptimizationResultWithUnassigned firstSolution = optimizeWithUnassigned(buildingData, config);
        solutions.add(firstSolution);

        // 追加の解が不要な場合、または元の解で完全解が得られた場合は終了
        if (MAX_SOLUTIONS_TO_KEEP <= 1) {
            return new MultiSolutionResult(solutions);
        }

        // ★★★ 解2以降: 異なるバリエーションを探索 ★★★
        LOGGER.info("=== 解2以降: 追加のバリエーションを探索 ===");

        // ステップ0: 大浴清掃スタッフへの事前割り振り
        BathStaffPreAssignmentResult bathPreAssignment = preAssignBathCleaningStaff(buildingData, config);

        List<List<AdaptiveRoomOptimizer.StaffAssignment>> additionalAssignmentsList = new ArrayList<>();

        if (bathPreAssignment.bathStaffAssignments.isEmpty()) {
            LOGGER.info("大浴清掃スタッフなし。通常のCPSAT複数解最適化を実行します。");
            // 追加解を探索（最大MAX_SOLUTIONS_TO_KEEP - 1個）
            additionalAssignmentsList = optimizeWithCPSATMultiple(buildingData, config, new HashMap<>(), config, MAX_SOLUTIONS_TO_KEEP - 1);

            // ECO割り振りを各解に適用
            for (List<AdaptiveRoomOptimizer.StaffAssignment> assignments : additionalAssignmentsList) {
                if (!assignments.isEmpty()) {
                    assignEcoWithCPSAT(assignments, buildingData, config);
                }
            }
        } else {
            LOGGER.info("=== 大浴清掃スタッフの事前割り振り完了 ===");

            AdaptiveRoomOptimizer.AdaptiveLoadConfig remainingConfig =
                    createRemainingConfig(config, bathPreAssignment);

            List<List<AdaptiveRoomOptimizer.StaffAssignment>> cpSatAssignmentsList =
                    optimizeWithCPSATMultiple(bathPreAssignment.remainingBuildingData,
                            remainingConfig, bathPreAssignment.bathStaffAssignments, config, MAX_SOLUTIONS_TO_KEEP - 1);

            // 各解に大浴清掃スタッフの割り振りをマージしてECO割り振りを適用
            // ★修正: 各解ごとに大浴清掃スタッフのディープコピーを作成して独立性を確保
            for (List<AdaptiveRoomOptimizer.StaffAssignment> cpSatAssignments : cpSatAssignmentsList) {
                // 毎回新しいディープコピーを作成
                Map<String, AdaptiveRoomOptimizer.StaffAssignment> bathCopy = new HashMap<>();
                for (Map.Entry<String, AdaptiveRoomOptimizer.StaffAssignment> entry :
                        bathPreAssignment.bathStaffAssignments.entrySet()) {
                    bathCopy.put(entry.getKey(), entry.getValue().deepCopy());
                }

                List<AdaptiveRoomOptimizer.StaffAssignment> merged =
                        mergeAssignments(bathCopy, cpSatAssignments);
                assignEcoWithCPSAT(merged, originalBuildingData, config);
                additionalAssignmentsList.add(merged);
            }
        }

        // 追加解を評価して重複を除去
        String firstSolutionPattern = buildFloorPattern(firstSolution.assignments);

        for (List<AdaptiveRoomOptimizer.StaffAssignment> assignments : additionalAssignmentsList) {
            // 最初の解と重複していないかチェック
            String pattern = buildFloorPattern(assignments);
            if (!pattern.equals(firstSolutionPattern)) {
                // 既存の追加解とも重複していないかチェック
                boolean isDuplicate = false;
                for (int i = 1; i < solutions.size(); i++) {
                    String existingPattern = buildFloorPattern(solutions.get(i).assignments);
                    if (pattern.equals(existingPattern)) {
                        isDuplicate = true;
                        break;
                    }
                }
                if (!isDuplicate) {
                    UnassignedRooms unassignedRooms = calculateUnassignedRooms(originalBuildingData, assignments);
                    solutions.add(new OptimizationResultWithUnassigned(assignments, unassignedRooms));
                    LOGGER.info(String.format("追加解 %d を採用", solutions.size()));

                    if (solutions.size() >= MAX_SOLUTIONS_TO_KEEP) {
                        break;
                    }
                }
            }
        }

        LOGGER.info(String.format("=== 複数解最適化完了: %d個の解を生成 ===", solutions.size()));

        return new MultiSolutionResult(solutions);
    }

    /**
     * CPSAT複数解最適化（フロアあたりスタッフ数制限を段階的に緩和）
     * タイムアウト機能と部分解早期返却を追加
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

        // タイムアウトなし（全パターン探索）
        long startTime = System.currentTimeMillis();

        // ★追加: 全体で最良の部分解を管理
        List<PartialSolutionResult> globalPartialResults = new ArrayList<>();

        LOGGER.info("=== フロアあたり最大2人制限でCPSAT複数解最適化を開始（タイムアウトなし）===");

        // フロアあたりのスタッフ数制限を段階的に緩和
        for (int maxStaffPerFloor = INITIAL_MAX_STAFF_PER_FLOOR;
             maxStaffPerFloor <= MAX_STAFF_PER_FLOOR_LIMIT;
             maxStaffPerFloor++) {

            long elapsed = System.currentTimeMillis() - startTime;
            LOGGER.info(String.format("経過時間: %d秒", elapsed / 1000));

            // 部分解も収集するバージョンを呼び出す
            OptimizationResultWithPartials result = optimizeWithStaffPerFloorLimitMultipleWithPartials(
                    buildingData, config, existingAssignments,
                    maxStaffPerFloor, originalConfig, maxSolutions,
                    startTime);

            // 部分解を全体リストに追加
            if (result.partialResults != null) {
                globalPartialResults.addAll(result.partialResults);
            }

            // 完全解が見つかった場合はそれを返す
            if (result.completeSolutions != null && !result.completeSolutions.isEmpty()) {
                LOGGER.info(String.format("フロアあたり最大%d人制限で%d個の解が見つかりました。",
                        maxStaffPerFloor, result.completeSolutions.size()));
                return result.completeSolutions;
            }

            if (maxStaffPerFloor < MAX_STAFF_PER_FLOOR_LIMIT) {
                LOGGER.warning(String.format("=== フロアあたり%d人制限では解が見つかりませんでした。%d人制限で再試行します ===",
                        maxStaffPerFloor, maxStaffPerFloor + 1));
            }
        }

        // ★追加: 完全解がない場合は部分解から最良のものを返す
        if (!globalPartialResults.isEmpty()) {
            LOGGER.warning("=== 完全な解が見つかりませんでした。最良の部分解を使用します ===");

            // 未割当が少ない順にソート
            globalPartialResults.sort((a, b) -> Integer.compare(a.shortage, b.shortage));

            PartialSolutionResult best = globalPartialResults.get(0);
            LOGGER.warning(String.format("最良の部分解: 未割当=%d室（割り振り済み: %d室 / 目標: %d室）",
                    best.shortage, best.totalAssignedRooms, best.totalTargetRooms));

            List<List<AdaptiveRoomOptimizer.StaffAssignment>> results = new ArrayList<>();
            for (int i = 0; i < Math.min(maxSolutions, globalPartialResults.size()); i++) {
                results.add(globalPartialResults.get(i).assignments);
            }
            return results;
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

    /**
     * CPSAT最適化（フロアあたりスタッフ数制限を段階的に緩和）
     * ★変更: RuntimeExceptionを削除
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

        // タイムアウトなし（全パターン探索）
        long startTime = System.currentTimeMillis();

        // ★追加: 全体で最良の部分解を管理
        PartialSolutionResult globalBestPartial = null;

        LOGGER.info("=== フロアあたり最大2人制限でCPSAT最適化を開始（タイムアウトなし）===");

        // フロアあたりのスタッフ数制限を段階的に緩和
        for (int maxStaffPerFloor = INITIAL_MAX_STAFF_PER_FLOOR;
             maxStaffPerFloor <= MAX_STAFF_PER_FLOOR_LIMIT;
             maxStaffPerFloor++) {

            long elapsed = System.currentTimeMillis() - startTime;
            LOGGER.info(String.format("経過時間: %d秒", elapsed / 1000));

            // タイムアウト情報を渡す（タイムアウトなし）
            OptimizeWithLimitResult result = optimizeWithStaffPerFloorLimitWithTimeout(
                    buildingData, config, existingAssignments, maxStaffPerFloor, originalConfig,
                    startTime);

            // 部分解を更新
            if (result.bestPartial != null) {
                if (globalBestPartial == null || result.bestPartial.shortage < globalBestPartial.shortage) {
                    globalBestPartial = result.bestPartial;
                }
            }

            if (result.assignments != null) {
                LOGGER.info(String.format("フロアあたり最大%d人制限で解が見つかりました。", maxStaffPerFloor));
                return result.assignments;
            }

            if (maxStaffPerFloor < MAX_STAFF_PER_FLOOR_LIMIT) {
                LOGGER.warning(String.format("=== フロアあたり%d人制限では解が見つかりませんでした。%d人制限で再試行します ===",
                        maxStaffPerFloor, maxStaffPerFloor + 1));
            }
        }

        // ★追加: 完全解がない場合は部分解を返す
        if (globalBestPartial != null) {
            LOGGER.warning("=== 完全な解が見つかりませんでした。最良の部分解を使用します ===");
            LOGGER.warning(String.format("最良の部分解: 未割当=%d室（割り振り済み: %d室 / 目標: %d室）",
                    globalBestPartial.shortage, globalBestPartial.totalAssignedRooms, globalBestPartial.totalTargetRooms));
            return globalBestPartial.assignments;
        }

        LOGGER.severe(String.format("フロアあたり%d人制限でも解が見つかりませんでした。", MAX_STAFF_PER_FLOOR_LIMIT));
        LOGGER.warning("通常清掃部屋割り振り設定を見直してください。未割当部屋が発生します。");
        return null;
    }

    /**
     * ★追加: optimizeWithStaffPerFloorLimitの結果クラス
     */
    private static class OptimizeWithLimitResult {
        final List<AdaptiveRoomOptimizer.StaffAssignment> assignments;
        final PartialSolutionResult bestPartial;
        final boolean timedOut;

        OptimizeWithLimitResult(List<AdaptiveRoomOptimizer.StaffAssignment> assignments,
                                PartialSolutionResult bestPartial, boolean timedOut) {
            this.assignments = assignments;
            this.bestPartial = bestPartial;
            this.timedOut = timedOut;
        }
    }

    /**
     * ★変更: タイムアウト解除版のoptimizeWithStaffPerFloorLimit
     */
    private static OptimizeWithLimitResult optimizeWithStaffPerFloorLimitWithTimeout(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config,
            Map<String, AdaptiveRoomOptimizer.StaffAssignment> existingAssignments,
            int maxStaffPerFloor,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig originalConfig,
            long startTime) {

        LOGGER.info(String.format("=== 残りスタッフのCPSAT最適化（フロアあたり最大%d人）===", maxStaffPerFloor));

        List<Map<String, TwinAssignment>> twinPatterns = generateTwinPatterns(buildingData, config);
        LOGGER.info("ツインパターン数: " + twinPatterns.size());

        if (twinPatterns.isEmpty()) {
            LOGGER.warning("ツインパターンが生成されませんでした。空の結果を返します。");
            return new OptimizeWithLimitResult(new ArrayList<>(), null, false);
        }

        // 最良の部分解を保持
        PartialSolutionResult bestPartialResult = null;
        int bestPartialPatternIndex = -1;

        for (int i = 0; i < twinPatterns.size(); i++) {
            long elapsed = System.currentTimeMillis() - startTime;

            Map<String, TwinAssignment> pattern = twinPatterns.get(i);

            LOGGER.info(String.format("=== ツインパターン %d/%d を試行中（経過%d秒）===",
                    i + 1, twinPatterns.size(), elapsed / 1000));

            Map<String, FloorTwinAssignment> twinResult =
                    assignTwinsRoundRobin(buildingData, config, pattern, maxStaffPerFloor);
            if (twinResult == null) {
                continue;
            }

            // シングル割り振り（CP-SAT）
            List<PartialSolutionResult> singleResults =
                    assignSinglesMultiple(buildingData, config, twinResult, maxStaffPerFloor);

            for (PartialSolutionResult result : singleResults) {
                if (result.shortage == 0) {
                    // 完全解が見つかった
                    LOGGER.info("完全解が見つかりました。");
                    return new OptimizeWithLimitResult(result.assignments, bestPartialResult, false);
                } else {
                    // 部分解を保持
                    if (bestPartialResult == null || result.shortage < bestPartialResult.shortage) {
                        bestPartialResult = result;
                        bestPartialPatternIndex = i + 1;
                        LOGGER.info(String.format("より良い部分解を発見: 未割当=%d室（パターン%d）",
                                result.shortage, bestPartialPatternIndex));
                    }
                }
            }
        }

        // 完全解がない場合
        return new OptimizeWithLimitResult(null, bestPartialResult, false);
    }

    /**
     * フロアあたりのスタッフ数制限付きCPSAT最適化
     * ★変更: 完全解が見つかれば即返す、部分解は未割当最小を選択、均等性評価を削除
     */
    private static List<AdaptiveRoomOptimizer.StaffAssignment> optimizeWithStaffPerFloorLimit(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config,
            Map<String, AdaptiveRoomOptimizer.StaffAssignment> existingAssignments,
            int maxStaffPerFloor,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig originalConfig) {

        LOGGER.info(String.format("=== 残りスタッフのCPSAT最適化（フロアあたり最大%d人）===", maxStaffPerFloor));

        List<Map<String, TwinAssignment>> twinPatterns = generateTwinPatterns(buildingData, config);
        LOGGER.info("ツインパターン数: " + twinPatterns.size());

        if (twinPatterns.isEmpty()) {
            LOGGER.warning("ツインパターンが生成されませんでした。空の結果を返します。");
            return new ArrayList<>();
        }

        // 最良の部分解を保持（未割当が最小のもの）
        PartialSolutionResult bestPartialResult = null;
        int bestPartialPatternIndex = -1;

        for (int i = 0; i < twinPatterns.size(); i++) {
            Map<String, TwinAssignment> pattern = twinPatterns.get(i);

            LOGGER.info("=== ツインパターン " + (i + 1) + "/" + twinPatterns.size() + " を試行中 ===");

            Map<String, FloorTwinAssignment> twinResult =
                    assignTwinsRoundRobin(buildingData, config, pattern, maxStaffPerFloor);
            if (twinResult == null) {
                continue;
            }

            // シングル割り振り（CP-SAT）
            List<PartialSolutionResult> singleResults =
                    assignSinglesMultiple(buildingData, config, twinResult, maxStaffPerFloor);

            for (PartialSolutionResult result : singleResults) {
                if (result.shortage == 0) {
                    // ★変更: 完全解が見つかった → 即座に返す
                    LOGGER.info("完全解が見つかりました。");
                    return result.assignments;
                } else {
                    // ★変更: 部分解は未割当(shortage)が最小のものを保持
                    if (bestPartialResult == null || result.shortage < bestPartialResult.shortage) {
                        bestPartialResult = result;
                        bestPartialPatternIndex = i + 1;
                        LOGGER.info(String.format("より良い部分解を発見: 未割当=%d室（パターン%d）",
                                result.shortage, bestPartialPatternIndex));
                    }
                }
            }
        }

        // 完全解がない場合：最大制限に達していなければnullを返して次の制限で再試行
        if (maxStaffPerFloor < MAX_STAFF_PER_FLOOR_LIMIT) {
            return null;
        }

        // 最大制限でも完全解がない場合は部分解を使用
        if (bestPartialResult != null) {
            LOGGER.warning("=== 完全な解が見つかりませんでした ===");
            LOGGER.warning("最良の部分解（パターン " + bestPartialPatternIndex + "）を使用します");
            LOGGER.warning(String.format("割り振り済み: %d室 / 目標: %d室（未割当: %d室）",
                    bestPartialResult.totalAssignedRooms, bestPartialResult.totalTargetRooms, bestPartialResult.shortage));

            return bestPartialResult.assignments;
        }

        return null;
    }

    /**
     * ★追加: 複数解を返すフロアあたりのスタッフ数制限付きCPSAT最適化
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

        List<Map<String, TwinAssignment>> twinPatterns = generateTwinPatterns(buildingData, config);
        LOGGER.info("ツインパターン数: " + twinPatterns.size());

        if (twinPatterns.isEmpty()) {
            LOGGER.warning("ツインパターンが生成されませんでした。空の結果を返します。");
            return new ArrayList<>();
        }

        // 完全解を収集
        List<List<AdaptiveRoomOptimizer.StaffAssignment>> completeSolutions = new ArrayList<>();
        // 部分解も保持（完全解が足りない場合に使用）
        List<PartialSolutionResult> partialResults = new ArrayList<>();

        for (int i = 0; i < twinPatterns.size() && completeSolutions.size() < maxSolutions; i++) {
            Map<String, TwinAssignment> pattern = twinPatterns.get(i);

            LOGGER.info("=== ツインパターン " + (i + 1) + "/" + twinPatterns.size() + " を試行中（現在" + completeSolutions.size() + "解）===");

            Map<String, FloorTwinAssignment> twinResult =
                    assignTwinsRoundRobin(buildingData, config, pattern, maxStaffPerFloor);
            if (twinResult == null) {
                continue;
            }

            // シングル割り振り（CP-SAT）
            List<PartialSolutionResult> singleResults =
                    assignSinglesMultiple(buildingData, config, twinResult, maxStaffPerFloor);

            for (PartialSolutionResult result : singleResults) {
                if (result.shortage == 0) {
                    // 完全解を収集
                    if (completeSolutions.size() < maxSolutions) {
                        // 重複チェック（簡易的に総部屋数の分布で判定）
                        if (!isDuplicateSolution(completeSolutions, result.assignments)) {
                            completeSolutions.add(result.assignments);
                            LOGGER.info(String.format("完全解 %d を発見（パターン%d）",
                                    completeSolutions.size(), i + 1));
                        }
                    }
                } else {
                    // 部分解を保持
                    partialResults.add(result);
                }
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

            // 未割当が少ない順にソート
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
     * ★追加: 部分解も返すバージョンのフロア制限付き最適化
     */
    private static OptimizationResultWithPartials optimizeWithStaffPerFloorLimitMultipleWithPartials(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config,
            Map<String, AdaptiveRoomOptimizer.StaffAssignment> existingAssignments,
            int maxStaffPerFloor,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig originalConfig,
            int maxSolutions,
            long startTime) {

        LOGGER.info(String.format("=== 残りスタッフのCPSAT複数解最適化（フロアあたり最大%d人、最大%d解）===",
                maxStaffPerFloor, maxSolutions));

        List<Map<String, TwinAssignment>> twinPatterns = generateTwinPatterns(buildingData, config);
        LOGGER.info("ツインパターン数: " + twinPatterns.size());

        if (twinPatterns.isEmpty()) {
            LOGGER.warning("ツインパターンが生成されませんでした。空の結果を返します。");
            return new OptimizationResultWithPartials(new ArrayList<>(), new ArrayList<>(), false);
        }

        // 完全解を収集
        List<List<AdaptiveRoomOptimizer.StaffAssignment>> completeSolutions = new ArrayList<>();
        // 部分解も保持
        List<PartialSolutionResult> partialResults = new ArrayList<>();

        for (int i = 0; i < twinPatterns.size() && completeSolutions.size() < maxSolutions; i++) {
            long elapsed = System.currentTimeMillis() - startTime;

            Map<String, TwinAssignment> pattern = twinPatterns.get(i);

            LOGGER.info(String.format("=== ツインパターン %d/%d を試行中（現在%d解、経過%d秒）===",
                    i + 1, twinPatterns.size(), completeSolutions.size(), elapsed / 1000));

            Map<String, FloorTwinAssignment> twinResult =
                    assignTwinsRoundRobin(buildingData, config, pattern, maxStaffPerFloor);
            if (twinResult == null) {
                continue;
            }

            // シングル割り振り（CP-SAT）
            List<PartialSolutionResult> singleResults =
                    assignSinglesMultiple(buildingData, config, twinResult, maxStaffPerFloor);

            for (PartialSolutionResult result : singleResults) {
                if (result.shortage == 0) {
                    // 完全解を収集
                    if (completeSolutions.size() < maxSolutions) {
                        if (!isDuplicateSolution(completeSolutions, result.assignments)) {
                            completeSolutions.add(result.assignments);
                            LOGGER.info(String.format("完全解 %d を発見（パターン%d）",
                                    completeSolutions.size(), i + 1));
                        }
                    }
                } else {
                    // 部分解を保持（最良の10個程度に制限）
                    partialResults.add(result);
                    if (partialResults.size() > 20) {
                        // 未割当が多いものを削除
                        partialResults.sort((a, b) -> Integer.compare(a.shortage, b.shortage));
                        partialResults = new ArrayList<>(partialResults.subList(0, 10));
                    }

                    // 最良の部分解が更新されたらログ出力
                    if (partialResults.size() == 1 ||
                            result.shortage < partialResults.stream().mapToInt(p -> p.shortage).min().orElse(Integer.MAX_VALUE)) {
                        LOGGER.info(String.format("より良い部分解を発見: 未割当=%d室（パターン%d）",
                                result.shortage, i + 1));
                    }
                }
            }
        }

        // 完全解が見つかった場合
        if (!completeSolutions.isEmpty()) {
            LOGGER.info(String.format("合計 %d 個の完全解を発見", completeSolutions.size()));
            return new OptimizationResultWithPartials(completeSolutions, partialResults, false);
        }

        // 完全解がない場合
        return new OptimizationResultWithPartials(null, partialResults, false);
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
        patterns.addAll(generateAdjustedTwinPatterns(basePattern, config));

        return patterns;
    }

    private static List<Map<String, TwinAssignment>> generateAdjustedTwinPatterns(
            Map<String, TwinAssignment> basePattern,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        List<Map<String, TwinAssignment>> adjusted = new ArrayList<>();
        List<String> staffNames = new ArrayList<>(basePattern.keySet());

        for (int i = 0; i < staffNames.size(); i++) {
            for (int j = i + 1; j < staffNames.size(); j++) {
                String s1 = staffNames.get(i), s2 = staffNames.get(j);
                TwinAssignment ta1 = basePattern.get(s1), ta2 = basePattern.get(s2);

                if (ta1.mainTwinRooms > 0) {
                    Map<String, TwinAssignment> p = deepCopyPattern(basePattern);
                    p.get(s1).mainTwinRooms--; p.get(s2).mainTwinRooms++;
                    adjusted.add(p);
                }
                if (ta2.mainTwinRooms > 0) {
                    Map<String, TwinAssignment> p = deepCopyPattern(basePattern);
                    p.get(s2).mainTwinRooms--; p.get(s1).mainTwinRooms++;
                    adjusted.add(p);
                }
                if (ta1.annexTwinRooms > 0) {
                    Map<String, TwinAssignment> p = deepCopyPattern(basePattern);
                    p.get(s1).annexTwinRooms--; p.get(s2).annexTwinRooms++;
                    adjusted.add(p);
                }
                if (ta2.annexTwinRooms > 0) {
                    Map<String, TwinAssignment> p = deepCopyPattern(basePattern);
                    p.get(s2).annexTwinRooms--; p.get(s1).annexTwinRooms++;
                    adjusted.add(p);
                }
            }
        }
        return adjusted;
    }

    private static Map<String, TwinAssignment> deepCopyPattern(Map<String, TwinAssignment> src) {
        Map<String, TwinAssignment> copy = new HashMap<>();
        for (Map.Entry<String, TwinAssignment> e : src.entrySet()) {
            copy.put(e.getKey(), new TwinAssignment(e.getValue().mainTwinRooms, e.getValue().annexTwinRooms));
        }
        return copy;
    }

    // ================================================================
    // ツイン割り振り（ラウンドロビン方式）
    // ================================================================

    private static Map<String, FloorTwinAssignment> assignTwinsRoundRobin(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config,
            Map<String, TwinAssignment> twinPattern,
            int maxStaffPerFloor) {

        // 各フロアのツイン数を集計
        Map<Integer, Integer> mainFloorTwins = new LinkedHashMap<>();
        Map<Integer, Integer> annexFloorTwins = new LinkedHashMap<>();

        for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
            int twins = floor.roomCounts.entrySet().stream()
                    .filter(e -> isMainTwin(e.getKey()))
                    .mapToInt(Map.Entry::getValue)
                    .sum();
            if (twins > 0) {
                mainFloorTwins.put(floor.floorNumber, twins);
            }
        }

        for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.annexFloors) {
            int twins = floor.roomCounts.entrySet().stream()
                    .filter(e -> isAnnexTwin(e.getKey()))
                    .mapToInt(Map.Entry::getValue)
                    .sum();
            if (twins > 0) {
                annexFloorTwins.put(floor.floorNumber, twins);
            }
        }

        // 結果格納用
        Map<String, FloorTwinAssignment> result = new HashMap<>();

        // 各フロアの残りツイン数
        Map<Integer, Integer> remainingMainTwins = new HashMap<>(mainFloorTwins);
        Map<Integer, Integer> remainingAnnexTwins = new HashMap<>(annexFloorTwins);

        // 各スタッフの残り割り当て数
        Map<String, Integer> remainingMainTarget = new HashMap<>();
        Map<String, Integer> remainingAnnexTarget = new HashMap<>();

        // ツインを担当するスタッフリスト
        List<String> mainTwinStaff = new ArrayList<>();
        List<String> annexTwinStaff = new ArrayList<>();

        // ★修正: 元の設定値を取得し、超過分を再配分する
        Map<String, Integer> originalMainTwin = new HashMap<>();
        Map<String, Integer> originalAnnexTwin = new HashMap<>();

        for (Map.Entry<String, TwinAssignment> entry : twinPattern.entrySet()) {
            String staffName = entry.getKey();
            result.put(staffName, new FloorTwinAssignment());

            NormalRoomDistributionDialog.StaffDistribution dist =
                    config.roomDistribution != null ? config.roomDistribution.get(staffName) : null;
            TwinAssignment ta = entry.getValue();

            // 元の設定値を保存
            originalMainTwin.put(staffName, dist != null ? dist.mainTwinAssignedRooms : ta.mainTwinRooms);
            originalAnnexTwin.put(staffName, dist != null ? dist.annexTwinAssignedRooms : ta.annexTwinRooms);
        }

        // 本館ツイン: 設定値を超える分を集計し、設定値に制限
        int excessMain = 0;
        for (Map.Entry<String, TwinAssignment> entry : twinPattern.entrySet()) {
            String staffName = entry.getKey();
            TwinAssignment ta = entry.getValue();
            int original = originalMainTwin.get(staffName);

            if (ta.mainTwinRooms > original) {
                // 超過分を集計
                excessMain += ta.mainTwinRooms - original;
                if (original > 0) {
                    remainingMainTarget.put(staffName, original);
                    mainTwinStaff.add(staffName);
                }
            } else if (ta.mainTwinRooms > 0) {
                remainingMainTarget.put(staffName, ta.mainTwinRooms);
                mainTwinStaff.add(staffName);
            }
        }

        // 超過分を、設定値より少ないスタッフに再配分
        if (excessMain > 0) {
            for (String staffName : twinPattern.keySet()) {
                if (excessMain <= 0) break;
                int current = remainingMainTarget.getOrDefault(staffName, 0);
                int original = originalMainTwin.get(staffName);
                if (current < original) {
                    int canAdd = original - current;
                    int toAdd = Math.min(canAdd, excessMain);
                    remainingMainTarget.put(staffName, current + toAdd);
                    excessMain -= toAdd;
                    if (current == 0 && toAdd > 0 && !mainTwinStaff.contains(staffName)) {
                        mainTwinStaff.add(staffName);
                    }
                }
            }
            if (excessMain > 0) {
                LOGGER.fine(String.format("本館ツイン超過分%d室を再配分できませんでした", excessMain));
            }
        }

        // 別館ツイン: 設定値を超える分を集計し、設定値に制限
        int excessAnnex = 0;
        for (Map.Entry<String, TwinAssignment> entry : twinPattern.entrySet()) {
            String staffName = entry.getKey();
            TwinAssignment ta = entry.getValue();
            int original = originalAnnexTwin.get(staffName);

            if (ta.annexTwinRooms > original) {
                // 超過分を集計
                excessAnnex += ta.annexTwinRooms - original;
                if (original > 0) {
                    remainingAnnexTarget.put(staffName, original);
                    annexTwinStaff.add(staffName);
                }
            } else if (ta.annexTwinRooms > 0) {
                remainingAnnexTarget.put(staffName, ta.annexTwinRooms);
                annexTwinStaff.add(staffName);
            }
        }

        // 超過分を、設定値より少ないスタッフに再配分
        if (excessAnnex > 0) {
            for (String staffName : twinPattern.keySet()) {
                if (excessAnnex <= 0) break;
                int current = remainingAnnexTarget.getOrDefault(staffName, 0);
                int original = originalAnnexTwin.get(staffName);
                if (current < original) {
                    int canAdd = original - current;
                    int toAdd = Math.min(canAdd, excessAnnex);
                    remainingAnnexTarget.put(staffName, current + toAdd);
                    excessAnnex -= toAdd;
                    if (current == 0 && toAdd > 0 && !annexTwinStaff.contains(staffName)) {
                        annexTwinStaff.add(staffName);
                    }
                }
            }
            if (excessAnnex > 0) {
                LOGGER.fine(String.format("別館ツイン超過分%d室を再配分できませんでした", excessAnnex));
            }
        }

        // 本館ツイン割り振り（ラウンドロビン）
        if (!mainTwinStaff.isEmpty()) {
            int staffIndex = 0;
            List<Integer> floors = new ArrayList<>(mainFloorTwins.keySet());
            Collections.sort(floors);

            for (Integer floorNum : floors) {
                int twinsOnFloor = remainingMainTwins.getOrDefault(floorNum, 0);

                while (twinsOnFloor > 0) {
                    int startIndex = staffIndex;
                    boolean found = false;

                    do {
                        String staffName = mainTwinStaff.get(staffIndex);
                        int remaining = remainingMainTarget.getOrDefault(staffName, 0);

                        if (remaining > 0) {
                            FloorTwinAssignment fta = result.get(staffName);
                            int maxFloors = getMaxFloors(staffName, config);

                            boolean alreadyOnFloor = fta.mainFloorTwins.containsKey(floorNum);
                            boolean canAddFloor = fta.usedFloors.size() < maxFloors;

                            if (alreadyOnFloor || canAddFloor) {
                                // ★修正: 1回で1部屋だけ割り当て（フロア内で複数スタッフに分散）
                                int toAssign = 1;

                                int currentAssigned = fta.mainFloorTwins.getOrDefault(floorNum, 0);
                                fta.mainFloorTwins.put(floorNum, currentAssigned + toAssign);
                                fta.usedFloors.add(floorNum);

                                remainingMainTarget.put(staffName, remaining - toAssign);
                                twinsOnFloor -= toAssign;

                                found = true;
                            }
                        }

                        staffIndex = (staffIndex + 1) % mainTwinStaff.size();
                    } while (staffIndex != startIndex && !found);

                    if (!found) {
                        break;
                    }
                }

                remainingMainTwins.put(floorNum, twinsOnFloor);
            }
        }

        // 別館ツイン割り振り（ラウンドロビン）
        if (!annexTwinStaff.isEmpty()) {
            int staffIndex = 0;
            List<Integer> floors = new ArrayList<>(annexFloorTwins.keySet());
            Collections.sort(floors);

            for (Integer floorNum : floors) {
                int twinsOnFloor = remainingAnnexTwins.getOrDefault(floorNum, 0);

                while (twinsOnFloor > 0) {
                    int startIndex = staffIndex;
                    boolean found = false;

                    do {
                        String staffName = annexTwinStaff.get(staffIndex);
                        int remaining = remainingAnnexTarget.getOrDefault(staffName, 0);

                        if (remaining > 0) {
                            FloorTwinAssignment fta = result.get(staffName);
                            int maxFloors = getMaxFloors(staffName, config);

                            int floorKey = 1000 + floorNum;
                            boolean alreadyOnFloor = fta.annexFloorTwins.containsKey(floorNum);
                            boolean canAddFloor = fta.usedFloors.size() < maxFloors;

                            if (alreadyOnFloor || canAddFloor) {
                                // ★修正: 1回で1部屋だけ割り当て（フロア内で複数スタッフに分散）
                                int toAssign = 1;

                                int currentAssigned = fta.annexFloorTwins.getOrDefault(floorNum, 0);
                                fta.annexFloorTwins.put(floorNum, currentAssigned + toAssign);
                                fta.usedFloors.add(floorKey);

                                remainingAnnexTarget.put(staffName, remaining - toAssign);
                                twinsOnFloor -= toAssign;

                                found = true;
                            }
                        }

                        staffIndex = (staffIndex + 1) % annexTwinStaff.size();
                    } while (staffIndex != startIndex && !found);

                    if (!found) {
                        break;
                    }
                }

                remainingAnnexTwins.put(floorNum, twinsOnFloor);
            }
        }

        // スタッフの目標を満たせたかチェック
        boolean staffTargetMet = true;
        for (String staffName : remainingMainTarget.keySet()) {
            if (remainingMainTarget.get(staffName) > 0) {
                LOGGER.fine(String.format("ツイン割り振り: %s の本館ツインが%d室不足",
                        staffName, remainingMainTarget.get(staffName)));
                staffTargetMet = false;
            }
        }
        for (String staffName : remainingAnnexTarget.keySet()) {
            if (remainingAnnexTarget.get(staffName) > 0) {
                LOGGER.fine(String.format("ツイン割り振り: %s の別館ツインが%d室不足",
                        staffName, remainingAnnexTarget.get(staffName)));
                staffTargetMet = false;
            }
        }

        // フロアに残りツインがあるかチェック（警告のみ）
        for (Integer floorNum : remainingMainTwins.keySet()) {
            if (remainingMainTwins.get(floorNum) > 0) {
                LOGGER.info(String.format("ツイン割り振り: 本館%d階に%d室のツインが未割り当て（残し部屋）",
                        floorNum, remainingMainTwins.get(floorNum)));
            }
        }
        for (Integer floorNum : remainingAnnexTwins.keySet()) {
            if (remainingAnnexTwins.get(floorNum) > 0) {
                LOGGER.info(String.format("ツイン割り振り: 別館%d階に%d室のツインが未割り当て（残し部屋）",
                        floorNum, remainingAnnexTwins.get(floorNum)));
            }
        }

        if (!staffTargetMet) {
            return null;
        }

        LOGGER.fine("ツイン割り振り（ラウンドロビン）: 成功");
        return result;
    }
    // ================================================================
    // シングル等割り振り（CP-SAT）
    // ================================================================

    private static List<PartialSolutionResult> assignSinglesMultiple(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config,
            Map<String, FloorTwinAssignment> twinResult,
            int maxStaffPerFloor) {

        CpModel model = new CpModel();

        List<AdaptiveRoomOptimizer.ExtendedStaffInfo> staffList = config.extendedStaffInfo;
        List<AdaptiveRoomOptimizer.FloorInfo> allFloors = new ArrayList<>();
        allFloors.addAll(buildingData.mainFloors);
        allFloors.addAll(buildingData.annexFloors);

        Map<String, IntVar> xVars = new HashMap<>();
        Map<String, BoolVar> yVars = new HashMap<>();

        // 変数作成（通常清掃のみ）
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            NormalRoomDistributionDialog.StaffDistribution dist =
                    config.roomDistribution != null ?
                            config.roomDistribution.get(staffName) : null;

            if (dist == null) continue;

            boolean hasMainRooms = (dist.mainSingleAssignedRooms + dist.mainTwinAssignedRooms) > 0;
            boolean hasAnnexRooms = (dist.annexSingleAssignedRooms + dist.annexTwinAssignedRooms) > 0;

            for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
                int floorNum = floor.floorNumber;
                boolean isMain = floor.isMainBuilding;

                if ((hasMainRooms && isMain) || (hasAnnexRooms && !isMain)) {
                    for (Map.Entry<String, Integer> entry : floor.roomCounts.entrySet()) {
                        String roomType = entry.getKey();
                        if ((isMain && isMainTwin(roomType)) || (!isMain && isAnnexTwin(roomType))) continue;

                        String varName = String.format("x_%s_%d_%s", staffName, floorNum, roomType);
                        xVars.put(varName, model.newIntVar(0, entry.getValue(), varName));
                    }

                    String yVarName = String.format("y_%s_%d", staffName, floorNum);
                    if (!yVars.containsKey(yVarName)) {
                        yVars.put(yVarName, model.newBoolVar(yVarName));
                    }
                }
            }
        }

        // 制約: 各フロアの部屋数を超えない
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

        // ソフト制約: スタッフのシングル等総数
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
                        if (xVars.containsKey(xVarName)) {
                            mainVars.add(xVars.get(xVarName));
                        }
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
                        if (xVars.containsKey(xVarName)) {
                            annexVars.add(xVars.get(xVarName));
                        }
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

        // スタッフのフロア制限
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            FloorTwinAssignment fta = twinResult.get(staffName);

            int maxFloors = getMaxFloors(staffName, config);
            int usedByTwin = (fta != null) ? fta.usedFloors.size() : 0;
            int remaining = maxFloors - usedByTwin;

            List<BoolVar> newFloorVars = new ArrayList<>();
            for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
                String yVarName = String.format("y_%s_%d", staffName, floor.floorNumber);
                if (!yVars.containsKey(yVarName)) continue;

                int floorKey = floor.isMainBuilding ? floor.floorNumber : (1000 + floor.floorNumber);
                if (fta != null && fta.usedFloors.contains(floorKey)) continue;

                newFloorVars.add(yVars.get(yVarName));
            }

            if (!newFloorVars.isEmpty() && remaining >= 0) {
                model.addLessOrEqual(LinearExpr.sum(newFloorVars.toArray(new BoolVar[0])), remaining);
            }
        }

        // フロア担当リンク制約
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;

            for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
                int floorNum = floor.floorNumber;
                String yVarName = String.format("y_%s_%d", staffName, floorNum);
                if (!yVars.containsKey(yVarName)) continue;

                List<IntVar> roomVars = new ArrayList<>();
                for (String rt : floor.roomCounts.keySet()) {
                    if ((floor.isMainBuilding && isMainTwin(rt)) ||
                            (!floor.isMainBuilding && isAnnexTwin(rt))) continue;
                    String vn = String.format("x_%s_%d_%s", staffName, floorNum, rt);
                    if (xVars.containsKey(vn)) roomVars.add(xVars.get(vn));
                }

                if (!roomVars.isEmpty()) {
                    IntVar total = model.newIntVar(0, 1000, "total_" + staffName + "_" + floorNum);
                    model.addEquality(total, LinearExpr.sum(roomVars.toArray(new IntVar[0])));

                    BoolVar yVar = yVars.get(yVarName);
                    model.addGreaterThan(total, 0).onlyEnforceIf(yVar);
                    model.addEquality(total, 0).onlyEnforceIf(yVar.not());
                }
            }
        }

        // フロアあたりのスタッフ数制限
        // ★修正: ツインで既にフロアにいるスタッフをstaffOnFloorから除外し、二重カウントを防止
        for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
            int floorKey = floor.isMainBuilding ? floor.floorNumber : (1000 + floor.floorNumber);

            // ツインで既にこのフロアにいるスタッフ名を収集
            Set<String> twinAssignedStaffNames = new HashSet<>();
            for (String staffName : twinResult.keySet()) {
                FloorTwinAssignment fta = twinResult.get(staffName);
                if (fta != null && fta.usedFloors.contains(floorKey)) {
                    twinAssignedStaffNames.add(staffName);
                }
            }

            // シングルで新規にこのフロアに来るスタッフのy変数のみ収集
            List<BoolVar> newStaffOnFloor = new ArrayList<>();
            for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
                if (twinAssignedStaffNames.contains(staffInfo.staff.name)) continue;
                String yVarName = String.format("y_%s_%d", staffInfo.staff.name, floor.floorNumber);
                if (yVars.containsKey(yVarName)) {
                    newStaffOnFloor.add(yVars.get(yVarName));
                }
            }

            int alreadyAssignedStaff = twinAssignedStaffNames.size();
            int remainingSlots = maxStaffPerFloor - alreadyAssignedStaff;
            if (remainingSlots < 0) remainingSlots = 0;

            if (!newStaffOnFloor.isEmpty() && remainingSlots < newStaffOnFloor.size()) {
                model.addLessOrEqual(
                        LinearExpr.sum(newStaffOnFloor.toArray(new BoolVar[0])),
                        remainingSlots);
            }
        }

        // ECO設定があるスタッフはECOがあるフロアを少なくとも1つ使用
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            NormalRoomDistributionDialog.StaffDistribution dist =
                    config.roomDistribution != null ?
                            config.roomDistribution.get(staffName) : null;

            if (dist == null) continue;

            if (dist.mainEcoAssignedRooms > 0) {
                List<BoolVar> ecoFloorVars = new ArrayList<>();
                for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
                    if (floor.ecoRooms > 0) {
                        String yVarName = String.format("y_%s_%d", staffName, floor.floorNumber);
                        if (yVars.containsKey(yVarName)) {
                            ecoFloorVars.add(yVars.get(yVarName));
                        }
                    }
                }
                if (!ecoFloorVars.isEmpty()) {
                    model.addGreaterOrEqual(
                            LinearExpr.sum(ecoFloorVars.toArray(new BoolVar[0])), 1);
                    LOGGER.info(String.format("  %s: 本館ECOフロア使用制約追加（%d候補フロア）",
                            staffName, ecoFloorVars.size()));
                }
            }

            if (dist.annexEcoAssignedRooms > 0) {
                List<BoolVar> ecoFloorVars = new ArrayList<>();
                for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.annexFloors) {
                    if (floor.ecoRooms > 0) {
                        String yVarName = String.format("y_%s_%d", staffName, floor.floorNumber);
                        if (yVars.containsKey(yVarName)) {
                            ecoFloorVars.add(yVars.get(yVarName));
                        }
                    }
                }
                if (!ecoFloorVars.isEmpty()) {
                    model.addGreaterOrEqual(
                            LinearExpr.sum(ecoFloorVars.toArray(new BoolVar[0])), 1);
                    LOGGER.info(String.format("  %s: 別館ECOフロア使用制約追加（%d候補フロア）",
                            staffName, ecoFloorVars.size()));
                }
            }
        }

        // 目的関数
        if (!shortageVars.isEmpty()) {
            model.minimize(LinearExpr.sum(shortageVars.toArray(new IntVar[0])));
        }

        // 複数解を列挙
        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(2.0);
        solver.getParameters().setEnumerateAllSolutions(true);

        List<PartialSolutionResult> results = new ArrayList<>();

        final Map<String, IntVar> finalXVars = xVars;
        final Map<String, IntVar> finalStaffShortageVars = staffShortageVars;

        CpSolverSolutionCallback callback = new CpSolverSolutionCallback() {
            @Override
            public void onSolutionCallback() {
                if (results.size() >= MAX_SINGLE_SOLUTIONS) {
                    stopSearch();
                    return;
                }

                int totalShortage = 0;
                for (IntVar shortageVar : shortageVars) {
                    totalShortage += (int) value(shortageVar);
                }

                if (totalShortage == 0 || results.isEmpty()) {
                    List<AdaptiveRoomOptimizer.StaffAssignment> assignments =
                            buildSolutionFromCallback(this, finalXVars, twinResult, staffList, buildingData, config);

                    int totalAssigned = 0, totalTarget = 0;
                    Map<String, Integer> staffShortage = new HashMap<>();

                    for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
                        String staffName = staffInfo.staff.name;
                        NormalRoomDistributionDialog.StaffDistribution dist =
                                config.roomDistribution != null ?
                                        config.roomDistribution.get(staffName) : null;
                        if (dist == null) continue;

                        int target = dist.mainSingleAssignedRooms + dist.annexSingleAssignedRooms;
                        totalTarget += target;

                        int mainShort = 0, annexShort = 0;
                        if (finalStaffShortageVars.containsKey(staffName + "_main")) {
                            mainShort = (int) value(finalStaffShortageVars.get(staffName + "_main"));
                        }
                        if (finalStaffShortageVars.containsKey(staffName + "_annex")) {
                            annexShort = (int) value(finalStaffShortageVars.get(staffName + "_annex"));
                        }

                        int assigned = target - mainShort - annexShort;
                        totalAssigned += assigned;
                        staffShortage.put(staffName, mainShort + annexShort);
                    }

                    results.add(new PartialSolutionResult(assignments, totalAssigned, totalTarget, staffShortage));
                }
            }
        };

        solver.solve(model, callback);

        if (results.isEmpty()) {
            LOGGER.warning("シングル割り振りで解が見つかりませんでした");
        } else {
            LOGGER.info(String.format("シングル割り振り: %d個の解を取得", results.size()));
        }

        return results;
    }

    /**
     * SolutionCallbackからソリューションを構築
     */
    private static List<AdaptiveRoomOptimizer.StaffAssignment> buildSolutionFromCallback(
            CpSolverSolutionCallback callback,
            Map<String, IntVar> xVars,
            Map<String, FloorTwinAssignment> twinResult,
            List<AdaptiveRoomOptimizer.ExtendedStaffInfo> staffList,
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        List<AdaptiveRoomOptimizer.StaffAssignment> result = new ArrayList<>();

        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            FloorTwinAssignment fta = twinResult.get(staffName);

            Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> mainAssignments = new TreeMap<>();
            Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> annexAssignments = new TreeMap<>();

            // 本館
            for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
                Map<String, Integer> rooms = new HashMap<>();
                if (fta != null && fta.mainFloorTwins.containsKey(floor.floorNumber)) {
                    rooms.put("T", fta.mainFloorTwins.get(floor.floorNumber));
                }
                for (String rt : floor.roomCounts.keySet()) {
                    if (isMainTwin(rt)) continue;
                    String vn = String.format("x_%s_%d_%s", staffName, floor.floorNumber, rt);
                    if (xVars.containsKey(vn)) {
                        int v = (int) callback.value(xVars.get(vn));
                        if (v > 0) rooms.put(rt, v);
                    }
                }
                if (!rooms.isEmpty()) {
                    mainAssignments.put(floor.floorNumber,
                            new AdaptiveRoomOptimizer.RoomAllocation(rooms, 0));
                }
            }

            // 別館
            for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.annexFloors) {
                Map<String, Integer> rooms = new HashMap<>();
                if (fta != null && fta.annexFloorTwins.containsKey(floor.floorNumber)) {
                    rooms.put("T", fta.annexFloorTwins.get(floor.floorNumber));
                }
                for (String rt : floor.roomCounts.keySet()) {
                    if (isAnnexTwin(rt)) continue;
                    String vn = String.format("x_%s_%d_%s", staffName, floor.floorNumber, rt);
                    if (xVars.containsKey(vn)) {
                        int v = (int) callback.value(xVars.get(vn));
                        if (v > 0) rooms.put(rt, v);
                    }
                }
                if (!rooms.isEmpty()) {
                    annexAssignments.put(floor.floorNumber,
                            new AdaptiveRoomOptimizer.RoomAllocation(rooms, 0));
                }
            }

            result.add(new AdaptiveRoomOptimizer.StaffAssignment(
                    staffInfo.staff, mainAssignments, annexAssignments, staffInfo.bathCleaningType));
        }

        return result;
    }

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