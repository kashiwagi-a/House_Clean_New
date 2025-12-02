package org.example;

import com.google.ortools.Loader;
import com.google.ortools.sat.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Google OR-Tools CP-SATソルバーを使用した部屋割り当て最適化
 *
 * ★修正版: ツイン→シングル等の順序で分離計算
 *
 * 計算順序:
 * 1. ツインを最初に割り振る（2階またぎ制限内）
 * 2. その後にシングル等を割り振る（ツインと合わせて2階制限を守る）
 * 3. 解が存在しない場合はツインの割り振りを再計算
 * 4. 全パターンを試行しても解が見つからない場合はエラー
 */
public class RoomAssignmentCPSATOptimizer {

    private static final Logger LOGGER = Logger.getLogger(RoomAssignmentCPSATOptimizer.class.getName());

    /**
     * ツイン部屋の判定（本館）
     * roomTypeCode = "T" のみ（FileProcessorでマッピング済み）
     */
    private static boolean isMainTwin(String roomType) {
        return "T".equals(roomType);
    }

    /**
     * ツイン部屋の判定（別館）
     * roomTypeCode = "T" のみ（FileProcessorでマッピング済み）
     */
    private static boolean isAnnexTwin(String roomType) {
        return "T".equals(roomType);
    }

    /**
     * メイン最適化メソッド
     */
    public static List<AdaptiveRoomOptimizer.StaffAssignment> optimize(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        LOGGER.info("=== CP-SATソルバーによる最適化を開始（ツイン優先分離計算版） ===");

        // OR-Toolsロード
        try {
            Loader.loadNativeLibraries();
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException("OR-Toolsのロードに失敗しました: " + e.getMessage(), e);
        }

        // フロア情報の収集
        List<AdaptiveRoomOptimizer.FloorInfo> allFloors = new ArrayList<>();
        allFloors.addAll(buildingData.mainFloors);
        allFloors.addAll(buildingData.annexFloors);

        // ツインの全パターンを生成して試行
        List<Map<String, TwinAssignment>> twinPatterns = generateTwinPatterns(buildingData, config);

        LOGGER.info(String.format("ツインパターン数: %d", twinPatterns.size()));

        for (int patternIndex = 0; patternIndex < twinPatterns.size(); patternIndex++) {
            Map<String, TwinAssignment> twinPattern = twinPatterns.get(patternIndex);

            LOGGER.info(String.format("=== ツインパターン %d/%d を試行中 ===",
                    patternIndex + 1, twinPatterns.size()));

            try {
                // ステップ1: ツインを割り振り
                Map<String, FloorTwinAssignment> twinResult = assignTwins(buildingData, config, twinPattern);

                if (twinResult == null) {
                    LOGGER.info("ツイン割り振りに失敗、次のパターンを試行");
                    continue;
                }

                // ステップ2: シングル等を割り振り（ツインの結果を考慮）
                List<AdaptiveRoomOptimizer.StaffAssignment> result =
                        assignSinglesWithTwins(buildingData, config, twinResult);

                if (result != null) {
                    LOGGER.info("✓ 解が見つかりました！");
                    return result;
                }

                LOGGER.info("シングル等の割り振りに失敗、次のパターンを試行");

            } catch (Exception e) {
                LOGGER.warning("パターン " + (patternIndex + 1) + " でエラー: " + e.getMessage());
            }
        }

        // 全パターン失敗
        String errorMsg = "全てのツインパターンを試行しましたが、解が見つかりませんでした。\n" +
                "通常清掃部屋割り振り設定を見直してください。";
        LOGGER.severe(errorMsg);
        throw new RuntimeException(errorMsg);
    }

    /**
     * スタッフごとのツイン割り当て情報
     */
    private static class TwinAssignment {
        int mainTwinRooms;
        int annexTwinRooms;
        List<Integer> mainTwinFloors = new ArrayList<>();
        List<Integer> annexTwinFloors = new ArrayList<>();

        TwinAssignment(int mainTwin, int annexTwin) {
            this.mainTwinRooms = mainTwin;
            this.annexTwinRooms = annexTwin;
        }
    }

    /**
     * フロアごとのツイン割り当て結果
     */
    private static class FloorTwinAssignment {
        Map<Integer, Integer> mainFloorTwins = new HashMap<>();  // フロア -> ツイン数
        Map<Integer, Integer> annexFloorTwins = new HashMap<>();
        Set<Integer> usedFloors = new HashSet<>();
    }

    /**
     * ツインの全パターンを生成
     */
    private static List<Map<String, TwinAssignment>> generateTwinPatterns(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        List<Map<String, TwinAssignment>> patterns = new ArrayList<>();

        // 基本パターン（設定値どおり）
        Map<String, TwinAssignment> basePattern = new HashMap<>();
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : config.extendedStaffInfo) {
            String staffName = staffInfo.staff.name;
            NormalRoomDistributionDialog.StaffDistribution dist =
                    config.roomDistribution.get(staffName);

            if (dist != null) {
                basePattern.put(staffName, new TwinAssignment(
                        dist.mainTwinAssignedRooms,
                        dist.annexTwinAssignedRooms));
            } else {
                basePattern.put(staffName, new TwinAssignment(0, 0));
            }
        }
        patterns.add(basePattern);

        // 追加パターン: ツインを少しずつ調整したパターンを生成
        // （計算量を抑えるため、主要な調整パターンのみ）
        patterns.addAll(generateAdjustedTwinPatterns(basePattern, config));

        return patterns;
    }

    /**
     * ツイン調整パターンを生成（全組み合わせ版）
     * ツインは数が少ないため全パターンを計算しても問題ない
     */
    private static List<Map<String, TwinAssignment>> generateAdjustedTwinPatterns(
            Map<String, TwinAssignment> basePattern,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        List<Map<String, TwinAssignment>> adjusted = new ArrayList<>();

        List<String> staffNames = new ArrayList<>(basePattern.keySet());

        // パターン1: スタッフ間でツインを1つずつ移動
        for (int i = 0; i < staffNames.size(); i++) {
            for (int j = i + 1; j < staffNames.size(); j++) {
                String staff1 = staffNames.get(i);
                String staff2 = staffNames.get(j);

                TwinAssignment ta1 = basePattern.get(staff1);
                TwinAssignment ta2 = basePattern.get(staff2);

                // 本館ツインを staff1 -> staff2 へ移動
                if (ta1.mainTwinRooms > 0) {
                    Map<String, TwinAssignment> pattern = deepCopyPattern(basePattern);
                    pattern.get(staff1).mainTwinRooms--;
                    pattern.get(staff2).mainTwinRooms++;
                    adjusted.add(pattern);
                }

                // 本館ツインを staff2 -> staff1 へ移動
                if (ta2.mainTwinRooms > 0) {
                    Map<String, TwinAssignment> pattern = deepCopyPattern(basePattern);
                    pattern.get(staff2).mainTwinRooms--;
                    pattern.get(staff1).mainTwinRooms++;
                    adjusted.add(pattern);
                }

                // 別館ツインも同様
                if (ta1.annexTwinRooms > 0) {
                    Map<String, TwinAssignment> pattern = deepCopyPattern(basePattern);
                    pattern.get(staff1).annexTwinRooms--;
                    pattern.get(staff2).annexTwinRooms++;
                    adjusted.add(pattern);
                }

                if (ta2.annexTwinRooms > 0) {
                    Map<String, TwinAssignment> pattern = deepCopyPattern(basePattern);
                    pattern.get(staff2).annexTwinRooms--;
                    pattern.get(staff1).annexTwinRooms++;
                    adjusted.add(pattern);
                }
            }
        }

        // パターン2: 2つ移動するパターン
        for (int i = 0; i < staffNames.size(); i++) {
            for (int j = i + 1; j < staffNames.size(); j++) {
                String staff1 = staffNames.get(i);
                String staff2 = staffNames.get(j);

                TwinAssignment ta1 = basePattern.get(staff1);
                TwinAssignment ta2 = basePattern.get(staff2);

                if (ta1.mainTwinRooms >= 2) {
                    Map<String, TwinAssignment> pattern = deepCopyPattern(basePattern);
                    pattern.get(staff1).mainTwinRooms -= 2;
                    pattern.get(staff2).mainTwinRooms += 2;
                    adjusted.add(pattern);
                }

                if (ta2.mainTwinRooms >= 2) {
                    Map<String, TwinAssignment> pattern = deepCopyPattern(basePattern);
                    pattern.get(staff2).mainTwinRooms -= 2;
                    pattern.get(staff1).mainTwinRooms += 2;
                    adjusted.add(pattern);
                }

                if (ta1.annexTwinRooms >= 2) {
                    Map<String, TwinAssignment> pattern = deepCopyPattern(basePattern);
                    pattern.get(staff1).annexTwinRooms -= 2;
                    pattern.get(staff2).annexTwinRooms += 2;
                    adjusted.add(pattern);
                }

                if (ta2.annexTwinRooms >= 2) {
                    Map<String, TwinAssignment> pattern = deepCopyPattern(basePattern);
                    pattern.get(staff2).annexTwinRooms -= 2;
                    pattern.get(staff1).annexTwinRooms += 2;
                    adjusted.add(pattern);
                }
            }
        }

        // パターン3: 本館ツインと別館ツインを交換
        for (int i = 0; i < staffNames.size(); i++) {
            for (int j = i + 1; j < staffNames.size(); j++) {
                String staff1 = staffNames.get(i);
                String staff2 = staffNames.get(j);

                TwinAssignment ta1 = basePattern.get(staff1);
                TwinAssignment ta2 = basePattern.get(staff2);

                // staff1の本館ツインをstaff2へ、staff2の別館ツインをstaff1へ
                if (ta1.mainTwinRooms > 0 && ta2.annexTwinRooms > 0) {
                    Map<String, TwinAssignment> pattern = deepCopyPattern(basePattern);
                    pattern.get(staff1).mainTwinRooms--;
                    pattern.get(staff2).mainTwinRooms++;
                    pattern.get(staff2).annexTwinRooms--;
                    pattern.get(staff1).annexTwinRooms++;
                    adjusted.add(pattern);
                }

                // 逆方向
                if (ta2.mainTwinRooms > 0 && ta1.annexTwinRooms > 0) {
                    Map<String, TwinAssignment> pattern = deepCopyPattern(basePattern);
                    pattern.get(staff2).mainTwinRooms--;
                    pattern.get(staff1).mainTwinRooms++;
                    pattern.get(staff1).annexTwinRooms--;
                    pattern.get(staff2).annexTwinRooms++;
                    adjusted.add(pattern);
                }
            }
        }

        LOGGER.info(String.format("調整パターン生成完了: %d パターン", adjusted.size()));

        return adjusted;
    }

    private static Map<String, TwinAssignment> deepCopyPattern(Map<String, TwinAssignment> original) {
        Map<String, TwinAssignment> copy = new HashMap<>();
        for (Map.Entry<String, TwinAssignment> entry : original.entrySet()) {
            TwinAssignment orig = entry.getValue();
            copy.put(entry.getKey(), new TwinAssignment(orig.mainTwinRooms, orig.annexTwinRooms));
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

        // 変数: x[staff][floor] = そのフロアで担当するツイン数
        Map<String, Map<Integer, IntVar>> twinVars = new HashMap<>();
        // 変数: y[staff][floor] = そのフロアを担当するか (0/1)
        Map<String, Map<Integer, BoolVar>> floorVars = new HashMap<>();

        // 各フロアのツイン総数
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

        // 変数作成
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            TwinAssignment ta = twinPattern.get(staffName);

            if (ta == null || (ta.mainTwinRooms == 0 && ta.annexTwinRooms == 0)) {
                continue;
            }

            Map<Integer, IntVar> staffTwinVars = new HashMap<>();
            Map<Integer, BoolVar> staffFloorVars = new HashMap<>();

            // 建物制約を確認
            NormalRoomDistributionDialog.StaffDistribution dist =
                    config.roomDistribution.get(staffName);
            boolean canDoMain = dist == null || !"別館のみ".equals(dist.buildingAssignment);
            boolean canDoAnnex = dist == null || !"本館のみ".equals(dist.buildingAssignment);

            // 本館のツインフロア
            if (canDoMain && ta.mainTwinRooms > 0) {
                for (Integer floorNum : mainFloorTwins.keySet()) {
                    int maxTwins = mainFloorTwins.get(floorNum);
                    String varName = String.format("twin_%s_main_%d", staffName, floorNum);
                    IntVar var = model.newIntVar(0, maxTwins, varName);
                    staffTwinVars.put(floorNum, var);

                    String floorVarName = String.format("floor_%s_main_%d", staffName, floorNum);
                    BoolVar fVar = model.newBoolVar(floorVarName);
                    staffFloorVars.put(floorNum, fVar);

                    // リンク制約: twin > 0 <=> floor = 1
                    model.addGreaterThan(var, 0).onlyEnforceIf(fVar);
                    model.addEquality(var, 0).onlyEnforceIf(fVar.not());
                }
            }

            // 別館のツインフロア
            if (canDoAnnex && ta.annexTwinRooms > 0) {
                for (Integer floorNum : annexFloorTwins.keySet()) {
                    int maxTwins = annexFloorTwins.get(floorNum);
                    String varName = String.format("twin_%s_annex_%d", staffName, floorNum);
                    IntVar var = model.newIntVar(0, maxTwins, varName);
                    staffTwinVars.put(1000 + floorNum, var);  // 別館は1000+で区別

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

            // 本館ツイン合計
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

            // 別館ツイン合計
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

        // 制約2: 各フロアのツイン総数（全部使い切る）
        for (Integer floorNum : mainFloorTwins.keySet()) {
            List<IntVar> vars = new ArrayList<>();
            for (String staffName : twinVars.keySet()) {
                Map<Integer, IntVar> staffTwinVars = twinVars.get(staffName);
                if (staffTwinVars.containsKey(floorNum)) {
                    vars.add(staffTwinVars.get(floorNum));
                }
            }
            if (!vars.isEmpty()) {
                model.addEquality(
                        LinearExpr.sum(vars.toArray(new IntVar[0])),
                        mainFloorTwins.get(floorNum));
            }
        }

        for (Integer floorNum : annexFloorTwins.keySet()) {
            List<IntVar> vars = new ArrayList<>();
            for (String staffName : twinVars.keySet()) {
                Map<Integer, IntVar> staffTwinVars = twinVars.get(staffName);
                if (staffTwinVars.containsKey(1000 + floorNum)) {
                    vars.add(staffTwinVars.get(1000 + floorNum));
                }
            }
            if (!vars.isEmpty()) {
                model.addEquality(
                        LinearExpr.sum(vars.toArray(new IntVar[0])),
                        annexFloorTwins.get(floorNum));
            }
        }

        // 制約3: フロア制限（ツインだけで最大フロア数まで）
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

        // 求解
        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(30.0);

        CpSolverStatus status = solver.solve(model);

        if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) {
            LOGGER.warning("ツイン割り振りで解が見つかりませんでした");
            return null;
        }

        // 結果を構築
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
     * ステップ2: シングル等の割り振り（ツイン結果を考慮）
     */
    private static List<AdaptiveRoomOptimizer.StaffAssignment> assignSinglesWithTwins(
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config,
            Map<String, FloorTwinAssignment> twinResult) {

        LOGGER.info("--- ステップ2: シングル等の割り振り ---");

        CpModel model = new CpModel();

        List<AdaptiveRoomOptimizer.ExtendedStaffInfo> staffList = config.extendedStaffInfo;
        List<AdaptiveRoomOptimizer.FloorInfo> allFloors = new ArrayList<>();
        allFloors.addAll(buildingData.mainFloors);
        allFloors.addAll(buildingData.annexFloors);

        // 変数
        Map<String, IntVar> xVars = new HashMap<>();
        Map<String, IntVar> eVars = new HashMap<>();
        Map<String, BoolVar> yVars = new HashMap<>();

        // 変数作成
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            NormalRoomDistributionDialog.StaffDistribution dist =
                    config.roomDistribution.get(staffName);

            for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
                int floorNum = floor.floorNumber;
                boolean isMain = floor.isMainBuilding;

                if (dist != null) {
                    if ("本館のみ".equals(dist.buildingAssignment) && !isMain) continue;
                    if ("別館のみ".equals(dist.buildingAssignment) && isMain) continue;
                }

                // シングル等の変数（ツイン以外）
                for (Map.Entry<String, Integer> entry : floor.roomCounts.entrySet()) {
                    String roomType = entry.getKey();

                    // ツインはスキップ
                    if ((isMain && isMainTwin(roomType)) || (!isMain && isAnnexTwin(roomType))) {
                        continue;
                    }

                    int maxRooms = entry.getValue();
                    String varName = String.format("x_%s_%d_%s", staffName, floorNum, roomType);
                    IntVar var = model.newIntVar(0, maxRooms, varName);
                    xVars.put(varName, var);
                }

                // エコ変数
                if (floor.ecoRooms > 0) {
                    String eVarName = String.format("e_%s_%d", staffName, floorNum);
                    IntVar eVar = model.newIntVar(0, floor.ecoRooms, eVarName);
                    eVars.put(eVarName, eVar);
                }

                // フロア変数
                String yVarName = String.format("y_%s_%d", staffName, floorNum);
                BoolVar yVar = model.newBoolVar(yVarName);
                yVars.put(yVarName, yVar);
            }
        }

        // 制約1: フロアのシングル等を全部使い切る
        for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
            boolean isMain = floor.isMainBuilding;

            for (Map.Entry<String, Integer> entry : floor.roomCounts.entrySet()) {
                String roomType = entry.getKey();

                if ((isMain && isMainTwin(roomType)) || (!isMain && isAnnexTwin(roomType))) {
                    continue;
                }

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
                    model.addEquality(LinearExpr.sum(vars.toArray(new IntVar[0])), totalRooms);
                }
            }

            // エコも全部使い切る
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
                    model.addEquality(LinearExpr.sum(ecoVars.toArray(new IntVar[0])), floor.ecoRooms);
                }
            }
        }

        // 制約2: スタッフのシングル等総数
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            NormalRoomDistributionDialog.StaffDistribution dist =
                    config.roomDistribution.get(staffName);

            if (dist == null) continue;

            // 本館シングル等
            if (dist.mainSingleAssignedRooms > 0) {
                List<IntVar> mainSingleVars = new ArrayList<>();

                for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
                    for (String roomType : floor.roomCounts.keySet()) {
                        if (isMainTwin(roomType)) continue;

                        String varName = String.format("x_%s_%d_%s",
                                staffName, floor.floorNumber, roomType);
                        if (xVars.containsKey(varName)) {
                            mainSingleVars.add(xVars.get(varName));
                        }
                    }
                }

                if (!mainSingleVars.isEmpty()) {
                    model.addEquality(
                            LinearExpr.sum(mainSingleVars.toArray(new IntVar[0])),
                            dist.mainSingleAssignedRooms);
                }
            }

            // 別館シングル等
            if (dist.annexSingleAssignedRooms > 0) {
                List<IntVar> annexSingleVars = new ArrayList<>();

                for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.annexFloors) {
                    for (String roomType : floor.roomCounts.keySet()) {
                        if (isAnnexTwin(roomType)) continue;

                        String varName = String.format("x_%s_%d_%s",
                                staffName, floor.floorNumber, roomType);
                        if (xVars.containsKey(varName)) {
                            annexSingleVars.add(xVars.get(varName));
                        }
                    }
                }

                if (!annexSingleVars.isEmpty()) {
                    model.addEquality(
                            LinearExpr.sum(annexSingleVars.toArray(new IntVar[0])),
                            dist.annexSingleAssignedRooms);
                }
            }
        }

        // 制約3: フロア制限（ツインで使用済みのフロアを考慮）
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            FloorTwinAssignment fta = twinResult.get(staffName);

            int maxFloors = getMaxFloors(staffName, config);
            int usedFloorsByTwin = (fta != null) ? fta.usedFloors.size() : 0;
            int remainingFloors = maxFloors - usedFloorsByTwin;

            List<BoolVar> newFloorVars = new ArrayList<>();

            for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
                String yVarName = String.format("y_%s_%d", staffName, floor.floorNumber);
                if (!yVars.containsKey(yVarName)) continue;

                int floorKey = floor.isMainBuilding ? floor.floorNumber : (1000 + floor.floorNumber);

                // ツインで使用済みのフロアは新規フロアとしてカウントしない
                if (fta != null && fta.usedFloors.contains(floorKey)) {
                    // このフロアは使用可能（すでに使用中）
                    continue;
                }

                newFloorVars.add(yVars.get(yVarName));
            }

            if (!newFloorVars.isEmpty() && remainingFloors >= 0) {
                model.addLessOrEqual(
                        LinearExpr.sum(newFloorVars.toArray(new BoolVar[0])),
                        remainingFloors);
            }
        }

        // 制約4: フロア担当リンク制約
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;

            for (AdaptiveRoomOptimizer.FloorInfo floor : allFloors) {
                int floorNum = floor.floorNumber;
                String yVarName = String.format("y_%s_%d", staffName, floorNum);

                if (!yVars.containsKey(yVarName)) continue;

                List<IntVar> roomVars = new ArrayList<>();

                for (String roomType : floor.roomCounts.keySet()) {
                    if ((floor.isMainBuilding && isMainTwin(roomType)) ||
                            (!floor.isMainBuilding && isAnnexTwin(roomType))) {
                        continue;
                    }

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
        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(60.0);

        CpSolverStatus status = solver.solve(model);

        if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) {
            LOGGER.warning("シングル等の割り振りで解が見つかりませんでした");
            return null;
        }

        // 結果を構築（ツイン結果と統合）
        return buildFinalSolution(solver, xVars, eVars, twinResult, staffList, buildingData, config);
    }

    /**
     * 最終結果を構築
     */
    private static List<AdaptiveRoomOptimizer.StaffAssignment> buildFinalSolution(
            CpSolver solver,
            Map<String, IntVar> xVars,
            Map<String, IntVar> eVars,
            Map<String, FloorTwinAssignment> twinResult,
            List<AdaptiveRoomOptimizer.ExtendedStaffInfo> staffList,
            AdaptiveRoomOptimizer.BuildingData buildingData,
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        LOGGER.info("=== 最終結果の構築 ===");
        List<AdaptiveRoomOptimizer.StaffAssignment> result = new ArrayList<>();

        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : staffList) {
            String staffName = staffInfo.staff.name;
            FloorTwinAssignment fta = twinResult.get(staffName);

            Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> mainAssignments = new TreeMap<>();
            Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> annexAssignments = new TreeMap<>();

            // 本館
            for (AdaptiveRoomOptimizer.FloorInfo floor : buildingData.mainFloors) {
                Map<String, Integer> rooms = new HashMap<>();
                int ecoCount = 0;

                // ツイン
                if (fta != null && fta.mainFloorTwins.containsKey(floor.floorNumber)) {
                    rooms.put("T", fta.mainFloorTwins.get(floor.floorNumber));
                }

                // シングル等
                for (String roomType : floor.roomCounts.keySet()) {
                    if (isMainTwin(roomType)) continue;

                    String varName = String.format("x_%s_%d_%s", staffName, floor.floorNumber, roomType);
                    if (xVars.containsKey(varName)) {
                        int value = (int) solver.value(xVars.get(varName));
                        if (value > 0) {
                            rooms.put(roomType, value);
                        }
                    }
                }

                // エコ
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

                // ツイン
                if (fta != null && fta.annexFloorTwins.containsKey(floor.floorNumber)) {
                    rooms.put("T", fta.annexFloorTwins.get(floor.floorNumber));
                }

                // シングル等
                for (String roomType : floor.roomCounts.keySet()) {
                    if (isAnnexTwin(roomType)) continue;

                    String varName = String.format("x_%s_%d_%s", staffName, floor.floorNumber, roomType);
                    if (xVars.containsKey(varName)) {
                        int value = (int) solver.value(xVars.get(varName));
                        if (value > 0) {
                            rooms.put(roomType, value);
                        }
                    }
                }

                // エコ
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
                AdaptiveRoomOptimizer.StaffAssignment assignment =
                        new AdaptiveRoomOptimizer.StaffAssignment(
                                staffInfo.staff, mainAssignments, annexAssignments,
                                staffInfo.bathCleaningType);
                result.add(assignment);

                LOGGER.info(String.format("  %s: 本館%d室, 別館%d室, フロア数%d",
                        staffName,
                        mainAssignments.values().stream().mapToInt(a -> a.getTotalRooms()).sum(),
                        annexAssignments.values().stream().mapToInt(a -> a.getTotalRooms()).sum(),
                        assignment.floors.size()));
            }
        }

        return result;
    }

    /**
     * スタッフの最大フロア数を取得
     * ★修正: 本館＋別館の両方に部屋があるスタッフは4フロアまで許容
     */
    private static int getMaxFloors(String staffName, AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {
        // デフォルトは2階まで
        int maxFloors = 2;

        // ポイント制約から取得
        AdaptiveRoomOptimizer.PointConstraint constraint = config.pointConstraints.get(staffName);
        if (constraint != null) {
            String type = constraint.constraintType;
            if (type.contains("業者") || type.contains("リライアンス")) {
                maxFloors = 99;  // 業者は制限なし
            }
        }

        // 大浴場清掃スタッフは1階のみ
        for (AdaptiveRoomOptimizer.ExtendedStaffInfo staffInfo : config.extendedStaffInfo) {
            if (staffInfo.staff.name.equals(staffName) &&
                    staffInfo.bathCleaningType != AdaptiveRoomOptimizer.BathCleaningType.NONE) {
                maxFloors = 1;
                return maxFloors;  // 大浴場は最優先で1フロア
            }
        }

        // ★追加: 本館と別館の両方に部屋があるスタッフは4フロアまで許容
        NormalRoomDistributionDialog.StaffDistribution dist = config.roomDistribution.get(staffName);
        if (dist != null) {
            int mainRooms = dist.mainSingleAssignedRooms + dist.mainTwinAssignedRooms;
            int annexRooms = dist.annexSingleAssignedRooms + dist.annexTwinAssignedRooms;

            if (mainRooms > 0 && annexRooms > 0) {
                // 本館と別館の両方に部屋がある場合は4フロアまで（本館2+別館2）
                maxFloors = 4;
                LOGGER.info(String.format("  %s: 本館＋別館担当のため最大4フロアに緩和", staffName));
            }
        }

        return maxFloors;
    }

    /**
     * 事前検証メソッド（NormalRoomDistributionDialogから呼び出し）
     */
    public static ValidationResult validateDistribution(
            int totalMainSingleRooms, int totalMainTwinRooms,
            int totalAnnexSingleRooms, int totalAnnexTwinRooms,
            Map<String, NormalRoomDistributionDialog.StaffDistribution> distribution,
            int staffCount) {

        ValidationResult result = new ValidationResult();

        // 割り当て済み部屋数の集計
        int assignedMainSingle = 0;
        int assignedMainTwin = 0;
        int assignedAnnexSingle = 0;
        int assignedAnnexTwin = 0;

        for (NormalRoomDistributionDialog.StaffDistribution dist : distribution.values()) {
            assignedMainSingle += dist.mainSingleAssignedRooms;
            assignedMainTwin += dist.mainTwinAssignedRooms;
            assignedAnnexSingle += dist.annexSingleAssignedRooms;
            assignedAnnexTwin += dist.annexTwinAssignedRooms;
        }

        // 部屋数チェック
        if (assignedMainSingle != totalMainSingleRooms) {
            result.addError(String.format("本館シングル等: 割当%d室 ≠ 実際%d室",
                    assignedMainSingle, totalMainSingleRooms));
        }
        if (assignedMainTwin != totalMainTwinRooms) {
            result.addError(String.format("本館ツイン: 割当%d室 ≠ 実際%d室",
                    assignedMainTwin, totalMainTwinRooms));
        }
        if (assignedAnnexSingle != totalAnnexSingleRooms) {
            result.addError(String.format("別館シングル等: 割当%d室 ≠ 実際%d室",
                    assignedAnnexSingle, totalAnnexSingleRooms));
        }
        if (assignedAnnexTwin != totalAnnexTwinRooms) {
            result.addError(String.format("別館ツイン: 割当%d室 ≠ 実際%d室",
                    assignedAnnexTwin, totalAnnexTwinRooms));
        }

        return result;
    }

    /**
     * 検証結果クラス
     */
    public static class ValidationResult {
        private List<String> errors = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();

        public void addError(String error) {
            errors.add(error);
        }

        public void addWarning(String warning) {
            warnings.add(warning);
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }

        public List<String> getErrors() {
            return errors;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        public String getErrorMessage() {
            return String.join("\n", errors);
        }
    }
}