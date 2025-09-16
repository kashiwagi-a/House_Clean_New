package org.example;

import java.util.*;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * 適応型清掃管理最適化システム（建物分離ポイントベース版）
 * 清掃条件.txtの要件を反映した実装
 * ★拡張: ポイント制限・建物指定・下限範囲対応版
 */
public class AdaptiveRoomOptimizer {

    private static final Logger LOGGER = Logger.getLogger(AdaptiveRoomOptimizer.class.getName());

    // 清掃条件の定数
    public static final int MAX_MAIN_BUILDING_ROOMS = 99;
    public static final int MAX_ANNEX_BUILDING_ROOMS = 99;
    public static final int BATH_CLEANING_REDUCTION = 4;
    public static final int BATH_DRAINING_REDUCTION = 5;
    public static final int BATH_CLEANING_STAFF_COUNT = 4;
    public static final double FLOOR_CROSSING_PENALTY = 0;
    public static final double BUILDING_CROSSING_PENALTY = 1.0;

    // ★修正: 部屋タイプ別の実質ポイント（ポイントベース計算用）
    private static final Map<String, Double> ROOM_POINTS = new HashMap<>() {{
        put("S", 1.0);
        put("D", 1.0);
        put("T", 1.67);
        put("FD", 2.0);
        put("ECO", 0.2);
    }};

    /**
     * 大浴場清掃タイプ
     */
    public enum BathCleaningType {
        NONE("なし", 0),
        NORMAL("大浴場清掃", BATH_CLEANING_REDUCTION),
        WITH_DRAINING("湯抜きあり", BATH_DRAINING_REDUCTION);

        public final String displayName;
        public final int reduction;

        BathCleaningType(String displayName, int reduction) {
            this.displayName = displayName;
            this.reduction = reduction;
        }
    }

    /**
     * スタッフの建物割当タイプ
     */
    public enum BuildingAssignment {
        MAIN_ONLY("本館のみ"),
        ANNEX_ONLY("別館のみ"),
        BOTH("両方");

        public final String displayName;

        BuildingAssignment(String displayName) {
            this.displayName = displayName;
        }
    }

    /**
     * 作業者タイプ列挙型
     */
    public enum WorkerType {
        NORMAL_DUTY("通常", 0),
        LIGHT_DUTY("軽減", 3);

        public final String displayName;
        public final int reduction;

        WorkerType(String displayName, int reduction) {
            this.displayName = displayName;
            this.reduction = reduction;
        }
    }

    /**
     * 負荷レベル列挙型
     */
    public enum LoadLevel {
        LIGHT("軽負荷", 8),
        NORMAL("通常負荷", 15),
        HEAVY("重負荷", 999);

        public final String displayName;
        private final int threshold;

        LoadLevel(String displayName, int threshold) {
            this.displayName = displayName;
            this.threshold = threshold;
        }

        public static LoadLevel fromAverage(double avgRooms) {
            if (avgRooms < LIGHT.threshold) return LIGHT;
            if (avgRooms < NORMAL.threshold) return NORMAL;
            return HEAVY;
        }
    }

    /**
     * 適応型ワーカータイプ
     */
    public enum AdaptiveWorkerType {
        SUPER_LIGHT("超軽減", 0.1),
        LIGHT("軽減", 0.1),
        MILD_LIGHT("やや軽減", 0.15),
        NORMAL("通常", 0.65);

        public final String displayName;
        private final double ratio;

        AdaptiveWorkerType(String displayName, double ratio) {
            this.displayName = displayName;
            this.ratio = ratio;
        }
    }

    /**
     * 拡張スタッフ情報（前日の割当情報を含む）
     */
    public static class ExtendedStaffInfo {
        public final FileProcessor.Staff staff;
        public final BathCleaningType bathCleaning;
        public final BuildingAssignment preferredBuilding;
        public final List<Integer> previousFloors;
        public final boolean isConsecutiveDay;

        public ExtendedStaffInfo(FileProcessor.Staff staff, BathCleaningType bathCleaning,
                                 BuildingAssignment preferredBuilding,
                                 List<Integer> previousFloors, boolean isConsecutiveDay) {
            this.staff = staff;
            this.bathCleaning = bathCleaning;
            this.preferredBuilding = preferredBuilding;
            this.previousFloors = previousFloors != null ? new ArrayList<>(previousFloors) : new ArrayList<>();
            this.isConsecutiveDay = isConsecutiveDay;
        }

        public ExtendedStaffInfo(FileProcessor.Staff staff) {
            this(staff, BathCleaningType.NONE, BuildingAssignment.BOTH, new ArrayList<>(), false);
        }
    }

    /**
     * ★拡張: ポイント制限情報クラス
     */
    public static class PointConstraint {
        public final String staffId;
        public final ConstraintType type;
        public final BuildingAssignment buildingAssignment;
        public final double upperLimit;
        public final double lowerMinLimit;
        public final double lowerMaxLimit;

        public PointConstraint(String staffId, ConstraintType type, BuildingAssignment buildingAssignment,
                               double upperLimit, double lowerMinLimit, double lowerMaxLimit) {
            this.staffId = staffId;
            this.type = type;
            this.buildingAssignment = buildingAssignment;
            this.upperLimit = upperLimit;
            this.lowerMinLimit = lowerMinLimit;
            this.lowerMaxLimit = lowerMaxLimit;
        }

        public enum ConstraintType {
            NONE, UPPER_LIMIT, LOWER_RANGE
        }

        public boolean hasUpperLimit() {
            return type == ConstraintType.UPPER_LIMIT;
        }

        public boolean hasLowerRange() {
            return type == ConstraintType.LOWER_RANGE;
        }

        public boolean isMainBuildingOnly() {
            return buildingAssignment == BuildingAssignment.MAIN_ONLY;
        }

        public boolean isAnnexBuildingOnly() {
            return buildingAssignment == BuildingAssignment.ANNEX_ONLY;
        }
    }

    /**
     * 建物別データクラス
     */
    public static class BuildingData {
        public final List<FloorInfo> mainFloors;
        public final List<FloorInfo> annexFloors;
        public final double mainTotalPoints;
        public final double annexTotalPoints;
        public final int mainRoomCount;
        public final int annexRoomCount;

        public BuildingData(List<FloorInfo> mainFloors, List<FloorInfo> annexFloors) {
            this.mainFloors = mainFloors;
            this.annexFloors = annexFloors;
            this.mainTotalPoints = mainFloors.stream().mapToDouble(FloorInfo::getTotalPoints).sum();
            this.annexTotalPoints = annexFloors.stream().mapToDouble(FloorInfo::getTotalPoints).sum();
            this.mainRoomCount = mainFloors.stream().mapToInt(FloorInfo::getTotalRooms).sum();
            this.annexRoomCount = annexFloors.stream().mapToInt(FloorInfo::getTotalRooms).sum();
        }
    }

    /**
     * スタッフ配分結果クラス
     */
    public static class StaffAllocation {
        public final int mainStaff;
        public final int annexStaff;
        public final boolean hasCrossBuilding;
        public final String method;
        public final double mainAvgPoints;
        public final double annexAvgPoints;
        public final double pointDifference;

        public StaffAllocation(int mainStaff, int annexStaff, boolean hasCrossBuilding,
                               String method, double mainPoints, double annexPoints) {
            this.mainStaff = mainStaff;
            this.annexStaff = annexStaff;
            this.hasCrossBuilding = hasCrossBuilding;
            this.method = method;
            this.mainAvgPoints = mainStaff > 0 ? mainPoints / mainStaff : 0;
            this.annexAvgPoints = annexStaff > 0 ? annexPoints / annexStaff : 0;
            this.pointDifference = mainAvgPoints - annexAvgPoints;
        }
    }

    /**
     * ★大幅拡張: 適応型負荷設定（ポイント制限統合版）
     */
    public static class AdaptiveLoadConfig {
        public final List<FileProcessor.Staff> availableStaff;
        public final List<ExtendedStaffInfo> extendedStaffInfo;
        public final LoadLevel loadLevel;
        public final Map<AdaptiveWorkerType, Integer> reductionMap;
        public final Map<AdaptiveWorkerType, Integer> targetRoomsMap;
        public final Map<AdaptiveWorkerType, List<FileProcessor.Staff>> staffByType;
        public final Map<String, WorkerType> workerTypes;
        public final Map<String, BathCleaningType> bathCleaningAssignments;

        // ★拡張: ポイント制限関連
        public final Map<String, PointConstraint> pointConstraints;
        public final Map<String, PointConstraint> upperLimitConstraints;
        public final Map<String, PointConstraint> lowerRangeConstraints;

        private AdaptiveLoadConfig(
                List<FileProcessor.Staff> availableStaff,
                List<ExtendedStaffInfo> extendedStaffInfo,
                LoadLevel loadLevel,
                Map<AdaptiveWorkerType, Integer> reductionMap,
                Map<AdaptiveWorkerType, Integer> targetRoomsMap,
                Map<AdaptiveWorkerType, List<FileProcessor.Staff>> staffByType,
                Map<String, BathCleaningType> bathCleaningAssignments,
                Map<String, PointConstraint> pointConstraints) {

            this.availableStaff = new ArrayList<>(availableStaff);
            this.extendedStaffInfo = new ArrayList<>(extendedStaffInfo);
            this.loadLevel = loadLevel;
            this.reductionMap = new HashMap<>(reductionMap);
            this.targetRoomsMap = new HashMap<>(targetRoomsMap);
            this.staffByType = new HashMap<>(staffByType);
            this.workerTypes = convertToWorkerTypeMap(staffByType);
            this.bathCleaningAssignments = new HashMap<>(bathCleaningAssignments);

            // ★拡張: ポイント制限情報の処理
            this.pointConstraints = pointConstraints != null ? new HashMap<>(pointConstraints) : new HashMap<>();
            this.upperLimitConstraints = this.pointConstraints.entrySet().stream()
                    .filter(entry -> entry.getValue().hasUpperLimit())
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            this.lowerRangeConstraints = this.pointConstraints.entrySet().stream()
                    .filter(entry -> entry.getValue().hasLowerRange())
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        private static Map<String, WorkerType> convertToWorkerTypeMap(
                Map<AdaptiveWorkerType, List<FileProcessor.Staff>> staffByType) {
            Map<String, WorkerType> result = new HashMap<>();
            staffByType.forEach((type, staffList) -> {
                WorkerType workerType = type == AdaptiveWorkerType.NORMAL ?
                        WorkerType.NORMAL_DUTY : WorkerType.LIGHT_DUTY;
                staffList.forEach(staff -> result.put(staff.id, workerType));
            });
            return result;
        }

        /**
         * ★拡張: 個別目標ポイント数を取得（制限考慮版）
         */
        public double getIndividualTargetPoints(String staffId, double baseTarget) {
            LOGGER.fine(String.format("ポイント制限計算: %s, ベース: %.1fP", staffId, baseTarget));

            // 大浴場清掃による削減
            BathCleaningType bathType = bathCleaningAssignments.get(staffId);
            double bathReduction = 0;
            if (bathType != null && bathType != BathCleaningType.NONE) {
                bathReduction = bathType.reduction;
                LOGGER.fine(String.format("大浴場清掃: %s -%.1fP", staffId, bathReduction));
            }

            double target = baseTarget - bathReduction;

            // ポイント制限の適用
            PointConstraint constraint = pointConstraints.get(staffId);
            if (constraint != null) {
                if (constraint.hasUpperLimit()) {
                    target = Math.min(target, constraint.upperLimit);
                    LOGGER.fine(String.format("上限適用: %s %.1fP", staffId, constraint.upperLimit));
                } else if (constraint.hasLowerRange()) {
                    // 下限範囲は後で処理されるため、ここでは最小値のみ適用
                    target = Math.max(target, constraint.lowerMinLimit);
                    LOGGER.fine(String.format("下限最小適用: %s %.1fP", staffId, constraint.lowerMinLimit));
                }
            }

            // 最低3ポイントは保証
            target = Math.max(3.0, target);

            return target;
        }

        /**
         * ★新規: 建物制限チェック
         */
        public boolean canAssignToBuilding(String staffId, boolean isMainBuilding) {
            PointConstraint constraint = pointConstraints.get(staffId);
            if (constraint == null) return true;

            switch (constraint.buildingAssignment) {
                case MAIN_ONLY:
                    return isMainBuilding;
                case ANNEX_ONLY:
                    return !isMainBuilding;
                default:
                    return true;
            }
        }

        /**
         * ★新規: 上限制限スタッフの取得
         */
        public List<String> getUpperLimitStaffIds() {
            return new ArrayList<>(upperLimitConstraints.keySet());
        }

        /**
         * ★新規: 下限範囲制限スタッフの取得
         */
        public List<String> getLowerRangeStaffIds() {
            return new ArrayList<>(lowerRangeConstraints.keySet());
        }

        /**
         * ★新規: ポイント制限対応版設定作成メソッド
         */
        public static AdaptiveLoadConfig createAdaptiveConfigWithPointConstraints(
                List<FileProcessor.Staff> availableStaff, int totalRooms,
                int mainBuildingRooms, int annexBuildingRooms,
                BathCleaningType bathType,
                List<RoomAssignmentApplication.StaffPointConstraint> staffConstraints) {

            LOGGER.info("=== 適応型設定作成（ポイント制限機能付き） ===");
            LOGGER.info(String.format("総部屋数: %d, 本館: %d, 別館: %d",
                    totalRooms, mainBuildingRooms, annexBuildingRooms));

            // ポイント制限情報の変換
            Map<String, PointConstraint> pointConstraints = new HashMap<>();
            if (staffConstraints != null) {
                LOGGER.info("ポイント制限設定:");
                for (RoomAssignmentApplication.StaffPointConstraint staffConstraint : staffConstraints) {
                    PointConstraint.ConstraintType cType;
                    switch (staffConstraint.constraintType) {
                        case UPPER_LIMIT:
                            cType = PointConstraint.ConstraintType.UPPER_LIMIT;
                            break;
                        case LOWER_RANGE:
                            cType = PointConstraint.ConstraintType.LOWER_RANGE;
                            break;
                        default:
                            cType = PointConstraint.ConstraintType.NONE;
                    }

                    BuildingAssignment bAssignment;
                    switch (staffConstraint.buildingAssignment) {
                        case MAIN_ONLY:
                            bAssignment = BuildingAssignment.MAIN_ONLY;
                            break;
                        case ANNEX_ONLY:
                            bAssignment = BuildingAssignment.ANNEX_ONLY;
                            break;
                        default:
                            bAssignment = BuildingAssignment.BOTH;
                    }

                    PointConstraint constraint = new PointConstraint(
                            staffConstraint.staffId, cType, bAssignment,
                            staffConstraint.upperLimit,
                            staffConstraint.lowerMinLimit,
                            staffConstraint.lowerMaxLimit);

                    pointConstraints.put(staffConstraint.staffId, constraint);

                    LOGGER.info(String.format("  %s: %s, %s",
                            staffConstraint.staffName,
                            staffConstraint.getConstraintDisplay(),
                            staffConstraint.buildingAssignment.displayName));
                }
            }

            // 拡張スタッフ情報の生成
            List<ExtendedStaffInfo> extendedInfo = new ArrayList<>();
            Map<String, BathCleaningType> bathAssignments = new HashMap<>();

            // 大浴場清掃担当者の設定（最初の4人を本館担当の大浴場清掃に）
            int bathStaffAssigned = 0;
            final BathCleaningType finalBathType = bathType;
            final int maxBathStaff = BATH_CLEANING_STAFF_COUNT;

            for (int i = 0; i < availableStaff.size(); i++) {
                FileProcessor.Staff staff = availableStaff.get(i);
                BathCleaningType staffBathType = BathCleaningType.NONE;
                BuildingAssignment building = BuildingAssignment.BOTH;

                // 大浴場清掃担当者の割り当て（4人必要）
                if (bathStaffAssigned < maxBathStaff && finalBathType != BathCleaningType.NONE) {
                    // ポイント制限で本館のみ指定されていない場合のみ大浴場担当に
                    PointConstraint constraint = pointConstraints.get(staff.id);
                    if (constraint == null || constraint.buildingAssignment != BuildingAssignment.ANNEX_ONLY) {
                        staffBathType = finalBathType;
                        building = BuildingAssignment.MAIN_ONLY; // 大浴場担当は本館のみ
                        bathStaffAssigned++;
                    }
                }

                // 個別制限がある場合は優先
                PointConstraint constraint = pointConstraints.get(staff.id);
                if (constraint != null && staffBathType == BathCleaningType.NONE) {
                    building = constraint.buildingAssignment;
                }

                extendedInfo.add(new ExtendedStaffInfo(staff, staffBathType,
                        building, new ArrayList<>(), false));
                bathAssignments.put(staff.id, staffBathType);
            }

            // 本館と別館の実質的な必要部屋数を計算
            final int finalBathStaffAssigned = bathStaffAssigned;
            int bathReductionTotal = finalBathStaffAssigned * finalBathType.reduction;
            int mainEffectiveRooms = mainBuildingRooms + bathReductionTotal;

            // スタッフの建物別配分を決定
            int mainStaffCount = 12; // 本館担当者数（大浴場4人含む）
            int annexStaffCount = availableStaff.size() - mainStaffCount;

            LOGGER.info(String.format("スタッフ配分: 本館%d人（大浴場%d人）、別館%d人",
                    mainStaffCount, finalBathStaffAssigned, annexStaffCount));
            LOGGER.info(String.format("実質清掃必要数: 本館%d室（+大浴場分%d室）、別館%d室",
                    mainBuildingRooms, bathReductionTotal, annexBuildingRooms));

            double avgRooms = (double) totalRooms / availableStaff.size();
            LoadLevel level = LoadLevel.fromAverage(avgRooms);

            LOGGER.info(String.format("負荷レベル判定: 平均%.1f室/人 → %s",
                    avgRooms, level.displayName));

            Map<AdaptiveWorkerType, Integer> reductionMap = new HashMap<>();
            switch (level) {
                case LIGHT:
                    reductionMap.put(AdaptiveWorkerType.SUPER_LIGHT, 2);
                    reductionMap.put(AdaptiveWorkerType.LIGHT, 1);
                    reductionMap.put(AdaptiveWorkerType.MILD_LIGHT, 1);
                    reductionMap.put(AdaptiveWorkerType.NORMAL, 0);
                    break;
                case NORMAL:
                    reductionMap.put(AdaptiveWorkerType.SUPER_LIGHT, 3);
                    reductionMap.put(AdaptiveWorkerType.LIGHT, 2);
                    reductionMap.put(AdaptiveWorkerType.MILD_LIGHT, 1);
                    reductionMap.put(AdaptiveWorkerType.NORMAL, 0);
                    break;
                case HEAVY:
                    reductionMap.put(AdaptiveWorkerType.SUPER_LIGHT, 4);
                    reductionMap.put(AdaptiveWorkerType.LIGHT, 3);
                    reductionMap.put(AdaptiveWorkerType.MILD_LIGHT, 2);
                    reductionMap.put(AdaptiveWorkerType.NORMAL, 0);
                    break;
            }

            Map<AdaptiveWorkerType, List<FileProcessor.Staff>> staffByType =
                    assignStaffToTypes(availableStaff, level);

            Map<AdaptiveWorkerType, Integer> targetRoomsMap =
                    calculateTargetRooms(totalRooms, staffByType, reductionMap, avgRooms,
                            bathAssignments, mainEffectiveRooms, mainStaffCount, annexStaffCount);

            return new AdaptiveLoadConfig(availableStaff, extendedInfo, level,
                    reductionMap, targetRoomsMap, staffByType, bathAssignments, pointConstraints);
        }

        // 既存メソッド（後方互換性のため）
        public static AdaptiveLoadConfig createAdaptiveConfig(
                List<FileProcessor.Staff> availableStaff, int totalRooms,
                int mainBuildingRooms, int annexBuildingRooms,
                BathCleaningType bathType, Map<String, Integer> roomLimits) {
            // 旧形式から新形式に変換
            List<RoomAssignmentApplication.StaffPointConstraint> constraints = new ArrayList<>();
            // roomLimitsは無視（ポイント制限に移行）
            return createAdaptiveConfigWithPointConstraints(
                    availableStaff, totalRooms, mainBuildingRooms, annexBuildingRooms,
                    bathType, constraints);
        }

        public static AdaptiveLoadConfig createAdaptiveConfig(
                List<FileProcessor.Staff> availableStaff, int totalRooms) {
            int mainRooms = (int)(totalRooms * 0.55);
            int annexRooms = totalRooms - mainRooms;
            return createAdaptiveConfigWithPointConstraints(availableStaff, totalRooms, mainRooms, annexRooms,
                    BathCleaningType.NORMAL, new ArrayList<>());
        }

        private static Map<AdaptiveWorkerType, List<FileProcessor.Staff>> assignStaffToTypes(
                List<FileProcessor.Staff> staff, LoadLevel level) {

            Map<AdaptiveWorkerType, List<FileProcessor.Staff>> result = new HashMap<>();
            List<FileProcessor.Staff> shuffled = new ArrayList<>(staff);

            int index = 0;
            for (AdaptiveWorkerType type : AdaptiveWorkerType.values()) {
                double adjustedRatio = type.ratio;
                if (level == LoadLevel.HEAVY && type != AdaptiveWorkerType.NORMAL) {
                    adjustedRatio *= 1.2;
                } else if (level == LoadLevel.LIGHT && type != AdaptiveWorkerType.NORMAL) {
                    adjustedRatio *= 0.7;
                }

                int count = Math.max(1, (int) Math.round(staff.size() * adjustedRatio));
                if (type == AdaptiveWorkerType.NORMAL) {
                    count = staff.size() - index;
                }

                List<FileProcessor.Staff> typeStaff = new ArrayList<>();
                for (int i = 0; i < count && index < staff.size(); i++, index++) {
                    typeStaff.add(shuffled.get(index));
                }

                if (!typeStaff.isEmpty()) {
                    result.put(type, typeStaff);
                }
            }
            return result;
        }

        private static Map<AdaptiveWorkerType, Integer> calculateTargetRooms(
                int totalRooms,
                Map<AdaptiveWorkerType, List<FileProcessor.Staff>> staffByType,
                Map<AdaptiveWorkerType, Integer> reductionMap,
                double avgRooms,
                Map<String, BathCleaningType> bathAssignments,
                int mainEffectiveRooms,
                int mainStaffCount,
                int annexStaffCount) {

            Map<AdaptiveWorkerType, Integer> targetMap = new HashMap<>();

            double avgMainRooms = (double) mainEffectiveRooms / mainStaffCount;
            double avgAnnexRooms = (double) (totalRooms - mainEffectiveRooms +
                    BATH_CLEANING_STAFF_COUNT * BATH_CLEANING_REDUCTION) / annexStaffCount;

            final int baseTarget = (int) Math.floor(avgRooms);
            int totalReduction = 0;
            int normalStaffCount = 0;

            int bathReduction = 0;
            for (BathCleaningType bathType : bathAssignments.values()) {
                if (bathType != BathCleaningType.NONE) {
                    bathReduction += bathType.reduction;
                }
            }

            for (Map.Entry<AdaptiveWorkerType, List<FileProcessor.Staff>> entry : staffByType.entrySet()) {
                AdaptiveWorkerType type = entry.getKey();
                int staffCount = entry.getValue().size();
                int reduction = reductionMap.get(type);

                if (type == AdaptiveWorkerType.NORMAL) {
                    normalStaffCount = staffCount;
                } else {
                    totalReduction += staffCount * reduction;
                }

                int individualTarget = Math.max(3, baseTarget - reduction);
                targetMap.put(type, individualTarget);
            }

            if (normalStaffCount > 0) {
                int additionalPerNormal = (int) Math.ceil((double) totalReduction / normalStaffCount);
                int normalTarget = targetMap.get(AdaptiveWorkerType.NORMAL) + additionalPerNormal;
                targetMap.put(AdaptiveWorkerType.NORMAL, normalTarget);
            }

            return targetMap;
        }

        public AdaptiveWorkerType getAdaptiveWorkerType(String staffId) {
            for (Map.Entry<AdaptiveWorkerType, List<FileProcessor.Staff>> entry : staffByType.entrySet()) {
                if (entry.getValue().stream().anyMatch(s -> s.id.equals(staffId))) {
                    return entry.getKey();
                }
            }
            return AdaptiveWorkerType.NORMAL;
        }
    }

    /**
     * フロア情報クラス
     */
    public static class FloorInfo {
        public final int floorNumber;
        public final Map<String, Integer> roomCounts;
        public final int ecoRooms;
        public final boolean isMainBuilding;

        public FloorInfo(int floorNumber, Map<String, Integer> roomCounts, int ecoRooms, boolean isMainBuilding) {
            this.floorNumber = floorNumber;
            this.roomCounts = new HashMap<>(roomCounts);
            if (!this.roomCounts.containsKey("D")) {
                this.roomCounts.put("D", 0);
            }
            this.ecoRooms = ecoRooms;
            this.isMainBuilding = isMainBuilding;
        }

        public FloorInfo(int floorNumber, Map<String, Integer> roomCounts, int ecoRooms) {
            this(floorNumber, roomCounts, ecoRooms, true);
        }

        public int getTotalRooms() {
            return roomCounts.values().stream().mapToInt(Integer::intValue).sum() + ecoRooms;
        }

        public double getTotalPoints() {
            double points = 0;
            for (Map.Entry<String, Integer> entry : roomCounts.entrySet()) {
                points += entry.getValue() * ROOM_POINTS.getOrDefault(entry.getKey(), 1.0);
            }
            points += ecoRooms * ROOM_POINTS.get("ECO");
            return points;
        }
    }

    /**
     * 部屋配分情報
     */
    public static class RoomAllocation {
        public final Map<String, Integer> roomCounts;
        public final int ecoRooms;

        public RoomAllocation(Map<String, Integer> roomCounts, int ecoRooms) {
            this.roomCounts = new HashMap<>(roomCounts);
            if (!this.roomCounts.containsKey("D")) {
                this.roomCounts.put("D", 0);
            }
            this.ecoRooms = ecoRooms;
        }

        public int getTotalRooms() {
            return roomCounts.values().stream().mapToInt(Integer::intValue).sum() + ecoRooms;
        }

        public double getTotalPoints() {
            double points = 0;
            for (Map.Entry<String, Integer> entry : roomCounts.entrySet()) {
                points += entry.getValue() * ROOM_POINTS.getOrDefault(entry.getKey(), 1.0);
            }
            points += ecoRooms * ROOM_POINTS.get("ECO");
            return points;
        }

        public boolean isEmpty() {
            return getTotalRooms() == 0;
        }
    }

    /**
     * スタッフ配分結果（拡張版）
     */
    public static class StaffAssignment {
        public final FileProcessor.Staff staff;
        public final WorkerType workerType;
        public final List<Integer> floors;
        public final Map<Integer, RoomAllocation> roomsByFloor;
        public final int totalRooms;
        public final double totalPoints;
        public final double movementPenalty;
        public final double adjustedScore;
        public final boolean hasMainBuilding;
        public final boolean hasAnnexBuilding;

        public StaffAssignment(FileProcessor.Staff staff, WorkerType workerType,
                               List<Integer> floors, Map<Integer, RoomAllocation> roomsByFloor) {
            this.staff = staff;
            this.workerType = workerType;
            this.floors = new ArrayList<>(floors);
            this.roomsByFloor = new HashMap<>(roomsByFloor);
            this.totalRooms = calculateTotalRooms();
            this.totalPoints = calculateTotalPoints();
            this.movementPenalty = calculateMovementPenalty();
            this.adjustedScore = totalPoints + movementPenalty;

            this.hasMainBuilding = floors.stream().anyMatch(f -> f <= 10);
            this.hasAnnexBuilding = floors.stream().anyMatch(f -> f > 10);
        }

        private int calculateTotalRooms() {
            return roomsByFloor.values().stream()
                    .mapToInt(RoomAllocation::getTotalRooms)
                    .sum();
        }

        private double calculateTotalPoints() {
            return roomsByFloor.values().stream()
                    .mapToDouble(RoomAllocation::getTotalPoints)
                    .sum();
        }

        private double calculateMovementPenalty() {
            double penalty = 0;

            if (floors.size() > 2) {
                penalty += (floors.size() - 2) * 10.0;
            }

            if (hasMainBuilding && hasAnnexBuilding) {
                penalty += BUILDING_CROSSING_PENALTY;
            }

            return penalty;
        }

        public String getDetailedDescription() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%s: %d部屋 (基本%.1fポイント + 移動%.1fポイント = %.1fポイント) - ",
                    staff.name, totalRooms, totalPoints, movementPenalty, adjustedScore));

            for (int i = 0; i < floors.size(); i++) {
                if (i > 0) sb.append(", ");
                int floor = floors.get(i);
                RoomAllocation allocation = roomsByFloor.get(floor);
                sb.append(String.format("%d階(", floor));

                List<String> parts = new ArrayList<>();
                if (allocation.roomCounts.getOrDefault("S", 0) > 0) {
                    parts.add(String.format("S:%d", allocation.roomCounts.get("S")));
                }
                if (allocation.roomCounts.getOrDefault("D", 0) > 0) {
                    parts.add(String.format("D:%d", allocation.roomCounts.get("D")));
                }
                if (allocation.roomCounts.getOrDefault("T", 0) > 0) {
                    parts.add(String.format("T:%d", allocation.roomCounts.get("T")));
                }
                if (allocation.roomCounts.getOrDefault("FD", 0) > 0) {
                    parts.add(String.format("FD:%d", allocation.roomCounts.get("FD")));
                }
                if (allocation.ecoRooms > 0) {
                    parts.add(String.format("エコ:%d", allocation.ecoRooms));
                }

                sb.append(String.join(" ", parts));
                sb.append(")");
            }

            if (hasMainBuilding && hasAnnexBuilding) {
                sb.append(" [館跨ぎ]");
            }

            return sb.toString();
        }
    }

    /**
     * 最適化結果クラス
     */
    public static class OptimizationResult {
        public final List<StaffAssignment> assignments;
        public final AdaptiveLoadConfig config;
        public final LocalDate targetDate;
        public final double pointDifference;
        public final double adjustedScoreDifference;

        public OptimizationResult(List<StaffAssignment> assignments,
                                  AdaptiveLoadConfig config, LocalDate targetDate) {
            this.assignments = assignments;
            this.config = config;
            this.targetDate = targetDate;
            this.pointDifference = calculatePointDifference();
            this.adjustedScoreDifference = calculateAdjustedScoreDifference();
        }

        private double calculatePointDifference() {
            double minPoints = assignments.stream().mapToDouble(a -> a.totalPoints).min().orElse(0);
            double maxPoints = assignments.stream().mapToDouble(a -> a.totalPoints).max().orElse(0);
            return maxPoints - minPoints;
        }

        private double calculateAdjustedScoreDifference() {
            double minScore = assignments.stream().mapToDouble(a -> a.adjustedScore).min().orElse(0);
            double maxScore = assignments.stream().mapToDouble(a -> a.adjustedScore).max().orElse(0);
            return maxScore - minScore;
        }

        public void printDetailedSummary() {
            System.out.println("\n=== 最適化結果（ポイント制限適用版） ===");
            System.out.printf("対象日: %s\n",
                    targetDate.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")));
            System.out.printf("出勤スタッフ数: %d人\n", config.availableStaff.size());

            // 大浴場担当者の表示
            System.out.println("\n【大浴場清掃担当】");
            int bathCount = 0;
            for (Map.Entry<String, BathCleaningType> entry : config.bathCleaningAssignments.entrySet()) {
                if (entry.getValue() != BathCleaningType.NONE) {
                    System.out.printf("  %s: %s (-%.1fポイント)\n",
                            entry.getKey(), entry.getValue().displayName, (double)entry.getValue().reduction);
                    bathCount++;
                }
            }
            System.out.printf("  大浴場清掃担当者数: %d人（本館のみ担当）\n", bathCount);

            // ★拡張: ポイント制限適用状況
            if (!config.pointConstraints.isEmpty()) {
                System.out.println("\n【ポイント制限適用状況】");
                for (StaffAssignment assignment : assignments) {
                    String staffId = assignment.staff.id;
                    PointConstraint constraint = config.pointConstraints.get(staffId);
                    if (constraint != null) {
                        double actual = assignment.totalPoints;
                        String status = "";

                        if (constraint.hasUpperLimit()) {
                            status = actual <= constraint.upperLimit ? "✓" : "⚠ 超過";
                            System.out.printf("  %s: 上限%.1fP → 実際%.1fP %s\n",
                                    assignment.staff.name, constraint.upperLimit, actual, status);
                        } else if (constraint.hasLowerRange()) {
                            status = actual >= constraint.lowerMinLimit && actual <= constraint.lowerMaxLimit ?
                                    "✓" : "⚠ 範囲外";
                            System.out.printf("  %s: 下限%.1f〜%.1fP → 実際%.1fP %s\n",
                                    assignment.staff.name, constraint.lowerMinLimit,
                                    constraint.lowerMaxLimit, actual, status);
                        }

                        if (constraint.buildingAssignment != BuildingAssignment.BOTH) {
                            System.out.printf("    建物指定: %s\n", constraint.buildingAssignment.displayName);
                        }
                    }
                }
            }

            System.out.println("\n【個別配分結果】");
            assignments.forEach(a -> System.out.println(a.getDetailedDescription()));

            // 建物別部屋数チェック
            System.out.println("\n【建物別集計】");
            int mainBuildingTotal = 0;
            int annexBuildingTotal = 0;
            for (StaffAssignment assignment : assignments) {
                if (assignment.hasMainBuilding) {
                    mainBuildingTotal += assignment.totalRooms;
                }
                if (assignment.hasAnnexBuilding) {
                    annexBuildingTotal += assignment.totalRooms;
                }
            }
            System.out.printf("本館合計: %d室 (上限%d室)\n", mainBuildingTotal, MAX_MAIN_BUILDING_ROOMS);
            System.out.printf("別館合計: %d室 (上限%d室)\n", annexBuildingTotal, MAX_ANNEX_BUILDING_ROOMS);
            System.out.printf("差: %d室\n", Math.abs(mainBuildingTotal - annexBuildingTotal));

            // ツイン部屋の分布
            System.out.println("\n【ツイン部屋分布】");
            Map<String, Integer> twinDistribution = new HashMap<>();
            for (StaffAssignment assignment : assignments) {
                int twinCount = 0;
                for (RoomAllocation allocation : assignment.roomsByFloor.values()) {
                    twinCount += allocation.roomCounts.getOrDefault("T", 0);
                }
                twinDistribution.put(assignment.staff.name, twinCount);
            }
            twinDistribution.forEach((name, count) ->
                    System.out.printf("  %s: %d室\n", name, count));

            System.out.println("\n【統計サマリー】");
            double[] points = assignments.stream()
                    .mapToDouble(a -> a.totalPoints).toArray();
            double[] adjustedScores = assignments.stream()
                    .mapToDouble(a -> a.adjustedScore).toArray();

            System.out.printf("基本点数範囲: %.2f ～ %.2f (差: %.2fポイント)\n",
                    Arrays.stream(points).min().orElse(0),
                    Arrays.stream(points).max().orElse(0),
                    pointDifference);
            System.out.printf("調整後スコア範囲: %.2f ～ %.2f (差: %.2fポイント)\n",
                    Arrays.stream(adjustedScores).min().orElse(0),
                    Arrays.stream(adjustedScores).max().orElse(0),
                    adjustedScoreDifference);

            System.out.println("\n【作業者タイプ別統計】");
            Map<WorkerType, List<StaffAssignment>> byType = new HashMap<>();
            for (StaffAssignment assignment : assignments) {
                byType.computeIfAbsent(assignment.workerType, k -> new ArrayList<>())
                        .add(assignment);
            }

            byType.forEach((type, list) -> {
                double avgRooms = list.stream().mapToInt(a -> a.totalRooms).average().orElse(0);
                double avgPoints = list.stream().mapToDouble(a -> a.adjustedScore).average().orElse(0);
                System.out.printf("%s作業者: %d人, 平均%.1f室, 平均%.1fポイント\n",
                        type.displayName, list.size(), avgRooms, avgPoints);
            });

            System.out.println("\n【最適化評価】");
            if (adjustedScoreDifference <= 3.0) {
                System.out.println("✅ 優秀：バランスの取れた配分です");
            } else if (adjustedScoreDifference <= 4.0) {
                System.out.println("⭕ 良好：概ね公平な配分です");
            } else {
                System.out.println("⚠️ 要改善：スタッフ間の負荷差が大きいです");
            }
        }
    }

    /**
     * ★大幅改善版: 建物分離型ポイントベース最適化エンジン
     */
    public static class AdaptiveOptimizer {
        private final List<FloorInfo> floors;
        private final AdaptiveLoadConfig adaptiveConfig;

        public AdaptiveOptimizer(List<FloorInfo> floors, AdaptiveLoadConfig config) {
            this.floors = new ArrayList<>(floors);
            this.adaptiveConfig = config;
        }

        public OptimizationResult optimize(LocalDate targetDate) {
            System.out.println("=== ポイント制限対応最適化開始 ===");
            System.out.printf("対象日: %s\n", targetDate.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")));
            System.out.printf("負荷レベル: %s\n", adaptiveConfig.loadLevel.displayName);

            // 建物別データ分離
            BuildingData buildingData = separateBuildingData();

            System.out.printf("\n【建物別集計】\n");
            System.out.printf("本館: %.2fポイント (%d室)\n", buildingData.mainTotalPoints, buildingData.mainRoomCount);
            System.out.printf("別館: %.2fポイント (%d室)\n", buildingData.annexTotalPoints, buildingData.annexRoomCount);
            System.out.printf("総計: %.2fポイント (%d室)\n",
                    buildingData.mainTotalPoints + buildingData.annexTotalPoints,
                    buildingData.mainRoomCount + buildingData.annexRoomCount);

            // ★拡張: ポイント制限対応最適化実行
            PointConstraintOptimizer optimizer = new PointConstraintOptimizer(buildingData, adaptiveConfig);
            List<StaffAssignment> result = optimizer.optimizeWithConstraints();

            return new OptimizationResult(result, adaptiveConfig, targetDate);
        }

        /**
         * 建物別データ分離
         */
        private BuildingData separateBuildingData() {
            List<FloorInfo> mainFloors = new ArrayList<>();
            List<FloorInfo> annexFloors = new ArrayList<>();

            for (FloorInfo floor : floors) {
                if (floor.isMainBuilding) {
                    mainFloors.add(floor);
                } else {
                    annexFloors.add(floor);
                }
            }

            return new BuildingData(mainFloors, annexFloors);
        }
    }

    /**
     * ★新規追加: ポイント制限対応最適化器
     */
    public static class PointConstraintOptimizer {
        private final BuildingData buildingData;
        private final AdaptiveLoadConfig config;

        public PointConstraintOptimizer(BuildingData buildingData, AdaptiveLoadConfig config) {
            this.buildingData = buildingData;
            this.config = config;
        }

        /**
         * ★新規: 制約付き最適化（上限→通常→下限の順で処理）
         */
        public List<StaffAssignment> optimizeWithConstraints() {
            System.out.println("\n=== ポイント制限適用最適化実行 ===");

            List<StaffAssignment> assignments = new ArrayList<>();
            Map<Integer, RoomAllocation> remainingMainRooms = initializeRemainingRooms(buildingData.mainFloors);
            Map<Integer, RoomAllocation> remainingAnnexRooms = initializeRemainingRooms(buildingData.annexFloors);

            // Phase 1: 上限制限スタッフを先に処理
            List<String> upperLimitStaff = config.getUpperLimitStaffIds();
            if (!upperLimitStaff.isEmpty()) {
                System.out.println("\n【Phase 1: 上限制限スタッフ処理】");
                for (String staffId : upperLimitStaff) {
                    FileProcessor.Staff staff = findStaffById(staffId);
                    if (staff != null) {
                        StaffAssignment assignment = assignWithUpperLimit(staff,
                                remainingMainRooms, remainingAnnexRooms);
                        assignments.add(assignment);
                        System.out.printf("  %s: %.1fP (%d室) - 上限制限適用\n",
                                staff.name, assignment.totalPoints, assignment.totalRooms);
                    }
                }
            }

            // Phase 2: 通常スタッフ処理
            List<String> processedStaffIds = assignments.stream()
                    .map(a -> a.staff.id).collect(Collectors.toList());
            List<FileProcessor.Staff> normalStaff = config.availableStaff.stream()
                    .filter(s -> !processedStaffIds.contains(s.id) &&
                            !config.getLowerRangeStaffIds().contains(s.id))
                    .collect(Collectors.toList());

            if (!normalStaff.isEmpty()) {
                System.out.println("\n【Phase 2: 通常スタッフ処理】");
                double remainingMainPoints = calculateRemainingPoints(remainingMainRooms);
                double remainingAnnexPoints = calculateRemainingPoints(remainingAnnexRooms);
                double avgPointsPerStaff = (remainingMainPoints + remainingAnnexPoints) /
                        (normalStaff.size() + config.getLowerRangeStaffIds().size());

                for (FileProcessor.Staff staff : normalStaff) {
                    StaffAssignment assignment = assignNormalStaff(staff, avgPointsPerStaff,
                            remainingMainRooms, remainingAnnexRooms);
                    assignments.add(assignment);
                    System.out.printf("  %s: %.1fP (%d室) - 通常配分\n",
                            staff.name, assignment.totalPoints, assignment.totalRooms);
                }
            }

            // Phase 3: 下限範囲制限スタッフを最後に処理
            List<String> lowerRangeStaff = config.getLowerRangeStaffIds();
            if (!lowerRangeStaff.isEmpty()) {
                System.out.println("\n【Phase 3: 下限範囲制限スタッフ処理】");
                for (String staffId : lowerRangeStaff) {
                    FileProcessor.Staff staff = findStaffById(staffId);
                    if (staff != null) {
                        StaffAssignment assignment = assignWithLowerRange(staff,
                                remainingMainRooms, remainingAnnexRooms);
                        assignments.add(assignment);
                        System.out.printf("  %s: %.1fP (%d室) - 下限範囲制限適用\n",
                                staff.name, assignment.totalPoints, assignment.totalRooms);
                    }
                }
            }

            return assignments;
        }

        /**
         * 上限制限付きスタッフ割り当て
         */
        private StaffAssignment assignWithUpperLimit(FileProcessor.Staff staff,
                                                     Map<Integer, RoomAllocation> remainingMainRooms,
                                                     Map<Integer, RoomAllocation> remainingAnnexRooms) {

            PointConstraint constraint = config.pointConstraints.get(staff.id);
            double targetPoints = constraint.upperLimit;

            // 大浴場清掃減算
            BathCleaningType bathType = config.bathCleaningAssignments.get(staff.id);
            if (bathType != null && bathType != BathCleaningType.NONE) {
                targetPoints -= bathType.reduction;
            }

            return assignToStaffWithTarget(staff, targetPoints, constraint.buildingAssignment,
                    remainingMainRooms, remainingAnnexRooms);
        }

        /**
         * 通常スタッフ割り当て
         */
        private StaffAssignment assignNormalStaff(FileProcessor.Staff staff, double targetPoints,
                                                  Map<Integer, RoomAllocation> remainingMainRooms,
                                                  Map<Integer, RoomAllocation> remainingAnnexRooms) {

            // 大浴場清掃減算
            BathCleaningType bathType = config.bathCleaningAssignments.get(staff.id);
            if (bathType != null && bathType != BathCleaningType.NONE) {
                targetPoints -= bathType.reduction;
            }

            // 建物制限チェック
            PointConstraint constraint = config.pointConstraints.get(staff.id);
            BuildingAssignment buildingAssignment = constraint != null ?
                    constraint.buildingAssignment : BuildingAssignment.BOTH;

            return assignToStaffWithTarget(staff, targetPoints, buildingAssignment,
                    remainingMainRooms, remainingAnnexRooms);
        }

        /**
         * 下限範囲制限付きスタッフ割り当て
         */
        private StaffAssignment assignWithLowerRange(FileProcessor.Staff staff,
                                                     Map<Integer, RoomAllocation> remainingMainRooms,
                                                     Map<Integer, RoomAllocation> remainingAnnexRooms) {

            PointConstraint constraint = config.pointConstraints.get(staff.id);
            double targetPoints = (constraint.lowerMinLimit + constraint.lowerMaxLimit) / 2.0; // 中央値を目標

            // 大浴場清掃減算
            BathCleaningType bathType = config.bathCleaningAssignments.get(staff.id);
            if (bathType != null && bathType != BathCleaningType.NONE) {
                targetPoints -= bathType.reduction;
            }

            // 残り部屋から可能な限り多く割り当て
            double remainingMainPoints = calculateRemainingPoints(remainingMainRooms);
            double remainingAnnexPoints = calculateRemainingPoints(remainingAnnexRooms);
            double maxAvailable = remainingMainPoints + remainingAnnexPoints;

            // 下限最大値と利用可能ポイントの小さい方を採用
            targetPoints = Math.min(constraint.lowerMaxLimit, maxAvailable);
            targetPoints = Math.max(constraint.lowerMinLimit, targetPoints);

            return assignToStaffWithTarget(staff, targetPoints, constraint.buildingAssignment,
                    remainingMainRooms, remainingAnnexRooms);
        }

        /**
         * 目標ポイントに基づくスタッフ割り当て
         */
        private StaffAssignment assignToStaffWithTarget(FileProcessor.Staff staff, double targetPoints,
                                                        BuildingAssignment buildingAssignment,
                                                        Map<Integer, RoomAllocation> remainingMainRooms,
                                                        Map<Integer, RoomAllocation> remainingAnnexRooms) {

            Map<Integer, RoomAllocation> staffRooms = new HashMap<>();
            List<Integer> staffFloors = new ArrayList<>();
            double currentPoints = 0;

            // 建物制限に基づいて利用可能フロアを決定
            List<Integer> availableFloors = new ArrayList<>();

            if (buildingAssignment != BuildingAssignment.ANNEX_ONLY) {
                availableFloors.addAll(getAvailableFloors(remainingMainRooms));
            }
            if (buildingAssignment != BuildingAssignment.MAIN_ONLY) {
                availableFloors.addAll(getAvailableFloors(remainingAnnexRooms));
            }

            Collections.sort(availableFloors);

            // 最大2階まで割り当て
            for (int i = 0; i < Math.min(2, availableFloors.size()) && currentPoints < targetPoints; i++) {
                int floor = availableFloors.get(i);

                Map<Integer, RoomAllocation> targetRemainingRooms =
                        floor <= 10 ? remainingMainRooms : remainingAnnexRooms;

                RoomAllocation available = targetRemainingRooms.get(floor);
                if (available == null || available.isEmpty()) continue;

                double remainingTarget = targetPoints - currentPoints;
                RoomAllocation allocation = calculateOptimalAllocation(available, remainingTarget);

                if (!allocation.isEmpty()) {
                    staffRooms.put(floor, allocation);
                    staffFloors.add(floor);
                    currentPoints += allocation.getTotalPoints();

                    // 残り部屋を更新
                    updateRemainingRooms(targetRemainingRooms, floor, allocation);
                }
            }

            AdaptiveWorkerType type = config.getAdaptiveWorkerType(staff.id);
            WorkerType workerType = type == AdaptiveWorkerType.NORMAL ?
                    WorkerType.NORMAL_DUTY : WorkerType.LIGHT_DUTY;

            return new StaffAssignment(staff, workerType, staffFloors, staffRooms);
        }

        // ヘルパーメソッド群
        private FileProcessor.Staff findStaffById(String staffId) {
            return config.availableStaff.stream()
                    .filter(s -> s.id.equals(staffId))
                    .findFirst().orElse(null);
        }

        private Map<Integer, RoomAllocation> initializeRemainingRooms(List<FloorInfo> floors) {
            Map<Integer, RoomAllocation> remaining = new HashMap<>();
            for (FloorInfo floor : floors) {
                remaining.put(floor.floorNumber,
                        new RoomAllocation(new HashMap<>(floor.roomCounts), floor.ecoRooms));
            }
            return remaining;
        }

        private List<Integer> getAvailableFloors(Map<Integer, RoomAllocation> remainingRooms) {
            return remainingRooms.entrySet().stream()
                    .filter(entry -> !entry.getValue().isEmpty())
                    .map(Map.Entry::getKey)
                    .sorted()
                    .collect(Collectors.toList());
        }

        private double calculateRemainingPoints(Map<Integer, RoomAllocation> remainingRooms) {
            return remainingRooms.values().stream()
                    .mapToDouble(RoomAllocation::getTotalPoints)
                    .sum();
        }

        private RoomAllocation calculateOptimalAllocation(RoomAllocation available, double targetPoints) {
            Map<String, Integer> bestCounts = new HashMap<>();
            int bestEco = 0;
            double currentPoints = 0;

            // 高ポイント部屋から優先的に割り当て
            List<Map.Entry<String, Integer>> sortedRooms = available.roomCounts.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0)
                    .sorted((a, b) -> Double.compare(
                            ROOM_POINTS.getOrDefault(b.getKey(), 1.0),
                            ROOM_POINTS.getOrDefault(a.getKey(), 1.0)))
                    .collect(Collectors.toList());

            // 各部屋タイプを割り当て
            for (Map.Entry<String, Integer> entry : sortedRooms) {
                String roomType = entry.getKey();
                int availableCount = entry.getValue();
                double roomPoints = ROOM_POINTS.getOrDefault(roomType, 1.0);

                int assignCount = Math.min(availableCount,
                        (int) Math.ceil((targetPoints - currentPoints) / roomPoints));

                if (assignCount > 0) {
                    bestCounts.put(roomType, assignCount);
                    currentPoints += assignCount * roomPoints;

                    if (currentPoints >= targetPoints) break;
                }
            }

            // エコ部屋で微調整
            if (currentPoints < targetPoints && available.ecoRooms > 0) {
                double remainingPoints = targetPoints - currentPoints;
                int ecoCount = Math.min(available.ecoRooms,
                        (int) Math.ceil(remainingPoints / ROOM_POINTS.get("ECO")));
                bestEco = ecoCount;
            }

            return new RoomAllocation(bestCounts, bestEco);
        }

        private void updateRemainingRooms(Map<Integer, RoomAllocation> remainingRooms,
                                          int floor, RoomAllocation allocated) {
            RoomAllocation current = remainingRooms.get(floor);
            if (current != null) {
                Map<String, Integer> newCounts = new HashMap<>(current.roomCounts);
                allocated.roomCounts.forEach((type, count) -> {
                    newCounts.put(type, Math.max(0, newCounts.getOrDefault(type, 0) - count));
                });
                int newEco = Math.max(0, current.ecoRooms - allocated.ecoRooms);
                remainingRooms.put(floor, new RoomAllocation(newCounts, newEco));
            }
        }
    }
}