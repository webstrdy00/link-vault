package com.linkvault.app.test;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import com.linkvault.app.BuildConfig;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Private, framework-only document-contract fixture in the debug target APK.
 *
 * <p>DocumentsProvider requires an exported provider, so this deliberately uses ContentProvider
 * with document URIs and the deleteDocument call contract instead. It has no picker roots and is
 * not evidence of system or third-party SAF picker interoperability. Only existing JSON files
 * directly inside the target cache's fixture_exports directory can be opened or deleted.
 */
public final class FixtureExportProvider extends ContentProvider {
    private static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".test.fixture-exports";
    private static final String FIXTURE_DIRECTORY = "fixture_exports";
    private static final String JSON_MIME_TYPE = "application/json";
    private static final Pattern FIXTURE_FILE_NAME =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*\\.json");
    // DocumentsContract uses these platform call keys, which are not public SDK constants.
    private static final String DELETE_DOCUMENT_METHOD = "android:deleteDocument";
    private static final String DOCUMENT_URI_EXTRA = "uri";
    private static final String[] DOCUMENT_COLUMNS = {
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED
    };

    /** Encodes an existing direct fixture_exports child; resolution is bound to target cache. */
    public static Uri uriForFile(File input) {
        final File file;
        final File parent;
        try {
            file = input.getCanonicalFile();
            File inputParent = input.getAbsoluteFile().getParentFile();
            parent = inputParent == null ? null : inputParent.getCanonicalFile();
        } catch (IOException error) {
            throw new IllegalArgumentException("Fixture export path is invalid.", error);
        }
        if (parent == null
                || !FIXTURE_DIRECTORY.equals(parent.getName())
                || !FIXTURE_FILE_NAME.matcher(input.getName()).matches()
                || !file.equals(new File(parent, input.getName()))
                || !parent.equals(file.getParentFile())
                || !file.isFile()) {
            throw new IllegalArgumentException(
                    "Fixture export must be a JSON file directly inside fixture_exports.");
        }
        return DocumentsContract.buildDocumentUri(AUTHORITY, file.getName());
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        requireFixtureFile(uri);
        return JSON_MIME_TYPE;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = requireFixtureFile(uri);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (signal != null) {
            signal.throwIfCanceled();
        }
        return openFile(uri, mode);
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        requireNoSelection(selection, selectionArgs);
        if (sortOrder != null) {
            throw new IllegalArgumentException("Fixture exports do not support sorting.");
        }
        File file = requireFixtureFile(uri);
        String[] columns = projection != null ? projection : DOCUMENT_COLUMNS;
        Object[] row = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            switch (columns[index]) {
                case Document.COLUMN_DOCUMENT_ID:
                case Document.COLUMN_DISPLAY_NAME:
                    row[index] = file.getName();
                    break;
                case Document.COLUMN_MIME_TYPE:
                    row[index] = JSON_MIME_TYPE;
                    break;
                case Document.COLUMN_FLAGS:
                    row[index] = Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE;
                    break;
                case Document.COLUMN_SIZE:
                    row[index] = file.length();
                    break;
                case Document.COLUMN_LAST_MODIFIED:
                    row[index] = file.lastModified();
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Unsupported fixture export column: " + columns[index]);
            }
        }
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        requireNoSelection(selection, selectionArgs);
        deleteFixture(uri);
        return 1;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!DELETE_DOCUMENT_METHOD.equals(method)) {
            throw new UnsupportedOperationException("Unsupported fixture export operation.");
        }
        if (arg != null || extras == null) {
            throw new IllegalArgumentException("Fixture export document URI is required.");
        }
        Uri uri = extras.getParcelable(DOCUMENT_URI_EXTRA);
        requireFixtureFile(uri);
        // ContentProvider.call does not enforce per-URI write permission on our behalf.
        if (Binder.getCallingUid() != Process.myUid()) {
            getContext().enforceCallingOrSelfUriPermission(
                    uri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    "Fixture export write permission is required.");
        }
        deleteFixture(uri);
        return Bundle.EMPTY;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Create fixture files in target cache first.");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Fixture export metadata is read-only.");
    }

    private void deleteFixture(Uri uri) {
        if (!requireFixtureFile(uri).delete()) {
            throw new IllegalStateException("Fixture export could not be deleted.");
        }
        getContext().revokeUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    private static void requireNoSelection(String selection, String[] selectionArgs) {
        if (selection != null || selectionArgs != null) {
            throw new IllegalArgumentException("Fixture exports do not support selection.");
        }
    }

    private File requireFixtureFile(Uri uri) {
        if (uri == null
                || !"content".equals(uri.getScheme())
                || !AUTHORITY.equals(uri.getAuthority())
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("Unknown fixture export URI.");
        }
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 2
                || !"document".equals(segments.get(0))
                || !FIXTURE_FILE_NAME.matcher(segments.get(1)).matches()) {
            throw new IllegalArgumentException("Invalid fixture export document ID.");
        }
        final File cacheDirectory;
        final File fixtureDirectory;
        final File file;
        try {
            cacheDirectory = getContext().getCacheDir().getCanonicalFile();
            fixtureDirectory = new File(cacheDirectory, FIXTURE_DIRECTORY).getCanonicalFile();
            file = new File(fixtureDirectory, segments.get(1)).getCanonicalFile();
        } catch (IOException error) {
            throw new IllegalArgumentException("Fixture export path is invalid.", error);
        }
        if (!fixtureDirectory.equals(new File(cacheDirectory, FIXTURE_DIRECTORY))
                || !fixtureDirectory.equals(file.getParentFile())
                || !file.equals(new File(fixtureDirectory, segments.get(1)))
                || !file.isFile()) {
            throw new IllegalArgumentException("Fixture export is unavailable.");
        }
        return file;
    }
}
