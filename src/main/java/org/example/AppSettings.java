package org.example;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * ★新規: アプリ設定（記憶されたファイルパスなど）を一元管理するクラス。
 *
 * 設定はユーザーフォルダ直下の room_assignment_settings.properties に保存する。
 * （例: C:\Users\ユーザー名\room_assignment_settings.properties）
 * Program Files 配下ではなくユーザーフォルダに置くため、
 * インストール場所や起動方法（ショートカット等）に関係なく読み書きできる。
 *
 * 【使い方】
 *   // パスの記憶（即座にファイルへ保存される）
 *   AppSettings.getInstance().setPath(AppSettings.KEY_INSTRUCTION_TEMPLATE, file.getAbsolutePath());
 *
 *   // 記憶されたパスの取得（実在するファイルのみ返す。無ければ null）
 *   File f = AppSettings.getInstance().getExistingFile(AppSettings.KEY_INSTRUCTION_TEMPLATE);
 *
 * 【将来の拡張】
 *   部屋状態CSV・シフトxls・エコDBの自動読み込みを実装する際は、
 *   下記の KEY_ROOM_STATUS_CSV / KEY_SHIFT_XLS / KEY_ECO_DB を
 *   同じ要領で setPath / getExistingFile するだけでよい。
 *   新しい設定項目が必要になったら、キー定数をこのクラスに追加していく。
 */
public class AppSettings {

    /** 設定ファイル名（ユーザーフォルダ直下に作成される） */
    private static final String SETTINGS_FILE_NAME = "room_assignment_settings.properties";

    // ===== 設定キー =====
    /** 原本指示書作成システム（.xlsm）のパス（旧: 清掃指示書（原本）.xlsm） */
    public static final String KEY_INSTRUCTION_TEMPLATE = "instruction.template.path";
    /** 部屋状態CSV・シフトxlsx・エコDBを収めた共通データフォルダーのパス */
    public static final String KEY_DATA_FOLDER = "data.folder.path";
    /** 部屋状態CSV のパス（将来の自動読み込み用） */
    public static final String KEY_ROOM_STATUS_CSV = "room.status.csv.path";
    /** シフト表xls のパス（将来の自動読み込み用） */
    public static final String KEY_SHIFT_XLS = "shift.xls.path";
    /** エコDB のパス（将来の自動読み込み用） */
    public static final String KEY_ECO_DB = "eco.db.path";

    /** シングルトンインスタンス */
    private static AppSettings instance;

    private final File settingsFile;
    private final Properties props = new Properties();

    /**
     * シングルトン取得。アプリ内のどのクラスからでも
     * AppSettings.getInstance() で同じ設定にアクセスできる。
     */
    public static synchronized AppSettings getInstance() {
        if (instance == null) {
            instance = new AppSettings();
        }
        return instance;
    }

    private AppSettings() {
        this.settingsFile = new File(System.getProperty("user.home"), SETTINGS_FILE_NAME);
        load();
    }

    /**
     * 設定ファイルを読み込む。ファイルが無い・読めない場合は空の設定として扱う。
     */
    public void load() {
        props.clear();
        if (!settingsFile.exists()) {
            return;
        }
        try (FileInputStream fis = new FileInputStream(settingsFile)) {
            props.load(fis);
        } catch (Exception e) {
            // 設定ファイルが壊れている等の場合は記憶なしとして扱う
            props.clear();
        }
    }

    /**
     * 現在の設定内容をファイルへ保存する。
     * @return 保存に成功したら true（失敗しても例外は投げない）
     */
    public boolean save() {
        try (FileOutputStream fos = new FileOutputStream(settingsFile)) {
            props.store(fos, "Room Assignment Application Settings");
            return true;
        } catch (Exception e) {
            // 保存に失敗しても呼び出し元の処理は続行できるようにする
            return false;
        }
    }

    /**
     * 記憶されたパス文字列を取得する。
     * @return 記憶されたパス。未設定なら null
     */
    public String getPath(String key) {
        return props.getProperty(key);
    }

    /**
     * 記憶されたパスを File として取得する。
     * @return 実在するファイルのみ返す。未設定または実在しない場合は null
     */
    public File getExistingFile(String key) {
        String path = getPath(key);
        if (path == null || path.isEmpty()) {
            return null;
        }
        File f = new File(path);
        return (f.exists() && f.isFile()) ? f : null;
    }

    /**
     * 記憶されたパスをフォルダーとして取得する。
     * @return 実在するフォルダーのみ返す。未設定または実在しない場合は null
     */
    public File getExistingFolder(String key) {
        String path = getPath(key);
        if (path == null || path.isEmpty()) {
            return null;
        }
        File f = new File(path);
        return (f.exists() && f.isDirectory()) ? f : null;
    }

    /**
     * パスを記憶し、即座にファイルへ保存する。
     */
    public void setPath(String key, String path) {
        if (path == null) {
            props.remove(key);
        } else {
            props.setProperty(key, path);
        }
        save();
    }
}