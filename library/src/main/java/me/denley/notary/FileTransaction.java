package me.denley.notary;

import android.content.Context;
import android.os.Environment;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;

import java.io.File;
import java.util.UUID;

public class FileTransaction {

    public static final String EXTERNAL_STORAGE_DIRECTORY = "~";

    private static final String PATH_PREFIX_TRANSACTION = "/notary_transaction_";

    public static String normalizePath(@Nullable final String path) {
        if(path==null) {
            return null;
        } else if(path.startsWith(EXTERNAL_STORAGE_DIRECTORY)) {
            return Environment.getExternalStorageDirectory().getAbsolutePath() + path.substring(1);
        }

        return path;
    }

    public static boolean isFileTransactionItem(DataItem item) {
        return item.getUri().getPath().startsWith(PATH_PREFIX_TRANSACTION);
    }

    private static String createTransactionId() {
        return new UUID(System.currentTimeMillis(), (long)(Math.random()*1000000000000l)).toString();
    }

    public static final int STATUS_IN_PROGRESS = 0;
    public static final int STATUS_COMPLETE = 1;
    public static final int STATUS_CANCELED = 3;
    public static final int STATUS_FAILED_FILE_NOT_FOUND = 4;
    public static final int STATUS_FAILED_BAD_DESTINATION = 5;
    public static final int STATUS_FAILED_FILE_ALREADY_EXISTS = 6;
    public static final int STATUS_FAILED_UNKNOWN = 7;
    public static final int STATUS_FAILED_NO_READ_PERMISSION = 8;
    public static final int STATUS_FAILED_NO_DELETE_PERMISSION = 9;

    @IntDef({
            STATUS_IN_PROGRESS,
            STATUS_COMPLETE,
            STATUS_CANCELED,
            STATUS_FAILED_FILE_NOT_FOUND,
            STATUS_FAILED_BAD_DESTINATION,
            STATUS_FAILED_FILE_ALREADY_EXISTS,
            STATUS_FAILED_UNKNOWN,
            STATUS_FAILED_NO_READ_PERMISSION,
            STATUS_FAILED_NO_DELETE_PERMISSION
    })
    public @interface FileTransactionStatus {}


    @NonNull private final String sourceFile;
    @NonNull public final String sourceNode;

    @Nullable private final String destinationDirectory;
    @Nullable public final String destinationNode;

    private boolean shouldCopy = false;
    private boolean hasCopied = false;
    private boolean shouldDelete = false;
    private boolean hasDeleted = false;

    @FileTransactionStatus int status = STATUS_IN_PROGRESS;

    private final String transactionId;

    @Nullable Asset fileAsset;

    FileTransaction(@NonNull String sourceFile, @NonNull String sourceNode, @Nullable String destinationDirectory, @NonNull String destinationNode, boolean deleteSource) {
        this.sourceFile = sourceFile;
        this.sourceNode = sourceNode;
        this.destinationDirectory = destinationDirectory;
        this.destinationNode = destinationNode;
        shouldCopy = true;
        shouldDelete = deleteSource;
        status = STATUS_IN_PROGRESS;
        transactionId = createTransactionId();
        fileAsset = null;
    }

    FileTransaction(@NonNull String fileToDelete, @NonNull String node) {
        sourceFile = fileToDelete;
        sourceNode = node;
        destinationDirectory = null;
        destinationNode = null;
        shouldCopy = false;
        shouldDelete = true;
        status = STATUS_IN_PROGRESS;
        transactionId = createTransactionId();
        fileAsset = null;
    }

    FileTransaction(@NonNull DataItem item) {
        this(DataMapItem.fromDataItem(item).getDataMap());
    }

    @SuppressWarnings("ResourceType")
    private FileTransaction(@NonNull DataMap map) {
        sourceFile = map.getString("sourceFile");
        sourceNode = map.getString("sourceNode");
        destinationDirectory = map.getString("destinationDirectory");
        destinationNode = map.getString("destinationNode");
        shouldCopy = map.getBoolean("shouldCopy");
        hasCopied = map.getBoolean("hasCopied");
        shouldDelete = map.getBoolean("shouldDelete");
        hasDeleted = map.getBoolean("hasDeleted");
        status = map.getInt("status");
        transactionId = map.getString("transactionId");
        fileAsset = map.getAsset("fileAsset");
    }

    PutDataRequest asPutDataRequest() {
        final PutDataMapRequest request = PutDataMapRequest.create(PATH_PREFIX_TRANSACTION + transactionId);

        final DataMap map = request.getDataMap();
        map.putString("sourceFile", sourceFile);
        map.putString("sourceNode", sourceNode);
        map.putString("destinationDirectory", destinationDirectory);
        map.putString("destinationNode", destinationNode);
        map.putBoolean("shouldCopy", shouldCopy);
        map.putBoolean("hasCopied", hasCopied);
        map.putBoolean("shouldDelete", shouldDelete);
        map.putBoolean("hasDeleted", hasDeleted);
        map.putInt("status", status);
        map.putString("transactionId", transactionId);

        map.putAsset("fileAsset", fileAsset);

        return request.asPutDataRequest();
    }

    @FileTransactionStatus public int getStatus() {
        return status;
    }

    void setHasCopied() {
        if(!shouldCopy) {
            throw new IllegalStateException("File was copied, but should not have been");
        }
        hasCopied = true;

        if(!shouldDelete) {
            updateStatus(STATUS_COMPLETE);
        }
    }

    void setHasDeleted() {
        if(!shouldDelete) {
            throw new IllegalStateException("File was deleted, but should not have been");
        } else if (shouldCopy && !hasCopied) {
            throw new IllegalStateException("File was deleted before being copied");
        }
        hasDeleted = true;

        updateStatus(STATUS_COMPLETE);
    }

    boolean pendingCopy() {
        return shouldCopy && !hasCopied && fileAsset==null;
    }

    boolean pendingSave() {
        return shouldCopy && !hasCopied && fileAsset!=null;
    }

    boolean pendingDelete() {
        return (!shouldCopy || hasCopied) && shouldDelete && !hasDeleted;
    }

    boolean hasCopiedAndSaved() {
        return shouldCopy & hasCopied;
    }

    boolean hasDeleted() {
        return hasDeleted;
    }

    private void updateStatus(@FileTransactionStatus int newStatus) {
        status = newStatus;
    }

    @NonNull String getDataApiPath() {
        return PATH_PREFIX_TRANSACTION + transactionId;
    }

    @NonNull public String getSourceFileName() {
        return getSourceFile().getName();
    }

    @NonNull public File getSourceFile() {
        return new File(normalizePath(sourceFile));
    }

    @NonNull public File getDestinationDirectoryFile(@NonNull Context context) {
        if(destinationDirectory==null) {
            return new File(Notary.getDefaultDirectory(context));
        }

        return new File(normalizePath(destinationDirectory));
    }
}
