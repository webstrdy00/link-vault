package com.linkvault.app.attachment;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Process;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Framework/JDK-only fixture state shared with the standalone test-APK process. */
public final class UriPermissionFixture {
    public static final String TARGET_PACKAGE = "com.linkvault.app.titleiconqa";
    public static final String TEST_PACKAGE = "com.linkvault.app.titleiconqa.test";
    public static final int WIDTH = 32;
    public static final int HEIGHT = 24;
    public static final int MAX_BYTES = 16_384;
    public static final String PREPARE = TEST_PACKAGE + ".permissionfixture.PREPARE";
    public static final String GRANT = TEST_PACKAGE + ".permissionfixture.GRANT";
    public static final String REVOKE = TEST_PACKAGE + ".permissionfixture.REVOKE";
    public static final String CLEANUP = TEST_PACKAGE + ".permissionfixture.CLEANUP";
    public static final String EXTRA_TARGET = "fixture_target";
    public static final String EXTRA_REQUEST = "fixture_request";
    public static final String EXTRA_RECEIVER = "fixture_receiver";
    public static final String EXTRA_ACTION = "fixture_action";
    public static final String EXTRA_UID = "fixture_uid";
    public static final String EXTRA_BYTES = "fixture_bytes";
    public static final Uri URI = Uri.parse(
            "content://" + TEST_PACKAGE + ".permissionfixture/synthetic-permission-fixture.png");

    private UriPermissionFixture() {}

    static boolean supportsAction(String action) {
        return PREPARE.equals(action)
                || GRANT.equals(action)
                || REVOKE.equals(action)
                || CLEANUP.equals(action);
    }

    static void requireOwner(Context context) {
        if (context == null
                || !TEST_PACKAGE.equals(context.getPackageName())
                || !TEST_PACKAGE.equals(context.getApplicationInfo().packageName)
                || context.getApplicationInfo().uid != Process.myUid()) {
            throw new IllegalArgumentException("Only the isolated test APK may own this fixture");
        }
    }

    static void requireUri(Uri uri) {
        if (!URI.equals(uri)) {
            throw new IllegalArgumentException("Only the fixed synthetic fixture URI is supported");
        }
    }

    static File file(Context context) {
        requireOwner(context);
        return new File(
                context.getCacheDir(),
                "uri-permission-lifecycle-fixture/synthetic-permission-fixture.png");
    }

    static void revoke(Context context) {
        requireOwner(context);
        context.revokeUriPermission(TARGET_PACKAGE, URI, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    static void writePng(Context context) throws IOException {
        File file = file(context);
        File directory = file.getParentFile();
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
            throw new IOException("Could not create synthetic fixture directory");
        }
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        byte[] encoded;
        try {
            bitmap.eraseColor(Color.rgb(32, 96, 160));
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IOException("Could not encode synthetic fixture");
                }
                encoded = output.toByteArray();
                if (encoded.length == 0 || encoded.length > MAX_BYTES) {
                    throw new IOException("Synthetic fixture exceeded its byte bound");
                }
            }
        } finally {
            bitmap.recycle();
        }
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(encoded);
        }
    }
}
