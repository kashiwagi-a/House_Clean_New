package org.example;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;

public class RoomAssignmentApplication extends JFrame {
    private JTextArea outputArea;
    private JButton loadRoomDataButton;
    private JButton loadShiftDataButton;
    private JButton loadCleaningDataButton;
    private JButton processButton;
    private JButton saveButton;
    private JButton editButton;
    private JButton enhancedEditButton;
    private JButton clearButton;
    private JProgressBar progressBar;
    private JLabel statusLabel;

    private ProcessingResult lastResult;
    private Map<String, Integer> floorStaffCount;

    // ファイルパス保存用
    private File roomDataFile;
    private File shiftDataFile;
    private File cleaningDataFile;

    // データ保存用
    private List<AppRoom> loadedRooms;
    private List<AppStaff> loadedStaff;
    private Map<String, Integer> cleaningTimeData;

    // データクラス定義
    static class AppRoom {
        String roomNumber;
        String type;
        boolean isCheckout;
        boolean isStay;
        boolean isDND;
        int priority;
        int buildingNumber;
        String floor;
        int estimatedTime;

        public AppRoom(String roomNumber, String type, boolean isCheckout, boolean isStay,
                       boolean isDND, int priority, int buildingNumber) {
            this.roomNumber = roomNumber;
            this.type = type;
            this.isCheckout = isCheckout;
            this.isStay = isStay;
            this.isDND = isDND;
            this.priority = priority;
            this.buildingNumber = buildingNumber;
            this.floor = calculateFloor(roomNumber);  // 修正: 正しい階数計算を使用
            this.estimatedTime = calculateEstimatedTime();
        }

        // 修正: 正しい階数計算メソッド（別館表記対応）
        private String calculateFloor(String roomNumber) {
            if (roomNumber == null || roomNumber.isEmpty()) {
                return "1";
            }

            try {
                int roomNum = Integer.parseInt(roomNumber.replaceAll("[^0-9]", ""));

                if (roomNum >= 201 && roomNum <= 299) {
                    return "2";      // 2階（本館）
                } else if (roomNum >= 301 && roomNum <= 999) {
                    return String.valueOf(roomNum / 100);  // 3-9階（本館）
                } else if (roomNum >= 1001 && roomNum <= 1017) {
                    return "10";     // 10階（本館）
                } else if (roomNum >= 2201 && roomNum <= 2799) {
                    // 別館の階数計算: 22xx→別館2階, 23xx→別館3階, etc.
                    int annexFloor = roomNum / 100 - 20;  // 22→2, 23→3, 24→4, etc.
                    return "別館" + annexFloor;
                } else {
                    return String.valueOf(roomNum / 100);
                }
            } catch (Exception e) {
                return "1";
            }
        }

        private int calculateEstimatedTime() {
            int time = 20; // 基本時間
            if (isCheckout) time += 15;
            if (isStay) time -= 5;
            if ("NS".equals(type)) time -= 3;
            if (isDND) time += 5;
            return Math.max(time, 10);
        }

        @Override
        public String toString() {
            return String.format("AppRoom{number='%s', type='%s', floor='%s', checkout=%b, stay=%b, dnd=%b, priority=%d, time=%d}",
                    roomNumber, type, floor, isCheckout, isStay, isDND, priority, estimatedTime);
        }
    }

    static class AppStaff {
        String name;
        String shift;
        boolean isAvailable;
        List<String> assignedRooms;
        int currentLoad;
        int maxCapacity;
        Set<String> preferredFloors;

        public AppStaff(String name, String shift, boolean isAvailable) {
            this.name = name;
            this.shift = shift;
            this.isAvailable = isAvailable;
            this.assignedRooms = new ArrayList<>();
            this.currentLoad = 0;
            this.maxCapacity = shift.contains("早") ? 15 : 12;
            this.preferredFloors = new HashSet<>();
        }

        @Override
        public String toString() {
            return String.format("AppStaff{name='%s', shift='%s', available=%b, load=%d/%d}",
                    name, shift, isAvailable, currentLoad, maxCapacity);
        }
    }

    static class ProcessingResult {
        Map<String, List<AppRoom>> assignments;
        List<AppRoom> unassignedRooms;
        Map<String, AppStaff> staffMap;
        List<AppRoom> allRooms;
        String summary;
        Map<String, Integer> floorStaffCount;
        Map<String, Integer> cleaningData;
        double optimizationScore;
        long processingTime;

        // ========== 追加フィールド（AdaptiveRoomOptimizerとの連携用） ==========
        AdaptiveRoomOptimizer.OptimizationResult optimizationResult;
        FileProcessor.CleaningData cleaningDataObj;
        // ======================================================================

        public ProcessingResult() {
            this.assignments = new HashMap<>();
            this.unassignedRooms = new ArrayList<>();
            this.staffMap = new HashMap<>();
            this.allRooms = new ArrayList<>();
            this.summary = "";
            this.floorStaffCount = new HashMap<>();
            this.cleaningData = new HashMap<>();
            this.optimizationScore = 0.0;
            this.processingTime = 0;

            // 追加フィールドの初期化
            this.optimizationResult = null;
            this.cleaningDataObj = null;
        }
    }

    public RoomAssignmentApplication() {
        setTitle("ホテル清掃管理システム v2.0");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // メニューバー
        createMenuBar();

        // ツールバー
        JPanel toolBar = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // ファイル読み込みボタン
        gbc.gridx = 0;
        gbc.gridy = 0;
        loadRoomDataButton = new JButton("部屋データ読込");
        loadRoomDataButton.setIcon(UIManager.getIcon("FileView.fileIcon"));
        toolBar.add(loadRoomDataButton, gbc);

        gbc.gridx = 1;
        loadShiftDataButton = new JButton("シフトデータ読込");
        loadShiftDataButton.setIcon(UIManager.getIcon("FileView.fileIcon"));
        toolBar.add(loadShiftDataButton, gbc);

        gbc.gridx = 2;
        loadCleaningDataButton = new JButton("清掃データ読込");
        loadCleaningDataButton.setIcon(UIManager.getIcon("FileView.fileIcon"));
        toolBar.add(loadCleaningDataButton, gbc);

        // 処理ボタン
        gbc.gridx = 0;
        gbc.gridy = 1;
        processButton = new JButton("最適化実行");
        processButton.setEnabled(false);
        processButton.setBackground(new Color(100, 200, 100));
        toolBar.add(processButton, gbc);

        gbc.gridx = 1;
        saveButton = new JButton("結果保存");
        saveButton.setEnabled(false);
        toolBar.add(saveButton, gbc);

        gbc.gridx = 2;
        clearButton = new JButton("クリア");
        toolBar.add(clearButton, gbc);

        // 編集ボタン
        gbc.gridx = 0;
        gbc.gridy = 2;
        editButton = new JButton("割当編集");
        editButton.setEnabled(false);
        toolBar.add(editButton, gbc);

        gbc.gridx = 1;
        enhancedEditButton = new JButton("詳細編集");
        enhancedEditButton.setEnabled(false);
        toolBar.add(enhancedEditButton, gbc);

        // ステータスパネル
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusLabel = new JLabel("ファイルを読み込んでください");
        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        statusPanel.add(statusLabel, BorderLayout.WEST);
        statusPanel.add(progressBar, BorderLayout.CENTER);

        // 出力エリア
        outputArea = new JTextArea(25, 80);
        outputArea.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12));
        outputArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(outputArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("処理ログ"));

        // レイアウト
        JPanel northPanel = new JPanel(new BorderLayout());
        northPanel.add(toolBar, BorderLayout.CENTER);
        northPanel.add(statusPanel, BorderLayout.SOUTH);

        add(northPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);

        // イベントリスナー
        loadRoomDataButton.addActionListener(e -> loadRoomData());
        loadShiftDataButton.addActionListener(e -> loadShiftData());
        loadCleaningDataButton.addActionListener(e -> loadCleaningData());
        processButton.addActionListener(e -> processAssignments());
        saveButton.addActionListener(e -> saveResults());
        editButton.addActionListener(e -> openEditor());
        enhancedEditButton.addActionListener(e -> openEnhancedEditor());
        clearButton.addActionListener(e -> clearAll());

        pack();
        setLocationRelativeTo(null);

        // 初期メッセージ
        outputArea.append("=== ホテル清掃管理システム v2.0 ===\n");
        outputArea.append("1. 部屋データ（CSV）を読み込んでください\n");
        outputArea.append("2. シフトデータ（Excel）を読み込んでください\n");
        outputArea.append("3. 必要に応じて清掃データ（Excel）を読み込んでください\n");
        outputArea.append("4. 「最適化実行」ボタンをクリックして割り当てを生成します\n\n");
    }

    private void createMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // ファイルメニュー
        JMenu fileMenu = new JMenu("ファイル");
        JMenuItem openItem = new JMenuItem("開く...");
        JMenuItem saveItem = new JMenuItem("保存...");
        JMenuItem exitItem = new JMenuItem("終了");

        fileMenu.add(openItem);
        fileMenu.add(saveItem);
        fileMenu.addSeparator();
        fileMenu.add(exitItem);

        // 編集メニュー
        JMenu editMenu = new JMenu("編集");
        JMenuItem preferencesItem = new JMenuItem("設定...");
        editMenu.add(preferencesItem);

        // ヘルプメニュー
        JMenu helpMenu = new JMenu("ヘルプ");
        JMenuItem aboutItem = new JMenuItem("このソフトウェアについて");
        helpMenu.add(aboutItem);

        menuBar.add(fileMenu);
        menuBar.add(editMenu);
        menuBar.add(helpMenu);

        setJMenuBar(menuBar);

        // イベントリスナー
        exitItem.addActionListener(e -> System.exit(0));
        aboutItem.addActionListener(e -> showAboutDialog());
    }

    private void loadRoomData() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("部屋データCSVファイルを選択");
        fileChooser.setFileFilter(new FileNameExtensionFilter("CSVファイル", "csv", "CSV"));

        if (roomDataFile != null) {
            fileChooser.setCurrentDirectory(roomDataFile.getParentFile());
        }

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            roomDataFile = fileChooser.getSelectedFile();

            try {
                loadedRooms = readRoomDataFromFile(roomDataFile);
                outputArea.append("部屋データを読み込みました: " + roomDataFile.getName() + "\n");
                outputArea.append("  - 読み込んだ部屋数: " + loadedRooms.size() + "\n");

                // フロア別集計
                Map<String, Long> floorCounts = loadedRooms.stream()
                        .collect(Collectors.groupingBy(r -> r.floor, Collectors.counting()));
                outputArea.append("  - フロア別: ");
                floorCounts.forEach((floor, count) ->
                        outputArea.append(floor + "階=" + count + "部屋 "));
                outputArea.append("\n\n");

                statusLabel.setText("部屋データ読込完了");
                checkEnableProcessButton();

            } catch (Exception e) {
                JOptionPane.showMessageDialog(this,
                        "ファイル読み込みエラー: " + e.getMessage(),
                        "エラー",
                        JOptionPane.ERROR_MESSAGE);
                outputArea.append("エラー: " + e.getMessage() + "\n");
            }
        }
    }

    private void loadShiftData() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("シフトデータExcelファイルを選択");
        fileChooser.setFileFilter(new FileNameExtensionFilter("Excelファイル", "xlsx", "xls"));

        if (shiftDataFile != null) {
            fileChooser.setCurrentDirectory(shiftDataFile.getParentFile());
        }

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            shiftDataFile = fileChooser.getSelectedFile();

            // 日付選択ダイアログを表示
            java.time.LocalDate selectedDate = showDateSelectionDialog();
            if (selectedDate == null) {
                return; // キャンセルされた場合
            }

            try {
                // FileProcessor.getAvailableStaffを使用
                List<FileProcessor.Staff> fileStaffList = FileProcessor.getAvailableStaff(shiftDataFile, selectedDate);

                // AppStaffに変換
                loadedStaff = new ArrayList<>();
                for (FileProcessor.Staff fs : fileStaffList) {
                    loadedStaff.add(new AppStaff(fs.name, "通常", true));
                }

                outputArea.append("シフトデータを読み込みました: " + shiftDataFile.getName() + "\n");
                outputArea.append("  - 対象日付: " + selectedDate + "\n");
                outputArea.append("  - スタッフ数: " + loadedStaff.size() + "\n");

                // シフト別集計
                Map<String, Long> shiftCounts = loadedStaff.stream()
                        .collect(Collectors.groupingBy(s -> s.shift, Collectors.counting()));
                outputArea.append("  - シフト別: ");
                shiftCounts.forEach((shift, count) ->
                        outputArea.append(shift + "=" + count + "人 "));
                outputArea.append("\n\n");

                statusLabel.setText("シフトデータ読込完了");
                checkEnableProcessButton();

            } catch (Exception e) {
                JOptionPane.showMessageDialog(this,
                        "ファイル読み込みエラー: " + e.getMessage(),
                        "エラー",
                        JOptionPane.ERROR_MESSAGE);
                outputArea.append("エラー: " + e.getMessage() + "\n");
            }
        }
    }

    private java.time.LocalDate showDateSelectionDialog() {
        JDialog dateDialog = new JDialog(this, "日付選択", true);
        dateDialog.setLayout(new BorderLayout());
        dateDialog.setSize(400, 200);
        dateDialog.setLocationRelativeTo(this);

        JPanel panel = new JPanel(new GridLayout(3, 1, 10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel titleLabel = new JLabel("シフト確認する日付を選択してください：", JLabel.CENTER);
        titleLabel.setFont(new Font("Dialog", Font.BOLD, 14));
        panel.add(titleLabel);

        JPanel datePanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));

        // 今日の日付をデフォルトに設定
        java.time.LocalDate today = java.time.LocalDate.now();

        JTextField yearField = new JTextField(String.valueOf(today.getYear()), 6);
        JTextField monthField = new JTextField(String.valueOf(today.getMonthValue()), 4);
        JTextField dayField = new JTextField(String.valueOf(today.getDayOfMonth()), 4);

        // フィールドのフォントサイズを大きく
        Font fieldFont = new Font("Dialog", Font.PLAIN, 14);
        yearField.setFont(fieldFont);
        monthField.setFont(fieldFont);
        dayField.setFont(fieldFont);

        datePanel.add(yearField);
        datePanel.add(new JLabel("年"));
        datePanel.add(monthField);
        datePanel.add(new JLabel("月"));
        datePanel.add(dayField);
        datePanel.add(new JLabel("日"));

        panel.add(datePanel);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 10));
        JButton okButton = new JButton("OK");
        JButton cancelButton = new JButton("キャンセル");

        // ボタンのサイズを大きく
        okButton.setPreferredSize(new Dimension(80, 30));
        cancelButton.setPreferredSize(new Dimension(80, 30));

        final java.time.LocalDate[] selectedDate = {null};

        okButton.addActionListener(e -> {
            try {
                int year = Integer.parseInt(yearField.getText().trim());
                int month = Integer.parseInt(monthField.getText().trim());
                int day = Integer.parseInt(dayField.getText().trim());

                selectedDate[0] = java.time.LocalDate.of(year, month, day);
                dateDialog.dispose();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dateDialog,
                        "正しい日付を入力してください", "入力エラー", JOptionPane.ERROR_MESSAGE);
            }
        });

        cancelButton.addActionListener(e -> {
            selectedDate[0] = null;
            dateDialog.dispose();
        });

        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);

        panel.add(buttonPanel);

        dateDialog.add(panel, BorderLayout.CENTER);
        dateDialog.setVisible(true);
        return selectedDate[0];
    }

    private void loadCleaningData() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("清掃データExcelファイルを選択（オプション）");
        fileChooser.setFileFilter(new FileNameExtensionFilter("Excelファイル", "xlsx", "xls"));

        if (cleaningDataFile != null) {
            fileChooser.setCurrentDirectory(cleaningDataFile.getParentFile());
        }

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            cleaningDataFile = fileChooser.getSelectedFile();

            try {
                cleaningTimeData = readCleaningDataFromFile(cleaningDataFile);
                outputArea.append("清掃データを読み込みました: " + cleaningDataFile.getName() + "\n");
                outputArea.append("  - データ項目数: " + cleaningTimeData.size() + "\n\n");

                statusLabel.setText("清掃データ読込完了");

            } catch (Exception e) {
                JOptionPane.showMessageDialog(this,
                        "ファイル読み込みエラー: " + e.getMessage(),
                        "エラー",
                        JOptionPane.ERROR_MESSAGE);
                outputArea.append("エラー: " + e.getMessage() + "\n");
            }
        }
    }

    private List<AppRoom> readRoomDataFromFile(File file) throws IOException {
        List<AppRoom> rooms = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;

            while ((line = br.readLine()) != null) {
                lineNumber++;
                String[] parts = line.split(",");

                if (parts.length >= 8) {
                    try {
                        String roomNumber = parts[1].trim();
                        String type = parts[2].trim();
                        boolean isCheckout = "1".equals(parts[3].trim());
                        boolean isStay = "1".equals(parts[4].trim());
                        boolean isDND = "1".equals(parts[5].trim());
                        int priority = Integer.parseInt(parts[6].trim());
                        int building = Integer.parseInt(parts[7].trim());

                        rooms.add(new AppRoom(roomNumber, type, isCheckout, isStay, isDND, priority, building));
                    } catch (NumberFormatException e) {
                        outputArea.append("警告: " + lineNumber + "行目のデータをスキップ（数値変換エラー）\n");
                    }
                } else {
                    outputArea.append("警告: " + lineNumber + "行目のデータをスキップ（カラム数不足）\n");
                }
            }
        }

        return rooms;
    }

    private Map<String, Integer> readCleaningDataFromFile(File file) throws IOException {
        Map<String, Integer> cleaningData = new HashMap<>();
        String extension = file.getName().toLowerCase();

        try (FileInputStream fis = new FileInputStream(file)) {
            Workbook workbook;

            if (extension.endsWith(".xlsx")) {
                workbook = new XSSFWorkbook(fis);
            } else if (extension.endsWith(".xls")) {
                workbook = new HSSFWorkbook(fis);
            } else {
                throw new IOException("サポートされないファイル形式です");
            }

            Sheet sheet = workbook.getSheetAt(0);

            for (Row row : sheet) {
                Cell keyCell = row.getCell(0);
                Cell valueCell = row.getCell(1);

                if (keyCell != null && valueCell != null) {
                    String key = getCellValueAsString(keyCell);
                    String valueStr = getCellValueAsString(valueCell);

                    try {
                        int value = Integer.parseInt(valueStr);
                        cleaningData.put(key, value);
                    } catch (NumberFormatException e) {
                        // 数値でない場合はスキップ
                    }
                }
            }

            workbook.close();
        }

        return cleaningData;
    }

    private String getCellValueAsString(Cell cell) {
        if (cell == null) return "";

        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                return String.valueOf((int) cell.getNumericCellValue());
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            default:
                return "";
        }
    }

    private void checkEnableProcessButton() {
        boolean canProcess = loadedRooms != null && !loadedRooms.isEmpty() &&
                loadedStaff != null && !loadedStaff.isEmpty();
        processButton.setEnabled(canProcess);

        if (canProcess) {
            statusLabel.setText("最適化実行の準備ができました");
        }
    }

    private void processAssignments() {
        if (loadedRooms == null || loadedStaff == null) {
            JOptionPane.showMessageDialog(this, "必要なデータが読み込まれていません");
            return;
        }

        outputArea.append("\n=== 最適化処理開始 ===\n");
        progressBar.setValue(0);
        progressBar.setMaximum(100);

        SwingWorker<ProcessingResult, Integer> worker = new SwingWorker<ProcessingResult, Integer>() {
            @Override
            protected ProcessingResult doInBackground() throws Exception {
                long startTime = System.currentTimeMillis();

                ProcessingResult result = new ProcessingResult();
                result.allRooms = new ArrayList<>(loadedRooms);

                // スタッフマップの作成
                publish(10);
                for (AppStaff s : loadedStaff) {
                    result.staffMap.put(s.name, new AppStaff(s.name, s.shift, s.isAvailable));
                }

                // 清掃データの適用
                if (cleaningTimeData != null) {
                    result.cleaningData = new HashMap<>(cleaningTimeData);
                }

                // 最適化アルゴリズムの実行
                publish(30);
                performOptimizedAssignment(loadedRooms, new ArrayList<>(result.staffMap.values()), result);

                // スコア計算
                publish(80);
                result.optimizationScore = calculateOptimizationScore(result);

                result.processingTime = System.currentTimeMillis() - startTime;
                publish(100);

                return result;
            }

            @Override
            protected void process(List<Integer> chunks) {
                for (Integer value : chunks) {
                    progressBar.setValue(value);
                }
            }

            @Override
            protected void done() {
                try {
                    lastResult = get();
                    displayResults(lastResult);

                    saveButton.setEnabled(true);
                    editButton.setEnabled(true);
                    enhancedEditButton.setEnabled(true);

                    statusLabel.setText(String.format("最適化完了 (%.2f秒)",
                            lastResult.processingTime / 1000.0));

                } catch (Exception e) {
                    outputArea.append("処理エラー: " + e.getMessage() + "\n");
                    e.printStackTrace();
                }
            }
        };

        worker.execute();
    }

    private void performOptimizedAssignment(List<AppRoom> rooms, List<AppStaff> staff, ProcessingResult result) {
        // 優先度とフロアでソート
        rooms.sort(Comparator.comparingInt((AppRoom r) -> r.priority)
                .thenComparing(r -> r.floor));

        // 利用可能なスタッフのみ
        List<AppStaff> availableStaff = staff.stream()
                .filter(s -> s.isAvailable)
                .collect(Collectors.toList());

        if (availableStaff.isEmpty()) {
            result.unassignedRooms.addAll(rooms);
            return;
        }

        // フロアごとの部屋をグループ化
        Map<String, List<AppRoom>> roomsByFloor = rooms.stream()
                .collect(Collectors.groupingBy(r -> r.floor));

        // 各フロアに最適なスタッフを割り当て
        for (Map.Entry<String, List<AppRoom>> entry : roomsByFloor.entrySet()) {
            String floor = entry.getKey();
            List<AppRoom> floorRooms = entry.getValue();

            // このフロアに最適なスタッフを選択
            List<AppStaff> floorStaff = selectStaffForFloor(availableStaff, floor, floorRooms.size());

            // 部屋を均等に割り当て
            int staffIndex = 0;
            for (AppRoom room : floorRooms) {
                if (room.isDND && !room.isCheckout) {
                    result.unassignedRooms.add(room);
                    continue;
                }

                if (floorStaff.isEmpty()) {
                    result.unassignedRooms.add(room);
                    continue;
                }

                AppStaff assignedStaff = floorStaff.get(staffIndex % floorStaff.size());

                // 容量チェック
                if (assignedStaff.currentLoad >= assignedStaff.maxCapacity) {
                    // 別のスタッフを探す
                    boolean assigned = false;
                    for (AppStaff altStaff : floorStaff) {
                        if (altStaff.currentLoad < altStaff.maxCapacity) {
                            assignedStaff = altStaff;
                            assigned = true;
                            break;
                        }
                    }
                    if (!assigned) {
                        result.unassignedRooms.add(room);
                        continue;
                    }
                }

                if (!result.assignments.containsKey(assignedStaff.name)) {
                    result.assignments.put(assignedStaff.name, new ArrayList<>());
                }

                result.assignments.get(assignedStaff.name).add(room);
                assignedStaff.assignedRooms.add(room.roomNumber);
                assignedStaff.currentLoad++;
                assignedStaff.preferredFloors.add(floor);

                staffIndex++;
            }
        }

        // フロアごとのスタッフ数を計算
        result.floorStaffCount = calculateFloorStaffCount(result.assignments);
    }

    private List<AppStaff> selectStaffForFloor(List<AppStaff> availableStaff, String floor, int roomCount) {
        // 必要なスタッフ数を計算
        int requiredStaff = Math.max(1, roomCount / 12);

        // フロアの経験があるスタッフを優先
        List<AppStaff> preferredStaff = availableStaff.stream()
                .filter(s -> s.preferredFloors.contains(floor))
                .limit(requiredStaff)
                .collect(Collectors.toList());

        // 不足分を他のスタッフから補充
        if (preferredStaff.size() < requiredStaff) {
            List<AppStaff> additionalStaff = availableStaff.stream()
                    .filter(s -> !preferredStaff.contains(s))
                    .filter(s -> s.currentLoad < s.maxCapacity)
                    .limit(requiredStaff - preferredStaff.size())
                    .collect(Collectors.toList());
            preferredStaff.addAll(additionalStaff);
        }

        return preferredStaff.isEmpty() ? availableStaff : preferredStaff;
    }

    private Map<String, Integer> calculateFloorStaffCount(Map<String, List<AppRoom>> assignments) {
        Map<String, Set<String>> floorStaffMap = new HashMap<>();

        for (Map.Entry<String, List<AppRoom>> entry : assignments.entrySet()) {
            String staffName = entry.getKey();
            for (AppRoom room : entry.getValue()) {
                floorStaffMap.computeIfAbsent(room.floor, k -> new HashSet<>()).add(staffName);
            }
        }

        Map<String, Integer> result = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : floorStaffMap.entrySet()) {
            result.put(entry.getKey(), entry.getValue().size());
        }

        return result;
    }

    private double calculateOptimizationScore(ProcessingResult result) {
        double score = 100.0;

        // 未割当ペナルティ
        double unassignedPenalty = (double) result.unassignedRooms.size() / result.allRooms.size() * 30;
        score -= unassignedPenalty;

        // 負荷バランスペナルティ
        if (!result.assignments.isEmpty()) {
            List<Integer> loads = result.assignments.values().stream()
                    .map(List::size)
                    .collect(Collectors.toList());
            double avgLoad = loads.stream().mapToInt(Integer::intValue).average().orElse(0);
            double variance = loads.stream()
                    .mapToDouble(l -> Math.pow(l - avgLoad, 2))
                    .average().orElse(0);
            double balancePenalty = Math.sqrt(variance) * 2;
            score -= Math.min(balancePenalty, 20);
        }

        // フロア効率ボーナス
        for (AppStaff staff : result.staffMap.values()) {
            if (staff.preferredFloors.size() == 1) {
                score += 2; // 単一フロア担当ボーナス
            }
        }

        return Math.max(0, Math.min(100, score));
    }

    private void displayResults(ProcessingResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== 割り当て結果 ===\n");
        sb.append(String.format("処理時間: %.2f秒\n", result.processingTime / 1000.0));
        sb.append(String.format("最適化スコア: %.1f/100\n\n", result.optimizationScore));

        // 統計情報
        int totalRooms = result.allRooms.size();
        int assignedRooms = result.assignments.values().stream()
                .mapToInt(List::size).sum();
        int unassignedRooms = result.unassignedRooms.size();

        sb.append(String.format("総部屋数: %d\n", totalRooms));
        sb.append(String.format("割当済: %d (%.1f%%)\n", assignedRooms,
                (double)assignedRooms/totalRooms*100));
        sb.append(String.format("未割当: %d\n\n", unassignedRooms));

        // スタッフ別詳細
        sb.append("=== スタッフ別割当 ===\n");
        for (Map.Entry<String, List<AppRoom>> entry : result.assignments.entrySet()) {
            String staffName = entry.getKey();
            List<AppRoom> rooms = entry.getValue();
            AppStaff staff = result.staffMap.get(staffName);

            int totalTime = rooms.stream().mapToInt(r -> r.estimatedTime).sum();

            sb.append(String.format("%s (%s): %d部屋, 推定%d分\n",
                    staffName, staff.shift, rooms.size(), totalTime));

            // フロア別内訳（正しい順序でソート）
            Map<String, Long> floorCount = rooms.stream()
                    .collect(Collectors.groupingBy(r -> r.floor, Collectors.counting()));
            sb.append("  フロア: ");
            floorCount.entrySet().stream()
                    .sorted((e1, e2) -> compareFloors(e1.getKey(), e2.getKey()))
                    .forEach(entry2 -> sb.append(entry2.getKey() + "階(" + entry2.getValue() + ") "));
            sb.append("\n");
        }

        // 未割当部屋の詳細（本館→別館、階数順にソート）
        if (!result.unassignedRooms.isEmpty()) {
            sb.append("\n=== 未割当部屋 ===\n");
            result.unassignedRooms.stream()
                    .sorted((r1, r2) -> {
                        // まず階数でソート
                        int floorCompare = compareFloors(r1.floor, r2.floor);
                        if (floorCompare != 0) return floorCompare;
                        // 同じ階なら部屋番号でソート
                        return Integer.compare(Integer.parseInt(r1.roomNumber), Integer.parseInt(r2.roomNumber));
                    })
                    .forEach(room -> sb.append(String.format("  %s (%s階) - 理由: %s\n",
                            room.roomNumber, room.floor,
                            room.isDND ? "DND" : "スタッフ不足")));
        }

        // フロア別スタッフ配置（本館→別館、階数順にソート）
        sb.append("\n=== フロア別スタッフ配置 ===\n");
        result.floorStaffCount.entrySet().stream()
                .sorted((e1, e2) -> compareFloors(e1.getKey(), e2.getKey()))
                .forEach(entry -> sb.append(String.format("%s階: %d人\n", entry.getKey(), entry.getValue())));

        outputArea.append(sb.toString());
        outputArea.setCaretPosition(outputArea.getDocument().getLength());
    }

    /**
     * 階数の比較メソッド（本館→別館、階数順）
     */
    private int compareFloors(String floor1, String floor2) {
        boolean isAnnex1 = floor1.startsWith("別館");
        boolean isAnnex2 = floor2.startsWith("別館");

        // 本館と別館の場合
        if (isAnnex1 != isAnnex2) {
            return isAnnex1 ? 1 : -1; // 本館が先
        }

        // 両方とも本館の場合
        if (!isAnnex1 && !isAnnex2) {
            int floorNum1 = Integer.parseInt(floor1);
            int floorNum2 = Integer.parseInt(floor2);
            return Integer.compare(floorNum1, floorNum2);
        }

        // 両方とも別館の場合
        if (isAnnex1 && isAnnex2) {
            int annexFloor1 = Integer.parseInt(floor1.substring(2)); // "別館2" → 2
            int annexFloor2 = Integer.parseInt(floor2.substring(2)); // "別館3" → 3
            return Integer.compare(annexFloor1, annexFloor2);
        }

        return 0;
    }

    private void saveResults() {
        JFileChooser fileChooser = new JFileChooser();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
        String defaultFileName = "assignment_result_" + sdf.format(new Date());

        fileChooser.setSelectedFile(new File(defaultFileName));
        fileChooser.setFileFilter(new FileNameExtensionFilter("テキストファイル", "txt"));

        int result = fileChooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            if (!file.getName().endsWith(".txt")) {
                file = new File(file.getAbsolutePath() + ".txt");
            }

            try (PrintWriter writer = new PrintWriter(
                    new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                writer.write(outputArea.getText());

                // 詳細データも追加
                writer.write("\n\n=== 詳細データ ===\n");
                for (Map.Entry<String, List<AppRoom>> entry : lastResult.assignments.entrySet()) {
                    writer.write("\n" + entry.getKey() + ":\n");
                    for (AppRoom room : entry.getValue()) {
                        writer.write("  " + room.toString() + "\n");
                    }
                }

                JOptionPane.showMessageDialog(this,
                        "結果を保存しました: " + file.getName(),
                        "保存完了",
                        JOptionPane.INFORMATION_MESSAGE);

            } catch (IOException e) {
                JOptionPane.showMessageDialog(this,
                        "保存エラー: " + e.getMessage(),
                        "エラー",
                        JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void openEditor() {
        if (lastResult != null) {
            AssignmentEditorGUI.showEditor(lastResult);
        }
    }

    private void openEnhancedEditor() {
        if (lastResult != null) {
            EnhancedAssignmentEditorGUI editor = new EnhancedAssignmentEditorGUI();
            editor.showEnhancedEditor(lastResult);
        }
    }

    private void clearAll() {
        int result = JOptionPane.showConfirmDialog(this,
                "すべてのデータをクリアしますか？",
                "確認",
                JOptionPane.YES_NO_OPTION);

        if (result == JOptionPane.YES_OPTION) {
            loadedRooms = null;
            loadedStaff = null;
            cleaningTimeData = null;
            lastResult = null;
            roomDataFile = null;
            shiftDataFile = null;
            cleaningDataFile = null;

            outputArea.setText("");
            outputArea.append("=== データをクリアしました ===\n\n");

            processButton.setEnabled(false);
            saveButton.setEnabled(false);
            editButton.setEnabled(false);
            enhancedEditButton.setEnabled(false);

            statusLabel.setText("ファイルを読み込んでください");
            progressBar.setValue(0);
        }
    }

    private void showAboutDialog() {
        String message = "ホテル清掃管理システム v2.0\n\n" +
                "このシステムは、ホテルの清掃業務を\n" +
                "効率的に管理・最適化するためのツールです。\n\n" +
                "機能:\n" +
                "- CSVおよびExcelファイルの読み込み\n" +
                "- スタッフへの部屋割り当て最適化\n" +
                "- 割り当て結果の編集と保存\n" +
                "- 詳細な統計情報の表示\n\n" +
                "© 2025 Hotel Management System";

        JOptionPane.showMessageDialog(this, message, "About", JOptionPane.INFORMATION_MESSAGE);
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        SwingUtilities.invokeLater(() -> {
            RoomAssignmentApplication app = new RoomAssignmentApplication();
            app.setVisible(true);
        });
    }
}