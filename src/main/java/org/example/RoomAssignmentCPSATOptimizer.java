package org.example;

import com.google.ortools.sat.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * CP-SATソルバーを使用した部屋割り振り最適化
 *
 * ★処理フロー（ECO分離版・部分解対応）:
 * 1. ツイン割り振り（フロア単位）
 * 2. シングル等割り振り（ECO除外、ソフト制約で部分解も許容）
 * 3. ECO割り振り（CP-SAT最適化：2階またぎ制限・負荷均等化）
 */
public class RoomAssignmentCPSATOptimizer {
    private static final Logger LOGGER = Logger.getLogger(RoomAssignmentCPSATOptimizer.class.getName());

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
     * メイン最適化メソッド
     */
    public static List<AdaptiveRoomOptimizer.StaffAssignment> optimize(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        LOGGER.info("=== CP-SATソルバーによる最適化を開始（部分解対応版） ===");

        List<Map<String, TwinAssignment>> twinPatterns = generateTwinPatterns(buildingData, config);
        LOGGER.info("ツインパターン数: " + twinPatterns.size());

        PartialSolutionResult bestResult = null;
        int bestPatternIndex = -1;

        for (int i = 0; i < twinPatterns.size(); i++) {
            Map<String, TwinAssignment> pattern = twinPatterns.get(i);

            if (i % 20 == 0) {
                LOGGER.info("=== ツインパターン " + (i + 1) + "/" + twinPatterns.size() + " を試行中 ===");
            }

            Map<String, FloorTwinAssignment> twinResult = assignTwins(buildingData, config, pattern);
            if (twinResult == null) {
                continue;
            }

            PartialSolutionResult result = assignSinglesWithSoftConstraints(
                    buildingData, config, twinResult);

            if (result != null) {
                if (result.shortage == 0) {
                    LOGGER.info("=== 完全な解が見つかりました（パターン " + (i + 1) + "） ===");
                    assignEcoRooms(result.assignments, buildingData, config);
                    return result.assignments;
                }

                if (bestResult == null || result.totalAssignedRooms > bestResult.totalAssignedRooms) {
                    bestResult = result;
                    bestPatternIndex = i + 1;
                }
            }
        }

        if (bestResult != null) {
            LOGGER.severe("=== 完全な解が見つかりませんでした ===");
            LOGGER.severe("最良の部分解（パターン " + bestPatternIndex + "）を使用します");
            LOGGER.severe(String.format("割り振り済み: %d室 / 目標: %d室（不足: %d室）",
                    bestResult.totalAssignedRooms, bestResult.totalTargetRooms, bestResult.shortage));

            LOGGER.severe("=== スタッフ別不足数 ===");
            for (Map.Entry<String, Integer> entry : bestResult.staffShortage.entrySet()) {
                if (entry.getValue() > 0) {
                    LOGGER.severe("  " + entry.getKey() + ": " + entry.getValue() + "室不足");
                }
            }

            assignEcoRooms(bestResult.assignments, buildingData, config);
            return bestResult.assignments;
        }

        throw new RuntimeException("全てのツインパターンを試行しましたが、解が見つかりませんでした。\n" +
                "通常清掃部屋割り振り設定を見直してください。");
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
                    p.get(s1).mainTwinRooms--;
                    p.get(s2).mainTwinRooms++;
                    adjusted.add(p);
                }
                if (ta2.mainTwinRooms > 0) {
                    Map<String, TwinAssignment> p = deepCopyPattern(basePattern);
                    p.get(s2).mainTwinRooms--;
                    p.get(s1).mainTwinRooms++;
                    adjusted.add(p);
                }
                if (ta1.annexTwinRooms > 0) {
                    Map<String, TwinAssignment> p = deepCopyPattern(basePattern);
                    p.get(s1).annexTwinRooms--;
                    p.get(s2).annexTwinRooms++;
                    adjusted.add(p);
                }
                if (ta2.annexTwinRooms > 0) {
                    Map<String, TwinAssignment> p = deepCopyPattern(basePattern);
                    p.get(s2).annexTwinRooms--;
                    p.get(s1).annexTwinRooms++;
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

    /**
     * ステップ1: ツインの割り振り
     */
    private static Map<String, FloorTwinAssignment> assignTwins(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config,
            Map<String, TwinAssignment> twinPattern) {

        LOGGER.info("--- ステップ1: ツインの割り振り ---");

        CpModel model = new CpModel();

        List<AdaptiveRoomOptimizer.ExtendedStaffInfo> staffList = config.extendedStaffInfo;

        Map<String, Map<Integer, IntVar>> twinVars = new HashMap<>();
        Map<String, Map<Integer, BoolVar>> floorVars = new HashMap<>();

        Map<Integer, Integer> mainFloorTwins = new HashMap<>();
        Map<Integer, Integer> annexFloorTwins = new HashMap<>();

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

        LOGGER.info(String.format("本館ツインフロア: %s", mainFloorTwins));
        LOGGER.info(String.format("別館ツインフロア: %s", annexFloorTwins));

        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            TwinAssignment ta = twinPattern.get(staffName);

            if (ta == null || (ta.mainTwinRooms == 0 && ta.annexTwinRooms == 0)) {
                continue;
            }

            Map<Integer, IntVar> staffTwinVars = new HashMap<>();
            Map<Integer, BoolVar> staffFloorVars = new HashMap<>();

            NormalRoomDistributionDialog.StaffDistribution dist =
                    config.roomDistribution.get(staffName);

            boolean canDoMain = true;
            boolean canDoAnnex = true;

            if (dist != null) {
                canDoMain = dist.mainTwinAssignedRooms > 0 ||
                        (dist.mainSingleAssignedRooms > 0 && ta.mainTwinRooms > 0);
                canDoAnnex = dist.annexTwinAssignedRooms > 0 ||
                        (dist.annexSingleAssignedRooms > 0 && ta.annexTwinRooms > 0);

                if (ta.mainTwinRooms > 0) canDoMain = true;
                if (ta.annexTwinRooms > 0) canDoAnnex = true;
            }

            if (canDoMain && ta.mainTwinRooms > 0) {
                for (Integer floorNum : mainFloorTwins.keySet()) {
                    int maxTwins = mainFloorTwins.get(floorNum);
                    String varName = String.format("twin_%s_main_%d", staffName, floorNum);
                    IntVar var = model.newIntVar(0, maxTwins, varName);
                    staffTwinVars.put(floorNum, var);

                    String floorVarName = String.format("floor_%s_main_%d", staffName, floorNum);
                    BoolVar fVar = model.newBoolVar(floorVarName);
                    staffFloorVars.put(floorNum, fVar);

                    model.addGreaterThan(var, 0).onlyEnforceIf(fVar);
                    model.addEquality(var, 0).onlyEnforceIf(fVar.not());
                }
            }

            if (canDoAnnex && ta.annexTwinRooms > 0) {
                for (Integer floorNum : annexFloorTwins.keySet()) {
                    int maxTwins = annexFloorTwins.get(floorNum);
                    String varName = String.format("twin_%s_annex_%d", staffName, floorNum);
                    IntVar var = model.newIntVar(0, maxTwins, varName);
                    staffTwinVars.put(1000 + floorNum, var);

                    String floorVarName = String.format("floor_%s_annex_%d", staffName, floorNum);
                    BoolVar fVar = model.newBoolVar(floorVarName);
                    staffFloorVars.put(1000 + floorNum, fVar);

                    model.addGreaterThan(var, 0).onlyEnforceIf(fVar);
                    model.addEquality(var, 0).onlyEnforceIf(fVar.not());
                }
            }

            twinVars.put(staffName, staffTwinVars);
            floorVars.put(staffName, staffFloorVars);
        }

        // 制約1: 各スタッフのツイン総数
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            TwinAssignment ta = twinPattern.get(staffName);

            if (ta == null) continue;

            Map<Integer, IntVar> staffTwinVars = twinVars.get(staffName);
            if (staffTwinVars == null) continue;

            if (ta.mainTwinRooms > 0) {
                List<IntVar> mainVars = new ArrayList<>();
                for (Integer floorNum : mainFloorTwins.keySet()) {
                    if (staffTwinVars.containsKey(floorNum)) {
                        mainVars.add(staffTwinVars.get(floorNum));
                    }
                }
                if (!mainVars.isEmpty()) {
                    model.addEquality(
                            LinearExpr.sum(mainVars.toArray(new IntVar[0])),
                            ta.mainTwinRooms);
                }
            }

            if (ta.annexTwinRooms > 0) {
                List<IntVar> annexVars = new ArrayList<>();
                for (Integer floorNum : annexFloorTwins.keySet()) {
                    if (staffTwinVars.containsKey(1000 + floorNum)) {
                        annexVars.add(staffTwinVars.get(1000 + floorNum));
                    }
                }
                if (!annexVars.isEmpty()) {
                    model.addEquality(
                            LinearExpr.sum(annexVars.toArray(new IntVar[0])),
                            ta.annexTwinRooms);
                }
            }
        }

        // 制約2: 各フロアのツイン総数
        for (Integer floorNum : mainFloorTwins.keySet()) {
            int totalTwins = mainFloorTwins.get(floorNum);
            List<IntVar> vars = new ArrayList<>();

            for (String staffName : twinVars.keySet()) {
                Map<Integer, IntVar> staffTwinVars = twinVars.get(staffName);
                if (staffTwinVars.containsKey(floorNum)) {
                    vars.add(staffTwinVars.get(floorNum));
                }
            }

            if (!vars.isEmpty()) {
                model.addEquality(LinearExpr.sum(vars.toArray(new IntVar[0])), totalTwins);
            }
        }

        for (Integer floorNum : annexFloorTwins.keySet()) {
            int totalTwins = annexFloorTwins.get(floorNum);
            List<IntVar> vars = new ArrayList<>();

            for (String staffName : twinVars.keySet()) {
                Map<Integer, IntVar> staffTwinVars = twinVars.get(staffName);
                if (staffTwinVars.containsKey(1000 + floorNum)) {
                    vars.add(staffTwinVars.get(1000 + floorNum));
                }
            }

            if (!vars.isEmpty()) {
                model.addEquality(LinearExpr.sum(vars.toArray(new IntVar[0])), totalTwins);
            }
        }

        // 制約3: フロア制限
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            Map<Integer, BoolVar> staffFloorVars = floorVars.get(staffName);

            if (staffFloorVars == null || staffFloorVars.isEmpty()) continue;

            int maxFloors = getMaxFloors(staffName, config);

            List<BoolVar> allFloorVars = new ArrayList<>(staffFloorVars.values());
            if (!allFloorVars.isEmpty()) {
                model.addLessOrEqual(
                        LinearExpr.sum(allFloorVars.toArray(new BoolVar[0])),
                        maxFloors);
            }
        }

        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(30.0);

        CpSolverStatus status = solver.solve(model);

        if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) {
            LOGGER.warning("ツイン割り振りで解が見つかりませんでした");
            return null;
        }

        Map<String, FloorTwinAssignment> result = new HashMap<>();

        for (String staffName : twinVars.keySet()) {
            FloorTwinAssignment fta = new FloorTwinAssignment();
            Map<Integer, IntVar> staffTwinVars = twinVars.get(staffName);

            for (Integer floorNum : mainFloorTwins.keySet()) {
                if (staffTwinVars.containsKey(floorNum)) {
                    int value = (int) solver.value(staffTwinVars.get(floorNum));
                    if (value > 0) {
                        fta.mainFloorTwins.put(floorNum, value);
                        fta.usedFloors.add(floorNum);
                    }
                }
            }

            for (Integer floorNum : annexFloorTwins.keySet()) {
                if (staffTwinVars.containsKey(1000 + floorNum)) {
                    int value = (int) solver.value(staffTwinVars.get(1000 + floorNum));
                    if (value > 0) {
                        fta.annexFloorTwins.put(floorNum, value);
                        fta.usedFloors.add(1000 + floorNum);
                    }
                }
            }

            result.put(staffName, fta);

            LOGGER.info(String.format("  %s: 本館ツイン%s, 別館ツイン%s",
                    staffName, fta.mainFloorTwins, fta.annexFloorTwins));
        }

        return result;
    }

    /**
     * ステップ2: シングル等割り振り（ソフト制約版・部分解対応）
     */
    private static PartialSolutionResult assignSinglesWithSoftConstraints(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config,
            Map<String, FloorTwinAssignment> twinResult) {

        CpModel model = new CpModel();

        List<AdaptiveRoomOptimizer.ExtendedStaffInfo> staffList = config.extendedStaffInfo;
        List<AdaptiveRoomOptimizer.FloorInfo> allFloors = new ArrayList<>();
        allFloors.addAll(buildingData.mainFloors);
        allFloors.addAll(buildingData.annexFloors);

        Map<String, IntVar> xVars = new HashMap<>();
        Map<String, BoolVar> yVars = new HashMap<>();

        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            NormalRoomDistributionDialog.StaffDistribution dist =
                    config.roomDistribution != null ?
                            config.roomDistribution.get(staffName) : null;

            boolean hasMainRooms = false;
            boolean hasAnnexRooms = false;
            if (dist != null) {
                hasMainRooms = (dist.mainSingleAssignedRooms + dist.mainTwinAssignedRooms) > 0;
                hasAnnexRooms = (dist.annexSingleAssignedRooms + dist.annexTwinAssignedRooms) > 0;
            }

            for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
                int floorNum = floor.floorNumber;
                boolean isMain = floor.isMainBuilding;

                if (dist != null) {
                    if (!hasMainRooms && isMain) continue;
                    if (!hasAnnexRooms && !isMain) continue;
                }

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

        // ハード制約: 各フロアの部屋を使い切る
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
                    model.addEquality(LinearExpr.sum(vars.toArray(new IntVar[0])), entry.getValue());
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

            // 本館シングル
            if (dist.mainSingleAssignedRooms > 0) {
                List<IntVar> mainVars = new ArrayList<>();
                for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
                    for (String rt : floor.roomCounts.keySet()) {
                        if (isMainTwin(rt)) continue;
                        String vn = String.format("x_%s_%d_%s", staffName, floor.floorNumber, rt);
                        if (xVars.containsKey(vn)) mainVars.add(xVars.get(vn));
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

            // 別館シングル
            if (dist.annexSingleAssignedRooms > 0) {
                List<IntVar> annexVars = new ArrayList<>();
                for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.annexFloors) {
                    for (String rt : floor.roomCounts.keySet()) {
                        if (isAnnexTwin(rt)) continue;
                        String vn = String.format("x_%s_%d_%s", staffName, floor.floorNumber, rt);
                        if (xVars.containsKey(vn)) annexVars.add(xVars.get(vn));
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

        // フロア制限
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

        if (!shortageVars.isEmpty()) {
            model.minimize(LinearExpr.sum(shortageVars.toArray(new IntVar[0])));
        }

        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(30.0);
        CpSolverStatus status = solver.solve(model);

        if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) {
            return null;
        }

        List<AdaptiveRoomOptimizer.StaffAssignment> assignments =
                buildSolution(solver, xVars, twinResult, staffList, buildingData, config);

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
            if (staffShortageVars.containsKey(staffName + "_main")) {
                mainShort = (int) solver.value(staffShortageVars.get(staffName + "_main"));
            }
            if (staffShortageVars.containsKey(staffName + "_annex")) {
                annexShort = (int) solver.value(staffShortageVars.get(staffName + "_annex"));
            }

            int assigned = target - mainShort - annexShort;
            totalAssigned += assigned;
            staffShortage.put(staffName, mainShort + annexShort);
        }

        return new PartialSolutionResult(assignments, totalAssigned, totalTarget, staffShortage);
    }

    /**
     * ソリューション構築
     */
    private static List<AdaptiveRoomOptimizer.StaffAssignment> buildSolution(
            CpSolver solver, Map<String, IntVar> xVars,
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

            for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
                Map<String, Integer> rooms = new HashMap<>();
                if (fta != null && fta.mainFloorTwins.containsKey(floor.floorNumber)) {
                    rooms.put("T", fta.mainFloorTwins.get(floor.floorNumber));
                }
                for (String rt : floor.roomCounts.keySet()) {
                    if (isMainTwin(rt)) continue;
                    String vn = String.format("x_%s_%d_%s", staffName, floor.floorNumber, rt);
                    if (xVars.containsKey(vn)) {
                        int v = (int) solver.value(xVars.get(vn));
                        if (v > 0) rooms.put(rt, v);
                    }
                }
                if (!rooms.isEmpty()) {
                    mainAssignments.put(floor.floorNumber,
                            new AdaptiveRoomOptimizer.RoomAllocation(rooms, 0));
                }
            }

            for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.annexFloors) {
                Map<String, Integer> rooms = new HashMap<>();
                if (fta != null && fta.annexFloorTwins.containsKey(floor.floorNumber)) {
                    rooms.put("T", fta.annexFloorTwins.get(floor.floorNumber));
                }
                for (String rt : floor.roomCounts.keySet()) {
                    if (isAnnexTwin(rt)) continue;
                    String vn = String.format("x_%s_%d_%s", staffName, floor.floorNumber, rt);
                    if (xVars.containsKey(vn)) {
                        int v = (int) solver.value(xVars.get(vn));
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
    // ステップ3: CP-SATによるECO割り振り最適化
    // ============================================================

    /**
     * ステップ3: CP-SATによるECO割り振り最適化
     *
     * 制約:
     * 1. 各フロアのECO部屋数を使い切る
     * 2. スタッフは既存の担当フロアにのみECO割り当て可能（2階またぎ制限）
     * 3. 本館/別館は分離して考慮
     *
     * 目的関数:
     * - 総部屋数の最大値と最小値の差を最小化（負荷均等化）
     */
    private static void assignEcoRooms(
            List<AdaptiveRoomOptimizer.StaffAssignment> assignments,
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        LOGGER.info("--- ステップ3: CP-SATによるECO割り振り最適化 ---");

        // ECOフロア情報を収集
        List<EcoFloorInfo> ecoFloors = new ArrayList<>();
        for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
            if (floor.ecoRooms > 0) {
                ecoFloors.add(new EcoFloorInfo(floor.floorNumber, floor.ecoRooms, true));
            }
        }
        for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.annexFloors) {
            if (floor.ecoRooms > 0) {
                ecoFloors.add(new EcoFloorInfo(floor.floorNumber, floor.ecoRooms, false));
            }
        }

        if (ecoFloors.isEmpty()) {
            LOGGER.info("ECO部屋がありません");
            return;
        }

        int totalEco = ecoFloors.stream().mapToInt(f -> f.ecoCount).sum();
        LOGGER.info(String.format("ECO総数: %d室（%dフロア）", totalEco, ecoFloors.size()));

        // 各スタッフの現在の総部屋数と担当フロアを計算
        Map<String, Integer> currentTotals = new HashMap<>();
        Map<String, Set<Integer>> staffMainFloors = new HashMap<>();
        Map<String, Set<Integer>> staffAnnexFloors = new HashMap<>();

        for (AdaptiveRoomOptimizer.StaffAssignment sa : assignments) {
            String name = sa.staff.name;
            currentTotals.put(name, sa.getTotalRooms());
            staffMainFloors.put(name, new HashSet<>(sa.mainBuildingAssignments.keySet()));
            staffAnnexFloors.put(name, new HashSet<>(sa.annexBuildingAssignments.keySet()));
        }

        // CP-SATモデル構築
        CpModel model = new CpModel();

        // ECO割り当て変数
        Map<String, Map<String, Map<Integer, IntVar>>> ecoVars = new HashMap<>();

        for (AdaptiveRoomOptimizer.StaffAssignment sa : assignments) {
            String staffName = sa.staff.name;
            int maxFloors = getMaxFloors(staffName, config);
            Set<Integer> currentMainFloors = staffMainFloors.get(staffName);
            Set<Integer> currentAnnexFloors = staffAnnexFloors.get(staffName);

            Map<String, Map<Integer, IntVar>> buildingMap = new HashMap<>();
            buildingMap.put("main", new HashMap<>());
            buildingMap.put("annex", new HashMap<>());

            for (EcoFloorInfo ecoFloor : ecoFloors) {
                String building = ecoFloor.isMain ? "main" : "annex";
                Set<Integer> currentFloors = ecoFloor.isMain ? currentMainFloors : currentAnnexFloors;

                boolean canAssign = canAssignEcoToFloor(
                        staffName, ecoFloor.floorNumber, ecoFloor.isMain,
                        currentFloors, currentMainFloors, currentAnnexFloors,
                        maxFloors, config);

                if (canAssign) {
                    String varName = String.format("eco_%s_%s_%d",
                            staffName, building, ecoFloor.floorNumber);
                    IntVar var = model.newIntVar(0, ecoFloor.ecoCount, varName);
                    buildingMap.get(building).put(ecoFloor.floorNumber, var);
                }
            }
            ecoVars.put(staffName, buildingMap);
        }

        // 制約1: 各フロアのECO部屋を使い切る
        for (EcoFloorInfo ecoFloor : ecoFloors) {
            String building = ecoFloor.isMain ? "main" : "annex";
            List<IntVar> floorVars = new ArrayList<>();

            for (AdaptiveRoomOptimizer.StaffAssignment sa : assignments) {
                Map<Integer, IntVar> floorMap = ecoVars.get(sa.staff.name).get(building);
                if (floorMap.containsKey(ecoFloor.floorNumber)) {
                    floorVars.add(floorMap.get(ecoFloor.floorNumber));
                }
            }

            if (floorVars.isEmpty()) {
                LOGGER.warning(String.format("フロア %d（%s）にECOを割り当て可能なスタッフがいません",
                        ecoFloor.floorNumber, ecoFloor.isMain ? "本館" : "別館"));
                assignEcoRoomsFallback(assignments, buildingData, config);
                return;
            }

            model.addEquality(
                    LinearExpr.sum(floorVars.toArray(new IntVar[0])),
                    ecoFloor.ecoCount);
        }

        // 制約2: 2階またぎ制限（新規フロア追加時）
        for (AdaptiveRoomOptimizer.StaffAssignment sa : assignments) {
            String staffName = sa.staff.name;
            int maxFloors = getMaxFloors(staffName, config);

            Set<Integer> existingAllFloors = new HashSet<>();
            existingAllFloors.addAll(staffMainFloors.get(staffName));
            existingAllFloors.addAll(staffAnnexFloors.get(staffName));

            List<BoolVar> newFloorIndicators = new ArrayList<>();

            for (String building : Arrays.asList("main", "annex")) {
                Set<Integer> currentFloors = building.equals("main") ?
                        staffMainFloors.get(staffName) :
                        staffAnnexFloors.get(staffName);

                Map<Integer, IntVar> floorMap = ecoVars.get(staffName).get(building);
                for (Map.Entry<Integer, IntVar> entry : floorMap.entrySet()) {
                    if (!currentFloors.contains(entry.getKey())) {
                        BoolVar isUsed = model.newBoolVar(
                                String.format("newfloor_%s_%s_%d", staffName, building, entry.getKey()));
                        model.addGreaterThan(entry.getValue(), 0).onlyEnforceIf(isUsed);
                        model.addEquality(entry.getValue(), 0).onlyEnforceIf(isUsed.not());
                        newFloorIndicators.add(isUsed);
                    }
                }
            }

            int remainingFloors = maxFloors - existingAllFloors.size();
            if (!newFloorIndicators.isEmpty() && remainingFloors >= 0) {
                model.addLessOrEqual(
                        LinearExpr.sum(newFloorIndicators.toArray(new BoolVar[0])),
                        remainingFloors);
            }
        }

        // 目的関数: 総部屋数の均等化（max - min を最小化）
        List<IntVar> totalVars = new ArrayList<>();
        Map<String, IntVar> staffTotalVars = new HashMap<>();

        for (AdaptiveRoomOptimizer.StaffAssignment sa : assignments) {
            String staffName = sa.staff.name;
            int currentTotal = currentTotals.get(staffName);

            List<IntVar> staffEcoVars = new ArrayList<>();
            for (String building : Arrays.asList("main", "annex")) {
                staffEcoVars.addAll(ecoVars.get(staffName).get(building).values());
            }

            IntVar ecoSum = model.newIntVar(0, totalEco, "ecoSum_" + staffName);
            if (!staffEcoVars.isEmpty()) {
                model.addEquality(ecoSum, LinearExpr.sum(staffEcoVars.toArray(new IntVar[0])));
            } else {
                model.addEquality(ecoSum, 0);
            }

            IntVar total = model.newIntVar(currentTotal, currentTotal + totalEco, "total_" + staffName);
            model.addEquality(total, LinearExpr.newBuilder()
                    .add(currentTotal).addTerm(ecoSum, 1).build());
            totalVars.add(total);
            staffTotalVars.put(staffName, total);
        }

        int minPossible = currentTotals.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        int maxPossible = currentTotals.values().stream().mapToInt(Integer::intValue).max().orElse(0) + totalEco;

        IntVar maxTotal = model.newIntVar(minPossible, maxPossible, "maxTotal");
        IntVar minTotal = model.newIntVar(minPossible, maxPossible, "minTotal");
        model.addMaxEquality(maxTotal, totalVars.toArray(new IntVar[0]));
        model.addMinEquality(minTotal, totalVars.toArray(new IntVar[0]));

        IntVar spread = model.newIntVar(0, maxPossible - minPossible, "spread");
        model.addEquality(spread, LinearExpr.newBuilder()
                .addTerm(maxTotal, 1).addTerm(minTotal, -1).build());
        model.minimize(spread);

        // 求解
        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(10);
        CpSolverStatus status = solver.solve(model);

        if (status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE) {
            LOGGER.info(String.format("ECO最適化成功: spread = %d（max=%d, min=%d）",
                    solver.value(spread), solver.value(maxTotal), solver.value(minTotal)));

            for (AdaptiveRoomOptimizer.StaffAssignment sa : assignments) {
                String staffName = sa.staff.name;
                int staffEcoTotal = 0;

                for (String building : Arrays.asList("main", "annex")) {
                    Map<Integer, IntVar> floorMap = ecoVars.get(staffName).get(building);
                    for (Map.Entry<Integer, IntVar> entry : floorMap.entrySet()) {
                        int ecoCount = (int) solver.value(entry.getValue());
                        if (ecoCount > 0) {
                            addEcoToStaff(sa, entry.getKey(), ecoCount, building.equals("main"));
                            staffEcoTotal += ecoCount;
                        }
                    }
                }

                if (staffEcoTotal > 0) {
                    LOGGER.info(String.format("  %s: ECO %d室 → 総部屋数 %d",
                            staffName, staffEcoTotal, (int) solver.value(staffTotalVars.get(staffName))));
                }
            }
        } else {
            LOGGER.warning("ECO最適化で解が見つかりませんでした。フォールバック処理を実行します。");
            assignEcoRoomsFallback(assignments, buildingData, config);
        }
    }

    /**
     * ECO割り当て可能判定
     */
    private static boolean canAssignEcoToFloor(
            String staffName, int floorNumber, boolean isMain,
            Set<Integer> currentBuildingFloors,
            Set<Integer> allMainFloors, Set<Integer> allAnnexFloors,
            int maxFloors,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        // 既に同じ建物で担当しているフロアならOK
        if (currentBuildingFloors.contains(floorNumber)) {
            return true;
        }

        // 業者は制限なし
        AdaptiveRoomOptimizer.PointConstraint constraint = config.pointConstraints.get(staffName);
        if (constraint != null) {
            String type = constraint.constraintType;
            if (type.contains("業者") || type.contains("リライアンス")) {
                return true;
            }
        }

        // 大浴清掃スタッフは既存フロアのみ
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo si : config.extendedStaffInfo) {
            if (si.staff.name.equals(staffName) &&
                    si.bathCleaningType != AdaptiveRoomOptimizer.BathCleaningType.NONE) {
                return false;
            }
        }

        // 総フロア数チェック
        int totalFloors = allMainFloors.size() + allAnnexFloors.size();
        if (totalFloors >= maxFloors) {
            // 既に上限に達している場合、隣接フロアのみ許可
            for (int f : currentBuildingFloors) {
                if (Math.abs(f - floorNumber) <= 1) {
                    return true;
                }
            }
            return false;
        }

        // 隣接フロア（±1）ならOK
        for (int f : currentBuildingFloors) {
            if (Math.abs(f - floorNumber) <= 1) {
                return true;
            }
        }

        // 新規フロアでフロア制限内ならOK
        return totalFloors < maxFloors;
    }

    /**
     * フォールバック: 従来のラウンドロビン方式
     */
    private static void assignEcoRoomsFallback(
            List<AdaptiveRoomOptimizer.StaffAssignment> assignments,
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        LOGGER.info("--- フォールバック: ラウンドロビンECO割り振り ---");

        for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
            if (floor.ecoRooms > 0) {
                assignEcoToFloorFallback(assignments, floor.floorNumber, floor.ecoRooms, true);
            }
        }
        for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.annexFloors) {
            if (floor.ecoRooms > 0) {
                assignEcoToFloorFallback(assignments, floor.floorNumber, floor.ecoRooms, false);
            }
        }
    }

    /**
     * フォールバック: 個別フロアのECO割り振り（従来のラウンドロビン）
     */
    private static void assignEcoToFloorFallback(
            List<AdaptiveRoomOptimizer.StaffAssignment> assignments,
            int floorNumber, int ecoRooms, boolean isMain) {

        List<AdaptiveRoomOptimizer.StaffAssignment> floorStaff = new ArrayList<>();
        for (AdaptiveRoomOptimizer.StaffAssignment sa : assignments) {
            Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> fm =
                    isMain ? sa.mainBuildingAssignments : sa.annexBuildingAssignments;
            if (fm.containsKey(floorNumber)) floorStaff.add(sa);
        }

        if (floorStaff.isEmpty()) {
            AdaptiveRoomOptimizer.StaffAssignment minStaff = assignments.stream()
                    .min(Comparator.comparingInt(AdaptiveRoomOptimizer.StaffAssignment::getTotalRooms))
                    .orElse(null);
            if (minStaff != null) addEcoToStaff(minStaff, floorNumber, ecoRooms, isMain);
            return;
        }

        if (floorStaff.size() == 1) {
            addEcoToStaff(floorStaff.get(0), floorNumber, ecoRooms, isMain);
        } else {
            floorStaff.sort(Comparator.comparingInt(AdaptiveRoomOptimizer.StaffAssignment::getTotalRooms));
            int remaining = ecoRooms, idx = 0;
            while (remaining > 0) {
                addEcoToStaff(floorStaff.get(idx % floorStaff.size()), floorNumber, 1, isMain);
                remaining--;
                idx++;
            }
        }
    }

    /**
     * スタッフにECOを追加
     */
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
     * 最大フロア数取得
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