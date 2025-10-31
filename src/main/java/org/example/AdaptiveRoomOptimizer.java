package org.example;

import java.util.*;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

/**
 * 適応型清掃管理最適化システム（建物分離ポイントベース版）
 * ★修正版: 通常清掃部屋を設定値通り割り振り、その後エコ清掃を割り振る
 * フロア制限: 業者制限以外は2階分、大浴清掃スタッフは通常1階+エコで2階分まで
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

    // 部屋タイプ別の実質ポイント
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
     * 作業者タイプ
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
     * 部屋割り当てクラス
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
     * スタッフ割り当て結果クラス
     */
    public static class StaffAssignment {
        public final FileProcessor.Staff staff;
        public final WorkerType workerType;
        public final List<Integer> floors;
        public final Map<Integer, RoomAllocation> mainBuildingAssignments;
        public final Map<Integer, RoomAllocation> annexBuildingAssignments;
        public final Map<Integer, RoomAllocation> roomsByFloor;
        public final double totalPoints;
        public final double adjustedScore;
        public final BathCleaningType bathCleaningType;

        public StaffAssignment(FileProcessor.Staff staff,
                               Map<Integer, RoomAllocation> mainAssignments,
                               Map<Integer, RoomAllocation> annexAssignments,
                               BathCleaningType bathType) {
            this.staff = staff;
            this.workerType = WorkerType.REGULAR;
            this.mainBuildingAssignments = new HashMap<>(mainAssignments);
            this.annexBuildingAssignments = new HashMap<>(annexAssignments);
            this.bathCleaningType = bathType;

            Set<Integer> allFloors = new HashSet<>();
            allFloors.addAll(mainAssignments.keySet());
            allFloors.addAll(annexAssignments.keySet());
            this.floors = new ArrayList<>(allFloors);
            Collections.sort(this.floors);

            this.roomsByFloor = new HashMap<>();
            this.roomsByFloor.putAll(mainAssignments);
            this.roomsByFloor.putAll(annexAssignments);

            this.totalPoints = calculateTotalPoints();
            this.adjustedScore = calculateAdjustedScore();
        }

        public StaffAssignment(FileProcessor.Staff staff,
                               WorkerType workerType,
                               List<Integer> floors,
                               Map<Integer, RoomAllocation> roomsByFloor) {
            this.staff = staff;
            this.workerType = workerType;
            this.floors = new ArrayList<>(floors);
            this.roomsByFloor = new HashMap<>(roomsByFloor);
            this.bathCleaningType = BathCleaningType.NONE;

            this.mainBuildingAssignments = new HashMap<>();
            this.annexBuildingAssignments = new HashMap<>();

            for (Map.Entry<Integer, RoomAllocation> entry : roomsByFloor.entrySet()) {
                int floor = entry.getKey();
                if (floor <= 20) {
                    this.mainBuildingAssignments.put(floor, entry.getValue());
                } else {
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

        public Map<Integer, RoomAllocation> getRoomsByFloor() {
            return new HashMap<>(roomsByFloor);
        }
    }

    /**
     * 適応型設定クラス
     */
    public static class AdaptiveLoadConfig {
        public final List<FileProcessor.Staff> staff;
        public final List<ExtendedStaffInfo> extendedStaffInfo;
        public final Map<String, BathCleaningType> bathAssignments;
        public final Map<String, PointConstraint> pointConstraints;
        public final Map<String, NormalRoomDistributionDialog.StaffDistribution> roomDistribution;

        public AdaptiveLoadConfig(List<FileProcessor.Staff> staff,
                                  List<ExtendedStaffInfo> extendedInfo,
                                  Map<String, BathCleaningType> bathAssignments,
                                  Map<String, PointConstraint> pointConstraints,
                                  Map<String, NormalRoomDistributionDialog.StaffDistribution> roomDistribution) {
            this.staff = new ArrayList<>(staff);
            this.extendedStaffInfo = new ArrayList<>(extendedInfo);
            this.bathAssignments = new HashMap<>(bathAssignments);
            this.pointConstraints = new HashMap<>(pointConstraints);
            this.roomDistribution = roomDistribution != null ? new HashMap<>(roomDistribution) : null;
        }

        public static AdaptiveLoadConfig createAdaptiveConfigWithBathSelection(
                List<FileProcessor.Staff> availableStaff, int totalRooms,
                int mainBuildingRooms, int annexBuildingRooms,
                BathCleaningType bathType,
                List<RoomAssignmentApplication.StaffPointConstraint> staffConstraints,
                Map<String, NormalRoomDistributionDialog.StaffDistribution> roomDistribution) {

            List<ExtendedStaffInfo> extendedInfo = new ArrayList<>();
            Map<String, BathCleaningType> bathAssignments = new HashMap<>();
            Map<String, PointConstraint> pointConstraints = new HashMap<>();

            for (FileProcessor.Staff staff : availableStaff) {
                BuildingAssignment buildingAssignment = BuildingAssignment.BOTH;
                BathCleaningType staffBathType = BathCleaningType.NONE;

                for (RoomAssignmentApplication.StaffPointConstraint constraint : staffConstraints) {
                    if (!constraint.staffName.equals(staff.name)) continue;

                    if ("本館のみ".equals(constraint.buildingAssignment.displayName)) {
                        buildingAssignment = BuildingAssignment.MAIN_ONLY;
                    } else if ("別館のみ".equals(constraint.buildingAssignment.displayName)) {
                        buildingAssignment = BuildingAssignment.ANNEX_ONLY;
                    } else {
                        buildingAssignment = BuildingAssignment.BOTH;
                    }

                    if (constraint.isBathCleaningStaff) {
                        staffBathType = bathType;
                    }

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

            return new AdaptiveLoadConfig(availableStaff, extendedInfo, bathAssignments, pointConstraints, roomDistribution);
        }

        public static AdaptiveLoadConfig createAdaptiveConfigWithBathSelection(
                List<FileProcessor.Staff> availableStaff, int totalRooms,
                int mainBuildingRooms, int annexBuildingRooms,
                BathCleaningType bathType,
                List<RoomAssignmentApplication.StaffPointConstraint> staffConstraints) {
            return createAdaptiveConfigWithBathSelection(
                    availableStaff, totalRooms, mainBuildingRooms, annexBuildingRooms,
                    bathType, staffConstraints, null);
        }
    }

    /**
     * 適応型最適化エンジン
     */
    public static class AdaptiveOptimizer {
        private final List<FloorInfo> floors;
        private final AdaptiveLoadConfig config;

        public AdaptiveOptimizer(List<FloorInfo> floors, AdaptiveLoadConfig config) {
            this.floors = new ArrayList<>(floors);
            this.config = config;
        }

        public OptimizationResult optimize(LocalDate targetDate) {
            LOGGER.info("=== 適応型最適化を開始 ===");

            BuildingData buildingData = separateBuildings(floors);

            List<StaffAssignment> assignments;

            if (config.roomDistribution != null && !config.roomDistribution.isEmpty()) {
                LOGGER.info("通常清掃部屋割り振り設定を使用します");
                assignments = optimizeWithRoomDistribution(buildingData);
            } else {
                LOGGER.info("従来の最適化ロジックを使用します");
                PointConstraintOptimizer optimizer = new PointConstraintOptimizer(buildingData, config);
                assignments = optimizer.optimizeWithConstraints();
            }

            return new OptimizationResult(assignments, config, targetDate);
        }

        /**
         * ★修正: roomDistribution を使用した最適化
         * 通常清掃を設定値通り割り振り、その後エコ清掃を割り振る
         */
        private List<StaffAssignment> optimizeWithRoomDistribution(BuildingData buildingData) {
            // フロア別の利用可能な部屋データを構築
            Map<String, FloorRoomPool> roomPools = buildRoomPools(buildingData);

            // スタッフ別の割り当て状況を追跡
            Map<String, StaffAllocationTracker> staffTrackers = new HashMap<>();
            for (ExtendedStaffInfo staffInfo : config.extendedStaffInfo) {
                staffTrackers.put(staffInfo.staff.name, new StaffAllocationTracker(staffInfo));
            }

            // フェーズ1: 通常清掃部屋の割り振り
            LOGGER.info("=== フェーズ1: 通常清掃部屋の割り振り ===");
            assignNormalRooms(buildingData, roomPools, staffTrackers);

            // フェーズ2: エコ清掃部屋の割り振り
            LOGGER.info("=== フェーズ2: エコ清掃部屋の割り振り ===");
            assignEcoRooms(buildingData, roomPools, staffTrackers);

            // 結果の構築
            List<StaffAssignment> assignments = new ArrayList<>();
            for (StaffAllocationTracker tracker : staffTrackers.values()) {
                assignments.add(tracker.buildAssignment());
            }

            return assignments;
        }

        /**
         * フロア別の部屋プールを構築
         */
        private Map<String, FloorRoomPool> buildRoomPools(BuildingData buildingData) {
            Map<String, FloorRoomPool> pools = new HashMap<>();

            for (FloorInfo floor : buildingData.mainFloors) {
                String key = "本館_" + floor.floorNumber;
                pools.put(key, new FloorRoomPool(floor));
            }

            for (FloorInfo floor : buildingData.annexFloors) {
                String key = "別館_" + floor.floorNumber;
                pools.put(key, new FloorRoomPool(floor));
            }

            return pools;
        }

        /**
         * 通常清掃部屋の割り振り
         */
        private void assignNormalRooms(BuildingData buildingData,
                                       Map<String, FloorRoomPool> roomPools,
                                       Map<String, StaffAllocationTracker> staffTrackers) {

            for (Map.Entry<String, StaffAllocationTracker> entry : staffTrackers.entrySet()) {
                String staffName = entry.getKey();
                StaffAllocationTracker tracker = entry.getValue();

                NormalRoomDistributionDialog.StaffDistribution dist = config.roomDistribution.get(staffName);
                if (dist == null) {
                    LOGGER.warning("スタッフ " + staffName + " の部屋割り振り設定が見つかりません");
                    continue;
                }

                LOGGER.info(String.format("%s: 本館%d室, 別館%d室を割り振り開始",
                        staffName, dist.mainAssignedRooms, dist.annexAssignedRooms));

                // 本館の割り当て
                if (dist.mainAssignedRooms > 0) {
                    assignNormalRoomsToBuilding(buildingData.mainFloors, roomPools, tracker,
                            dist.mainAssignedRooms, true);
                }

                // 別館の割り当て
                if (dist.annexAssignedRooms > 0) {
                    assignNormalRoomsToBuilding(buildingData.annexFloors, roomPools, tracker,
                            dist.annexAssignedRooms, false);
                }

                LOGGER.info(String.format("%s: 通常清掃 合計%d室割り振り完了（フロア数:%d）",
                        staffName, tracker.getTotalNormalRooms(), tracker.getFloorCount()));
            }
        }

        /**
         * 建物別の通常清掃部屋割り振り
         */
        private void assignNormalRoomsToBuilding(List<FloorInfo> floors,
                                                 Map<String, FloorRoomPool> roomPools,
                                                 StaffAllocationTracker tracker,
                                                 int targetRooms,
                                                 boolean isMainBuilding) {

            String buildingName = isMainBuilding ? "本館" : "別館";
            int remaining = targetRooms;
            int maxFloors = tracker.getMaxNormalFloors();
            int currentNormalFloors = tracker.getNormalFloorCount();

            LOGGER.info(String.format("  %s: 目標%d室, 最大フロア数%d, 現在の通常フロア数%d",
                    buildingName, targetRooms, maxFloors, currentNormalFloors));

            for (FloorInfo floor : floors) {
                if (remaining <= 0) break;

                // フロア制限チェック
                if (!tracker.isContractor() && currentNormalFloors >= maxFloors) {
                    LOGGER.info(String.format("  %dF: 通常清掃フロア制限に達しました", floor.floorNumber));
                    break;
                }

                String poolKey = buildingName + "_" + floor.floorNumber;
                FloorRoomPool pool = roomPools.get(poolKey);

                if (pool == null || pool.getNormalRoomsAvailable() == 0) {
                    continue;
                }

                int toAssign = Math.min(remaining, pool.getNormalRoomsAvailable());
                Map<String, Integer> assignedRooms = pool.allocateNormalRooms(toAssign);

                if (!assignedRooms.isEmpty()) {
                    tracker.addNormalRooms(floor.floorNumber, assignedRooms, isMainBuilding);
                    remaining -= assignedRooms.values().stream().mapToInt(Integer::intValue).sum();
                    currentNormalFloors = tracker.getNormalFloorCount();

                    LOGGER.info(String.format("  %dF: %d室割り振り（残り%d室, フロア数%d）",
                            floor.floorNumber, toAssign, remaining, currentNormalFloors));
                }
            }

            if (remaining > 0) {
                LOGGER.warning(String.format("  %s: %d室を割り振れませんでした",
                        buildingName, remaining));
            }
        }

        /**
         * エコ清掃部屋の割り振り
         */
        private void assignEcoRooms(BuildingData buildingData,
                                    Map<String, FloorRoomPool> roomPools,
                                    Map<String, StaffAllocationTracker> staffTrackers) {

            List<String> staffNames = new ArrayList<>(staffTrackers.keySet());
            int staffIndex = 0;

            // 全フロアを走査してエコ清掃部屋を割り振る
            List<FloorInfo> allFloors = new ArrayList<>();
            allFloors.addAll(buildingData.mainFloors);
            allFloors.addAll(buildingData.annexFloors);

            Collections.sort(allFloors, Comparator.comparingInt(f -> f.floorNumber));

            for (FloorInfo floor : allFloors) {
                String buildingName = floor.isMainBuilding ? "本館" : "別館";
                String poolKey = buildingName + "_" + floor.floorNumber;
                FloorRoomPool pool = roomPools.get(poolKey);

                if (pool == null || pool.getEcoRoomsAvailable() == 0) {
                    continue;
                }

                LOGGER.info(String.format("%s %dF: エコ%d室を割り振り中",
                        buildingName, floor.floorNumber, pool.getEcoRoomsAvailable()));

                while (pool.getEcoRoomsAvailable() > 0) {
                    boolean assigned = false;
                    int attempts = 0;

                    while (attempts < staffNames.size()) {
                        String staffName = staffNames.get(staffIndex % staffNames.size());
                        StaffAllocationTracker tracker = staffTrackers.get(staffName);
                        staffIndex++;
                        attempts++;

                        // フロア制限チェック
                        if (!tracker.canAssignEcoFloor(floor.floorNumber)) {
                            continue;
                        }

                        // 1部屋割り当て
                        int allocatedEco = pool.allocateEcoRooms(1);
                        if (allocatedEco > 0) {
                            tracker.addEcoRooms(floor.floorNumber, allocatedEco, floor.isMainBuilding);
                            assigned = true;
                            LOGGER.info(String.format("  %dF: %sにエコ1室割り振り（残り%d室）",
                                    floor.floorNumber, staffName, pool.getEcoRoomsAvailable()));
                            break;
                        }
                    }

                    if (!assigned) {
                        LOGGER.warning(String.format("  %dF: エコ%d室をフロア制限により割り振れませんでした",
                                floor.floorNumber, pool.getEcoRoomsAvailable()));
                        break;
                    }
                }
            }
        }

        private BuildingData separateBuildings(List<FloorInfo> allFloors) {
            List<FloorInfo> mainFloors = allFloors.stream()
                    .filter(f -> f.isMainBuilding)
                    .sorted(Comparator.comparingInt(f -> f.floorNumber))
                    .collect(Collectors.toList());

            List<FloorInfo> annexFloors = allFloors.stream()
                    .filter(f -> !f.isMainBuilding)
                    .sorted(Comparator.comparingInt(f -> f.floorNumber))
                    .collect(Collectors.toList());

            double mainTotalPoints = mainFloors.stream()
                    .mapToDouble(FloorInfo::getTotalPoints)
                    .sum();

            double annexTotalPoints = annexFloors.stream()
                    .mapToDouble(FloorInfo::getTotalPoints)
                    .sum();

            int mainRoomCount = mainFloors.stream()
                    .mapToInt(f -> f.getTotalRooms())
                    .sum();

            int annexRoomCount = annexFloors.stream()
                    .mapToInt(f -> f.getTotalRooms())
                    .sum();

            return new BuildingData(mainFloors, annexFloors, mainTotalPoints, annexTotalPoints,
                    mainRoomCount, annexRoomCount);
        }
    }

    /**
     * フロア部屋プールクラス
     */
    private static class FloorRoomPool {
        private final FloorInfo floor;
        private final Map<String, Integer> normalRoomsAvailable;
        private int ecoRoomsAvailable;

        FloorRoomPool(FloorInfo floor) {
            this.floor = floor;
            this.normalRoomsAvailable = new HashMap<>(floor.roomCounts);
            this.ecoRoomsAvailable = floor.ecoRooms;
        }

        int getNormalRoomsAvailable() {
            return normalRoomsAvailable.values().stream().mapToInt(Integer::intValue).sum();
        }

        int getEcoRoomsAvailable() {
            return ecoRoomsAvailable;
        }

        Map<String, Integer> allocateNormalRooms(int count) {
            Map<String, Integer> allocated = new HashMap<>();
            int remaining = count;

            List<String> roomTypes = new ArrayList<>(normalRoomsAvailable.keySet());
            roomTypes.sort((a, b) -> Integer.compare(
                    normalRoomsAvailable.get(b), normalRoomsAvailable.get(a)));

            for (String roomType : roomTypes) {
                if (remaining <= 0) break;

                int available = normalRoomsAvailable.get(roomType);
                int toAllocate = Math.min(remaining, available);

                if (toAllocate > 0) {
                    allocated.put(roomType, toAllocate);
                    normalRoomsAvailable.put(roomType, available - toAllocate);
                    remaining -= toAllocate;
                }
            }

            return allocated;
        }

        int allocateEcoRooms(int count) {
            int toAllocate = Math.min(count, ecoRoomsAvailable);
            ecoRoomsAvailable -= toAllocate;
            return toAllocate;
        }
    }

    /**
     * スタッフ割り当て追跡クラス
     */
    private static class StaffAllocationTracker {
        private final ExtendedStaffInfo staffInfo;
        private final Map<Integer, Map<String, Integer>> mainNormalRooms = new HashMap<>();
        private final Map<Integer, Map<String, Integer>> annexNormalRooms = new HashMap<>();
        private final Map<Integer, Integer> mainEcoRooms = new HashMap<>();
        private final Map<Integer, Integer> annexEcoRooms = new HashMap<>();

        StaffAllocationTracker(ExtendedStaffInfo staffInfo) {
            this.staffInfo = staffInfo;
        }

        void addNormalRooms(int floor, Map<String, Integer> rooms, boolean isMainBuilding) {
            if (isMainBuilding) {
                mainNormalRooms.put(floor, new HashMap<>(rooms));
            } else {
                annexNormalRooms.put(floor, new HashMap<>(rooms));
            }
        }

        void addEcoRooms(int floor, int count, boolean isMainBuilding) {
            if (isMainBuilding) {
                mainEcoRooms.merge(floor, count, Integer::sum);
            } else {
                annexEcoRooms.merge(floor, count, Integer::sum);
            }
        }

        int getTotalNormalRooms() {
            int total = 0;
            for (Map<String, Integer> rooms : mainNormalRooms.values()) {
                total += rooms.values().stream().mapToInt(Integer::intValue).sum();
            }
            for (Map<String, Integer> rooms : annexNormalRooms.values()) {
                total += rooms.values().stream().mapToInt(Integer::intValue).sum();
            }
            return total;
        }

        int getFloorCount() {
            Set<Integer> floors = new HashSet<>();
            floors.addAll(mainNormalRooms.keySet());
            floors.addAll(annexNormalRooms.keySet());
            floors.addAll(mainEcoRooms.keySet());
            floors.addAll(annexEcoRooms.keySet());
            return floors.size();
        }

        int getNormalFloorCount() {
            Set<Integer> floors = new HashSet<>();
            floors.addAll(mainNormalRooms.keySet());
            floors.addAll(annexNormalRooms.keySet());
            return floors.size();
        }

        boolean isContractor() {
            PointConstraint constraint = staffInfo.bathCleaningType != BathCleaningType.NONE ? null :
                    null; // 実際のconstraintはconfigから取得する必要がある
            return constraint != null && "業者制限".equals(constraint.constraintType);
        }

        int getMaxNormalFloors() {
            if (isContractor()) {
                return Integer.MAX_VALUE;
            }
            return staffInfo.bathCleaningType != BathCleaningType.NONE ? 1 : 2;
        }

        boolean canAssignEcoFloor(int floor) {
            Set<Integer> allFloors = new HashSet<>();
            allFloors.addAll(mainNormalRooms.keySet());
            allFloors.addAll(annexNormalRooms.keySet());
            allFloors.addAll(mainEcoRooms.keySet());
            allFloors.addAll(annexEcoRooms.keySet());

            if (allFloors.contains(floor)) {
                return true;
            }

            if (isContractor()) {
                return true;
            }

            return allFloors.size() < 2;
        }

        StaffAssignment buildAssignment() {
            Map<Integer, RoomAllocation> mainAssignments = new HashMap<>();
            Map<Integer, RoomAllocation> annexAssignments = new HashMap<>();

            for (Map.Entry<Integer, Map<String, Integer>> entry : mainNormalRooms.entrySet()) {
                int floor = entry.getKey();
                Map<String, Integer> rooms = entry.getValue();
                int eco = mainEcoRooms.getOrDefault(floor, 0);
                mainAssignments.put(floor, new RoomAllocation(rooms, eco));
            }

            for (Integer floor : mainEcoRooms.keySet()) {
                if (!mainAssignments.containsKey(floor)) {
                    mainAssignments.put(floor, new RoomAllocation(new HashMap<>(), mainEcoRooms.get(floor)));
                }
            }

            for (Map.Entry<Integer, Map<String, Integer>> entry : annexNormalRooms.entrySet()) {
                int floor = entry.getKey();
                Map<String, Integer> rooms = entry.getValue();
                int eco = annexEcoRooms.getOrDefault(floor, 0);
                annexAssignments.put(floor, new RoomAllocation(rooms, eco));
            }

            for (Integer floor : annexEcoRooms.keySet()) {
                if (!annexAssignments.containsKey(floor)) {
                    annexAssignments.put(floor, new RoomAllocation(new HashMap<>(), annexEcoRooms.get(floor)));
                }
            }

            return new StaffAssignment(staffInfo.staff, mainAssignments, annexAssignments,
                    staffInfo.bathCleaningType);
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
     * ポイント制限対応最適化エンジン（従来ロジック用）
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

            for (ExtendedStaffInfo staffInfo : config.extendedStaffInfo) {
                StaffAssignment assignment = optimizeStaffAssignment(staffInfo);
                result.add(assignment);
            }

            return result;
        }

        private StaffAssignment optimizeStaffAssignment(ExtendedStaffInfo staffInfo) {
            Map<Integer, RoomAllocation> mainAssignments = new HashMap<>();
            Map<Integer, RoomAllocation> annexAssignments = new HashMap<>();

            PointConstraint constraint = config.pointConstraints.get(staffInfo.staff.name);

            double targetPoints = staffInfo.adjustedCapacity;
            if (constraint != null) {
                if ("故障制限".equals(constraint.constraintType)) {
                    targetPoints = constraint.minPoints;
                } else if ("業者制限".equals(constraint.constraintType)) {
                    targetPoints = Math.max(constraint.minPoints, staffInfo.adjustedCapacity);
                }
            }

            switch (staffInfo.buildingAssignment) {
                case MAIN_ONLY:
                    assignToBuilding(mainAssignments, buildingData.mainFloors, targetPoints);
                    break;
                case ANNEX_ONLY:
                    assignToBuilding(annexAssignments, buildingData.annexFloors, targetPoints);
                    break;
                case BOTH:
                    double mainPortion = targetPoints * 0.6;
                    double annexPortion = targetPoints * 0.4;
                    assignToBuilding(mainAssignments, buildingData.mainFloors, mainPortion);
                    assignToBuilding(annexAssignments, buildingData.annexFloors, annexPortion);
                    break;
            }

            return new StaffAssignment(staffInfo.staff, mainAssignments, annexAssignments, staffInfo.bathCleaningType);
        }

        private void assignToBuilding(Map<Integer, RoomAllocation> assignments, List<FloorInfo> floors, double targetPoints) {
            if (floors.isEmpty() || targetPoints <= 0) return;

            double pointsPerFloor = targetPoints / floors.size();

            for (FloorInfo floor : floors) {
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
     * 最適化結果クラス
     */
    public static class OptimizationResult {
        public final List<StaffAssignment> assignments;
        public final AdaptiveLoadConfig config;
        public final LocalDate targetDate;

        public OptimizationResult(List<StaffAssignment> assignments,
                                  AdaptiveLoadConfig config,
                                  LocalDate targetDate) {
            this.assignments = new ArrayList<>(assignments);
            this.config = config;
            this.targetDate = targetDate;
        }

        public void printDetailedSummary() {
            System.out.println("\n=== 最適化結果サマリー ===");
            System.out.printf("対象日: %s\n", targetDate.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")));
            System.out.printf("スタッフ数: %d人\n", assignments.size());

            double totalPoints = 0;
            int totalRooms = 0;

            for (StaffAssignment assignment : assignments) {
                totalPoints += assignment.totalPoints;
                totalRooms += assignment.getTotalRooms();

                System.out.printf("\n%s (%s):\n",
                        assignment.staff.name,
                        assignment.bathCleaningType.displayName);
                System.out.printf("  部屋数: %d室\n", assignment.getTotalRooms());
                System.out.printf("  ポイント: %.2f\n", assignment.totalPoints);
                System.out.printf("  担当フロア: %s\n", assignment.floors);
            }

            System.out.printf("\n総計:\n");
            System.out.printf("  部屋数: %d室\n", totalRooms);
            System.out.printf("  ポイント: %.2f\n", totalPoints);
        }
    }
}