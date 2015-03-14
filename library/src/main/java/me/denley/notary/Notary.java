package me.denley.notary;

import android.content.Context;
import android.support.annotation.NonNull;
import android.util.Pair;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.ObjectInputStream;
import java.util.LinkedHashMap;
import java.util.Map;

public class Notary {

    public static final String RESPONSE_LIST_FILES = "/response_list_files";
    public static final String REQUEST_LIST_FILES = "/request_list_files";

    private static Map<FileListener, Pair<String, String>> LISTENERS = new LinkedHashMap<>();

    public static void requestFileTransfer(@NonNull final Context context, @NonNull final String sourceFile, @NonNull final String sourceNode,
                                           @NonNull final String destinationDirectory, @NonNull final String destinationNode,
                                           final boolean deleteSource) {
        final FileTransaction transaction = new FileTransaction(sourceFile, sourceNode, destinationDirectory, destinationNode, deleteSource);
        putTransactionAsync(context, transaction);
    }

    public static void requestFileDelete(@NonNull final Context context, @NonNull final String file, @NonNull final String node) {
        final FileTransaction transaction = new FileTransaction(file, node);
        putTransactionAsync(context, transaction);
    }

    public static void registerFileListener(@NonNull final FileListener listener, @NonNull String fileOrDirectory, @NonNull String node) {
        final Pair<String, String> target = new Pair<>(fileOrDirectory, node);
        LISTENERS.put(listener, target);
    }

    public static void unregisterFileListener(@NonNull final FileListener listener) {
        LISTENERS.remove(listener);
    }

    static void notifyListeners(@NonNull final FileTransaction transaction) {
        for(Map.Entry<FileListener, Pair<String, String>> entry:LISTENERS.entrySet()) {
            final Pair<String, String> target = entry.getValue();
            checkAndTriggerListener(target.first, target.second, entry.getKey(), transaction);
        }
    }

    private static void checkAndTriggerListener(@NonNull String fileOrDirectory, @NonNull String node,
                                                @NonNull FileListener listener, @NonNull FileTransaction transaction) {

        checkAndTriggerListenerForSource(fileOrDirectory, node, listener, transaction);
        checkAndTriggerListenerForDestination(fileOrDirectory, node, listener, transaction);
    }

    private static void checkAndTriggerListenerForSource(@NonNull String fileOrDirectory, @NonNull String node,
                                                         @NonNull FileListener listener, @NonNull FileTransaction transaction) {
        if(!node.equals(transaction.sourceNode)) {
            return;
        }

        final String sourceDirectory = new File(transaction.sourceFile).getParent();

        if(fileOrDirectory.equalsIgnoreCase(transaction.sourceFile) || fileOrDirectory.equalsIgnoreCase(sourceDirectory)) {
            listener.onSourceFileStatusChanged(transaction);
        }
    }

    private static void checkAndTriggerListenerForDestination(@NonNull String fileOrDirectory, @NonNull String node,
                                                              @NonNull FileListener listener, @NonNull FileTransaction transaction) {
        if(!node.equals(transaction.destinationNode) || transaction.destinationDirectory==null) {
            return;
        }

        final String sourceFileName = new File(transaction.sourceFile).getName();
        final String destinationFileName = new File(transaction.destinationDirectory, sourceFileName).getAbsolutePath();

        if(fileOrDirectory.equalsIgnoreCase(transaction.destinationDirectory) || fileOrDirectory.equalsIgnoreCase(destinationFileName)) {
            listener.onDestinationFileStatusChanged(transaction);
        }
    }

    private static void putTransactionAsync(@NonNull final Context context, @NonNull final FileTransaction transaction) {
        new Thread() {
            public void run() {
                putTransaction(context, transaction);
            }
        }.start();
    }

    private static void putTransaction(@NonNull final Context context, @NonNull final FileTransaction transaction) {
        final GoogleApiClient apiClient = new GoogleApiClient.Builder(context)
                .addApi(Wearable.API)
                .build();

        final ConnectionResult result = apiClient.blockingConnect();
        if(result.isSuccess()) {
            Wearable.DataApi.putDataItem(apiClient, transaction.asPutDataRequest());
        }
    }




    public interface FileListCallback {
        public void success(FileListContainer fileList);
        public void failure(ConnectionResult result);
    }

    public static void requestFileList(@NonNull final Context context, @NonNull final String node, @NonNull final String directory, @NonNull final FileListCallback callback) {
        final GoogleApiClient apiClient = new GoogleApiClient.Builder(context)
                .addApi(Wearable.API).build();

        final ConnectionResult result = apiClient.blockingConnect();
        if(result.isSuccess()) {
            Wearable.MessageApi.addListener(apiClient, new MessageApi.MessageListener() {
                @Override public void onMessageReceived(MessageEvent messageEvent) {
                    if(messageEvent.getPath().equals(RESPONSE_LIST_FILES) && messageEvent.getSourceNodeId().equals(node)) {
                        try {
                            final ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(messageEvent.getData()));
                            final FileListContainer container = (FileListContainer)in.readObject();
                            if(directory.equalsIgnoreCase(container.directory)) {
                                callback.success(container);
                                Wearable.MessageApi.removeListener(apiClient, this);
                            }
                        } catch (Exception e) {}
                    }
                }
            });
            Wearable.MessageApi.sendMessage(apiClient, node, REQUEST_LIST_FILES, directory.getBytes());
        } else {
            callback.failure(result);
        }
    }

}
