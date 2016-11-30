/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.database.sqlite;

import android.content.Context;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase.CursorFactory;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * A helper class to manage database creation and version management.
 *
 * <p>You create a subclass implementing {@link #onCreate}, {@link #onUpgrade} and
 * optionally {@link #onOpen}, and this class takes care of opening the database
 * if it exists, creating it if it does not, and upgrading it as necessary.
 * Transactions are used to make sure the database is always in a sensible state.
 *
 * <p>This class makes it easy for {@link android.content.ContentProvider}
 * implementations to defer opening and upgrading the database until first use,
 * to avoid blocking application startup with long-running database upgrades.
 *
 * <p>For an example, see the NotePadProvider class in the NotePad sample application,
 * in the <em>samples/</em> directory of the SDK.</p>
 *
 * <p class="note"><strong>Note:</strong> this class assumes
 * monotonically increasing version numbers for upgrades.</p>
 */
public abstract class SQLiteOpenHelper {
    private static final String TAG = SQLiteOpenHelper.class.getSimpleName();
    private static final String INCOGNITO_DB_PREFIX = "INCOGNITO_TIRAMISU_";
    private static final String INCONGNITO_SETTING_FILE = "/sdcard/incog.config";

    // When true, getReadableDatabase returns a read-only database if it is just being opened.
    // The database handle is reopened in read/write mode when getWritableDatabase is called.
    // We leave this behavior disabled in production because it is inefficient and breaks
    // many applications.  For debugging purposes it can be useful to turn on strict
    // read-only semantics to catch applications that call getReadableDatabase when they really
    // wanted getWritableDatabase.
    private static final boolean DEBUG_STRICT_READONLY = false;

    private final Context mContext;
    private final String mName;
    private final CursorFactory mFactory;
    private final int mNewVersion;

    private String mIncogDbPath;
    private boolean mIsIncognito;
    private SQLiteDatabase mDatabase;
    private boolean mIsInitializing;
    private boolean mEnableWriteAheadLogging;
    private final DatabaseErrorHandler mErrorHandler;

    /**
     * Create a helper object to create, open, and/or manage a database.
     * This method always returns very quickly.  The database is not actually
     * created or opened until one of {@link #getWritableDatabase} or
     * {@link #getReadableDatabase} is called.
     *
     * @param context to use to open or create the database
     * @param name of the database file, or null for an in-memory database
     * @param factory to use for creating cursor objects, or null for the default
     * @param version number of the database (starting at 1); if the database is older,
     *     {@link #onUpgrade} will be used to upgrade the database; if the database is
     *     newer, {@link #onDowngrade} will be used to downgrade the database
     */
    public SQLiteOpenHelper(Context context, String name, CursorFactory factory, int version) {
        this(context, name, factory, version, null);
    }

    /**
     * Create a helper object to create, open, and/or manage a database.
     * The database is not actually created or opened until one of
     * {@link #getWritableDatabase} or {@link #getReadableDatabase} is called.
     *
     * <p>Accepts input param: a concrete instance of {@link DatabaseErrorHandler} to be
     * used to handle corruption when sqlite reports database corruption.</p>
     *
     * @param context to use to open or create the database
     * @param name of the database file, or null for an in-memory database
     * @param factory to use for creating cursor objects, or null for the default
     * @param version number of the database (starting at 1); if the database is older,
     *     {@link #onUpgrade} will be used to upgrade the database; if the database is
     *     newer, {@link #onDowngrade} will be used to downgrade the database
     * @param errorHandler the {@link DatabaseErrorHandler} to be used when sqlite reports database
     * corruption, or null to use the default error handler.
     */
    public SQLiteOpenHelper(Context context, String name, CursorFactory factory, int version,
            DatabaseErrorHandler errorHandler) {
        if (version < 1) throw new IllegalArgumentException("Version must be >= 1, was " + version);

        mContext = context;
        mFactory = factory;
        mNewVersion = version;
        mErrorHandler = errorHandler;

        // read the incognito state and change the DB name accordingly
        mIsIncognito = readIncognitoState(context.getPackageName());
        Log.e(TAG, "Tiramisu: SQLiteOpenHelper - incognito " + mIsIncognito);
        if (mIsIncognito) {
            mName = INCOGNITO_DB_PREFIX + name;
            Log.e(TAG, "Tiramisu: SQLiteOpenHelper Original DB name " + name + " changed " + mName);
        } else {
            mName = name;
        }
    }

    /**
     * Return the name of the SQLite database being opened, as given to
     * the constructor.
     */
    public String getDatabaseName() {
        if (mIsIncognito) {
            return mName.substring(INCOGNITO_DB_PREFIX.length());
        }
        return mName;
    }

    private boolean readIncognitoState(String incogPackageName)  {
        if(isExternalStorageAvailable(true /* needWriteAccess */)) {
            try {
                File f = new File(INCONGNITO_SETTING_FILE);
                if (!f.exists()) {
                    Log.d("Tiramisu", INCONGNITO_SETTING_FILE + " file does not exist");
                } else {
                    boolean incognitoMode = getAppIncognitoState(incogPackageName, f);
                    Log.d("Tiramisu", incogPackageName + " is started in " + incognitoMode);
                    return incognitoMode;
                }
            } catch (Exception e) {
                Log.e("Tiramisu", "cannot get package info for " + e.toString());
            }
        }
        return false;
    }

    public boolean isExternalStorageAvailable(boolean needWriteAccess) {
        String state = Environment.getExternalStorageState();
        Log.e("Tiramisu", "storage state is " + state);

        if (Environment.MEDIA_MOUNTED.equals(state)) {
            return true;
        } else if (!needWriteAccess &&
                Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
            return true;
        }
        return false;
    }
    boolean getAppIncognitoState(String packageName, File f) {
        FileInputStream fios;
        try (FileReader fileReader = new FileReader(f)) {
            Properties config = new Properties();
            config.load(fileReader);
            String value = config.getProperty(packageName);
            if (value != null) {
                switch(value) {
                    case "true":
                        return true;
                    case "false":
                    default:
                        return false;
                }
            }
        } catch (FileNotFoundException e) {
            Log.d("Tiramisu", "App does not have permission to read external storage");
        } catch (Exception e) {
            e.printStackTrace();
        }

        return false;
    }

    /**
     * Enables or disables the use of write-ahead logging for the database.
     *
     * Write-ahead logging cannot be used with read-only databases so the value of
     * this flag is ignored if the database is opened read-only.
     *
     * @param enabled True if write-ahead logging should be enabled, false if it
     * should be disabled.
     *
     * @see SQLiteDatabase#enableWriteAheadLogging()
     */
    public void setWriteAheadLoggingEnabled(boolean enabled) {
        synchronized (this) {
            if (mEnableWriteAheadLogging != enabled) {
                if (mDatabase != null && mDatabase.isOpen() && !mDatabase.isReadOnly()) {
                    if (enabled) {
                        mDatabase.enableWriteAheadLogging();
                    } else {
                        mDatabase.disableWriteAheadLogging();
                    }
                }
                mEnableWriteAheadLogging = enabled;
            }
        }
    }

    /**
     * Create and/or open a database that will be used for reading and writing.
     * The first time this is called, the database will be opened and
     * {@link #onCreate}, {@link #onUpgrade} and/or {@link #onOpen} will be
     * called.
     *
     * <p>Once opened successfully, the database is cached, so you can
     * call this method every time you need to write to the database.
     * (Make sure to call {@link #close} when you no longer need the database.)
     * Errors such as bad permissions or a full disk may cause this method
     * to fail, but future attempts may succeed if the problem is fixed.</p>
     *
     * <p class="caution">Database upgrade may take a long time, you
     * should not call this method from the application main thread, including
     * from {@link android.content.ContentProvider#onCreate ContentProvider.onCreate()}.
     *
     * @throws SQLiteException if the database cannot be opened for writing
     * @return a read/write database object valid until {@link #close} is called
     */
    public SQLiteDatabase getWritableDatabase() {
        synchronized (this) {
            return getDatabaseLocked(true);
        }
    }

    /**
     * Create and/or open a database.  This will be the same object returned by
     * {@link #getWritableDatabase} unless some problem, such as a full disk,
     * requires the database to be opened read-only.  In that case, a read-only
     * database object will be returned.  If the problem is fixed, a future call
     * to {@link #getWritableDatabase} may succeed, in which case the read-only
     * database object will be closed and the read/write object will be returned
     * in the future.
     *
     * <p class="caution">Like {@link #getWritableDatabase}, this method may
     * take a long time to return, so you should not call it from the
     * application main thread, including from
     * {@link android.content.ContentProvider#onCreate ContentProvider.onCreate()}.
     *
     * @throws SQLiteException if the database cannot be opened
     * @return a database object valid until {@link #getWritableDatabase}
     *     or {@link #close} is called.
     */
    public SQLiteDatabase getReadableDatabase() {
        synchronized (this) {
            return getDatabaseLocked(false);
        }
    }

    private SQLiteDatabase getDatabaseLocked(boolean writable) {
        if (mDatabase != null) {
            if (!mDatabase.isOpen()) {
                // Darn!  The user closed the database by calling mDatabase.close().
                mDatabase = null;
            } else if (!writable || !mDatabase.isReadOnly()) {
                // The database is already open for business.
                return mDatabase;
            }
        }

        if (mIsInitializing) {
            throw new IllegalStateException("getDatabase called recursively");
        }

        SQLiteDatabase db = mDatabase;
        try {
            mIsInitializing = true;

            if (db != null) {
                if (writable && db.isReadOnly()) {
                    db.reopenReadWrite();
                }
            } else if (mName == null) {
                db = SQLiteDatabase.create(null);
            } else {
                if (mIsIncognito) {
                    mIncogDbPath = mContext.getDatabasePath(mName).getPath();

                    // delete the incognito database if it already exists
                    if (mContext.deleteDatabase(mName)) {
                        Log.e(TAG, "Tiramisu: existing incognito database " + mName + " deleted");
                    }

                    // copy the original database to an incognito DB
                    copyOriginalDB();
                }

                try {
                    if (DEBUG_STRICT_READONLY && !writable) {
                        final String path = mContext.getDatabasePath(mName).getPath();
                        db = SQLiteDatabase.openDatabase(path, mFactory,
                                SQLiteDatabase.OPEN_READONLY, mErrorHandler);
                    } else {
                        db = mContext.openOrCreateDatabase(mName, mEnableWriteAheadLogging ?
                                Context.MODE_ENABLE_WRITE_AHEAD_LOGGING : 0,
                                mFactory, mErrorHandler);
                    }
                } catch (SQLiteException ex) {
                    if (writable) {
                        throw ex;
                    }
                    Log.e(TAG, "Couldn't open " + mName
                            + " for writing (will try read-only):", ex);
                    final String path = mContext.getDatabasePath(mName).getPath();
                    db = SQLiteDatabase.openDatabase(path, mFactory,
                            SQLiteDatabase.OPEN_READONLY, mErrorHandler);
                }
            }

            onConfigure(db);

            final int version = db.getVersion();
            if (version != mNewVersion) {
                if (db.isReadOnly()) {
                    throw new SQLiteException("Can't upgrade read-only database from version " +
                            db.getVersion() + " to " + mNewVersion + ": " + mName);
                }

                db.beginTransaction();
                try {
                    if (version == 0) {
                        onCreate(db);
                    } else {
                        if (version > mNewVersion) {
                            onDowngrade(db, version, mNewVersion);
                        } else {
                            onUpgrade(db, version, mNewVersion);
                        }
                    }
                    db.setVersion(mNewVersion);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }

            onOpen(db);

            if (db.isReadOnly()) {
                Log.w(TAG, "Opened " + mName + " in read-only mode");
            }

            mDatabase = db;
            return db;
        } finally {
            mIsInitializing = false;
            if (db != null && db != mDatabase) {
                db.close();
            }
        }
    }

    private void copyOriginalDB() {
        String originalDbName = mName.substring(INCOGNITO_DB_PREFIX.length());
        File originalDbFile = mContext.getDatabasePath(originalDbName);

        try (InputStream is = new FileInputStream(originalDbFile);
             OutputStream os = new FileOutputStream(new File(mIncogDbPath))) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) > 0) {
                os.write(buf, 0, len);
            }
            Log.e(TAG, "Tiramisu: Original DB " + originalDbName + " cloned");
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Tiramisu: Original DB " + originalDbName + " does not exist");
        } catch (IOException e) {
            Log.e(TAG, "Tiramisu: Error copying Original DB " + originalDbName);
            e.printStackTrace();
        }
    }

    /**
     * Close any open database object.
     */
    public synchronized void close() {
        if (mIsInitializing) throw new IllegalStateException("Closed during initialization");

        if (mDatabase != null && mDatabase.isOpen()) {
            mDatabase.close();
            mDatabase = null;
        }

        if (mIsIncognito) {
            // delete the incognito database
            mContext.deleteDatabase(mName);
        }
    }

    /**
     * Called when the database connection is being configured, to enable features
     * such as write-ahead logging or foreign key support.
     * <p>
     * This method is called before {@link #onCreate}, {@link #onUpgrade},
     * {@link #onDowngrade}, or {@link #onOpen} are called.  It should not modify
     * the database except to configure the database connection as required.
     * </p><p>
     * This method should only call methods that configure the parameters of the
     * database connection, such as {@link SQLiteDatabase#enableWriteAheadLogging}
     * {@link SQLiteDatabase#setForeignKeyConstraintsEnabled},
     * {@link SQLiteDatabase#setLocale}, {@link SQLiteDatabase#setMaximumSize},
     * or executing PRAGMA statements.
     * </p>
     *
     * @param db The database.
     */
    public void onConfigure(SQLiteDatabase db) {}

    /**
     * Called when the database is created for the first time. This is where the
     * creation of tables and the initial population of the tables should happen.
     *
     * @param db The database.
     */
    public abstract void onCreate(SQLiteDatabase db);

    /**
     * Called when the database needs to be upgraded. The implementation
     * should use this method to drop tables, add tables, or do anything else it
     * needs to upgrade to the new schema version.
     *
     * <p>
     * The SQLite ALTER TABLE documentation can be found
     * <a href="http://sqlite.org/lang_altertable.html">here</a>. If you add new columns
     * you can use ALTER TABLE to insert them into a live table. If you rename or remove columns
     * you can use ALTER TABLE to rename the old table, then create the new table and then
     * populate the new table with the contents of the old table.
     * </p><p>
     * This method executes within a transaction.  If an exception is thrown, all changes
     * will automatically be rolled back.
     * </p>
     *
     * @param db The database.
     * @param oldVersion The old database version.
     * @param newVersion The new database version.
     */
    public abstract void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion);

    /**
     * Called when the database needs to be downgraded. This is strictly similar to
     * {@link #onUpgrade} method, but is called whenever current version is newer than requested one.
     * However, this method is not abstract, so it is not mandatory for a customer to
     * implement it. If not overridden, default implementation will reject downgrade and
     * throws SQLiteException
     *
     * <p>
     * This method executes within a transaction.  If an exception is thrown, all changes
     * will automatically be rolled back.
     * </p>
     *
     * @param db The database.
     * @param oldVersion The old database version.
     * @param newVersion The new database version.
     */
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new SQLiteException("Can't downgrade database from version " +
                oldVersion + " to " + newVersion);
    }

    /**
     * Called when the database has been opened.  The implementation
     * should check {@link SQLiteDatabase#isReadOnly} before updating the
     * database.
     * <p>
     * This method is called after the database connection has been configured
     * and after the database schema has been created, upgraded or downgraded as necessary.
     * If the database connection must be configured in some way before the schema
     * is created, upgraded, or downgraded, do it in {@link #onConfigure} instead.
     * </p>
     *
     * @param db The database.
     */
    public void onOpen(SQLiteDatabase db) {}
}
