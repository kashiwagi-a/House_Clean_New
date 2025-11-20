package org.example;

import com.google.ortools.Loader;
import com.google.ortools.sat.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Google OR-Tools CP-SATソルバーを使用した部屋割り当て最適化（完全修正版）
 *
 * ★修正内容:
 * 1. 各フロアは上限のみ（≤ 制約）
 * 2. 各スタッフの部屋数はNormalRoomDistributionDialogの設定通り（厳密）
 * 3. フロア制限（2階まで）を厳守
 */
public class RoomAssignmentCPSATOptimizer {

    private static final Logger LOGGER = Logger.getLogger(RoomAssignmentCPSATOptimizer.class.getName());

    public static List<AdaptiveRoomOptimizer.StaffAssignment> optimize(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        LOGGER.info("=== CP-SATソルバーによる最適化を開始（修正版） ===");

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

                // 通常清掃部屋の変数
                for (Map.Entry<String, Integer> entry : floor.roomCounts.entrySet()) {
                    String roomType = entry.getKey();
                    int maxRooms = entry.getValue();

                    String varName = String.format("x_%s_%d_%s", staffName, floorNum, roomType);
                    IntVar var = model.newIntVar(0, maxRooms, varName);
                    xVars.put(varName, var);
                }

                // エコ清掃部屋の変数（別管理）
                if (floor.ecoRooms > 0) {
                    String eVarName = String.format("e_%s_%d", staffName, floorNum);
                    IntVar eVar = model.newIntVar(0, floor.ecoRooms, eVarName);
                    eVars.put(eVarName, eVar);
                }

                // フロア担当フラグ
                String yVarName = String.format("y_%s_%d", staffName, floorNum);
                BoolVar yVar = model.newBoolVar(yVarName);
                yVars.put(yVarName, yVar);
            }
        }

        LOGGER.info(String.format("変数作成完了: x=%d, e=%d, y=%d",
                xVars.size(), eVars.size(), yVars.size()));

        // ★修正1: 各フロアの部屋数制約（上限のみ ≤）
        LOGGER.info("制約1: フロア部屋数制約を追加中（上限のみ）...");
        for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
            for (Map.Entry<String, Integer> entry : floor.roomCounts.entrySet()) {
                String roomType = entry.getKey();
                int maxRooms = entry.getValue();

                List<IntVar> vars = new ArrayList<>();
                for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
                    String varName = String.format("x_%s_%d_%s",
                            staffInfo.staff.name, floor.floorNumber, roomType);
                    if (xVars.containsKey(varName)) {
                        vars.add(xVars.get(varName));
                    }
                }
                if (!vars.isEmpty()) {
                    // ★修正: ≤ 制約（余ってもOK）
                    model.addLessOrEqual(LinearExpr.sum(vars.toArray(new IntVar[0])), maxRooms);
                }
            }

            // エコ清掃も同様
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
                    model.addLessOrEqual(LinearExpr.sum(ecoVars.toArray(new IntVar[0])), floor.ecoRooms);
                }
            }
        }

        // ★修正2: スタッフの部屋数制約（NormalRoomDistributionDialogの設定を厳守）
        LOGGER.info("制約2: スタッフ部屋数制約を追加中（厳密）...");

        int totalNormalRooms = 0;

        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            NormalRoomDistributionDialog.StaffDistribution dist =
                    config.roomDistribution.get(staffName);

            if (dist == null) continue;

            // 本館の総部屋数（厳密制約）
            int mainTotalTarget = dist.mainSingleAssignedRooms + dist.mainTwinAssignedRooms;
            if (mainTotalTarget > 0) {
                List<IntVar> allMainVars = new ArrayList<>();

                for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
                    for (String roomType : floor.roomCounts.keySet()) {
                        String varName = String.format("x_%s_%d_%s",
                                staffName, floor.floorNumber, roomType);
                        if (xVars.containsKey(varName)) {
                            allMainVars.add(xVars.get(varName));
                        }
                    }
                }

                if (!allMainVars.isEmpty()) {
                    // ★本館総部屋数を厳密に守る
                    model.addEquality(
                            LinearExpr.sum(allMainVars.toArray(new IntVar[0])),
                            mainTotalTarget
                    );
                    totalNormalRooms += mainTotalTarget;
                }
            }

            // 別館の総部屋数（厳密制約）
            int annexTotalTarget = dist.annexSingleAssignedRooms + dist.annexTwinAssignedRooms;
            if (annexTotalTarget > 0) {
                List<IntVar> allAnnexVars = new ArrayList<>();

                for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.annexFloors) {
                    for (String roomType : floor.roomCounts.keySet()) {
                        String varName = String.format("x_%s_%d_%s",
                                staffName, floor.floorNumber, roomType);
                        if (xVars.containsKey(varName)) {
                            allAnnexVars.add(xVars.get(varName));
                        }
                    }
                }

                if (!allAnnexVars.isEmpty()) {
                    // ★別館総部屋数を厳密に守る
                    model.addEquality(
                            LinearExpr.sum(allAnnexVars.toArray(new IntVar[0])),
                            annexTotalTarget
                    );
                    totalNormalRooms += annexTotalTarget;
                }
            }
        }

        LOGGER.info(String.format("NormalRoomDistributionDialogの設定合計: %d室", totalNormalRooms));

        // 制約3: フロア制限（2階まで）
        LOGGER.info("制約3: フロア制限制約を追加中（2階まで）...");
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
                int maxFloorCount = 2; // デフォルト2階まで

                model.addLessOrEqual(sumFloors, maxFloorCount);
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

        // 求解
        LOGGER.info("CP-SATソルバーで求解中...");
        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(180.0); // 3分に延長

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
            LOGGER.severe("ステータス: " + status);
            throw new RuntimeException("CP-SATで実行可能な解が見つかりませんでした。");
        }
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

        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;

            Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> mainAssignments = new TreeMap<>();
            Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> annexAssignments = new TreeMap<>();

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

                // 検証ログ
                NormalRoomDistributionDialog.StaffDistribution dist =
                        config.roomDistribution.get(staffName);
                if (dist != null) {
                    int actualMain = mainAssignments.values().stream()
                            .mapToInt(a -> a.getTotalRooms()).sum();
                    int actualAnnex = annexAssignments.values().stream()
                            .mapToInt(a -> a.getTotalRooms()).sum();

                    LOGGER.info(String.format("  %s: 本館目標%d→実際%d, 別館目標%d→実際%d",
                            staffName,
                            dist.mainSingleAssignedRooms + dist.mainTwinAssignedRooms, actualMain,
                            dist.annexSingleAssignedRooms + dist.annexTwinAssignedRooms, actualAnnex));
                }
            }
        }

        return result;
    }
}