package com.linkvault.app.attachment;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

/** No manual read-permission checks: Android enforces the non-exported provider's URI grant. */
public final class UriPermissionFixtureProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        UriPermissionFixture.requireOwner(getContext());
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        UriPermissionFixture.requireUri(uri);
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Synthetic fixture is read-only");
        }
        Context owner = getContext();
        UriPermissionFixture.requireOwner(owner);
        File file = UriPermissionFixture.file(owner);
        if (!file.isFile() || file.length() < 1 || file.length() > UriPermissionFixture.MAX_BYTES) {
            throw new FileNotFoundException("Synthetic fixture is unavailable");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        UriPermissionFixture.requireUri(uri);
        return "image/png";
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        throw new UnsupportedOperationException("Synthetic fixture does not support queries");
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Synthetic fixture is read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Synthetic fixture is read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Synthetic fixture is read-only");
    }
}
