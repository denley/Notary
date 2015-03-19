package me.denley.notary;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.Pair;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuppressWarnings("unused")
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

    public static void requestFileDelete(@NonNull final Context context, @NonNull final String file, @NonNull final String node,
                                         @NonNull String observerDirectory, @NonNull String observerNode) {
        final FileTransaction transaction = new FileTransaction(observerDirectory, observerNode, file, node);
        putTransactionAsync(context, transaction);
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

    public static void registerFileListener(@NonNull final FileListener listener, @NonNull String fileOrDirectory, @NonNull String node) {
        final Pair<String, String> target = new Pair<>(fileOrDirectory, node);
        LISTENERS.put(listener, target);
    }

    public static void unregisterFileListener(@NonNull final FileListener listener) {
        LISTENERS.remove(listener);
    }

    static void notifyListeners(@NonNull Context context, @NonNull final FileTransaction transaction) {
        for(Map.Entry<FileListener, Pair<String, String>> entry:LISTENERS.entrySet()) {
            final Pair<String, String> target = entry.getValue();
            checkAndTriggerListener(context, target.first, target.second, entry.getKey(), transaction);
        }
    }

    private static void checkAndTriggerListener(@NonNull Context context,
                                                @NonNull String fileOrDirectory, @NonNull String node,
                                                @NonNull FileListener listener, @NonNull FileTransaction transaction) {

        checkAndTriggerListenerForSource(context, fileOrDirectory, node, listener, transaction);
        checkAndTriggerListenerForDestination(context, fileOrDirectory, node, listener, transaction);
    }

    private static void checkAndTriggerListenerForSource(@NonNull Context context,
                                                         @NonNull String fileOrDirectory, @NonNull String node,
                                                         @NonNull FileListener listener, @NonNull FileTransaction transaction) {
        if(!node.equals(transaction.sourceNode)) {
            return;
        }

        final String sourcePath = transaction.getSourceFile(context).getAbsolutePath();
        final String sourceDirectory = transaction.getSourceFile(context).getParent();

        if(fileOrDirectory.equalsIgnoreCase(sourcePath) || fileOrDirectory.equalsIgnoreCase(sourceDirectory)) {
            listener.onSourceFileStatusChanged(transaction);
        }
    }

    private static void checkAndTriggerListenerForDestination(@NonNull Context context,
                                                              @NonNull String fileOrDirectory, @NonNull String node,
                                                              @NonNull FileListener listener, @NonNull FileTransaction transaction) {
        final File destinationDirectory = transaction.getDestinationDirectoryFile(context);

        if(!node.equals(transaction.destinationNode)) {
            return;
        }

        final String sourceFileName = transaction.getSourceFileName();
        final String destinationFileName = new File(destinationDirectory, sourceFileName).getAbsolutePath();

        if(fileOrDirectory.equalsIgnoreCase(destinationDirectory.getAbsolutePath()) || fileOrDirectory.equalsIgnoreCase(destinationFileName)) {
            if(transaction.isDeleteOnlyTransaction && transaction.getStatus()==FileTransaction.STATUS_COMPLETE) {
                listener.onDeleteTransactionSuccess(transaction);
            } else {
                listener.onDestinationFileStatusChanged(transaction);
            }
        }
    }

    static List<PendingFile> getTransactionsForDirectory(@NonNull final Context context, @NonNull final String directory) {
        final GoogleApiClient apiClient = new GoogleApiClient.Builder(context)
                .addApi(Wearable.API).build();

        final ConnectionResult result = apiClient.blockingConnect();
        if(result.isSuccess()) {
            final DataItemBuffer items = Wearable.DataApi.getDataItems(apiClient).await();
            final Node node = NotaryWearableListenerService.getLocalNode(context);

            final List<PendingFile> files = new ArrayList<>();
            for(DataItem item:items) {
                if(item.getData().length>0 && FileTransaction.isFileTransactionItem(item)) {
                    final FileTransaction transaction = new FileTransaction(item);
                    final String sourceDirectory = transaction.getSourceFile(context).getParent();

                    if(node.getId().equals(transaction.sourceNode) && directory.equalsIgnoreCase(sourceDirectory)) {
                        files.add(new PendingFile(directory, transaction));
                    }
                }
            }

            items.release();
            return files;
        } else {
            return new ArrayList<>();
        }
    }




    public interface FileListCallback {
        public void success(FileListContainer fileList);
        public void failure(ConnectionResult result);
    }

    public static void requestFileList(@NonNull final Context context, @NonNull final FileListCallback callback) {
        requestFileList(context, null, callback);
    }

    public static void requestFileList(@NonNull final Context context, @Nullable final String directory, @NonNull final FileListCallback callback) {
        final GoogleApiClient apiClient = new GoogleApiClient.Builder(context)
                .addApi(Wearable.API).build();

        final ConnectionResult result = apiClient.blockingConnect();
        if(result.isSuccess()) {
            final List<Node> nodes = Wearable.NodeApi.getConnectedNodes(apiClient).await().getNodes();
            if(nodes.size()==0) {
                Log.e("WearApi", "Not connected to any nodes");
                callback.failure(null);
            } else {
                requestFileList(apiClient, nodes.get(0).getId(), directory, callback);
            }
        } else {
            Log.e("WearApi", "Failed to connect to API");
            callback.failure(result);
        }
    }

    public static void requestFileList(@NonNull final Context context, @NonNull final String node, @Nullable final String directory, @NonNull final FileListCallback callback) {
        final GoogleApiClient apiClient = new GoogleApiClient.Builder(context)
                .addApi(Wearable.API).build();

        final ConnectionResult result = apiClient.blockingConnect();
        if(result.isSuccess()) {
            requestFileList(apiClient, node, directory, callback);
        } else {
            Log.e("WearApi", "Failed to connect to API");
            callback.failure(result);
        }
    }

    private static void requestFileList(@NonNull final GoogleApiClient apiClient, @NonNull final String node, @Nullable final String directory, @NonNull final FileListCallback callback) {
        Wearable.MessageApi.addListener(apiClient, new MessageApi.MessageListener() {
            @Override public void onMessageReceived(MessageEvent messageEvent) {
                if(messageEvent.getPath().equals(RESPONSE_LIST_FILES) && messageEvent.getSourceNodeId().equals(node)) {
                    Log.d("WearApi", "File listing response received");
                    try {
                        final ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(messageEvent.getData()));
                        final FileListContainer container = (FileListContainer)in.readObject();
                        if((directory==null && container.directory==null) || (directory!=null && directory.equalsIgnoreCase(container.directory))) {
                            Log.d("WearApi", "File list success");
                            callback.success(container);
                            Wearable.MessageApi.removeListener(apiClient, this);
                        }
                    } catch (Exception e) {
                        Log.e("WearApi", "Error parsing file list response", e);
                    }
                }
            }
        });
        Wearable.MessageApi.sendMessage(apiClient, node, REQUEST_LIST_FILES, directory!=null?directory.getBytes():new byte[0]);
    }

}
