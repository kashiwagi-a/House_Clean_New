package org.example;

import java.util.*;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

/**
 * 適応型清掃管理最適化システム(部屋タイプ別割り振り対応版)
 * ★改善版: シングル・ツイン別の部屋割り振りに対応
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

        public int getTotalNormalRooms() {
            return roomCounts.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /**
     * スタッフ拡張情報クラス
     */
    public static class ExtendedStaffInfo {
        public final FileProcessor.Staff staff;
        public final BathCleaningType bathCleaningType;
        public final boolean isLinenClosetCleaning;
        public final int linenClosetFloorCount;

        public ExtendedStaffInfo(FileProcessor.Staff staff,
                                 BathCleaningType bathType) {
            this(staff, bathType, false, 0);
        }

        public ExtendedStaffInfo(FileProcessor.Staff staff,
                                 BathCleaningType bathType,
                                 boolean isLinenClosetCleaning,
                                 int linenClosetFloorCount) {
            this.staff = staff;
            this.bathCleaningType = bathType;
            this.isLinenClosetCleaning = isLinenClosetCleaning;
            this.linenClosetFloorCount = linenClosetFloorCount;
        }
    }

    /**
     * ポイント制約クラス(業者判定とフロア制限に使用)
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
        public final boolean isLinenClosetCleaning;
        public final int linenClosetFloorCount;
        // ★★追加: 具体的なリネン庫担当フロア（後処理で割り当て）
        private List<Integer> linenClosetFloors;

        public StaffAssignment(FileProcessor.Staff staff,
                               Map<Integer, RoomAllocation> mainAssignments,
                               Map<Integer, RoomAllocation> annexAssignments,
                               BathCleaningType bathType,
                               boolean isLinenClosetCleaning,
                               int linenClosetFloorCount) {
            this.staff = staff;
            this.mainBuildingAssignments = new HashMap<>(mainAssignments);
            this.annexBuildingAssignments = new HashMap<>(annexAssignments);
            this.bathCleaningType = bathType;
            this.isLinenClosetCleaning = isLinenClosetCleaning;
            this.linenClosetFloorCount = linenClosetFloorCount;
            this.linenClosetFloors = new ArrayList<>();

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

        // ★★追加: リネン庫担当フロアのgetter/setter
        public List<Integer> getLinenClosetFloors() {
            return new ArrayList<>(linenClosetFloors);
        }

        public void setLinenClosetFloors(List<Integer> floors) {
            this.linenClosetFloors = floors != null ? new ArrayList<>(floors) : new ArrayList<>();
        }

        // ★★追加: リネン庫担当フロアの表示用テキスト
        public String getLinenClosetFloorsDisplay() {
            if (linenClosetFloors == null || linenClosetFloors.isEmpty()) {
                return "";
            }
            List<Integer> sorted = new ArrayList<>(linenClosetFloors);
            Collections.sort(sorted);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < sorted.size(); i++) {
                if (i > 0) sb.append(",");
                int f = sorted.get(i);
                if (f > 20) {
                    sb.append("別館").append(f - 20).append("F");
                } else {
                    sb.append(f).append("F");
                }
            }
            return sb.toString();
        }

        /**
         * ★追加: ディープコピーを作成
         * 複数解生成時にオブジェクト共有による副作用を防ぐため
         */
        public StaffAssignment deepCopy() {
            Map<Integer, RoomAllocation> mainCopy = new HashMap<>();
            for (Map.Entry<Integer, RoomAllocation> entry : mainBuildingAssignments.entrySet()) {
                mainCopy.put(entry.getKey(), new RoomAllocation(
                        new HashMap<>(entry.getValue().roomCounts),
                        entry.getValue().ecoRooms
                ));
            }

            Map<Integer, RoomAllocation> annexCopy = new HashMap<>();
            for (Map.Entry<Integer, RoomAllocation> entry : annexBuildingAssignments.entrySet()) {
                annexCopy.put(entry.getKey(), new RoomAllocation(
                        new HashMap<>(entry.getValue().roomCounts),
                        entry.getValue().ecoRooms
                ));
            }

            StaffAssignment copy = new StaffAssignment(this.staff, mainCopy, annexCopy, this.bathCleaningType, this.isLinenClosetCleaning, this.linenClosetFloorCount);
            copy.setLinenClosetFloors(this.linenClosetFloors);
            return copy;
        }
    }

    /**
     * ★★追加: リネン庫担当フロアの割り当て後処理
     * 各リネン庫担当スタッフの清掃担当フロアから、重複なくリネン庫フロアを割り振る
     */
    public static void assignLinenClosetFloors(List<StaffAssignment> assignments) {
        // リネン庫担当スタッフを抽出
        List<StaffAssignment> linenStaff = new ArrayList<>();
        for (StaffAssignment a : assignments) {
            if (a.isLinenClosetCleaning && a.linenClosetFloorCount > 0) {
                linenStaff.add(a);
            }
        }
        if (linenStaff.isEmpty()) return;

        LOGGER.info("=== リネン庫担当フロア割り当て開始 ===");

        // --- 二部マッチングによる最適割り当て ---
        // スタッフが複数フロアを要求する場合に備え、スロット方式を採用
        // 例: スタッフAが2フロア必要 → スロット(A,0), (A,1) を作成

        // 全候補フロアを収集し、インデックス化
        Set<Integer> allFloorSet = new LinkedHashSet<>();
        for (StaffAssignment staff : linenStaff) {
            allFloorSet.addAll(staff.floors);
        }
        List<Integer> allFloors = new ArrayList<>(allFloorSet);
        Collections.sort(allFloors);
        Map<Integer, Integer> floorToIndex = new HashMap<>();
        for (int i = 0; i < allFloors.size(); i++) {
            floorToIndex.put(allFloors.get(i), i);
        }
        int numFloors = allFloors.size();

        // スロットを構築: (スタッフインデックス, スロット番号) → スロット通し番号
        List<int[]> slots = new ArrayList<>(); // [staffIdx, slotNum]
        List<List<Integer>> slotToFloorIndices = new ArrayList<>(); // 各スロットが接続可能なフロアインデックス

        for (int si = 0; si < linenStaff.size(); si++) {
            StaffAssignment staff = linenStaff.get(si);
            Set<Integer> staffFloorIndices = new HashSet<>();
            for (int floor : staff.floors) {
                Integer idx = floorToIndex.get(floor);
                if (idx != null) staffFloorIndices.add(idx);
            }
            List<Integer> sortedFloorIndices = new ArrayList<>(staffFloorIndices);
            Collections.sort(sortedFloorIndices);

            for (int k = 0; k < staff.linenClosetFloorCount; k++) {
                slots.add(new int[]{si, k});
                slotToFloorIndices.add(sortedFloorIndices);
            }
        }
        int numSlots = slots.size();

        // 二部マッチング: スロット → フロア（増加パス法 / Hungarian method）
        int[] slotMatch = new int[numSlots];   // slotMatch[slot] = マッチしたフロアインデックス (-1 = 未マッチ)
        int[] floorMatch = new int[numFloors]; // floorMatch[floor] = マッチしたスロット番号 (-1 = 未マッチ)
        Arrays.fill(slotMatch, -1);
        Arrays.fill(floorMatch, -1);

        for (int s = 0; s < numSlots; s++) {
            // 各スロットについて増加パスを探索
            boolean[] visited = new boolean[numFloors];
            augment(s, slotToFloorIndices, slotMatch, floorMatch, visited);
        }

        // マッチング結果を各スタッフに反映
        // まずスタッフごとに割り当てられたフロアを集める
        Map<Integer, List<Integer>> staffIdxToFloors = new HashMap<>();
        for (int s = 0; s < numSlots; s++) {
            int staffIdx = slots.get(s)[0];
            if (slotMatch[s] >= 0) {
                staffIdxToFloors.computeIfAbsent(staffIdx, k -> new ArrayList<>())
                        .add(allFloors.get(slotMatch[s]));
            }
        }

        for (int si = 0; si < linenStaff.size(); si++) {
            StaffAssignment staff = linenStaff.get(si);
            List<Integer> assigned = staffIdxToFloors.getOrDefault(si, new ArrayList<>());
            Collections.sort(assigned);
            staff.setLinenClosetFloors(assigned);

            int needed = staff.linenClosetFloorCount;
            LOGGER.info(String.format("  %s: リネン庫担当フロア = %s（要求: %d階、割当: %d階）",
                    staff.staff.name, staff.getLinenClosetFloorsDisplay(),
                    needed, assigned.size()));

            if (assigned.size() < needed) {
                LOGGER.warning(String.format("  警告: %s のリネン庫フロアが不足しています（要求: %d、割当: %d）",
                        staff.staff.name, needed, assigned.size()));
            }
        }

        LOGGER.info("=== リネン庫担当フロア割り当て完了 ===");
    }

    /**
     * 二部マッチングの増加パス探索（DFS）
     * スロットsから未マッチのフロアへの増加パスを見つけ、マッチングを更新する
     *
     * @return 増加パスが見つかった場合true
     */
    private static boolean augment(int slot, List<List<Integer>> slotToFloorIndices,
                                   int[] slotMatch, int[] floorMatch, boolean[] visited) {
        for (int floorIdx : slotToFloorIndices.get(slot)) {
            if (visited[floorIdx]) continue;
            visited[floorIdx] = true;

            // このフロアが未マッチ、または既マッチ先スロットから別の増加パスが見つかる場合
            if (floorMatch[floorIdx] < 0 || augment(floorMatch[floorIdx], slotToFloorIndices, slotMatch, floorMatch, visited)) {
                slotMatch[slot] = floorIdx;
                floorMatch[floorIdx] = slot;
                return true;
            }
        }
        return false;
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

                // ★★追加: リネン庫清掃情報をroomDistributionから取得
                boolean isLinenCleaning = false;
                int linenFloorCount = 0;
                if (roomDistribution != null) {
                    NormalRoomDistributionDialog.StaffDistribution dist = roomDistribution.get(staff.name);
                    if (dist != null && dist.isLinenClosetCleaning) {
                        isLinenCleaning = true;
                        linenFloorCount = dist.linenClosetFloorCount;
                    }
                }

                extendedInfo.add(new ExtendedStaffInfo(staff, staffBathType, isLinenCleaning, linenFloorCount));
                bathAssignments.put(staff.name, staffBathType);
            }

            LOGGER.info(String.format("大浴場清掃担当設定完了: %d人",
                    bathAssignments.values().stream().mapToInt(t -> t != BathCleaningType.NONE ? 1 : 0).sum()));

            return new AdaptiveLoadConfig(availableStaff, extendedInfo, bathAssignments, pointConstraints, roomDistribution);
        }
    }

    /**
     * 適応型最適化エンジン(部屋タイプ別割り振り対応版)
     */
    public static class AdaptiveOptimizer {
        private final List<FloorInfo> floors;
        private final AdaptiveLoadConfig config;

        public AdaptiveOptimizer(List<FloorInfo> floors, AdaptiveLoadConfig config) {
            this.floors = new ArrayList<>(floors);
            this.config = config;
        }
// ============================================================
// AdaptiveOptimizer クラス内の optimize メソッド（CP-SAT統合版・完全版）
// ============================================================


        /**
         * ★追加: 複数解を返す最適化メソッド
         */
        public MultiOptimizationResult optimizeMultiple(LocalDate targetDate) {
            LOGGER.info("=== 適応型複数解最適化を開始 ===");

            // 部屋割り振り設定の必須チェック
            if (config.roomDistribution == null || config.roomDistribution.isEmpty()) {
                throw new IllegalStateException(
                        "部屋割り振り設定が必須です。通常清掃部屋割り振りダイアログで設定してください。");
            }

            // 建物データの分離（本館・別館）
            BuildingData buildingData = separateBuildings(floors);

            // CP-SATソルバーを使用した複数解最適化
            RoomAssignmentCPSATOptimizer.MultiSolutionResult multiResult;

            try {
                LOGGER.info("CP-SATソルバーを使用した複数解最適化を開始します");
                multiResult = RoomAssignmentCPSATOptimizer.optimizeMultipleSolutions(buildingData, config);
                LOGGER.info(String.format("CP-SATソルバーによる複数解最適化が成功しました（%d解）",
                        multiResult.totalSolutionCount));

            } catch (NoClassDefFoundError e) {
                String errorMsg = "OR-Toolsライブラリが見つかりません。\n" +
                        "pom.xmlにortools-javaの依存関係を追加してください。";
                LOGGER.severe(errorMsg);
                throw new RuntimeException(errorMsg, e);

            } catch (UnsatisfiedLinkError e) {
                String errorMsg = "OR-Toolsのネイティブライブラリのロードに失敗しました。\n" +
                        "詳細: " + e.getMessage();
                LOGGER.severe(errorMsg);
                throw new RuntimeException(errorMsg, e);

            } catch (RuntimeException e) {
                LOGGER.severe("最適化に失敗しました: " + e.getMessage());
                throw e;

            } catch (Exception e) {
                String errorMsg = "最適化中に予期しないエラーが発生しました: " + e.getMessage();
                LOGGER.severe(errorMsg);
                e.printStackTrace();
                throw new RuntimeException(errorMsg, e);
            }

            // 複数解の最適化結果を返す
            return new MultiOptimizationResult(multiResult, config, targetDate);
        }


// ============================================================
// 補足: 修正が必要な箇所（BuildingDataのアクセス修飾子）
// ============================================================
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
     * ★新規: 部屋タイプ列挙型
     */
    private enum RoomType {
        SINGLE,  // シングル等(T, NT, ANT, ADT以外の全て)
        TWIN     // ツイン(本館: T, NT / 別館: ANT, ADT)
    }

    /**
     * ★改善版: フロア部屋プールクラス(部屋タイプ別割り振り対応)
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

        /**
         * ★新規: 部屋タイプ別の利用可能部屋数を取得
         */
        int getRoomsAvailableByType(RoomType roomType) {
            int count = 0;
            for (Map.Entry<String, Integer> entry : normalRoomsAvailable.entrySet()) {
                String type = entry.getKey();
                if (isRoomTypeMatch(type, roomType, floor.isMainBuilding)) {
                    count += entry.getValue();
                }
            }
            return count;
        }

        /**
         * ★新規: 部屋タイプコードが指定されたタイプに一致するか判定
         */
        private boolean isRoomTypeMatch(String roomTypeCode, RoomType roomType, boolean isMainBuilding) {
            if (roomType == RoomType.TWIN) {
                // 本館ツイン: T, NT
                // 別館ツイン: ANT, ADT
                if (isMainBuilding) {
                    return "T".equals(roomTypeCode) || "NT".equals(roomTypeCode);
                } else {
                    return "ANT".equals(roomTypeCode) || "ADT".equals(roomTypeCode);
                }
            } else {
                // シングル等: ツイン以外の全て
                if (isMainBuilding) {
                    return !"T".equals(roomTypeCode) && !"NT".equals(roomTypeCode);
                } else {
                    return !"ANT".equals(roomTypeCode) && !"ADT".equals(roomTypeCode);
                }
            }
        }

        /**
         * ★改善版: 部屋タイプを指定して通常部屋を割り当て
         */
        Map<String, Integer> allocateRoomsByType(int count, RoomType roomType) {
            Map<String, Integer> allocated = new HashMap<>();
            int remaining = count;

            // 指定された部屋タイプに一致する部屋タイプをフィルタリング
            List<String> matchingTypes = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : normalRoomsAvailable.entrySet()) {
                if (isRoomTypeMatch(entry.getKey(), roomType, floor.isMainBuilding) && entry.getValue() > 0) {
                    matchingTypes.add(entry.getKey());
                }
            }

            // 多い順にソート
            matchingTypes.sort((a, b) -> Integer.compare(
                    normalRoomsAvailable.get(b), normalRoomsAvailable.get(a)));

            // 各タイプから順に割り当て
            for (String type : matchingTypes) {
                if (remaining <= 0) break;

                int available = normalRoomsAvailable.get(type);
                int toAllocate = Math.min(remaining, available);

                if (toAllocate > 0) {
                    allocated.put(type, toAllocate);
                    normalRoomsAvailable.put(type, available - toAllocate);
                    remaining -= toAllocate;
                }
            }

            if (remaining > 0) {
                String roomTypeName = roomType == RoomType.SINGLE ? "シングル等" : "ツイン";
                LOGGER.warning(String.format("警告: %dF %s 要求%d室だが%d室しか割り当てられませんでした",
                        floor.floorNumber, roomTypeName, count, count - remaining));
            }

            return allocated;
        }

        /**
         * ★既存: エコ部屋を割り当て
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
                mainNormalRooms.merge(floor, new HashMap<>(rooms), (oldMap, newMap) -> {
                    Map<String, Integer> merged = new HashMap<>(oldMap);
                    for (Map.Entry<String, Integer> entry : newMap.entrySet()) {
                        merged.merge(entry.getKey(), entry.getValue(), Integer::sum);
                    }
                    return merged;
                });
            } else {
                annexNormalRooms.merge(floor, new HashMap<>(rooms), (oldMap, newMap) -> {
                    Map<String, Integer> merged = new HashMap<>(oldMap);
                    for (Map.Entry<String, Integer> entry : newMap.entrySet()) {
                        merged.merge(entry.getKey(), entry.getValue(), Integer::sum);
                    }
                    return merged;
                });
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

            // 通常スタッフ: 合計2階まで
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
                    staffInfo.bathCleaningType, staffInfo.isLinenClosetCleaning, staffInfo.linenClosetFloorCount);
        }
    }

    /**
     * 建物データクラス
     */
    public static class BuildingData  {
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

    /**
     * ★追加: 複数解を保持する最適化結果クラス
     */
    public static class MultiOptimizationResult {
        public final RoomAssignmentCPSATOptimizer.MultiSolutionResult multiSolutionResult;
        public final AdaptiveLoadConfig config;
        public final LocalDate targetDate;
        public final int totalSolutionCount;
        private int currentIndex;

        public MultiOptimizationResult(
                RoomAssignmentCPSATOptimizer.MultiSolutionResult multiSolutionResult,
                AdaptiveLoadConfig config,
                LocalDate targetDate) {
            this.multiSolutionResult = multiSolutionResult;
            this.config = config;
            this.targetDate = targetDate;
            this.totalSolutionCount = multiSolutionResult.totalSolutionCount;
            this.currentIndex = 0;

            // ★★追加: 各解のリネン庫担当フロアを割り当て
            for (int i = 0; i < totalSolutionCount; i++) {
                RoomAssignmentCPSATOptimizer.OptimizationResultWithUnassigned result =
                        multiSolutionResult.getSolution(i);
                if (result != null && result.assignments != null) {
                    assignLinenClosetFloors(result.assignments);
                }
            }
        }

        /**
         * 指定インデックスの解を取得
         */
        public List<StaffAssignment> getAssignments(int index) {
            RoomAssignmentCPSATOptimizer.OptimizationResultWithUnassigned result =
                    multiSolutionResult.getSolution(index);
            return result != null ? result.assignments : new ArrayList<>();
        }

        /**
         * 指定インデックスの未割当情報を取得
         */
        public RoomAssignmentCPSATOptimizer.UnassignedRooms getUnassignedRooms(int index) {
            RoomAssignmentCPSATOptimizer.OptimizationResultWithUnassigned result =
                    multiSolutionResult.getSolution(index);
            return result != null ? result.unassignedRooms : new RoomAssignmentCPSATOptimizer.UnassignedRooms();
        }

        /**
         * 複数解があるかどうか
         */
        public boolean hasMultipleSolutions() {
            return totalSolutionCount > 1;
        }

        /**
         * 従来互換: 最初の解をOptimizationResultとして取得
         */
        public OptimizationResult toSingleResult() {
            return new OptimizationResult(getAssignments(0), config, targetDate);
        }

        /**
         * 指定インデックスの解をOptimizationResultとして取得
         */
        public OptimizationResult toSingleResult(int index) {
            return new OptimizationResult(getAssignments(index), config, targetDate);
        }

        public void printDetailedSummary() {
            System.out.println("\n=== 複数解最適化結果サマリー ===");
            System.out.printf("対象日: %s\n", targetDate.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")));
            System.out.printf("生成された解の数: %d\n", totalSolutionCount);

            for (int i = 0; i < totalSolutionCount; i++) {
                List<StaffAssignment> assignments = getAssignments(i);
                int totalRooms = assignments.stream().mapToInt(StaffAssignment::getTotalRooms).sum();
                RoomAssignmentCPSATOptimizer.UnassignedRooms unassigned = getUnassignedRooms(i);

                System.out.printf("\n--- 解 %d/%d ---\n", i + 1, totalSolutionCount);
                System.out.printf("  スタッフ数: %d人\n", assignments.size());
                System.out.printf("  総部屋数: %d室\n", totalRooms);
                System.out.printf("  未割当: %d室\n", unassigned.getTotalUnassigned());
            }
        }
    }
}