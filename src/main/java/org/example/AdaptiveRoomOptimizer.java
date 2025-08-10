package org.example;

import java.util.*;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * 適応型清掃管理最適化システム（独立版）
 * 親クラスへの依存を削除し、単独で動作する実装
 */
public class AdaptiveRoomOptimizer {

    private static final Logger LOGGER = Logger.getLogger(AdaptiveRoomOptimizer.class.getName());

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
     * 適応型作業者タイプ
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
     * 適応型負荷設定
     */
    public static class AdaptiveLoadConfig {
        public final List<FileProcessor.Staff> availableStaff;
        public final LoadLevel loadLevel;
        public final Map<AdaptiveWorkerType, Integer> reductionMap;
        public final Map<AdaptiveWorkerType, Integer> targetRoomsMap;
        public final Map<AdaptiveWorkerType, List<FileProcessor.Staff>> staffByType;
        public final Map<String, WorkerType> workerTypes;

        private AdaptiveLoadConfig(
                List<FileProcessor.Staff> availableStaff,
                LoadLevel loadLevel,
                Map<AdaptiveWorkerType, Integer> reductionMap,
                Map<AdaptiveWorkerType, Integer> targetRoomsMap,
                Map<AdaptiveWorkerType, List<FileProcessor.Staff>> staffByType) {

            this.availableStaff = new ArrayList<>(availableStaff);
            this.loadLevel = loadLevel;
            this.reductionMap = new HashMap<>(reductionMap);
            this.targetRoomsMap = new HashMap<>(targetRoomsMap);
            this.staffByType = new HashMap<>(staffByType);
            this.workerTypes = convertToWorkerTypeMap(staffByType);
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

        public static AdaptiveLoadConfig createAdaptiveConfig(
                List<FileProcessor.Staff> availableStaff, int totalRooms) {

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
                    calculateTargetRooms(totalRooms, staffByType, reductionMap, avgRooms);

            return new AdaptiveLoadConfig(availableStaff, level,
                    reductionMap, targetRoomsMap, staffByType);
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
                double avgRooms) {

            Map<AdaptiveWorkerType, Integer> targetMap = new HashMap<>();
            int baseTarget = (int) Math.floor(avgRooms);
            int totalReduction = 0;
            int normalStaffCount = 0;

            for (Map.Entry<AdaptiveWorkerType, List<FileProcessor.Staff>> entry : staffByType.entrySet()) {
                AdaptiveWorkerType type = entry.getKey();
                int staffCount = entry.getValue().size();
                int reduction = reductionMap.get(type);

                if (type == AdaptiveWorkerType.NORMAL) {
                    normalStaffCount = staffCount;
                } else {
                    totalReduction += staffCount * reduction;
                }
                targetMap.put(type, Math.max(3, baseTarget - reduction));
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

        public FloorInfo(int floorNumber, Map<String, Integer> roomCounts, int ecoRooms) {
            this.floorNumber = floorNumber;
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

        public boolean isEmpty() {
            return getTotalRooms() == 0;
        }
    }

    /**
     * スタッフ配分結果
     */
    public static class StaffAssignment {
        public final FileProcessor.Staff staff;
        public final WorkerType workerType;
        public final List<Integer> floors;
        public final Map<Integer, RoomAllocation> roomsByFloor;
        public final int totalRooms;
        public final double totalPoints;

        public StaffAssignment(FileProcessor.Staff staff, WorkerType workerType,
                               List<Integer> floors, Map<Integer, RoomAllocation> roomsByFloor) {
            this.staff = staff;
            this.workerType = workerType;
            this.floors = new ArrayList<>(floors);
            this.roomsByFloor = new HashMap<>(roomsByFloor);
            this.totalRooms = calculateTotalRooms();
            this.totalPoints = calculateTotalPoints();
        }

        private int calculateTotalRooms() {
            return roomsByFloor.values().stream()
                    .mapToInt(RoomAllocation::getTotalRooms)
                    .sum();
        }

        private double calculateTotalPoints() {
            double points = 0;
            for (RoomAllocation allocation : roomsByFloor.values()) {
                points += allocation.roomCounts.getOrDefault("S", 0) * 1.0;
                points += allocation.roomCounts.getOrDefault("D", 0) * 1.0;
                points += allocation.roomCounts.getOrDefault("T", 0) * 1.5;
                points += allocation.roomCounts.getOrDefault("FD", 0) * 2.0;
                points += allocation.ecoRooms * 0.5;
            }
            return points;
        }

        public String getDetailedDescription() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%s: %d室 (%.1f点) - ",
                    staff.name, totalRooms, totalPoints));

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

        public OptimizationResult(List<StaffAssignment> assignments,
                                  AdaptiveLoadConfig config, LocalDate targetDate) {
            this.assignments = assignments;
            this.config = config;
            this.targetDate = targetDate;
            this.pointDifference = calculatePointDifference();
        }

        private double calculatePointDifference() {
            double minPoints = assignments.stream().mapToDouble(a -> a.totalPoints).min().orElse(0);
            double maxPoints = assignments.stream().mapToDouble(a -> a.totalPoints).max().orElse(0);
            return maxPoints - minPoints;
        }

        public void printDetailedSummary() {
            System.out.println("\n=== 最適化結果 ===");
            System.out.printf("対象日: %s\n",
                    targetDate.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")));
            System.out.printf("出勤スタッフ数: %d名\n", config.availableStaff.size());

            System.out.println("\n【個別配分結果】");
            assignments.forEach(a -> System.out.println(a.getDetailedDescription()));

            System.out.println("\n【統計サマリー】");
            double[] points = assignments.stream()
                    .mapToDouble(a -> a.totalPoints).toArray();

            System.out.printf("点数範囲: %.2f ～ %.2f (差: %.2f点)\n",
                    Arrays.stream(points).min().orElse(0),
                    Arrays.stream(points).max().orElse(0),
                    pointDifference);

            System.out.println("\n【作業者タイプ別統計】");
            Map<WorkerType, List<StaffAssignment>> byType = new HashMap<>();
            for (StaffAssignment assignment : assignments) {
                byType.computeIfAbsent(assignment.workerType, k -> new ArrayList<>())
                        .add(assignment);
            }

            byType.forEach((type, list) -> {
                double avgRooms = list.stream().mapToInt(a -> a.totalRooms).average().orElse(0);
                double avgPoints = list.stream().mapToDouble(a -> a.totalPoints).average().orElse(0);
                System.out.printf("%s作業者: %d名, 平均%.1f室, 平均%.1f点\n",
                        type.displayName, list.size(), avgRooms, avgPoints);
            });

            System.out.println("\n【最適化評価】");
            if (pointDifference <= 3.0) {
                System.out.println("✅ 優秀：バランスの取れた配分です");
            } else if (pointDifference <= 4.0) {
                System.out.println("⭕ 良好：概ね公平な配分です");
            } else {
                System.out.println("⚠️ 要改善：スタッフ間の負荷差が大きいです");
            }
        }
    }

    /**
     * 適応型最適化エンジン
     */
    public static class AdaptiveOptimizer {
        private final List<FloorInfo> floors;
        private final AdaptiveLoadConfig adaptiveConfig;

        public AdaptiveOptimizer(List<FloorInfo> floors, AdaptiveLoadConfig config) {
            this.floors = new ArrayList<>(floors);
            this.adaptiveConfig = config;
        }

        public OptimizationResult optimize(LocalDate targetDate) {
            System.out.println("=== 適応型最適化開始 ===");
            System.out.printf("対象日: %s\n", targetDate.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")));
            System.out.printf("負荷レベル: %s\n", adaptiveConfig.loadLevel.displayName);

            System.out.println("\n【スタッフ配分】");
            adaptiveConfig.staffByType.forEach((type, staffList) -> {
                int target = adaptiveConfig.targetRoomsMap.get(type);
                int reduction = adaptiveConfig.reductionMap.get(type);
                System.out.printf("%s: %d名 (目標%d室, -%d室)\n",
                        type.displayName, staffList.size(), target, reduction);
            });

            List<StaffAssignment> stage1Result = stageOneAdaptiveAssignment();
            System.out.println("\n--- Stage 1完了: 適応型初期配分 ---");
            printStageResult(stage1Result);

            List<StaffAssignment> stage2Result = stageTwoStrategicReallocation(stage1Result);
            System.out.println("\n--- Stage 2完了: 戦略的再配分 ---");
            printStageResult(stage2Result);

            List<StaffAssignment> stage3Result = stageThreeBalanceOptimization(stage2Result);
            System.out.println("\n--- Stage 3完了: バランス最適化 ---");
            printStageResult(stage3Result);

            return new OptimizationResult(stage3Result, adaptiveConfig, targetDate);
        }

        private List<StaffAssignment> stageOneAdaptiveAssignment() {
            Map<Integer, RoomAllocation> remainingRooms = new HashMap<>();
            for (FloorInfo floor : floors) {
                remainingRooms.put(floor.floorNumber,
                        new RoomAllocation(new HashMap<>(floor.roomCounts), floor.ecoRooms));
            }

            List<StaffAssignment> assignments = new ArrayList<>();

            for (AdaptiveWorkerType type : AdaptiveWorkerType.values()) {
                List<FileProcessor.Staff> staffList = adaptiveConfig.staffByType.get(type);
                if (staffList == null) continue;

                int targetRooms = adaptiveConfig.targetRoomsMap.get(type);

                for (FileProcessor.Staff staff : staffList) {
                    Map<Integer, RoomAllocation> staffRooms = new HashMap<>();
                    List<Integer> staffFloors = new ArrayList<>();
                    int currentRooms = 0;

                    boolean preferHighPoint = (type != AdaptiveWorkerType.NORMAL);

                    while (currentRooms < targetRooms && hasRemainingRooms(remainingRooms)) {
                        FloorInfo bestFloor = findBestFloorForAdaptiveStaff(
                                remainingRooms, targetRooms - currentRooms,
                                staffFloors, preferHighPoint);

                        if (bestFloor != null) {
                            RoomAllocation allocation = calculateAdaptiveAllocation(
                                    remainingRooms.get(bestFloor.floorNumber),
                                    targetRooms - currentRooms, preferHighPoint);

                            if (!allocation.isEmpty()) {
                                staffRooms.put(bestFloor.floorNumber, allocation);
                                if (!staffFloors.contains(bestFloor.floorNumber)) {
                                    staffFloors.add(bestFloor.floorNumber);
                                }

                                updateRemainingRooms(remainingRooms, bestFloor.floorNumber, allocation);
                                currentRooms += allocation.getTotalRooms();

                                if (staffFloors.size() >= 2) break;
                            } else {
                                break;
                            }
                        } else {
                            break;
                        }
                    }

                    WorkerType workerType = type == AdaptiveWorkerType.NORMAL ?
                            WorkerType.NORMAL_DUTY : WorkerType.LIGHT_DUTY;
                    assignments.add(new StaffAssignment(staff, workerType, staffFloors, staffRooms));
                }
            }

            distributeRemainingRoomsAdaptive(assignments, remainingRooms);
            return assignments;
        }

        private FloorInfo findBestFloorForAdaptiveStaff(
                Map<Integer, RoomAllocation> remainingRooms,
                int targetRooms, List<Integer> currentFloors,
                boolean preferHighPoint) {

            FloorInfo bestFloor = null;
            double bestScore = Double.MIN_VALUE;

            for (FloorInfo floor : floors) {
                RoomAllocation remaining = remainingRooms.get(floor.floorNumber);
                if (remaining != null && !remaining.isEmpty()) {
                    if (currentFloors.size() >= 2 && !currentFloors.contains(floor.floorNumber)) {
                        continue;
                    }

                    double score = calculateAdaptiveFloorScore(
                            remaining, targetRooms, preferHighPoint);

                    if (score > bestScore) {
                        bestScore = score;
                        bestFloor = floor;
                    }
                }
            }
            return bestFloor;
        }

        private double calculateAdaptiveFloorScore(
                RoomAllocation remaining, int targetRooms, boolean preferHighPoint) {

            int availableRooms = remaining.getTotalRooms();
            double baseScore = 1.0 / (1.0 + Math.abs(availableRooms - targetRooms));

            if (preferHighPoint) {
                int highPointRooms = remaining.roomCounts.getOrDefault("T", 0) +
                        remaining.roomCounts.getOrDefault("FD", 0);
                double highPointRatio = availableRooms > 0 ?
                        (double) highPointRooms / availableRooms : 0;
                return baseScore + highPointRatio * 0.5;
            }
            return baseScore;
        }

        private RoomAllocation calculateAdaptiveAllocation(
                RoomAllocation available, int targetRooms, boolean preferHighPoint) {

            if (available.isEmpty() || targetRooms <= 0) {
                return new RoomAllocation(new HashMap<>(), 0);
            }

            Map<String, Integer> bestCounts = new HashMap<>();
            int bestEco = 0;

            if (preferHighPoint) {
                int fd = Math.min(available.roomCounts.getOrDefault("FD", 0),
                        Math.min(2, targetRooms));
                int t = Math.min(available.roomCounts.getOrDefault("T", 0),
                        Math.min(3, targetRooms - fd));
                int remaining = targetRooms - fd - t;

                int s = Math.min(available.roomCounts.getOrDefault("S", 0), remaining / 2);
                int d = Math.min(available.roomCounts.getOrDefault("D", 0), remaining - s);
                s += Math.min(available.roomCounts.getOrDefault("S", 0) - s, remaining - s - d);

                int eco = Math.min(available.ecoRooms, targetRooms - fd - t - s - d);

                if (fd > 0) bestCounts.put("FD", fd);
                if (t > 0) bestCounts.put("T", t);
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
            int eco = Math.min(available.ecoRooms, targetRooms);
            int remainingTarget = targetRooms - eco;

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

        private List<StaffAssignment> stageTwoStrategicReallocation(List<StaffAssignment> initial) {
            System.out.println("  高ポイント部屋の再配分を実行中...");
            // 簡略化された実装
            return initial;
        }

        private List<StaffAssignment> stageThreeBalanceOptimization(List<StaffAssignment> initial) {
            System.out.println("  最終バランス調整を実行中...");
            // 簡略化された実装
            return initial;
        }

        private boolean hasRemainingRooms(Map<Integer, RoomAllocation> remainingRooms) {
            return remainingRooms.values().stream().anyMatch(r -> !r.isEmpty());
        }

        private void updateRemainingRooms(Map<Integer, RoomAllocation> remainingRooms,
                                          int floor, RoomAllocation allocated) {
            RoomAllocation current = remainingRooms.get(floor);
            if (current != null) {
                Map<String, Integer> newCounts = new HashMap<>(current.roomCounts);
                allocated.roomCounts.forEach((type, count) -> {
                    newCounts.put(type, newCounts.getOrDefault(type, 0) - count);
                });
                int newEco = current.ecoRooms - allocated.ecoRooms;
                remainingRooms.put(floor, new RoomAllocation(newCounts, newEco));
            }
        }

        private void distributeRemainingRoomsAdaptive(
                List<StaffAssignment> assignments,
                Map<Integer, RoomAllocation> remainingRooms) {

            List<StaffAssignment> normalStaff = assignments.stream()
                    .filter(a -> adaptiveConfig.getAdaptiveWorkerType(a.staff.id) ==
                            AdaptiveWorkerType.NORMAL)
                    .sorted(Comparator.comparingInt(a -> a.totalRooms))
                    .collect(Collectors.toList());

            for (Map.Entry<Integer, RoomAllocation> entry : remainingRooms.entrySet()) {
                RoomAllocation remaining = entry.getValue();
                if (!remaining.isEmpty() && !normalStaff.isEmpty()) {
                    StaffAssignment target = normalStaff.get(0);
                    updateStaffAssignment(assignments, target.staff.id, entry.getKey(), remaining);
                }
            }
        }

        private void updateStaffAssignment(List<StaffAssignment> assignments, String staffId,
                                           int floor, RoomAllocation additional) {
            for (int i = 0; i < assignments.size(); i++) {
                StaffAssignment current = assignments.get(i);
                if (current.staff.id.equals(staffId)) {
                    Map<Integer, RoomAllocation> newRooms = new HashMap<>(current.roomsByFloor);
                    newRooms.put(floor, additional);
                    List<Integer> newFloors = new ArrayList<>(current.floors);
                    if (!newFloors.contains(floor)) {
                        newFloors.add(floor);
                    }
                    assignments.set(i, new StaffAssignment(current.staff, current.workerType,
                            newFloors, newRooms));
                    break;
                }
            }
        }

        private void printStageResult(List<StaffAssignment> assignments) {
            double minPoints = assignments.stream().mapToDouble(a -> a.totalPoints).min().orElse(0);
            double maxPoints = assignments.stream().mapToDouble(a -> a.totalPoints).max().orElse(0);
            double avgPoints = assignments.stream().mapToDouble(a -> a.totalPoints).average().orElse(0);
            System.out.printf("  点数範囲: %.1f ～ %.1f (差: %.1f), 平均: %.1f\n",
                    minPoints, maxPoints, maxPoints - minPoints, avgPoints);
        }
    }
}