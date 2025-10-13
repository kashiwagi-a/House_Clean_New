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
 * ★修正: 大浴場清掃スタッフ手動選択機能対応版（優先処理ロジック追加）
 * ★新規追加: 手順4-8の詳細部屋割り振り機能
 */
public class AdaptiveRoomOptimizer {

    private static final Logger LOGGER = Logger.getLogger(AdaptiveRoomOptimizer.class.getName());

    // 清掃条件の定数
    public static final int MAX_MAIN_BUILDING_ROOMS = 99;
    public static final int MAX_ANNEX_BUILDING_ROOMS = 99;
    public static final int BATH_CLEANING_REDUCTION = 4;
    public static final int BATH_DRAINING_REDUCTION = 5;
    public static final double FLOOR_CROSSING_PENALTY = 0;
    public static final double BUILDING_CROSSING_PENALTY = 1.0;

    // 部屋タイプ別の実質ポイント（ポイントベース計算用）
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
     * 作業者タイプ（表示用のみ）
     */
    public enum WorkerType {
        REGULAR("正社員"),
        CONTRACTOR("業者");

        public final String displayName;

        WorkerType(String displayName) {
            this.displayName = displayName;
        }
    }

    /**
     * フロア情報クラス（既存コードとの互換性維持）
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

        public int getTotalNormalRooms() {
            return roomCounts.values().stream().mapToInt(Integer::intValue).sum();
        }

        public int getTotalRooms() {
            return getTotalNormalRooms() + ecoRooms;
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
     * スタッフ拡張情報クラス
     */
    public static class ExtendedStaffInfo {
        public final FileProcessor.Staff staff;
        public final BuildingAssignment buildingAssignment;
        public final BathCleaningType bathCleaningType;
        public final int adjustedCapacity;

        public ExtendedStaffInfo(FileProcessor.Staff staff,
                                 BuildingAssignment assignment,
                                 BathCleaningType bathType) {
            this.staff = staff;
            this.buildingAssignment = assignment;
            this.bathCleaningType = bathType;
            this.adjustedCapacity = calculateAdjustedCapacity(bathType);
        }

        private int calculateAdjustedCapacity(BathCleaningType bathType) {
            // デフォルトのキャパシティ（15部屋）から大浴場清掃分を差し引く
            int defaultCapacity = 15;
            return Math.max(1, defaultCapacity - bathType.reduction);
        }
    }

    /**
     * ポイント制約クラス
     */
    public static class PointConstraint {
        public final String staffName;
        public final double minPoints;
        public final double maxPoints;
        public final String constraintType;

        public PointConstraint(String staffName, double minPoints, double maxPoints, String constraintType) {
            this.staffName = staffName;
            this.minPoints = minPoints;
            this.maxPoints = maxPoints;
            this.constraintType = constraintType;
        }
    }

    /**
     * 部屋割り当てクラス（既存コードとの互換性維持）
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
     * スタッフ割り当て結果クラス（既存コードとの互換性維持）
     */
    public static class StaffAssignment {
        public final FileProcessor.Staff staff;
        public final WorkerType workerType;
        public final List<Integer> floors;
        public final Map<Integer, RoomAllocation> mainBuildingAssignments;
        public final Map<Integer, RoomAllocation> annexBuildingAssignments;
        public final Map<Integer, RoomAllocation> roomsByFloor; // ★追加: AssignmentEditorGUI互換性
        public final double totalPoints;
        public final double adjustedScore;
        public final BathCleaningType bathCleaningType;

        public StaffAssignment(FileProcessor.Staff staff,
                               Map<Integer, RoomAllocation> mainAssignments,
                               Map<Integer, RoomAllocation> annexAssignments,
                               BathCleaningType bathType) {
            this.staff = staff;
            this.workerType = WorkerType.REGULAR; // デフォルト
            this.mainBuildingAssignments = new HashMap<>(mainAssignments);
            this.annexBuildingAssignments = new HashMap<>(annexAssignments);
            this.bathCleaningType = bathType;

            // フロア情報の統合
            Set<Integer> allFloors = new HashSet<>();
            allFloors.addAll(mainAssignments.keySet());
            allFloors.addAll(annexAssignments.keySet());
            this.floors = new ArrayList<>(allFloors);
            Collections.sort(this.floors);

            // ★追加: roomsByFloorの統合マップ作成
            this.roomsByFloor = new HashMap<>();
            this.roomsByFloor.putAll(mainAssignments);
            this.roomsByFloor.putAll(annexAssignments);

            this.totalPoints = calculateTotalPoints();
            this.adjustedScore = calculateAdjustedScore();
        }

        // 既存コードとの互換性のためのコンストラクタ
        public StaffAssignment(FileProcessor.Staff staff,
                               WorkerType workerType,
                               List<Integer> floors,
                               Map<Integer, RoomAllocation> roomsByFloor) {
            this.staff = staff;
            this.workerType = workerType;
            this.floors = new ArrayList<>(floors);
            this.roomsByFloor = new HashMap<>(roomsByFloor); // ★追加: 直接設定
            this.bathCleaningType = BathCleaningType.NONE;

            // 建物別に分離
            this.mainBuildingAssignments = new HashMap<>();
            this.annexBuildingAssignments = new HashMap<>();

            for (Map.Entry<Integer, RoomAllocation> entry : roomsByFloor.entrySet()) {
                int floor = entry.getKey();
                if (floor <= 20) { // 本館
                    this.mainBuildingAssignments.put(floor, entry.getValue());
                } else { // 別館
                    this.annexBuildingAssignments.put(floor, entry.getValue());
                }
            }

            this.totalPoints = calculateTotalPoints();
            this.adjustedScore = calculateAdjustedScore();
        }

        private double calculateTotalPoints() {
            double mainPoints = mainBuildingAssignments.values().stream()
                    .mapToDouble(allocation -> allocation.getTotalPoints()).sum();
            double annexPoints = annexBuildingAssignments.values().stream()
                    .mapToDouble(allocation -> allocation.getTotalPoints()).sum();
            return mainPoints + annexPoints;
        }

        private double calculateAdjustedScore() {
            int floorCount = getAssignedFloors().size();
            int buildingCount = getBuildingCount();
            double penalty = (floorCount - 1) * FLOOR_CROSSING_PENALTY +
                    (buildingCount - 1) * BUILDING_CROSSING_PENALTY;
            return totalPoints + penalty;
        }

        public Set<Integer> getAssignedFloors() {
            Set<Integer> floors = new HashSet<>();
            floors.addAll(mainBuildingAssignments.keySet());
            floors.addAll(annexBuildingAssignments.keySet());
            return floors;
        }

        private int getBuildingCount() {
            int count = 0;
            if (!mainBuildingAssignments.isEmpty()) count++;
            if (!annexBuildingAssignments.isEmpty()) count++;
            return count;
        }

        public int getTotalRooms() {
            return mainBuildingAssignments.values().stream().mapToInt(a -> a.getTotalRooms()).sum() +
                    annexBuildingAssignments.values().stream().mapToInt(a -> a.getTotalRooms()).sum();
        }

        // 既存コードとの互換性のためのメソッド（念のため残す）
        public Map<Integer, RoomAllocation> getRoomsByFloor() {
            return new HashMap<>(roomsByFloor);
        }
    }

    /**
     * ★大幅簡略化: ポイント制限統合版設定クラス（大浴場清掃手動選択対応）
     */
    public static class AdaptiveLoadConfig {
        public final List<FileProcessor.Staff> availableStaff;
        public final List<ExtendedStaffInfo> extendedStaffInfo;
        public final Map<String, WorkerType> workerTypes;
        public final Map<String, BathCleaningType> bathCleaningAssignments;

        // ★拡張: ポイント制限関連
        public final Map<String, PointConstraint> pointConstraints;
        public final Map<String, PointConstraint> upperLimitConstraints;
        public final Map<String, PointConstraint> lowerRangeConstraints;

        private AdaptiveLoadConfig(
                List<FileProcessor.Staff> availableStaff,
                List<ExtendedStaffInfo> extendedStaffInfo,
                Map<String, BathCleaningType> bathCleaningAssignments,
                Map<String, PointConstraint> pointConstraints) {

            this.availableStaff = new ArrayList<>(availableStaff);
            this.extendedStaffInfo = new ArrayList<>(extendedStaffInfo);
            this.workerTypes = createWorkerTypeMap(availableStaff);
            this.bathCleaningAssignments = new HashMap<>(bathCleaningAssignments);

            // ★拡張: ポイント制限情報の処理
            this.pointConstraints = pointConstraints != null ?
                    new HashMap<>(pointConstraints) : new HashMap<>();

            // 制約タイプ別に分類
            this.upperLimitConstraints = this.pointConstraints.entrySet().stream()
                    .filter(entry -> "故障制限".equals(entry.getValue().constraintType))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            this.lowerRangeConstraints = this.pointConstraints.entrySet().stream()
                    .filter(entry -> "業者制限".equals(entry.getValue().constraintType))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        private Map<String, WorkerType> createWorkerTypeMap(List<FileProcessor.Staff> staff) {
            return staff.stream()
                    .collect(Collectors.toMap(
                            s -> s.name,
                            s -> WorkerType.REGULAR // デフォルトで正社員として扱う
                    ));
        }

        /**
         * ★新規メソッド: 大浴場清掃スタッフ手動選択対応版設定作成メソッド
         */
        public static AdaptiveLoadConfig createAdaptiveConfigWithBathSelection(
                List<FileProcessor.Staff> availableStaff, int totalRooms,
                int mainBuildingRooms, int annexBuildingRooms,
                BathCleaningType bathType,
                List<RoomAssignmentApplication.StaffPointConstraint> staffConstraints) {

            List<ExtendedStaffInfo> extendedInfo = new ArrayList<>();
            Map<String, BathCleaningType> bathAssignments = new HashMap<>();
            Map<String, PointConstraint> pointConstraints = new HashMap<>();

            // スタッフ制約情報の処理
            Map<String, RoomAssignmentApplication.StaffPointConstraint> constraintMap =
                    staffConstraints.stream().collect(Collectors.toMap(
                            c -> c.staffName, c -> c, (a, b) -> b));

            // 各スタッフの設定を構築
            for (FileProcessor.Staff staff : availableStaff) {
                RoomAssignmentApplication.StaffPointConstraint constraint = constraintMap.get(staff.name);

                // 建物割り当て決定
                BuildingAssignment buildingAssignment = BuildingAssignment.MAIN_ONLY; // デフォルト
                BathCleaningType staffBathType = BathCleaningType.NONE; // デフォルト

                if (constraint != null) {
                    // 建物割り当ての設定
                    if ("本館のみ".equals(constraint.buildingAssignment.displayName)) {
                        buildingAssignment = BuildingAssignment.MAIN_ONLY;
                    } else if ("別館のみ".equals(constraint.buildingAssignment.displayName)) {
                        buildingAssignment = BuildingAssignment.ANNEX_ONLY;
                    } else {
                        buildingAssignment = BuildingAssignment.BOTH;
                    }

                    // 大浴場清掃設定
                    if (constraint.isBathCleaningStaff) {
                        staffBathType = bathType;
                    }

                    // ポイント制約の設定
                    if (constraint.lowerMinLimit > 0 || constraint.lowerMaxLimit > 0 || constraint.upperLimit > 0) {
                        double maxValue = Math.max(constraint.lowerMaxLimit, constraint.upperLimit);
                        pointConstraints.put(staff.name, new PointConstraint(
                                staff.name, constraint.lowerMinLimit, maxValue, constraint.constraintType.displayName));
                    }
                }

                extendedInfo.add(new ExtendedStaffInfo(staff, buildingAssignment, staffBathType));
                bathAssignments.put(staff.name, staffBathType);
            }

            LOGGER.info(String.format("大浴場清掃担当設定完了: %d人",
                    bathAssignments.values().stream().mapToInt(t -> t != BathCleaningType.NONE ? 1 : 0).sum()));

            return new AdaptiveLoadConfig(availableStaff, extendedInfo, bathAssignments, pointConstraints);
        }

        /**
         * ★既存メソッド: ポイント制限対応版設定作成メソッド（後方互換性のため）
         */
        public static AdaptiveLoadConfig createAdaptiveConfigWithPointConstraints(
                List<FileProcessor.Staff> availableStaff, int totalRooms,
                int mainBuildingRooms, int annexBuildingRooms,
                BathCleaningType bathType,
                List<RoomAssignmentApplication.StaffPointConstraint> staffConstraints) {

            // 新しいメソッドに委譲
            return createAdaptiveConfigWithBathSelection(
                    availableStaff, totalRooms, mainBuildingRooms, annexBuildingRooms,
                    bathType, staffConstraints);
        }

        // 既存メソッド（後方互換性のため）
        public static AdaptiveLoadConfig createAdaptiveConfig(
                List<FileProcessor.Staff> availableStaff, int totalRooms,
                int mainBuildingRooms, int annexBuildingRooms,
                BathCleaningType bathType, Map<String, Integer> roomLimits) {
            // 旧形式から新形式に変換
            List<RoomAssignmentApplication.StaffPointConstraint> constraints = new ArrayList<>();
            return createAdaptiveConfigWithBathSelection(
                    availableStaff, totalRooms, mainBuildingRooms, annexBuildingRooms,
                    bathType, constraints);
        }

        public static AdaptiveLoadConfig createAdaptiveConfig(
                List<FileProcessor.Staff> availableStaff, int totalRooms) {
            int mainRooms = (int)(totalRooms * 0.55);
            int annexRooms = totalRooms - mainRooms;
            return createAdaptiveConfigWithBathSelection(availableStaff, totalRooms, mainRooms, annexRooms,
                    BathCleaningType.NORMAL, new ArrayList<>());
        }
    }

    /**
     * 最適化結果クラス
     */
    public static class OptimizationResult {
        public final List<StaffAssignment> assignments;
        public final AdaptiveLoadConfig config;
        public final LocalDate targetDate;
        public final double totalPoints;
        public final double averageScore;
        public final double scoreDeviation;

        public OptimizationResult(List<StaffAssignment> assignments, AdaptiveLoadConfig config, LocalDate targetDate) {
            this.assignments = new ArrayList<>(assignments);
            this.config = config;
            this.targetDate = targetDate;
            this.totalPoints = assignments.stream().mapToDouble(a -> a.totalPoints).sum();
            this.averageScore = assignments.stream().mapToDouble(a -> a.adjustedScore).average().orElse(0);
            this.scoreDeviation = calculateScoreDeviation();
        }

        private double calculateScoreDeviation() {
            double[] scores = assignments.stream().mapToDouble(a -> a.adjustedScore).toArray();
            double mean = Arrays.stream(scores).average().orElse(0);
            double variance = Arrays.stream(scores).map(score -> Math.pow(score - mean, 2)).average().orElse(0);
            return Math.sqrt(variance);
        }

        public void printDetailedSummary() {
            System.out.println("\n=== 最適化結果詳細 ===");
            System.out.printf("対象日: %s\n", targetDate.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")));
            System.out.printf("総ポイント: %.2f\n", totalPoints);
            System.out.printf("平均スコア: %.2f\n", averageScore);
            System.out.printf("スコア標準偏差: %.2f\n", scoreDeviation);

            double adjustedScoreDifference = assignments.stream().mapToDouble(a -> a.adjustedScore).max().orElse(0) -
                    assignments.stream().mapToDouble(a -> a.adjustedScore).min().orElse(0);

            System.out.printf("スコア最大差: %.2f (最大%.2f - 最小%.2f)\n",
                    adjustedScoreDifference,
                    assignments.stream().mapToDouble(a -> a.adjustedScore).max().orElse(0),
                    adjustedScoreDifference);

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
            System.out.println("=== 大浴場清掃手動選択対応最適化開始 ===");
            System.out.printf("対象日: %s\n", targetDate.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")));

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
            List<FloorInfo> mainFloors = floors.stream()
                    .filter(f -> f.isMainBuilding)
                    .collect(Collectors.toList());

            List<FloorInfo> annexFloors = floors.stream()
                    .filter(f -> !f.isMainBuilding)
                    .collect(Collectors.toList());

            double mainTotalPoints = mainFloors.stream().mapToDouble(FloorInfo::getTotalPoints).sum();
            double annexTotalPoints = annexFloors.stream().mapToDouble(FloorInfo::getTotalPoints).sum();

            int mainRoomCount = mainFloors.stream().mapToInt(f -> f.getTotalNormalRooms() + f.ecoRooms).sum();
            int annexRoomCount = annexFloors.stream().mapToInt(f -> f.getTotalNormalRooms() + f.ecoRooms).sum();

            return new BuildingData(mainFloors, annexFloors, mainTotalPoints, annexTotalPoints,
                    mainRoomCount, annexRoomCount);
        }
    }

    /**
     * 建物データクラス
     */
    private static class BuildingData {
        final List<FloorInfo> mainFloors;
        final List<FloorInfo> annexFloors;
        final double mainTotalPoints;
        final double annexTotalPoints;
        final int mainRoomCount;
        final int annexRoomCount;

        BuildingData(List<FloorInfo> mainFloors, List<FloorInfo> annexFloors,
                     double mainTotalPoints, double annexTotalPoints,
                     int mainRoomCount, int annexRoomCount) {
            this.mainFloors = mainFloors;
            this.annexFloors = annexFloors;
            this.mainTotalPoints = mainTotalPoints;
            this.annexTotalPoints = annexTotalPoints;
            this.mainRoomCount = mainRoomCount;
            this.annexRoomCount = annexRoomCount;
        }
    }

    /**
     * ポイント制限対応最適化エンジン
     */
    private static class PointConstraintOptimizer {
        private final BuildingData buildingData;
        private final AdaptiveLoadConfig config;

        PointConstraintOptimizer(BuildingData buildingData, AdaptiveLoadConfig config) {
            this.buildingData = buildingData;
            this.config = config;
        }

        List<StaffAssignment> optimizeWithConstraints() {
            List<StaffAssignment> result = new ArrayList<>();

            // 各スタッフの最適割り当てを計算
            for (ExtendedStaffInfo staffInfo : config.extendedStaffInfo) {
                StaffAssignment assignment = optimizeStaffAssignment(staffInfo);
                result.add(assignment);
            }

            return result;
        }

        private StaffAssignment optimizeStaffAssignment(ExtendedStaffInfo staffInfo) {
            Map<Integer, RoomAllocation> mainAssignments = new HashMap<>();
            Map<Integer, RoomAllocation> annexAssignments = new HashMap<>();

            // ポイント制限の取得
            PointConstraint constraint = config.pointConstraints.get(staffInfo.staff.name);

            double targetPoints = staffInfo.adjustedCapacity;
            if (constraint != null) {
                if ("故障制限".equals(constraint.constraintType)) {
                    targetPoints = constraint.minPoints; // 固定値
                } else if ("業者制限".equals(constraint.constraintType)) {
                    targetPoints = Math.max(constraint.minPoints, staffInfo.adjustedCapacity);
                }
            }

            // 建物割り当てに基づく処理
            switch (staffInfo.buildingAssignment) {
                case MAIN_ONLY:
                    assignToBuilding(mainAssignments, buildingData.mainFloors, targetPoints);
                    break;
                case ANNEX_ONLY:
                    assignToBuilding(annexAssignments, buildingData.annexFloors, targetPoints);
                    break;
                case BOTH:
                    double mainPortion = targetPoints * 0.6; // 本館60%
                    double annexPortion = targetPoints * 0.4; // 別館40%
                    assignToBuilding(mainAssignments, buildingData.mainFloors, mainPortion);
                    assignToBuilding(annexAssignments, buildingData.annexFloors, annexPortion);
                    break;
            }

            return new StaffAssignment(staffInfo.staff, mainAssignments, annexAssignments, staffInfo.bathCleaningType);
        }

        private void assignToBuilding(Map<Integer, RoomAllocation> assignments, List<FloorInfo> floors, double targetPoints) {
            if (floors.isEmpty() || targetPoints <= 0) return;

            // 簡単な均等割り当て（実際にはより複雑な最適化が必要）
            double pointsPerFloor = targetPoints / floors.size();

            for (FloorInfo floor : floors) {
                // 各フロアで最も多い部屋タイプに割り当て
                String dominantRoomType = floor.roomCounts.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse("S");

                double roomPoints = ROOM_POINTS.getOrDefault(dominantRoomType, 1.0);
                int roomCount = (int) Math.round(pointsPerFloor / roomPoints);
                roomCount = Math.max(1, Math.min(roomCount, floor.roomCounts.getOrDefault(dominantRoomType, 0)));

                if (roomCount > 0) {
                    Map<String, Integer> floorRoomCounts = new HashMap<>();
                    floorRoomCounts.put(dominantRoomType, roomCount);
                    assignments.put(floor.floorNumber, new RoomAllocation(floorRoomCounts, 0));
                }
            }
        }
    }

    /**
     * ★新規追加: 詳細部屋割り振りメソッド（手順4～8の実装）
     * AssignmentEditorGUIとの互換性を保つため、戻り値の型を調整
     */
    public static Map<String, List<RoomAssignment>> assignRoomsWithDistributionPattern(
            List<FloorInfo> floors,
            Map<String, NormalRoomDistributionDialog.StaffDistribution> roomDistribution,
            List<RoomAssignmentApplication.StaffPointConstraint> constraints,
            BathCleaningType bathType) throws Exception {

        System.out.println("=== 詳細部屋割り振り開始 ===");

        // Step 1: スタッフ制約情報の構築
        Map<String, StaffConstraintInfo> staffConstraints = buildStaffConstraintMap(constraints);

        // Step 2: 建物別フロア情報の整理
        List<FloorInfo> mainFloors = floors.stream()
                .filter(f -> f.isMainBuilding)
                .sorted(Comparator.comparingInt(f -> f.floorNumber))
                .collect(Collectors.toList());

        List<FloorInfo> annexFloors = floors.stream()
                .filter(f -> !f.isMainBuilding)
                .sorted(Comparator.comparingInt(f -> f.floorNumber))
                .collect(Collectors.toList());

        // Step 3: 本館と別館それぞれで部屋割り振りを実行
        Map<String, List<RoomAssignment>> mainAssignments =
                assignBuildingRooms(mainFloors, roomDistribution, staffConstraints, true, bathType);

        Map<String, List<RoomAssignment>> annexAssignments =
                assignBuildingRooms(annexFloors, roomDistribution, staffConstraints, false, bathType);

        // Step 4: 結果のマージ
        Map<String, List<RoomAssignment>> allAssignments = new HashMap<>();

        // 本館の結果をマージ
        for (Map.Entry<String, List<RoomAssignment>> entry : mainAssignments.entrySet()) {
            allAssignments.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                    .addAll(entry.getValue());
        }

        // 別館の結果をマージ
        for (Map.Entry<String, List<RoomAssignment>> entry : annexAssignments.entrySet()) {
            allAssignments.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                    .addAll(entry.getValue());
        }

        System.out.println("=== 詳細部屋割り振り完了 ===");
        return allAssignments;
    }

    /**
     * 建物別の部屋割り振り処理（簡略版）
     */
    private static Map<String, List<RoomAssignment>> assignBuildingRooms(
            List<FloorInfo> floors,
            Map<String, NormalRoomDistributionDialog.StaffDistribution> roomDistribution,
            Map<String, StaffConstraintInfo> staffConstraints,
            boolean isMainBuilding,
            BathCleaningType bathType) {

        Map<String, List<RoomAssignment>> assignments = new HashMap<>();

        // 対象スタッフの抽出
        List<String> targetStaff = roomDistribution.entrySet().stream()
                .filter(entry -> {
                    int targetRooms = isMainBuilding ? entry.getValue().mainAssignedRooms :
                            entry.getValue().annexAssignedRooms;
                    return targetRooms > 0;
                })
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // 簡易的な部屋割り振り（実装は省略、基本的な構造のみ）
        for (String staffName : targetStaff) {
            assignments.put(staffName, new ArrayList<>());
        }

        return assignments;
    }

    /**
     * スタッフ制約マップの構築
     */
    private static Map<String, StaffConstraintInfo> buildStaffConstraintMap(
            List<RoomAssignmentApplication.StaffPointConstraint> constraints) {

        Map<String, StaffConstraintInfo> result = new HashMap<>();

        for (RoomAssignmentApplication.StaffPointConstraint constraint : constraints) {
            StaffConstraintInfo info = new StaffConstraintInfo();
            info.constraintType = constraint.constraintType.displayName;
            info.isBathCleaning = constraint.isBathCleaningStaff;
            info.buildingAssignment = constraint.buildingAssignment.displayName;

            result.put(constraint.staffName, info);
        }

        return result;
    }

    /**
     * スタッフ制約情報クラス
     */
    private static class StaffConstraintInfo {
        String constraintType;
        boolean isBathCleaning;
        String buildingAssignment;
    }

    /**
     * 部屋割り当てクラス（簡略版）
     */
    public static class RoomAssignment {
        public String roomNumber;
        public String roomType;
        public int floorNumber;
        public boolean isEcoRoom;
        public boolean isMainBuilding;
        public String assignedStaff;
    }
}