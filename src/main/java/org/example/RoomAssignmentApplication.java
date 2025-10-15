package org.example;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
    private JButton processButton;
    private JButton viewResultsButton;

    private File selectedRoomFile;
    private File selectedShiftFile;
    private File selectedEcoDataFile;
    private LocalDate selectedDate;
    private ProcessingResult lastResult;

    private Set<String> selectedBrokenRoomsForCleaning = new HashSet<>();

    /**
     * ★拡張: ポイント制限管理用データ構造（大浴場清掃フラグ追加）
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

        public StaffPointConstraint(String staffId, String staffName,
                                    ConstraintType constraintType,
                                    BuildingAssignment buildingAssignment,
                                    double upperLimit, double lowerMinLimit, double lowerMaxLimit,
                                    boolean isBathCleaningStaff) {
            this.staffId = staffId;
            this.staffName = staffName;
            this.constraintType = constraintType;
            this.buildingAssignment = buildingAssignment;
            this.upperLimit = upperLimit;
            this.lowerMinLimit = lowerMinLimit;
            this.lowerMaxLimit = lowerMaxLimit;
            this.isBathCleaningStaff = isBathCleaningStaff;
        }

        public StaffPointConstraint(String staffId, String staffName,
                                    ConstraintType constraintType,
                                    BuildingAssignment buildingAssignment,
                                    double upperLimit, double lowerMinLimit, double lowerMaxLimit) {
            this(staffId, staffName, constraintType, buildingAssignment,
                    upperLimit, lowerMinLimit, lowerMaxLimit, false);
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
    }

    public static class ProcessingResult {
        public final FileProcessor.CleaningData cleaningDataObj;
        public final AdaptiveRoomOptimizer.OptimizationResult optimizationResult;
        public final Map<String, List<FileProcessor.Room>> detailedRoomAssignments;

        public ProcessingResult(FileProcessor.CleaningData cleaningData,
                                AdaptiveRoomOptimizer.OptimizationResult optimizationResult,
                                Map<String, List<FileProcessor.Room>> detailedAssignments) {
            this.cleaningDataObj = cleaningData;
            this.optimizationResult = optimizationResult;
            this.detailedRoomAssignments = detailedAssignments;
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
        panel.add(new JLabel("エコデータ（オプション）:"), gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        selectEcoDataButton = new JButton("Excelファイルを選択...");
        selectEcoDataButton.addActionListener(this::selectEcoDataFile);
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

    private void selectEcoDataFile(ActionEvent e) {
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
            selectedEcoDataFile = fileChooser.getSelectedFile();
            selectEcoDataButton.setText(selectedEcoDataFile.getName());
            appendLog("エコデータファイルを選択しました: " + selectedEcoDataFile.getName());
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
    }

    private void processData(ActionEvent e) {
        if (selectedRoomFile == null || selectedShiftFile == null || selectedDate == null) {
            JOptionPane.showMessageDialog(this, "必要なファイルと日付を選択してください。",
                    "エラー", JOptionPane.ERROR_MESSAGE);
            return;
        }

        SwingUtilities.invokeLater(() -> {
            processButton.setEnabled(false);
            processButton.setText("処理中...");

            try {
                executeProcessing();
            } finally {
                processButton.setEnabled(true);
                processButton.setText("処理実行");
            }
        });
    }

    private void executeProcessing() {
        try {
            appendLog("\n=== 処理開始 ===");

            if (selectedEcoDataFile != null) {
                System.setProperty("ecoDataFile", selectedEcoDataFile.getAbsolutePath());
                appendLog("エコデータファイルを設定: " + selectedEcoDataFile.getName());
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
            FileProcessor.CleaningData cleaningData = FileProcessor.processRoomFile(selectedRoomFile);
            appendLog(String.format("部屋データ読み込み完了: 本館%d室, 別館%d室, エコ%d室, 故障%d室",
                    cleaningData.totalMainRooms, cleaningData.totalAnnexRooms,
                    cleaningData.ecoRooms.size(), cleaningData.totalBrokenRooms));

            // 2. スタッフデータの読み込み
            appendLog("スタッフデータを読み込み中...");
            List<FileProcessor.Staff> availableStaff = FileProcessor.getAvailableStaff(selectedShiftFile, selectedDate);
            appendLog(String.format("利用可能スタッフ: %d人", availableStaff.size()));

            if (availableStaff.isEmpty()) {
                appendLog("警告: 利用可能なスタッフが見つかりませんでした。");
                JOptionPane.showMessageDialog(this, "指定日に利用可能なスタッフが見つかりませんでした。",
                        "警告", JOptionPane.WARNING_MESSAGE);
                return;
            }

            // 3. フロア情報の構築
            appendLog("フロア情報を構築中...");
            List<AdaptiveRoomOptimizer.FloorInfo> floors = buildFloorInfo(cleaningData);
            appendLog(String.format("フロア情報構築完了: %d階層", floors.size()));

            // 4. 大浴場清掃タイプの選択
            AdaptiveRoomOptimizer.BathCleaningType bathType = selectBathCleaningType();

            // 5. ポイント制限の設定（大浴場清掃スタッフ選択機能付き）
            appendLog("ポイント制限・大浴場清掃スタッフ設定を確認中...");
            List<StaffPointConstraint> pointConstraints = selectStaffPointConstraintsWithBathCleaning(availableStaff, bathType);

            // 5.5. ★新機能: 通常清掃部屋の事前割り振り設定
            appendLog("通常清掃部屋の割り振りパターンを設定中...");
            Map<String, NormalRoomDistributionDialog.StaffDistribution> roomDistribution =
                    selectNormalRoomDistribution(cleaningData.totalMainRooms, cleaningData.totalAnnexRooms,
                            pointConstraints, availableStaff, bathType);

            // 大浴場清掃スタッフの集計
            long bathStaffCount = pointConstraints.stream()
                    .mapToLong(constraint -> constraint.isBathCleaningStaff ? 1 : 0)
                    .sum();

            if (!pointConstraints.isEmpty()) {
                appendLog(String.format("ポイント制限が設定されました: %d人", pointConstraints.size()));
                appendLog(String.format("大浴場清掃スタッフ: %d人", bathStaffCount));
                pointConstraints.forEach(constraint -> {
                    String bathInfo = constraint.isBathCleaningStaff ? " [大浴場清掃担当]" : "";
                    appendLog(String.format("  %s: %s, %s%s",
                            constraint.staffName,
                            constraint.getConstraintDisplay(),
                            constraint.buildingAssignment.displayName,
                            bathInfo));
                });
            } else {
                appendLog("ポイント制限は設定されませんでした（全員均等配分）");
            }

            // ★追加: 部屋割り振り結果の表示
            if (roomDistribution != null && !roomDistribution.isEmpty()) {
                appendLog("通常清掃部屋割り振り設定:");
                roomDistribution.values().forEach(dist -> {
                    appendLog(String.format("  %s: %d部屋 (%s)",
                            dist.staffName, dist.assignedRooms, dist.buildingAssignment));
                });
            }

            // 6. 適応型設定の作成
            appendLog("最適化設定を作成中...");
            int totalRooms = cleaningData.totalMainRooms + cleaningData.totalAnnexRooms;
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config = createAdaptiveConfigWithPointConstraints(
                    availableStaff, totalRooms, cleaningData.totalMainRooms, cleaningData.totalAnnexRooms,
                    bathType, pointConstraints);

            // 7. 最適化実行
            appendLog("最適化を実行中...");
            AdaptiveRoomOptimizer.AdaptiveOptimizer optimizer =
                    new AdaptiveRoomOptimizer.AdaptiveOptimizer(floors, config);
            AdaptiveRoomOptimizer.OptimizationResult result = optimizer.optimize(selectedDate);

            // 8. 部屋番号の割り当て（オプション）
            Map<String, List<FileProcessor.Room>> detailedAssignments = new HashMap<>();
            try {
                RoomNumberAssigner assigner = new RoomNumberAssigner(cleaningData);
                detailedAssignments = assigner.assignDetailedRooms(result.assignments);
                appendLog("部屋番号の詳細割り当てが完了しました。");
            } catch (Exception ex) {
                appendLog("部屋番号の詳細割り当てに失敗しましたが、処理を続行します: " + ex.getMessage());
            }

            // 9. 結果の保存
            lastResult = new ProcessingResult(cleaningData, result, detailedAssignments);
            viewResultsButton.setEnabled(true);

            // 10. 結果サマリーの表示
            appendLog("\n=== 処理完了 ===");
            result.printDetailedSummary();

            appendLog("\n結果表示ボタンで詳細を確認できます。");

            JOptionPane.showMessageDialog(this,
                    String.format("処理が完了しました。\n\n利用可能スタッフ: %d人\n清掃対象部屋: %d室\n" +
                                    "制限設定スタッフ: %d人\n大浴場清掃スタッフ: %d人\n故障部屋(清掃対象): %d室\n\n結果表示ボタンで詳細を確認してください。",
                            availableStaff.size(), totalRooms, pointConstraints.size(), bathStaffCount, selectedBrokenRoomsForCleaning.size()),
                    "処理完了", JOptionPane.INFORMATION_MESSAGE);

        } catch (Exception ex) {
            String errorMsg = "処理中にエラーが発生しました: " + ex.getMessage();
            appendLog("エラー: " + errorMsg);
            LOGGER.severe(errorMsg);
            ex.printStackTrace();

            JOptionPane.showMessageDialog(this, errorMsg, "エラー", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * ★新機能: 通常清掃部屋の事前割り振り設定ダイアログ
     */
    private Map<String, NormalRoomDistributionDialog.StaffDistribution> selectNormalRoomDistribution(
            int mainRooms, int annexRooms,
            List<StaffPointConstraint> pointConstraints,
            List<FileProcessor.Staff> availableStaff,
            AdaptiveRoomOptimizer.BathCleaningType bathType) {  // ★追加

        List<String> staffNamesList = availableStaff.stream()
                .map(s -> s.name)
                .collect(Collectors.toList());

        NormalRoomDistributionDialog dialog = new NormalRoomDistributionDialog(
                parentFrame, mainRooms, annexRooms, pointConstraints, staffNamesList, bathType);  // ★bathType追加
        dialog.setVisible(true);

        if (dialog.getDialogResult()) {
            return dialog.getCurrentDistribution();
        }

        return null;
    }

    /**
     * ★修正: ポイント制限・大浴場清掃スタッフ選択（制限値入力エラー修正版）
     */
    private List<StaffPointConstraint> selectStaffPointConstraintsWithBathCleaning(
            List<FileProcessor.Staff> availableStaff, AdaptiveRoomOptimizer.BathCleaningType bathType) {
        List<StaffPointConstraint> constraints = new ArrayList<>();

        int result = JOptionPane.showConfirmDialog(
                parentFrame,
                "清掃スタッフを設定しますか？\n" +
                        "・故障者制限：体調不良者用\n" +
                        "・業者制限：リライアンス用\n" +
                        "・建物指定：本館のみ/別館のみの担当を指定\n" +
                        "・大浴場清掃：担当スタッフをチェック",
                "ポイント制限・大浴場清掃設定",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );

        if (result != JOptionPane.YES_OPTION) {
            return constraints;
        }

        JDialog dialog = new JDialog(parentFrame, "ポイント制限・大浴場清掃設定", true);
        dialog.setLayout(new BorderLayout());
        dialog.setSize(900, 500);
        dialog.setLocationRelativeTo(parentFrame);

        JPanel infoPanel = new JPanel(new GridLayout(5, 1));
        infoPanel.setBorder(BorderFactory.createTitledBorder("設定方法"));
        infoPanel.add(new JLabel("• 制限タイプ：「制限なし」、「故障者制限」、「業者制限」から選択"));
        infoPanel.add(new JLabel("• 故障者制限：正の数値（例：18.0 = 最大18.0ポイントまで）"));
        infoPanel.add(new JLabel("• 業者制限：最小〜最大（例：20.0〜25.0 = 20.0〜25.0ポイント確保）"));
        infoPanel.add(new JLabel("• 建物指定：「両方」、「本館のみ」、「別館のみ」から選択"));
        infoPanel.add(new JLabel("• 大浴場清掃：チェックを入れたスタッフが大浴場清掃を担当"));

        JPanel staffPanel = new JPanel(new BorderLayout());
        staffPanel.setBorder(BorderFactory.createTitledBorder("スタッフ別制限"));

        String[] columnNames = {"スタッフ名", "制限タイプ", "制限値", "建物指定", "大浴場清掃", "設定状況"};
        DefaultTableModel tableModel = new DefaultTableModel(columnNames, 0);

        for (FileProcessor.Staff staff : availableStaff) {
            Object[] row = {staff.name, "制限なし", "", "両方", false, "未設定"};
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
                        tableModel.setValueAt("大浴場清掃担当", editingRow, 5);
                        tableModel.setValueAt("本館のみ", editingRow, 3);
                    } else {
                        String constraintType = (String) tableModel.getValueAt(editingRow, 1);
                        if ("制限なし".equals(constraintType)) {
                            tableModel.setValueAt("制限なし", editingRow, 5);
                        }
                        tableModel.setValueAt("両方", editingRow, 3);
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
                                    tableModel.setValueAt("設定済み(故障者" + limit + "P)", editingRow, 5);
                                } else {
                                    tableModel.setValueAt("正の数値を入力してください", editingRow, 5);
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
                                            tableModel.setValueAt("設定済み(業者" + min + "〜" + max + "P)", editingRow, 5);
                                        } else {
                                            tableModel.setValueAt("最小値 < 最大値で入力してください", editingRow, 5);
                                        }
                                    } else {
                                        tableModel.setValueAt("範囲形式で入力してください（例：20.0〜25.0）", editingRow, 5);
                                    }
                                } else {
                                    tableModel.setValueAt("範囲形式で入力してください（例：20.0〜25.0）", editingRow, 5);
                                }
                            }
                        } catch (NumberFormatException e) {
                            tableModel.setValueAt("数値エラー", editingRow, 5);
                        }
                    } else if ("制限なし".equals(constraintType)) {
                        boolean isBathStaff = (Boolean) tableModel.getValueAt(editingRow, 4);
                        tableModel.setValueAt(isBathStaff ? "大浴場清掃担当" : "制限なし", editingRow, 5);
                    }
                }

                return super.stopCellEditing();
            }
        });

        JScrollPane scrollPane = new JScrollPane(table);
        staffPanel.add(scrollPane, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout());

        AtomicBoolean confirmed = new AtomicBoolean(false);

        JButton okButton = new JButton("設定完了");
        okButton.addActionListener(e -> {
            confirmed.set(true);
            dialog.dispose();
        });

        JButton cancelButton = new JButton("キャンセル");
        cancelButton.addActionListener(e -> dialog.dispose());

        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);

        dialog.add(infoPanel, BorderLayout.NORTH);
        dialog.add(staffPanel, BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        dialog.setVisible(true);

        if (confirmed.get()) {
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                String staffName = (String) tableModel.getValueAt(i, 0);
                String constraintType = (String) tableModel.getValueAt(i, 1);
                String constraintValue = (String) tableModel.getValueAt(i, 2);
                String buildingType = (String) tableModel.getValueAt(i, 3);
                Boolean isBathCleaning = (Boolean) tableModel.getValueAt(i, 4);

                if ((!"制限なし".equals(constraintType) && constraintValue != null && !constraintValue.trim().isEmpty()) ||
                        (isBathCleaning != null && isBathCleaning) ||
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

                        StaffPointConstraint constraint = new StaffPointConstraint(
                                staffId, staffName, cType, bType, upperLimit, lowerMin, lowerMax, bathCleaningFlag);
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
                pointConstraints);
    }

    private AdaptiveRoomOptimizer.BathCleaningType selectBathCleaningType() {
        String[] options = {
                AdaptiveRoomOptimizer.BathCleaningType.NONE.displayName,
                AdaptiveRoomOptimizer.BathCleaningType.NORMAL.displayName,
                AdaptiveRoomOptimizer.BathCleaningType.WITH_DRAINING.displayName
        };

        int choice = JOptionPane.showOptionDialog(
                this,
                "今日は？:",
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

        for (FileProcessor.Room room : cleaningData.mainRooms) {
            int floor = room.floor;
            buildingData.put(floor, true);

            floorData.computeIfAbsent(floor, k -> new HashMap<>())
                    .merge(room.roomType, 1, Integer::sum);

            if (room.isEcoClean) {
                ecoData.merge(floor, 1, Integer::sum);
            }
        }

        for (FileProcessor.Room room : cleaningData.annexRooms) {
            int floor = room.floor;
            buildingData.put(floor, false);

            floorData.computeIfAbsent(floor, k -> new HashMap<>())
                    .merge(room.roomType, 1, Integer::sum);

            if (room.isEcoClean) {
                ecoData.merge(floor, 1, Integer::sum);
            }
        }

        List<AdaptiveRoomOptimizer.FloorInfo> floors = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, Integer>> entry : floorData.entrySet()) {
            int floor = entry.getKey();
            Map<String, Integer> roomCounts = entry.getValue();
            int ecoRooms = ecoData.getOrDefault(floor, 0);
            boolean isMainBuilding = buildingData.getOrDefault(floor, true);

            floors.add(new AdaptiveRoomOptimizer.FloorInfo(floor, roomCounts, ecoRooms, isMainBuilding));
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