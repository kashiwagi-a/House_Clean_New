package org.example;

import com.google.ortools.sat.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * CP-SATソルバーを使用した部屋割り振り最適化
 *
 * ★修正版: 複数解列挙 + 本館・別館別均等性評価
 * ★追加: フロアあたりのスタッフ数制限（2人→3人フォールバック）
 * ★追加: NormalRoomDistributionDialogのECO目標値に従ったECO割り振り
 *
 * 処理フロー:
 * 0. 大浴清掃スタッフ: フロア単位ラウンドロビン（データ順序維持）
 * 1. ツイン割り振り（複数解を列挙）
 * 2. シングル等割り振り（複数解を列挙）
 * 3. ECO割り振り（目標値ベースまたは均等配分）
 * 4. 本館・別館別の均等性を評価し、最適解を選択
 */
public class RoomAssignmentCPSATOptimizer {
    private static final Logger LOGGER = Logger.getLogger(RoomAssignmentCPSATOptimizer.class.getName());

    // 探索する解の最大数
    private static final int MAX_SINGLE_SOLUTIONS = 5;
    private static final int MAX_TOTAL_CANDIDATES = 50;

    // ★追加: フロアあたりの最大スタッフ数（初期値と上限）
    private static final int INITIAL_MAX_STAFF_PER_FLOOR = 2;
    private static final int MAX_STAFF_PER_FLOOR_LIMIT = 10;  // これ以上は緩和しない

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

    /**
     * ツイン割り振り結果
     */
    public static class FloorTwinAssignment {
        public final Map<Integer, Integer> mainFloorTwins = new HashMap<>();
        public final Map<Integer, Integer> annexFloorTwins = new HashMap<>();
        public final Set<Integer> usedFloors = new HashSet<>();

        public FloorTwinAssignment() {}

        // ディープコピー用コンストラクタ
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
     * ★新規: 候補解（通常部屋 + ECOシミュレーション結果）
     */
    public static class CandidateSolution {
        public final List<AdaptiveRoomOptimizer.StaffAssignment> assignments;
        public final Map<String, Integer> mainRoomCounts;   // スタッフ別本館部屋数
        public final Map<String, Integer> annexRoomCounts;  // スタッフ別別館部屋数
        public final double mainSpread;   // 本館の最大-最小
        public final double annexSpread;  // 別館の最大-最小
        public final double totalScore;   // 総合スコア（小さいほど良い）

        public CandidateSolution(List<AdaptiveRoomOptimizer.StaffAssignment> assignments,
                                 Map<String, Integer> mainRoomCounts,
                                 Map<String, Integer> annexRoomCounts) {
            this.assignments = assignments;
            this.mainRoomCounts = mainRoomCounts;
            this.annexRoomCounts = annexRoomCounts;

            // 本館の均等性評価
            if (mainRoomCounts.isEmpty()) {
                this.mainSpread = 0;
            } else {
                int maxMain = mainRoomCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
                int minMain = mainRoomCounts.values().stream().mapToInt(Integer::intValue).min().orElse(0);
                this.mainSpread = maxMain - minMain;
            }

            // 別館の均等性評価
            if (annexRoomCounts.isEmpty()) {
                this.annexSpread = 0;
            } else {
                int maxAnnex = annexRoomCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
                int minAnnex = annexRoomCounts.values().stream().mapToInt(Integer::intValue).min().orElse(0);
                this.annexSpread = maxAnnex - minAnnex;
            }

            // 総合スコア（本館と別館の均等性を両方考慮）
            this.totalScore = this.mainSpread + this.annexSpread;
        }

        @Override
        public String toString() {
            return String.format("本館spread=%.1f, 別館spread=%.1f, 総合=%.1f",
                    mainSpread, annexSpread, totalScore);
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

    /**
     * メイン最適化メソッド
     */
    public static List<AdaptiveRoomOptimizer.StaffAssignment> optimize(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        LOGGER.info("=== CP-SATソルバーによる最適化を開始（複数解評価版） ===");

        // ステップ0: 大浴清掃スタッフへのフロア単位ラウンドロビン割り振り
        BathStaffPreAssignmentResult bathPreAssignment = preAssignBathCleaningStaff(buildingData, config);

        // 大浴清掃スタッフがいない場合は通常のCPSAT最適化を実行
        if (bathPreAssignment.bathStaffAssignments.isEmpty()) {
            LOGGER.info("大浴清掃スタッフなし。通常のCPSAT最適化を実行します。");
            return optimizeWithCPSAT(buildingData, config, new HashMap<>());
        }

        LOGGER.info("=== 大浴清掃スタッフの事前割り振り完了 ===");
        for (Map.Entry<String, AdaptiveRoomOptimizer.StaffAssignment> entry : bathPreAssignment.bathStaffAssignments.entrySet()) {
            LOGGER.info(String.format("  %s: %d室", entry.getKey(), entry.getValue().getTotalRooms()));
        }

        // 残りのスタッフに対してCPSAT最適化を実行
        AdaptiveRoomOptimizer.AdaptiveLoadConfig remainingConfig = createRemainingConfig(config, bathPreAssignment);

        List<AdaptiveRoomOptimizer.StaffAssignment> cpSatAssignments =
                optimizeWithCPSAT(bathPreAssignment.remainingBuildingData, remainingConfig, bathPreAssignment.bathStaffAssignments);

        // 大浴清掃スタッフの割り振りとCPSAT結果をマージ
        return mergeAssignments(bathPreAssignment.bathStaffAssignments, cpSatAssignments);
    }

    /**
     * 大浴清掃スタッフへの事前割り振り（フロア単位ラウンドロビン方式）
     */
    private static BathStaffPreAssignmentResult preAssignBathCleaningStaff(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        LOGGER.info("=== ステップ0: 大浴清掃スタッフへのフロア単位ラウンドロビン割り振り ===");

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

        // 本館フロア（元のデータ順序を維持）
        List<AdaptiveRoomOptimizer.FloorInfo> sortedMainFloors = new ArrayList<>(buildingData.mainFloors);

        // 別館フロア（元のデータ順序を維持）
        List<AdaptiveRoomOptimizer.FloorInfo> sortedAnnexFloors = new ArrayList<>(buildingData.annexFloors);

        // スタッフ別の割り振り結果を保持
        Map<String, Map<Integer, AdaptiveRoomOptimizer.RoomAllocation>> staffMainAssignments = new HashMap<>();
        Map<String, Map<Integer, AdaptiveRoomOptimizer.RoomAllocation>> staffAnnexAssignments = new HashMap<>();

        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : bathStaffList) {
            staffMainAssignments.put(staffInfo.staff.name, new TreeMap<>());
            staffAnnexAssignments.put(staffInfo.staff.name, new TreeMap<>());
        }

        int staffIndex = 0;

        // 本館のフロアを順に処理（データ順序維持）
        LOGGER.info("--- 本館フロアの割り振り ---");
        for (AdaptiveRoomOptimizer.FloorInfo floor : sortedMainFloors) {
            int floorNumber = floor.floorNumber;

            int floorSingleRooms = 0;
            int floorTwinRooms = 0;
            for (Map.Entry<String, Integer> entry : floor.roomCounts.entrySet()) {
                if (isMainTwin(entry.getKey())) {
                    floorTwinRooms += entry.getValue();
                } else {
                    floorSingleRooms += entry.getValue();
                }
            }

            if (floorSingleRooms == 0 && floorTwinRooms == 0) {
                continue;
            }

            AdaptiveRoomOptimizer.ExtendedStaffInfo selectedStaff = null;
            int attempts = 0;
            while (attempts < bathStaffList.size()) {
                AdaptiveRoomOptimizer.ExtendedStaffInfo candidate = bathStaffList.get(staffIndex % bathStaffList.size());
                String candidateName = candidate.staff.name;
                BathStaffRoomTarget target = staffTargets.get(candidateName);

                if (target != null && target.needsMainRooms()) {
                    selectedStaff = candidate;
                    break;
                }
                staffIndex++;
                attempts++;
            }

            if (selectedStaff == null) {
                LOGGER.info("全大浴清掃スタッフが本館の目標部屋数に達しました。");
                break;
            }

            String staffName = selectedStaff.staff.name;
            BathStaffRoomTarget target = staffTargets.get(staffName);

            int singleToAssign = Math.min(floorSingleRooms, target.getMainSingleRemaining());
            int twinToAssign = Math.min(floorTwinRooms, target.getMainTwinRemaining());

            LOGGER.info(String.format("本館 %d階（S:%d, T:%d）→ %s に S:%d, T:%d を割り振り",
                    floorNumber, floorSingleRooms, floorTwinRooms, staffName, singleToAssign, twinToAssign));

            Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> staffFloors = staffMainAssignments.get(staffName);
            Map<String, Integer> assignedRoomCounts = new HashMap<>();

            int singleAssigned = 0;
            int twinAssigned = 0;

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
                staffFloors.put(floorNumber, new AdaptiveRoomOptimizer.RoomAllocation(assignedRoomCounts, 0));
                target.mainSingleAssigned += singleAssigned;
                target.mainTwinAssigned += twinAssigned;
            }

            staffIndex++;
        }

        // 別館のフロアを順に処理（データ順序維持）
        boolean needAnnex = false;
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : bathStaffList) {
            String staffName = staffInfo.staff.name;
            BathStaffRoomTarget target = staffTargets.get(staffName);
            if (target != null && target.needsAnnexRooms()) {
                needAnnex = true;
                break;
            }
        }

        if (needAnnex) {
            LOGGER.info("--- 別館フロアの割り振り ---");
            for (AdaptiveRoomOptimizer.FloorInfo floor : sortedAnnexFloors) {
                int floorNumber = floor.floorNumber;

                int floorSingleRooms = 0;
                int floorTwinRooms = 0;
                for (Map.Entry<String, Integer> entry : floor.roomCounts.entrySet()) {
                    if (isAnnexTwin(entry.getKey())) {
                        floorTwinRooms += entry.getValue();
                    } else {
                        floorSingleRooms += entry.getValue();
                    }
                }

                if (floorSingleRooms == 0 && floorTwinRooms == 0) {
                    continue;
                }

                AdaptiveRoomOptimizer.ExtendedStaffInfo selectedStaff = null;
                int attempts = 0;
                while (attempts < bathStaffList.size()) {
                    AdaptiveRoomOptimizer.ExtendedStaffInfo candidate = bathStaffList.get(staffIndex % bathStaffList.size());
                    String candidateName = candidate.staff.name;
                    BathStaffRoomTarget target = staffTargets.get(candidateName);

                    if (target != null && target.needsAnnexRooms()) {
                        selectedStaff = candidate;
                        break;
                    }
                    staffIndex++;
                    attempts++;
                }

                if (selectedStaff == null) {
                    LOGGER.info("全大浴清掃スタッフが別館の目標部屋数に達しました。");
                    break;
                }

                String staffName = selectedStaff.staff.name;
                BathStaffRoomTarget target = staffTargets.get(staffName);

                int singleToAssign = Math.min(floorSingleRooms, target.getAnnexSingleRemaining());
                int twinToAssign = Math.min(floorTwinRooms, target.getAnnexTwinRemaining());

                LOGGER.info(String.format("別館 %d階（S:%d, T:%d）→ %s に S:%d, T:%d を割り振り",
                        floorNumber, floorSingleRooms, floorTwinRooms, staffName, singleToAssign, twinToAssign));

                Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> staffFloors = staffAnnexAssignments.get(staffName);
                Map<String, Integer> assignedRoomCounts = new HashMap<>();

                int singleAssigned = 0;
                int twinAssigned = 0;

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
                    staffFloors.put(floorNumber, new AdaptiveRoomOptimizer.RoomAllocation(assignedRoomCounts, 0));
                    target.annexSingleAssigned += singleAssigned;
                    target.annexTwinAssigned += twinAssigned;
                }

                staffIndex++;
            }
        }

        // StaffAssignmentを作成
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
            LOGGER.info(String.format("大浴清掃スタッフ %s: 本館S=%d/%d, 本館T=%d/%d, 別館S=%d/%d, 別館T=%d/%d, 合計=%d室",
                    staffName,
                    target.mainSingleAssigned, target.mainSingleTarget,
                    target.mainTwinAssigned, target.mainTwinTarget,
                    target.annexSingleAssigned, target.annexSingleTarget,
                    target.annexTwinAssigned, target.annexTwinTarget,
                    assignment.getTotalRooms()));
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
     * ★修正: CPSAT最適化（複数解評価版 + フロアあたりスタッフ数制限）
     */
    private static List<AdaptiveRoomOptimizer.StaffAssignment> optimizeWithCPSAT(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config,
            Map<String, AdaptiveRoomOptimizer.StaffAssignment> existingAssignments) {

        if (config.extendedStaffInfo.isEmpty()) {
            LOGGER.info("CPSAT最適化対象のスタッフがいません。");
            return new ArrayList<>();
        }

        if (buildingData.mainRoomCount == 0 && buildingData.annexRoomCount == 0) {
            LOGGER.info("CPSAT最適化対象の部屋がありません。");
            return new ArrayList<>();
        }

        // ★追加: まず2人制限で試行
        LOGGER.info("=== フロアあたり最大2人制限でCPSAT最適化を開始 ===");
        // ★修正: フロアあたりのスタッフ数制限を段階的に緩和（2人→3人→4人...）
        for (int maxStaffPerFloor = INITIAL_MAX_STAFF_PER_FLOOR;
             maxStaffPerFloor <= MAX_STAFF_PER_FLOOR_LIMIT;
             maxStaffPerFloor++) {

            List<AdaptiveRoomOptimizer.StaffAssignment> result =
                    optimizeWithStaffPerFloorLimit(buildingData, config, existingAssignments, maxStaffPerFloor);

            if (result != null) {
                LOGGER.info(String.format("フロアあたり最大%d人制限で解が見つかりました。", maxStaffPerFloor));
                return result;
            }

            if (maxStaffPerFloor < MAX_STAFF_PER_FLOOR_LIMIT) {
                LOGGER.warning(String.format("=== フロアあたり%d人制限では解が見つかりませんでした。%d人制限で再試行します ===",
                        maxStaffPerFloor, maxStaffPerFloor + 1));
            }
        }

        // 全ての制限を試しても解が見つからない場合
        LOGGER.severe(String.format("フロアあたり%d人制限でも解が見つかりませんでした。", MAX_STAFF_PER_FLOOR_LIMIT));
        throw new RuntimeException("全てのフロア制限パターンを試行しましたが、解が見つかりませんでした。\n" +
                "通常清掃部屋割り振り設定を見直してください。");
    }

    /**
     * ★新規: フロアあたりのスタッフ数制限付きCPSAT最適化
     */
    private static List<AdaptiveRoomOptimizer.StaffAssignment> optimizeWithStaffPerFloorLimit(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config,
            Map<String, AdaptiveRoomOptimizer.StaffAssignment> existingAssignments,
            int maxStaffPerFloor) {

        LOGGER.info(String.format("=== 残りスタッフのCPSAT最適化（フロアあたり最大%d人）===", maxStaffPerFloor));

        List<Map<String, TwinAssignment>> twinPatterns = generateTwinPatterns(buildingData, config);
        LOGGER.info("ツインパターン数: " + twinPatterns.size());

        List<CandidateSolution> completeSolutions = new ArrayList<>();
        PartialSolutionResult bestPartialResult = null;
        int bestPartialPatternIndex = -1;

        for (int i = 0; i < twinPatterns.size(); i++) {
            Map<String, TwinAssignment> pattern = twinPatterns.get(i);

            if (i % 20 == 0) {
                LOGGER.info("=== ツインパターン " + (i + 1) + "/" + twinPatterns.size() + " を試行中 ===");
            }

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
                    // 完全解: ECOを割り振ってシミュレーション評価
                    CandidateSolution candidate = simulateEcoAndEvaluate(
                            result.assignments, buildingData, config);
                    completeSolutions.add(candidate);

                    LOGGER.info(String.format("完全解 #%d: %s",
                            completeSolutions.size(), candidate.toString()));

                    if (completeSolutions.size() >= MAX_TOTAL_CANDIDATES) {
                        LOGGER.info("十分な候補解が見つかりました。探索を終了します。");
                        break;
                    }
                } else {
                    if (bestPartialResult == null || result.totalAssignedRooms > bestPartialResult.totalAssignedRooms) {
                        bestPartialResult = result;
                        bestPartialPatternIndex = i + 1;
                    }
                }
            }

            if (completeSolutions.size() >= MAX_TOTAL_CANDIDATES) {
                break;
            }
        }
        // 最適な解を選択
        if (!completeSolutions.isEmpty()) {
            LOGGER.info(String.format("=== %d個の完全解から最適解を選択 ===", completeSolutions.size()));

            // 本館・別館別の均等性でソート
            completeSolutions.sort(Comparator.comparingDouble(c -> c.totalScore));

            CandidateSolution best = completeSolutions.get(0);
            LOGGER.info(String.format("最適解を選択: %s", best.toString()));

            // スタッフ別部屋数をログ出力
            LOGGER.info("=== 最適解のスタッフ別部屋数 ===");
            for (AdaptiveRoomOptimizer.StaffAssignment sa : best.assignments) {
                int mainRooms = best.mainRoomCounts.getOrDefault(sa.staff.name, 0);
                int annexRooms = best.annexRoomCounts.getOrDefault(sa.staff.name, 0);
                LOGGER.info(String.format("  %s: 本館=%d室, 別館=%d室, 合計=%d室",
                        sa.staff.name, mainRooms, annexRooms, mainRooms + annexRooms));
            }

            // 実際にECOを割り振り
            assignEcoRooms(best.assignments, buildingData, config);
            return best.assignments;
        }

        // 完全解がない場合：最大制限に達していなければnullを返して次の制限で再試行
        if (maxStaffPerFloor < MAX_STAFF_PER_FLOOR_LIMIT) {
            return null;
        }

        // 最大制限でも完全解がない場合は部分解を使用
        if (bestPartialResult != null) {
            LOGGER.severe("=== 完全な解が見つかりませんでした ===");
            LOGGER.severe("最良の部分解（パターン " + bestPartialPatternIndex + "）を使用します");
            LOGGER.severe(String.format("割り振り済み: %d室 / 目標: %d室（不足: %d室）",
                    bestPartialResult.totalAssignedRooms, bestPartialResult.totalTargetRooms, bestPartialResult.shortage));

            assignEcoRooms(bestPartialResult.assignments, buildingData, config);
            return bestPartialResult.assignments;
        }

        if (twinPatterns.isEmpty()) {
            LOGGER.warning("ツインパターンが生成されませんでした。空の結果を返します。");
            return new ArrayList<>();
        }

        return null;
    }

    /**
     * ★新規: ECO割り振りをシミュレートして均等性を評価
     */
    private static CandidateSolution simulateEcoAndEvaluate(
            List<AdaptiveRoomOptimizer.StaffAssignment> assignments,
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        // 現在の本館・別館の部屋数を集計
        Map<String, Integer> mainRoomCounts = new HashMap<>();
        Map<String, Integer> annexRoomCounts = new HashMap<>();

        for (AdaptiveRoomOptimizer.StaffAssignment sa : assignments) {
            int mainRooms = getMainBuildingRooms(sa);
            int annexRooms = getAnnexBuildingRooms(sa);
            mainRoomCounts.put(sa.staff.name, mainRooms);
            annexRoomCounts.put(sa.staff.name, annexRooms);
        }

        // ECOをシミュレート割り振り
        List<EcoFloorInfo> mainEcoFloors = new ArrayList<>();
        List<EcoFloorInfo> annexEcoFloors = new ArrayList<>();

        for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
            if (floor.ecoRooms > 0) {
                mainEcoFloors.add(new EcoFloorInfo(floor.floorNumber, floor.ecoRooms, true));
            }
        }
        for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.annexFloors) {
            if (floor.ecoRooms > 0) {
                annexEcoFloors.add(new EcoFloorInfo(floor.floorNumber, floor.ecoRooms, false));
            }
        }

        // ★★変更: ECO目標値がある場合は目標に従ってシミュレート
        Map<String, Integer> mainEcoTargets = new HashMap<>();
        Map<String, Integer> annexEcoTargets = new HashMap<>();
        boolean hasEcoTargets = false;

        if (config.roomDistribution != null) {
            for (Map.Entry<String, NormalRoomDistributionDialog.StaffDistribution> entry :
                    config.roomDistribution.entrySet()) {
                NormalRoomDistributionDialog.StaffDistribution dist = entry.getValue();
                mainEcoTargets.put(entry.getKey(), dist.mainEcoAssignedRooms);
                annexEcoTargets.put(entry.getKey(), dist.annexEcoAssignedRooms);
                if (dist.mainEcoAssignedRooms > 0 || dist.annexEcoAssignedRooms > 0) {
                    hasEcoTargets = true;
                }
            }
        }

        if (hasEcoTargets) {
            // 目標値に従ってECOを加算
            for (String staffName : mainRoomCounts.keySet()) {
                int ecoTarget = mainEcoTargets.getOrDefault(staffName, 0);
                mainRoomCounts.put(staffName, mainRoomCounts.get(staffName) + ecoTarget);
            }
            for (String staffName : annexRoomCounts.keySet()) {
                int ecoTarget = annexEcoTargets.getOrDefault(staffName, 0);
                annexRoomCounts.put(staffName, annexRoomCounts.get(staffName) + ecoTarget);
            }
        } else {
            // 本館ECOを均等に割り振り（シミュレーション）
            int totalMainEco = mainEcoFloors.stream().mapToInt(f -> f.ecoCount).sum();
            if (totalMainEco > 0 && !mainRoomCounts.isEmpty()) {
                distributeEcoEvenly(mainRoomCounts, totalMainEco);
            }

            // 別館ECOを均等に割り振り（シミュレーション）
            int totalAnnexEco = annexEcoFloors.stream().mapToInt(f -> f.ecoCount).sum();
            if (totalAnnexEco > 0 && !annexRoomCounts.isEmpty()) {
                distributeEcoEvenly(annexRoomCounts, totalAnnexEco);
            }
        }

        return new CandidateSolution(assignments, mainRoomCounts, annexRoomCounts);
    }

    /**
     * ★新規: ECOを均等に分配（シミュレーション用）
     */
    private static void distributeEcoEvenly(Map<String, Integer> roomCounts, int ecoToDistribute) {
        if (roomCounts.isEmpty() || ecoToDistribute <= 0) {
            return;
        }

        // PriorityQueueを使って最小負荷のスタッフに順次割り当て
        PriorityQueue<Map.Entry<String, Integer>> pq = new PriorityQueue<>(
                Comparator.comparingInt(Map.Entry::getValue));
        pq.addAll(roomCounts.entrySet());

        for (int i = 0; i < ecoToDistribute; i++) {
            Map.Entry<String, Integer> minEntry = pq.poll();
            if (minEntry != null) {
                roomCounts.put(minEntry.getKey(), minEntry.getValue() + 1);
                pq.add(new AbstractMap.SimpleEntry<>(minEntry.getKey(), minEntry.getValue() + 1));
            }
        }
    }

    /**
     * 大浴清掃スタッフの割り振りとCPSAT結果をマージ
     */
    private static List<AdaptiveRoomOptimizer.StaffAssignment> mergeAssignments(
            Map<String, AdaptiveRoomOptimizer.StaffAssignment> bathStaffAssignments,
            List<AdaptiveRoomOptimizer.StaffAssignment> cpSatAssignments) {

        List<AdaptiveRoomOptimizer.StaffAssignment> merged = new ArrayList<>();
        merged.addAll(bathStaffAssignments.values());
        merged.addAll(cpSatAssignments);

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

    // ============================================================
    // ★変更: ツイン割り振り（ラウンドロビン方式）
    // ============================================================

    private static Map<String, FloorTwinAssignment> assignTwinsRoundRobin(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config,
            Map<String, TwinAssignment> twinPattern,
            int maxStaffPerFloor) {

        // 各フロアのツイン数を集計
        Map<Integer, Integer> mainFloorTwins = new LinkedHashMap<>();  // 順序保持
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

        for (Map.Entry<String, TwinAssignment> entry : twinPattern.entrySet()) {
            String staffName = entry.getKey();
            TwinAssignment ta = entry.getValue();

            result.put(staffName, new FloorTwinAssignment());

            if (ta.mainTwinRooms > 0) {
                remainingMainTarget.put(staffName, ta.mainTwinRooms);
                mainTwinStaff.add(staffName);
            }
            if (ta.annexTwinRooms > 0) {
                remainingAnnexTarget.put(staffName, ta.annexTwinRooms);
                annexTwinStaff.add(staffName);
            }
        }

        // 本館ツイン割り振り（ラウンドロビン）
        if (!mainTwinStaff.isEmpty()) {
            int staffIndex = 0;
            List<Integer> floors = new ArrayList<>(mainFloorTwins.keySet());
            Collections.sort(floors);  // フロア昇順

            for (Integer floorNum : floors) {
                int twinsOnFloor = remainingMainTwins.getOrDefault(floorNum, 0);

                while (twinsOnFloor > 0) {
                    // 次のスタッフを探す
                    int startIndex = staffIndex;
                    boolean found = false;

                    do {
                        String staffName = mainTwinStaff.get(staffIndex);
                        int remaining = remainingMainTarget.getOrDefault(staffName, 0);

                        if (remaining > 0) {
                            // フロア制限チェック
                            FloorTwinAssignment fta = result.get(staffName);
                            int maxFloors = getMaxFloors(staffName, config);

                            // 既にこのフロアを担当しているか、新規フロアとして追加可能か
                            boolean alreadyOnFloor = fta.mainFloorTwins.containsKey(floorNum);
                            boolean canAddFloor = fta.usedFloors.size() < maxFloors;

                            if (alreadyOnFloor || canAddFloor) {
                                // 割り当て可能な数を計算（このスタッフの残り目標数まで）
                                int toAssign = Math.min(remaining, twinsOnFloor);

                                // 割り当て
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
                        // 割り当て可能なスタッフがいない
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

                            int floorKey = 1000 + floorNum;  // 別館はオフセット
                            boolean alreadyOnFloor = fta.annexFloorTwins.containsKey(floorNum);
                            boolean canAddFloor = fta.usedFloors.size() < maxFloors;

                            if (alreadyOnFloor || canAddFloor) {
                                int toAssign = Math.min(remaining, twinsOnFloor);

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

        // フロアに残りツインがあるかチェック（警告のみ、未割り当て許容）
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
            return null;  // スタッフの目標部屋数を満たせない場合は失敗
        }

        LOGGER.fine("ツイン割り振り（ラウンドロビン）: 成功");
        return result;
    }

    // ============================================================
    // ★修正: シングル等割り振り（複数解を返す版 + フロアあたりスタッフ数制限）
    // ============================================================

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

        // 変数作成
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

                if (!hasMainRooms && isMain) continue;
                if (!hasAnnexRooms && !isMain) continue;

                for (Map.Entry<String, Integer> entry : floor.roomCounts.entrySet()) {
                    String roomType = entry.getKey();
                    if ((isMain && isMainTwin(roomType)) || (!isMain && isAnnexTwin(roomType))) continue;

                    String varName = String.format("x_%s_%d_%s", staffName, floorNum, roomType);
                    xVars.put(varName, model.newIntVar(0, entry.getValue(), varName));
                }

                String yVarName = String.format("y_%s_%d", staffName, floorNum);
                yVars.put(yVarName, model.newBoolVar(yVarName));
            }
        }

        // 制約: 各フロアの部屋数を超えない（未割り当て許容）
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

        // ソフト制約: スタッフのシングル等総数（ペナルティ付き）
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

        // ★追加: フロアあたりのスタッフ数制限
        for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
            List<BoolVar> staffOnFloor = new ArrayList<>();
            for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
                String yVarName = String.format("y_%s_%d", staffInfo.staff.name, floor.floorNumber);
                if (yVars.containsKey(yVarName)) {
                    staffOnFloor.add(yVars.get(yVarName));
                }
            }

            // ツインで既に担当しているスタッフも考慮
            int alreadyAssignedStaff = 0;
            int floorKey = floor.isMainBuilding ? floor.floorNumber : (1000 + floor.floorNumber);
            for (String staffName : twinResult.keySet()) {
                FloorTwinAssignment fta = twinResult.get(staffName);
                if (fta != null && fta.usedFloors.contains(floorKey)) {
                    alreadyAssignedStaff++;
                }
            }

            int remainingSlots = maxStaffPerFloor - alreadyAssignedStaff;
            if (remainingSlots < 0) remainingSlots = 0;

            if (!staffOnFloor.isEmpty() && remainingSlots < staffOnFloor.size()) {
                model.addLessOrEqual(
                        LinearExpr.sum(staffOnFloor.toArray(new BoolVar[0])),
                        remainingSlots);
            }
        }

        // 目的関数: 不足の合計を最小化
        if (!shortageVars.isEmpty()) {
            model.minimize(LinearExpr.sum(shortageVars.toArray(new IntVar[0])));
        }

        // 複数解を列挙
        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(10.0);
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

                // 不足の合計を計算
                int totalShortage = 0;
                for (IntVar shortageVar : shortageVars) {
                    totalShortage += (int) value(shortageVar);
                }

                // 最適解（不足0）のみ収集、または初回の解
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

    // ============================================================
    // ★★修正: ECO割り振り（目標値ベースまたは均等配分）
    // ============================================================

    /**
     * ★★修正版: NormalRoomDistributionDialogのECO目標値に従って割り振り
     */
    private static void assignEcoRooms(
            List<AdaptiveRoomOptimizer.StaffAssignment> assignments,
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        LOGGER.info("--- ECO割り振り ---");

        // ★★変更: NormalRoomDistributionDialogからECO目標を取得
        Map<String, Integer> mainEcoTargets = new HashMap<>();
        Map<String, Integer> annexEcoTargets = new HashMap<>();
        boolean hasEcoTargets = false;

        if (config.roomDistribution != null) {
            for (Map.Entry<String, NormalRoomDistributionDialog.StaffDistribution> entry :
                    config.roomDistribution.entrySet()) {
                NormalRoomDistributionDialog.StaffDistribution dist = entry.getValue();
                mainEcoTargets.put(entry.getKey(), dist.mainEcoAssignedRooms);
                annexEcoTargets.put(entry.getKey(), dist.annexEcoAssignedRooms);

                if (dist.mainEcoAssignedRooms > 0 || dist.annexEcoAssignedRooms > 0) {
                    hasEcoTargets = true;
                    LOGGER.info(String.format("  ECO目標 %s: 本館=%d室, 別館=%d室",
                            entry.getKey(), dist.mainEcoAssignedRooms, dist.annexEcoAssignedRooms));
                }
            }
        }

        // ECOフロア情報を収集
        List<EcoFloorInfo> mainEcoFloors = new ArrayList<>();
        List<EcoFloorInfo> annexEcoFloors = new ArrayList<>();

        for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
            if (floor.ecoRooms > 0) {
                mainEcoFloors.add(new EcoFloorInfo(floor.floorNumber, floor.ecoRooms, true));
            }
        }
        for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.annexFloors) {
            if (floor.ecoRooms > 0) {
                annexEcoFloors.add(new EcoFloorInfo(floor.floorNumber, floor.ecoRooms, false));
            }
        }

        int totalMainEco = mainEcoFloors.stream().mapToInt(f -> f.ecoCount).sum();
        int totalAnnexEco = annexEcoFloors.stream().mapToInt(f -> f.ecoCount).sum();

        LOGGER.info(String.format("ECO総数: 本館=%d室, 別館=%d室", totalMainEco, totalAnnexEco));

        if (totalMainEco == 0 && totalAnnexEco == 0) {
            LOGGER.info("ECO部屋がありません");
            return;
        }

        // ★★分岐: ECO目標がある場合は目標ベース、ない場合は従来の均等配分
        if (hasEcoTargets) {
            LOGGER.info("ECO目標値に基づいて割り振りを行います");

            // 本館ECOを目標に従って割り振り
            if (totalMainEco > 0) {
                assignEcoToBuildingWithTargets(assignments, mainEcoFloors, true, mainEcoTargets);
            }

            // 別館ECOを目標に従って割り振り
            if (totalAnnexEco > 0) {
                assignEcoToBuildingWithTargets(assignments, annexEcoFloors, false, annexEcoTargets);
            }
        } else {
            LOGGER.info("ECO目標値なし。従来の均等配分を行います");

            // 本館ECOを均等に割り振り（従来ロジック）
            if (totalMainEco > 0) {
                assignEcoToBuilding(assignments, mainEcoFloors, true, config);
            }

            // 別館ECOを均等に割り振り（従来ロジック）
            if (totalAnnexEco > 0) {
                assignEcoToBuilding(assignments, annexEcoFloors, false, config);
            }
        }
    }

    /**
     * ★★新規: 目標値に基づいてECOを割り振り
     */
    private static void assignEcoToBuildingWithTargets(
            List<AdaptiveRoomOptimizer.StaffAssignment> assignments,
            List<EcoFloorInfo> ecoFloors,
            boolean isMain,
            Map<String, Integer> ecoTargets) {

        String buildingName = isMain ? "本館" : "別館";
        int totalEco = ecoFloors.stream().mapToInt(f -> f.ecoCount).sum();

        LOGGER.info(String.format("--- %sECO割り振り開始（目標ベース）: %d室 ---", buildingName, totalEco));

        // 目標合計を確認
        int targetSum = ecoTargets.values().stream().mapToInt(Integer::intValue).sum();
        LOGGER.info(String.format("ECO目標合計: %d室 (実際: %d室)", targetSum, totalEco));

        // スタッフごとの残り目標を追跡
        Map<String, Integer> remainingTargets = new HashMap<>(ecoTargets);

        // 各フロアのECOを割り振り
        for (EcoFloorInfo ecoFloor : ecoFloors) {
            int remaining = ecoFloor.ecoCount;
            int floorNumber = ecoFloor.floorNumber;

            LOGGER.info(String.format("%s %d階: ECO %d室を配分", buildingName, floorNumber, remaining));

            while (remaining > 0) {
                // 最も目標が残っているスタッフを選択
                String selectedStaff = null;
                int maxRemaining = 0;

                for (Map.Entry<String, Integer> entry : remainingTargets.entrySet()) {
                    if (entry.getValue() > maxRemaining) {
                        // このスタッフが対象の建物を担当しているか確認
                        for (AdaptiveRoomOptimizer.StaffAssignment sa : assignments) {
                            if (sa.staff.name.equals(entry.getKey())) {
                                boolean hasBuilding = isMain ?
                                        !sa.mainBuildingAssignments.isEmpty() || entry.getValue() > 0 :
                                        !sa.annexBuildingAssignments.isEmpty() || entry.getValue() > 0;
                                if (hasBuilding) {
                                    maxRemaining = entry.getValue();
                                    selectedStaff = entry.getKey();
                                }
                                break;
                            }
                        }
                    }
                }

                if (selectedStaff == null || maxRemaining == 0) {
                    // 目標を達成した場合は、まだECOが残っていれば均等配分にフォールバック
                    if (remaining > 0) {
                        LOGGER.info("全スタッフがECO目標を達成。残り" + remaining + "室を均等配分します。");
                        distributeRemainingEcoEvenly(assignments, floorNumber, remaining, isMain);
                    }
                    break;
                }

                // このスタッフにECOを1つ割り振り
                for (AdaptiveRoomOptimizer.StaffAssignment sa : assignments) {
                    if (sa.staff.name.equals(selectedStaff)) {
                        addEcoToStaff(sa, floorNumber, 1, isMain);
                        LOGGER.fine(String.format("  → %s に ECO 1室を割り当て (残り目標: %d)",
                                selectedStaff, maxRemaining - 1));
                        break;
                    }
                }

                remainingTargets.put(selectedStaff, maxRemaining - 1);
                remaining--;
            }
        }

        // 結果をログ出力
        LOGGER.info(String.format("--- %sECO割り振り結果 ---", buildingName));
        for (AdaptiveRoomOptimizer.StaffAssignment sa : assignments) {
            int ecoCount = isMain ? getMainBuildingEcoRooms(sa) : getAnnexBuildingEcoRooms(sa);
            int target = ecoTargets.getOrDefault(sa.staff.name, 0);
            if (ecoCount > 0 || target > 0) {
                LOGGER.info(String.format("  %s: ECO=%d室 (目標: %d室)", sa.staff.name, ecoCount, target));
            }
        }
    }

    /**
     * ★★新規: 残りのECOを均等に配分（フォールバック用）
     */
    private static void distributeRemainingEcoEvenly(
            List<AdaptiveRoomOptimizer.StaffAssignment> assignments,
            int floorNumber,
            int remaining,
            boolean isMain) {

        // 現在の部屋数が最小のスタッフから順に割り当て
        while (remaining > 0) {
            AdaptiveRoomOptimizer.StaffAssignment minStaff = null;
            int minRooms = Integer.MAX_VALUE;

            for (AdaptiveRoomOptimizer.StaffAssignment sa : assignments) {
                int rooms = isMain ? getMainBuildingRooms(sa) : getAnnexBuildingRooms(sa);
                if (rooms < minRooms) {
                    minRooms = rooms;
                    minStaff = sa;
                }
            }

            if (minStaff != null) {
                addEcoToStaff(minStaff, floorNumber, 1, isMain);
                remaining--;
            } else {
                break;
            }
        }
    }

    /**
     * 建物別のECO割り振り（均等化・従来ロジック）
     */
    private static void assignEcoToBuilding(
            List<AdaptiveRoomOptimizer.StaffAssignment> assignments,
            List<EcoFloorInfo> ecoFloors,
            boolean isMain,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        String buildingName = isMain ? "本館" : "別館";
        int totalEco = ecoFloors.stream().mapToInt(f -> f.ecoCount).sum();

        LOGGER.info(String.format("--- %sECO割り振り開始（均等配分）: %d室 ---", buildingName, totalEco));

        // 現在の部屋数を取得
        Map<String, Integer> currentCounts = new HashMap<>();
        for (AdaptiveRoomOptimizer.StaffAssignment sa : assignments) {
            int count = isMain ? getMainBuildingRooms(sa) : getAnnexBuildingRooms(sa);
            currentCounts.put(sa.staff.name, count);
        }

        // PriorityQueueで最小負荷のスタッフに順次割り当て
        PriorityQueue<Map.Entry<String, Integer>> pq = new PriorityQueue<>(
                Comparator.comparingInt(Map.Entry::getValue));
        pq.addAll(currentCounts.entrySet());

        // 各フロアのECOを割り振り
        for (EcoFloorInfo ecoFloor : ecoFloors) {
            int remaining = ecoFloor.ecoCount;

            while (remaining > 0) {
                Map.Entry<String, Integer> minEntry = pq.poll();
                if (minEntry == null) break;

                String staffName = minEntry.getKey();

                // このスタッフにECOを1つ割り振り
                for (AdaptiveRoomOptimizer.StaffAssignment sa : assignments) {
                    if (sa.staff.name.equals(staffName)) {
                        addEcoToStaff(sa, ecoFloor.floorNumber, 1, isMain);
                        break;
                    }
                }

                remaining--;
                currentCounts.put(staffName, minEntry.getValue() + 1);
                pq.add(new AbstractMap.SimpleEntry<>(staffName, minEntry.getValue() + 1));
            }
        }

        // 結果をログ出力
        LOGGER.info(String.format("--- %sECO割り振り結果 ---", buildingName));
        for (AdaptiveRoomOptimizer.StaffAssignment sa : assignments) {
            int count = isMain ? getMainBuildingRooms(sa) : getAnnexBuildingRooms(sa);
            int ecoCount = isMain ? getMainBuildingEcoRooms(sa) : getAnnexBuildingEcoRooms(sa);
            LOGGER.info(String.format("  %s: %s=%d室（うちECO=%d室）",
                    sa.staff.name, buildingName, count, ecoCount));
        }
    }

    private static void addEcoToStaff(AdaptiveRoomOptimizer.StaffAssignment sa,
                                      int floorNumber, int ecoCount, boolean isMain) {
        Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> fm =
                isMain ? sa.mainBuildingAssignments : sa.annexBuildingAssignments;

        AdaptiveRoomOptimizer.RoomAllocation existing = fm.get(floorNumber);
        if (existing != null) {
            fm.put(floorNumber, new AdaptiveRoomOptimizer.RoomAllocation(
                    existing.roomCounts, existing.ecoRooms + ecoCount));
        } else {
            fm.put(floorNumber, new AdaptiveRoomOptimizer.RoomAllocation(new HashMap<>(), ecoCount));
        }
    }

    /**
     * ★補助メソッド: スタッフの本館部屋数を計算
     */
    private static int getMainBuildingRooms(AdaptiveRoomOptimizer.StaffAssignment sa) {
        int total = 0;
        for (AdaptiveRoomOptimizer.RoomAllocation allocation : sa.mainBuildingAssignments.values()) {
            total += allocation.roomCounts.values().stream().mapToInt(Integer::intValue).sum();
            total += allocation.ecoRooms;
        }
        return total;
    }

    /**
     * ★補助メソッド: スタッフの別館部屋数を計算
     */
    private static int getAnnexBuildingRooms(AdaptiveRoomOptimizer.StaffAssignment sa) {
        int total = 0;
        for (AdaptiveRoomOptimizer.RoomAllocation allocation : sa.annexBuildingAssignments.values()) {
            total += allocation.roomCounts.values().stream().mapToInt(Integer::intValue).sum();
            total += allocation.ecoRooms;
        }
        return total;
    }

    /**
     * ★補助メソッド: スタッフの本館ECO部屋数を計算
     */
    private static int getMainBuildingEcoRooms(AdaptiveRoomOptimizer.StaffAssignment sa) {
        return sa.mainBuildingAssignments.values().stream()
                .mapToInt(a -> a.ecoRooms)
                .sum();
    }

    /**
     * ★補助メソッド: スタッフの別館ECO部屋数を計算
     */
    private static int getAnnexBuildingEcoRooms(AdaptiveRoomOptimizer.StaffAssignment sa) {
        return sa.annexBuildingAssignments.values().stream()
                .mapToInt(a -> a.ecoRooms)
                .sum();
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

        if (config.roomDistribution != null) {
            NormalRoomDistributionDialog.StaffDistribution dist = config.roomDistribution.get(staffName);
            if (dist != null) {
                int mainR = dist.mainSingleAssignedRooms + dist.mainTwinAssignedRooms;
                int annexR = dist.annexSingleAssignedRooms + dist.annexTwinAssignedRooms;
                if (mainR > 0 && annexR > 0) maxFloors = 4;
            }
        }
        return maxFloors;
    }
}