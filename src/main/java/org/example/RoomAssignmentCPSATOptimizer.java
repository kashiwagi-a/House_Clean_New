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
 * 3. ECO割り振り（担当フロアに分配）
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
        public int totalMainTwins = 0;
        public int totalAnnexTwins = 0;
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
        public final int shortage;  // 不足数
        public final Map<String, Integer> staffShortage;  // スタッフ別不足数

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
     * メイン最適化メソッド
     */
    public static List<AdaptiveRoomOptimizer.StaffAssignment> optimize(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        LOGGER.info("=== CP-SATソルバーによる最適化を開始（部分解対応版） ===");

        // ツインパターンを生成
        List<Map<String, TwinAssignment>> twinPatterns = generateTwinPatterns(buildingData, config);
        LOGGER.info("ツインパターン数: " + twinPatterns.size());

        PartialSolutionResult bestResult = null;
        int bestPatternIndex = -1;

        // 各パターンを試行
        for (int i = 0; i < twinPatterns.size(); i++) {
            Map<String, TwinAssignment> pattern = twinPatterns.get(i);

            if (i % 20 == 0) {
                LOGGER.info("=== ツインパターン " + (i + 1) + "/" + twinPatterns.size() + " を試行中 ===");
            }

            // ステップ1: ツイン割り振り
            Map<String, FloorTwinAssignment> twinResult = assignTwins(buildingData, config, pattern);
            if (twinResult == null) {
                continue;
            }

            // ステップ2: シングル等割り振り（ソフト制約で部分解も許容）
            PartialSolutionResult result = assignSinglesWithSoftConstraints(
                    buildingData, config, twinResult);

            if (result != null) {
                // 完全な解が見つかった場合
                if (result.shortage == 0) {
                    LOGGER.info("=== 完全な解が見つかりました（パターン " + (i + 1) + "） ===");
                    assignEcoRooms(result.assignments, buildingData, config);
                    return result.assignments;
                }

                // 部分解を記録（より良いものを保持）
                if (bestResult == null || result.totalAssignedRooms > bestResult.totalAssignedRooms) {
                    bestResult = result;
                    bestPatternIndex = i + 1;
                }
            }
        }

        // 完全な解がなかった場合、最良の部分解を返す
        if (bestResult != null) {
            LOGGER.severe("=== 完全な解が見つかりませんでした ===");
            LOGGER.severe("最良の部分解（パターン " + bestPatternIndex + "）を使用します");
            LOGGER.severe(String.format("割り振り済み: %d室 / 目標: %d室（不足: %d室）",
                    bestResult.totalAssignedRooms, bestResult.totalTargetRooms, bestResult.shortage));

            // スタッフ別不足を表示
            LOGGER.severe("=== スタッフ別不足数 ===");
            for (Map.Entry<String, Integer> entry : bestResult.staffShortage.entrySet()) {
                if (entry.getValue() > 0) {
                    LOGGER.severe("  " + entry.getKey() + ": " + entry.getValue() + "室不足");
                }
            }

            // ECO割り振りを実行
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
                    config.roomDistribution != null ? config.roomDistribution.get(staffName) : null;

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

    /**
     * ステップ1: ツイン割り振り
     */
    private static Map<String, FloorTwinAssignment> assignTwins(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config,
            Map<String, TwinAssignment> pattern) {

        CpModel model = new CpModel();
        List<AdaptiveRoomOptimizer.ExtendedStaffInfo> staffList = config.extendedStaffInfo;

        Map<Integer, Integer> mainFloorTwins = new HashMap<>();
        Map<Integer, Integer> annexFloorTwins = new HashMap<>();

        for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
            int tc = floor.roomCounts.entrySet().stream()
                    .filter(e -> isMainTwin(e.getKey())).mapToInt(Map.Entry::getValue).sum();
            if (tc > 0) mainFloorTwins.put(floor.floorNumber, tc);
        }
        for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.annexFloors) {
            int tc = floor.roomCounts.entrySet().stream()
                    .filter(e -> isAnnexTwin(e.getKey())).mapToInt(Map.Entry::getValue).sum();
            if (tc > 0) annexFloorTwins.put(floor.floorNumber, tc);
        }

        Map<String, IntVar> tVars = new HashMap<>();

        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            TwinAssignment ta = pattern.get(staffName);
            if (ta == null || (ta.mainTwinRooms == 0 && ta.annexTwinRooms == 0)) continue;

            NormalRoomDistributionDialog.StaffDistribution dist =
                    config.roomDistribution != null ? config.roomDistribution.get(staffName) : null;

            if (ta.mainTwinRooms > 0) {
                for (int floor : mainFloorTwins.keySet()) {
                    if (dist != null && "別館のみ".equals(dist.buildingAssignment)) continue;
                    String varName = "t_" + staffName + "_main_" + floor;
                    tVars.put(varName, model.newIntVar(0, mainFloorTwins.get(floor), varName));
                }
            }
            if (ta.annexTwinRooms > 0) {
                for (int floor : annexFloorTwins.keySet()) {
                    if (dist != null && "本館のみ".equals(dist.buildingAssignment)) continue;
                    String varName = "t_" + staffName + "_annex_" + floor;
                    tVars.put(varName, model.newIntVar(0, annexFloorTwins.get(floor), varName));
                }
            }
        }

        // 制約: 各フロアのツインを使い切る
        for (int floor : mainFloorTwins.keySet()) {
            List<IntVar> vars = new ArrayList<>();
            for (String vn : tVars.keySet()) {
                if (vn.contains("_main_" + floor)) vars.add(tVars.get(vn));
            }
            if (!vars.isEmpty()) {
                model.addEquality(LinearExpr.sum(vars.toArray(new IntVar[0])), mainFloorTwins.get(floor));
            }
        }
        for (int floor : annexFloorTwins.keySet()) {
            List<IntVar> vars = new ArrayList<>();
            for (String vn : tVars.keySet()) {
                if (vn.contains("_annex_" + floor)) vars.add(tVars.get(vn));
            }
            if (!vars.isEmpty()) {
                model.addEquality(LinearExpr.sum(vars.toArray(new IntVar[0])), annexFloorTwins.get(floor));
            }
        }

        // 制約: 各スタッフのツイン総数
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            TwinAssignment ta = pattern.get(staffName);
            if (ta == null) continue;

            if (ta.mainTwinRooms > 0) {
                List<IntVar> vars = new ArrayList<>();
                for (String vn : tVars.keySet()) {
                    if (vn.startsWith("t_" + staffName + "_main_")) vars.add(tVars.get(vn));
                }
                if (!vars.isEmpty()) {
                    model.addEquality(LinearExpr.sum(vars.toArray(new IntVar[0])), ta.mainTwinRooms);
                }
            }
            if (ta.annexTwinRooms > 0) {
                List<IntVar> vars = new ArrayList<>();
                for (String vn : tVars.keySet()) {
                    if (vn.startsWith("t_" + staffName + "_annex_")) vars.add(tVars.get(vn));
                }
                if (!vars.isEmpty()) {
                    model.addEquality(LinearExpr.sum(vars.toArray(new IntVar[0])), ta.annexTwinRooms);
                }
            }
        }

        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(10.0);
        CpSolverStatus status = solver.solve(model);

        if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) {
            return null;
        }

        Map<String, FloorTwinAssignment> result = new HashMap<>();
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            FloorTwinAssignment fta = new FloorTwinAssignment();

            for (String vn : tVars.keySet()) {
                if (!vn.startsWith("t_" + staffName + "_")) continue;
                int value = (int) solver.value(tVars.get(vn));
                if (value > 0) {
                    int floor = Integer.parseInt(vn.substring(vn.lastIndexOf("_") + 1));
                    if (vn.contains("_main_")) {
                        fta.mainFloorTwins.put(floor, value);
                        fta.usedFloors.add(floor);
                        fta.totalMainTwins += value;
                    } else {
                        fta.annexFloorTwins.put(floor, value);
                        fta.usedFloors.add(1000 + floor);
                        fta.totalAnnexTwins += value;
                    }
                }
            }
            result.put(staffName, fta);
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

        // 変数作成
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            NormalRoomDistributionDialog.StaffDistribution dist =
                    config.roomDistribution != null ? config.roomDistribution.get(staffName) : null;

            for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
                int floorNum = floor.floorNumber;
                boolean isMain = floor.isMainBuilding;

                if (dist != null) {
                    if ("本館のみ".equals(dist.buildingAssignment) && !isMain) continue;
                    if ("別館のみ".equals(dist.buildingAssignment) && isMain) continue;
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

        // ソフト制約: スタッフのシングル等総数（ペナルティ付き）
        List<IntVar> shortageVars = new ArrayList<>();
        Map<String, IntVar> staffShortageVars = new HashMap<>();

        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            NormalRoomDistributionDialog.StaffDistribution dist =
                    config.roomDistribution != null ? config.roomDistribution.get(staffName) : null;
            if (dist == null) continue;

            // 本館シングル等
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

                    // 不足分を計算
                    IntVar shortage = model.newIntVar(0, dist.mainSingleAssignedRooms, "short_main_" + staffName);
                    // shortage = max(0, target - sum)
                    IntVar diff = model.newIntVar(-1000, 1000, "diff_main_" + staffName);
                    model.addEquality(diff, LinearExpr.newBuilder()
                            .addTerm(sum, -1).add(dist.mainSingleAssignedRooms).build());
                    model.addMaxEquality(shortage, new IntVar[]{diff, model.newConstant(0)});
                    shortageVars.add(shortage);
                    staffShortageVars.put(staffName + "_main", shortage);
                }
            }

            // 別館シングル等
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

        // フロア制限（ソフト化しない、ハード制約として維持）
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

        // 目的関数: 不足の合計を最小化
        if (!shortageVars.isEmpty()) {
            model.minimize(LinearExpr.sum(shortageVars.toArray(new IntVar[0])));
        }

        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(30.0);
        CpSolverStatus status = solver.solve(model);

        if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) {
            return null;
        }

        // 結果を構築
        List<AdaptiveRoomOptimizer.StaffAssignment> assignments =
                buildSolution(solver, xVars, twinResult, staffList, buildingData, config);

        // 統計を計算
        int totalAssigned = 0, totalTarget = 0;
        Map<String, Integer> staffShortage = new HashMap<>();

        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            NormalRoomDistributionDialog.StaffDistribution dist =
                    config.roomDistribution != null ? config.roomDistribution.get(staffName) : null;
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
                        int v = (int) solver.value(xVars.get(vn));
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

    /**
     * ステップ3: ECO割り振り
     */
    private static void assignEcoRooms(
            List<AdaptiveRoomOptimizer.StaffAssignment> assignments,
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        LOGGER.info("--- ステップ3: ECO割り振り ---");

        for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
            if (floor.ecoRooms > 0) {
                assignEcoToFloor(assignments, floor.floorNumber, floor.ecoRooms, true);
            }
        }
        for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.annexFloors) {
            if (floor.ecoRooms > 0) {
                assignEcoToFloor(assignments, floor.floorNumber, floor.ecoRooms, false);
            }
        }
    }

    private static void assignEcoToFloor(
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
                remaining--; idx++;
            }
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