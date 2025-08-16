package org.example;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * ホテル客室清掃割り当てアプリケーション
 * メインクラス（ファイル選択ダイアログ付き拡張版）
 */
public class RoomAssignmentApplication {

    private static final Logger LOGGER = Logger.getLogger(RoomAssignmentApplication.class.getName());

    // 調整パラメータ
    private static final double MAX_ALLOWED_DIFFERENCE = 3.0;  // 許容される最大ポイント差
    private static final int MAX_FLOORS_PER_STAFF = 2;        // スタッフ1人あたりの最大階数
    private static final boolean ENABLE_AUTO_ADJUSTMENT = true; // 自動調整の有効化

    static {
        setupLogger();
    }

    private static void setupLogger() {
        Logger rootLogger = Logger.getLogger("");
        rootLogger.setLevel(Level.INFO);

        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setLevel(Level.INFO);
        consoleHandler.setFormatter(new SimpleFormatter());

        Arrays.stream(rootLogger.getHandlers()).forEach(rootLogger::removeHandler);
        rootLogger.addHandler(consoleHandler);
    }

    public static void main(String[] args) {
        try {
            System.out.println("=== ホテル客室清掃割り当てシステム ===");
            System.out.println("Version 3.1 - 適応型最適化エンジン + ファイル選択機能");
            System.out.println("=========================================\n");

            // Swingのルック&フィールを設定
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                // ルック&フィールの設定に失敗しても続行
            }

            // ファイル選択ダイアログで設定を取得
            ApplicationConfig config = selectFilesWithDialog();

            if (config == null) {
                System.out.println("ファイル選択がキャンセルされました。");
                System.exit(0);
            }

            // ファイルの検証
            validateFiles(config);

            // データの処理
            ProcessingResult result = processData(config);

            // 結果の後処理と調整
            if (ENABLE_AUTO_ADJUSTMENT && result.optimizationResult != null) {
                result.optimizationResult = performPostOptimizationAdjustments(result);
            }

            // 結果の検証
            validateResults(result);

            // 結果の出力
            outputResults(result);

            System.out.println("\n=== 処理完了 ===");

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "アプリケーション実行中にエラーが発生しました", e);
            System.err.println("エラー: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * ファイル選択ダイアログで設定を取得
     */
    private static ApplicationConfig selectFilesWithDialog() {
        ApplicationConfig config = new ApplicationConfig();

        // JFileChooserのデフォルトディレクトリを設定
        File currentDir = new File(System.getProperty("user.dir"));

        System.out.println("ファイル選択ダイアログが開きます...\n");

        // 1. 部屋データファイル（CSV）の選択
        JFileChooser roomFileChooser = new JFileChooser(currentDir);
        roomFileChooser.setDialogTitle("部屋データファイル（CSV）を選択してください");
        roomFileChooser.setFileFilter(new FileNameExtensionFilter("CSVファイル", "csv", "CSV"));

        int result = roomFileChooser.showOpenDialog(null);
        if (result != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        config.roomDataFile = roomFileChooser.getSelectedFile();
        System.out.println("部屋データ選択: " + config.roomDataFile.getName());

        // 2. スタッフシフトファイル（Excel）の選択
        JFileChooser staffFileChooser = new JFileChooser(currentDir);
        staffFileChooser.setDialogTitle("スタッフシフトファイル（Excel）を選択してください");
        staffFileChooser.setFileFilter(new FileNameExtensionFilter("Excelファイル", "xlsx", "xls"));

        result = staffFileChooser.showOpenDialog(null);
        if (result != JFileChooser.APPROVE_OPTION) {
            // スタッフファイルが選択されない場合はデフォルトを使用
            System.out.println("スタッフファイルが選択されませんでした。デフォルトスタッフを使用します。");
            config.staffDataFile = new File("dummy_staff.xlsx");
        } else {
            config.staffDataFile = staffFileChooser.getSelectedFile();
            System.out.println("スタッフデータ選択: " + config.staffDataFile.getName());
        }

        // 3. エコ清掃データファイル（Excel）の選択
        JFileChooser ecoFileChooser = new JFileChooser(currentDir);
        ecoFileChooser.setDialogTitle("エコ清掃データファイル（Excel）を選択してください（オプション）");
        ecoFileChooser.setFileFilter(new FileNameExtensionFilter("Excelファイル", "xlsx", "xls"));

        result = ecoFileChooser.showOpenDialog(null);
        if (result != JFileChooser.APPROVE_OPTION) {
            System.out.println("エコ清掃ファイルが選択されませんでした。エコ清掃なしで処理します。");
            config.ecoDataFile = new File("dummy_eco.xlsx");
        } else {
            config.ecoDataFile = ecoFileChooser.getSelectedFile();
            System.out.println("エコ清掃データ選択: " + config.ecoDataFile.getName());
        }

        // 4. 日付の選択（ダイアログ）
        config.targetDate = selectDateWithDialog();
        if (config.targetDate == null) {
            config.targetDate = LocalDate.now();
        }
        System.out.println("対象日付: " + config.targetDate);

        // 5. 大浴場清掃タイプの選択（ダイアログ）
        config.bathCleaningType = selectBathCleaningType();
        System.out.println("大浴場清掃: " + config.bathCleaningType.displayName);

        // エコデータファイルをシステムプロパティに設定
        System.setProperty("ecoDataFile", config.ecoDataFile.getAbsolutePath());

        System.out.println();
        return config;
    }

    /**
     * 日付選択ダイアログ
     */
    private static LocalDate selectDateWithDialog() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        JLabel label = new JLabel("対象日付を選択してください：");
        panel.add(label);
        panel.add(Box.createVerticalStrut(10));

        // 年月日の入力フィールド
        JPanel datePanel = new JPanel();
        LocalDate today = LocalDate.now();

        JTextField yearField = new JTextField(String.valueOf(today.getYear()), 4);
        JTextField monthField = new JTextField(String.valueOf(today.getMonthValue()), 2);
        JTextField dayField = new JTextField(String.valueOf(today.getDayOfMonth()), 2);

        datePanel.add(yearField);
        datePanel.add(new JLabel("年"));
        datePanel.add(monthField);
        datePanel.add(new JLabel("月"));
        datePanel.add(dayField);
        datePanel.add(new JLabel("日"));

        panel.add(datePanel);

        int result = JOptionPane.showConfirmDialog(null, panel, "日付選択",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            try {
                int year = Integer.parseInt(yearField.getText());
                int month = Integer.parseInt(monthField.getText());
                int day = Integer.parseInt(dayField.getText());
                return LocalDate.of(year, month, day);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, "日付の形式が正しくありません。今日の日付を使用します。");
                return LocalDate.now();
            }
        }

        return LocalDate.now();
    }

    /**
     * 大浴場清掃タイプ選択ダイアログ
     */
    private static AdaptiveRoomOptimizer.BathCleaningType selectBathCleaningType() {
        String[] options = {
                AdaptiveRoomOptimizer.BathCleaningType.NONE.displayName,
                AdaptiveRoomOptimizer.BathCleaningType.NORMAL.displayName,
                AdaptiveRoomOptimizer.BathCleaningType.WITH_DRAINING.displayName
        };

        String selected = (String) JOptionPane.showInputDialog(
                null,
                "大浴場清掃のタイプを選択してください：",
                "大浴場清掃設定",
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[1]  // デフォルトは「通常」
        );

        if (selected == null || selected.equals(options[0])) {
            return AdaptiveRoomOptimizer.BathCleaningType.NONE;
        } else if (selected.equals(options[2])) {
            return AdaptiveRoomOptimizer.BathCleaningType.WITH_DRAINING;
        } else {
            return AdaptiveRoomOptimizer.BathCleaningType.NORMAL;
        }
    }

    /**
     * 最適化後の調整処理
     */
    private static AdaptiveRoomOptimizer.OptimizationResult performPostOptimizationAdjustments(
            ProcessingResult result) {

        System.out.println("\n=== 最適化結果の調整処理 ===");

        List<AdaptiveRoomOptimizer.StaffAssignment> adjustedAssignments =
                new ArrayList<>(result.optimizationResult.assignments);

        // 1. ポイント差の調整
        adjustedAssignments = adjustPointDifferences(adjustedAssignments, result);

        // 2. 階跨ぎの最適化
        adjustedAssignments = optimizeFloorAssignments(adjustedAssignments, result);

        // 3. 特殊条件の確認と調整
        adjustedAssignments = handleSpecialConditions(adjustedAssignments, result);

        // 4. 最終バランス調整
        adjustedAssignments = finalBalanceAdjustment(adjustedAssignments, result);

        // 新しい最適化結果を作成
        return new AdaptiveRoomOptimizer.OptimizationResult(
                adjustedAssignments,
                result.optimizationResult.config,
                result.optimizationResult.targetDate
        );
    }

    /**
     * ポイント差の調整
     */
    private static List<AdaptiveRoomOptimizer.StaffAssignment> adjustPointDifferences(
            List<AdaptiveRoomOptimizer.StaffAssignment> assignments,
            ProcessingResult result) {

        System.out.println("  1. ポイント差の調整を実行中...");

        // ポイントでソート
        assignments.sort(Comparator.comparingDouble(a -> a.adjustedScore));

        double minScore = assignments.get(0).adjustedScore;
        double maxScore = assignments.get(assignments.size() - 1).adjustedScore;
        double difference = maxScore - minScore;

        if (difference > MAX_ALLOWED_DIFFERENCE) {
            System.out.printf("    警告: ポイント差が大きすぎます (%.2f点)\n", difference);

            // 部屋の再配分を試みる
            AdaptiveRoomOptimizer.StaffAssignment minStaff = assignments.get(0);
            AdaptiveRoomOptimizer.StaffAssignment maxStaff = assignments.get(assignments.size() - 1);

            // 高負荷スタッフから低負荷スタッフへ部屋を移動する処理
            // (実装は複雑なため、ここでは警告のみ)
            System.out.printf("    提案: %sから%sへ部屋を移動することを検討\n",
                    maxStaff.staff.name, minStaff.staff.name);
        } else {
            System.out.printf("    ポイント差は許容範囲内です (%.2f点)\n", difference);
        }

        return assignments;
    }

    /**
     * 階跨ぎの最適化
     */
    private static List<AdaptiveRoomOptimizer.StaffAssignment> optimizeFloorAssignments(
            List<AdaptiveRoomOptimizer.StaffAssignment> assignments,
            ProcessingResult result) {

        System.out.println("  2. 階跨ぎの最適化を実行中...");

        int multiFloorCount = 0;
        int excessiveFloorCount = 0;

        for (AdaptiveRoomOptimizer.StaffAssignment assignment : assignments) {
            if (assignment.floors.size() > 1) {
                multiFloorCount++;
                if (assignment.floors.size() > MAX_FLOORS_PER_STAFF) {
                    excessiveFloorCount++;
                    System.out.printf("    警告: %sが%d階を担当しています\n",
                            assignment.staff.name, assignment.floors.size());
                }
            }
        }

        System.out.printf("    複数階担当: %d名, 過剰担当: %d名\n",
                multiFloorCount, excessiveFloorCount);

        // 階数が多すぎる場合の調整ロジック
        if (excessiveFloorCount > 0) {
            // 実際の再配分ロジックは複雑なため、ここでは警告のみ
            System.out.println("    提案: 階数制限を超えているスタッフの負荷を再配分");
        }

        return assignments;
    }

    /**
     * 特殊条件の確認と調整
     */
    private static List<AdaptiveRoomOptimizer.StaffAssignment> handleSpecialConditions(
            List<AdaptiveRoomOptimizer.StaffAssignment> assignments,
            ProcessingResult result) {

        System.out.println("  3. 特殊条件の確認中...");

        // ツイン部屋の配分確認
        Map<String, Integer> twinDistribution = new HashMap<>();
        for (AdaptiveRoomOptimizer.StaffAssignment assignment : assignments) {
            int twinCount = 0;
            for (AdaptiveRoomOptimizer.RoomAllocation allocation : assignment.roomsByFloor.values()) {
                twinCount += allocation.roomCounts.getOrDefault("T", 0);
            }
            twinDistribution.put(assignment.staff.name, twinCount);
        }

        int minTwins = twinDistribution.values().stream().min(Integer::compare).orElse(0);
        int maxTwins = twinDistribution.values().stream().max(Integer::compare).orElse(0);

        if (maxTwins - minTwins > 1) {
            System.out.printf("    ツイン部屋の偏り検出: 最小%d室、最大%d室\n", minTwins, maxTwins);

            // ツイン部屋が多いスタッフと少ないスタッフを特定
            String maxTwinStaff = twinDistribution.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("");
            String minTwinStaff = twinDistribution.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("");

            System.out.printf("    提案: %sから%sへツイン部屋を移動\n", maxTwinStaff, minTwinStaff);
        }

        // 大浴場清掃担当者の確認
        int bathCleaningStaff = 0;
        for (AdaptiveRoomOptimizer.StaffAssignment assignment : assignments) {
            AdaptiveRoomOptimizer.BathCleaningType bathType =
                    result.optimizationResult.config.bathCleaningAssignments.get(assignment.staff.id);
            if (bathType != null && bathType != AdaptiveRoomOptimizer.BathCleaningType.NONE) {
                bathCleaningStaff++;
                System.out.printf("    大浴場担当: %s (%s, -%d室)\n",
                        assignment.staff.name, bathType.displayName, bathType.reduction);
            }
        }

        if (bathCleaningStaff > 0) {
            System.out.printf("    大浴場清掃担当者数: %d名\n", bathCleaningStaff);
        }

        return assignments;
    }

    /**
     * 最終バランス調整
     */
    private static List<AdaptiveRoomOptimizer.StaffAssignment> finalBalanceAdjustment(
            List<AdaptiveRoomOptimizer.StaffAssignment> assignments,
            ProcessingResult result) {

        System.out.println("  4. 最終バランス調整...");

        // 統計情報の計算
        double avgRooms = assignments.stream()
                .mapToInt(a -> a.totalRooms)
                .average()
                .orElse(0);

        double avgPoints = assignments.stream()
                .mapToDouble(a -> a.adjustedScore)
                .average()
                .orElse(0);

        // 標準偏差の計算
        double variance = assignments.stream()
                .mapToDouble(a -> Math.pow(a.adjustedScore - avgPoints, 2))
                .average()
                .orElse(0);
        double stdDev = Math.sqrt(variance);

        System.out.printf("    平均部屋数: %.1f室\n", avgRooms);
        System.out.printf("    平均ポイント: %.1f点\n", avgPoints);
        System.out.printf("    標準偏差: %.2f\n", stdDev);

        // 外れ値の検出
        for (AdaptiveRoomOptimizer.StaffAssignment assignment : assignments) {
            double deviation = Math.abs(assignment.adjustedScore - avgPoints);
            if (deviation > stdDev * 2) {
                System.out.printf("    外れ値検出: %s (%.1f点, 平均から%.1f点の乖離)\n",
                        assignment.staff.name, assignment.adjustedScore, deviation);
            }
        }

        // 建物別の負荷確認
        int mainBuildingTotal = 0;
        int annexBuildingTotal = 0;

        for (AdaptiveRoomOptimizer.StaffAssignment assignment : assignments) {
            if (assignment.hasMainBuilding) {
                mainBuildingTotal += assignment.totalRooms;
            }
            if (assignment.hasAnnexBuilding) {
                annexBuildingTotal += assignment.totalRooms;
            }
        }

        System.out.printf("    本館負荷: %d室\n", mainBuildingTotal);
        System.out.printf("    別館負荷: %d室\n", annexBuildingTotal);

        if (Math.abs(mainBuildingTotal - annexBuildingTotal) > 5) {
            System.out.println("    警告: 建物間の負荷バランスに偏りがあります");
        }

        return assignments;
    }

    /**
     * 結果の検証
     */
    private static void validateResults(ProcessingResult result) {
        System.out.println("\n=== 結果の検証 ===");

        if (result.optimizationResult == null) {
            throw new IllegalStateException("最適化結果が生成されていません");
        }

        // 全部屋が割り当てられているか確認
        int totalAssignedRooms = result.optimizationResult.assignments.stream()
                .mapToInt(a -> a.totalRooms)
                .sum();

        int totalRooms = result.cleaningData.totalMainRooms +
                result.cleaningData.totalAnnexRooms;

        if (totalAssignedRooms != totalRooms) {
            System.out.printf("  警告: 部屋数の不一致 (割当: %d, 総数: %d)\n",
                    totalAssignedRooms, totalRooms);
        } else {
            System.out.printf("  ✓ 全%d室が正しく割り当てられています\n", totalRooms);
        }

        // スタッフ数の確認
        int assignedStaffCount = result.optimizationResult.assignments.size();
        int availableStaffCount = result.availableStaff.size();

        if (assignedStaffCount != availableStaffCount) {
            System.out.printf("  警告: スタッフ数の不一致 (割当: %d, 利用可能: %d)\n",
                    assignedStaffCount, availableStaffCount);
        } else {
            System.out.printf("  ✓ 全%d名のスタッフに割り当て完了\n", assignedStaffCount);
        }

        // 制約条件の確認
        boolean constraintViolation = false;

        for (AdaptiveRoomOptimizer.StaffAssignment assignment : result.optimizationResult.assignments) {
            // 本館の制約確認
            if (assignment.hasMainBuilding && assignment.totalRooms > AdaptiveRoomOptimizer.MAX_MAIN_BUILDING_ROOMS) {
                System.out.printf("  × %sが本館制限を超過 (%d室 > %d室)\n",
                        assignment.staff.name, assignment.totalRooms,
                        AdaptiveRoomOptimizer.MAX_MAIN_BUILDING_ROOMS);
                constraintViolation = true;
            }

            // 別館の制約確認
            if (assignment.hasAnnexBuilding && assignment.totalRooms > AdaptiveRoomOptimizer.MAX_ANNEX_BUILDING_ROOMS) {
                System.out.printf("  × %sが別館制限を超過 (%d室 > %d室)\n",
                        assignment.staff.name, assignment.totalRooms,
                        AdaptiveRoomOptimizer.MAX_ANNEX_BUILDING_ROOMS);
                constraintViolation = true;
            }
        }

        if (!constraintViolation) {
            System.out.println("  ✓ すべての制約条件を満たしています");
        }
    }

    /**
     * 設定の読み込み（コマンドライン引数がある場合のみ使用）
     */
    private static ApplicationConfig loadConfiguration(String[] args) {
        // コマンドライン引数がない場合はnullを返す
        if (args.length == 0) {
            return null;
        }

        ApplicationConfig config = new ApplicationConfig();

        // コマンドライン引数から設定を読み込む
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--room-data":
                case "-r":
                    if (i + 1 < args.length) {
                        config.roomDataFile = new File(args[++i]);
                    }
                    break;
                case "--staff-data":
                case "-s":
                    if (i + 1 < args.length) {
                        config.staffDataFile = new File(args[++i]);
                    }
                    break;
                case "--eco-data":
                case "-e":
                    if (i + 1 < args.length) {
                        config.ecoDataFile = new File(args[++i]);
                    }
                    break;
                case "--date":
                case "-d":
                    if (i + 1 < args.length) {
                        config.targetDate = LocalDate.parse(args[++i]);
                    }
                    break;
                case "--bath-cleaning":
                case "-b":
                    if (i + 1 < args.length) {
                        String bathType = args[++i].toUpperCase();
                        config.bathCleaningType = AdaptiveRoomOptimizer.BathCleaningType.valueOf(bathType);
                    }
                    break;
                case "--no-adjustment":
                    config.disableAdjustment = true;
                    break;
                case "--max-difference":
                    if (i + 1 < args.length) {
                        config.maxAllowedDifference = Double.parseDouble(args[++i]);
                    }
                    break;
                case "--help":
                case "-h":
                    printHelp();
                    System.exit(0);
                    break;
            }
        }

        // デフォルト値の設定
        if (config.roomDataFile == null) {
            config.roomDataFile = new File("wincal_room_data.CSV");
        }
        if (config.staffDataFile == null) {
            config.staffDataFile = new File("Shift.xlsx");
        }
        if (config.ecoDataFile == null) {
            config.ecoDataFile = new File("hotel_cleaning_now.xlsx");
        }
        if (config.targetDate == null) {
            config.targetDate = LocalDate.now();
        }
        if (config.bathCleaningType == null) {
            config.bathCleaningType = AdaptiveRoomOptimizer.BathCleaningType.NORMAL;
        }
        if (config.maxAllowedDifference == 0) {
            config.maxAllowedDifference = MAX_ALLOWED_DIFFERENCE;
        }

        // エコデータファイルをシステムプロパティに設定
        System.setProperty("ecoDataFile", config.ecoDataFile.getAbsolutePath());

        return config;
    }

    /**
     * ファイルの検証
     */
    private static void validateFiles(ApplicationConfig config) {
        if (!config.roomDataFile.exists()) {
            throw new IllegalArgumentException("部屋データファイルが見つかりません: " +
                    config.roomDataFile.getAbsolutePath());
        }

        if (!config.staffDataFile.exists()) {
            LOGGER.warning("スタッフデータファイルが見つかりません。デフォルトスタッフを使用します: " +
                    config.staffDataFile.getAbsolutePath());
        }

        if (!config.ecoDataFile.exists()) {
            LOGGER.warning("エコ清掃データファイルが見つかりません: " +
                    config.ecoDataFile.getAbsolutePath());
        }

        System.out.println("【入力ファイル】");
        System.out.println("部屋データ: " + config.roomDataFile.getName());
        System.out.println("スタッフデータ: " + config.staffDataFile.getName());
        System.out.println("エコ清掃データ: " + config.ecoDataFile.getName());
        System.out.println("対象日: " + config.targetDate);
        System.out.println("大浴場清掃: " + config.bathCleaningType.displayName);
        System.out.println("自動調整: " + (!config.disableAdjustment ? "有効" : "無効"));
        System.out.println("許容ポイント差: " + config.maxAllowedDifference);
        System.out.println();
    }

    /**
     * データの処理
     */
    private static ProcessingResult processData(ApplicationConfig config) {
        ProcessingResult result = new ProcessingResult();

        // 部屋データの読み込み
        System.out.println("部屋データを読み込み中...");
        FileProcessor.CleaningData cleaningData = FileProcessor.processRoomFile(config.roomDataFile);
        result.cleaningData = cleaningData;

        System.out.println("読み込み完了:");
        System.out.println("  本館: " + cleaningData.totalMainRooms + "室");
        System.out.println("  別館: " + cleaningData.totalAnnexRooms + "室");
        System.out.println("  エコ清掃: " + cleaningData.ecoRooms.size() + "室");
        System.out.println("  故障: " + cleaningData.totalBrokenRooms + "室");
        System.out.println();

        // スタッフデータの読み込み
        System.out.println("スタッフデータを読み込み中...");
        List<FileProcessor.Staff> availableStaff = FileProcessor.getAvailableStaff(
                config.staffDataFile, config.targetDate);
        result.availableStaff = availableStaff;

        System.out.println("利用可能スタッフ: " + availableStaff.size() + "名");
        System.out.println();

        // フロア情報の生成
        System.out.println("フロア情報を生成中...");
        List<AdaptiveRoomOptimizer.FloorInfo> floors = generateFloorInfo(cleaningData);
        result.floors = floors;

        // 適応型最適化の実行
        System.out.println("最適化エンジンを起動中...");
        int totalRooms = cleaningData.totalMainRooms + cleaningData.totalAnnexRooms;

        // 適応型設定の作成
        AdaptiveRoomOptimizer.AdaptiveLoadConfig adaptiveConfig =
                AdaptiveRoomOptimizer.AdaptiveLoadConfig.createAdaptiveConfig(
                        availableStaff,
                        totalRooms,
                        cleaningData.totalMainRooms,
                        cleaningData.totalAnnexRooms,
                        config.bathCleaningType
                );

        // 最適化の実行
        AdaptiveRoomOptimizer.AdaptiveOptimizer optimizer =
                new AdaptiveRoomOptimizer.AdaptiveOptimizer(floors, adaptiveConfig);

        result.optimizationResult = optimizer.optimize(config.targetDate);
        result.config = config;

        return result;
    }

    /**
     * フロア情報の生成
     */
    private static List<AdaptiveRoomOptimizer.FloorInfo> generateFloorInfo(
            FileProcessor.CleaningData cleaningData) {

        Map<Integer, Map<String, Integer>> floorRoomCounts = new HashMap<>();
        Map<Integer, Integer> floorEcoCounts = new HashMap<>();
        Map<Integer, Boolean> floorBuildings = new HashMap<>();

        // 本館の部屋を集計
        for (FileProcessor.Room room : cleaningData.mainRooms) {
            int floor = room.floor;
            if (floor == 0) continue;

            floorRoomCounts.computeIfAbsent(floor, k -> new HashMap<>());
            floorBuildings.put(floor, true);

            if (room.isEcoClean) {
                floorEcoCounts.merge(floor, 1, Integer::sum);
            } else {
                Map<String, Integer> counts = floorRoomCounts.get(floor);
                counts.merge(room.roomType, 1, Integer::sum);
            }
        }

        // 別館の部屋を集計
        for (FileProcessor.Room room : cleaningData.annexRooms) {
            int floor = room.floor;
            if (floor == 0) continue;

            if (floor < 10) {
                floor = floor + 20;
            }

            floorRoomCounts.computeIfAbsent(floor, k -> new HashMap<>());
            floorBuildings.put(floor, false);

            if (room.isEcoClean) {
                floorEcoCounts.merge(floor, 1, Integer::sum);
            } else {
                Map<String, Integer> counts = floorRoomCounts.get(floor);
                counts.merge(room.roomType, 1, Integer::sum);
            }
        }

        // FloorInfoオブジェクトのリストを作成
        List<AdaptiveRoomOptimizer.FloorInfo> floors = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, Integer>> entry : floorRoomCounts.entrySet()) {
            int floorNumber = entry.getKey();
            Map<String, Integer> roomCounts = entry.getValue();
            int ecoCounts = floorEcoCounts.getOrDefault(floorNumber, 0);
            boolean isMainBuilding = floorBuildings.getOrDefault(floorNumber, true);

            floors.add(new AdaptiveRoomOptimizer.FloorInfo(
                    floorNumber, roomCounts, ecoCounts, isMainBuilding));
        }

        floors.sort(Comparator.comparingInt(f -> f.floorNumber));

        System.out.println("生成されたフロア数: " + floors.size());
        for (AdaptiveRoomOptimizer.FloorInfo floor : floors) {
            System.out.printf("  %d階 (%s): %d室 (エコ: %d室)\n",
                    floor.floorNumber,
                    floor.isMainBuilding ? "本館" : "別館",
                    floor.getTotalRooms(),
                    floor.ecoRooms);
        }
        System.out.println();

        return floors;
    }

    /**
     * 結果の出力
     */
    private static void outputResults(ProcessingResult result) {
        if (result.optimizationResult != null) {
            result.optimizationResult.printDetailedSummary();

            // 調整提案の出力
            if (ENABLE_AUTO_ADJUSTMENT) {
                outputAdjustmentSuggestions(result);
            }

            // CSV形式での出力
            if (Boolean.getBoolean("output.csv")) {
                outputCsvResults(result.optimizationResult);
            }

            // GUI編集ツールの起動オプション
            int response = JOptionPane.showConfirmDialog(null,
                    "割り当て結果を編集しますか？\n編集GUIを開きます。",
                    "編集確認",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);

            if (response == JOptionPane.YES_OPTION) {
                // 編集GUIを起動
                System.out.println("\n編集GUIを起動しています...");
                AssignmentEditorGUI.showEditor(result.optimizationResult);
            }
        }
    }

    /**
     * 調整提案の出力
     */
    private static void outputAdjustmentSuggestions(ProcessingResult result) {
        System.out.println("\n=== 調整提案 ===");

        List<String> suggestions = new ArrayList<>();

        // ポイント差の確認
        if (result.optimizationResult.adjustedScoreDifference > MAX_ALLOWED_DIFFERENCE) {
            suggestions.add("スタッフ間のポイント差を縮小するため、部屋の再配分を検討してください");
        }

        // 階跨ぎの確認
        long multiFloorStaff = result.optimizationResult.assignments.stream()
                .filter(a -> a.floors.size() > MAX_FLOORS_PER_STAFF)
                .count();
        if (multiFloorStaff > 0) {
            suggestions.add(String.format("%d名のスタッフが%d階以上を担当しています。階数を減らすことを検討してください",
                    multiFloorStaff, MAX_FLOORS_PER_STAFF + 1));
        }

        // 建物バランスの確認
        int mainTotal = 0, annexTotal = 0;
        for (AdaptiveRoomOptimizer.StaffAssignment a : result.optimizationResult.assignments) {
            if (a.hasMainBuilding) mainTotal += a.totalRooms;
            if (a.hasAnnexBuilding) annexTotal += a.totalRooms;
        }

        if (Math.abs(mainTotal - annexTotal) > 5) {
            suggestions.add(String.format("建物間の負荷差が大きいです（本館:%d室、別館:%d室）",
                    mainTotal, annexTotal));
        }

        if (suggestions.isEmpty()) {
            System.out.println("  現在の配分は最適な状態です。追加の調整は不要です。");
        } else {
            suggestions.forEach(s -> System.out.println("  ・" + s));
        }
    }

    /**
     * CSV形式での結果出力
     */
    private static void outputCsvResults(AdaptiveRoomOptimizer.OptimizationResult result) {
        System.out.println("\n=== CSV形式出力 ===");
        System.out.println("スタッフ名,部屋数,基本点,調整後点,階数,詳細");

        for (AdaptiveRoomOptimizer.StaffAssignment assignment : result.assignments) {
            System.out.printf("%s,%d,%.2f,%.2f,\"%s\",\"%s\"\n",
                    assignment.staff.name,
                    assignment.totalRooms,
                    assignment.totalPoints,
                    assignment.adjustedScore,
                    assignment.floors.toString(),
                    assignment.getDetailedDescription());
        }
    }

    /**
     * ヘルプメッセージの表示
     */
    private static void printHelp() {
        System.out.println("使用方法: java RoomAssignmentApplication [オプション]");
        System.out.println();
        System.out.println("オプション:");
        System.out.println("  -r, --room-data <file>      部屋データファイル (CSV)");
        System.out.println("  -s, --staff-data <file>     スタッフシフトファイル (Excel)");
        System.out.println("  -e, --eco-data <file>       エコ清掃データファイル (Excel)");
        System.out.println("  -d, --date <yyyy-MM-dd>     対象日付");
        System.out.println("  -b, --bath-cleaning <type>  大浴場清掃タイプ (NONE/NORMAL/WITH_DRAINING)");
        System.out.println("  --no-adjustment              自動調整を無効化");
        System.out.println("  --max-difference <value>     許容される最大ポイント差");
        System.out.println("  -h, --help                   このヘルプを表示");
        System.out.println();
        System.out.println("例:");
        System.out.println("  java RoomAssignmentApplication -r rooms.csv -s shift.xlsx -d 2024-12-01");
        System.out.println("  java RoomAssignmentApplication --no-adjustment --max-difference 2.5");
    }

    /**
     * アプリケーション設定クラス
     */
    private static class ApplicationConfig {
        File roomDataFile;
        File staffDataFile;
        File ecoDataFile;
        LocalDate targetDate;
        AdaptiveRoomOptimizer.BathCleaningType bathCleaningType;
        boolean disableAdjustment = false;
        double maxAllowedDifference = 0;
    }

    /**
     * 処理結果クラス
     */
    private static class ProcessingResult {
        FileProcessor.CleaningData cleaningData;
        List<FileProcessor.Staff> availableStaff;
        List<AdaptiveRoomOptimizer.FloorInfo> floors;
        AdaptiveRoomOptimizer.OptimizationResult optimizationResult;
        ApplicationConfig config;
    }
}