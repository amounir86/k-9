package com.fsck.k9.provider;

import java.util.List;

import com.fsck.k9.Account;
import com.fsck.k9.Preferences;
import com.fsck.k9.helper.StringUtils;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.store.LocalStore;
import com.fsck.k9.mail.store.LockableDatabase;
import com.fsck.k9.mail.store.LockableDatabase.DbCallback;
import com.fsck.k9.mail.store.LockableDatabase.WrappedException;
import com.fsck.k9.mail.store.UnavailableStorageException;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

/**
 * Content Provider used to display the message list etc.
 *
 * <p>
 * For now this content provider is for internal use only. In the future we may allow third-party
 * apps to access K-9 Mail content using this content provider.
 * </p>
 */
/*
 * TODO:
 * - modify MessagingController (or LocalStore?) to call ContentResolver.notifyChange() to trigger
 *   notifications when the underlying data changes.
 * - add support for message threading
 * - add support for search views
 * - add support for querying multiple accounts (e.g. "Unified Inbox")
 * - add support for account list and folder list
 */
public class EmailProvider extends ContentProvider {
    private static final UriMatcher sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    public static final String AUTHORITY = "org.k9mail.provider.email";

    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);


    /*
     * Constants that are used for the URI matching.
     */
    private static final int MESSAGE_BASE = 0;
    private static final int MESSAGES = MESSAGE_BASE;
    //private static final int MESSAGES_THREADED = MESSAGE_BASE + 1;
    //private static final int MESSAGES_THREAD = MESSAGE_BASE + 2;


    private static final String MESSAGES_TABLE = "messages";


    static {
        UriMatcher matcher = sUriMatcher;

        matcher.addURI(AUTHORITY, "account/*/messages", MESSAGES);
        //matcher.addURI(AUTHORITY, "account/*/messages/threaded", MESSAGES_THREADED);
        //matcher.addURI(AUTHORITY, "account/*/thread/#", MESSAGES_THREAD);
    }


    public interface MessageColumns {
        public static final String ID = "id";
        public static final String UID = "uid";
        public static final String INTERNAL_DATE = "internal_date";
        public static final String SUBJECT = "subject";
        public static final String DATE = "date";
        public static final String MESSAGE_ID = "message_id";
        public static final String SENDER_LIST = "sender_list";
        public static final String TO_LIST = "to_list";
        public static final String CC_LIST = "cc_list";
        public static final String BCC_LIST = "bcc_list";
        public static final String REPLY_TO_LIST = "reply_to_list";
        public static final String FLAGS = "flags";
        public static final String ATTACHMENT_COUNT = "attachment_count";
        public static final String FOLDER_ID = "folder_id";
        public static final String PREVIEW = "preview";
        public static final String THREAD_ROOT = "thread_root";
        public static final String THREAD_PARENT = "thread_parent";
    }

    private interface InternalMessageColumns extends MessageColumns {
        public static final String DELETED = "deleted";
        public static final String EMPTY = "empty";
        public static final String TEXT_CONTENT = "text_content";
        public static final String HTML_CONTENT = "html_content";
        public static final String MIME_TYPE = "mime_type";
    }


    private Preferences mPreferences;


    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {

        int match = sUriMatcher.match(uri);
        if (match < 0) {
            throw new IllegalArgumentException("Unknown URI: " + uri);
        }

        ContentResolver contentResolver = getContext().getContentResolver();
        Cursor cursor = null;
        switch (match) {
            case MESSAGES: {
                List<String> segments = uri.getPathSegments();
                String accountUuid = segments.get(1);

                cursor = getMessages(accountUuid, projection, selection, selectionArgs, sortOrder);

                cursor.setNotificationUri(contentResolver, uri);
                break;
            }
        }

        return new IdTrickeryCursor(cursor);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new RuntimeException("not implemented yet");
    }

    protected Cursor getMessages(String accountUuid, final String[] projection,
            final String selection, final String[] selectionArgs, final String sortOrder) {

        Account account = getAccount(accountUuid);
        LockableDatabase database = getDatabase(account);

        try {
            return database.execute(false, new DbCallback<Cursor>() {
                @Override
                public Cursor doDbWork(SQLiteDatabase db) throws WrappedException,
                        UnavailableStorageException {

                    String where;
                    if (StringUtils.isNullOrEmpty(selection)) {
                        where = InternalMessageColumns.DELETED + "=0 AND " +
                                InternalMessageColumns.EMPTY + "!=1";
                    } else {
                        where = "(" + selection + ") AND " +
                                InternalMessageColumns.DELETED + "=0 AND " +
                                InternalMessageColumns.EMPTY + "!=1";
                    }

                    return db.query(MESSAGES_TABLE, projection, where, selectionArgs, null, null,
                            sortOrder);
                }
            });
        } catch (UnavailableStorageException e) {
            throw new RuntimeException("Storage not available", e);
        }
    }

    private Account getAccount(String accountUuid) {
        if (mPreferences == null) {
            Context appContext = getContext().getApplicationContext();
            mPreferences = Preferences.getPreferences(appContext);
        }

        Account account = mPreferences.getAccount(accountUuid);

        if (account == null) {
            throw new IllegalArgumentException("Unknown account: " + accountUuid);
        }

        return account;
    }

    private LockableDatabase getDatabase(Account account) {
        LocalStore localStore;
        try {
            localStore = account.getLocalStore();
        } catch (MessagingException e) {
            throw new RuntimeException("Couldn't get LocalStore", e);
        }

        return localStore.getDatabase();
    }

    /**
     * This class is needed to make {@link CursorAdapter} work with our database schema.
     *
     * <p>
     * {@code CursorAdapter} requires a column named {@code "_id"} containing a stable id. We use
     * the column name {@code "id"} as primary key in all our tables. So this {@link CursorWrapper}
     * maps all queries for {@code "_id"} to {@code "id"}.
     * </p><p>
     * Please note that this only works for the returned {@code Cursor}. When querying the content
     * provider you still need to use {@link MessageColumns#ID}.
     * </p>
     */
    static class IdTrickeryCursor extends CursorWrapper {
        public IdTrickeryCursor(Cursor cursor) {
            super(cursor);
        }

        @Override
        public int getColumnIndex(String columnName) {
            if ("_id".equals(columnName)) {
                return super.getColumnIndex("id");
            }

            return super.getColumnIndex(columnName);
        }

        @Override
        public int getColumnIndexOrThrow(String columnName) {
            if ("_id".equals(columnName)) {
                return super.getColumnIndexOrThrow("id");
            }

            return super.getColumnIndexOrThrow(columnName);
        }
    }
}