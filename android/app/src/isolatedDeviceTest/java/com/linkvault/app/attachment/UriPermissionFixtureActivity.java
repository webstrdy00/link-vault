package com.linkvault.app.attachment;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.ResultReceiver;
import java.io.File;
import java.io.IOException;

/** Runs as the test APK's own UID with Android/JDK classes only, not target APK dependencies. */
public final class UriPermissionFixtureActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Intent request = getIntent();
            ResultReceiver receiver = receiver(request);
            int requestId = request.getIntExtra(UriPermissionFixture.EXTRA_REQUEST, -1);
            String action = request.getAction();
            Bundle response = new Bundle();
            response.putInt(UriPermissionFixture.EXTRA_REQUEST, requestId);
            response.putInt(UriPermissionFixture.EXTRA_UID, Process.myUid());
            response.putString(
                    UriPermissionFixture.EXTRA_ACTION,
                    UriPermissionFixture.supportsAction(action) ? action : "");
            int resultCode;
            try {
                UriPermissionFixture.requireOwner(this);
                if (!UriPermissionFixture.TARGET_PACKAGE.equals(
                            request.getStringExtra(UriPermissionFixture.EXTRA_TARGET))
                        || request.getData() != null
                        || request.getClipData() != null
                        || requestId < 1
                        || requestId > 100
                        || receiver == null
                        || !UriPermissionFixture.supportsAction(action)) {
                    throw new IllegalArgumentException("Invalid synthetic fixture request");
                }
                @SuppressWarnings("deprecation")
                ApplicationInfo target = getPackageManager().getApplicationInfo(
                        UriPermissionFixture.TARGET_PACKAGE, 0);
                if (!UriPermissionFixture.TARGET_PACKAGE.equals(target.packageName)
                        || target.uid == Process.myUid()) {
                    throw new IllegalArgumentException("Fixture requires a separate isolated target UID");
                }
                File file = UriPermissionFixture.file(this);
                switch (action) {
                    case UriPermissionFixture.PREPARE:
                        UriPermissionFixture.revoke(this);
                        UriPermissionFixture.writePng(this);
                        break;
                    case UriPermissionFixture.GRANT:
                        if (!file.isFile() || file.length() < 1 || file.length() > UriPermissionFixture.MAX_BYTES) {
                            throw new IOException("Synthetic fixture is unavailable");
                        }
                        grantUriPermission(
                                UriPermissionFixture.TARGET_PACKAGE,
                                UriPermissionFixture.URI,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        break;
                    case UriPermissionFixture.REVOKE:
                        UriPermissionFixture.revoke(this);
                        break;
                    case UriPermissionFixture.CLEANUP:
                        try {
                            UriPermissionFixture.revoke(this);
                        } finally {
                            if (file.exists() && !file.delete()) {
                                throw new IOException("Could not remove synthetic fixture");
                            }
                            File directory = file.getParentFile();
                            if (directory == null || (directory.exists() && !directory.delete())) {
                                throw new IOException("Could not remove synthetic fixture directory");
                            }
                        }
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown fixture action");
                }
                response.putLong(UriPermissionFixture.EXTRA_BYTES, file.length());
                resultCode = RESULT_OK;
            } catch (Exception error) {
                // Return a bounded failure code, never paths, image data, or exception messages.
                resultCode = RESULT_CANCELED;
            }
            if (receiver != null) {
                receiver.send(resultCode, response);
            }
        } finally {
            finish();
        }
    }

    @SuppressWarnings("deprecation")
    private static ResultReceiver receiver(Intent request) {
        if (Build.VERSION.SDK_INT >= 33) {
            return request.getParcelableExtra(UriPermissionFixture.EXTRA_RECEIVER, ResultReceiver.class);
        }
        return request.getParcelableExtra(UriPermissionFixture.EXTRA_RECEIVER);
    }
}
