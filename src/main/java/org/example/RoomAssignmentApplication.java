package org.example;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.Date;
import java.util.Calendar;
import java.util.stream.Collectors;

/**
 * ホテル清掃管理システム - メインアプリケーション
 * 大浴場清掃スタッフ手動選択機能付き
 * ★修正: 制限値入力エラー修正版 + 通常清掃部屋割り振り機能追加
 * ★データベース対応版
 * ★★ECO部屋数認識修正版
 */
public class RoomAssignmentApplication extends JFrame {
    private static final Logger LOGGER = Logger.getLogger(RoomAssignmentApplication.class.getName());

    private JFrame parentFrame;
    private JTextArea logArea;
    private JButton selectRoomFileButton;
    private JButton selectShiftFileButton;
    private JButton selectEcoDataButton;
    private JButton selectDateButton;
    private JButton brokenRoomSettingsButton;
    private JButton pendingRoomSettingsButton;
    private JButton processButton;
    private JButton viewResultsButton;

    private File selectedRoomFile;
    private File selectedShiftFile;
    private File selectedEcoDataFile;
    private LocalDate selectedDate;
    private ProcessingResult lastResult;

    // ★★追加: 階別の手動割り当て（任意機能）
    private Map<String, ManualFloorAssignmentDialog.StaffManual> lastManualLayout = null;
    private Map<Integer, ManualFloorAssignmentDialog.FloorInv> pendingManualInventory = new HashMap<>();

    private Set<String> selectedBrokenRoomsForCleaning = new HashSet<>();

    // ★★追加: 割り振り画面で「スタッフ選択に戻る」が押されたか
    private boolean distributionBackRequested = false;

    /**
     * ★拡張: ポイント制限管理用データ構造(大浴場清掃フラグ追加)
     */
    public static class StaffPointConstraint {
        public final String staffId;
        public final String staffName;
        public final ConstraintType constraintType;
        public final BuildingAssignment buildingAssignment;
        public final double upperLimit;
        public final double lowerMinLimit;
        public final double lowerMaxLimit;
        public final boolean isBathCleaningStaff;
        // ★追加: 備品発注担当フラグ（大浴場清掃とは排他）
        public final boolean isSuppliesOrderStaff;

        public StaffPointConstraint(String staffId, String staffName,
                                    ConstraintType constraintType,
                                    BuildingAssignment buildingAssignment,
                                    double upperLimit, double lowerMinLimit, double lowerMaxLimit,
                                    boolean isBathCleaningStaff) {
            this(staffId, staffName, constraintType, buildingAssignment,
                    upperLimit, lowerMinLimit, lowerMaxLimit, isBathCleaningStaff, false);
        }

        // ★追加: 備品発注フラグ付きコンストラクタ（マスター）
        public StaffPointConstraint(String staffId, String staffName,
                                    ConstraintType constraintType,
                                    BuildingAssignment buildingAssignment,
                                    double upperLimit, double lowerMinLimit, double lowerMaxLimit,
                                    boolean isBathCleaningStaff,
                                    boolean isSuppliesOrderStaff) {
            this.staffId = staffId;
            this.staffName = staffName;
            this.constraintType = constraintType;
            this.buildingAssignment = buildingAssignment;
            this.upperLimit = upperLimit;
            this.lowerMinLimit = lowerMinLimit;
            this.lowerMaxLimit = lowerMaxLimit;
            this.isBathCleaningStaff = isBathCleaningStaff;
            this.isSuppliesOrderStaff = isSuppliesOrderStaff;
        }

        public StaffPointConstraint(String staffId, String staffName,
                                    ConstraintType constraintType,
                                    BuildingAssignment buildingAssignment,
                                    double upperLimit, double lowerMinLimit, double lowerMaxLimit) {
            this(staffId, staffName, constraintType, buildingAssignment,
                    upperLimit, lowerMinLimit, lowerMaxLimit, false, false);
        }

        public enum ConstraintType {
            NONE("制限なし"),
            UPPER_LIMIT("故障者制限"),
            LOWER_RANGE("リライアンス用");

            public final String displayName;
            ConstraintType(String displayName) {
                this.displayName = displayName;
            }
        }

        public enum BuildingAssignment {
            BOTH("両方"),
            MAIN_ONLY("本館のみ"),
            ANNEX_ONLY("別館のみ");

            public final String displayName;
            BuildingAssignment(String displayName) {
                this.displayName = displayName;
            }
        }

        public String getConstraintDisplay() {
            switch (constraintType) {
                case UPPER_LIMIT:
                    return String.format("上限%.1fP", upperLimit);
                case LOWER_RANGE:
                    return String.format("下限%.1f〜%.1fP", lowerMinLimit, lowerMaxLimit);
                default:
                    return "制限なし";
            }
        }

        public String getBathCleaningDisplay() {
            return isBathCleaningStaff ? "担当" : "";
        }

        // ★追加: 備品発注担当の表示
        public String getSuppliesOrderDisplay() {
            return isSuppliesOrderStaff ? "担当" : "";
        }
    }

    public static class ProcessingResult {
        public final FileProcessor.CleaningData cleaningDataObj;
        public final AdaptiveRoomOptimizer.OptimizationResult optimizationResult;
        public final Map<String, List<FileProcessor.Room>> detailedRoomAssignments;

        // ★追加: 複数解対応
        public final AdaptiveRoomOptimizer.MultiOptimizationResult multiOptimizationResult;
        public final int totalSolutionCount;
        private int currentSolutionIndex;

        // 従来のコンストラクタ（単一解用）
        public ProcessingResult(FileProcessor.CleaningData cleaningData,
                                AdaptiveRoomOptimizer.OptimizationResult optimizationResult,
                                Map<String, List<FileProcessor.Room>> detailedAssignments) {
            this.cleaningDataObj = cleaningData;
            this.optimizationResult = optimizationResult;
            this.detailedRoomAssignments = detailedAssignments;
            this.multiOptimizationResult = null;
            this.totalSolutionCount = 1;
            this.currentSolutionIndex = 0;
        }

        // ★追加: 複数解用コンストラクタ
        public ProcessingResult(FileProcessor.CleaningData cleaningData,
                                AdaptiveRoomOptimizer.MultiOptimizationResult multiResult,
                                Map<String, List<FileProcessor.Room>> detailedAssignments) {
            this.cleaningDataObj = cleaningData;
            this.multiOptimizationResult = multiResult;
            this.optimizationResult = multiResult.toSingleResult(); // 従来互換用
            this.detailedRoomAssignments = detailedAssignments;
            this.totalSolutionCount = multiResult.totalSolutionCount;
            this.currentSolutionIndex = 0;
        }

        /**
         * ★追加: 複数解があるかどうか
         */
        public boolean hasMultipleSolutions() {
            return multiOptimizationResult != null && totalSolutionCount > 1;
        }

        /**
         * ★追加: 現在の解のインデックスを取得
         */
        public int getCurrentSolutionIndex() {
            return currentSolutionIndex;
        }

        /**
         * ★追加: 現在の解のインデックスを設定
         */
        public void setCurrentSolutionIndex(int index) {
            if (index >= 0 && index < totalSolutionCount) {
                this.currentSolutionIndex = index;
            }
        }

        /**
         * ★追加: 指定インデックスの解を取得
         */
        public AdaptiveRoomOptimizer.OptimizationResult getOptimizationResult(int index) {
            if (multiOptimizationResult != null) {
                return multiOptimizationResult.toSingleResult(index);
            }
            return optimizationResult;
        }

        /**
         * ★追加: 現在選択中の解を取得
         */
        public AdaptiveRoomOptimizer.OptimizationResult getCurrentOptimizationResult() {
            return getOptimizationResult(currentSolutionIndex);
        }
    }

    public RoomAssignmentApplication() {
        this.parentFrame = this;
        initializeGUI();
    }

    private void initializeGUI() {
        setTitle("ホテル清掃管理システム - 部屋数制限機能付き");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        JPanel mainPanel = new JPanel(new BorderLayout());
        JPanel filePanel = createFileSelectionPanel();
        mainPanel.add(filePanel, BorderLayout.NORTH);

        logArea = new JTextArea(20, 60);
        logArea.setEditable(false);
        logArea.setFont(new Font("MS Gothic", Font.PLAIN, 12));
        JScrollPane logScrollPane = new JScrollPane(logArea);
        logScrollPane.setBorder(BorderFactory.createTitledBorder("処理ログ"));
        mainPanel.add(logScrollPane, BorderLayout.CENTER);

        JPanel buttonPanel = createButtonPanel();
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        add(mainPanel);

        updateButtonStates();
        setSize(800, 700);
        setLocationRelativeTo(null);

        appendLog("ホテル清掃管理システムを開始しました。");
        appendLog("部屋数制限機能が有効です。");
    }

    private JPanel createFileSelectionPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        panel.setBorder(BorderFactory.createTitledBorder("ファイル選択"));

        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("部屋データファイル:"), gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        selectRoomFileButton = new JButton("CSVファイルを選択...");
        selectRoomFileButton.addActionListener(this::selectRoomFile);
        panel.add(selectRoomFileButton, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("シフトファイル:"), gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        selectShiftFileButton = new JButton("Excelファイルを選択...");
        selectShiftFileButton.addActionListener(this::selectShiftFile);
        panel.add(selectShiftFileButton, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("エコデータ(オプション):"), gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        selectEcoDataButton = new JButton("データベースを選択...");
        selectEcoDataButton.setToolTipText("EcoRoomClean.pyで作成されたhotel_cleaning.dbデータベースを選択してください");
        selectEcoDataButton.addActionListener(e -> selectEcoDatabase());
        panel.add(selectEcoDataButton, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("対象日:"), gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        selectDateButton = new JButton("日付を選択...");
        selectDateButton.addActionListener(this::selectDate);
        panel.add(selectDateButton, gbc);

        return panel;
    }

    private JPanel createButtonPanel() {
        JPanel panel = new JPanel(new FlowLayout());

        brokenRoomSettingsButton = new JButton("故障部屋設定");
        brokenRoomSettingsButton.setFont(new Font("MS Gothic", Font.BOLD, 12));
        brokenRoomSettingsButton.setPreferredSize(new Dimension(120, 35));
        brokenRoomSettingsButton.setBackground(new Color(255, 140, 0));
        brokenRoomSettingsButton.setForeground(Color.BLACK);
        brokenRoomSettingsButton.addActionListener(this::openBrokenRoomSettings);
        brokenRoomSettingsButton.setEnabled(false);
        panel.add(brokenRoomSettingsButton);

        pendingRoomSettingsButton = new JButton("未チェックイン設定");
        pendingRoomSettingsButton.setFont(new Font("MS Gothic", Font.BOLD, 12));
        pendingRoomSettingsButton.setPreferredSize(new Dimension(150, 35));
        pendingRoomSettingsButton.setBackground(new Color(70, 130, 180));
        pendingRoomSettingsButton.setForeground(Color.WHITE);
        pendingRoomSettingsButton.addActionListener(this::openPendingRoomSettings);
        pendingRoomSettingsButton.setEnabled(false);
        panel.add(pendingRoomSettingsButton);

        processButton = new JButton("処理実行");
        processButton.setFont(new Font("MS Gothic", Font.BOLD, 14));
        processButton.setPreferredSize(new Dimension(120, 35));
        processButton.addActionListener(this::processData);

        viewResultsButton = new JButton("結果表示");
        viewResultsButton.setFont(new Font("MS Gothic", Font.BOLD, 14));
        viewResultsButton.setPreferredSize(new Dimension(120, 35));
        viewResultsButton.addActionListener(this::viewResults);
        viewResultsButton.setEnabled(false);

        panel.add(processButton);
        panel.add(viewResultsButton);

        return panel;
    }

    private void openBrokenRoomSettings(ActionEvent e) {
        if (selectedRoomFile == null) {
            JOptionPane.showMessageDialog(this,
                    "部屋データファイルを先に選択してください。",
                    "エラー", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            BrokenRoomSelectionDialog dialog = new BrokenRoomSelectionDialog(this, selectedRoomFile);
            dialog.setVisible(true);

            if (dialog.getDialogResult()) {
                selectedBrokenRoomsForCleaning = dialog.getSelectedRoomsForCleaning();

                appendLog("故障部屋設定が完了しました:");
                appendLog("  清掃対象に追加する故障部屋: " + selectedBrokenRoomsForCleaning.size() + "室");

                if (!selectedBrokenRoomsForCleaning.isEmpty()) {
                    for (String roomNumber : selectedBrokenRoomsForCleaning) {
                        appendLog("    " + roomNumber);
                    }
                }

                brokenRoomSettingsButton.setBackground(new Color(34, 139, 34));
                brokenRoomSettingsButton.setForeground(Color.BLACK);
                brokenRoomSettingsButton.setText("故障部屋設定済み");
            }

        } catch (Exception ex) {
            LOGGER.severe("故障部屋設定ダイアログでエラー: " + ex.getMessage());
            JOptionPane.showMessageDialog(this,
                    "故障部屋設定でエラーが発生しました: " + ex.getMessage(),
                    "エラー", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void selectRoomFile(ActionEvent e) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new javax.swing.filechooser.FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().toLowerCase().endsWith(".csv");
            }

            @Override
            public String getDescription() {
                return "CSVファイル (*.csv)";
            }
        });

        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            selectedRoomFile = fileChooser.getSelectedFile();
            selectRoomFileButton.setText(selectedRoomFile.getName());
            appendLog("部屋データファイルを選択しました: " + selectedRoomFile.getName());

            brokenRoomSettingsButton.setEnabled(true);
            brokenRoomSettingsButton.setText("故障部屋設定");
            brokenRoomSettingsButton.setBackground(new Color(255, 140, 0));
            selectedBrokenRoomsForCleaning.clear();

            updateButtonStates();
        }
    }

    private void selectShiftFile(ActionEvent e) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new javax.swing.filechooser.FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().toLowerCase().endsWith(".xlsx");
            }

            @Override
            public String getDescription() {
                return "Excelファイル (*.xlsx)";
            }
        });

        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            selectedShiftFile = fileChooser.getSelectedFile();
            selectShiftFileButton.setText(selectedShiftFile.getName());
            appendLog("シフトファイルを選択しました: " + selectedShiftFile.getName());
            updateButtonStates();
        }
    }

    /**
     * ★データベース対応: エコ清掃データベースファイルを選択
     */
    private void selectEcoDatabase() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("エコ清掃データベースを選択");

        // SQLiteデータベースファイルのフィルタを設定
        fileChooser.setFileFilter(new javax.swing.filechooser.FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().toLowerCase().endsWith(".db");
            }

            @Override
            public String getDescription() {
                return "SQLiteデータベース (*.db)";
            }
        });

        // デフォルトで hotel_cleaning.db を探す
        File currentDir = new File(System.getProperty("user.dir"));
        File defaultDb = new File(currentDir, "hotel_cleaning.db");
        if (defaultDb.exists()) {
            fileChooser.setSelectedFile(defaultDb);
        }

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            selectedEcoDataFile = fileChooser.getSelectedFile();
            selectEcoDataButton.setText(selectedEcoDataFile.getName());
            appendLog("エコ清掃データベースを選択しました: " + selectedEcoDataFile.getName());
            updateButtonStates();
        }
    }

    private void openPendingRoomSettings(ActionEvent e) {
        if (selectedRoomFile == null || selectedEcoDataFile == null) {
            JOptionPane.showMessageDialog(this,
                    "部屋データファイルとエコデータベースを先に選択してください。",
                    "エラー", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            PendingRoomSelectionDialog dialog = new PendingRoomSelectionDialog(this, selectedRoomFile);
            dialog.setVisible(true);

            if (dialog.getDialogResult()) {
                Map<String, PendingRoomSelectionDialog.PendingRoomInfo> pendingData = dialog.getPendingRoomData();

                long changedCount = pendingData.values().stream()
                        .filter(info -> !"1".equals(info.newStatus) || info.isEco)
                        .count();

                appendLog("未チェックイン部屋設定が完了しました:");
                appendLog("  設定変更された部屋: " + changedCount + "室 / " + pendingData.size() + "室");

                if (changedCount > 0) {
                    pendingData.values().stream()
                            .filter(info -> !"1".equals(info.newStatus) || info.isEco)
                            .forEach(info -> appendLog("    " + info.roomNumber
                                    + " → " + info.getStatusDisplay()
                                    + (info.isEco ? ", エコ清掃" : "")));
                }

                if (changedCount > 0) {
                    pendingRoomSettingsButton.setBackground(new Color(34, 139, 34));
                    pendingRoomSettingsButton.setForeground(Color.WHITE);
                    pendingRoomSettingsButton.setText("未チェックイン設定済み");
                } else {
                    pendingRoomSettingsButton.setBackground(new Color(70, 130, 180));
                    pendingRoomSettingsButton.setForeground(Color.WHITE);
                    pendingRoomSettingsButton.setText("未チェックイン設定");
                }
            }

        } catch (Exception ex) {
            LOGGER.severe("未チェックイン設定ダイアログでエラー: " + ex.getMessage());
            JOptionPane.showMessageDialog(this,
                    "未チェックイン設定でエラーが発生しました: " + ex.getMessage(),
                    "エラー", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void selectDate(ActionEvent e) {
        Date currentDate = selectedDate != null ?
                java.sql.Date.valueOf(selectedDate) : new Date();

        SpinnerDateModel model = new SpinnerDateModel(currentDate, null, null, Calendar.DAY_OF_MONTH);
        JSpinner spinner = new JSpinner(model);
        JSpinner.DateEditor editor = new JSpinner.DateEditor(spinner, "yyyy年MM月dd日");
        spinner.setEditor(editor);
        spinner.setFont(new Font("MS Gothic", Font.PLAIN, 14));

        JButton todayButton = new JButton("今日の日付");
        todayButton.addActionListener(evt -> spinner.setValue(new Date()));

        JPanel messagePanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();

        gbc.gridx = 0; gbc.gridy = 0;
        gbc.insets = new Insets(5, 5, 10, 5);
        gbc.anchor = GridBagConstraints.WEST;
        messagePanel.add(new JLabel("対象日を選択してください:"), gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        messagePanel.add(spinner, gbc);

        gbc.gridx = 0; gbc.gridy = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.insets = new Insets(10, 5, 5, 5);
        messagePanel.add(todayButton, gbc);

        int result = JOptionPane.showConfirmDialog(
                this,
                messagePanel,
                "日付選択",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
        );

        if (result == JOptionPane.OK_OPTION) {
            Date selectedDateValue = (Date) spinner.getValue();
            selectedDate = selectedDateValue.toInstant()
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate();

            selectDateButton.setText(selectedDate.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")));
            appendLog("対象日を設定しました: " + selectedDate.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")));
            updateButtonStates();
        }
    }

    private void updateButtonStates() {
        boolean canProcess = selectedRoomFile != null && selectedShiftFile != null && selectedDate != null;
        processButton.setEnabled(canProcess);
        brokenRoomSettingsButton.setEnabled(selectedRoomFile != null);
        pendingRoomSettingsButton.setEnabled(selectedRoomFile != null && selectedEcoDataFile != null);
    }

    private void processData(ActionEvent e) {
        if (selectedRoomFile == null || selectedShiftFile == null || selectedDate == null) {
            JOptionPane.showMessageDialog(this, "必要なファイルと日付を選択してください。",
                    "エラー", JOptionPane.ERROR_MESSAGE);
            return;
        }

        processButton.setEnabled(false);
        processButton.setText("処理中...");

        try {
            executeProcessingWithWorker();
        } catch (Exception ex) {
            String errorMsg = "処理中にエラーが発生しました: " + ex.getMessage();
            appendLog("エラー: " + errorMsg);
            LOGGER.severe(errorMsg);
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this, errorMsg, "エラー", JOptionPane.ERROR_MESSAGE);
            processButton.setEnabled(true);
            processButton.setText("処理実行");
        }
    }

    /**
     * 処理実行（ダイアログ操作はEDT、最適化はバックグラウンド）
     */
    private void executeProcessingWithWorker() {
        // === Phase 1: データ読み込み・ダイアログ操作（EDT上で実行） ===
        appendLog("\n=== 処理開始 ===");

        if (selectedEcoDataFile != null) {
            System.setProperty("ecoDataFile", selectedEcoDataFile.getAbsolutePath());
            appendLog("エコ清掃データベースを設定: " + selectedEcoDataFile.getName());
            appendLog("  → " + selectedEcoDataFile.getAbsolutePath());
        }

        if (!selectedBrokenRoomsForCleaning.isEmpty()) {
            String brokenRoomsStr = String.join(",", selectedBrokenRoomsForCleaning);
            System.setProperty("selectedBrokenRooms", brokenRoomsStr);
            appendLog("清掃対象故障部屋を設定: " + selectedBrokenRoomsForCleaning.size() + "室");
        } else {
            System.clearProperty("selectedBrokenRooms");
        }

        // 1. 部屋データの読み込み
        appendLog("部屋データを読み込み中...");
        final FileProcessor.CleaningData cleaningData = FileProcessor.processRoomFile(selectedRoomFile, selectedDate);
        appendLog(String.format("部屋データ読み込み完了: 本館%d室, 別館%d室, エコ%d室, 故障%d室",
                cleaningData.totalMainRooms, cleaningData.totalAnnexRooms,
                cleaningData.ecoRooms.size(), cleaningData.totalBrokenRooms));

        // エコ清掃警告チェック
        if (!cleaningData.ecoWarnings.isEmpty()) {
            appendLog("\n【エコ清掃警告】");
            appendLog("エコ清掃情報に以下の問題が検出されました:");
            for (String warning : cleaningData.ecoWarnings) {
                appendLog("  " + warning);
            }
            appendLog("");

            StringBuilder warningMessage = new StringBuilder();
            warningMessage.append("エコ清掃情報に ").append(cleaningData.ecoWarnings.size())
                    .append(" 件の警告があります:\n\n");

            int displayCount = Math.min(10, cleaningData.ecoWarnings.size());
            for (int i = 0; i < displayCount; i++) {
                warningMessage.append("• ").append(cleaningData.ecoWarnings.get(i)).append("\n");
            }

            if (cleaningData.ecoWarnings.size() > 10) {
                warningMessage.append("\n... 他 ").append(cleaningData.ecoWarnings.size() - 10)
                        .append(" 件の警告があります。\n詳細はログをご確認ください。");
            }

            int result = JOptionPane.showConfirmDialog(
                    this,
                    warningMessage.toString(),
                    "エコ清掃情報の警告",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );

            if (result != JOptionPane.OK_OPTION) {
                appendLog("処理がユーザーによってキャンセルされました。");
                processButton.setEnabled(true);
                processButton.setText("処理実行");
                return;
            }
        }

        // 2. スタッフデータの読み込み
        appendLog("スタッフデータを読み込み中...");
        final List<FileProcessor.Staff> availableStaff = FileProcessor.getAvailableStaff(selectedShiftFile, selectedDate);
        appendLog(String.format("利用可能スタッフ: %d人", availableStaff.size()));

        if (availableStaff.isEmpty()) {
            appendLog("警告: 利用可能なスタッフが見つかりませんでした。");
            JOptionPane.showMessageDialog(this, "指定日に利用可能なスタッフが見つかりませんでした。",
                    "警告", JOptionPane.WARNING_MESSAGE);
            processButton.setEnabled(true);
            processButton.setText("処理実行");
            return;
        }

        // 3. フロア情報の構築
        appendLog("フロア情報を構築中...");
        final List<AdaptiveRoomOptimizer.FloorInfo> floors = buildFloorInfo(cleaningData);
        appendLog(String.format("フロア情報構築完了: %d階層", floors.size()));

        // 4. 大浴場清掃タイプの選択
        final AdaptiveRoomOptimizer.BathCleaningType bathType = selectBathCleaningType();

        // ★★変更: 5と5.5をループ化（割り振り画面の「スタッフ選択に戻る」でやり直し可能）
        List<StaffPointConstraint> tmpConstraints;
        Map<String, NormalRoomDistributionDialog.StaffDistribution> tmpDistribution;
        while (true) {
            // 5. ポイント制限の設定(大浴場清掃スタッフ選択機能付き)
            tmpConstraints = selectStaffPointConstraintsWithBathCleaning(availableStaff, bathType);

            // 5.5. ★新機能: 通常清掃部屋の事前割り振り設定
            appendLog("通常清掃部屋の割り振りパターンを設定中...");
            tmpDistribution = selectNormalRoomDistribution(
                    cleaningData.totalMainRooms, cleaningData.totalAnnexRooms,
                    tmpConstraints, availableStaff, bathType, floors);

            if (!distributionBackRequested) {
                break;  // OKまたはキャンセル → 従来どおり次へ進む
            }
            appendLog("「スタッフ選択に戻る」が選択されました。ポイント制限設定からやり直します。");
        }
        final List<StaffPointConstraint> pointConstraints = tmpConstraints;
        final Map<String, NormalRoomDistributionDialog.StaffDistribution> roomDistribution = tmpDistribution;

        // 大浴場清掃スタッフの集計
        final long bathStaffCount = pointConstraints.stream()
                .mapToLong(constraint -> constraint.isBathCleaningStaff ? 1 : 0)
                .sum();

        // ★追加: 備品発注担当スタッフの集計
        final long suppliesStaffCount = pointConstraints.stream()
                .mapToLong(constraint -> constraint.isSuppliesOrderStaff ? 1 : 0)
                .sum();

        if (!pointConstraints.isEmpty()) {
            appendLog(String.format("ポイント制限が設定されました: %d人", pointConstraints.size()));
            appendLog(String.format("大浴場清掃スタッフ: %d人", bathStaffCount));
            appendLog(String.format("備品発注担当スタッフ: %d人", suppliesStaffCount));
            pointConstraints.forEach(constraint -> {
                String bathInfo = constraint.isBathCleaningStaff ? " [大浴場清掃担当]" : "";
                String suppliesInfo = constraint.isSuppliesOrderStaff ? " [備品発注担当]" : "";
                appendLog(String.format("  %s: %s, %s%s%s",
                        constraint.staffName,
                        constraint.getConstraintDisplay(),
                        constraint.buildingAssignment.displayName,
                        bathInfo,
                        suppliesInfo));
            });
        } else {
            appendLog("ポイント制限は設定されませんでした(全員均等配分)");
        }

        // ★追加: 部屋割り振り結果の表示
        if (roomDistribution != null && !roomDistribution.isEmpty()) {
            appendLog("通常清掃部屋割り振り設定:");
            roomDistribution.values().forEach(dist -> {
                String floorInfo = (dist.preferredFloors != null && !dist.preferredFloors.isEmpty())
                        ? " [指定階: " + dist.getPreferredFloorsText() + "]" : "";
                String linenInfo = dist.isLinenClosetCleaning
                        ? String.format(" [リネン庫: %d階分]", dist.linenClosetFloorCount) : "";
                appendLog(String.format("  %s: %d部屋 (%s)%s%s",
                        dist.staffName, dist.assignedRooms, dist.buildingAssignment, floorInfo, linenInfo));
            });
        }

        // ★★追加: 階別の手動割り当てが使われた場合は、CP-SATを通さず手動レイアウトで確定
        if (lastManualLayout != null && !lastManualLayout.isEmpty()) {
            appendLog("階別の手動割り当てを使用します（CP-SAT自動配分はスキップ）");

            final int manualTotalRooms = cleaningData.totalMainRooms + cleaningData.totalAnnexRooms;
            final AdaptiveRoomOptimizer.AdaptiveLoadConfig manualConfig =
                    createAdaptiveConfigWithPointConstraints(
                            availableStaff, manualTotalRooms,
                            cleaningData.totalMainRooms, cleaningData.totalAnnexRooms,
                            bathType, pointConstraints, roomDistribution);

            List<AdaptiveRoomOptimizer.StaffAssignment> manualAssignments =
                    ManualFloorAssignmentDialog.buildStaffAssignments(
                            lastManualLayout, cleaningData, availableStaff, roomDistribution, bathType);

            Map<String, List<FileProcessor.Room>> manualDetailed = new HashMap<>();
            try {
                RoomNumberAssigner assigner = new RoomNumberAssigner(cleaningData);
                manualDetailed = assigner.assignDetailedRooms(manualAssignments);
                appendLog("手動割り当ての部屋番号確定が完了しました。");
            } catch (Exception ex) {
                appendLog("手動割り当ての部屋番号確定に失敗しましたが続行します: " + ex.getMessage());
            }

            AdaptiveRoomOptimizer.OptimizationResult manualResult =
                    new AdaptiveRoomOptimizer.OptimizationResult(manualAssignments, manualConfig, selectedDate);
            lastResult = new ProcessingResult(cleaningData, manualResult, manualDetailed);
            viewResultsButton.setEnabled(true);

            appendLog("\n=== 処理完了（階別の手動割り当て） ===");
            JOptionPane.showMessageDialog(RoomAssignmentApplication.this,
                    "階別の手動割り当てで処理が完了しました。\n結果表示ボタンで「清掃割り当て調整」を確認してください。",
                    "完了", JOptionPane.INFORMATION_MESSAGE);
            return;  // ★ 以降のCP-SAT/SwingWorkerパスへは進まない
        }

        // 6. 適応型設定の作成
        appendLog("最適化設定を作成中...");
        final int totalRooms = cleaningData.totalMainRooms + cleaningData.totalAnnexRooms;
        final AdaptiveRoomOptimizer.AdaptiveLoadConfig config = createAdaptiveConfigWithPointConstraints(
                availableStaff, totalRooms, cleaningData.totalMainRooms, cleaningData.totalAnnexRooms,
                bathType, pointConstraints, roomDistribution);

        // ★★追加: リネン庫設定の事前バリデーション
        // リネン庫フロアのハード制約が原理的に成立しない設定の場合は、最適化を実行せずに中止する
        {
            int mainFloorCount = 0;
            int annexFloorCount = 0;
            for (AdaptiveRoomOptimizer.FloorInfo fi : floors) {
                if (fi.isMainBuilding) mainFloorCount++;
                else annexFloorCount++;
            }
            List<String> linenErrors = RoomAssignmentCPSATOptimizer.validateLinenClosetFeasibility(
                    config, mainFloorCount, annexFloorCount);
            if (!linenErrors.isEmpty()) {
                appendLog("\n【リネン庫設定エラー】以下の問題により処理を中止しました:");
                for (String err : linenErrors) {
                    appendLog("  " + err);
                }
                JOptionPane.showMessageDialog(this,
                        "リネン庫フロアの割り当てが成立しません:\n\n" +
                                String.join("\n", linenErrors) +
                                "\n\n通常清掃部屋割り振り設定を見直してください。",
                        "リネン庫設定エラー", JOptionPane.ERROR_MESSAGE);
                processButton.setEnabled(true);
                processButton.setText("処理実行");
                return;
            }
        }

        // === Phase 2: 最適化実行（バックグラウンドスレッド） ===
        appendLog("最適化を実行中（最大7つの解を探索）...");

        final AdaptiveRoomOptimizer.AdaptiveOptimizer optimizer =
                new AdaptiveRoomOptimizer.AdaptiveOptimizer(floors, config);

        new SwingWorker<AdaptiveRoomOptimizer.MultiOptimizationResult, String>() {
            @Override
            protected AdaptiveRoomOptimizer.MultiOptimizationResult doInBackground() {
                // 進捗リスナーを設定（LOGGERの出力をGUIに転送）
                RoomAssignmentCPSATOptimizer.setProgressListener(msg -> publish(msg));
                return optimizer.optimizeMultiple(selectedDate);
            }

            @Override
            protected void process(List<String> chunks) {
                // バックグラウンドからのログメッセージをGUIに表示
                for (String msg : chunks) {
                    logArea.append(msg + "\n");
                    logArea.setCaretPosition(logArea.getDocument().getLength());
                }
            }

            @Override
            protected void done() {
                // 進捗リスナーを解除
                RoomAssignmentCPSATOptimizer.setProgressListener(null);

                try {
                    AdaptiveRoomOptimizer.MultiOptimizationResult multiResult = get();
                    appendLog(String.format("最適化完了: %d個の解が見つかりました", multiResult.totalSolutionCount));

                    // 8. 部屋番号の割り当て
                    Map<String, List<FileProcessor.Room>> detailedAssignments = new HashMap<>();
                    try {
                        RoomNumberAssigner assigner = new RoomNumberAssigner(cleaningData);
                        detailedAssignments = assigner.assignDetailedRooms(multiResult.getAssignments(0));
                        appendLog("部屋番号の詳細割り当てが完了しました。");
                    } catch (Exception ex) {
                        appendLog("部屋番号の詳細割り当てに失敗しましたが、処理を続行します: " + ex.getMessage());
                    }

                    // 9. 結果を保存
                    lastResult = new ProcessingResult(cleaningData, multiResult, detailedAssignments);
                    viewResultsButton.setEnabled(true);

                    // 10. 結果サマリーの表示
                    appendLog("\n=== 処理完了 ===");
                    multiResult.printDetailedSummary();

                    appendLog("\n結果表示ボタンで詳細を確認できます。");
                    if (multiResult.hasMultipleSolutions()) {
                        appendLog(String.format("★ %d個の解が生成されました。結果画面で切り替えできます。", multiResult.totalSolutionCount));
                    }

                    long selectedBrokenRoomsForCleaningCount = cleaningData.brokenRooms.stream()
                            .filter(r -> cleaningData.mainRooms.contains(r) || cleaningData.annexRooms.contains(r))
                            .count();

                    String solutionInfo = multiResult.hasMultipleSolutions()
                            ? String.format("\n生成された解の数: %d個（結果画面で切り替え可能）", multiResult.totalSolutionCount)
                            : "";

                    JOptionPane.showMessageDialog(RoomAssignmentApplication.this,
                            String.format("処理が完了しました。\n\n利用可能スタッフ: %d人\n清掃対象部屋: %d室\n" +
                                            "制限設定スタッフ: %d人\n大浴場清掃スタッフ: %d人\n故障部屋(清掃対象): %d室%s\n\n結果表示ボタンで詳細を確認してください。",
                                    availableStaff.size(), totalRooms, pointConstraints.size(), bathStaffCount,
                                    selectedBrokenRoomsForCleaningCount, solutionInfo),
                            "処理完了", JOptionPane.INFORMATION_MESSAGE);

                } catch (Exception ex) {
                    String errorMsg = "最適化中にエラーが発生しました: " + ex.getMessage();
                    appendLog("エラー: " + errorMsg);
                    LOGGER.severe(errorMsg);
                    ex.printStackTrace();
                    JOptionPane.showMessageDialog(RoomAssignmentApplication.this,
                            errorMsg, "エラー", JOptionPane.ERROR_MESSAGE);
                } finally {
                    processButton.setEnabled(true);
                    processButton.setText("処理実行");
                }
            }
        }.execute();
    }
    /**
     * ★新規追加: roomDistribution を含む AdaptiveLoadConfig を作成
     */
    private AdaptiveRoomOptimizer.AdaptiveLoadConfig createAdaptiveConfigWithPointConstraints(
            List<FileProcessor.Staff> availableStaff,
            int totalRooms,
            int mainBuildingRooms,
            int annexBuildingRooms,
            AdaptiveRoomOptimizer.BathCleaningType bathType,
            List<StaffPointConstraint> pointConstraints,
            Map<String, NormalRoomDistributionDialog.StaffDistribution> roomDistribution) {  // ★パラメータ追加

        return AdaptiveRoomOptimizer.AdaptiveLoadConfig.createAdaptiveConfigWithBathSelection(
                availableStaff,
                totalRooms,
                mainBuildingRooms,
                annexBuildingRooms,
                bathType,
                pointConstraints,
                roomDistribution);
    }

    /**
     * ★新機能: 通常清掃部屋の事前割り振り設定ダイアログ
     * ★★修正: FileProcessorから直接部屋データを読み込み、6区分を集計（ECO対応）
     */
    private Map<String, NormalRoomDistributionDialog.StaffDistribution> selectNormalRoomDistribution(
            int mainRooms, int annexRooms,
            List<StaffPointConstraint> pointConstraints,
            List<FileProcessor.Staff> availableStaff,
            AdaptiveRoomOptimizer.BathCleaningType bathType,
            List<AdaptiveRoomOptimizer.FloorInfo> floors) {

        // ★★追加: 手動レイアウトをリセット（今回未使用なら null のまま＝従来フロー）
        this.lastManualLayout = null;
        this.distributionBackRequested = false;  // ★★追加: 戻るフラグをリセット

        // ★修正: ECO部屋はCP-SATが自動配分するため、通常室（シングル等・ツイン）のみ集計
        int totalMainSingleRooms = 0;
        int totalMainTwinRooms = 0;
        int totalAnnexSingleRooms = 0;
        int totalAnnexTwinRooms = 0;

        try {
            FileProcessor.CleaningData cleaningData = FileProcessor.processRoomFile(selectedRoomFile, selectedDate);

            for (FileProcessor.Room room : cleaningData.mainRooms) {
                if (room.isEcoClean) continue; // ECOはスキップ（CP-SATが担当）
                if ("T".equals(room.roomType)) {
                    totalMainTwinRooms++;
                } else {
                    totalMainSingleRooms++;
                }
            }

            for (FileProcessor.Room room : cleaningData.annexRooms) {
                if (room.isEcoClean) continue; // ECOはスキップ（CP-SATが担当）
                if ("T".equals(room.roomType)) {
                    totalAnnexTwinRooms++;
                } else {
                    totalAnnexSingleRooms++;
                }
            }

            appendLog(String.format("部屋タイプ集計: 本館(S:%d, T:%d), 別館(S:%d, T:%d) ※ECOはCP-SAT自動配分",
                    totalMainSingleRooms, totalMainTwinRooms,
                    totalAnnexSingleRooms, totalAnnexTwinRooms));

            // ★★追加: 階別在庫を構築（手動割り当ての階プルダウン・検証用）
            this.pendingManualInventory = ManualFloorAssignmentDialog.buildInventory(cleaningData);

        } catch (Exception e) {
            totalMainSingleRooms = mainRooms;
            totalAnnexSingleRooms = annexRooms;
            appendLog("警告: 部屋タイプ集計中にエラーが発生しました。全てシングル等として扱います。");
            LOGGER.warning("部屋タイプ集計エラー: " + e.getMessage());
        }

        List<String> staffNamesList = availableStaff.stream()
                .map(s -> s.name)
                .collect(Collectors.toList());

        // ECO配分はCP-SATが自動実施するため、通常室のみを渡す
        NormalRoomDistributionDialog dialog = new NormalRoomDistributionDialog(
                parentFrame,
                totalMainSingleRooms,
                totalMainTwinRooms,
                totalAnnexSingleRooms,
                totalAnnexTwinRooms,
                pointConstraints,
                staffNamesList,
                bathType);

        // ★★追加: 利用可能フロア番号を設定（指定階バリデーション用）
        if (floors != null) {
            Set<Integer> mainFloors = new HashSet<>();
            Set<Integer> annexFloors = new HashSet<>();
            for (AdaptiveRoomOptimizer.FloorInfo fi : floors) {
                if (fi.isMainBuilding) {
                    mainFloors.add(fi.floorNumber);
                } else {
                    annexFloors.add(fi.floorNumber);
                }
            }
            dialog.setAvailableFloors(mainFloors, annexFloors);
        }

        // ★★追加: 階別在庫を手動割り当てダイアログへ渡す（階プルダウンの選択肢になる）
        dialog.setManualInventory(this.pendingManualInventory);

        // ★★追加: 初回フローでは「スタッフ選択に戻る」ボタンを表示する
        dialog.showBackToStaffSelectionButton();

        dialog.setVisible(true);

        // ★★追加: 「スタッフ選択に戻る」が押されたかを記録
        this.distributionBackRequested = dialog.isBackToStaffSelection();

        if (dialog.getDialogResult()) {
            appendLog("通常清掃部屋の割り振りパターンが設定されました");
            this.lastManualLayout = dialog.getManualLayout();  // ★★追加（未使用なら null）
            return dialog.getCurrentDistribution();
        } else {
            if (distributionBackRequested) {
                appendLog("通常清掃部屋の割り振り設定からスタッフ選択に戻ります");
            } else {
                appendLog("通常清掃部屋の割り振り設定がキャンセルされました");
            }
            return null;
        }
    }

    /**
     * ★新規追加: 担当者変更ダイアログ
     * 在籍スタッフ全員をチェックボックス一覧で表示し、選択されたスタッフのリストを返す。
     * ★変更: 初期チェックは currentSelection（現在選択中のスタッフ）に含まれる人を ON にする。
     *         currentSelection が null/空なら全て未チェックになる。
     * キャンセル時はnullを返す。0人でOK押下時は警告を表示し、ダイアログは閉じない。
     */
    private List<FileProcessor.Staff> showStaffSelectionDialog(java.awt.Window parent,
                                                               List<FileProcessor.Staff> currentSelection) {
        if (selectedShiftFile == null) {
            JOptionPane.showMessageDialog(parent,
                    "シフトファイルが選択されていません。",
                    "エラー", JOptionPane.ERROR_MESSAGE);
            return null;
        }

        // 在籍スタッフ全員を読み込み（シフト値は問わない）
        final List<FileProcessor.Staff> enrolledStaff =
                FileProcessor.getAllEnrolledStaff(selectedShiftFile);

        if (enrolledStaff.isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                    "在籍スタッフが見つかりませんでした。",
                    "エラー", JOptionPane.ERROR_MESSAGE);
            return null;
        }

        // 親ウィンドウに応じてJDialogを生成
        final JDialog dialog;
        if (parent instanceof Frame) {
            dialog = new JDialog((Frame) parent, "担当者変更", true);
        } else if (parent instanceof Dialog) {
            dialog = new JDialog((Dialog) parent, "担当者変更", true);
        } else {
            dialog = new JDialog((Frame) null, "担当者変更", true);
        }
        dialog.setLayout(new BorderLayout());
        dialog.setSize(380, 520);
        dialog.setLocationRelativeTo(parent);

        // 説明パネル
        JPanel infoPanel = new JPanel(new GridLayout(2, 1));
        infoPanel.setBorder(BorderFactory.createTitledBorder("担当者選択"));
        infoPanel.add(new JLabel(" 担当するスタッフにチェックを入れてください"));
        infoPanel.add(new JLabel(" 在籍スタッフ: " + enrolledStaff.size() + "名"));

        // チェックボックス一覧（★変更: 現在選択中のスタッフを初期チェック）
        JPanel checkPanel = new JPanel();
        checkPanel.setLayout(new BoxLayout(checkPanel, BoxLayout.Y_AXIS));
        checkPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        // ★追加: 現在選択中スタッフのid集合（初期チェック判定用）。null/空なら全て未チェック。
        final java.util.Set<String> selectedIds = new java.util.HashSet<>();
        if (currentSelection != null) {
            for (FileProcessor.Staff s : currentSelection) {
                selectedIds.add(s.id);
            }
        }

        final List<JCheckBox> checkBoxes = new ArrayList<>();
        for (FileProcessor.Staff staff : enrolledStaff) {
            JCheckBox cb = new JCheckBox(staff.name);
            cb.setSelected(selectedIds.contains(staff.id)); // ★変更: 現在選択中ならON
            checkBoxes.add(cb);
            checkPanel.add(cb);
        }

        JScrollPane scrollPane = new JScrollPane(checkPanel);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);

        // ボタンパネル
        JPanel buttonPanel = new JPanel(new FlowLayout());

        // OK結果の格納用（キャンセル時は空のまま、OK時に選択分が入る）
        final List<FileProcessor.Staff> result = new ArrayList<>();

        // ★追加: 全てのチェックを外すボタン
        JButton uncheckAllButton = new JButton("全て外す");
        uncheckAllButton.addActionListener(e -> {
            for (JCheckBox cb : checkBoxes) {
                cb.setSelected(false);
            }
        });

        JButton okButton = new JButton("OK");
        okButton.addActionListener(e -> {
            List<FileProcessor.Staff> selected = new ArrayList<>();
            for (int i = 0; i < checkBoxes.size(); i++) {
                if (checkBoxes.get(i).isSelected()) {
                    selected.add(enrolledStaff.get(i));
                }
            }
            if (selected.isEmpty()) {
                // 仕様: 0人で警告を出してダイアログは閉じない
                JOptionPane.showMessageDialog(dialog,
                        "1人以上選択してください。",
                        "警告", JOptionPane.WARNING_MESSAGE);
                return;
            }
            result.clear();
            result.addAll(selected);
            dialog.dispose();
        });

        JButton cancelButton = new JButton("キャンセル");
        cancelButton.addActionListener(e -> dialog.dispose());

        buttonPanel.add(uncheckAllButton);
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);

        dialog.add(infoPanel, BorderLayout.NORTH);
        dialog.add(scrollPane, BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        dialog.setVisible(true);

        // resultが空 = キャンセル、それ以外 = OK確定
        return result.isEmpty() ? null : result;
    }

    /**
     * ★修正: ポイント制限・大浴場清掃スタッフ選択(制限値入力エラー修正版)
     */
    private List<StaffPointConstraint> selectStaffPointConstraintsWithBathCleaning(
            List<FileProcessor.Staff> availableStaff, AdaptiveRoomOptimizer.BathCleaningType bathType) {
        List<StaffPointConstraint> constraints = new ArrayList<>();

        JDialog dialog = new JDialog(parentFrame, "ポイント制限・大浴場清掃・備品発注設定", true);
        dialog.setLayout(new BorderLayout());
        dialog.setSize(1000, 520);
        dialog.setLocationRelativeTo(parentFrame);

        JPanel infoPanel = new JPanel(new GridLayout(6, 1));
        infoPanel.setBorder(BorderFactory.createTitledBorder("設定方法"));
        infoPanel.add(new JLabel("• 制限タイプ:「制限なし」、「故障者制限」、「業者制限」から選択"));
        infoPanel.add(new JLabel("• 故障者制限:正の数値(例:18 = 最大18部屋まで)"));
        infoPanel.add(new JLabel("• 業者制限:最小-最大(例:20-25 = 20から25部屋)"));
        infoPanel.add(new JLabel("• 建物指定:「両方」、「本館のみ」、「別館のみ」から選択"));
        infoPanel.add(new JLabel("• 大浴場清掃:チェックを入れて下さい"));
        infoPanel.add(new JLabel("• 備品発注:チェックを入れて下さい(大浴場清掃とは併用不可。通常清掃から-6室)"));

        JPanel staffPanel = new JPanel(new BorderLayout());
        staffPanel.setBorder(BorderFactory.createTitledBorder("当日スタッフ数 : (" + availableStaff.size() + "名)"));

        String[] columnNames = {"スタッフ名", "制限タイプ", "制限値", "建物指定", "大浴場清掃", "備品発注", "設定状況"};
        DefaultTableModel tableModel = new DefaultTableModel(columnNames, 0);

        for (FileProcessor.Staff staff : availableStaff) {
            Object[] row = {staff.name, "制限なし", "", "両方", false, false, "未設定"};
            tableModel.addRow(row);
        }

        JTable table = new JTable(tableModel);
        table.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        String[] constraintTypes = {"制限なし", "故障者制限", "業者制限"};
        JComboBox<String> constraintCombo = new JComboBox<>(constraintTypes);
        table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(constraintCombo));

        String[] buildings = {"両方", "本館のみ", "別館のみ"};
        JComboBox<String> buildingCombo = new JComboBox<>(buildings);
        table.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(buildingCombo));

        table.getColumnModel().getColumn(4).setCellRenderer(new TableCellRenderer() {
            private final JCheckBox checkBox = new JCheckBox();

            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                           boolean isSelected, boolean hasFocus, int row, int column) {
                checkBox.setSelected(value != null && (Boolean) value);
                checkBox.setHorizontalAlignment(JLabel.CENTER);

                if (isSelected) {
                    checkBox.setBackground(table.getSelectionBackground());
                } else {
                    checkBox.setBackground(table.getBackground());
                }
                checkBox.setOpaque(true);

                return checkBox;
            }
        });

        table.getColumnModel().getColumn(4).setCellEditor(new DefaultCellEditor(new JCheckBox()) {
            @Override
            public boolean stopCellEditing() {
                boolean selected = (Boolean) getCellEditorValue();
                int editingRow = table.getEditingRow();

                if (editingRow >= 0) {
                    if (selected) {
                        // ★大浴場清掃と備品発注は排他: 備品発注をオフにする
                        tableModel.setValueAt(false, editingRow, 5);
                        tableModel.setValueAt("大浴場清掃担当", editingRow, 6);
                        tableModel.setValueAt("本館のみ", editingRow, 3);
                    } else {
                        String constraintType = (String) tableModel.getValueAt(editingRow, 1);
                        if ("制限なし".equals(constraintType)) {
                            boolean isSupplies = Boolean.TRUE.equals(tableModel.getValueAt(editingRow, 5));
                            tableModel.setValueAt(isSupplies ? "備品発注担当" : "制限なし", editingRow, 6);
                        }
                        tableModel.setValueAt("両方", editingRow, 3);
                    }
                }

                return super.stopCellEditing();
            }
        });

        // ★追加: 備品発注チェックボックス列（インデックス5）のレンダラー
        table.getColumnModel().getColumn(5).setCellRenderer(new TableCellRenderer() {
            private final JCheckBox checkBox = new JCheckBox();

            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                           boolean isSelected, boolean hasFocus, int row, int column) {
                checkBox.setSelected(value != null && (Boolean) value);
                checkBox.setHorizontalAlignment(JLabel.CENTER);

                if (isSelected) {
                    checkBox.setBackground(table.getSelectionBackground());
                } else {
                    checkBox.setBackground(table.getBackground());
                }
                checkBox.setOpaque(true);

                return checkBox;
            }
        });

        // ★追加: 備品発注チェックボックス列（インデックス5）のエディター
        table.getColumnModel().getColumn(5).setCellEditor(new DefaultCellEditor(new JCheckBox()) {
            @Override
            public boolean stopCellEditing() {
                boolean selected = (Boolean) getCellEditorValue();
                int editingRow = table.getEditingRow();

                if (editingRow >= 0) {
                    if (selected) {
                        // ★大浴場清掃と備品発注は排他: 大浴場清掃をオフにする
                        tableModel.setValueAt(false, editingRow, 4);
                        tableModel.setValueAt("備品発注担当", editingRow, 6);
                        // ★備品発注は建物指定をユーザー設定のまま維持（大浴場清掃のような本館固定はしない）
                    } else {
                        String constraintType = (String) tableModel.getValueAt(editingRow, 1);
                        if ("制限なし".equals(constraintType)) {
                            tableModel.setValueAt("制限なし", editingRow, 6);
                        }
                    }
                }

                return super.stopCellEditing();
            }
        });

        table.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(new JTextField()) {
            private int editingRow = -1;

            @Override
            public Component getTableCellEditorComponent(JTable table, Object value,
                                                         boolean isSelected, int row, int column) {
                editingRow = row;
                return super.getTableCellEditorComponent(table, value, isSelected, row, column);
            }

            @Override
            public boolean stopCellEditing() {
                String value = (String) getCellEditorValue();

                if (editingRow >= 0 && editingRow < tableModel.getRowCount()) {
                    String constraintType = (String) tableModel.getValueAt(editingRow, 1);

                    if (!value.trim().isEmpty()) {
                        try {
                            if ("故障者制限".equals(constraintType)) {
                                double limit = Double.parseDouble(value.trim());
                                if (limit > 0) {
                                    tableModel.setValueAt("設定済み(故障者" + limit + "P)", editingRow, 6);
                                } else {
                                    tableModel.setValueAt("正の数値を入力してください", editingRow, 6);
                                }
                            } else if ("業者制限".equals(constraintType)) {
                                if (value.contains("〜") || value.contains("-") || value.contains("~")) {
                                    String[] parts;
                                    if (value.contains("〜")) {
                                        parts = value.split("〜");
                                    } else if (value.contains("~")) {
                                        parts = value.split("~");
                                    } else {
                                        parts = value.split("-");
                                    }

                                    if (parts.length == 2) {
                                        double min = Double.parseDouble(parts[0].trim());
                                        double max = Double.parseDouble(parts[1].trim());
                                        if (min > 0 && max > min) {
                                            tableModel.setValueAt("設定済み(業者" + min + "〜" + max + "P)", editingRow, 6);
                                        } else {
                                            tableModel.setValueAt("最小値 < 最大値で入力してください", editingRow, 6);
                                        }
                                    } else {
                                        tableModel.setValueAt("範囲形式で入力してください(例:20.0〜25.0)", editingRow, 6);
                                    }
                                } else {
                                    tableModel.setValueAt("範囲形式で入力してください(例:20.0〜25.0)", editingRow, 6);
                                }
                            }
                        } catch (NumberFormatException e) {
                            tableModel.setValueAt("数値エラー", editingRow, 6);
                        }
                    } else if ("制限なし".equals(constraintType)) {
                        boolean isBathStaff = Boolean.TRUE.equals(tableModel.getValueAt(editingRow, 4));
                        boolean isSupplies = Boolean.TRUE.equals(tableModel.getValueAt(editingRow, 5));
                        String status = isBathStaff ? "大浴場清掃担当" : (isSupplies ? "備品発注担当" : "制限なし");
                        tableModel.setValueAt(status, editingRow, 6);
                    }
                }

                return super.stopCellEditing();
            }
        });

        JScrollPane scrollPane = new JScrollPane(table);
        staffPanel.add(scrollPane, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout());

        AtomicBoolean confirmed = new AtomicBoolean(false);

        // ★新規追加: 担当者変更ボタン（在籍スタッフから手動で選び直し）
        JButton staffChangeButton = new JButton("担当者変更");
        staffChangeButton.addActionListener(e -> {
            List<FileProcessor.Staff> newStaff = showStaffSelectionDialog(dialog, availableStaff);
            if (newStaff == null) {
                // キャンセルされた、またはエラー時は何もしない（既存のスタッフリストを維持）
                return;
            }

            // availableStaffをインプレース更新（参照は維持したまま中身を入れ替える）
            availableStaff.clear();
            availableStaff.addAll(newStaff);

            // テーブルを再構築（全行クリア → 新スタッフで再構築、設定はすべてリセット）
            tableModel.setRowCount(0);
            for (FileProcessor.Staff staff : availableStaff) {
                Object[] row = {staff.name, "制限なし", "", "両方", false, false, "未設定"};
                tableModel.addRow(row);
            }

            // タイトルバーの人数表示を更新
            staffPanel.setBorder(BorderFactory.createTitledBorder(
                    "当日スタッフ数 : (" + availableStaff.size() + "名)"));
            staffPanel.repaint();

            appendLog("担当者を変更しました: " + availableStaff.size() + "名");
        });

        JButton okButton = new JButton("設定完了");
        okButton.addActionListener(e -> {
            confirmed.set(true);
            dialog.dispose();
        });

        JButton cancelButton = new JButton("キャンセル");
        cancelButton.addActionListener(e -> dialog.dispose());

        buttonPanel.add(staffChangeButton);
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);

        dialog.add(infoPanel, BorderLayout.NORTH);
        dialog.add(staffPanel, BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        dialog.setVisible(true);

        if (confirmed.get()) {
            // ★追加: 手動選択(設定完了)の結果を元のシフト表へ反映
            //   選択=空欄 / 非選択かつ空欄="/" / 非選択かつ文字あり=維持
            if (selectedShiftFile != null && selectedDate != null) {
                try {
                    boolean applied = FileProcessor.applyManualSelectionToShiftFile(
                            selectedShiftFile, selectedDate, availableStaff);
                    if (applied) {
                        appendLog("シフト表へ担当者選択を反映しました（選択=空欄／非選択='/'／記入済みは維持）。");
                    } else {
                        appendLog("対象日の列が見つからなかったため、シフト表への反映を中止しました。");
                    }
                } catch (Exception ex) {
                    appendLog("シフト表への反映に失敗しました: " + ex.getMessage());
                    JOptionPane.showMessageDialog(this,
                            "シフト表への反映に失敗しました。\n" +
                                    "ファイルがExcel等で開かれていないかご確認ください。\n\n" + ex.getMessage(),
                            "反映エラー", JOptionPane.ERROR_MESSAGE);
                }
            }

            for (int i = 0; i < tableModel.getRowCount(); i++) {
                String staffName = (String) tableModel.getValueAt(i, 0);
                String constraintType = (String) tableModel.getValueAt(i, 1);
                String constraintValue = (String) tableModel.getValueAt(i, 2);
                String buildingType = (String) tableModel.getValueAt(i, 3);
                Boolean isBathCleaning = (Boolean) tableModel.getValueAt(i, 4);
                Boolean isSuppliesOrder = (Boolean) tableModel.getValueAt(i, 5);

                if ((!"制限なし".equals(constraintType) && constraintValue != null && !constraintValue.trim().isEmpty()) ||
                        (isBathCleaning != null && isBathCleaning) ||
                        (isSuppliesOrder != null && isSuppliesOrder) ||
                        !"両方".equals(buildingType)) {

                    try {
                        String staffId = availableStaff.stream()
                                .filter(s -> s.name.equals(staffName))
                                .map(s -> s.id)
                                .findFirst()
                                .orElse(staffName);

                        StaffPointConstraint.ConstraintType cType;
                        StaffPointConstraint.BuildingAssignment bType;
                        double upperLimit = 0;
                        double lowerMin = 0;
                        double lowerMax = 0;

                        if ("故障者制限".equals(constraintType)) {
                            cType = StaffPointConstraint.ConstraintType.UPPER_LIMIT;
                            upperLimit = Double.parseDouble(constraintValue.trim());
                        } else if ("業者制限".equals(constraintType)) {
                            cType = StaffPointConstraint.ConstraintType.LOWER_RANGE;
                            String[] parts;
                            if (constraintValue.contains("〜")) {
                                parts = constraintValue.split("〜");
                            } else if (constraintValue.contains("~")) {
                                parts = constraintValue.split("~");
                            } else {
                                parts = constraintValue.split("-");
                            }
                            lowerMin = Double.parseDouble(parts[0].trim());
                            lowerMax = Double.parseDouble(parts[1].trim());
                        } else {
                            cType = StaffPointConstraint.ConstraintType.NONE;
                        }

                        if ("本館のみ".equals(buildingType)) {
                            bType = StaffPointConstraint.BuildingAssignment.MAIN_ONLY;
                        } else if ("別館のみ".equals(buildingType)) {
                            bType = StaffPointConstraint.BuildingAssignment.ANNEX_ONLY;
                        } else {
                            bType = StaffPointConstraint.BuildingAssignment.BOTH;
                        }

                        boolean bathCleaningFlag = isBathCleaning != null && isBathCleaning;
                        boolean suppliesOrderFlag = isSuppliesOrder != null && isSuppliesOrder;
                        // ★大浴場清掃と備品発注は排他（UIで制御済みだが念のため大浴場優先）
                        if (bathCleaningFlag) {
                            suppliesOrderFlag = false;
                        }

                        StaffPointConstraint constraint = new StaffPointConstraint(
                                staffId, staffName, cType, bType, upperLimit, lowerMin, lowerMax,
                                bathCleaningFlag, suppliesOrderFlag);
                        constraints.add(constraint);

                    } catch (NumberFormatException e) {
                        System.err.println("制限値解析エラー: " + staffName + " - " + constraintValue);
                    }
                }
            }
        }

        return constraints;
    }

    private AdaptiveRoomOptimizer.AdaptiveLoadConfig createAdaptiveConfigWithPointConstraints(
            List<FileProcessor.Staff> availableStaff,
            int totalRooms,
            int mainBuildingRooms,
            int annexBuildingRooms,
            AdaptiveRoomOptimizer.BathCleaningType bathType,
            List<StaffPointConstraint> pointConstraints) {

        return AdaptiveRoomOptimizer.AdaptiveLoadConfig.createAdaptiveConfigWithBathSelection(
                availableStaff,
                totalRooms,
                mainBuildingRooms,
                annexBuildingRooms,
                bathType,
                pointConstraints,
                null);  // roomDistributionなし（ECOはCP-SATが自動配分）
    }

    private AdaptiveRoomOptimizer.BathCleaningType selectBathCleaningType() {
        String[] options = {
                AdaptiveRoomOptimizer.BathCleaningType.NONE.displayName,
                AdaptiveRoomOptimizer.BathCleaningType.NORMAL.displayName,
                AdaptiveRoomOptimizer.BathCleaningType.WITH_DRAINING.displayName
        };

        int choice = JOptionPane.showOptionDialog(
                this,
                "今日は?:",
                "大浴場清掃設定",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[1]
        );

        switch (choice) {
            case 0: return AdaptiveRoomOptimizer.BathCleaningType.NONE;
            case 2: return AdaptiveRoomOptimizer.BathCleaningType.WITH_DRAINING;
            default: return AdaptiveRoomOptimizer.BathCleaningType.NORMAL;
        }
    }

    private List<AdaptiveRoomOptimizer.FloorInfo> buildFloorInfo(FileProcessor.CleaningData cleaningData) {
        Map<Integer, Map<String, Integer>> floorData = new HashMap<>();
        Map<Integer, Integer> ecoData = new HashMap<>();
        Map<Integer, Boolean> buildingData = new HashMap<>();
        Map<Integer, Integer> checkoutData = new HashMap<>();  // ★追加: アウト清掃(status=2)室数
        Map<Integer, Integer> stayoverData = new HashMap<>();  // ★追加: 連泊(status=3)室数

        // ★修正: 本館（エコ部屋を roomCounts から除外）
        for (FileProcessor.Room room : cleaningData.mainRooms) {
            int floor = room.floor;
            buildingData.put(floor, true);

            // ★追加: アウト/連泊カウント
            if ("2".equals(room.roomStatus)) {
                checkoutData.merge(floor, 1, Integer::sum);
            } else if ("3".equals(room.roomStatus)) {
                stayoverData.merge(floor, 1, Integer::sum);
            }

            if (room.isEcoClean) {
                // エコ部屋は ecoData のみにカウント
                ecoData.merge(floor, 1, Integer::sum);
            } else {
                // 通常清掃の部屋のみ roomCounts に追加
                floorData.computeIfAbsent(floor, k -> new HashMap<>())
                        .merge(room.roomType, 1, Integer::sum);
            }
        }

        // ★修正: 別館（エコ部屋を roomCounts から除外）
        for (FileProcessor.Room room : cleaningData.annexRooms) {
            int floor = room.floor;
            buildingData.put(floor, false);

            // ★追加: アウト/連泊カウント
            if ("2".equals(room.roomStatus)) {
                checkoutData.merge(floor, 1, Integer::sum);
            } else if ("3".equals(room.roomStatus)) {
                stayoverData.merge(floor, 1, Integer::sum);
            }

            if (room.isEcoClean) {
                // エコ部屋は ecoData のみにカウント
                ecoData.merge(floor, 1, Integer::sum);
            } else {
                // 通常清掃の部屋のみ roomCounts に追加
                floorData.computeIfAbsent(floor, k -> new HashMap<>())
                        .merge(room.roomType, 1, Integer::sum);
            }
        }

        List<AdaptiveRoomOptimizer.FloorInfo> floors = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, Integer>> entry : floorData.entrySet()) {
            int floor = entry.getKey();
            Map<String, Integer> roomCounts = entry.getValue();
            int ecoRooms = ecoData.getOrDefault(floor, 0);
            boolean isMainBuilding = buildingData.getOrDefault(floor, true);
            int checkoutRooms = checkoutData.getOrDefault(floor, 0);  // ★追加
            int stayoverRooms = stayoverData.getOrDefault(floor, 0);  // ★追加

            floors.add(new AdaptiveRoomOptimizer.FloorInfo(
                    floor, roomCounts, ecoRooms, isMainBuilding, checkoutRooms, stayoverRooms));
        }

        // ★追加: エコ部屋のみのフロアも追加
        for (Map.Entry<Integer, Integer> entry : ecoData.entrySet()) {
            int floor = entry.getKey();
            if (!floorData.containsKey(floor)) {
                boolean isMainBuilding = buildingData.getOrDefault(floor, true);
                int checkoutRooms = checkoutData.getOrDefault(floor, 0);  // ★追加
                int stayoverRooms = stayoverData.getOrDefault(floor, 0);  // ★追加
                floors.add(new AdaptiveRoomOptimizer.FloorInfo(
                        floor, new HashMap<>(), entry.getValue(), isMainBuilding, checkoutRooms, stayoverRooms));
            }
        }

        floors.sort(Comparator.comparingInt(f -> f.floorNumber));
        return floors;
    }

    private void viewResults(ActionEvent e) {
        if (lastResult == null) {
            JOptionPane.showMessageDialog(this, "表示する結果がありません。", "エラー", JOptionPane.ERROR_MESSAGE);
            return;
        }

        AssignmentEditorGUI.showEditor(lastResult);
    }

    private void appendLog(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                // デフォルトのルック&フィールを使用
            }

            new RoomAssignmentApplication().setVisible(true);
        });
    }
}