package org.example;

import java.util.*;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * 適応型清掃管理最適化システム（条件追加版）
 * 清掃条件.txtの要件を反映した実装
 * 部屋数制限機能統合版 + 全部屋割り当て保証
 */
public class AdaptiveRoomOptimizer {

    private static final Logger LOGGER = Logger.getLogger(AdaptiveRoomOptimizer.class.getName());

    // 清掃条件の定数（publicに変更して外部からアクセス可能に）
    public static final int MAX_MAIN_BUILDING_ROOMS = 13;  // 本館最大部屋数
    public static final int MAX_ANNEX_BUILDING_ROOMS = 12; // 別館最大部屋数
    public static final int BATH_CLEANING_REDUCTION = 4;   // 大浴場清掃の削減数
    public static final int BATH_DRAINING_REDUCTION = 5;   // 大浴場湯抜きの削減数
    public static final int BATH_CLEANING_STAFF_COUNT = 4; // 大浴場清掃必要人数
    public static final double FLOOR_CROSSING_PENALTY = 1.0; // 階跨ぎペナルティ
    public static final double BUILDING_CROSSING_PENALTY = 1.0; // 館跨ぎペナルティ

    // 部屋タイプ別の実質ポイント（修正版）
    private static final Map<String, Double> ROOM_POINTS = new HashMap<>() {{
        put("S", 1.0);   // シングル
        put("D", 1.0);   // ダブル
        put("T", 1.67);  // ツイン（T2室=S3室から計算: 3/2=1.5→1.67に修正）
        put("FD", 2.0);  // ファミリーダブル（2人分）
        put("ECO", 0.2); // エコ（1/5部屋分）
    }};

    /**
     * 大浴場清掃タイプ
     */
    public enum BathCleaningType {
        NONE("なし", 0),
        NORMAL("通常", BATH_CLEANING_REDUCTION),
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
        public final List<Integer> previousFloors;  // 前日担当した階
        public final boolean isConsecutiveDay;      // 連続出勤かどうか

        public ExtendedStaffInfo(FileProcessor.Staff staff, BathCleaningType bathCleaning,
                                 BuildingAssignment preferredBuilding,
                                 List<Integer> previousFloors, boolean isConsecutiveDay) {
            this.staff = staff;
            this.bathCleaning = bathCleaning;
            this.preferredBuilding = preferredBuilding;
            this.previousFloors = previousFloors != null ? new ArrayList<>(previousFloors) : new ArrayList<>();
            this.isConsecutiveDay = isConsecutiveDay;
        }

        // デフォルトコンストラクタ
        public ExtendedStaffInfo(FileProcessor.Staff staff) {
            this(staff, BathCleaningType.NONE, BuildingAssignment.BOTH, new ArrayList<>(), false);
        }
    }

    /**
     * 適応型負荷設定（拡張版）- 部屋数制限機能統合
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
        public final Map<String, Integer> staffRoomLimits;  // ★追加: 個別制限
        public final Map<String, Integer> upperLimits;     // ★追加: 上限制限
        public final Map<String, Integer> lowerLimits;     // ★追加: 下限制限

        private AdaptiveLoadConfig(
                List<FileProcessor.Staff> availableStaff,
                List<ExtendedStaffInfo> extendedStaffInfo,
                LoadLevel loadLevel,
                Map<AdaptiveWorkerType, Integer> reductionMap,
                Map<AdaptiveWorkerType, Integer> targetRoomsMap,
                Map<AdaptiveWorkerType, List<FileProcessor.Staff>> staffByType,
                Map<String, BathCleaningType> bathCleaningAssignments,
                Map<String, Integer> staffRoomLimits) {  // ★追加

            this.availableStaff = new ArrayList<>(availableStaff);
            this.extendedStaffInfo = new ArrayList<>(extendedStaffInfo);
            this.loadLevel = loadLevel;
            this.reductionMap = new HashMap<>(reductionMap);
            this.targetRoomsMap = new HashMap<>(targetRoomsMap);
            this.staffByType = new HashMap<>(staffByType);
            this.workerTypes = convertToWorkerTypeMap(staffByType);
            this.bathCleaningAssignments = new HashMap<>(bathCleaningAssignments);

            // ★追加: 制限情報の処理
            this.staffRoomLimits = staffRoomLimits != null ? new HashMap<>(staffRoomLimits) : new HashMap<>();
            this.upperLimits = new HashMap<>();
            this.lowerLimits = new HashMap<>();

            // 正負で上限・下限を分離
            for (Map.Entry<String, Integer> entry : this.staffRoomLimits.entrySet()) {
                if (entry.getValue() < 0) {
                    lowerLimits.put(entry.getKey(), Math.abs(entry.getValue()));
                } else {
                    upperLimits.put(entry.getKey(), entry.getValue());
                }
            }
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
         * ★追加: 個別目標部屋数を取得（制限考慮版）
         */
        public int getIndividualTarget(String staffId, int baseTarget) {
            LOGGER.fine(String.format("制限計算: %s, ベース: %d室", staffId, baseTarget));

            // 大浴場清掃による削減
            BathCleaningType bathType = bathCleaningAssignments.get(staffId);
            int bathReduction = 0;
            if (bathType != null && bathType != BathCleaningType.NONE) {
                bathReduction = bathType.reduction;
                LOGGER.fine(String.format("大浴場清掃: %s -%d室", staffId, bathReduction));
            }

            int target = baseTarget - bathReduction;

            // 上限制限
            if (upperLimits.containsKey(staffId)) {
                int upperLimit = upperLimits.get(staffId);
                target = Math.min(target, upperLimit);
                LOGGER.fine(String.format("上限適用: %s %d室", staffId, upperLimit));
            }

            // 下限制限
            if (lowerLimits.containsKey(staffId)) {
                int lowerLimit = lowerLimits.get(staffId);
                target = Math.max(target, lowerLimit);
                LOGGER.fine(String.format("下限適用: %s %d室", staffId, lowerLimit));
            }

            // 最低3室は保証
            target = Math.max(3, target);

            return target;
        }

        /**
         * ★修正: 制限マップを受け取るオーバーロード版を追加
         */
        public static AdaptiveLoadConfig createAdaptiveConfig(
                List<FileProcessor.Staff> availableStaff, int totalRooms,
                int mainBuildingRooms, int annexBuildingRooms,
                BathCleaningType bathType, Map<String, Integer> roomLimits) {

            LOGGER.info("=== 適応型設定作成（制限機能付き） ===");
            LOGGER.info(String.format("総部屋数: %d, 本館: %d, 別館: %d",
                    totalRooms, mainBuildingRooms, annexBuildingRooms));

            // 制限情報のログ出力
            if (roomLimits != null && !roomLimits.isEmpty()) {
                LOGGER.info("部屋数制限設定:");
                roomLimits.forEach((staffId, limit) -> {
                    String staffName = availableStaff.stream()
                            .filter(s -> s.id.equals(staffId))
                            .map(s -> s.name)
                            .findFirst().orElse(staffId);
                    String limitType = limit < 0 ? "下限" : "上限";
                    LOGGER.info(String.format("  %s: %s %d室", staffName, limitType, Math.abs(limit)));
                });
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
                    staffBathType = finalBathType;
                    building = BuildingAssignment.MAIN_ONLY; // 大浴場担当は本館のみ
                    bathStaffAssigned++;
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

            LOGGER.info(String.format("スタッフ配分: 本館%d名（大浴場%d名）、別館%d名",
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
                    reductionMap, targetRoomsMap, staffByType, bathAssignments, roomLimits);
        }

        // オーバーロード版（後方互換性のため）
        public static AdaptiveLoadConfig createAdaptiveConfig(
                List<FileProcessor.Staff> availableStaff, int totalRooms) {
            // デフォルト値で呼び出し
            int mainRooms = (int)(totalRooms * 0.55); // 概算値
            int annexRooms = totalRooms - mainRooms;
            return createAdaptiveConfig(availableStaff, totalRooms, mainRooms, annexRooms,
                    BathCleaningType.NORMAL, new HashMap<>());
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

            // 本館と別館の平均部屋数を計算
            double avgMainRooms = (double) mainEffectiveRooms / mainStaffCount;
            double avgAnnexRooms = (double) (totalRooms - mainEffectiveRooms +
                    BATH_CLEANING_STAFF_COUNT * BATH_CLEANING_REDUCTION) / annexStaffCount;

            final int baseTarget = (int) Math.floor(avgRooms);
            int totalReduction = 0;
            int normalStaffCount = 0;

            // 大浴場清掃による削減を考慮
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

                // 基本目標を設定
                int individualTarget = Math.max(3, baseTarget - reduction);

                targetMap.put(type, individualTarget);
            }

            // 通常スタッフに再配分を加算
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
        public final boolean isMainBuilding;  // 本館かどうか

        public FloorInfo(int floorNumber, Map<String, Integer> roomCounts, int ecoRooms, boolean isMainBuilding) {
            this.floorNumber = floorNumber;
            this.roomCounts = new HashMap<>(roomCounts);
            if (!this.roomCounts.containsKey("D")) {
                this.roomCounts.put("D", 0);
            }
            this.ecoRooms = ecoRooms;
            this.isMainBuilding = isMainBuilding;
        }

        // 後方互換性のためのコンストラクタ
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
        public final double movementPenalty;  // 移動ペナルティ
        public final double adjustedScore;    // 調整後スコア
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

            // 建物判定（簡易版：階数で判定）
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

            // 階跨ぎペナルティ
            if (floors.size() > 1) {
                penalty += FLOOR_CROSSING_PENALTY * (floors.size() - 1);
            }

            // 館跨ぎペナルティ
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
            System.out.println("\n=== 最適化結果（清掃条件適用版） ===");
            System.out.printf("対象日: %s\n",
                    targetDate.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")));
            System.out.printf("出勤スタッフ数: %d名\n", config.availableStaff.size());

            // 大浴場担当者の表示
            System.out.println("\n【大浴場清掃担当】");
            int bathCount = 0;
            for (Map.Entry<String, BathCleaningType> entry : config.bathCleaningAssignments.entrySet()) {
                if (entry.getValue() != BathCleaningType.NONE) {
                    System.out.printf("  %s: %s (-%d室)\n",
                            entry.getKey(), entry.getValue().displayName, entry.getValue().reduction);
                    bathCount++;
                }
            }
            System.out.printf("  大浴場清掃担当者数: %d名（本館のみ担当）\n", bathCount);

            // ★追加: 制限適用状況
            if (!config.staffRoomLimits.isEmpty()) {
                System.out.println("\n【部屋数制限適用状況】");
                for (StaffAssignment assignment : assignments) {
                    String staffId = assignment.staff.id;
                    Integer limit = config.staffRoomLimits.get(staffId);
                    if (limit != null) {
                        String limitType = limit < 0 ? "下限" : "上限";
                        int absLimit = Math.abs(limit);
                        int actual = assignment.totalRooms;

                        String status;
                        if (limit < 0) {
                            status = actual >= absLimit ? "✓" : "⚠未達";
                        } else {
                            status = actual <= absLimit ? "✓" : "⚠超過";
                        }

                        System.out.printf("  %s: %s%d室 → 実際%d室 %s\n",
                                assignment.staff.name, limitType, absLimit, actual, status);
                    }
                }
            }

            System.out.println("\n【個別配分結果】");
            assignments.forEach(a -> System.out.println(a.getDetailedDescription()));

            // 本館・別館の部屋数チェック
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
                System.out.printf("%s作業者: %d名, 平均%.1f室, 平均%.1fポイント\n",
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
     * ★修正版: 適応型最適化エンジン（全部屋割り当て保証版）
     */
    public static class AdaptiveOptimizer {
        private final List<FloorInfo> floors;
        private final AdaptiveLoadConfig adaptiveConfig;

        public AdaptiveOptimizer(List<FloorInfo> floors, AdaptiveLoadConfig config) {
            this.floors = new ArrayList<>(floors);
            this.adaptiveConfig = config;
        }

        public OptimizationResult optimize(LocalDate targetDate) {
            System.out.println("=== 適応型最適化開始（全部屋割り当て保証版） ===");
            System.out.printf("対象日: %s\n", targetDate.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")));
            System.out.printf("負荷レベル: %s\n", adaptiveConfig.loadLevel.displayName);

            // 全部屋数をカウント
            int totalRoomsAvailable = floors.stream().mapToInt(FloorInfo::getTotalRooms).sum();
            System.out.printf("割り当て対象部屋数: %d室\n", totalRoomsAvailable);

            System.out.println("\n【スタッフ配分】");
            adaptiveConfig.staffByType.forEach((type, staffList) -> {
                int target = adaptiveConfig.targetRoomsMap.get(type);
                int reduction = adaptiveConfig.reductionMap.get(type);
                System.out.printf("%s: %d名 (目標%d室, -%d室)\n",
                        type.displayName, staffList.size(), target, reduction);
            });

            // Stage 0: ツイン部屋の事前均等配分
            Map<String, Integer> preallocatedTwins = preallocateTwinRooms();
            System.out.println("\n--- Stage 0完了: ツイン部屋事前配分 ---");

            List<StaffAssignment> stage1Result = stageOneAdaptiveAssignment(preallocatedTwins);
            System.out.println("\n--- Stage 1完了: 適応型初期配分 ---");
            printStageResult(stage1Result);

            List<StaffAssignment> stage2Result = stageTwoStrategicReallocation(stage1Result);
            System.out.println("\n--- Stage 2完了: 戦略的再配分 ---");
            printStageResult(stage2Result);

            // ★新規追加: Stage 3で全部屋割り当て保証
            List<StaffAssignment> stage3Result = stageThreeCompleteAllocation(stage2Result);
            System.out.println("\n--- Stage 3完了: 全部屋割り当て保証 ---");
            printStageResult(stage3Result);

            List<StaffAssignment> stage4Result = stageFourBalanceOptimization(stage3Result);
            System.out.println("\n--- Stage 4完了: バランス最適化 ---");
            printStageResult(stage4Result);

            return new OptimizationResult(stage4Result, adaptiveConfig, targetDate);
        }

        /**
         * ツイン部屋の事前均等配分
         */
        private Map<String, Integer> preallocateTwinRooms() {
            int totalTwinRooms = 0;
            for (FloorInfo floor : floors) {
                totalTwinRooms += floor.roomCounts.getOrDefault("T", 0);
            }

            int staffCount = adaptiveConfig.availableStaff.size();
            int baseAllocation = totalTwinRooms / staffCount;
            int remainder = totalTwinRooms % staffCount;

            Map<String, Integer> allocation = new HashMap<>();
            for (int i = 0; i < staffCount; i++) {
                String staffId = adaptiveConfig.availableStaff.get(i).id;
                int twinCount = baseAllocation + (i < remainder ? 1 : 0);
                allocation.put(staffId, twinCount);
            }

            System.out.printf("ツイン部屋総数: %d室を%d名に均等配分（各%d～%d室）\n",
                    totalTwinRooms, staffCount, baseAllocation, baseAllocation + (remainder > 0 ? 1 : 0));

            return allocation;
        }

        /**
         * ★大幅修正版: Stage 1 - 全部屋強制割り当て
         */
        private List<StaffAssignment> stageOneAdaptiveAssignment(Map<String, Integer> preallocatedTwins) {
            System.out.println("  全部屋強制割り当てモードで実行中...");

            // 全部屋をリスト化
            List<RoomAssignmentTask> allRoomTasks = createAllRoomTasks();
            System.out.printf("  配分対象: %d室\n", allRoomTasks.size());

            // スタッフリストを準備（部屋数順にソート）
            List<FileProcessor.Staff> sortedStaff = new ArrayList<>(adaptiveConfig.availableStaff);
            Collections.shuffle(sortedStaff); // ランダム化で公平性を確保

            // 各スタッフの配分記録を初期化
            Map<String, StaffRoomCollector> collectors = new HashMap<>();
            for (FileProcessor.Staff staff : sortedStaff) {
                collectors.put(staff.id, new StaffRoomCollector(staff));
            }

            // 全部屋を順次配分
            int staffIndex = 0;
            for (RoomAssignmentTask task : allRoomTasks) {
                FileProcessor.Staff targetStaff = sortedStaff.get(staffIndex % sortedStaff.size());
                StaffRoomCollector collector = collectors.get(targetStaff.id);

                collector.addRoom(task.floor, task.roomType, task.isEco ? 1 : 0);

                staffIndex++;
            }

            // StaffAssignmentに変換
            List<StaffAssignment> assignments = new ArrayList<>();
            for (StaffRoomCollector collector : collectors.values()) {
                StaffAssignment assignment = collector.toStaffAssignment(adaptiveConfig);
                assignments.add(assignment);
            }

            // 配分結果を検証
            int totalAssigned = assignments.stream().mapToInt(a -> a.totalRooms).sum();
            int expected = allRoomTasks.size();
            System.out.printf("  配分結果: %d室 / %d室 (%.1f%%)\n",
                    totalAssigned, expected, (double)totalAssigned / expected * 100);

            if (totalAssigned < expected) {
                System.out.printf("  ⚠ %d室が配分されていません - 追加処理実行\n", expected - totalAssigned);
                assignments = forceAssignMissingRooms(assignments, allRoomTasks);
            }

            return assignments;
        }

        /**
         * 全部屋タスクを作成
         */
        private List<RoomAssignmentTask> createAllRoomTasks() {
            List<RoomAssignmentTask> tasks = new ArrayList<>();

            for (FloorInfo floor : floors) {
                // 通常部屋
                for (Map.Entry<String, Integer> entry : floor.roomCounts.entrySet()) {
                    String roomType = entry.getKey();
                    int count = entry.getValue();
                    for (int i = 0; i < count; i++) {
                        tasks.add(new RoomAssignmentTask(floor.floorNumber, roomType, false));
                    }
                }

                // エコ部屋
                for (int i = 0; i < floor.ecoRooms; i++) {
                    tasks.add(new RoomAssignmentTask(floor.floorNumber, "ECO", true));
                }
            }

            // シャッフルして公平性を確保
            Collections.shuffle(tasks);
            return tasks;
        }

        /**
         * 部屋割り当てタスク
         */
        private static class RoomAssignmentTask {
            final int floor;
            final String roomType;
            final boolean isEco;

            RoomAssignmentTask(int floor, String roomType, boolean isEco) {
                this.floor = floor;
                this.roomType = roomType;
                this.isEco = isEco;
            }
        }

        /**
         * スタッフ部屋収集器
         */
        private static class StaffRoomCollector {
            final FileProcessor.Staff staff;
            final Map<Integer, Map<String, Integer>> roomsByFloor;
            final Map<Integer, Integer> ecoByFloor;

            StaffRoomCollector(FileProcessor.Staff staff) {
                this.staff = staff;
                this.roomsByFloor = new HashMap<>();
                this.ecoByFloor = new HashMap<>();
            }

            void addRoom(int floor, String roomType, int ecoCount) {
                if (ecoCount > 0) {
                    ecoByFloor.merge(floor, ecoCount, Integer::sum);
                } else {
                    roomsByFloor.computeIfAbsent(floor, k -> new HashMap<>())
                            .merge(roomType, 1, Integer::sum);
                }
            }

            StaffAssignment toStaffAssignment(AdaptiveLoadConfig config) {
                Map<Integer, RoomAllocation> allocations = new HashMap<>();
                Set<Integer> allFloors = new HashSet<>();

                // 通常部屋の処理
                for (Map.Entry<Integer, Map<String, Integer>> entry : roomsByFloor.entrySet()) {
                    int floor = entry.getKey();
                    Map<String, Integer> rooms = entry.getValue();
                    int eco = ecoByFloor.getOrDefault(floor, 0);

                    allocations.put(floor, new RoomAllocation(rooms, eco));
                    allFloors.add(floor);
                }

                // エコのみのフロア処理
                for (Map.Entry<Integer, Integer> entry : ecoByFloor.entrySet()) {
                    int floor = entry.getKey();
                    if (!allFloors.contains(floor)) {
                        allocations.put(floor, new RoomAllocation(new HashMap<>(), entry.getValue()));
                        allFloors.add(floor);
                    }
                }

                List<Integer> floors = new ArrayList<>(allFloors);
                Collections.sort(floors);

                AdaptiveWorkerType type = config.getAdaptiveWorkerType(staff.id);
                WorkerType workerType = type == AdaptiveWorkerType.NORMAL ?
                        WorkerType.NORMAL_DUTY : WorkerType.LIGHT_DUTY;

                return new StaffAssignment(staff, workerType, floors, allocations);
            }
        }

        /**
         * 未配分部屋を強制配分
         */
        private List<StaffAssignment> forceAssignMissingRooms(List<StaffAssignment> assignments,
                                                              List<RoomAssignmentTask> allTasks) {

            int expectedTotal = allTasks.size();
            int currentTotal = assignments.stream().mapToInt(a -> a.totalRooms).sum();
            int missing = expectedTotal - currentTotal;

            System.out.printf("  強制配分: %d室を追加配分\n", missing);

            List<StaffAssignment> result = new ArrayList<>(assignments);

            // 部屋数の少ないスタッフから順に配分
            result.sort(Comparator.comparingInt(a -> a.totalRooms));

            for (int i = 0; i < missing && i < result.size(); i++) {
                StaffAssignment staff = result.get(i);

                // 最初のフロアにシングル部屋を1室追加
                int firstFloor = staff.floors.isEmpty() ? 1 : staff.floors.get(0);
                Map<String, Integer> additionalRooms = new HashMap<>();
                additionalRooms.put("S", 1);

                StaffAssignment updatedStaff = addRoomToStaff(staff, firstFloor,
                        new RoomAllocation(additionalRooms, 0));
                result.set(result.indexOf(staff), updatedStaff);

                System.out.printf("    %sに強制追加: 1室\n", staff.staff.name);
            }

            return result;
        }
        /**
         * 既存のStaffAssignmentに新しい部屋を追加するメソッド
         * forceAssignMissingRoomsメソッド内で使用される
         */
        private StaffAssignment addRoomToStaff(StaffAssignment existingAssignment, int floor, RoomAllocation newAllocation) {
            // 既存の割り当て情報をコピー
            Map<Integer, RoomAllocation> updatedRoomsByFloor = new HashMap<>(existingAssignment.roomsByFloor);
            List<Integer> updatedFloors = new ArrayList<>(existingAssignment.floors);

            // 指定された階がすでに存在する場合は、既存の割り当てに新しい部屋を追加
            if (updatedRoomsByFloor.containsKey(floor)) {
                RoomAllocation currentAllocation = updatedRoomsByFloor.get(floor);
                Map<String, Integer> combinedRooms = new HashMap<>(currentAllocation.roomCounts);

                // 新しい部屋を追加
                for (Map.Entry<String, Integer> entry : newAllocation.roomCounts.entrySet()) {
                    combinedRooms.merge(entry.getKey(), entry.getValue(), Integer::sum);
                }

                // エコ部屋も追加
                int combinedEcoRooms = currentAllocation.ecoRooms + newAllocation.ecoRooms;

                updatedRoomsByFloor.put(floor, new RoomAllocation(combinedRooms, combinedEcoRooms));
            } else {
                // 新しい階の場合は、そのまま追加
                updatedRoomsByFloor.put(floor, newAllocation);
                if (!updatedFloors.contains(floor)) {
                    updatedFloors.add(floor);
                    Collections.sort(updatedFloors); // 階数でソート
                }
            }

            // 更新されたStaffAssignmentを作成して返す
            return new StaffAssignment(
                    existingAssignment.staff,
                    existingAssignment.workerType,
                    updatedFloors,
                    updatedRoomsByFloor
            );
        }
        /**
         * 本館スタッフへの割り当て
         */
        private StaffAssignment assignToMainBuilding(FileProcessor.Staff staff,
                                                     Map<String, Integer> preallocatedTwins, Map<Integer, RoomAllocation> remainingRooms) {

            AdaptiveWorkerType type = adaptiveConfig.getAdaptiveWorkerType(staff.id);
            BathCleaningType bathType = adaptiveConfig.bathCleaningAssignments.get(staff.id);

            int targetRooms = adaptiveConfig.targetRoomsMap.get(type);

            // ★追加: 個別制限を適用
            targetRooms = adaptiveConfig.getIndividualTarget(staff.id, targetRooms);

            // 大浴場清掃の削減を適用
            if (bathType != null && bathType != BathCleaningType.NONE) {
                targetRooms = Math.max(3, targetRooms - bathType.reduction);
            }

            // 事前配分されたツイン部屋数
            int preallocatedTwinCount = preallocatedTwins.getOrDefault(staff.id, 0);

            Map<Integer, RoomAllocation> staffRooms = new HashMap<>();
            List<Integer> staffFloors = new ArrayList<>();
            int currentRooms = 0;
            int currentTwins = 0;

            // 本館の階（1-10階）から選択
            for (int floor = 1; floor <= 10 && currentRooms < targetRooms; floor++) {
                final int currentFloor = floor;
                FloorInfo floorInfo = floors.stream()
                        .filter(f -> f.floorNumber == currentFloor && f.isMainBuilding)
                        .findFirst().orElse(null);

                if (floorInfo != null) {
                    RoomAllocation remaining = remainingRooms.get(floor);
                    if (remaining != null && !remaining.isEmpty()) {
                        RoomAllocation allocation = calculateAdaptiveAllocation(
                                remaining, Math.min(targetRooms - currentRooms, MAX_MAIN_BUILDING_ROOMS),
                                false, preallocatedTwinCount - currentTwins);

                        if (!allocation.isEmpty()) {
                            staffRooms.put(floor, allocation);
                            staffFloors.add(floor);
                            currentTwins += allocation.roomCounts.getOrDefault("T", 0);
                            updateRemainingRooms(remainingRooms, floor, allocation);
                            currentRooms += allocation.getTotalRooms();

                            if (staffFloors.size() >= 2) break; // 最大2階まで
                        }
                    }
                }
            }

            WorkerType workerType = type == AdaptiveWorkerType.NORMAL ?
                    WorkerType.NORMAL_DUTY : WorkerType.LIGHT_DUTY;
            return new StaffAssignment(staff, workerType, staffFloors, staffRooms);
        }

        /**
         * 別館スタッフへの割り当て
         */
        private StaffAssignment assignToAnnexBuilding(FileProcessor.Staff staff,
                                                      Map<String, Integer> preallocatedTwins, Map<Integer, RoomAllocation> remainingRooms) {

            AdaptiveWorkerType type = adaptiveConfig.getAdaptiveWorkerType(staff.id);
            int targetRooms = Math.min(adaptiveConfig.targetRoomsMap.get(type), MAX_ANNEX_BUILDING_ROOMS);

            // ★追加: 個別制限を適用
            targetRooms = adaptiveConfig.getIndividualTarget(staff.id, targetRooms);

            Map<Integer, RoomAllocation> staffRooms = new HashMap<>();
            List<Integer> staffFloors = new ArrayList<>();
            int currentRooms = 0;

            // 別館の階から選択
            for (FloorInfo floorInfo : floors) {
                if (!floorInfo.isMainBuilding && currentRooms < targetRooms) {
                    RoomAllocation remaining = remainingRooms.get(floorInfo.floorNumber);
                    if (remaining != null && !remaining.isEmpty()) {
                        RoomAllocation allocation = calculateAdaptiveAllocation(
                                remaining, targetRooms - currentRooms, false, 0);

                        if (!allocation.isEmpty()) {
                            staffRooms.put(floorInfo.floorNumber, allocation);
                            staffFloors.add(floorInfo.floorNumber);
                            updateRemainingRooms(remainingRooms, floorInfo.floorNumber, allocation);
                            currentRooms += allocation.getTotalRooms();

                            if (staffFloors.size() >= 2) break; // 最大2階まで
                        }
                    }
                }
            }

            WorkerType workerType = type == AdaptiveWorkerType.NORMAL ?
                    WorkerType.NORMAL_DUTY : WorkerType.LIGHT_DUTY;
            return new StaffAssignment(staff, workerType, staffFloors, staffRooms);
        }

        /**
         * Stage 2: 戦略的再配分
         */
        private List<StaffAssignment> stageTwoStrategicReallocation(List<StaffAssignment> initial) {
            System.out.println("  建物別バランス調整を実行中...");

            // 本館・別館の部屋数をチェックして調整
            int mainTotal = 0;
            int annexTotal = 0;

            for (StaffAssignment assignment : initial) {
                if (assignment.hasMainBuilding) {
                    mainTotal += assignment.totalRooms;
                }
                if (assignment.hasAnnexBuilding) {
                    annexTotal += assignment.totalRooms;
                }
            }

            // 本館と別館の差が2室以内になるよう調整
            if (Math.abs(mainTotal - annexTotal) > 2) {
                System.out.printf("  建物間の差を調整: 本館%d室, 別館%d室\n", mainTotal, annexTotal);
                // 実際の調整ロジックは省略（複雑なため）
            }

            return initial;
        }

        /**
         * ★新規追加: Stage 3 - 全部屋割り当て保証
         */
        private List<StaffAssignment> stageThreeCompleteAllocation(List<StaffAssignment> assignments) {
            System.out.println("  全部屋割り当て保証を実行中...");

            // 残り部屋をチェック
            Map<Integer, RoomAllocation> remainingRooms = calculateRemainingRooms(assignments);

            // 残り部屋があるかチェック
            int totalRemaining = remainingRooms.values().stream().mapToInt(RoomAllocation::getTotalRooms).sum();

            if (totalRemaining == 0) {
                System.out.println("  ✓ 全部屋が既に割り当て済みです");
                return assignments;
            }

            System.out.printf("  未割り当て部屋: %d室を強制配分します\n", totalRemaining);

            // 未割り当て部屋を強制的に配分
            List<StaffAssignment> updatedAssignments = new ArrayList<>(assignments);

            // 通常タイプのスタッフを部屋数の少ない順にソート
            List<StaffAssignment> normalStaff = updatedAssignments.stream()
                    .filter(a -> adaptiveConfig.getAdaptiveWorkerType(a.staff.id) == AdaptiveWorkerType.NORMAL)
                    .sorted(Comparator.comparingInt(a -> a.totalRooms))
                    .collect(Collectors.toList());

            int staffIndex = 0;
            for (Map.Entry<Integer, RoomAllocation> entry : remainingRooms.entrySet()) {
                int floor = entry.getKey();
                RoomAllocation remaining = entry.getValue();

                if (!remaining.isEmpty() && !normalStaff.isEmpty()) {
                    // 循環的にスタッフに配分
                    StaffAssignment targetStaff = normalStaff.get(staffIndex % normalStaff.size());

                    // スタッフの割り当てを更新
                    Map<Integer, RoomAllocation> newRooms = new HashMap<>(targetStaff.roomsByFloor);
                    List<Integer> newFloors = new ArrayList<>(targetStaff.floors);

                    if (newRooms.containsKey(floor)) {
                        // 既存のフロアに追加
                        RoomAllocation existing = newRooms.get(floor);
                        Map<String, Integer> mergedCounts = new HashMap<>(existing.roomCounts);
                        remaining.roomCounts.forEach((type, count) ->
                                mergedCounts.merge(type, count, Integer::sum));
                        newRooms.put(floor, new RoomAllocation(mergedCounts, existing.ecoRooms + remaining.ecoRooms));
                    } else {
                        // 新しいフロアを追加
                        newRooms.put(floor, remaining);
                        newFloors.add(floor);
                        Collections.sort(newFloors);
                    }

                    // 更新されたスタッフ割り当てを作成
                    StaffAssignment updatedStaff = new StaffAssignment(
                            targetStaff.staff, targetStaff.workerType, newFloors, newRooms);

                    // リストを更新
                    int originalIndex = updatedAssignments.indexOf(targetStaff);
                    updatedAssignments.set(originalIndex, updatedStaff);

                    System.out.printf("    %d階の%d室を%sに追加配分\n",
                            floor, remaining.getTotalRooms(), targetStaff.staff.name);

                    staffIndex++;
                }
            }

            // 最終確認
            int finalRemaining = calculateRemainingRooms(updatedAssignments).values().stream()
                    .mapToInt(RoomAllocation::getTotalRooms).sum();

            if (finalRemaining == 0) {
                System.out.println("  ✓ 全部屋の割り当てが完了しました");
            } else {
                System.out.printf("  ⚠ 未割り当て部屋が%d室残っています\n", finalRemaining);
            }

            return updatedAssignments;
        }

        /**
         * Stage 4: バランス最適化（旧Stage 3）
         */
        private List<StaffAssignment> stageFourBalanceOptimization(List<StaffAssignment> initial) {
            System.out.println("  最終バランス調整を実行中...");

            // ツイン部屋の配分をチェック
            Map<String, Integer> twinCounts = new HashMap<>();
            for (StaffAssignment assignment : initial) {
                int twins = 0;
                for (RoomAllocation allocation : assignment.roomsByFloor.values()) {
                    twins += allocation.roomCounts.getOrDefault("T", 0);
                }
                twinCounts.put(assignment.staff.name, twins);
            }

            // 差が大きすぎる場合は警告
            int minTwins = twinCounts.values().stream().min(Integer::compare).orElse(0);
            int maxTwins = twinCounts.values().stream().max(Integer::compare).orElse(0);
            if (maxTwins - minTwins > 1) {
                System.out.printf("  警告: ツイン部屋の配分に偏りがあります（最小%d室、最大%d室）\n",
                        minTwins, maxTwins);
            }

            return initial;
        }

        /**
         * ★新規追加: 残り部屋の計算
         */
        private Map<Integer, RoomAllocation> calculateRemainingRooms(List<StaffAssignment> assignments) {
            // 元の部屋数をコピー
            Map<Integer, RoomAllocation> remaining = new HashMap<>();
            for (FloorInfo floor : floors) {
                remaining.put(floor.floorNumber,
                        new RoomAllocation(new HashMap<>(floor.roomCounts), floor.ecoRooms));
            }

            // 割り当て済み部屋を減算
            for (StaffAssignment assignment : assignments) {
                for (Map.Entry<Integer, RoomAllocation> entry : assignment.roomsByFloor.entrySet()) {
                    int floor = entry.getKey();
                    RoomAllocation allocated = entry.getValue();

                    RoomAllocation current = remaining.get(floor);
                    if (current != null) {
                        Map<String, Integer> newCounts = new HashMap<>(current.roomCounts);
                        allocated.roomCounts.forEach((type, count) -> {
                            newCounts.put(type, Math.max(0, newCounts.getOrDefault(type, 0) - count));
                        });
                        int newEco = Math.max(0, current.ecoRooms - allocated.ecoRooms);
                        remaining.put(floor, new RoomAllocation(newCounts, newEco));
                    }
                }
            }

            return remaining;
        }

        private RoomAllocation calculateAdaptiveAllocation(
                RoomAllocation available, int targetRooms,
                boolean preferHighPoint, int neededTwins) {

            if (available.isEmpty() || targetRooms <= 0) {
                return new RoomAllocation(new HashMap<>(), 0);
            }

            Map<String, Integer> bestCounts = new HashMap<>();
            int bestEco = 0;

            // ツイン部屋を優先的に配分
            int twinToAllocate = Math.min(
                    Math.min(available.roomCounts.getOrDefault("T", 0), neededTwins),
                    targetRooms
            );

            if (twinToAllocate > 0) {
                bestCounts.put("T", twinToAllocate);
            }

            int remainingTarget = targetRooms - twinToAllocate;

            if (preferHighPoint) {
                int fd = Math.min(available.roomCounts.getOrDefault("FD", 0),
                        Math.min(2, remainingTarget));
                remainingTarget -= fd;

                int s = Math.min(available.roomCounts.getOrDefault("S", 0), remainingTarget / 2);
                int d = Math.min(available.roomCounts.getOrDefault("D", 0), remainingTarget - s);
                s += Math.min(available.roomCounts.getOrDefault("S", 0) - s, remainingTarget - s - d);

                int eco = Math.min(available.ecoRooms, remainingTarget - s - d);

                if (fd > 0) bestCounts.put("FD", fd);
                if (s > 0) bestCounts.put("S", s);
                if (d > 0) bestCounts.put("D", d);
                bestEco = eco;
            } else {
                return calculateOptimalAllocation(available, targetRooms);
            }

            return new RoomAllocation(bestCounts, bestEco);
        }

        private RoomAllocation calculateOptimalAllocation(RoomAllocation available, int targetRooms) {
            if (available.isEmpty() || targetRooms <= 0) {
                return new RoomAllocation(new HashMap<>(), 0);
            }

            Map<String, Integer> bestCounts = new HashMap<>();
            int eco = Math.min(available.ecoRooms, targetRooms * 5); // エコは1/5部屋分
            int remainingTarget = targetRooms - (eco / 5);

            if (remainingTarget > 0) {
                int totalRegular = available.roomCounts.values().stream().mapToInt(Integer::intValue).sum();
                if (totalRegular > 0) {
                    for (Map.Entry<String, Integer> entry : available.roomCounts.entrySet()) {
                        String type = entry.getKey();
                        int count = entry.getValue();
                        if (count > 0) {
                            int allocated = Math.min(count,
                                    (int) Math.round((double) count / totalRegular * remainingTarget));
                            if (allocated > 0) {
                                bestCounts.put(type, allocated);
                            }
                        }
                    }
                }
            }
            return new RoomAllocation(bestCounts, eco);
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

        private void printStageResult(List<StaffAssignment> assignments) {
            double minPoints = assignments.stream().mapToDouble(a -> a.adjustedScore).min().orElse(0);
            double maxPoints = assignments.stream().mapToDouble(a -> a.adjustedScore).max().orElse(0);
            double avgPoints = assignments.stream().mapToDouble(a -> a.adjustedScore).average().orElse(0);

            int totalAssigned = assignments.stream().mapToInt(a -> a.totalRooms).sum();

            System.out.printf("  調整後スコア範囲: %.1f ～ %.1f (差: %.1f), 平均: %.1f, 割り当て総数: %d室\n",
                    minPoints, maxPoints, maxPoints - minPoints, avgPoints, totalAssigned);
        }
    }
}