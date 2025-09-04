package org.example;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
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

/**
 * ホテル清掃管理システム - メインアプリケーション
 * 部屋数制限機能統合版 - 簡易版カレンダー日付選択付き
 * 故障部屋選択機能追加版
 */
public class RoomAssignmentApplication extends JFrame {
    private static final Logger LOGGER = Logger.getLogger(RoomAssignmentApplication.class.getName());

    private JFrame parentFrame;
    private JTextArea logArea;
    private JButton selectRoomFileButton;
    private JButton selectShiftFileButton;
    private JButton selectEcoDataButton;
    private JButton selectDateButton;
    private JButton brokenRoomSettingsButton;  // ★追加: 故障部屋設定ボタン
    private JButton processButton;
    private JButton viewResultsButton;

    private File selectedRoomFile;
    private File selectedShiftFile;
    private File selectedEcoDataFile;
    private LocalDate selectedDate;
    private ProcessingResult lastResult;

    // ★追加: 故障部屋選択関連
    private Set<String> selectedBrokenRoomsForCleaning = new HashSet<>();

    // 処理結果を保持するクラス
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

        // メインパネル
        JPanel mainPanel = new JPanel(new BorderLayout());

        // 上部：ファイル選択パネル
        JPanel filePanel = createFileSelectionPanel();
        mainPanel.add(filePanel, BorderLayout.NORTH);

        // 中央：ログ表示エリア
        logArea = new JTextArea(20, 60);
        logArea.setEditable(false);
        logArea.setFont(new Font("MS Gothic", Font.PLAIN, 12));
        JScrollPane logScrollPane = new JScrollPane(logArea);
        logScrollPane.setBorder(BorderFactory.createTitledBorder("処理ログ"));
        mainPanel.add(logScrollPane, BorderLayout.CENTER);

        // 下部：実行ボタン
        JPanel buttonPanel = createButtonPanel();
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        add(mainPanel);

        // 初期状態の設定
        updateButtonStates();

        // ウィンドウ設定
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

        // 部屋データファイル
        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("部屋データファイル:"), gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        selectRoomFileButton = new JButton("CSVファイルを選択...");
        selectRoomFileButton.addActionListener(this::selectRoomFile);
        panel.add(selectRoomFileButton, gbc);

        // シフトファイル
        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("シフトファイル:"), gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        selectShiftFileButton = new JButton("Excelファイルを選択...");
        selectShiftFileButton.addActionListener(this::selectShiftFile);
        panel.add(selectShiftFileButton, gbc);

        // エコデータファイル（オプション）
        gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("エコデータ（オプション）:"), gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        selectEcoDataButton = new JButton("Excelファイルを選択...");
        selectEcoDataButton.addActionListener(this::selectEcoDataFile);
        panel.add(selectEcoDataButton, gbc);

        // 対象日
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

        // ★追加: 故障部屋設定ボタン
        brokenRoomSettingsButton = new JButton("故障部屋設定");
        brokenRoomSettingsButton.setFont(new Font("MS Gothic", Font.BOLD, 12));
        brokenRoomSettingsButton.setPreferredSize(new Dimension(120, 35));
        brokenRoomSettingsButton.setBackground(new Color(255, 140, 0)); // オレンジ色
        brokenRoomSettingsButton.setForeground(Color.WHITE);
        brokenRoomSettingsButton.addActionListener(this::openBrokenRoomSettings);
        brokenRoomSettingsButton.setEnabled(false); // 初期状態は無効
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

    // ★追加: 故障部屋設定ダイアログを開く
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

                // ボタンの色を変更して設定済みを示す
                brokenRoomSettingsButton.setBackground(new Color(34, 139, 34)); // 緑色
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

            // ★追加: 部屋データファイルが選択されたら故障部屋設定ボタンを有効化
            brokenRoomSettingsButton.setEnabled(true);
            brokenRoomSettingsButton.setText("故障部屋設定");
            brokenRoomSettingsButton.setBackground(new Color(255, 140, 0)); // オレンジ色に戻す
            selectedBrokenRoomsForCleaning.clear(); // 前の設定をクリア

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

    /**
     * 簡易版カレンダー日付選択
     */
    private void selectDate(ActionEvent e) {
        // 現在選択されている日付、または今日の日付を初期値に設定
        Date currentDate = selectedDate != null ?
                java.sql.Date.valueOf(selectedDate) : new Date();

        // 日付選択用のSpinner設定
        SpinnerDateModel model = new SpinnerDateModel(currentDate, null, null, Calendar.DAY_OF_MONTH);
        JSpinner spinner = new JSpinner(model);
        JSpinner.DateEditor editor = new JSpinner.DateEditor(spinner, "yyyy年MM月dd日");
        spinner.setEditor(editor);
        spinner.setFont(new Font("MS Gothic", Font.PLAIN, 14));

        // 今日の日付ボタン
        JButton todayButton = new JButton("今日の日付");
        todayButton.addActionListener(evt -> spinner.setValue(new Date()));

        // メッセージパネル作成
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

        // ダイアログ表示
        int result = JOptionPane.showConfirmDialog(
                this,
                messagePanel,
                "日付選択",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
        );

        // 結果処理
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

        // 部屋データファイルが選択されている場合のみ故障部屋設定ボタンを有効化
        brokenRoomSettingsButton.setEnabled(selectedRoomFile != null);
    }

    private void processData(ActionEvent e) {
        if (selectedRoomFile == null || selectedShiftFile == null || selectedDate == null) {
            JOptionPane.showMessageDialog(this, "必要なファイルと日付を選択してください。",
                    "エラー", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // 処理を別スレッドで実行
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

            // エコデータファイルのシステムプロパティ設定
            if (selectedEcoDataFile != null) {
                System.setProperty("ecoDataFile", selectedEcoDataFile.getAbsolutePath());
                appendLog("エコデータファイルを設定: " + selectedEcoDataFile.getName());
            }

            // ★追加: 選択された故障部屋をシステムプロパティで渡す
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
            appendLog(String.format("利用可能スタッフ: %d名", availableStaff.size()));

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

            // 5. 部屋数制限の設定（手動設定のみ）
            appendLog("部屋数制限設定を確認中...");
            Map<String, Integer> roomLimits = selectStaffRoomLimitsOptional(availableStaff);
            if (!roomLimits.isEmpty()) {
                appendLog(String.format("部屋数制限が設定されました: %d名", roomLimits.size()));
                roomLimits.forEach((staffId, limit) -> {
                    String staffName = availableStaff.stream()
                            .filter(s -> s.id.equals(staffId))
                            .map(s -> s.name)
                            .findFirst().orElse(staffId);
                    String limitType = limit < 0 ? "下限" : "上限";
                    appendLog(String.format("  %s: %s %d室", staffName, limitType, Math.abs(limit)));
                });
            } else {
                appendLog("部屋数制限は設定されませんでした（全員均等配分）");
            }

            // 6. 適応型設定の作成
            appendLog("最適化設定を作成中...");
            int totalRooms = cleaningData.totalMainRooms + cleaningData.totalAnnexRooms;
            AdaptiveRoomOptimizer.AdaptiveLoadConfig config = createAdaptiveConfigWithLimits(
                    availableStaff, totalRooms, cleaningData.totalMainRooms, cleaningData.totalAnnexRooms,
                    bathType, roomLimits);

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
                    String.format("処理が完了しました。\n\n利用可能スタッフ: %d名\n清掃対象部屋: %d室\n" +
                                    "制限設定スタッフ: %d名\n故障部屋(清掃対象): %d室\n\n結果表示ボタンで詳細を確認してください。",
                            availableStaff.size(), totalRooms, roomLimits.size(), selectedBrokenRoomsForCleaning.size()),
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
     * 部屋数制限選択（手動設定のみ）
     */
    private Map<String, Integer> selectStaffRoomLimitsOptional(List<FileProcessor.Staff> availableStaff) {
        Map<String, Integer> roomLimits = new HashMap<>();

        int result = JOptionPane.showConfirmDialog(
                parentFrame,
                "部屋数制限を設定しますか？\n" +
                        "・上限制限：体調不良等で最大部屋数を制限\n" +
                        "・下限制限：ベテラン等で最低部屋数を保証",
                "部屋数制限設定",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );

        if (result != JOptionPane.YES_OPTION) {
            return roomLimits; // 空のマップを返す
        }

        // 手動設定ダイアログ
        JDialog dialog = new JDialog(parentFrame, "部屋数制限設定", true);
        dialog.setLayout(new BorderLayout());
        dialog.setSize(500, 400);
        dialog.setLocationRelativeTo(parentFrame);

        // 説明パネル
        JPanel infoPanel = new JPanel(new GridLayout(3, 1));
        infoPanel.setBorder(BorderFactory.createTitledBorder("設定方法"));
        infoPanel.add(new JLabel("• 上限制限：正の数値（例：8 = 最大8室まで）"));
        infoPanel.add(new JLabel("• 下限制限：負の数値（例：-15 = 最低15室は確保）"));
        infoPanel.add(new JLabel("• 未設定：0または空白"));

        // スタッフ設定パネル
        JPanel staffPanel = new JPanel(new BorderLayout());
        staffPanel.setBorder(BorderFactory.createTitledBorder("スタッフ別制限"));

        // テーブル作成
        String[] columnNames = {"スタッフ名", "制限値", "制限タイプ"};
        DefaultTableModel tableModel = new DefaultTableModel(columnNames, 0);

        for (FileProcessor.Staff staff : availableStaff) {
            Object[] row = {staff.name, "", "未設定"};
            tableModel.addRow(row);
        }

        JTable table = new JTable(tableModel);
        table.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // カスタムセルエディタ
        table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(new JTextField()) {
            @Override
            public boolean stopCellEditing() {
                String value = (String) getCellEditorValue();
                int row = table.getSelectedRow();

                if (!value.trim().isEmpty()) {
                    try {
                        int limit = Integer.parseInt(value.trim());
                        String type = limit > 0 ? "上限" + Math.abs(limit) + "室" :
                                limit < 0 ? "下限" + Math.abs(limit) + "室" : "未設定";
                        tableModel.setValueAt(type, row, 2);
                    } catch (NumberFormatException e) {
                        tableModel.setValueAt("入力エラー", row, 2);
                    }
                } else {
                    tableModel.setValueAt("未設定", row, 2);
                }

                return super.stopCellEditing();
            }
        });

        JScrollPane scrollPane = new JScrollPane(table);
        staffPanel.add(scrollPane, BorderLayout.CENTER);

        // ボタンパネル
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

        // レイアウト
        dialog.add(infoPanel, BorderLayout.NORTH);
        dialog.add(staffPanel, BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        dialog.setVisible(true);

        // 結果を処理
        if (confirmed.get()) {
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                String staffName = (String) tableModel.getValueAt(i, 0);
                String limitStr = (String) tableModel.getValueAt(i, 1);

                if (limitStr != null && !limitStr.trim().isEmpty()) {
                    try {
                        int limit = Integer.parseInt(limitStr.trim());
                        if (limit != 0) {
                            // スタッフIDを取得
                            String staffId = availableStaff.stream()
                                    .filter(s -> s.name.equals(staffName))
                                    .map(s -> s.id)
                                    .findFirst()
                                    .orElse(staffName);
                            roomLimits.put(staffId, limit);
                        }
                    } catch (NumberFormatException e) {
                        // 無効な値はスキップ
                    }
                }
            }
        }

        return roomLimits;
    }

    /**
     * 制限付き設定作成
     */
    private AdaptiveRoomOptimizer.AdaptiveLoadConfig createAdaptiveConfigWithLimits(
            List<FileProcessor.Staff> availableStaff,
            int totalRooms,
            int mainBuildingRooms,
            int annexBuildingRooms,
            AdaptiveRoomOptimizer.BathCleaningType bathType,
            Map<String, Integer> roomLimits) {

        // 修正されたAdaptiveRoomOptimizerの制限対応メソッドを呼び出し
        return AdaptiveRoomOptimizer.AdaptiveLoadConfig.createAdaptiveConfig(
                availableStaff,
                totalRooms,
                mainBuildingRooms,
                annexBuildingRooms,
                bathType,
                roomLimits  // 制限マップを渡す
        );
    }

    private AdaptiveRoomOptimizer.BathCleaningType selectBathCleaningType() {
        String[] options = {
                AdaptiveRoomOptimizer.BathCleaningType.NONE.displayName,
                AdaptiveRoomOptimizer.BathCleaningType.NORMAL.displayName,
                AdaptiveRoomOptimizer.BathCleaningType.WITH_DRAINING.displayName
        };

        int choice = JOptionPane.showOptionDialog(
                this,
                "大浴場清掃のタイプを選択してください:",
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

        // 本館の部屋を処理
        for (FileProcessor.Room room : cleaningData.mainRooms) {
            int floor = room.floor;
            buildingData.put(floor, true); // 本館

            floorData.computeIfAbsent(floor, k -> new HashMap<>())
                    .merge(room.roomType, 1, Integer::sum);

            if (room.isEcoClean) {
                ecoData.merge(floor, 1, Integer::sum);
            }
        }

        // 別館の部屋を処理
        for (FileProcessor.Room room : cleaningData.annexRooms) {
            int floor = room.floor;
            buildingData.put(floor, false); // 別館

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

        // 結果編集GUIを表示
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