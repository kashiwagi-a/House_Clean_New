package org.example;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * ★★必須清掃: 「必ず割り振る部屋・フロア」設定ダイアログ
 *
 * 通常清掃部屋（非ECO）から、割り振りを全部屋数より少なくした場合でも
 * 「必ず清掃対象として割り振る」部屋・フロアを選択する。
 *
 * - フロア単位: チェックした階の通常清掃部屋（非ECO）全室が必須になる
 * - 部屋単位: 個別にチェックした部屋が必須になる（フロア指定と併用可能・重複は自動排除）
 *
 * 選択結果は NormalRoomDistributionDialog が保持し、OK確定時に
 * ・CP-SAT: 該当フロアへ必須室数ぶんを必ず配るハード制約
 * ・RoomNumberAssigner: 各フロア内で必須部屋を優先的に取得
 * として反映される（階別の手動割り当て使用時は警告のみ・手動優先）。
 *
 * ※フロアキーは内部値（別館＝実階+20、Room.floor と一致）で保持する。
 */
public class RequiredRoomSelectionDialog extends JDialog {

    /** 階の区切り線の色 */
    private static final Color FLOOR_LINE_COLOR = new Color(96, 96, 96);

    /** 選択肢となる通常清掃部屋（非ECO・非連泊。連泊は必ず清掃されるため選択不要） */
    private final List<FileProcessor.Room> selectableRooms;

    /** 内部フロアキー → フロアチェックボックス */
    private final Map<Integer, JCheckBox> floorBoxes = new LinkedHashMap<>();

    /** 部屋テーブル */
    private JTable roomTable;
    private DefaultTableModel tableModel;
    /** テーブル行 → 部屋（行順と一致） */
    private final List<FileProcessor.Room> tableRooms = new ArrayList<>();

    private boolean confirmed = false;
    private Set<Integer> resultFloors = new HashSet<>();
    private Set<String> resultRooms = new HashSet<>();

    public RequiredRoomSelectionDialog(JDialog parent,
                                       List<FileProcessor.Room> rooms,
                                       Set<Integer> initialFloors,
                                       Set<String> initialRooms) {
        super(parent, "必須清掃設定（必ず割り振る部屋・フロア）", true);

        // 連泊（状態3）は必ず清掃対象になるため選択肢から除外する。
        // ※フロア必須時の室数展開（NormalRoomDistributionDialog側）は連泊を含んだままで正しい
        this.selectableRooms = new ArrayList<>();
        if (rooms != null) {
            for (FileProcessor.Room room : rooms) {
                if (!"3".equals(room.roomStatus)) {
                    this.selectableRooms.add(room);
                }
            }
        }
        Set<Integer> initFloors = (initialFloors != null) ? new HashSet<>(initialFloors) : new HashSet<>();
        Set<String> initRooms = (initialRooms != null) ? new HashSet<>(initialRooms) : new HashSet<>();

        initializeGUI(initFloors, initRooms);
        setSize(820, 640);
        setLocationRelativeTo(parent);
    }

    private void initializeGUI(Set<Integer> initFloors, Set<String> initRooms) {
        setLayout(new BorderLayout());

        // ===== 上部: 説明 + フロア単位選択 =====
        JPanel northPanel = new JPanel();
        northPanel.setLayout(new BoxLayout(northPanel, BoxLayout.Y_AXIS));

        JLabel info = new JLabel("<html><div style='padding:16px;'>" +
                "通常清掃部屋割り振り設定では全ての部屋を割り振らずとも機能を使用できます。<br>" +
                "そして余った部屋は’残し部屋’となります。<br>" +
                "しかし、残し部屋に選ばれる部屋は自動です。そこで必須設定では必ず清掃に含めたい部屋を選択することで強制的に割り振ることができます。<br>" +
                "</div></html>");
        info.setAlignmentX(Component.LEFT_ALIGNMENT);
        northPanel.add(info);

        // フロア単位選択パネル
        JPanel floorOuter = new JPanel();
        floorOuter.setLayout(new BoxLayout(floorOuter, BoxLayout.Y_AXIS));
        floorOuter.setBorder(BorderFactory.createTitledBorder("フロア単位で必須にする"));
        floorOuter.setAlignmentX(Component.LEFT_ALIGNMENT);

        // 本館・別館のフロアキーを収集（部屋データから。内部キー: 別館=実階+20）
        Set<Integer> mainFloorKeys = new TreeSet<>();
        Set<Integer> annexFloorKeys = new TreeSet<>();
        for (FileProcessor.Room room : selectableRooms) {
            if (room.floor <= 0) continue;
            if ("別館".equals(room.building)) {
                annexFloorKeys.add(room.floor);
            } else {
                mainFloorKeys.add(room.floor);
            }
        }

        if (!mainFloorKeys.isEmpty()) {
            JPanel mainPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            mainPanel.setBorder(BorderFactory.createTitledBorder("本館"));
            for (int f : mainFloorKeys) {
                JCheckBox cb = new JCheckBox(f + "階", initFloors.contains(f));
                cb.addActionListener(e -> roomTable.repaint());  // フロア色分けを更新
                floorBoxes.put(f, cb);
                mainPanel.add(cb);
            }
            floorOuter.add(mainPanel);
        }
        if (!annexFloorKeys.isEmpty()) {
            JPanel annexPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            annexPanel.setBorder(BorderFactory.createTitledBorder("別館"));
            for (int f : annexFloorKeys) {
                int actual = f > 20 ? f - 20 : f;  // 内部値→実階（別館2F=22等）
                JCheckBox cb = new JCheckBox(actual + "階", initFloors.contains(f));
                cb.addActionListener(e -> roomTable.repaint());
                floorBoxes.put(f, cb);
                annexPanel.add(cb);
            }
            floorOuter.add(annexPanel);
        }
        northPanel.add(floorOuter);
        add(northPanel, BorderLayout.NORTH);

        // ===== 中央: 部屋単位選択テーブル =====
        createRoomSelectionTable(initRooms);
        JScrollPane scrollPane = new JScrollPane(roomTable);
        scrollPane.setBorder(BorderFactory.createTitledBorder(
                "部屋単位で必須にする（フロア指定した階の部屋は自動的に必須扱い・薄橙で表示）"));
        add(scrollPane, BorderLayout.CENTER);

        // ===== 下部: 操作ボタン =====
        JPanel buttonPanel = new JPanel(new FlowLayout());

        JButton clearAllButton = new JButton("全て解除");
        clearAllButton.addActionListener(e -> {
            floorBoxes.values().forEach(cb -> cb.setSelected(false));
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                tableModel.setValueAt(false, i, 0);
            }
            roomTable.repaint();
        });
        buttonPanel.add(clearAllButton);

        JButton okButton = new JButton("OK");
        okButton.addActionListener(e -> {
            collectResults();
            confirmed = true;
            dispose();
        });
        buttonPanel.add(okButton);

        JButton cancelButton = new JButton("キャンセル");
        cancelButton.addActionListener(e -> {
            confirmed = false;
            dispose();
        });
        buttonPanel.add(cancelButton);

        add(buttonPanel, BorderLayout.SOUTH);
    }

    /**
     * 部屋選択テーブルの作成（本館→別館、階昇順、部屋番号昇順）
     */
    private void createRoomSelectionTable(Set<String> initRooms) {
        String[] columnNames = {"選択", "部屋番号", "部屋タイプ", "建物", "階", "状態"};

        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 0 ? Boolean.class : String.class;
            }
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0;
            }
        };

        // ソート: 本館→別館、階昇順、部屋番号（数値）昇順
        List<FileProcessor.Room> sorted = new ArrayList<>(selectableRooms);
        sorted.sort(Comparator
                .comparingInt((FileProcessor.Room r) -> "本館".equals(r.building) ? 0 : 1)
                .thenComparingInt(r -> r.floor)
                .thenComparingInt(r -> extractRoomNumber(r.roomNumber)));

        tableRooms.clear();
        for (FileProcessor.Room room : sorted) {
            int actualFloor = (!"本館".equals(room.building) && room.floor > 20)
                    ? room.floor - 20 : room.floor;
            Object[] rowData = {
                    initRooms.contains(room.roomNumber),
                    room.roomNumber,
                    // 正式タイプ（NS/ZS/ZT等）があればそちらを表示。無ければ丸め後のS/T
                    (room.rawRoomType != null && !room.rawRoomType.isEmpty())
                            ? room.rawRoomType : room.roomType,
                    room.building,
                    actualFloor + "階",
                    room.getStatusDisplay()
            };
            tableModel.addRow(rowData);
            tableRooms.add(room);
        }

        roomTable = new JTable(tableModel);
        roomTable.setRowHeight(25);
        roomTable.getColumnModel().getColumn(0).setPreferredWidth(50);
        roomTable.getColumnModel().getColumn(1).setPreferredWidth(90);
        roomTable.getColumnModel().getColumn(2).setPreferredWidth(80);
        roomTable.getColumnModel().getColumn(3).setPreferredWidth(60);
        roomTable.getColumnModel().getColumn(4).setPreferredWidth(60);
        roomTable.getColumnModel().getColumn(5).setPreferredWidth(110);

        // フロア指定された階の行を薄橙で表示するレンダラー（チェック列以外）
        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                           boolean isSelected, boolean hasFocus,
                                                           int row, int column) {
                Component c = super.getTableCellRendererComponent(
                        table, value, isSelected, hasFocus, row, column);
                setHorizontalAlignment(JLabel.CENTER);
                if (!isSelected) {
                    c.setBackground(isRowOnRequiredFloor(row)
                            ? new Color(255, 228, 196)   // 薄橙: フロア指定で必須扱い
                            : Color.WHITE);
                }
                setBorder(floorBoundaryBorder(row));
                return c;
            }
        };
        for (int i = 1; i < columnNames.length; i++) {
            roomTable.getColumnModel().getColumn(i).setCellRenderer(renderer);
        }

        // チェック列にも同じ区切り線を引く（デフォルトのBooleanレンダラーをラップ）
        TableCellRenderer boolRenderer = roomTable.getDefaultRenderer(Boolean.class);
        roomTable.getColumnModel().getColumn(0).setCellRenderer(
                (table, value, isSelected, hasFocus, row, column) -> {
                    Component c = boolRenderer.getTableCellRendererComponent(
                            table, value, isSelected, hasFocus, row, column);
                    if (c instanceof JComponent) {
                        ((JComponent) c).setBorder(floorBoundaryBorder(row));
                    }
                    return c;
                });
    }

    /** 階（建物含む）が前の行と変わる行なら上辺の区切り線、そうでなければ枠なしを返す */
    private javax.swing.border.Border floorBoundaryBorder(int row) {
        boolean boundary = row > 0 && row < tableRooms.size()
                && tableRooms.get(row).floor != tableRooms.get(row - 1).floor;
        return boundary
                ? BorderFactory.createMatteBorder(2, 0, 0, 0, FLOOR_LINE_COLOR)
                : BorderFactory.createEmptyBorder(2, 0, 0, 0);
    }

    /** 指定行の部屋が「フロア単位で必須」の階にあるか */
    private boolean isRowOnRequiredFloor(int row) {
        if (row < 0 || row >= tableRooms.size()) return false;
        JCheckBox cb = floorBoxes.get(tableRooms.get(row).floor);
        return cb != null && cb.isSelected();
    }

    /** OK時: チェック状態を結果セットへ収集 */
    private void collectResults() {
        resultFloors = new HashSet<>();
        for (Map.Entry<Integer, JCheckBox> en : floorBoxes.entrySet()) {
            if (en.getValue().isSelected()) {
                resultFloors.add(en.getKey());
            }
        }
        resultRooms = new HashSet<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            Boolean selected = (Boolean) tableModel.getValueAt(i, 0);
            if (selected != null && selected) {
                resultRooms.add((String) tableModel.getValueAt(i, 1));
            }
        }
    }

    /** 部屋番号から数値部分を抽出（ソート用） */
    private static int extractRoomNumber(String roomNumber) {
        try {
            String numStr = roomNumber.replaceAll("[^0-9]", "");
            return numStr.isEmpty() ? 0 : Integer.parseInt(numStr);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ====== 結果取得 ======

    public boolean isConfirmed() {
        return confirmed;
    }

    /** 必須フロア（内部キー: 別館=実階+20） */
    public Set<Integer> getRequiredFloors() {
        return new HashSet<>(resultFloors);
    }

    /** 個別に指定した必須部屋番号（フロア分は含まない） */
    public Set<String> getRequiredRoomNumbers() {
        return new HashSet<>(resultRooms);
    }
}