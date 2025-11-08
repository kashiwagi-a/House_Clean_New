package org.example;

import java.util.*;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

/**
 * 適応型清掃管理最適化システム（部屋数ベース版）
 * ★簡略化版: 部屋数ベースの割り振りに特化、ポイントベースロジックを削除
 */
public class AdaptiveRoomOptimizer {

    private static final Logger LOGGER = Logger.getLogger(AdaptiveRoomOptimizer.class.getName());

    /**
     * 大浴場清掃タイプ
     */
    public enum BathCleaningType {
        NONE("なし", 0),
        NORMAL("大浴場清掃", 4),
        WITH_DRAINING("湯抜きあり", 5);

        public final String displayName;
        public final int reduction;

        BathCleaningType(String displayName, int reduction) {
            this.displayName = displayName;
            this.reduction = reduction;
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
    }

    /**
     * スタッフ拡張情報クラス
     */
    public static class ExtendedStaffInfo {
        public final FileProcessor.Staff staff;
        public final BathCleaningType bathCleaningType;

        public ExtendedStaffInfo(FileProcessor.Staff staff,
                                 BathCleaningType bathType) {
            this.staff = staff;
            this.bathCleaningType = bathType;
        }
    }

    /**
     * ポイント制約クラス（業者判定とフロア制限に使用）
     */
    public static class PointConstraint {
        public final String staffName;
        public final String constraintType;

        public PointConstraint(String staffName, String constraintType) {
            this.staffName = staffName;
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
    }

    /**
     * スタッフ割り当て結果クラス
     */
    public static class StaffAssignment {
        public final FileProcessor.Staff staff;
        public final List<Integer> floors;
        public final Map<Integer, RoomAllocation> mainBuildingAssignments;
        public final Map<Integer, RoomAllocation> annexBuildingAssignments;
        public final Map<Integer, RoomAllocation> roomsByFloor;
        public final BathCleaningType bathCleaningType;

        public StaffAssignment(FileProcessor.Staff staff,
                               Map<Integer, RoomAllocation> mainAssignments,
                               Map<Integer, RoomAllocation> annexAssignments,
                               BathCleaningType bathType) {
            this.staff = staff;
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
                BathCleaningType staffBathType = BathCleaningType.NONE;

                for (RoomAssignmentApplication.StaffPointConstraint constraint : staffConstraints) {
                    if (!constraint.staffName.equals(staff.name)) continue;

                    if (constraint.isBathCleaningStaff) {
                        staffBathType = bathType;
                    }

                    if (constraint.lowerMinLimit > 0 || constraint.lowerMaxLimit > 0 || constraint.upperLimit > 0) {
                        pointConstraints.put(staff.name, new PointConstraint(
                                staff.name, constraint.constraintType.displayName));
                    }
                }

                extendedInfo.add(new ExtendedStaffInfo(staff, staffBathType));
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
     * 適応型最適化エンジン（部屋数ベース専用）
     */
    public static class AdaptiveOptimizer {
        private final List<FloorInfo> floors;
        private final AdaptiveLoadConfig config;

        public AdaptiveOptimizer(List<FloorInfo> floors, AdaptiveLoadConfig config) {
            this.floors = new ArrayList<>(floors);
            this.config = config;
        }

        public OptimizationResult optimize(LocalDate targetDate) {
            LOGGER.info("=== 適応型最適化を開始（部屋数ベース版） ===");

            // 部屋割り振り設定が必須
            if (config.roomDistribution == null || config.roomDistribution.isEmpty()) {
                throw new IllegalStateException(
                        "部屋割り振り設定が必須です。通常清掃部屋割り振りダイアログで設定してください。");
            }

            BuildingData buildingData = separateBuildings(floors);

            LOGGER.info("通常清掃部屋割り振り設定を使用します");
            List<StaffAssignment> assignments = optimizeWithRoomDistribution(buildingData);

            return new OptimizationResult(assignments, config, targetDate);
        }

        /**
         * roomDistribution を使用した最適化（設定値厳守）
         */
        private List<StaffAssignment> optimizeWithRoomDistribution(BuildingData buildingData) {
            // フロア別の利用可能な部屋データを構築
            Map<String, FloorRoomPool> roomPools = buildRoomPools(buildingData);

            // スタッフ別の割り当て状況を追跡
            Map<String, StaffAllocationTracker> staffTrackers = new HashMap<>();
            for (ExtendedStaffInfo staffInfo : config.extendedStaffInfo) {
                staffTrackers.put(staffInfo.staff.name, new StaffAllocationTracker(staffInfo, config));
            }

            // フェーズ1: 通常清掃部屋の割り振り（設定値厳守）
            LOGGER.info("=== フェーズ1: 通常清掃部屋の割り振り（設定値厳守） ===");
            boolean phase1Success = assignNormalRoomsStrict(buildingData, roomPools, staffTrackers);

            if (!phase1Success) {
                LOGGER.severe("エラー: 通常清掃部屋を設定値通りに割り振れませんでした");
            }

            // フェーズ2: エコ清掃部屋の割り振り（全部屋割り振り）
            LOGGER.info("=== フェーズ2: エコ清掃部屋の割り振り（全部屋） ===");
            boolean phase2Success = assignAllEcoRooms(buildingData, roomPools, staffTrackers);

            if (!phase2Success) {
                LOGGER.severe("エラー: エコ清掃部屋を全て割り振れませんでした");
            }

            // 結果の構築
            List<StaffAssignment> assignments = new ArrayList<>();
            for (StaffAllocationTracker tracker : staffTrackers.values()) {
                assignments.add(tracker.buildAssignment());
            }

            // 最終検証
            validateAssignments(assignments, buildingData);

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
         * ★厳密版: 通常清掃部屋の割り振り（設定値を1室も違えない）
         */
        private boolean assignNormalRoomsStrict(BuildingData buildingData,
                                                Map<String, FloorRoomPool> roomPools,
                                                Map<String, StaffAllocationTracker> staffTrackers) {

            boolean allSuccess = true;

            for (Map.Entry<String, StaffAllocationTracker> entry : staffTrackers.entrySet()) {
                String staffName = entry.getKey();
                StaffAllocationTracker tracker = entry.getValue();

                NormalRoomDistributionDialog.StaffDistribution dist = config.roomDistribution.get(staffName);
                if (dist == null) {
                    LOGGER.warning("スタッフ " + staffName + " の部屋割り振り設定が見つかりません");
                    continue;
                }

                LOGGER.info(String.format("%s: 本館%d室, 別館%d室を厳密に割り振ります",
                        staffName, dist.mainAssignedRooms, dist.annexAssignedRooms));

                // 本館の割り当て
                if (dist.mainAssignedRooms > 0) {
                    int assigned = assignNormalRoomsToBuildingStrict(
                            buildingData.mainFloors, roomPools, tracker,
                            dist.mainAssignedRooms, true);

                    if (assigned != dist.mainAssignedRooms) {
                        LOGGER.severe(String.format("エラー: %s 本館 設定%d室に対し%d室しか割り振れませんでした",
                                staffName, dist.mainAssignedRooms, assigned));
                        allSuccess = false;
                    }
                }

                // 別館の割り当て
                if (dist.annexAssignedRooms > 0) {
                    int assigned = assignNormalRoomsToBuildingStrict(
                            buildingData.annexFloors, roomPools, tracker,
                            dist.annexAssignedRooms, false);

                    if (assigned != dist.annexAssignedRooms) {
                        LOGGER.severe(String.format("エラー: %s 別館 設定%d室に対し%d室しか割り振れませんでした",
                                staffName, dist.annexAssignedRooms, assigned));
                        allSuccess = false;
                    }
                }

                LOGGER.info(String.format("%s: 通常清掃 合計%d室割り振り完了（フロア数:%d）",
                        staffName, tracker.getTotalNormalRooms(), tracker.getNormalFloorCount()));
            }

            return allSuccess;
        }

        /**
         * 建物別の通常清掃部屋割り振り（厳密版）
         */
        private int assignNormalRoomsToBuildingStrict(List<FloorInfo> floors,
                                                      Map<String, FloorRoomPool> roomPools,
                                                      StaffAllocationTracker tracker,
                                                      int targetRooms,
                                                      boolean isMainBuilding) {

            String buildingName = isMainBuilding ? "本館" : "別館";
            int assigned = 0;
            int maxFloors = tracker.getMaxNormalFloors();
            int currentNormalFloors = tracker.getNormalFloorCount();

            for (FloorInfo floor : floors) {
                if (assigned >= targetRooms) break;

                // フロア制限チェック
                if (!tracker.isContractor() && currentNormalFloors >= maxFloors) {
                    LOGGER.warning(String.format("  %dF: 通常清掃フロア制限(%d階)に達しました",
                            floor.floorNumber, maxFloors));
                    break;
                }

                String poolKey = buildingName + "_" + floor.floorNumber;
                FloorRoomPool pool = roomPools.get(poolKey);

                if (pool == null || pool.getNormalRoomsAvailable() == 0) {
                    continue;
                }

                int remaining = targetRooms - assigned;
                int available = pool.getNormalRoomsAvailable();
                int toAssign = Math.min(remaining, available);

                Map<String, Integer> assignedRooms = pool.allocateNormalRooms(toAssign);
                int actualAssigned = assignedRooms.values().stream().mapToInt(Integer::intValue).sum();

                if (actualAssigned > 0) {
                    tracker.addNormalRooms(floor.floorNumber, assignedRooms, isMainBuilding);
                    assigned += actualAssigned;
                    currentNormalFloors = tracker.getNormalFloorCount();

                    LOGGER.info(String.format("  %dF: %d室割り振り（累計%d/%d室）",
                            floor.floorNumber, actualAssigned, assigned, targetRooms));
                }
            }

            if (assigned < targetRooms) {
                LOGGER.warning(String.format("  %s: 目標%d室に対し%d室しか割り振れませんでした（不足%d室）",
                        buildingName, targetRooms, assigned, targetRooms - assigned));
            }

            return assigned;
        }

        /**
         * ★修正版: 全エコ清掃部屋を確実に割り振る
         */
        private boolean assignAllEcoRooms(BuildingData buildingData,
                                          Map<String, FloorRoomPool> roomPools,
                                          Map<String, StaffAllocationTracker> staffTrackers) {

            // 全フロアのリストを作成
            List<FloorInfo> allFloors = new ArrayList<>();
            allFloors.addAll(buildingData.mainFloors);
            allFloors.addAll(buildingData.annexFloors);
            Collections.sort(allFloors, Comparator.comparingInt(f -> f.floorNumber));

            // スタッフのリストを作成
            List<String> staffNames = new ArrayList<>(staffTrackers.keySet());

            int totalEcoRooms = countTotalEcoRooms(buildingData);
            int assignedEcoRooms = 0;
            int staffIndex = 0;

            LOGGER.info(String.format("エコ清掃総数: %d室", totalEcoRooms));

            // 全エコ部屋を割り振るまでループ
            for (FloorInfo floor : allFloors) {
                String buildingName = floor.isMainBuilding ? "本館" : "別館";
                String poolKey = buildingName + "_" + floor.floorNumber;
                FloorRoomPool pool = roomPools.get(poolKey);

                if (pool == null || pool.getEcoRoomsAvailable() == 0) {
                    continue;
                }

                int floorEcoRooms = pool.getEcoRoomsAvailable();
                LOGGER.info(String.format("%s %dF: エコ%d室を割り振り中",
                        buildingName, floor.floorNumber, floorEcoRooms));

                // このフロアの全エコ部屋を割り振る
                while (pool.getEcoRoomsAvailable() > 0) {
                    boolean assigned = false;
                    int attempts = 0;

                    // 全スタッフを試行
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
                            assignedEcoRooms++;
                            break;
                        }
                    }

                    if (!assigned) {
                        LOGGER.severe(String.format("エラー: %dFのエコ部屋を割り振れるスタッフがいません（残り%d室）",
                                floor.floorNumber, pool.getEcoRoomsAvailable()));
                        return false;
                    }
                }

                LOGGER.info(String.format("  %dF: エコ%d室の割り振り完了", floor.floorNumber, floorEcoRooms));
            }

            LOGGER.info(String.format("エコ清掃割り振り完了: %d/%d室", assignedEcoRooms, totalEcoRooms));

            // 各スタッフの状況をログ出力
            for (Map.Entry<String, StaffAllocationTracker> entry : staffTrackers.entrySet()) {
                StaffAllocationTracker tracker = entry.getValue();
                LOGGER.info(String.format("  %s: 通常%d室, エコ%d室, フロア数%d",
                        entry.getKey(), tracker.getTotalNormalRooms(),
                        tracker.getTotalEcoRooms(), tracker.getFloorCount()));
            }

            return assignedEcoRooms == totalEcoRooms;
        }

        /**
         * 総エコ部屋数を計算
         */
        private int countTotalEcoRooms(BuildingData buildingData) {
            int total = 0;
            for (FloorInfo floor : buildingData.mainFloors) {
                total += floor.ecoRooms;
            }
            for (FloorInfo floor : buildingData.annexFloors) {
                total += floor.ecoRooms;
            }
            return total;
        }

        /**
         * 割り当て結果の検証
         */
        private void validateAssignments(List<StaffAssignment> assignments, BuildingData buildingData) {
            LOGGER.info("=== 割り当て結果の検証 ===");

            // 割り当てられた部屋を集計
            int totalAssignedNormal = 0;
            int totalAssignedEco = 0;

            for (StaffAssignment assignment : assignments) {
                for (RoomAllocation allocation : assignment.roomsByFloor.values()) {
                    totalAssignedNormal += allocation.roomCounts.values().stream()
                            .mapToInt(Integer::intValue).sum();
                    totalAssignedEco += allocation.ecoRooms;
                }
            }

            // 実際の部屋数
            int actualNormalRooms = buildingData.mainRoomCount + buildingData.annexRoomCount;
            int actualEcoRooms = countTotalEcoRooms(buildingData);

            LOGGER.info(String.format("通常清掃: 割り当て%d室 / 実際%d室",
                    totalAssignedNormal, actualNormalRooms));
            LOGGER.info(String.format("エコ清掃: 割り当て%d室 / 実際%d室",
                    totalAssignedEco, actualEcoRooms));

            if (totalAssignedNormal != actualNormalRooms) {
                LOGGER.severe(String.format("エラー: 通常清掃部屋数が一致しません（差分%d室）",
                        actualNormalRooms - totalAssignedNormal));
            }

            if (totalAssignedEco != actualEcoRooms) {
                LOGGER.severe(String.format("エラー: エコ清掃部屋数が一致しません（差分%d室）",
                        actualEcoRooms - totalAssignedEco));
            }

            if (totalAssignedNormal == actualNormalRooms && totalAssignedEco == actualEcoRooms) {
                LOGGER.info("✓ 全部屋の割り振りが完了しました");
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

            int mainRoomCount = mainFloors.stream()
                    .mapToInt(f -> f.getTotalNormalRooms())
                    .sum();

            int annexRoomCount = annexFloors.stream()
                    .mapToInt(f -> f.getTotalNormalRooms())
                    .sum();

            return new BuildingData(mainFloors, annexFloors, mainRoomCount, annexRoomCount);
        }
    }

    /**
     * フロア部屋プールクラス（厳密版）
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

        /**
         * ★厳密版: 指定数の通常部屋を割り当て（存在する部屋のみ）
         */
        Map<String, Integer> allocateNormalRooms(int count) {
            Map<String, Integer> allocated = new HashMap<>();
            int remaining = count;
            int availableTotal = getNormalRoomsAvailable();

            // 要求数が利用可能数を超えていないかチェック
            if (count > availableTotal) {
                LOGGER.warning(String.format("警告: %dF 要求%d室だが利用可能は%d室のみ",
                        floor.floorNumber, count, availableTotal));
                remaining = availableTotal;
            }

            // 部屋タイプを多い順にソート
            List<String> roomTypes = new ArrayList<>(normalRoomsAvailable.keySet());
            roomTypes.sort((a, b) -> Integer.compare(
                    normalRoomsAvailable.get(b), normalRoomsAvailable.get(a)));

            // 各タイプから順に割り当て
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

        /**
         * ★厳密版: 指定数のエコ部屋を割り当て（存在する部屋のみ）
         */
        int allocateEcoRooms(int count) {
            if (count > ecoRoomsAvailable) {
                LOGGER.warning(String.format("警告: %dF エコ要求%d室だが利用可能は%d室のみ",
                        floor.floorNumber, count, ecoRoomsAvailable));
                count = ecoRoomsAvailable;
            }

            ecoRoomsAvailable -= count;
            return count;
        }
    }

    /**
     * スタッフ割り当て追跡クラス
     */
    private static class StaffAllocationTracker {
        private final ExtendedStaffInfo staffInfo;
        private final AdaptiveLoadConfig config;
        private final Map<Integer, Map<String, Integer>> mainNormalRooms = new HashMap<>();
        private final Map<Integer, Map<String, Integer>> annexNormalRooms = new HashMap<>();
        private final Map<Integer, Integer> mainEcoRooms = new HashMap<>();
        private final Map<Integer, Integer> annexEcoRooms = new HashMap<>();

        StaffAllocationTracker(ExtendedStaffInfo staffInfo, AdaptiveLoadConfig config) {
            this.staffInfo = staffInfo;
            this.config = config;
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

        int getTotalEcoRooms() {
            return mainEcoRooms.values().stream().mapToInt(Integer::intValue).sum() +
                    annexEcoRooms.values().stream().mapToInt(Integer::intValue).sum();
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
            PointConstraint constraint = config.pointConstraints.get(staffInfo.staff.name);
            return constraint != null && "業者制限".equals(constraint.constraintType);
        }

        int getMaxNormalFloors() {
            if (isContractor()) {
                return Integer.MAX_VALUE;
            }
            // 大浴清掃スタッフは通常清掃1階のみ
            return staffInfo.bathCleaningType != BathCleaningType.NONE ? 1 : 2;
        }

        boolean canAssignEcoFloor(int floor) {
            Set<Integer> allFloors = new HashSet<>();
            allFloors.addAll(mainNormalRooms.keySet());
            allFloors.addAll(annexNormalRooms.keySet());
            allFloors.addAll(mainEcoRooms.keySet());
            allFloors.addAll(annexEcoRooms.keySet());

            // 既に割り当てられているフロアなら常にOK
            if (allFloors.contains(floor)) {
                return true;
            }

            // 業者は制限なし
            if (isContractor()) {
                return true;
            }

            // 通常スタッフ：合計2階まで
            return allFloors.size() < 2;
        }

        StaffAssignment buildAssignment() {
            Map<Integer, RoomAllocation> mainAssignments = new HashMap<>();
            Map<Integer, RoomAllocation> annexAssignments = new HashMap<>();

            // 本館の割り当て
            Set<Integer> mainFloors = new HashSet<>();
            mainFloors.addAll(mainNormalRooms.keySet());
            mainFloors.addAll(mainEcoRooms.keySet());

            for (Integer floor : mainFloors) {
                Map<String, Integer> rooms = mainNormalRooms.getOrDefault(floor, new HashMap<>());
                int eco = mainEcoRooms.getOrDefault(floor, 0);
                mainAssignments.put(floor, new RoomAllocation(rooms, eco));
            }

            // 別館の割り当て
            Set<Integer> annexFloors = new HashSet<>();
            annexFloors.addAll(annexNormalRooms.keySet());
            annexFloors.addAll(annexEcoRooms.keySet());

            for (Integer floor : annexFloors) {
                Map<String, Integer> rooms = annexNormalRooms.getOrDefault(floor, new HashMap<>());
                int eco = annexEcoRooms.getOrDefault(floor, 0);
                annexAssignments.put(floor, new RoomAllocation(rooms, eco));
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
        final int mainRoomCount;
        final int annexRoomCount;

        BuildingData(List<FloorInfo> mainFloors, List<FloorInfo> annexFloors,
                     int mainRoomCount, int annexRoomCount) {
            this.mainFloors = mainFloors;
            this.annexFloors = annexFloors;
            this.mainRoomCount = mainRoomCount;
            this.annexRoomCount = annexRoomCount;
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

            int totalRooms = 0;

            for (StaffAssignment assignment : assignments) {
                totalRooms += assignment.getTotalRooms();

                System.out.printf("\n%s (%s):\n",
                        assignment.staff.name,
                        assignment.bathCleaningType.displayName);
                System.out.printf("  部屋数: %d室\n", assignment.getTotalRooms());
                System.out.printf("  担当フロア: %s\n", assignment.floors);
            }

            System.out.printf("\n総計:\n");
            System.out.printf("  部屋数: %d室\n", totalRooms);
        }
    }
}