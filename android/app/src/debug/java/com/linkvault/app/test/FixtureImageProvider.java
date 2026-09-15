package com.linkvault.app.test;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Framework-only provider for PNGs generated in the debug target APK's cache directory.
 *
 * <p>This provider belongs to the debug target APK, so instrumentation running as the target UID
 * can create fixtures in the same cache sandbox. It uses only Android framework and Java runtime
 * classes and is never packaged in release builds.
 */
public final class FixtureImageProvider extends ContentProvider {
    private static final String AUTHORITY = "com.linkvault.app.test.fixture-images";
    private static final String FIXTURE_DIRECTORY = "fixture_images";
    private static final String PNG_MIME_TYPE = "image/png";
    private static final Pattern FIXTURE_FILE_NAME =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*\\.png");

    /**
     * Creates the one supported URI shape after checking a direct fixture_images child.
     */
    public static Uri uriForFile(File input) {
        final File file;
        try {
            file = input.getCanonicalFile();
        } catch (IOException error) {
            throw new IllegalArgumentException("Fixture image path is invalid.", error);
        }
        File parent = file.getParentFile();
        if (parent == null
                || !FIXTURE_DIRECTORY.equals(parent.getName())
                || !FIXTURE_FILE_NAME.matcher(file.getName()).matches()
                || !file.isFile()) {
            throw new IllegalArgumentException(
                    "Fixture image must be a PNG directly inside fixture_images.");
        }
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(FIXTURE_DIRECTORY)
                .appendPath(file.getName())
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        requireFixtureFile(uri);
        return PNG_MIME_TYPE;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Fixture images are read-only.");
        }
        return ParcelFileDescriptor.open(
                requireFixtureFile(uri),
                ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        File file = requireFixtureFile(uri);
        String[] columns = projection != null
                ? projection
                : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        Object[] row = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[index])) {
                row[index] = file.getName();
            } else if (OpenableColumns.SIZE.equals(columns[index])) {
                row[index] = file.length();
            } else {
                throw new IllegalArgumentException(
                        "Unsupported fixture column: " + columns[index]);
            }
        }
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Fixture images are read-only.");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Fixture images are read-only.");
    }

    @Override
    public int update(
            Uri uri,
            ContentValues values,
            String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException("Fixture images are read-only.");
    }

    private File requireFixtureFile(Uri uri) {
        if (!"content".equals(uri.getScheme())
                || !AUTHORITY.equals(uri.getAuthority())
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("Unknown fixture image URI.");
        }
        java.util.List<String> segments = uri.getPathSegments();
        if (segments.size() != 2
                || !FIXTURE_DIRECTORY.equals(segments.get(0))
                || !FIXTURE_FILE_NAME.matcher(segments.get(1)).matches()) {
            throw new IllegalArgumentException("Invalid fixture image path.");
        }
        final File fixtureDirectory;
        final File file;
        try {
            fixtureDirectory =
                    new File(getContext().getCacheDir(), FIXTURE_DIRECTORY).getCanonicalFile();
            file = new File(fixtureDirectory, segments.get(1)).getCanonicalFile();
        } catch (IOException error) {
            throw new IllegalArgumentException("Fixture image path is invalid.", error);
        }
        if (!fixtureDirectory.equals(file.getParentFile()) || !file.isFile()) {
            throw new IllegalArgumentException("Fixture image is unavailable.");
        }
        return file;
    }
}
