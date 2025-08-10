package org.example;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;  // java.utilのListを使用
import java.util.stream.Collectors;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.logging.ConsoleHandler;
import java.util.logging.SimpleFormatter;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.*;
import java.awt.*;  // java.awtのListは使用しない
import java.awt.event.*;

/**
 * 部屋割り当てアプリケーションのメインクラス
 * データ読み込みと最適化計算を調整する
 */
public class RoomAssignmentApplication {

    private static final Logger LOGGER = Logger.getLogger(RoomAssignmentApplication.class.getName());

    // 設定値
    private static final String DEFAULT_ROOM_FILE = "wincal_room_data.csv";
    private static final String DEFAULT_SHIFT_FILE = "Shift.xlsx";
    private static final String DEFAULT_ECO_FILE = "hotel_cleaning_now.xlsx";

    // ファイルパス
    private final String roomFilePath;
    private final String shiftFilePath;
    private final String ecoFilePath;
    private final LocalDate targetDate;

    // データホルダー
    private FileProcessor.CleaningData cleaningData;
    private java.util.List<FileProcessor.Staff> availableStaff;  // 明示的にjava.util.Listを指定
    private java.util.List<AdaptiveRoomOptimizer.FloorInfo> floors;
    private int totalRooms;

    /**
     * コンストラクタ
     */
    public RoomAssignmentApplication(String roomFile, String shiftFile, String ecoFile, LocalDate date) {
        this.roomFilePath = roomFile;
        this.shiftFilePath = shiftFile;
        this.ecoFilePath = ecoFile;
        this.targetDate = date;
    }

    /**
     * デフォルトコンストラクタ
     */
    public RoomAssignmentApplication() {
        this(DEFAULT_ROOM_FILE, DEFAULT_SHIFT_FILE, DEFAULT_ECO_FILE, LocalDate.now());
    }

    /**
     * ファイル選択ダイアログを使用したコンストラクタ
     */
    public static RoomAssignmentApplication createWithFileSelection() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // 無視
        }

        JOptionPane.showMessageDialog(null,
                "適応型清掃管理最適化システムへようこそ。\n" +
                        "これから必要なファイルを選択していただきます。",
                "システム起動", JOptionPane.INFORMATION_MESSAGE);

        // 清掃部屋データファイルの選択
        String roomFile = selectFile("清掃部屋データファイル（CSV）を選択してください",
                "csv", "CSVファイル (*.csv)", DEFAULT_ROOM_FILE);
        if (roomFile == null) {
            System.out.println("キャンセルされました。");
            System.exit(0);
        }

        // シフトファイルの選択
        String shiftFile = selectFile("シフトファイル（Excel）を選択してください",
                "xlsx", "Excelファイル (*.xlsx)", DEFAULT_SHIFT_FILE);
        if (shiftFile == null) {
            System.out.println("キャンセルされました。");
            System.exit(0);
        }

        // エコ清掃ファイルの選択
        String ecoFile = selectFile("エコ清掃ファイル（Excel）を選択してください",
                "xlsx", "Excelファイル (*.xlsx)", DEFAULT_ECO_FILE);
        if (ecoFile == null) {
            System.out.println("キャンセルされました。");
            System.exit(0);
        }

        // 対象日の選択
        LocalDate targetDate = selectDate();

        return new RoomAssignmentApplication(roomFile, shiftFile, ecoFile, targetDate);
    }

    /**
     * ファイル選択ダイアログ
     */
    private static String selectFile(String message, String extension, String description, String defaultFile) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle(message);
        fileChooser.setCurrentDirectory(new File(System.getProperty("user.dir")));

        // デフォルトファイルが存在する場合は選択状態にする
        File defaultFileObj = new File(defaultFile);
        if (defaultFileObj.exists()) {
            fileChooser.setSelectedFile(defaultFileObj);
        }

        FileNameExtensionFilter filter = new FileNameExtensionFilter(description, extension);
        fileChooser.setFileFilter(filter);

        int result = fileChooser.showOpenDialog(null);
        if (result == JFileChooser.APPROVE_OPTION) {
            return fileChooser.getSelectedFile().getAbsolutePath();
        }
        return null;
    }

    /**
     * 日付選択ダイアログ
     */
    private static LocalDate selectDate() {
        LocalDate today = LocalDate.now();

        // 日付選択用のスピナーモデル
        SpinnerModel yearModel = new SpinnerNumberModel(today.getYear(), 2020, 2030, 1);
        SpinnerModel monthModel = new SpinnerNumberModel(today.getMonthValue(), 1, 12, 1);
        SpinnerModel dayModel = new SpinnerNumberModel(today.getDayOfMonth(), 1, 31, 1);

        JSpinner yearSpinner = new JSpinner(yearModel);
        JSpinner monthSpinner = new JSpinner(monthModel);
        JSpinner daySpinner = new JSpinner(dayModel);

        JPanel panel = new JPanel();
        panel.add(new JLabel("対象日を選択してください："));
        panel.add(yearSpinner);
        panel.add(new JLabel("年"));
        panel.add(monthSpinner);
        panel.add(new JLabel("月"));
        panel.add(daySpinner);
        panel.add(new JLabel("日"));

        int result = JOptionPane.showConfirmDialog(null, panel,
                "対象日の選択",
                JOptionPane.OK_CANCEL_OPTION);

        if (result == JOptionPane.OK_OPTION) {
            try {
                return LocalDate.of((Integer) yearSpinner.getValue(),
                        (Integer) monthSpinner.getValue(),
                        (Integer) daySpinner.getValue());
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, "無効な日付です。今日の日付を使用します。",
                        "エラー", JOptionPane.WARNING_MESSAGE);
                return today;
            }
        }

        return today;
    }

    /**
     * アプリケーションの実行
     */
    public void run() {
        try {
            System.out.println("=== 適応型清掃管理最適化システム ===");
            System.out.println("実行日時: " + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")));
            System.out.println();

            // Step 1: データ読み込み
            loadData();

            // Step 2: データ検証
            validateData();

            // Step 2.5: 残し部屋の選択
            selectRoomsToClean();

            // Step 3: フロア情報構築
            buildFloorInformation();

            // Step 4: 最適化設定の生成
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config = createOptimizationConfig();

            // Step 5: 最適化実行
            AdaptiveRoomOptimizer.OptimizationResult result = executeOptimization(config);

            // Step 6: 結果表示
            displayResults(result);

            // Step 7: 結果のエクスポート（オプション）
            exportResults(result);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "アプリケーション実行中にエラーが発生しました", e);
            System.err.println("\n❌ エラーが発生しました: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Step 1: データ読み込み
     */
    private void loadData() {
        System.out.println("【Step 1: データ読み込み】");

        // エコファイルパスを設定
        System.setProperty("ecoDataFile", ecoFilePath);

        // 部屋データの読み込み
        System.out.print("部屋データを読み込み中... ");
        File roomFile = new File(roomFilePath);
        if (!roomFile.exists()) {
            throw new RuntimeException("部屋データファイルが見つかりません: " + roomFilePath);
        }
        cleaningData = FileProcessor.processRoomFile(roomFile);
        System.out.println("✓ 完了");

        // シフトデータの読み込み
        System.out.print("シフトデータを読み込み中... ");
        File shiftFile = new File(shiftFilePath);
        if (!shiftFile.exists()) {
            throw new RuntimeException("シフトファイルが見つかりません: " + shiftFilePath);
        }
        availableStaff = FileProcessor.getAvailableStaff(shiftFile, targetDate);
        System.out.println("✓ 完了");

        // 総部屋数の計算
        totalRooms = cleaningData.totalMainRooms + cleaningData.totalAnnexRooms;

        System.out.println();
    }

    /**
     * Step 2: データ検証
     */
    private void validateData() {
        System.out.println("【Step 2: データ検証】");

        // 部屋データの検証
        System.out.println("部屋データ:");
        System.out.printf("  - 本館: %d室\n", cleaningData.totalMainRooms);
        System.out.printf("  - 別館: %d室\n", cleaningData.totalAnnexRooms);
        System.out.printf("  - エコ清掃: %d室\n", cleaningData.ecoRooms.size());
        System.out.printf("  - 故障部屋: %d室（清掃対象外）\n", cleaningData.totalBrokenRooms);
        System.out.printf("  - 清掃対象合計: %d室\n", totalRooms);

        if (totalRooms == 0) {
            throw new RuntimeException("清掃対象の部屋が0室です。データを確認してください。");
        }

        // スタッフデータの検証
        System.out.println("\nスタッフデータ:");
        System.out.printf("  - 出勤人数: %d名\n", availableStaff.size());

        if (availableStaff.isEmpty()) {
            throw new RuntimeException("出勤可能なスタッフが0名です。シフトデータを確認してください。");
        }

        // 負荷レベルの表示
        double avgRoomsPerStaff = (double) totalRooms / availableStaff.size();
        System.out.printf("  - 平均負荷: %.1f室/人\n", avgRoomsPerStaff);

        System.out.println();
    }

    /**
     * Step 2.5: 残し部屋の選択（GUI）
     */
    private void selectRoomsToClean() {
        System.out.println("【Step 2.5: 清掃部屋の選択】");

        // GUIで部屋選択画面を表示
        RoomSelectionDialog dialog = new RoomSelectionDialog(cleaningData);
        dialog.setVisible(true);

        // ダイアログの結果を待つ
        Set<String> excludedRooms = dialog.getExcludedRooms();

        if (!excludedRooms.isEmpty()) {
            System.out.printf("残し部屋として %d 室が選択されました\n", excludedRooms.size());

            // 残し部屋を除外してデータを更新
            updateCleaningDataWithExclusions(excludedRooms);

            // 総部屋数を再計算
            totalRooms = cleaningData.totalMainRooms + cleaningData.totalAnnexRooms;

            System.out.printf("更新後の清掃対象: %d室\n", totalRooms);
        }

        System.out.println();
    }

    /**
     * 残し部屋を除外してCleaningDataを更新
     */
    private void updateCleaningDataWithExclusions(Set<String> excludedRooms) {
        // 本館の部屋をフィルタリング
        java.util.List<FileProcessor.Room> filteredMainRooms = new ArrayList<>();
        for (FileProcessor.Room room : cleaningData.mainRooms) {
            if (!excludedRooms.contains(room.roomNumber)) {
                filteredMainRooms.add(room);
            }
        }

        // 別館の部屋をフィルタリング
        java.util.List<FileProcessor.Room> filteredAnnexRooms = new ArrayList<>();
        for (FileProcessor.Room room : cleaningData.annexRooms) {
            if (!excludedRooms.contains(room.roomNumber)) {
                filteredAnnexRooms.add(room);
            }
        }

        // エコ部屋もフィルタリング
        java.util.List<FileProcessor.Room> filteredEcoRooms = new ArrayList<>();
        for (FileProcessor.Room room : cleaningData.ecoRooms) {
            if (!excludedRooms.contains(room.roomNumber)) {
                filteredEcoRooms.add(room);
            }
        }

        // CleaningDataを再作成
        cleaningData = new FileProcessor.CleaningData(
                filteredMainRooms,
                filteredAnnexRooms,
                filteredEcoRooms,
                cleaningData.brokenRooms  // 故障部屋はそのまま
        );
    }

    /**
     * Step 3: フロア情報構築
     */
    private void buildFloorInformation() {
        System.out.println("【Step 3: フロア情報構築】");

        Map<Integer, AdaptiveRoomOptimizer.FloorInfo> floorMap = new HashMap<>();

        // 本館の部屋を処理
        processRoomsForFloor(cleaningData.mainRooms, floorMap, "本館");

        // 別館の部屋を処理
        processRoomsForFloor(cleaningData.annexRooms, floorMap, "別館");

        // フロアリストの作成とソート
        floors = new ArrayList<>(floorMap.values());
        floors.sort(Comparator.comparingInt(f -> f.floorNumber));

        // フロア情報の表示
        System.out.println("\nフロア別部屋数:");
        for (AdaptiveRoomOptimizer.FloorInfo floor : floors) {
            System.out.printf("  %2d階: ", floor.floorNumber);
            floor.roomCounts.forEach((type, count) -> {
                if (count > 0) System.out.printf("%s=%d ", type, count);
            });
            if (floor.ecoRooms > 0) {
                System.out.printf("エコ=%d ", floor.ecoRooms);
            }
            System.out.printf("(計%d室)\n", floor.getTotalRooms());
        }

        System.out.println();
    }

    /**
     * 部屋情報をフロアマップに追加
     */
    private void processRoomsForFloor(
            java.util.List<FileProcessor.Room> rooms,
            Map<Integer, AdaptiveRoomOptimizer.FloorInfo> floorMap,
            String buildingName) {

        for (FileProcessor.Room room : rooms) {
            int floor = room.floor;
            if (floor == 0) continue; // 階数不明の部屋はスキップ

            AdaptiveRoomOptimizer.FloorInfo floorInfo = floorMap.get(floor);
            if (floorInfo == null) {
                Map<String, Integer> counts = new HashMap<>();
                counts.put("S", 0);
                counts.put("D", 0);  // D部屋を追加
                counts.put("T", 0);
                counts.put("FD", 0);
                floorInfo = new AdaptiveRoomOptimizer.FloorInfo(floor, counts, 0);
                floorMap.put(floor, floorInfo);
            }

            if (room.isEcoClean) {
                floorInfo = new AdaptiveRoomOptimizer.FloorInfo(
                        floor, floorInfo.roomCounts, floorInfo.ecoRooms + 1);
            } else {
                Map<String, Integer> newCounts = new HashMap<>(floorInfo.roomCounts);
                newCounts.put(room.roomType, newCounts.get(room.roomType) + 1);
                floorInfo = new AdaptiveRoomOptimizer.FloorInfo(
                        floor, newCounts, floorInfo.ecoRooms);
            }
            floorMap.put(floor, floorInfo);
        }
    }

    /**
     * Step 4: 最適化設定の生成
     */
    private AdaptiveRoomOptimizer.AdaptiveLoadConfig createOptimizationConfig() {
        System.out.println("【Step 4: 最適化設定生成】");

        AdaptiveRoomOptimizer.AdaptiveLoadConfig config =
                AdaptiveRoomOptimizer.AdaptiveLoadConfig.createAdaptiveConfig(
                        availableStaff, totalRooms);

        System.out.printf("負荷レベル: %s\n", config.loadLevel.displayName);
        System.out.println("\nスタッフ配分:");
        config.staffByType.forEach((type, staffList) -> {
            int target = config.targetRoomsMap.get(type);
            int reduction = config.reductionMap.get(type);
            System.out.printf("  %s: %d名 (目標%d室, -%d室)\n",
                    type.displayName, staffList.size(), target, reduction);
        });

        System.out.println();
        return config;
    }

    /**
     * Step 5: 最適化実行
     */
    private AdaptiveRoomOptimizer.OptimizationResult executeOptimization(
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config) {

        System.out.println("【Step 5: 最適化実行】");
        System.out.println("最適化計算を開始します...\n");

        AdaptiveRoomOptimizer.AdaptiveOptimizer optimizer =
                new AdaptiveRoomOptimizer.AdaptiveOptimizer(floors, config);

        return optimizer.optimize(targetDate);
    }

    /**
     * Step 6: 結果表示
     */
    private void displayResults(AdaptiveRoomOptimizer.OptimizationResult result) {
        System.out.println("\n【Step 6: 結果表示】");
        result.printDetailedSummary();

        // 手動調整を行うか確認
        int response = JOptionPane.showConfirmDialog(null,
                "割り振り結果を手動で調整しますか？",
                "手動調整",
                JOptionPane.YES_NO_OPTION);

        if (response == JOptionPane.YES_OPTION) {
            // 手動調整画面を表示
            ManualAdjustmentFrame adjustmentFrame = new ManualAdjustmentFrame(
                    result, cleaningData, floors);
            adjustmentFrame.setVisible(true);

            // 調整後の結果を取得
            AdaptiveRoomOptimizer.OptimizationResult adjustedResult =
                    adjustmentFrame.getAdjustedResult();

            if (adjustedResult != null) {
                System.out.println("\n=== 手動調整後の結果 ===");
                adjustedResult.printDetailedSummary();
                result = adjustedResult;
            }
        }
    }

    /**
     * Step 7: 結果のエクスポート（将来の拡張用）
     */
    private void exportResults(AdaptiveRoomOptimizer.OptimizationResult result) {
        // 将来的にExcelやCSVへのエクスポート機能を実装可能
        System.out.println("\n【Step 7: 結果エクスポート】");
        System.out.println("（現在は未実装）");
    }

    /**
     * コマンドライン引数の解析
     */
    private static class CommandLineArgs {
        String roomFile = DEFAULT_ROOM_FILE;
        String shiftFile = DEFAULT_SHIFT_FILE;
        String ecoFile = DEFAULT_ECO_FILE;
        LocalDate targetDate = LocalDate.now();
        boolean help = false;
        boolean gui = false;

        static CommandLineArgs parse(String[] args) {
            CommandLineArgs result = new CommandLineArgs();

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "-r":
                    case "--room":
                        if (i + 1 < args.length) {
                            result.roomFile = args[++i];
                        }
                        break;
                    case "-s":
                    case "--shift":
                        if (i + 1 < args.length) {
                            result.shiftFile = args[++i];
                        }
                        break;
                    case "-e":
                    case "--eco":
                        if (i + 1 < args.length) {
                            result.ecoFile = args[++i];
                        }
                        break;
                    case "-d":
                    case "--date":
                        if (i + 1 < args.length) {
                            result.targetDate = LocalDate.parse(args[++i]);
                        }
                        break;
                    case "-g":
                    case "--gui":
                        result.gui = true;
                        break;
                    case "-h":
                    case "--help":
                        result.help = true;
                        break;
                }
            }

            return result;
        }
    }

    /**
     * ヘルプメッセージの表示
     */
    private static void showHelp() {
        System.out.println("使用方法: java RoomAssignmentApplication [オプション]");
        System.out.println();
        System.out.println("オプション:");
        System.out.println("  -r, --room <file>   部屋データファイル (デフォルト: " + DEFAULT_ROOM_FILE + ")");
        System.out.println("  -s, --shift <file>  シフトファイル (デフォルト: " + DEFAULT_SHIFT_FILE + ")");
        System.out.println("  -e, --eco <file>    エコ清掃ファイル (デフォルト: " + DEFAULT_ECO_FILE + ")");
        System.out.println("  -d, --date <date>   対象日 (YYYY-MM-DD形式, デフォルト: 今日)");
        System.out.println("  -g, --gui           GUIでファイルを選択");
        System.out.println("  -h, --help          このヘルプを表示");
    }

    /**
     * ロギング設定
     */
    private static void setupLogging() {
        Logger rootLogger = Logger.getLogger("");
        rootLogger.setLevel(Level.INFO);

        // コンソールハンドラーの設定
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setLevel(Level.INFO);
        consoleHandler.setFormatter(new SimpleFormatter() {
            @Override
            public String format(java.util.logging.LogRecord record) {
                if (record.getLevel() == Level.INFO) {
                    return record.getMessage() + "\n";
                } else {
                    return super.format(record);
                }
            }
        });

        // 既存のハンドラーを削除
        for (java.util.logging.Handler handler : rootLogger.getHandlers()) {
            rootLogger.removeHandler(handler);
        }

        rootLogger.addHandler(consoleHandler);
    }

    /**
     * メインメソッド
     */
    public static void main(String[] args) {
        // ロギング設定
        setupLogging();

        // コマンドライン引数の解析
        CommandLineArgs cmdArgs = CommandLineArgs.parse(args);

        if (cmdArgs.help) {
            showHelp();
            return;
        }

        try {
            RoomAssignmentApplication app;

            // GUI選択モードまたは引数なしの場合
            if (cmdArgs.gui || (args.length == 0)) {
                app = createWithFileSelection();
            } else {
                // コマンドライン引数からアプリケーションを作成
                app = new RoomAssignmentApplication(
                        cmdArgs.roomFile,
                        cmdArgs.shiftFile,
                        cmdArgs.ecoFile,
                        cmdArgs.targetDate
                );
            }

            app.run();

        } catch (Exception e) {
            System.err.println("\n❌ 致命的なエラーが発生しました: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 部屋選択ダイアログクラス
     */
    private static class RoomSelectionDialog extends JDialog {
        private Set<String> excludedRooms = new HashSet<>();
        private JTable roomTable;
        private DefaultTableModel tableModel;

        public RoomSelectionDialog(FileProcessor.CleaningData cleaningData) {
            setTitle("清掃部屋の選択");
            setModal(true);
            setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            setLayout(new BorderLayout());

            // ヘッダーパネル
            JPanel headerPanel = new JPanel(new BorderLayout());
            JLabel titleLabel = new JLabel("清掃する部屋を選択してください");
            titleLabel.setFont(new Font("MS Gothic", Font.BOLD, 16));
            titleLabel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            headerPanel.add(titleLabel, BorderLayout.NORTH);

            JLabel descLabel = new JLabel("「残し部屋」にする場合は、該当する部屋のチェックを外してください");
            descLabel.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
            headerPanel.add(descLabel, BorderLayout.SOUTH);

            add(headerPanel, BorderLayout.NORTH);

            // テーブルの作成
            String[] columnNames = {"清掃", "部屋番号", "タイプ", "建物", "エコ清掃"};
            tableModel = new DefaultTableModel(columnNames, 0) {
                @Override
                public Class<?> getColumnClass(int column) {
                    return column == 0 ? Boolean.class : String.class;
                }

                @Override
                public boolean isCellEditable(int row, int column) {
                    return column == 0;  // チェックボックス列のみ編集可能
                }
            };

            // 部屋データをテーブルに追加
            addRoomsToTable(cleaningData.mainRooms, "本館");
            addRoomsToTable(cleaningData.annexRooms, "別館");

            roomTable = new JTable(tableModel);
            roomTable.getColumnModel().getColumn(0).setPreferredWidth(50);
            roomTable.getColumnModel().getColumn(1).setPreferredWidth(100);
            roomTable.getColumnModel().getColumn(2).setPreferredWidth(60);
            roomTable.getColumnModel().getColumn(3).setPreferredWidth(60);
            roomTable.getColumnModel().getColumn(4).setPreferredWidth(80);

            JScrollPane scrollPane = new JScrollPane(roomTable);
            scrollPane.setPreferredSize(new Dimension(450, 400));
            add(scrollPane, BorderLayout.CENTER);

            // ボタンパネル
            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));

            // 全選択/全解除ボタン
            JButton selectAllButton = new JButton("全て選択");
            selectAllButton.addActionListener(e -> setAllRooms(true));
            buttonPanel.add(selectAllButton);

            JButton deselectAllButton = new JButton("全て解除");
            deselectAllButton.addActionListener(e -> setAllRooms(false));
            buttonPanel.add(deselectAllButton);

            // フロア別選択ボタン
            JButton floorButton = new JButton("フロア別選択");
            floorButton.addActionListener(e -> showFloorSelectionDialog());
            buttonPanel.add(floorButton);

            // OKボタン
            JButton okButton = new JButton("OK");
            okButton.addActionListener(e -> {
                updateExcludedRooms();
                dispose();
            });
            buttonPanel.add(okButton);

            // キャンセルボタン
            JButton cancelButton = new JButton("キャンセル");
            cancelButton.addActionListener(e -> {
                excludedRooms.clear();
                dispose();
            });
            buttonPanel.add(cancelButton);

            add(buttonPanel, BorderLayout.SOUTH);

            // ステータスバー
            JLabel statusLabel = new JLabel("総部屋数: " + tableModel.getRowCount() + "室");
            statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
            add(statusLabel, BorderLayout.PAGE_END);

            // ウィンドウサイズと位置
            pack();
            setLocationRelativeTo(null);
        }

        private void addRoomsToTable(java.util.List<FileProcessor.Room> rooms, String building) {
            for (FileProcessor.Room room : rooms) {
                Object[] row = {
                        true,  // デフォルトで選択
                        room.roomNumber,
                        room.roomType,
                        building,
                        room.isEcoClean ? "エコ" : "-"
                };
                tableModel.addRow(row);
            }
        }

        private void setAllRooms(boolean selected) {
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                tableModel.setValueAt(selected, i, 0);
            }
        }

        private void showFloorSelectionDialog() {
            // フロア情報を収集
            Map<Integer, java.util.List<Integer>> floorMap = new HashMap<>();
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                String roomNumber = (String) tableModel.getValueAt(i, 1);
                int floor = extractFloorFromRoom(roomNumber);
                if (floor > 0) {
                    floorMap.computeIfAbsent(floor, k -> new ArrayList<>()).add(i);
                }
            }

            // フロア選択ダイアログ
            JDialog floorDialog = new JDialog(this, "フロア別選択", true);
            floorDialog.setLayout(new BorderLayout());

            JPanel floorPanel = new JPanel(new GridLayout(0, 1));
            floorPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

            Map<Integer, JCheckBox> floorCheckBoxes = new HashMap<>();
            for (Integer floor : new TreeSet<>(floorMap.keySet())) {
                JCheckBox checkBox = new JCheckBox(floor + "階 (" + floorMap.get(floor).size() + "室)");
                checkBox.setSelected(true);
                floorCheckBoxes.put(floor, checkBox);
                floorPanel.add(checkBox);
            }

            JScrollPane floorScrollPane = new JScrollPane(floorPanel);
            floorScrollPane.setPreferredSize(new Dimension(200, 300));
            floorDialog.add(floorScrollPane, BorderLayout.CENTER);

            JPanel floorButtonPanel = new JPanel();
            JButton applyButton = new JButton("適用");
            applyButton.addActionListener(e -> {
                // 各フロアの選択状態を適用
                for (Map.Entry<Integer, java.util.List<Integer>> entry : floorMap.entrySet()) {
                    boolean selected = floorCheckBoxes.get(entry.getKey()).isSelected();
                    for (Integer rowIndex : entry.getValue()) {
                        tableModel.setValueAt(selected, rowIndex, 0);
                    }
                }
                floorDialog.dispose();
            });
            floorButtonPanel.add(applyButton);

            JButton closeButton = new JButton("閉じる");
            closeButton.addActionListener(e -> floorDialog.dispose());
            floorButtonPanel.add(closeButton);

            floorDialog.add(floorButtonPanel, BorderLayout.SOUTH);
            floorDialog.pack();
            floorDialog.setLocationRelativeTo(this);
            floorDialog.setVisible(true);
        }

        private int extractFloorFromRoom(String roomNumber) {
            try {
                String numericPart = roomNumber.replaceAll("[^0-9]", "");
                if (numericPart.length() == 3) {
                    return Character.getNumericValue(numericPart.charAt(0));
                } else if (numericPart.length() == 4 && numericPart.startsWith("10")) {
                    return 10;
                } else if (numericPart.length() == 4) {
                    return Character.getNumericValue(numericPart.charAt(1));
                }
            } catch (Exception e) {
                // 無視
            }
            return 0;
        }

        private void updateExcludedRooms() {
            excludedRooms.clear();
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                boolean selected = (Boolean) tableModel.getValueAt(i, 0);
                if (!selected) {
                    String roomNumber = (String) tableModel.getValueAt(i, 1);
                    excludedRooms.add(roomNumber);
                }
            }
        }

        public Set<String> getExcludedRooms() {
            return excludedRooms;
        }
    }

    /**
     * 手動調整メインフレーム
     */
    private static class ManualAdjustmentFrame extends JFrame {
        private AdaptiveRoomOptimizer.OptimizationResult currentResult;
        private final AdaptiveRoomOptimizer.OptimizationResult originalResult;
        private final FileProcessor.CleaningData cleaningData;
        private final java.util.List<AdaptiveRoomOptimizer.FloorInfo> floors;
        private JTable assignmentTable;
        private DefaultTableModel tableModel;
        private ScoreSummaryPanel scoreSummaryPanel;

        public ManualAdjustmentFrame(
                AdaptiveRoomOptimizer.OptimizationResult result,
                FileProcessor.CleaningData cleaningData,
                java.util.List<AdaptiveRoomOptimizer.FloorInfo> floors) {

            this.originalResult = result;
            this.currentResult = copyResult(result);
            this.cleaningData = cleaningData;
            this.floors = floors;

            setTitle("割り振り結果の手動調整");
            setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            setLayout(new BorderLayout());

            // メインコンテンツを分割パネルで構成
            JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
            splitPane.setDividerLocation(800);

            // 左側：部屋割り当て編集パネル
            JPanel leftPanel = createAssignmentEditPanel();
            splitPane.setLeftComponent(leftPanel);

            // 右側：スコアサマリーパネル
            scoreSummaryPanel = new ScoreSummaryPanel(currentResult);
            splitPane.setRightComponent(scoreSummaryPanel);

            add(splitPane, BorderLayout.CENTER);

            // ボタンパネル
            JPanel buttonPanel = createButtonPanel();
            add(buttonPanel, BorderLayout.SOUTH);

            // ウィンドウ設定
            setSize(1200, 700);
            setLocationRelativeTo(null);
        }

        private JPanel createAssignmentEditPanel() {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setBorder(BorderFactory.createTitledBorder("部屋割り当て編集"));

            // テーブルの作成
            String[] columnNames = {"階", "部屋番号", "タイプ", "エコ", "現在の担当", "新しい担当"};
            tableModel = new DefaultTableModel(columnNames, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return column == 5; // 新しい担当列のみ編集可能
                }

                @Override
                public Class<?> getColumnClass(int column) {
                    return column == 5 ? JComboBox.class : String.class;
                }
            };

            // 全部屋のデータを追加
            addAllRoomsToTable();

            assignmentTable = new JTable(tableModel);

            // コンボボックスエディタの設定
            JComboBox<String> staffCombo = new JComboBox<>();
            for (AdaptiveRoomOptimizer.StaffAssignment assignment : currentResult.assignments) {
                staffCombo.addItem(assignment.staff.name);
            }
            assignmentTable.getColumnModel().getColumn(5).setCellEditor(new DefaultCellEditor(staffCombo));

            // 列幅の設定
            assignmentTable.getColumnModel().getColumn(0).setPreferredWidth(40);
            assignmentTable.getColumnModel().getColumn(1).setPreferredWidth(80);
            assignmentTable.getColumnModel().getColumn(2).setPreferredWidth(50);
            assignmentTable.getColumnModel().getColumn(3).setPreferredWidth(50);
            assignmentTable.getColumnModel().getColumn(4).setPreferredWidth(150);
            assignmentTable.getColumnModel().getColumn(5).setPreferredWidth(150);

            JScrollPane scrollPane = new JScrollPane(assignmentTable);
            panel.add(scrollPane, BorderLayout.CENTER);

            // フィルターパネル
            JPanel filterPanel = createFilterPanel();
            panel.add(filterPanel, BorderLayout.NORTH);

            return panel;
        }

        private void addAllRoomsToTable() {
            // 現在の割り当てを部屋番号でマッピング
            Map<String, String> roomToStaff = new HashMap<>();
            for (AdaptiveRoomOptimizer.StaffAssignment assignment : currentResult.assignments) {
                String staffName = assignment.staff.name;
                for (Map.Entry<Integer, AdaptiveRoomOptimizer.RoomAllocation> entry :
                        assignment.roomsByFloor.entrySet()) {
                    int floor = entry.getKey();
                    // この階の部屋を取得して割り当て
                    assignRoomsToStaff(floor, entry.getValue(), staffName, roomToStaff);
                }
            }

            // 全部屋をテーブルに追加
            java.util.List<RoomInfo> allRooms = getAllRoomsSorted();
            for (RoomInfo room : allRooms) {
                String currentStaff = roomToStaff.getOrDefault(room.roomNumber, "未割当");
                Object[] row = {
                        room.floor,
                        room.roomNumber,
                        room.roomType,
                        room.isEco ? "エコ" : "-",
                        currentStaff,
                        currentStaff
                };
                tableModel.addRow(row);
            }
        }

        private void assignRoomsToStaff(int floor, AdaptiveRoomOptimizer.RoomAllocation allocation,
                                        String staffName, Map<String, String> roomToStaff) {
            // フロア情報から実際の部屋を取得
            java.util.List<FileProcessor.Room> floorRooms = new ArrayList<>();
            floorRooms.addAll(cleaningData.mainRooms.stream()
                    .filter(r -> r.floor == floor)
                    .collect(Collectors.toList()));
            floorRooms.addAll(cleaningData.annexRooms.stream()
                    .filter(r -> r.floor == floor)
                    .collect(Collectors.toList()));

            // タイプ別に部屋を割り当て
            Map<String, java.util.List<FileProcessor.Room>> roomsByType = floorRooms.stream()
                    .collect(Collectors.groupingBy(r -> r.roomType));

            // 各タイプの部屋を必要数だけ割り当て
            for (Map.Entry<String, Integer> typeEntry : allocation.roomCounts.entrySet()) {
                String type = typeEntry.getKey();
                int count = typeEntry.getValue();
                java.util.List<FileProcessor.Room> typeRooms = roomsByType.getOrDefault(type, new ArrayList<>());

                for (int i = 0; i < count && i < typeRooms.size(); i++) {
                    FileProcessor.Room room = typeRooms.get(i);
                    if (!room.isEcoClean) {
                        roomToStaff.put(room.roomNumber, staffName);
                    }
                }
            }

            // エコ部屋の割り当て
            java.util.List<FileProcessor.Room> ecoRooms = floorRooms.stream()
                    .filter(r -> r.isEcoClean)
                    .collect(Collectors.toList());

            for (int i = 0; i < allocation.ecoRooms && i < ecoRooms.size(); i++) {
                roomToStaff.put(ecoRooms.get(i).roomNumber, staffName);
            }
        }

        private java.util.List<RoomInfo> getAllRoomsSorted() {
            java.util.List<RoomInfo> allRooms = new ArrayList<>();

            // 本館の部屋
            for (FileProcessor.Room room : cleaningData.mainRooms) {
                allRooms.add(new RoomInfo(room.roomNumber, room.roomType,
                        room.floor, room.isEcoClean, "本館"));
            }

            // 別館の部屋
            for (FileProcessor.Room room : cleaningData.annexRooms) {
                allRooms.add(new RoomInfo(room.roomNumber, room.roomType,
                        room.floor, room.isEcoClean, "別館"));
            }

            // ソート（階数、部屋番号順）
            allRooms.sort((a, b) -> {
                int floorComp = Integer.compare(a.floor, b.floor);
                if (floorComp != 0) return floorComp;
                return a.roomNumber.compareTo(b.roomNumber);
            });

            return allRooms;
        }

        private JPanel createFilterPanel() {
            JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));

            // 階フィルター
            JLabel floorLabel = new JLabel("階:");
            JComboBox<String> floorCombo = new JComboBox<>();
            floorCombo.addItem("全て");
            for (int i = 1; i <= 10; i++) {
                floorCombo.addItem(i + "階");
            }

            // スタッフフィルター
            JLabel staffLabel = new JLabel("スタッフ:");
            JComboBox<String> staffCombo = new JComboBox<>();
            staffCombo.addItem("全て");
            for (AdaptiveRoomOptimizer.StaffAssignment assignment : currentResult.assignments) {
                staffCombo.addItem(assignment.staff.name);
            }

            // フィルター適用ボタン
            JButton filterButton = new JButton("フィルター適用");
            filterButton.addActionListener(e -> applyFilter(floorCombo, staffCombo));

            panel.add(floorLabel);
            panel.add(floorCombo);
            panel.add(Box.createHorizontalStrut(20));
            panel.add(staffLabel);
            panel.add(staffCombo);
            panel.add(Box.createHorizontalStrut(20));
            panel.add(filterButton);

            return panel;
        }

        private void applyFilter(JComboBox<String> floorCombo, JComboBox<String> staffCombo) {
            String selectedFloor = (String) floorCombo.getSelectedItem();
            String selectedStaff = (String) staffCombo.getSelectedItem();

            TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(tableModel);
            assignmentTable.setRowSorter(sorter);

            java.util.List<RowFilter<Object, Object>> filters = new ArrayList<>();

            // 階フィルター
            if (!"全て".equals(selectedFloor)) {
                String floor = selectedFloor.replace("階", "");
                filters.add(RowFilter.regexFilter("^" + floor + "$", 0));
            }

            // スタッフフィルター
            if (!"全て".equals(selectedStaff)) {
                filters.add(RowFilter.regexFilter("^" + selectedStaff + "$", 4));
            }

            if (!filters.isEmpty()) {
                sorter.setRowFilter(RowFilter.andFilter(filters));
            } else {
                sorter.setRowFilter(null);
            }
        }

        private JPanel createButtonPanel() {
            JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER));

            JButton applyButton = new JButton("変更を適用");
            applyButton.addActionListener(e -> applyChanges());

            JButton resetButton = new JButton("リセット");
            resetButton.addActionListener(e -> resetChanges());

            JButton detailButton = new JButton("詳細スコア表示");
            detailButton.addActionListener(e -> showDetailedScores());

            JButton confirmButton = new JButton("確定");
            confirmButton.addActionListener(e -> confirmAndClose());

            JButton cancelButton = new JButton("キャンセル");
            cancelButton.addActionListener(e -> dispose());

            panel.add(applyButton);
            panel.add(resetButton);
            panel.add(detailButton);
            panel.add(confirmButton);
            panel.add(cancelButton);

            return panel;
        }

        private void applyChanges() {
            // テーブルの変更を反映して新しい結果を生成
            Map<String, java.util.List<RoomAssignment>> newAssignments = new HashMap<>();

            // 各行から新しい割り当てを収集
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                String roomNumber = (String) tableModel.getValueAt(i, 1);
                String roomType = (String) tableModel.getValueAt(i, 2);
                String isEco = (String) tableModel.getValueAt(i, 3);
                int floor = Integer.parseInt(tableModel.getValueAt(i, 0).toString());
                String newStaff = (String) tableModel.getValueAt(i, 5);

                if (!"未割当".equals(newStaff)) {
                    newAssignments.computeIfAbsent(newStaff, k -> new ArrayList<>())
                            .add(new RoomAssignment(roomNumber, roomType, floor, "エコ".equals(isEco)));
                }
            }

            // 新しいStaffAssignmentリストを作成
            java.util.List<AdaptiveRoomOptimizer.StaffAssignment> newStaffAssignments = new ArrayList<>();

            for (AdaptiveRoomOptimizer.StaffAssignment original : originalResult.assignments) {
                String staffName = original.staff.name;
                java.util.List<RoomAssignment> rooms = newAssignments.get(staffName);

                if (rooms != null && !rooms.isEmpty()) {
                    // フロア別に部屋を整理
                    Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> roomsByFloor =
                            organizeRoomsByFloor(rooms);
                    java.util.List<Integer> floors = new ArrayList<>(roomsByFloor.keySet());

                    AdaptiveRoomOptimizer.StaffAssignment newAssignment =
                            new AdaptiveRoomOptimizer.StaffAssignment(
                                    original.staff, original.workerType, floors, roomsByFloor);
                    newStaffAssignments.add(newAssignment);
                } else {
                    // 部屋が割り当てられていない場合は空の割り当て
                    newStaffAssignments.add(new AdaptiveRoomOptimizer.StaffAssignment(
                            original.staff, original.workerType,
                            new ArrayList<>(), new HashMap<>()));
                }
            }

            // 新しい結果を作成
            currentResult = new AdaptiveRoomOptimizer.OptimizationResult(
                    newStaffAssignments, originalResult.config, originalResult.targetDate);

            // スコアパネルを更新
            scoreSummaryPanel.updateScores(currentResult);

            JOptionPane.showMessageDialog(this, "変更が適用されました。");
        }

        private Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> organizeRoomsByFloor(
                java.util.List<RoomAssignment> rooms) {
            Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> result = new HashMap<>();

            // フロア別にグループ化
            Map<Integer, java.util.List<RoomAssignment>> roomsByFloor = rooms.stream()
                    .collect(Collectors.groupingBy(r -> r.floor));

            for (Map.Entry<Integer, java.util.List<RoomAssignment>> entry : roomsByFloor.entrySet()) {
                int floor = entry.getKey();
                java.util.List<RoomAssignment> floorRooms = entry.getValue();

                // タイプ別カウント
                Map<String, Integer> typeCounts = new HashMap<>();
                typeCounts.put("S", 0);
                typeCounts.put("D", 0);
                typeCounts.put("T", 0);
                typeCounts.put("FD", 0);

                int ecoCount = 0;

                for (RoomAssignment room : floorRooms) {
                    if (room.isEco) {
                        ecoCount++;
                    } else {
                        typeCounts.put(room.roomType, typeCounts.get(room.roomType) + 1);
                    }
                }

                result.put(floor, new AdaptiveRoomOptimizer.RoomAllocation(typeCounts, ecoCount));
            }

            return result;
        }

        private void resetChanges() {
            // 元の割り当てに戻す
            currentResult = copyResult(originalResult);

            // テーブルを再構築
            tableModel.setRowCount(0);
            addAllRoomsToTable();

            // スコアパネルを更新
            scoreSummaryPanel.updateScores(currentResult);

            JOptionPane.showMessageDialog(this, "変更がリセットされました。");
        }

        private void showDetailedScores() {
            DetailedScoreDialog dialog = new DetailedScoreDialog(this, currentResult);
            dialog.setVisible(true);
        }

        private void confirmAndClose() {
            int response = JOptionPane.showConfirmDialog(this,
                    "現在の割り当てで確定しますか？",
                    "確認",
                    JOptionPane.YES_NO_OPTION);

            if (response == JOptionPane.YES_OPTION) {
                dispose();
            }
        }

        public AdaptiveRoomOptimizer.OptimizationResult getAdjustedResult() {
            return currentResult;
        }

        private AdaptiveRoomOptimizer.OptimizationResult copyResult(
                AdaptiveRoomOptimizer.OptimizationResult original) {
            // 結果のディープコピーを作成
            java.util.List<AdaptiveRoomOptimizer.StaffAssignment> copiedAssignments = new ArrayList<>();
            for (AdaptiveRoomOptimizer.StaffAssignment assignment : original.assignments) {
                copiedAssignments.add(new AdaptiveRoomOptimizer.StaffAssignment(
                        assignment.staff,
                        assignment.workerType,
                        new ArrayList<>(assignment.floors),
                        new HashMap<>(assignment.roomsByFloor)));
            }
            return new AdaptiveRoomOptimizer.OptimizationResult(
                    copiedAssignments, original.config, original.targetDate);
        }

        // 内部クラス：部屋情報
        private static class RoomInfo {
            final String roomNumber;
            final String roomType;
            final int floor;
            final boolean isEco;
            final String building;

            RoomInfo(String roomNumber, String roomType, int floor, boolean isEco, String building) {
                this.roomNumber = roomNumber;
                this.roomType = roomType;
                this.floor = floor;
                this.isEco = isEco;
                this.building = building;
            }
        }

        // 内部クラス：部屋割り当て
        private static class RoomAssignment {
            final String roomNumber;
            final String roomType;
            final int floor;
            final boolean isEco;

            RoomAssignment(String roomNumber, String roomType, int floor, boolean isEco) {
                this.roomNumber = roomNumber;
                this.roomType = roomType;
                this.floor = floor;
                this.isEco = isEco;
            }
        }
    }

    /**
     * スコアサマリーパネル
     */
    private static class ScoreSummaryPanel extends JPanel {
        private JTextArea summaryArea;

        public ScoreSummaryPanel(AdaptiveRoomOptimizer.OptimizationResult result) {
            setLayout(new BorderLayout());
            setBorder(BorderFactory.createTitledBorder("スコアサマリー"));

            summaryArea = new JTextArea();
            summaryArea.setEditable(false);
            summaryArea.setFont(new Font("Monospaced", Font.PLAIN, 12));

            JScrollPane scrollPane = new JScrollPane(summaryArea);
            add(scrollPane, BorderLayout.CENTER);

            updateScores(result);
        }

        public void updateScores(AdaptiveRoomOptimizer.OptimizationResult result) {
            StringBuilder sb = new StringBuilder();
            sb.append("=== スタッフ別スコア ===\n\n");

            // スコア計算
            double minScore = Double.MAX_VALUE;
            double maxScore = Double.MIN_VALUE;
            double totalScore = 0;

            for (AdaptiveRoomOptimizer.StaffAssignment assignment : result.assignments) {
                double score = assignment.totalPoints;
                minScore = Math.min(minScore, score);
                maxScore = Math.max(maxScore, score);
                totalScore += score;

                sb.append(String.format("%-15s: %2d室 (%.1f点)\n",
                        assignment.staff.name, assignment.totalRooms, score));

                // 詳細
                sb.append("  担当フロア: ");
                for (int i = 0; i < assignment.floors.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(assignment.floors.get(i) + "階");
                }
                sb.append("\n");

                // タイプ別内訳
                Map<String, Integer> totalCounts = new HashMap<>();
                totalCounts.put("S", 0);
                totalCounts.put("D", 0);
                totalCounts.put("T", 0);
                totalCounts.put("FD", 0);
                int totalEco = 0;

                for (AdaptiveRoomOptimizer.RoomAllocation allocation : assignment.roomsByFloor.values()) {
                    allocation.roomCounts.forEach((type, count) ->
                            totalCounts.put(type, totalCounts.get(type) + count));
                    totalEco += allocation.ecoRooms;
                }

                sb.append("  内訳: ");
                java.util.List<String> breakdown = new ArrayList<>();
                if (totalCounts.get("S") > 0) breakdown.add("S:" + totalCounts.get("S"));
                if (totalCounts.get("D") > 0) breakdown.add("D:" + totalCounts.get("D"));
                if (totalCounts.get("T") > 0) breakdown.add("T:" + totalCounts.get("T"));
                if (totalCounts.get("FD") > 0) breakdown.add("FD:" + totalCounts.get("FD"));
                if (totalEco > 0) breakdown.add("エコ:" + totalEco);
                sb.append(String.join(", ", breakdown));
                sb.append("\n\n");
            }

            // 統計情報
            sb.append("=== 統計情報 ===\n");
            sb.append(String.format("最小スコア: %.1f点\n", minScore));
            sb.append(String.format("最大スコア: %.1f点\n", maxScore));
            sb.append(String.format("スコア差: %.1f点\n", maxScore - minScore));
            sb.append(String.format("平均スコア: %.1f点\n",
                    totalScore / result.assignments.size()));

            // 評価
            String evaluation;
            double scoreDiff = maxScore - minScore;
            if (scoreDiff <= 3.0) {
                evaluation = "✅ 優秀（バランス良好）";
            } else if (scoreDiff <= 4.0) {
                evaluation = "⭕ 良好";
            } else {
                evaluation = "⚠️ 要改善（偏りあり）";
            }
            sb.append("\n評価: ").append(evaluation);

            summaryArea.setText(sb.toString());
            summaryArea.setCaretPosition(0);
        }
    }

    /**
     * 詳細スコアダイアログ
     */
    private static class DetailedScoreDialog extends JDialog {
        public DetailedScoreDialog(JFrame parent, AdaptiveRoomOptimizer.OptimizationResult result) {
            super(parent, "詳細スコア表示", true);
            setLayout(new BorderLayout());

            // テーブルの作成
            String[] columnNames = {"スタッフ", "総室数", "S", "D", "T", "FD", "エコ",
                    "総スコア", "タイプ"};
            DefaultTableModel model = new DefaultTableModel(columnNames, 0);

            for (AdaptiveRoomOptimizer.StaffAssignment assignment : result.assignments) {
                // タイプ別集計
                Map<String, Integer> totalCounts = new HashMap<>();
                totalCounts.put("S", 0);
                totalCounts.put("D", 0);
                totalCounts.put("T", 0);
                totalCounts.put("FD", 0);
                int totalEco = 0;

                for (AdaptiveRoomOptimizer.RoomAllocation allocation : assignment.roomsByFloor.values()) {
                    allocation.roomCounts.forEach((type, count) ->
                            totalCounts.put(type, totalCounts.get(type) + count));
                    totalEco += allocation.ecoRooms;
                }

                String workerType = assignment.workerType ==
                        AdaptiveRoomOptimizer.WorkerType.LIGHT_DUTY ?
                        "軽減" : "通常";

                Object[] row = {
                        assignment.staff.name,
                        assignment.totalRooms,
                        totalCounts.get("S"),
                        totalCounts.get("D"),
                        totalCounts.get("T"),
                        totalCounts.get("FD"),
                        totalEco,
                        String.format("%.1f", assignment.totalPoints),
                        workerType
                };
                model.addRow(row);
            }

            JTable table = new JTable(model);
            table.setAutoCreateRowSorter(true);

            // 列幅設定
            table.getColumnModel().getColumn(0).setPreferredWidth(150);
            for (int i = 1; i <= 6; i++) {
                table.getColumnModel().getColumn(i).setPreferredWidth(60);
            }
            table.getColumnModel().getColumn(7).setPreferredWidth(80);
            table.getColumnModel().getColumn(8).setPreferredWidth(60);

            JScrollPane scrollPane = new JScrollPane(table);
            add(scrollPane, BorderLayout.CENTER);

            // スコア計算説明
            JPanel bottomPanel = new JPanel(new BorderLayout());
            JTextArea explainArea = new JTextArea(
                    "【スコア計算式】\n" +
                            "S部屋: 1.0点/室\n" +
                            "D部屋: 1.0点/室\n" +
                            "T部屋: 1.5点/室\n" +
                            "FD部屋: 2.0点/室\n" +
                            "エコ部屋: 0.5点/室\n"
            );
            explainArea.setEditable(false);
            explainArea.setBackground(getBackground());
            bottomPanel.add(explainArea, BorderLayout.NORTH);

            JButton closeButton = new JButton("閉じる");
            closeButton.addActionListener(e -> dispose());
            JPanel buttonPanel = new JPanel();
            buttonPanel.add(closeButton);
            bottomPanel.add(buttonPanel, BorderLayout.SOUTH);

            add(bottomPanel, BorderLayout.SOUTH);

            setSize(700, 500);
            setLocationRelativeTo(parent);
        }
    }
}