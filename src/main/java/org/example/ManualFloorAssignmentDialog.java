package org.example;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.text.DefaultFormatterFactory;
import javax.swing.text.NumberFormatter;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.ParseException;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 階別の手動割り当てダイアログ（任意機能）
 *
 * 「通常清掃部屋割り振り設定」から開く。各スタッフごとに「階・通常シングル等(S)・ツイン(T)・Eco」を
 * 手動で割り振り、リネン庫担当フロアをチェックで指定する。入力に応じて「割り当て済み数」ウィンドウが
 * リアルタイム更新される。
 *
 * ハードゲート（OK可能条件）:
 *   - 各スタッフ: 本館合計＝本館上限（★★★本館ツイン統合: S/Tの区別なし。T欄は使わずSにまとめて入力）、
 *                 別館S/T合計＝別館S/T上限（建物は階で自動判定）
 *   - 各スタッフ: Eco合計＝Eco総数（このダイアログ内で入力）
 *   - 各階の入力 ≤ その階の在庫（本館はS在庫にツインを含めた合計、別館はS/T/Eco別）
 *   - リネン庫: 同じ階を複数スタッフがリネン庫担当に指定しない（ダブり禁止）
 *
 * ★★★本館ツイン統合: 本館はツイン区別を廃止したため、在庫(FloorInv)は本館ツインを
 * シングル等在庫(singleAvail)へ合算し、本館の twinAvail は常に0。本館フロアの入力は
 * すべてS欄で行う（T欄への入力は検証エラーで案内する）。別館は従来どおり。
 *
 * ※このダイアログは「使ったときだけ」手動レイアウトを確定する。使わなければ従来のCP-SAT自動フローのまま。
 */
public class ManualFloorAssignmentDialog extends JDialog {

    // ====== 公開データ構造 ======

    /** 1スタッフ・1階分の手動割り当て行 */
    public static class FloorAssign {
        public int floorKey;       // 内部フロアキー（別館＝実階+20。Room.floor と一致）
        public boolean isMain;     // true=本館 / false=別館
        public int singleCount;    // 通常シングル等(S区分: S/D/FD等)
        public int twinCount;      // 通常ツイン(T)
        public int ecoCount;       // Eco
        public boolean linen;      // リネン庫担当フロア

        public FloorAssign(int floorKey, boolean isMain) {
            this.floorKey = floorKey;
            this.isMain = isMain;
        }
    }

    /** 1スタッフ分の手動割り当て */
    public static class StaffManual {
        public final String staffName;
        public int ecoTotal;                 // Eco総数（上限・このダイアログで入力）
        public final List<FloorAssign> rows = new ArrayList<>();
        public StaffManual(String staffName) { this.staffName = staffName; }
    }

    /** 1階分の在庫情報（検証・ドロップダウン用） */
    public static class FloorInv {
        public final int floorKey;     // 内部キー（別館＝実階+20）
        public final boolean isMain;
        public final int displayFloor; // 表示用の実階
        public final int singleAvail;  // 通常シングル等の在庫（非Eco・T以外）
        public final int twinAvail;    // 通常ツインの在庫（非Eco・T）
        public final int ecoAvail;     // Eco在庫

        public FloorInv(int floorKey, boolean isMain, int displayFloor,
                        int singleAvail, int twinAvail, int ecoAvail) {
            this.floorKey = floorKey;
            this.isMain = isMain;
            this.displayFloor = displayFloor;
            this.singleAvail = singleAvail;
            this.twinAvail = twinAvail;
            this.ecoAvail = ecoAvail;
        }

        public String label() {
            return (isMain ? "" : "別館") + displayFloor + "F（" + (isMain ? "本館" : "別館") + "）";
        }
    }

    // ====== 内部状態 ======

    private final Map<String, NormalRoomDistributionDialog.StaffDistribution> distribution;
    private final Map<Integer, FloorInv> inventory;            // floorKey -> 在庫
    private final List<Integer> mainFloorKeys;                 // 本館フロアキー（昇順）
    private final List<Integer> annexFloorKeys;                // 別館フロアキー（昇順）

    private final Map<String, StaffManual> layout = new LinkedHashMap<>();
    private final Map<String, StaffPanelRefs> panelRefs = new LinkedHashMap<>();

    private boolean confirmed = false;
    private JPanel staffContainer;
    private AssignedCountWindow countWindow;

    /** 各スタッフUIの参照（再計算・再描画用） */
    private static class StaffPanelRefs {
        JPanel rowsPanel;
        JLabel totalsLabel;
        List<RowRefs> rows = new ArrayList<>();
    }
    private static class RowRefs {
        JComboBox<FloorInv> floorBox;
        JCheckBox linenCheck;
        JSpinner sSpinner;
        JSpinner tSpinner;
        JSpinner ecoSpinner;
        JPanel rowPanel;
    }

    // ====== コンストラクタ ======

    public ManualFloorAssignmentDialog(Window parent,
                                       Map<String, NormalRoomDistributionDialog.StaffDistribution> distribution,
                                       Map<Integer, FloorInv> inventory,
                                       Map<String, StaffManual> initialLayout) {
        super(parent, "階別の手動割り当て", ModalityType.APPLICATION_MODAL);
        this.distribution = distribution;
        this.inventory = inventory != null ? inventory : new HashMap<>();

        this.mainFloorKeys = this.inventory.values().stream()
                .filter(fi -> fi.isMain).map(fi -> fi.floorKey).sorted().collect(Collectors.toList());
        this.annexFloorKeys = this.inventory.values().stream()
                .filter(fi -> !fi.isMain).map(fi -> fi.floorKey).sorted().collect(Collectors.toList());

        // 初期レイアウト（再編集時）をコピー、無ければ空で開始
        for (String name : distribution.keySet()) {
            StaffManual sm = new StaffManual(name);
            if (initialLayout != null && initialLayout.containsKey(name)) {
                StaffManual src = initialLayout.get(name);
                sm.ecoTotal = src.ecoTotal;
                for (FloorAssign fa : src.rows) {
                    FloorAssign cp = new FloorAssign(fa.floorKey, fa.isMain);
                    cp.singleCount = fa.singleCount;
                    cp.twinCount = fa.twinCount;
                    cp.ecoCount = fa.ecoCount;
                    cp.linen = fa.linen;
                    sm.rows.add(cp);
                }
            }
            layout.put(name, sm);
        }

        buildUI();
        setSize(980, 760);   // ★横幅のみ拡大（引き伸ばさずに見えるように）。縦は従来どおり
        setLocationRelativeTo(parent);

        // 表示と同時に「割り当て済み数」ウィンドウを開く（このダイアログの子として）
        addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent e) { openCountWindow(); }
            @Override public void windowClosed(WindowEvent e) { closeCountWindow(); }
        });
    }

    // ====== UI構築 ======

    private void buildUI() {
        setLayout(new BorderLayout());

        JLabel hint = new JLabel("<html>各スタッフに「階・S数・T数・Eco数」を割り当てます。リネン庫にチェックした階は部屋番号が大きい順になります。" +
                "<br>合計が上限とちょうど一致するまでOKできません（ダブりのある階も不可）。</html>");
        hint.setFont(new Font("MS Gothic", Font.PLAIN, 12));
        hint.setBorder(new EmptyBorder(8, 12, 4, 12));
        add(hint, BorderLayout.NORTH);

        staffContainer = new JPanel();
        staffContainer.setLayout(new BoxLayout(staffContainer, BoxLayout.Y_AXIS));
        for (String name : sortedStaffNames()) {
            staffContainer.add(buildStaffBlock(name));
            staffContainer.add(Box.createVerticalStrut(8));
        }
        JScrollPane scroll = new JScrollPane(staffContainer);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        add(buildButtonPanel(), BorderLayout.SOUTH);
    }

    private JPanel buildStaffBlock(String name) {
        NormalRoomDistributionDialog.StaffDistribution dist = distribution.get(name);
        StaffManual sm = layout.get(name);
        StaffPanelRefs refs = new StaffPanelRefs();
        panelRefs.put(name, refs);

        JPanel block = new JPanel(new BorderLayout());
        block.setBorder(new LineBorder(Color.LIGHT_GRAY, 1, true));
        block.setAlignmentX(Component.LEFT_ALIGNMENT);

        // ---- ヘッダ（名前＋役割バッジ＋上限） ----
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JLabel nameLabel = new JLabel(name);
        nameLabel.setFont(new Font("MS Gothic", Font.BOLD, 13));
        header.add(nameLabel);

        // 役割バッジ（お風呂／備品＝搬入。排他のため最大1つ）
        if (dist != null && dist.isBathCleaning) {
            header.add(makeBadge("お風呂", new Color(0x1E, 0x6F, 0xB8), new Color(0xE3, 0xF0, 0xFB)));
        } else if (dist != null && dist.isSuppliesOrder) {
            header.add(makeBadge("備品（搬入）", new Color(0x8A, 0x5A, 0x00), new Color(0xFA, 0xEE, 0xDA)));
        }

        JLabel capLabel = new JLabel(capText(dist));
        capLabel.setFont(new Font("MS Gothic", Font.PLAIN, 11));
        capLabel.setForeground(Color.DARK_GRAY);
        header.add(capLabel);
        block.add(header, BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(new EmptyBorder(0, 8, 8, 8));

        // ---- Eco総数（上限）の設定UIは撤廃。Ecoは各行で入力し、階の実在庫のみで検証する ----

        // ---- 行ヘッダ ----
        JPanel rowHeader = new JPanel(new GridLayout(1, 6, 4, 0));
        rowHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
        rowHeader.add(headerCell("階"));
        rowHeader.add(headerCell("リネン庫"));
        rowHeader.add(headerCell("S数"));
        rowHeader.add(headerCell("T数"));
        rowHeader.add(headerCell("Eco数"));
        rowHeader.add(headerCell(""));
        body.add(rowHeader);

        // ---- 行コンテナ ----
        JPanel rowsPanel = new JPanel();
        rowsPanel.setLayout(new BoxLayout(rowsPanel, BoxLayout.Y_AXIS));
        rowsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        refs.rowsPanel = rowsPanel;
        body.add(rowsPanel);

        // 既存行を再現（担当建物がある場合のみ初期行を用意）
        boolean canAssign = !allowedFloorKeys(name).isEmpty();
        if (sm.rows.isEmpty() && canAssign) {
            sm.rows.add(new FloorAssign(defaultFloorKeyFor(name), defaultIsMainFor(name)));
        }
        for (FloorAssign fa : new ArrayList<>(sm.rows)) {
            addRowUI(name, fa);
        }

        // ---- 行追加ボタン＋合計 ----
        JPanel footer = new JPanel(new BorderLayout());
        footer.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton addRow = new JButton("＋ 行を追加");
        addRow.setFont(new Font("MS Gothic", Font.PLAIN, 11));
        addRow.setEnabled(canAssign);   // 担当建物が無いスタッフは追加不可（対象外）
        if (!canAssign) addRow.setToolTipText("このスタッフは本館・別館とも担当室が0のため対象外です");
        addRow.addActionListener(e -> {
            FloorAssign fa = new FloorAssign(defaultFloorKeyFor(name), defaultIsMainFor(name));
            sm.rows.add(fa);
            addRowUI(name, fa);
            rowsPanel.revalidate();
            rowsPanel.repaint();
            recalc(name);
        });
        footer.add(addRow, BorderLayout.WEST);

        JLabel totals = new JLabel();
        totals.setFont(new Font("MS Gothic", Font.PLAIN, 11));
        totals.setHorizontalAlignment(SwingConstants.RIGHT);
        refs.totalsLabel = totals;
        footer.add(totals, BorderLayout.CENTER);
        body.add(Box.createVerticalStrut(4));
        body.add(footer);

        block.add(body, BorderLayout.CENTER);

        recalc(name);
        return block;
    }

    private void addRowUI(String name, FloorAssign fa) {
        StaffManual sm = layout.get(name);
        StaffPanelRefs refs = panelRefs.get(name);
        RowRefs r = new RowRefs();

        JPanel rowPanel = new JPanel(new GridLayout(1, 6, 4, 0));
        rowPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        rowPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

        // 階セレクト（そのスタッフの担当建物の階のみ。両方0なら空＝対象外）
        DefaultComboBoxModel<FloorInv> model = new DefaultComboBoxModel<>();
        List<Integer> allowed = allowedFloorKeys(name);
        for (int key : allowed) {
            FloorInv fi = inventory.get(key);
            if (fi != null) model.addElement(fi);
        }
        JComboBox<FloorInv> floorBox = new JComboBox<>(model);
        floorBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int idx,
                                                          boolean sel, boolean focus) {
                Component c = super.getListCellRendererComponent(list, value, idx, sel, focus);
                if (value instanceof FloorInv) setText(((FloorInv) value).label());
                return c;
            }
        });
        // 初期選択（保存済みの階が担当建物外なら、先頭の許可階へ補正）
        FloorInv initial = inventory.get(fa.floorKey);
        if (initial == null || !allowed.contains(fa.floorKey)) {
            if (!allowed.isEmpty()) {
                initial = inventory.get(allowed.get(0));
                if (initial != null) { fa.floorKey = initial.floorKey; fa.isMain = initial.isMain; }
            } else {
                initial = null;
            }
        }
        if (initial != null) floorBox.setSelectedItem(initial);
        floorBox.addActionListener(e -> {
            FloorInv sel = (FloorInv) floorBox.getSelectedItem();
            if (sel != null) {
                fa.floorKey = sel.floorKey;
                fa.isMain = sel.isMain;
            }
            recalc(name);
        });
        r.floorBox = floorBox;
        rowPanel.add(floorBox);

        // リネン庫チェック（全スタッフ自由に操作可）
        JCheckBox linen = new JCheckBox();
        linen.setHorizontalAlignment(SwingConstants.CENTER);
        linen.setSelected(fa.linen);
        linen.addActionListener(e -> {
            fa.linen = linen.isSelected();
            recalcAll(); // ダブり判定は全体に影響
        });
        r.linenCheck = linen;
        rowPanel.add(linen);

        r.sSpinner = makeCountSpinner(fa.singleCount, v -> { fa.singleCount = v; recalc(name); });
        r.tSpinner = makeCountSpinner(fa.twinCount,  v -> { fa.twinCount  = v; recalc(name); });
        r.ecoSpinner = makeCountSpinner(fa.ecoCount, v -> { fa.ecoCount  = v; recalc(name); });
        rowPanel.add(wrapCenter(r.sSpinner));
        rowPanel.add(wrapCenter(r.tSpinner));
        rowPanel.add(wrapCenter(r.ecoSpinner));

        JButton del = new JButton("×");
        del.setMargin(new Insets(0, 4, 0, 4));
        del.setFont(new Font("MS Gothic", Font.PLAIN, 11));
        del.addActionListener(e -> {
            sm.rows.remove(fa);
            refs.rowsPanel.remove(rowPanel);
            refs.rows.remove(r);
            refs.rowsPanel.revalidate();
            refs.rowsPanel.repaint();
            recalcAll();
        });
        rowPanel.add(del);

        r.rowPanel = rowPanel;
        refs.rows.add(r);
        refs.rowsPanel.add(rowPanel);
    }

    private JPanel buildButtonPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton reset = new JButton("リセット");
        reset.addActionListener(e -> {
            for (String name : layout.keySet()) {
                StaffManual sm = layout.get(name);
                sm.ecoTotal = 0;
                sm.rows.clear();
                if (!allowedFloorKeys(name).isEmpty()) {
                    sm.rows.add(new FloorAssign(defaultFloorKeyFor(name), defaultIsMainFor(name)));
                }
            }
            // UI再構築
            staffContainer.removeAll();
            panelRefs.clear();
            for (String name : sortedStaffNames()) {
                staffContainer.add(buildStaffBlock(name));
                staffContainer.add(Box.createVerticalStrut(8));
            }
            staffContainer.revalidate();
            staffContainer.repaint();
            recalcAll();
        });
        panel.add(reset);

        JButton ok = new JButton("OK");
        ok.addActionListener(e -> {
            List<String> errors = validateAll();
            if (!errors.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                        "以下が未解決のため確定できません:\n\n• " + String.join("\n• ", errors),
                        "入力エラー", JOptionPane.ERROR_MESSAGE);
                return;
            }
            confirmed = true;
            closeCountWindow();
            dispose();
        });
        panel.add(ok);

        JButton cancel = new JButton("キャンセル");
        cancel.addActionListener(e -> {
            confirmed = false;
            closeCountWindow();
            dispose();
        });
        panel.add(cancel);

        return panel;
    }

    // ====== 検証・再計算 ======

    /** 全スタッフを再計算し、割り当て済みウィンドウも更新 */
    private void recalcAll() {
        for (String name : layout.keySet()) recalc(name);
        updateCountWindow();
    }

    /** 1スタッフの合計表示を更新（建物別S/T・Eco・リネン庫）＋全体のダブり表示 */
    private void recalc(String name) {
        NormalRoomDistributionDialog.StaffDistribution dist = distribution.get(name);
        StaffManual sm = layout.get(name);
        StaffPanelRefs refs = panelRefs.get(name);
        if (refs == null || refs.totalsLabel == null) return;

        int mainS = 0, mainT = 0, annexS = 0, annexT = 0, ecoSum = 0, linenCount = 0;
        for (FloorAssign fa : sm.rows) {
            if (fa.isMain) { mainS += fa.singleCount; mainT += fa.twinCount; }
            else { annexS += fa.singleCount; annexT += fa.twinCount; }
            ecoSum += fa.ecoCount;
            if (fa.linen) linenCount++;
        }

        int capMainS = dist != null ? dist.mainSingleAssignedRooms : 0;
        int capMainT = dist != null ? dist.mainTwinAssignedRooms : 0;
        int capAnnexS = dist != null ? dist.annexSingleAssignedRooms : 0;
        int capAnnexT = dist != null ? dist.annexTwinAssignedRooms : 0;

        StringBuilder sb = new StringBuilder("<html>");
        boolean hasMain = staffHasMain(name);
        boolean hasAnnex = staffHasAnnex(name);
        if (hasMain) {
            // ★★★本館ツイン統合: 本館はS/T区別なしの合計チップ1つ
            sb.append(chip("本館", mainS + mainT, capMainS + capMainT));
        }
        if (hasAnnex) {
            sb.append(chip("別館S", annexS, capAnnexS));
            sb.append(chip("別館T", annexT, capAnnexT));
        }
        sb.append(chipCount("Eco", ecoSum));   // ★Eco上限撤廃：入力数のみ表示（階の実在庫超過は別途検証）
        sb.append("</html>");
        refs.totalsLabel.setText(sb.toString());

        updateCountWindow();
    }

    /** 全体検証。OK可能ならエラーリストは空 */
    private List<String> validateAll() {
        List<String> errors = new ArrayList<>();

        // 階ごとのダブり（リネン庫が複数スタッフ）と在庫超過の集計
        Map<Integer, Integer> usedSingle = new HashMap<>();
        Map<Integer, Integer> usedTwin = new HashMap<>();
        Map<Integer, Integer> usedEco = new HashMap<>();
        Map<Integer, List<String>> linenByFloor = new HashMap<>();

        for (String name : layout.keySet()) {
            NormalRoomDistributionDialog.StaffDistribution dist = distribution.get(name);
            StaffManual sm = layout.get(name);

            int mainS = 0, mainT = 0, annexS = 0, annexT = 0, ecoSum = 0;
            for (FloorAssign fa : sm.rows) {
                if (fa.isMain) { mainS += fa.singleCount; mainT += fa.twinCount; }
                else { annexS += fa.singleCount; annexT += fa.twinCount; }
                ecoSum += fa.ecoCount;

                usedSingle.merge(fa.floorKey, fa.singleCount, Integer::sum);
                usedTwin.merge(fa.floorKey, fa.twinCount, Integer::sum);
                usedEco.merge(fa.floorKey, fa.ecoCount, Integer::sum);
                if (fa.linen) {
                    linenByFloor.computeIfAbsent(fa.floorKey, k -> new ArrayList<>()).add(name);
                }
            }

            // 上限とのピッタリ一致（不足・超過どちらも不可）
            int capMainS = dist != null ? dist.mainSingleAssignedRooms : 0;
            int capMainT = dist != null ? dist.mainTwinAssignedRooms : 0;
            int capAnnexS = dist != null ? dist.annexSingleAssignedRooms : 0;
            int capAnnexT = dist != null ? dist.annexTwinAssignedRooms : 0;
            // ★★★本館ツイン統合: 本館はS/T区別なしの合計で照合する
            if (mainS + mainT != capMainS + capMainT)
                errors.add(String.format("%s: 本館 合計%d ≠ 上限%d", name, mainS + mainT, capMainS + capMainT));
            if (annexS != capAnnexS) errors.add(String.format("%s: 別館S 合計%d ≠ 上限%d", name, annexS, capAnnexS));
            if (annexT != capAnnexT) errors.add(String.format("%s: 別館T 合計%d ≠ 上限%d", name, annexT, capAnnexT));
            // ★Eco上限の一致チェックは撤廃（Ecoは各階の実在庫超過のみ下で検証）
        }

        // 在庫超過
        // ★★★本館ツイン統合: 本館フロアはS在庫にツインを含めた合計と比較（T欄入力もSと同じ在庫を消費）
        for (Map.Entry<Integer, Integer> en : usedSingle.entrySet()) {
            FloorInv fi = inventory.get(en.getKey());
            if (fi == null) continue;
            int used = en.getValue()
                    + (fi.isMain ? usedTwin.getOrDefault(en.getKey(), 0) : 0);
            if (used > fi.singleAvail)
                errors.add(String.format("%s: %s合計%d が在庫%dを超過",
                        fi.label(), fi.isMain ? "通常室" : "S", used, fi.singleAvail));
        }
        for (Map.Entry<Integer, Integer> en : usedTwin.entrySet()) {
            FloorInv fi = inventory.get(en.getKey());
            if (fi == null || en.getValue() <= 0) continue;
            if (fi.isMain) {
                // ★★★本館ツイン統合: 本館フロアはT欄不要（案内のみ。在庫消費は上のS合算で検証済み）
                errors.add(String.format("%s: 本館はツイン区別なしのため、T欄ではなくS欄にまとめて入力してください",
                        fi.label()));
            } else if (en.getValue() > fi.twinAvail) {
                errors.add(String.format("%s: T合計%d が在庫%dを超過", fi.label(), en.getValue(), fi.twinAvail));
            }
        }
        for (Map.Entry<Integer, Integer> en : usedEco.entrySet()) {
            FloorInv fi = inventory.get(en.getKey());
            if (fi != null && en.getValue() > fi.ecoAvail)
                errors.add(String.format("%s: Eco合計%d が在庫%dを超過", fi.label(), en.getValue(), fi.ecoAvail));
        }

        // リネン庫ダブり禁止
        for (Map.Entry<Integer, List<String>> en : linenByFloor.entrySet()) {
            if (en.getValue().size() > 1) {
                FloorInv fi = inventory.get(en.getKey());
                String fl = fi != null ? fi.label() : (en.getKey() + "F");
                errors.add(String.format("%s: リネン庫担当が重複（%s）", fl, String.join(", ", en.getValue())));
            }
        }

        return errors;
    }

    // ====== 割り当て済みウィンドウ ======

    private void openCountWindow() {
        if (countWindow == null) {
            countWindow = new AssignedCountWindow(this, inventory, mainFloorKeys, annexFloorKeys);
        }
        countWindow.setVisible(true);
        updateCountWindow();
    }

    private void updateCountWindow() {
        if (countWindow == null) return;
        Map<Integer, int[]> assigned = new HashMap<>(); // floorKey -> [s, t, eco]
        for (StaffManual sm : layout.values()) {
            for (FloorAssign fa : sm.rows) {
                int[] a = assigned.computeIfAbsent(fa.floorKey, k -> new int[3]);
                a[0] += fa.singleCount;
                a[1] += fa.twinCount;
                a[2] += fa.ecoCount;
            }
        }
        countWindow.update(assigned);
    }

    private void closeCountWindow() {
        if (countWindow != null) {
            countWindow.dispose();
            countWindow = null;
        }
    }

    // ====== 公開API ======

    public boolean isConfirmed() { return confirmed; }

    /** 確定された手動レイアウト（キャンセル時は null）
     *  ※ JDialog(Container).getLayout() との衝突を避けるため getResultLayout という名前にしている */
    public Map<String, StaffManual> getResultLayout() {
        return confirmed ? layout : null;
    }

    // ====== ヘルパー（UI） ======

    private String capText(NormalRoomDistributionDialog.StaffDistribution d) {
        if (d == null) return "";
        boolean hasMain = (d.mainSingleAssignedRooms + d.mainTwinAssignedRooms) > 0;
        boolean hasAnnex = (d.annexSingleAssignedRooms + d.annexTwinAssignedRooms) > 0;

        List<String> parts = new ArrayList<>();
        if (hasMain) {
            parts.add(String.format("本館 S%d / T%d",
                    d.mainSingleAssignedRooms, d.mainTwinAssignedRooms));
        }
        if (hasAnnex) {
            parts.add(String.format("別館 S%d / T%d",
                    d.annexSingleAssignedRooms, d.annexTwinAssignedRooms));
        }
        if (parts.isEmpty()) return "";   // どちらも0なら上限表示なし（対象外スタッフ）
        return "上限：" + String.join("　", parts);
    }

    private String chip(String label, int actual, int cap) {
        String color = (actual == cap) ? "#1B7F4B" : "#B3261E"; // 一致=緑 / 不一致=赤
        return String.format("<span style='color:%s'>&nbsp;%s %d/%d&nbsp;</span>", color, label, actual, cap);
    }

    /** 上限を持たない項目（Eco等）の入力数のみを表示するチップ */
    private String chipCount(String label, int actual) {
        return String.format("<span style='color:#333333'>&nbsp;%s %d&nbsp;</span>", label, actual);
    }

    private JLabel makeBadge(String text, Color fg, Color bg) {
        JLabel b = new JLabel(text);
        b.setOpaque(true);
        b.setBackground(bg);
        b.setForeground(fg);
        b.setBorder(new EmptyBorder(1, 8, 1, 8));
        b.setFont(new Font("MS Gothic", Font.PLAIN, 11));
        return b;
    }

    private JLabel headerCell(String text) {
        JLabel l = new JLabel(text, SwingConstants.CENTER);
        l.setFont(new Font("MS Gothic", Font.PLAIN, 11));
        l.setForeground(Color.GRAY);
        return l;
    }

    private JPanel wrapCenter(JComponent c) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        p.add(c);
        return p;
    }

    private JSpinner makeCountSpinner(int value, java.util.function.IntConsumer onChange) {
        JSpinner sp = new JSpinner(new SpinnerNumberModel(value, 0, 999, 1));
        sp.setPreferredSize(new Dimension(56, 26));
        applyZeroAsBlankFormatter(sp);
        sp.addChangeListener(e -> onChange.accept((int) sp.getValue()));
        return sp;
    }

    /** 0を空白で表示するフォーマッタをスピナーに適用（見やすさ向上のため） */
    private void applyZeroAsBlankFormatter(JSpinner spinner) {
        JSpinner.NumberEditor editor = (JSpinner.NumberEditor) spinner.getEditor();
        JFormattedTextField textField = editor.getTextField();

        NumberFormatter formatter = new NumberFormatter() {
            @Override
            public String valueToString(Object value) throws ParseException {
                if (value == null || (value instanceof Number && ((Number) value).intValue() == 0)) {
                    return "";
                }
                return super.valueToString(value);
            }

            @Override
            public Object stringToValue(String text) throws ParseException {
                if (text == null || text.trim().isEmpty()) {
                    return 0;
                }
                return super.stringToValue(text);
            }
        };
        formatter.setValueClass(Integer.class);
        formatter.setMinimum(0);
        formatter.setMaximum(999);

        textField.setFormatterFactory(new DefaultFormatterFactory(formatter));
        textField.setHorizontalAlignment(JTextField.CENTER);
    }

    // ---- 並び順（割り振り設定画面と同一ロジック）----
    private List<String> sortedStaffNames() {
        List<String> names = new ArrayList<>(layout.keySet());
        names.sort((a, b) -> compareStaff(distribution.get(a), distribution.get(b)));
        return names;
    }

    private int compareStaff(NormalRoomDistributionDialog.StaffDistribution s1,
                             NormalRoomDistributionDialog.StaffDistribution s2) {
        if (s1 == null && s2 == null) return 0;
        if (s1 == null) return 1;
        if (s2 == null) return -1;
        if (s1.isBathCleaning != s2.isBathCleaning) return s1.isBathCleaning ? -1 : 1;
        if (s1.isSuppliesOrder != s2.isSuppliesOrder) return s1.isSuppliesOrder ? -1 : 1;
        boolean b1 = "故障者制限".equals(s1.constraintType);
        boolean b2 = "故障者制限".equals(s2.constraintType);
        if (b1 != b2) return b1 ? -1 : 1;
        int p1 = buildingPriority(s1), p2 = buildingPriority(s2);
        if (p1 != p2) return Integer.compare(p1, p2);
        boolean v1 = "業者制限".equals(s1.constraintType);
        boolean v2 = "業者制限".equals(s2.constraintType);
        if (v1 != v2) return v1 ? -1 : 1;
        return s1.staffName.compareTo(s2.staffName);
    }

    private int buildingPriority(NormalRoomDistributionDialog.StaffDistribution s) {
        boolean hasMain = (s.mainSingleAssignedRooms + s.mainTwinAssignedRooms) > 0;
        boolean hasAnnex = (s.annexSingleAssignedRooms + s.annexTwinAssignedRooms) > 0;
        if (hasMain && !hasAnnex) return 1;  // 本館のみ
        if (hasMain && hasAnnex) return 2;   // 両方
        if (!hasMain && hasAnnex) return 3;  // 別館のみ
        return 9;                            // どちらも0
    }

    // ---- 建物絞り込み（各スタッフの担当建物の階のみ選択可。両方0なら空＝対象外）----
    private boolean staffHasMain(String name) {
        NormalRoomDistributionDialog.StaffDistribution d = distribution.get(name);
        return d != null && (d.mainSingleAssignedRooms + d.mainTwinAssignedRooms) > 0;
    }

    private boolean staffHasAnnex(String name) {
        NormalRoomDistributionDialog.StaffDistribution d = distribution.get(name);
        return d != null && (d.annexSingleAssignedRooms + d.annexTwinAssignedRooms) > 0;
    }

    /** そのスタッフが選べる階キー（本館優先で連結。両方0なら空） */
    private List<Integer> allowedFloorKeys(String name) {
        List<Integer> keys = new ArrayList<>();
        if (staffHasMain(name)) keys.addAll(mainFloorKeys);
        if (staffHasAnnex(name)) keys.addAll(annexFloorKeys);
        return keys;
    }

    private int defaultFloorKeyFor(String name) {
        List<Integer> keys = allowedFloorKeys(name);
        return keys.isEmpty() ? 0 : keys.get(0);
    }

    private boolean defaultIsMainFor(String name) {
        return staffHasMain(name); // 本館を持てば先頭は本館。持たなければ別館(またはnone)
    }

    // ============================================================
    // 手動レイアウト → StaffAssignment 変換（CP-SATを通さず確定）
    // ============================================================

    /**
     * 手動レイアウトから StaffAssignment のリストを構築する。
     * 各階の「S数（シングル等）」は、その階の実在タイプ(S/D/FD等)に貪欲に割り付けて RoomAllocation を作る。
     * リネン庫担当フロアはチェックされた階をそのまま設定する（自動マッチングは行わない）。
     *
     * @param layout         確定済み手動レイアウト
     * @param cleaningData   清掃データ（階別の実タイプ在庫の取得に使用）
     * @param availableStaff 利用可能スタッフ（名前で照合）
     * @param distribution   割り振り設定（バス/備品/リネン庫担当フラグの取得に使用）
     * @param bathType       大浴場清掃タイプ（バス担当者へ付与）
     */
    public static List<AdaptiveRoomOptimizer.StaffAssignment> buildStaffAssignments(
            Map<String, StaffManual> layout,
            FileProcessor.CleaningData cleaningData,
            List<FileProcessor.Staff> availableStaff,
            Map<String, NormalRoomDistributionDialog.StaffDistribution> distribution,
            AdaptiveRoomOptimizer.BathCleaningType bathType) {

        // 階ごとの「単一カテゴリ(S/D/FD..)別の在庫タイプ」を準備（割付順序を決めるため）
        // floorKey -> (mappedSingleType -> 残数)。Tは別管理。
        Map<Integer, LinkedHashMap<String, Integer>> floorSingleTypes = new HashMap<>();
        Map<Integer, Integer> floorTwinAvail = new HashMap<>();

        List<FileProcessor.Room> allRooms = new ArrayList<>();
        if (cleaningData.mainRooms != null) allRooms.addAll(cleaningData.mainRooms);
        if (cleaningData.annexRooms != null) allRooms.addAll(cleaningData.annexRooms);

        for (FileProcessor.Room room : allRooms) {
            if (room.isEcoClean) continue; // 通常室のみ（Ecoは ecoRooms 側で別カウント）
            int floor = room.floor;
            String mapped = mapType(room.roomType);
            boolean isMainRoom = "本館".equals(room.building);
            if ("T".equals(mapped) && !isMainRoom) {
                // 別館ツインのみ別管理（従来どおり）
                floorTwinAvail.merge(floor, 1, Integer::sum);
            } else {
                // ★★★本館ツイン統合: 本館ツインはシングル等の在庫として扱い、
                //   "T" カテゴリのまま貪欲割付の対象に含める（本館は区別なしのため）
                floorSingleTypes.computeIfAbsent(floor, k -> new LinkedHashMap<>())
                        .merge(mapped, 1, Integer::sum);
            }
        }

        Map<String, FileProcessor.Staff> staffByName = new HashMap<>();
        for (FileProcessor.Staff s : availableStaff) staffByName.put(s.name, s);

        List<AdaptiveRoomOptimizer.StaffAssignment> result = new ArrayList<>();

        for (Map.Entry<String, StaffManual> en : layout.entrySet()) {
            String name = en.getKey();
            StaffManual sm = en.getValue();
            FileProcessor.Staff staff = staffByName.get(name);
            if (staff == null) continue;

            NormalRoomDistributionDialog.StaffDistribution dist = distribution.get(name);

            Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> mainAlloc = new HashMap<>();
            Map<Integer, AdaptiveRoomOptimizer.RoomAllocation> annexAlloc = new HashMap<>();
            List<Integer> linenFloors = new ArrayList<>();

            for (FloorAssign fa : sm.rows) {
                if (fa.singleCount == 0 && fa.twinCount == 0 && fa.ecoCount == 0) continue;

                Map<String, Integer> roomCounts = new HashMap<>();
                // ツインは "T" にまとめる
                if (fa.twinCount > 0) roomCounts.put("T", fa.twinCount);

                // シングル等は実在タイプ(S/D/FD..)へ貪欲割付
                int remaining = fa.singleCount;
                LinkedHashMap<String, Integer> avail = floorSingleTypes.get(fa.floorKey);
                if (avail != null) {
                    // S を最優先、その後その他
                    List<String> order = new ArrayList<>(avail.keySet());
                    order.sort((a, b) -> {
                        if (a.equals("S")) return -1;
                        if (b.equals("S")) return 1;
                        return a.compareTo(b);
                    });
                    for (String t : order) {
                        if (remaining <= 0) break;
                        int take = Math.min(remaining, avail.getOrDefault(t, 0));
                        if (take > 0) {
                            roomCounts.merge(t, take, Integer::sum);
                            remaining -= take;
                            avail.put(t, avail.get(t) - take);  // ★追加: 取ったぶんを共有在庫から減算（次のスタッフ/行へ持ち越す）
                        }
                    }
                }
                if (remaining > 0) {
                    // 在庫情報が取れない/不足時は "S" として残りを計上（RoomNumberAssigner側で取得）
                    roomCounts.merge("S", remaining, Integer::sum);
                }

                AdaptiveRoomOptimizer.RoomAllocation allocation =
                        new AdaptiveRoomOptimizer.RoomAllocation(roomCounts, fa.ecoCount);

                if (fa.isMain) {
                    mainAlloc.put(fa.floorKey, allocation);
                } else {
                    annexAlloc.put(fa.floorKey, allocation);
                }
                if (fa.linen) linenFloors.add(fa.floorKey);
            }

            boolean isBath = dist != null && dist.isBathCleaning;
            boolean isSupplies = dist != null && dist.isSuppliesOrder;
            boolean isLinen = !linenFloors.isEmpty();
            AdaptiveRoomOptimizer.BathCleaningType type =
                    isBath ? bathType : AdaptiveRoomOptimizer.BathCleaningType.NORMAL;

            AdaptiveRoomOptimizer.StaffAssignment sa = new AdaptiveRoomOptimizer.StaffAssignment(
                    staff, mainAlloc, annexAlloc, type, isLinen, linenFloors.size(), isSupplies);
            sa.setLinenClosetFloors(linenFloors);
            result.add(sa);
        }

        return result;
    }

    /** 階別在庫(FloorInv)を清掃データから構築（ダイアログへ渡す用）。floorKey は Room.floor をそのまま使用
     *  ★★★本館ツイン統合: 本館のツイン部屋はシングル等在庫(singleAvail)へ合算する（本館 twinAvail=0） */
    public static Map<Integer, FloorInv> buildInventory(FileProcessor.CleaningData cleaningData) {
        Map<Integer, int[]> agg = new HashMap<>();   // floorKey -> [single, twin, eco]
        Map<Integer, Boolean> isMainMap = new HashMap<>();

        if (cleaningData.mainRooms != null) {
            for (FileProcessor.Room room : cleaningData.mainRooms) {
                int[] a = agg.computeIfAbsent(room.floor, k -> new int[3]);
                isMainMap.put(room.floor, true);
                accumulate(a, room, true);
            }
        }
        if (cleaningData.annexRooms != null) {
            for (FileProcessor.Room room : cleaningData.annexRooms) {
                int[] a = agg.computeIfAbsent(room.floor, k -> new int[3]);
                isMainMap.putIfAbsent(room.floor, false);
                accumulate(a, room, false);
            }
        }

        Map<Integer, FloorInv> inv = new HashMap<>();
        for (Map.Entry<Integer, int[]> en : agg.entrySet()) {
            int key = en.getKey();
            boolean isMain = isMainMap.getOrDefault(key, key <= 10);
            int display = isMain ? key : (key > 20 ? key - 20 : key);
            int[] a = en.getValue();
            inv.put(key, new FloorInv(key, isMain, display, a[0], a[1], a[2]));
        }
        return inv;
    }

    private static void accumulate(int[] a, FileProcessor.Room room, boolean isMain) {
        if (room.isEcoClean) {
            a[2]++; // eco
        } else if ("T".equals(mapType(room.roomType))) {
            if (isMain) {
                // ★★★本館ツイン統合: 本館ツインはシングル等在庫へ合算（区別なし）
                a[0]++;
            } else {
                a[1]++; // twin（別館のみ）
            }
        } else {
            a[0]++; // single等
        }
    }

    /** RoomNumberAssigner.mapRoomType と同一のマッピング */
    private static String mapType(String originalType) {
        if (originalType == null) return "";
        switch (originalType.toUpperCase()) {
            case "S": case "NS": case "ANS": case "ABF": case "AKS": case "ZS":
                return "S";
            case "D": case "ND": case "AND": case "ZD":
                return "D";
            case "T": case "NT": case "ANT": case "ADT": case "ZT":
                return "T";
            case "FD": case "NFD":
                return "FD";
            default:
                return originalType;
        }
    }

    // ============================================================
    // 「割り当て済み数」ウィンドウ（同時に開く・リアルタイム更新）
    // ============================================================
    static class AssignedCountWindow extends JDialog {
        private final Map<Integer, FloorInv> inventory;
        private final List<Integer> mainKeys;
        private final List<Integer> annexKeys;
        private final Map<Integer, JLabel[]> cells = new HashMap<>(); // floorKey -> [S,T,Eco]

        AssignedCountWindow(Window owner, Map<Integer, FloorInv> inventory,
                            List<Integer> mainKeys, List<Integer> annexKeys) {
            super(owner, "階別 割り当て済み数（リアルタイム）", ModalityType.MODELESS);
            this.inventory = inventory;
            this.mainKeys = mainKeys;
            this.annexKeys = annexKeys;
            build();
            setSize(360, 620);
            if (owner != null) {
                setLocation(owner.getX() + owner.getWidth() + 8, owner.getY());
            }
        }

        private void build() {
            JPanel root = new JPanel();
            root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
            root.setBorder(new EmptyBorder(8, 10, 8, 10));

            root.add(sectionLabel("本館"));
            root.add(buildTable(mainKeys));
            root.add(Box.createVerticalStrut(10));
            root.add(sectionLabel("別館"));
            root.add(buildTable(annexKeys));

            add(new JScrollPane(root));
        }

        private JLabel sectionLabel(String text) {
            JLabel l = new JLabel(text);
            l.setFont(new Font("MS Gothic", Font.BOLD, 12));
            l.setAlignmentX(Component.LEFT_ALIGNMENT);
            return l;
        }

        private JPanel buildTable(List<Integer> keys) {
            JPanel table = new JPanel(new GridLayout(keys.size() + 1, 4, 2, 2));
            table.setAlignmentX(Component.LEFT_ALIGNMENT);
            table.add(head("階"));
            table.add(head("S(割当/総)"));
            table.add(head("T(割当/総)"));
            table.add(head("Eco(割当/総)"));
            for (int key : keys) {
                FloorInv fi = inventory.get(key);
                if (fi == null) continue;
                table.add(new JLabel(fi.displayFloor + "F", SwingConstants.CENTER));
                JLabel s = cell(0, fi.singleAvail);
                JLabel t = cell(0, fi.twinAvail);
                JLabel eco = cell(0, fi.ecoAvail);
                table.add(s); table.add(t); table.add(eco);
                cells.put(key, new JLabel[]{s, t, eco});
            }
            return table;
        }

        private JLabel head(String text) {
            JLabel l = new JLabel(text, SwingConstants.CENTER);
            l.setFont(new Font("MS Gothic", Font.PLAIN, 11));
            l.setForeground(Color.GRAY);
            return l;
        }

        private JLabel cell(int assigned, int total) {
            JLabel l = new JLabel(assigned + " / " + total, SwingConstants.CENTER);
            l.setFont(new Font("MS Gothic", Font.PLAIN, 12));
            return l;
        }

        void update(Map<Integer, int[]> assigned) {
            for (Map.Entry<Integer, JLabel[]> en : cells.entrySet()) {
                int key = en.getKey();
                JLabel[] ls = en.getValue();
                FloorInv fi = inventory.get(key);
                if (fi == null) continue;
                int[] a = assigned.getOrDefault(key, new int[3]);
                setCell(ls[0], a[0], fi.singleAvail);
                setCell(ls[1], a[1], fi.twinAvail);
                setCell(ls[2], a[2], fi.ecoAvail);
            }
        }

        private void setCell(JLabel l, int assigned, int total) {
            l.setText(assigned + " / " + total);
            if (assigned > total) {
                l.setForeground(new Color(0xB3, 0x26, 0x1E)); // 超過=赤
            } else if (total > 0 && assigned == total) {
                l.setForeground(new Color(0x1B, 0x7F, 0x4B)); // 完了=緑
            } else {
                l.setForeground(Color.BLACK);
            }
        }
    }
}