package org.example;

import com.google.ortools.Loader;
import com.google.ortools.sat.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Google OR-Tools CP-SATソルバーを使用した部屋割り当て最適化（全フロア使用版）
 *
 * ★特徴:
 * 1. 総部屋数は厳密に守る（例：EE=13室）
 * 2. ツイン内訳は最適化（できれば目標値、不可能ならシングルで代替）
 * 3. フロア制限は厳守（絶対条件）
 * 4. すべてのフロアのすべての部屋を使い切る（余らせない）
 *
 * ★動作原理:
 * CP-SATが各スタッフの担当フロアを自動選択し、
 * 全部屋を使い切りながらフロア制限を守る割り当てを見つける
 */
public class RoomAssignmentCPSATOptimizer {

    private static final Logger LOGGER = Logger.getLogger(RoomAssignmentCPSATOptimizer.class.getName());

    public static List<AdaptiveRoomOptimizer.StaffAssignment> optimize(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        LOGGER.info("=== CP-SATソルバーによる最適化を開始（全フロア使用版） ===");

        // OR-Toolsロード
        try {
            Loader.loadNativeLibraries();
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException("OR-Toolsのロードに失敗しました: " + e.getMessage(), e);
        }

        CpModel model = new CpModel();

        // データ準備
        List<AdaptiveRoomOptimizer.FloorInfo> allFloors = new ArrayList<>();
        allFloors.addAll(buildingData.mainFloors);
        allFloors.addAll(buildingData.annexFloors);

        List<AdaptiveRoomOptimizer.ExtendedStaffInfo> staffList = config.extendedStaffInfo;

        // 変数の作成
        Map<String, IntVar> xVars = new HashMap<>();
        Map<String, IntVar> eVars = new HashMap<>();
        Map<String, BoolVar> yVars = new HashMap<>();

        LOGGER.info("変数を作成中...");

        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;

            for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
                int floorNum = floor.floorNumber;
                boolean isMain = floor.isMainBuilding;

                NormalRoomDistributionDialog.StaffDistribution dist =
                        config.roomDistribution.get(staffName);

                if (dist != null) {
                    if ("本館のみ".equals(dist.buildingAssignment) && !isMain) continue;
                    if ("別館のみ".equals(dist.buildingAssignment) && isMain) continue;
                }

                // すべてのフロアを候補として変数を作成（CP-SATが最適なフロアを選択）
                for (Map.Entry<String, Integer> entry : floor.roomCounts.entrySet()) {
                    String roomType = entry.getKey();
                    int maxRooms = entry.getValue();

                    String varName = String.format("x_%s_%d_%s", staffName, floorNum, roomType);
                    IntVar var = model.newIntVar(0, maxRooms, varName);
                    xVars.put(varName, var);
                }

                if (floor.ecoRooms > 0) {
                    String eVarName = String.format("e_%s_%d", staffName, floorNum);
                    IntVar eVar = model.newIntVar(0, floor.ecoRooms, eVarName);
                    eVars.put(eVarName, eVar);
                }

                String yVarName = String.format("y_%s_%d", staffName, floorNum);
                BoolVar yVar = model.newBoolVar(yVarName);
                yVars.put(yVarName, yVar);
            }
        }

        LOGGER.info(String.format("変数作成完了: x=%d, e=%d, y=%d",
                xVars.size(), eVars.size(), yVars.size()));

        // 制約1: 各フロアの部屋数制約（完全使用 - すべての部屋を割り当てる）
        LOGGER.info("制約1: フロア部屋数制約を追加中（全部屋使い切り）...");
        for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
            for (Map.Entry<String, Integer> entry : floor.roomCounts.entrySet()) {
                String roomType = entry.getKey();
                int totalRooms = entry.getValue();

                List<IntVar> vars = new ArrayList<>();
                for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
                    String varName = String.format("x_%s_%d_%s",
                            staffInfo.staff.name, floor.floorNumber, roomType);
                    if (xVars.containsKey(varName)) {
                        vars.add(xVars.get(varName));
                    }
                }
                if (!vars.isEmpty()) {
                    // 全部屋を完全に使い切る（= 制約）
                    model.addEquality(LinearExpr.sum(vars.toArray(new IntVar[0])), totalRooms);
                }
            }

            if (floor.ecoRooms > 0) {
                List<IntVar> ecoVars = new ArrayList<>();
                for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
                    String eVarName = String.format("e_%s_%d",
                            staffInfo.staff.name, floor.floorNumber);
                    if (eVars.containsKey(eVarName)) {
                        ecoVars.add(eVars.get(eVarName));
                    }
                }
                if (!ecoVars.isEmpty()) {
                    // エコ室も完全に使い切る
                    model.addEquality(LinearExpr.sum(ecoVars.toArray(new IntVar[0])), floor.ecoRooms);
                }
            }
        }

        // 制約2: スタッフの部屋数制約（総数厳密、ツイン内訳最適化）
        LOGGER.info("制約2: スタッフ部屋数制約を追加中...");

        List<LinearArgument> penalties = new ArrayList<>();

        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            NormalRoomDistributionDialog.StaffDistribution dist =
                    config.roomDistribution.get(staffName);

            if (dist == null) continue;

            // 本館の総部屋数（厳密制約）
            int mainTotalTarget = dist.mainSingleAssignedRooms + dist.mainTwinAssignedRooms;
            if (mainTotalTarget > 0) {
                List<IntVar> allMainVars = new ArrayList<>();
                List<IntVar> twinVars = new ArrayList<>();

                for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
                    for (String roomType : floor.roomCounts.keySet()) {
                        String varName = String.format("x_%s_%d_%s",
                                staffName, floor.floorNumber, roomType);
                        if (xVars.containsKey(varName)) {
                            allMainVars.add(xVars.get(varName));
                            if (isMainTwin(roomType)) {
                                twinVars.add(xVars.get(varName));
                            }
                        }
                    }
                }

                if (!allMainVars.isEmpty()) {
                    // 総部屋数は厳密に守る
                    model.addEquality(
                            LinearExpr.sum(allMainVars.toArray(new IntVar[0])),
                            mainTotalTarget
                    );

                    // ツイン目標値とのズレをペナルティ化
                    if (dist.mainTwinAssignedRooms > 0 && !twinVars.isEmpty()) {
                        IntVar twinDeviation = model.newIntVar(
                                -dist.mainTwinAssignedRooms,
                                dist.mainTwinAssignedRooms,
                                "twin_dev_main_" + staffName
                        );

                        model.addEquality(
                                LinearExpr.sum(new LinearArgument[]{
                                        LinearExpr.sum(twinVars.toArray(new IntVar[0])),
                                        LinearExpr.term(twinDeviation, -1)
                                }),
                                dist.mainTwinAssignedRooms
                        );

                        // 絶対値をペナルティに追加
                        IntVar absDeviation = model.newIntVar(0, dist.mainTwinAssignedRooms,
                                "abs_twin_dev_main_" + staffName);
                        model.addAbsEquality(absDeviation, twinDeviation);
                        penalties.add(LinearExpr.term(absDeviation, 100));
                    }
                }
            }

            // 別館の総部屋数（厳密制約）
            int annexTotalTarget = dist.annexSingleAssignedRooms + dist.annexTwinAssignedRooms;
            if (annexTotalTarget > 0) {
                List<IntVar> allAnnexVars = new ArrayList<>();
                List<IntVar> twinVars = new ArrayList<>();

                for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.annexFloors) {
                    for (String roomType : floor.roomCounts.keySet()) {
                        String varName = String.format("x_%s_%d_%s",
                                staffName, floor.floorNumber, roomType);
                        if (xVars.containsKey(varName)) {
                            allAnnexVars.add(xVars.get(varName));
                            if (isAnnexTwin(roomType)) {
                                twinVars.add(xVars.get(varName));
                            }
                        }
                    }
                }

                if (!allAnnexVars.isEmpty()) {
                    // 総部屋数は厳密に守る
                    model.addEquality(
                            LinearExpr.sum(allAnnexVars.toArray(new IntVar[0])),
                            annexTotalTarget
                    );

                    // ツイン目標値とのズレをペナルティ化
                    if (dist.annexTwinAssignedRooms > 0 && !twinVars.isEmpty()) {
                        IntVar twinDeviation = model.newIntVar(
                                -dist.annexTwinAssignedRooms,
                                dist.annexTwinAssignedRooms,
                                "twin_dev_annex_" + staffName
                        );

                        model.addEquality(
                                LinearExpr.sum(new LinearArgument[]{
                                        LinearExpr.sum(twinVars.toArray(new IntVar[0])),
                                        LinearExpr.term(twinDeviation, -1)
                                }),
                                dist.annexTwinAssignedRooms
                        );

                        IntVar absDeviation = model.newIntVar(0, dist.annexTwinAssignedRooms,
                                "abs_twin_dev_annex_" + staffName);
                        model.addAbsEquality(absDeviation, twinDeviation);
                        penalties.add(LinearExpr.term(absDeviation, 100));
                    }
                }
            }
        }

        // 制約3: フロア制限（厳密制約 - 絶対条件）
        LOGGER.info("制約3: フロア制限制約を追加中（厳守）...");
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;

            List<BoolVar> floorVars = new ArrayList<>();
            for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
                String yVarName = String.format("y_%s_%d", staffName, floor.floorNumber);
                if (yVars.containsKey(yVarName)) {
                    floorVars.add(yVars.get(yVarName));
                }
            }

            if (!floorVars.isEmpty()) {
                LinearExpr sumFloors = LinearExpr.sum(floorVars.toArray(new BoolVar[0]));

                // デフォルトは2階まで
                int maxFloorCount = 2;

                // ポイント制約がある場合は上書き
                AdaptiveRoomOptimizer.PointConstraint constraint =
                        config.pointConstraints.get(staffName);
                if (constraint != null && constraint.constraintType.contains("階")) {
                    String type = constraint.constraintType;
                    if (type.contains("1階")) {
                        maxFloorCount = 1;
                    } else if (type.contains("3階")) {
                        maxFloorCount = 3;
                    }
                }

                // フロア制限を厳密に適用
                model.addLessOrEqual(sumFloors, maxFloorCount);
                LOGGER.info(String.format("  %s: 最大%d階まで", staffName, maxFloorCount));
            }
        }

        // 制約4: フロア担当リンク制約
        LOGGER.info("制約4: フロア担当リンク制約を追加中...");
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;

            for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
                int floorNum = floor.floorNumber;
                String yVarName = String.format("y_%s_%d", staffName, floorNum);

                if (!yVars.containsKey(yVarName)) continue;

                List<IntVar> roomVars = new ArrayList<>();
                for (String roomType : floor.roomCounts.keySet()) {
                    String varName = String.format("x_%s_%d_%s", staffName, floorNum, roomType);
                    if (xVars.containsKey(varName)) {
                        roomVars.add(xVars.get(varName));
                    }
                }

                String eVarName = String.format("e_%s_%d", staffName, floorNum);
                if (eVars.containsKey(eVarName)) {
                    roomVars.add(eVars.get(eVarName));
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

        // 目的関数: ツインのズレを最小化
        LOGGER.info("目的関数を設定中...");
        if (!penalties.isEmpty()) {
            model.minimize(LinearExpr.sum(penalties.toArray(new LinearArgument[0])));
        }

        // 求解
        LOGGER.info("CP-SATソルバーで求解中...");
        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(120.0);  // 時間を120秒に延長

        long startTime = System.currentTimeMillis();
        CpSolverStatus status = solver.solve(model);
        long endTime = System.currentTimeMillis();

        LOGGER.info(String.format("求解完了: ステータス=%s, 時間=%.2f秒",
                status, (endTime - startTime) / 1000.0));

        if (status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE) {
            LOGGER.info("✓ 解が見つかりました！");
            return buildSolution(solver, xVars, eVars, staffList, buildingData, config);
        } else {
            LOGGER.severe("✗ 解が見つかりませんでした");
            LOGGER.severe("原因: 全フロアを使い切りながらフロア制限(2階まで)を守る割り当てが存在しません");
            LOGGER.severe("対策: (1)フロア制限を緩和する (2)スタッフ数を増やす (3)部屋数を調整する");
            throw new RuntimeException("CP-SATで実行可能な解が見つかりませんでした。制約条件を確認してください。");
        }
    }

    private static boolean isMainTwin(String roomType) {
        return roomType.contains("ツイン") || roomType.contains("TWIN");
    }

    private static boolean isAnnexTwin(String roomType) {
        return roomType.contains("ツイン") || roomType.contains("TWIN");
    }

    private static List<AdaptiveRoomOptimizer.StaffAssignment> buildSolution(
            CpSolver solver,
            Map<String, IntVar> xVars,
            Map<String, IntVar> eVars,
            List<AdaptiveRoomOptimizer.ExtendedStaffInfo> staffList,
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        LOGGER.info("=== 解の構築 ===");
        List<AdaptiveRoomOptimizer.StaffAssignment> result = new ArrayList<>();

        int totalTwinGoal = 0;
        int totalTwinActual = 0;

        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;

            Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> mainAssignments = new TreeMap<>();
            Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> annexAssignments = new TreeMap<>();

            int totalMainSingle = 0;
            int totalMainTwin = 0;
            int totalAnnexSingle = 0;
            int totalAnnexTwin = 0;

            // 本館
            for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
                Map<String, Integer> rooms = new HashMap<>();
                int ecoCount = 0;

                for (String roomType : floor.roomCounts.keySet()) {
                    String varName = String.format("x_%s_%d_%s",
                            staffName, floor.floorNumber, roomType);
                    if (xVars.containsKey(varName)) {
                        long value = solver.value(xVars.get(varName));
                        if (value > 0) {
                            rooms.put(roomType, (int) value);
                            if (isMainTwin(roomType)) {
                                totalMainTwin += (int) value;
                            } else {
                                totalMainSingle += (int) value;
                            }
                        }
                    }
                }

                String eVarName = String.format("e_%s_%d", staffName, floor.floorNumber);
                if (eVars.containsKey(eVarName)) {
                    ecoCount = (int) solver.value(eVars.get(eVarName));
                }

                if (!rooms.isEmpty() || ecoCount > 0) {
                    mainAssignments.put(floor.floorNumber,
                            new AdaptiveRoomOptimizer.RoomAllocation(rooms, ecoCount));
                }
            }

            // 別館
            for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.annexFloors) {
                Map<String, Integer> rooms = new HashMap<>();
                int ecoCount = 0;

                for (String roomType : floor.roomCounts.keySet()) {
                    String varName = String.format("x_%s_%d_%s",
                            staffName, floor.floorNumber, roomType);
                    if (xVars.containsKey(varName)) {
                        long value = solver.value(xVars.get(varName));
                        if (value > 0) {
                            rooms.put(roomType, (int) value);
                            if (isAnnexTwin(roomType)) {
                                totalAnnexTwin += (int) value;
                            } else {
                                totalAnnexSingle += (int) value;
                            }
                        }
                    }
                }

                String eVarName = String.format("e_%s_%d", staffName, floor.floorNumber);
                if (eVars.containsKey(eVarName)) {
                    ecoCount = (int) solver.value(eVars.get(eVarName));
                }

                if (!rooms.isEmpty() || ecoCount > 0) {
                    annexAssignments.put(floor.floorNumber,
                            new AdaptiveRoomOptimizer.RoomAllocation(rooms, ecoCount));
                }
            }

            if (!mainAssignments.isEmpty() || !annexAssignments.isEmpty()) {
                result.add(new AdaptiveRoomOptimizer.StaffAssignment(
                        staffInfo.staff, mainAssignments, annexAssignments, staffInfo.bathCleaningType));

                // 目標値との比較をログ出力
                NormalRoomDistributionDialog.StaffDistribution dist =
                        config.roomDistribution.get(staffName);
                if (dist != null) {
                    totalTwinGoal += dist.mainTwinAssignedRooms + dist.annexTwinAssignedRooms;
                    totalTwinActual += totalMainTwin + totalAnnexTwin;

                    if (totalMainTwin != dist.mainTwinAssignedRooms || totalAnnexTwin != dist.annexTwinAssignedRooms) {
                        LOGGER.info(String.format("  %s: 本館ツイン目標%d→実際%d, 別館ツイン目標%d→実際%d",
                                staffName,
                                dist.mainTwinAssignedRooms, totalMainTwin,
                                dist.annexTwinAssignedRooms, totalAnnexTwin));
                    }
                }
            }
        }

        LOGGER.info(String.format("ツイン割り当て統計: 目標合計=%d室, 実際合計=%d室", totalTwinGoal, totalTwinActual));

        return result;
    }
}