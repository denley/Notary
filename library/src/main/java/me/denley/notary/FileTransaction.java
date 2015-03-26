package me.denley.notary;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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
import java.util.ArrayList;
import java.util.UUID;

public class FileTransaction {

    public static final String EXTERNAL_STORAGE_DIRECTORY = "~";
    public static final String APP_PRIVATE_DIRECTORY = "!";
    public static final String DEFAULT_DIRECTORY = "*";


    private static final String PATH_PREFIX_TRANSACTION = "/notary_transaction_";

    public static String normalizePath(@NonNull final Context context, @Nullable final String path) {
        if(path==null) {
            return null;
        } else if(path.startsWith(EXTERNAL_STORAGE_DIRECTORY)) {
            return Environment.getExternalStorageDirectory().getAbsolutePath() + path.substring(1);
        } else if(path.startsWith(APP_PRIVATE_DIRECTORY)) {
            return context.getFilesDir().getAbsolutePath() + path.substring(1);
        } else if(path.startsWith(DEFAULT_DIRECTORY)) {
            return getDefaultDirectory(context) + path.substring(1);
        }

        return path;
    }

    public static String getDefaultDirectory(@NonNull final Context context) {
        final String encodedPath = getDefaultDirectoryEncoded(context);
        if(encodedPath.startsWith(DEFAULT_DIRECTORY)) {
            throw new IllegalArgumentException("Default directory references itself recursively.");
        }
        return normalizePath(context, encodedPath);
    }

    public static String getDefaultDirectoryEncoded(@NonNull final Context context) {
        try {
            final ApplicationInfo info = context.getPackageManager().getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            return info.metaData.getString("default_path");
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean isFileTransactionItem(DataItem item) {
        return item.getUri().getPath().startsWith(PATH_PREFIX_TRANSACTION);
    }

    private static String createTransactionId() {
        return new UUID(System.currentTimeMillis(), (long)(Math.random()*1000000000000l)).toString();
    }

    private static ArrayList<String> asArrayList(String... strings) {
        final ArrayList<String> asList = new ArrayList<>();
        for(String item:strings) {
            asList.add(item);
        }
        return asList;
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


    @NonNull private final ArrayList<String> sourceFiles;
    @NonNull public final String sourceNode;

    @NonNull private final String destinationDirectory;
    @NonNull public final String destinationNode;

    private int actionableIndex = 0;
    private boolean shouldCopy = false;
    private boolean hasCopied = false;
    private boolean shouldDelete = false;
    private boolean hasDeleted = false;

    final boolean isDeleteOnlyTransaction;

    @FileTransactionStatus int status = STATUS_IN_PROGRESS;

    private final String transactionId;

    @Nullable Asset fileAsset;

    FileTransaction(@NonNull String sourceFile, @NonNull String sourceNode, @NonNull String destinationDirectory, @NonNull String destinationNode, boolean deleteSource) {
        this(asArrayList(sourceFile), sourceNode, destinationDirectory, destinationNode, deleteSource);
    }

    FileTransaction(@NonNull ArrayList<String> sourceFiles, @NonNull String sourceNode, @NonNull String destinationDirectory, @NonNull String destinationNode, boolean deleteSource) {
        this.sourceFiles = sourceFiles;
        this.sourceNode = sourceNode;
        this.destinationDirectory = destinationDirectory;
        this.destinationNode = destinationNode;
        shouldCopy = true;
        shouldDelete = deleteSource;
        status = STATUS_IN_PROGRESS;
        transactionId = createTransactionId();
        fileAsset = null;
        isDeleteOnlyTransaction = false;
    }

    FileTransaction(@NonNull String observerDirectory, @NonNull String observerNode, @NonNull String fileToDelete, @NonNull String node) {
        this(observerDirectory, observerNode, asArrayList(fileToDelete), node);
    }

    FileTransaction(@NonNull String observerDirectory, @NonNull String observerNode, @NonNull ArrayList<String> filesToDelete, @NonNull String node) {
        sourceFiles = filesToDelete;
        sourceNode = node;
        destinationDirectory = observerDirectory;
        destinationNode = observerNode;
        shouldCopy = false;
        shouldDelete = true;
        status = STATUS_IN_PROGRESS;
        transactionId = createTransactionId();
        fileAsset = null;
        isDeleteOnlyTransaction = true;
    }

    FileTransaction(@NonNull DataItem item) {
        this(DataMapItem.fromDataItem(item).getDataMap());
    }

    @SuppressWarnings("ResourceType")
    private FileTransaction(@NonNull DataMap map) {
        sourceFiles = map.getStringArrayList("sourceFiles");
        sourceNode = map.getString("sourceNode");
        destinationDirectory = map.getString("destinationDirectory");
        destinationNode = map.getString("destinationNode");
        actionableIndex = map.getInt("actionableIndex");
        shouldCopy = map.getBoolean("shouldCopy");
        hasCopied = map.getBoolean("hasCopied");
        shouldDelete = map.getBoolean("shouldDelete");
        hasDeleted = map.getBoolean("hasDeleted");
        status = map.getInt("status");
        transactionId = map.getString("transactionId");
        fileAsset = map.getAsset("fileAsset");
        isDeleteOnlyTransaction = map.getBoolean("isDeleteOnlyTransaction");
    }

    PutDataRequest asPutDataRequest() {
        final PutDataMapRequest request = PutDataMapRequest.create(PATH_PREFIX_TRANSACTION + transactionId);

        final DataMap map = request.getDataMap();
        map.putStringArrayList("sourceFiles", sourceFiles);
        map.putString("sourceNode", sourceNode);
        map.putString("destinationDirectory", destinationDirectory);
        map.putString("destinationNode", destinationNode);
        map.putInt("actionableIndex", actionableIndex);
        map.putBoolean("shouldCopy", shouldCopy);
        map.putBoolean("hasCopied", hasCopied);
        map.putBoolean("shouldDelete", shouldDelete);
        map.putBoolean("hasDeleted", hasDeleted);
        map.putInt("status", status);
        map.putString("transactionId", transactionId);

        map.putAsset("fileAsset", fileAsset);
        map.putBoolean("isDeleteOnlyTransaction", isDeleteOnlyTransaction);

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
        fileAsset = null;

        if(!shouldDelete) {
            setCurrentIndexComplete();
        }
    }

    void setHasDeleted() {
        if(!shouldDelete) {
            throw new IllegalStateException("File was deleted, but should not have been");
        } else if (shouldCopy && !hasCopied) {
            throw new IllegalStateException("File was deleted before being copied");
        }
        hasDeleted = true;

        setCurrentIndexComplete();
    }

    private void setCurrentIndexComplete() {
        actionableIndex++;
        if(actionableIndex < sourceFiles.size()) {
            hasCopied = false;
            hasDeleted = false;
            fileAsset = null;
        } else {
            updateStatus(STATUS_COMPLETE);
        }
    }

    int getActionableIndex() {
        return actionableIndex;
    }

    boolean pendingCopy() {
        return actionableIndex<sourceFiles.size() && shouldCopy && !hasCopied && fileAsset==null;
    }

    boolean pendingSave() {
        return actionableIndex<sourceFiles.size() && shouldCopy && !hasCopied && fileAsset!=null;
    }

    boolean pendingDelete() {
        return actionableIndex<sourceFiles.size() && (!shouldCopy || hasCopied) && shouldDelete && !hasDeleted;
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

    @NonNull public String getSourceFileName(int index) {
        return new File(sourceFiles.get(index)).getName();
    }

    @NonNull public String getActionableSourceFileName() {
        return new File(sourceFiles.get(actionableIndex)).getName();
    }

    public int getSourceFileCount() {
        return sourceFiles.size();
    }

    @NonNull public File getSourceFile(@NonNull final Context context, int index) {
        return new File(normalizePath(context, sourceFiles.get(index)));
    }

    @NonNull public File getActionableSourceFile(@NonNull final Context context) {
        return new File(normalizePath(context, sourceFiles.get(actionableIndex)));
    }

    @NonNull public File getDestinationDirectoryFile(@NonNull Context context) {
        return new File(normalizePath(context, destinationDirectory));
    }
}
