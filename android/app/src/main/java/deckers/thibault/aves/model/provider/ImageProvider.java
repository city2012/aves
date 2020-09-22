package deckers.thibault.aves.model.provider;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.exifinterface.media.ExifInterface;

import com.bumptech.glide.load.resource.bitmap.TransformationUtils;
import com.commonsware.cwac.document.DocumentFileCompat;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import deckers.thibault.aves.model.AvesImageEntry;
import deckers.thibault.aves.utils.MetadataHelper;
import deckers.thibault.aves.utils.MimeTypes;
import deckers.thibault.aves.utils.StorageUtils;
import deckers.thibault.aves.utils.Utils;

// *** about file access to write/rename/delete
// * primary volume
// until 28/Pie, use `File`
// on 29/Q, use `File` after setting `requestLegacyExternalStorage` flag in the manifest
// from 30/R, use `DocumentFile` (not `File`) after requesting permission to the volume root???
// * non primary volumes
// on 19/KitKat, use `DocumentFile` (not `File`) after getting permission for each file
// from 21/Lollipop, use `DocumentFile` (not `File`) after getting permission to the volume root

public abstract class ImageProvider {
    private static final String LOG_TAG = Utils.createLogTag(ImageProvider.class);

    public void fetchSingle(@NonNull final Context context, @NonNull final Uri uri, @NonNull final String mimeType, @NonNull final ImageOpCallback callback) {
        callback.onFailure(new UnsupportedOperationException());
    }

    public ListenableFuture<Object> delete(final Context context, final String path, final Uri uri) {
        return Futures.immediateFailedFuture(new UnsupportedOperationException());
    }

    public void moveMultiple(final Context context, final Boolean copy, final String destinationDir, final List<AvesImageEntry> entries, @NonNull final ImageOpCallback callback) {
        callback.onFailure(new UnsupportedOperationException());
    }

    public void rename(final Context context, final String oldPath, final Uri oldMediaUri, final String mimeType, final String newFilename, final ImageOpCallback callback) {
        if (oldPath == null) {
            callback.onFailure(new IllegalArgumentException("entry does not have a path, uri=" + oldMediaUri));
            return;
        }

        File oldFile = new File(oldPath);
        File newFile = new File(oldFile.getParent(), newFilename);
        if (oldFile.equals(newFile)) {
            Log.w(LOG_TAG, "new name and old name are the same, path=" + oldPath);
            callback.onSuccess(new HashMap<>());
            return;
        }

        DocumentFileCompat df = StorageUtils.getDocumentFile(context, oldPath, oldMediaUri);
        try {
            boolean renamed = df != null && df.renameTo(newFilename);
            if (!renamed) {
                callback.onFailure(new Exception("failed to rename entry at path=" + oldPath));
                return;
            }
        } catch (FileNotFoundException e) {
            callback.onFailure(e);
            return;
        }

        MediaScannerConnection.scanFile(context, new String[]{oldPath}, new String[]{mimeType}, null);
        scanNewPath(context, newFile.getPath(), mimeType, callback);
    }

    @SuppressWarnings("UnstableApiUsage")
    public void renameDirectory(Context context, String oldDirPath, String newDirName, final AlbumRenameOpCallback callback) {
        if (!oldDirPath.endsWith(File.separator)) {
            oldDirPath += File.separator;
        }

        DocumentFileCompat destinationDirDocFile = StorageUtils.createDirectoryIfAbsent(context, oldDirPath);
        if (destinationDirDocFile == null) {
            callback.onFailure(new Exception("failed to find directory at path=" + oldDirPath));
            return;
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        entries.addAll(listContentEntries(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, oldDirPath));
        entries.addAll(listContentEntries(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, oldDirPath));

        boolean renamed;
        try {
            renamed = destinationDirDocFile.renameTo(newDirName);
        } catch (FileNotFoundException e) {
            callback.onFailure(new Exception("failed to rename to name=" + newDirName + " directory at path=" + oldDirPath, e));
            return;
        }

        if (!renamed) {
            callback.onFailure(new Exception("failed to rename to name=" + newDirName + " directory at path=" + oldDirPath));
            return;
        }

        List<SettableFuture<Map<String, Object>>> scanFutures = new ArrayList<>();
        String newDirPath = new File(oldDirPath).getParent() + File.separator + newDirName + File.separator;
        for (Map<String, Object> entry : entries) {
            String displayName = (String) entry.get("displayName");
            String mimeType = (String) entry.get("mimeType");

            String oldEntryPath = oldDirPath + displayName;
            MediaScannerConnection.scanFile(context, new String[]{oldEntryPath}, new String[]{mimeType}, null);

            SettableFuture<Map<String, Object>> scanFuture = SettableFuture.create();
            scanFutures.add(scanFuture);
            String newEntryPath = newDirPath + displayName;
            scanNewPath(context, newEntryPath, mimeType, new ImageProvider.ImageOpCallback() {
                @Override
                public void onSuccess(Map<String, Object> newFields) {
                    entry.putAll(newFields);
                    entry.put("success", true);
                    scanFuture.set(entry);
                }

                @Override
                public void onFailure(Throwable throwable) {
                    Log.w(LOG_TAG, "failed to scan entry=" + displayName + " in new directory=" + newDirPath, throwable);
                    entry.put("success", false);
                    scanFuture.set(entry);
                }
            });
        }

        try {
            callback.onSuccess(Futures.allAsList(scanFutures).get());
        } catch (ExecutionException | InterruptedException e) {
            callback.onFailure(e);
        }
    }

    private List<Map<String, Object>> listContentEntries(Context context, Uri contentUri, String dirPath) {
        List<Map<String, Object>> entries = new ArrayList<>();
        String[] projection = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
        };
        String selection = MediaStore.MediaColumns.DATA + " like ?";

        try {
            Cursor cursor = context.getContentResolver().query(contentUri, projection, selection, new String[]{dirPath + "%"}, null);
            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
                int displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
                int mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE);
                while (cursor.moveToNext()) {
                    entries.add(new HashMap<String, Object>() {{
                        put("oldContentId", cursor.getInt(idColumn));
                        put("displayName", cursor.getString(displayNameColumn));
                        put("mimeType", cursor.getString(mimeTypeColumn));
                    }});
                }
                cursor.close();
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "failed to list entries in  contentUri=" + contentUri, e);
        }
        return entries;
    }

    public void rotate(final Context context, final String path, final Uri uri, final String mimeType, final boolean clockwise, final ImageOpCallback callback) {
        switch (mimeType) {
            case MimeTypes.JPEG:
                rotateJpeg(context, path, uri, clockwise, callback);
                break;
            case MimeTypes.PNG:
                rotatePng(context, path, uri, clockwise, callback);
                break;
            default:
                callback.onFailure(new UnsupportedOperationException("unsupported mimeType=" + mimeType));
        }
    }

    private void rotateJpeg(final Context context, final String path, final Uri uri, boolean clockwise, final ImageOpCallback callback) {
        final String mimeType = MimeTypes.JPEG;

        final DocumentFileCompat originalDocumentFile = StorageUtils.getDocumentFile(context, path, uri);
        if (originalDocumentFile == null) {
            callback.onFailure(new Exception("failed to get document file for path=" + path + ", uri=" + uri));
            return;
        }

        // copy original file to a temporary file for editing
        final String editablePath = StorageUtils.copyFileToTemp(originalDocumentFile, path);
        if (editablePath == null) {
            callback.onFailure(new Exception("failed to create a temporary file for path=" + path));
            return;
        }

        int newOrientationCode;
        try {
            ExifInterface exif = new ExifInterface(editablePath);
            switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    newOrientationCode = clockwise ? ExifInterface.ORIENTATION_ROTATE_180 : ExifInterface.ORIENTATION_NORMAL;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    newOrientationCode = clockwise ? ExifInterface.ORIENTATION_ROTATE_270 : ExifInterface.ORIENTATION_ROTATE_90;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    newOrientationCode = clockwise ? ExifInterface.ORIENTATION_NORMAL : ExifInterface.ORIENTATION_ROTATE_180;
                    break;
                default:
                    newOrientationCode = clockwise ? ExifInterface.ORIENTATION_ROTATE_90 : ExifInterface.ORIENTATION_ROTATE_270;
                    break;
            }
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(newOrientationCode));
            exif.saveAttributes();

            // copy the edited temporary file back to the original
            DocumentFileCompat.fromFile(new File(editablePath)).copyTo(originalDocumentFile);
        } catch (IOException e) {
            callback.onFailure(e);
            return;
        }

        // update fields in media store
        int orientationDegrees = MetadataHelper.getOrientationDegreesForExifCode(newOrientationCode);
        Map<String, Object> newFields = new HashMap<>();
        newFields.put("orientationDegrees", orientationDegrees);

//        ContentResolver contentResolver = context.getContentResolver();
//        ContentValues values = new ContentValues();
//        // from Android Q, media store update needs to be flagged IS_PENDING first
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
//            // TODO TLAD catch RecoverableSecurityException
//            contentResolver.update(uri, values, null, null);
//            values.clear();
//            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
//        }
//        // uses MediaStore.Images.Media instead of MediaStore.MediaColumns for APIs < Q
//        values.put(MediaStore.Images.Media.ORIENTATION, orientationDegrees);
//        // TODO TLAD catch RecoverableSecurityException
//        int updatedRowCount = contentResolver.update(uri, values, null, null);
//        if (updatedRowCount > 0) {
        MediaScannerConnection.scanFile(context, new String[]{path}, new String[]{mimeType}, (p, u) -> callback.onSuccess(newFields));
//        } else {
//            Log.w(LOG_TAG, "failed to update fields in Media Store for uri=" + uri);
//            callback.onSuccess(newFields);
//        }
    }

    private void rotatePng(final Context context, final String path, final Uri uri, boolean clockwise, final ImageOpCallback callback) {
        final String mimeType = MimeTypes.PNG;

        final DocumentFileCompat originalDocumentFile = StorageUtils.getDocumentFile(context, path, uri);
        if (originalDocumentFile == null) {
            callback.onFailure(new Exception("failed to get document file for path=" + path + ", uri=" + uri));
            return;
        }

        // copy original file to a temporary file for editing
        final String editablePath = StorageUtils.copyFileToTemp(originalDocumentFile, path);
        if (editablePath == null) {
            callback.onFailure(new Exception("failed to create a temporary file for path=" + path));
            return;
        }

        Bitmap originalImage;
        try {
            originalImage = BitmapFactory.decodeStream(StorageUtils.openInputStream(context, uri));
        } catch (FileNotFoundException e) {
            callback.onFailure(e);
            return;
        }
        if (originalImage == null) {
            callback.onFailure(new Exception("failed to decode image at path=" + path));
            return;
        }
        Bitmap rotatedImage = TransformationUtils.rotateImage(originalImage, clockwise ? 90 : -90);

        try (FileOutputStream fos = new FileOutputStream(editablePath)) {
            rotatedImage.compress(Bitmap.CompressFormat.PNG, 100, fos);

            // copy the edited temporary file back to the original
            DocumentFileCompat.fromFile(new File(editablePath)).copyTo(originalDocumentFile);
        } catch (IOException e) {
            callback.onFailure(e);
            return;
        }

        // update fields in media store
        int rotatedWidth = originalImage.getHeight();
        int rotatedHeight = originalImage.getWidth();
        Map<String, Object> newFields = new HashMap<>();
        newFields.put("width", rotatedWidth);
        newFields.put("height", rotatedHeight);

//        ContentResolver contentResolver = context.getContentResolver();
//        ContentValues values = new ContentValues();
//        // from Android Q, media store update needs to be flagged IS_PENDING first
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
//            // TODO TLAD catch RecoverableSecurityException
//            contentResolver.update(uri, values, null, null);
//            values.clear();
//            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
//        }
//        values.put(MediaStore.MediaColumns.WIDTH, rotatedWidth);
//        values.put(MediaStore.MediaColumns.HEIGHT, rotatedHeight);
//        // TODO TLAD catch RecoverableSecurityException
//        int updatedRowCount = contentResolver.update(uri, values, null, null);
//        if (updatedRowCount > 0) {
        MediaScannerConnection.scanFile(context, new String[]{path}, new String[]{mimeType}, (p, u) -> callback.onSuccess(newFields));
//        } else {
//            Log.w(LOG_TAG, "failed to update fields in Media Store for uri=" + uri);
//            callback.onSuccess(newFields);
//        }
    }

    protected void scanNewPath(final Context context, final String path, final String mimeType, final ImageOpCallback callback) {
        MediaScannerConnection.scanFile(context, new String[]{path}, new String[]{mimeType}, (newPath, newUri) -> {
            long contentId = 0;
            Uri contentUri = null;
            if (newUri != null) {
                // newURI is possibly a file media URI (e.g. "content://media/12a9-8b42/file/62872")
                // but we need an image/video media URI (e.g. "content://media/external/images/media/62872")
                contentId = ContentUris.parseId(newUri);
                if (mimeType.startsWith(MimeTypes.IMAGE)) {
                    contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentId);
                } else if (mimeType.startsWith(MimeTypes.VIDEO)) {
                    contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentId);
                }
            }
            if (contentUri == null) {
                callback.onFailure(new Exception("failed to get content URI of item at path=" + path));
                return;
            }

            Map<String, Object> newFields = new HashMap<>();
            // we retrieve updated fields as the renamed file became a new entry in the Media Store
            String[] projection = {
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.TITLE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
            };
            try {
                Cursor cursor = context.getContentResolver().query(contentUri, projection, null, null, null);
                if (cursor != null) {
                    if (cursor.moveToNext()) {
                        newFields.put("uri", contentUri.toString());
                        newFields.put("contentId", contentId);
                        newFields.put("path", path);
                        newFields.put("displayName", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)));
                        newFields.put("title", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE)));
                        newFields.put("dateModifiedSecs", cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)));
                    }
                    cursor.close();
                }
            } catch (Exception e) {
                callback.onFailure(e);
                return;
            }

            if (newFields.isEmpty()) {
                callback.onFailure(new Exception("failed to get item details from provider at contentUri=" + contentUri));
            } else {
                callback.onSuccess(newFields);
            }
        });
    }

    public interface ImageOpCallback {
        void onSuccess(Map<String, Object> fields);

        void onFailure(Throwable throwable);
    }

    public interface AlbumRenameOpCallback {
        void onSuccess(List<Map<String, Object>> fieldsByEntry);

        void onFailure(Throwable throwable);
    }
}
