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
 * ★改善: 本館・別館分離 + ポイントベース均等配分 + 階跨ぎ制約対応
 */
public class AdaptiveRoomOptimizer {

    private static final Logger LOGGER = Logger.getLogger(AdaptiveRoomOptimizer.class.getName());

    // 清掃条件の定数（publicに変更して外部からアクセス可能に）
    public static final int MAX_MAIN_BUILDING_ROOMS = 99;  // 本館最大部屋数
    public static final int MAX_ANNEX_BUILDING_ROOMS = 99; // 別館最大部屋数
    public static final int BATH_CLEANING_REDUCTION = 4;   // 大浴場清掃の削減数
    public static final int BATH_DRAINING_REDUCTION = 5;   // 大浴場湯抜きの削減数
    public static final int BATH_CLEANING_STAFF_COUNT = 4; // 大浴場清掃必要人数
    public static final double FLOOR_CROSSING_PENALTY = 0; // 階跨ぎペナルティ
    public static final double BUILDING_CROSSING_PENALTY = 1.0; // 館跨ぎペナルティ

    // ★修正: 部屋タイプ別の実質ポイント（ポイントベース計算用）
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
     * ★新規追加: 建物別データクラス
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
     * ★新規追加: スタッフ配分結果クラス
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

            // ★修正: 階跨ぎ制約（最大2階まで、連続不要）
            if (floors.size() > 2) {
                penalty += (floors.size() - 2) * 10.0; // 3階以上は大ペナルティ
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
            System.out.println("=== 建物分離型ポイントベース最適化開始 ===");
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

            // 建物分離最適化実行
            BuildingSeparatedOptimizer optimizer = new BuildingSeparatedOptimizer(buildingData, adaptiveConfig);
            List<StaffAssignment> result = optimizer.optimizeWithPriority();

            return new OptimizationResult(result, adaptiveConfig, targetDate);
        }

        /**
         * ★新規追加: 建物別データ分離
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
     * ★新規追加: 建物分離最適化器
     */
    public static class BuildingSeparatedOptimizer {
        private final BuildingData buildingData;
        private final AdaptiveLoadConfig config;

        public BuildingSeparatedOptimizer(BuildingData buildingData, AdaptiveLoadConfig config) {
            this.buildingData = buildingData;
            this.config = config;
        }

        /**
         * ★新規追加: 優先度付き最適化（1ポイント差 → 2ポイント差 → 館跨ぎ）
         */
        public List<StaffAssignment> optimizeWithPriority() {
            int totalStaff = config.availableStaff.size();

            System.out.println("\n=== 段階的最適化実行 ===");

            // 優先度1: 1ポイント差（館跨ぎなし）
            StaffAllocation allocation = tryPointDifference(1.0, "1ポイント差");
            if (allocation != null) {
                return executeOptimization(allocation);
            }

            // 優先度2: 2ポイント差（館跨ぎなし）
            allocation = tryPointDifference(2.0, "2ポイント差");
            if (allocation != null) {
                return executeOptimization(allocation);
            }

            // 優先度3: 館跨ぎで調整
            System.out.println("🔄 館跨ぎ調整モードに移行");
            return optimizeWithCrossBuilding();
        }

        /**
         * ★新規追加: 指定ポイント差での最適化試行
         */
        private StaffAllocation tryPointDifference(double targetDiff, String method) {
            System.out.printf("\n【%s試行】\n", method);

            int totalStaff = config.availableStaff.size();
            double tolerance = 0.3; // 許容範囲

            for (int mainStaff = 1; mainStaff < totalStaff; mainStaff++) {
                int annexStaff = totalStaff - mainStaff;

                double mainAvg = buildingData.mainTotalPoints / mainStaff;
                double annexAvg = buildingData.annexTotalPoints / annexStaff;
                double actualDiff = mainAvg - annexAvg;

                System.out.printf("  試行: 本館%d人(%.2fP) 別館%d人(%.2fP) 差%.2fP\n",
                        mainStaff, mainAvg, annexStaff, annexAvg, actualDiff);

                // 目標差±tolerance の範囲内か？
                if (Math.abs(actualDiff - targetDiff) <= tolerance) {
                    System.out.printf("✅ %s達成！\n", method);
                    return new StaffAllocation(mainStaff, annexStaff, false, method,
                            buildingData.mainTotalPoints, buildingData.annexTotalPoints);
                }
            }

            System.out.printf("❌ %sは達成困難\n", method);
            return null;
        }

        /**
         * ★新規追加: 館跨ぎ調整での最適化
         */
        private List<StaffAssignment> optimizeWithCrossBuilding() {
            int totalStaff = config.availableStaff.size();

            // 6:4 の割合で分割を試行
            int mainStaff = Math.max(1, (int) Math.round(totalStaff * 0.6)) - 1; // -1は館跨ぎスタッフ分
            int annexStaff = totalStaff - mainStaff - 1; // -1は館跨ぎスタッフ分

            if (mainStaff <= 0 || annexStaff <= 0) {
                // フォールバック: 半分ずつに分ける
                mainStaff = totalStaff / 2;
                annexStaff = totalStaff - mainStaff - 1;
            }

            double mainAvg = buildingData.mainTotalPoints / mainStaff;
            double annexAvg = buildingData.annexTotalPoints / annexStaff;

            System.out.printf("館跨ぎ前: 本館%d人(%.2fP) 別館%d人(%.2fP) 差%.2fP\n",
                    mainStaff, mainAvg, annexStaff, annexAvg, mainAvg - annexAvg);

            StaffAllocation allocation = new StaffAllocation(mainStaff, annexStaff, true, "館跨ぎ調整",
                    buildingData.mainTotalPoints, buildingData.annexTotalPoints);

            return executeOptimization(allocation);
        }

        /**
         * ★新規追加: 最適化実行
         */
        private List<StaffAssignment> executeOptimization(StaffAllocation allocation) {
            System.out.printf("\n📋 %sで最適化実行\n", allocation.method);
            System.out.printf("配分: 本館%d人 別館%d人", allocation.mainStaff, allocation.annexStaff);
            if (allocation.hasCrossBuilding) {
                System.out.print(" +館跨ぎ1人");
            }
            System.out.println();

            List<StaffAssignment> result = new ArrayList<>();

            if (!allocation.hasCrossBuilding) {
                // 館跨ぎなし: 完全分離最適化
                List<FileProcessor.Staff> mainStaffList = config.availableStaff.subList(0, allocation.mainStaff);
                List<FileProcessor.Staff> annexStaffList = config.availableStaff.subList(
                        allocation.mainStaff, allocation.mainStaff + allocation.annexStaff);

                result.addAll(optimizeBuilding(buildingData.mainFloors, mainStaffList, "本館"));
                result.addAll(optimizeBuilding(buildingData.annexFloors, annexStaffList, "別館"));
            } else {
                // 館跨ぎあり: 1人を両建物担当
                List<FileProcessor.Staff> mainStaffList = config.availableStaff.subList(0, allocation.mainStaff);
                List<FileProcessor.Staff> annexStaffList = config.availableStaff.subList(
                        allocation.mainStaff, allocation.mainStaff + allocation.annexStaff);
                FileProcessor.Staff crossBuildingStaff = config.availableStaff.get(allocation.mainStaff + allocation.annexStaff);

                result.addAll(optimizeBuilding(buildingData.mainFloors, mainStaffList, "本館"));
                result.addAll(optimizeBuilding(buildingData.annexFloors, annexStaffList, "別館"));

                // 館跨ぎスタッフの処理
                result.add(createCrossBuildingAssignment(crossBuildingStaff));
            }

            return result;
        }

        /**
         * ★新規追加: 建物別最適化
         */
        private List<StaffAssignment> optimizeBuilding(List<FloorInfo> floors,
                                                       List<FileProcessor.Staff> staffList,
                                                       String buildingName) {
            if (staffList.isEmpty()) {
                System.out.printf("⚠️ %sにスタッフが割り当てられていません\n", buildingName);
                return new ArrayList<>();
            }

            System.out.printf("\n【%s最適化】スタッフ%d人\n", buildingName, staffList.size());

            double totalPoints = floors.stream().mapToDouble(FloorInfo::getTotalPoints).sum();
            double targetPointsPerStaff = totalPoints / staffList.size();

            System.out.printf("目標: %.2fポイント/人\n", targetPointsPerStaff);

            List<StaffAssignment> assignments = new ArrayList<>();

            // ★ポイントベース配分実装
            Map<Integer, RoomAllocation> remainingRooms = initializeRemainingRooms(floors);

            for (FileProcessor.Staff staff : staffList) {
                // 個別目標ポイントを計算（制限考慮）
                double individualTarget = calculateIndividualTarget(staff.id, targetPointsPerStaff);

                StaffAssignment assignment = assignToStaff(staff, individualTarget, remainingRooms, buildingName);
                assignments.add(assignment);

                System.out.printf("  %s: %.2fP (%d室) %s\n",
                        staff.name, assignment.totalPoints, assignment.totalRooms,
                        assignment.floors.size() > 2 ? "⚠️階数超過" : "");
            }

            return assignments;
        }

        /**
         * ★新規追加: 個別目標ポイント計算
         */
        private double calculateIndividualTarget(String staffId, double baseTarget) {
            // 大浴場清掃による削減
            BathCleaningType bathType = config.bathCleaningAssignments.get(staffId);
            double reduction = 0;
            if (bathType != null && bathType != BathCleaningType.NONE) {
                reduction = bathType.reduction;
            }

            double target = baseTarget - reduction;

            // 制限の適用
            if (config.upperLimits.containsKey(staffId)) {
                target = Math.min(target, config.upperLimits.get(staffId));
            }
            if (config.lowerLimits.containsKey(staffId)) {
                target = Math.max(target, config.lowerLimits.get(staffId));
            }

            return Math.max(3.0, target); // 最低3ポイントは保証
        }

        /**
         * ★新規追加: 残り部屋の初期化
         */
        private Map<Integer, RoomAllocation> initializeRemainingRooms(List<FloorInfo> floors) {
            Map<Integer, RoomAllocation> remaining = new HashMap<>();
            for (FloorInfo floor : floors) {
                remaining.put(floor.floorNumber,
                        new RoomAllocation(new HashMap<>(floor.roomCounts), floor.ecoRooms));
            }
            return remaining;
        }

        /**
         * ★新規追加: スタッフへの部屋割り当て（ポイントベース）
         */
        private StaffAssignment assignToStaff(FileProcessor.Staff staff, double targetPoints,
                                              Map<Integer, RoomAllocation> remainingRooms, String buildingName) {
            Map<Integer, RoomAllocation> staffRooms = new HashMap<>();
            List<Integer> staffFloors = new ArrayList<>();
            double currentPoints = 0;

            // 利用可能な階を取得（最大2階まで）
            List<Integer> availableFloors = remainingRooms.entrySet().stream()
                    .filter(entry -> !entry.getValue().isEmpty())
                    .map(Map.Entry::getKey)
                    .sorted()
                    .collect(Collectors.toList());

            // 最大2階まで割り当て
            for (int i = 0; i < Math.min(2, availableFloors.size()) && currentPoints < targetPoints; i++) {
                int floor = availableFloors.get(i);
                RoomAllocation available = remainingRooms.get(floor);

                if (available.isEmpty()) continue;

                // この階から取得するポイント数を決定
                double remainingTarget = targetPoints - currentPoints;
                RoomAllocation allocation = calculateOptimalAllocation(available, remainingTarget);

                if (!allocation.isEmpty()) {
                    staffRooms.put(floor, allocation);
                    staffFloors.add(floor);
                    currentPoints += allocation.getTotalPoints();

                    // 残り部屋を更新
                    updateRemainingRooms(remainingRooms, floor, allocation);
                }
            }

            AdaptiveWorkerType type = config.getAdaptiveWorkerType(staff.id);
            WorkerType workerType = type == AdaptiveWorkerType.NORMAL ?
                    WorkerType.NORMAL_DUTY : WorkerType.LIGHT_DUTY;

            return new StaffAssignment(staff, workerType, staffFloors, staffRooms);
        }

        /**
         * ★新規追加: 最適配分計算
         */
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

        /**
         * ★新規追加: 残り部屋更新
         */
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

        /**
         * ★新規追加: 館跨ぎスタッフ割り当て作成
         */
        private StaffAssignment createCrossBuildingAssignment(FileProcessor.Staff staff) {
            System.out.printf("館跨ぎスタッフ作成: %s\n", staff.name);

            // 簡易実装: 本館1階+別館1階を割り当て
            Map<Integer, RoomAllocation> crossRooms = new HashMap<>();
            List<Integer> crossFloors = Arrays.asList(2, 21); // 本館2階 + 別館1階

            // 少量の部屋を割り当て（館跨ぎペナルティを考慮して）
            Map<String, Integer> mainRooms = new HashMap<>();
            mainRooms.put("S", 2);
            crossRooms.put(2, new RoomAllocation(mainRooms, 0));

            Map<String, Integer> annexRooms = new HashMap<>();
            annexRooms.put("S", 2);
            crossRooms.put(21, new RoomAllocation(annexRooms, 0));

            return new StaffAssignment(staff, WorkerType.NORMAL_DUTY, crossFloors, crossRooms);
        }
    }
}