package me.denley.notary;

import android.content.Context;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NotaryWearableListenerService extends WearableListenerService {

    @WorkerThread
    @Nullable public static Node getLocalNode(final Context context) {
        final GoogleApiClient apiClient = new GoogleApiClient.Builder(context)
                .addApi(Wearable.API).build();

        final ConnectionResult result = apiClient.blockingConnect();
        if(result.isSuccess()) {
            return Wearable.NodeApi.getLocalNode(apiClient).await().getNode();
        } else {
            return null;
        }
    }

    @WorkerThread
    @NonNull public static List<Node> getRemoteNodes(final Context context) {
        final GoogleApiClient apiClient = new GoogleApiClient.Builder(context)
                .addApi(Wearable.API).build();

        final ConnectionResult result = apiClient.blockingConnect();
        if(result.isSuccess()) {
            return Wearable.NodeApi.getConnectedNodes(apiClient).await().getNodes();
        } else {
            return new ArrayList<>();
        }
    }

    @WorkerThread
    private static void updateDiskCapacity(final Context context) {
        final GoogleApiClient apiClient = new GoogleApiClient.Builder(context)
                .addApi(Wearable.API).build();

        final ConnectionResult result = apiClient.blockingConnect();
        if(result.isSuccess()) {
            final java.io.File file = new java.io.File(FileTransaction.getDefaultDirectory(context));

            final PutDataMapRequest request = PutDataMapRequest.create(Notary.PATH_DISK_CAPACITY);
            final DataMap map = request.getDataMap();
            map.putLong("total_space", file.getTotalSpace());
            map.putLong("available_space", file.getUsableSpace());
            Wearable.DataApi.putDataItem(apiClient, request.asPutDataRequest());
            apiClient.disconnect();
        }
    }


    Node localNode;

    @Override public void onPeerConnected(Node peer) {
        super.onPeerConnected(peer);
        checkAllItems();
    }

    @Override public void onDataChanged(@NonNull final DataEventBuffer dataEvents) {
        super.onDataChanged(dataEvents);
        localNode = getLocalNode(this);

        boolean diskStateChanged = false;

        for(DataEvent event:dataEvents) {
            final DataItem item = event.getDataItem();
            if(item.getData()!=null && item.getData().length>0 && localNode !=null && FileTransaction.isFileTransactionItem(item)) {

                final FileTransaction transaction = new FileTransaction(item);
                Notary.notifyListeners(this, transaction);

                if(transaction.status==FileTransaction.STATUS_IN_PROGRESS) {
                    final boolean actioned = onTransactionDataItemChanged(item);
                    diskStateChanged = actioned || diskStateChanged;
                }
            }
        }

        if(diskStateChanged) {
            updateDiskCapacity(this);
        }
    }

    @WorkerThread
    private void checkAllItems() {
        final GoogleApiClient apiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API).build();

        final ConnectionResult result = apiClient.blockingConnect();
        if(result.isSuccess()) {
            localNode = Wearable.NodeApi.getLocalNode(apiClient).await().getNode();
            checkAllItems(apiClient);
            apiClient.disconnect();
        }
    }

    private void checkAllItems(final GoogleApiClient apiClient) {
        boolean diskStateChanged = false;

        final DataItemBuffer items = Wearable.DataApi.getDataItems(apiClient).await();
        for (DataItem item : items) {
            if(item.getData().length>0 && localNode !=null && FileTransaction.isFileTransactionItem(item)) {
                final boolean actioned = onTransactionDataItemChanged(item);
                diskStateChanged = actioned || diskStateChanged;
            }
        }
        items.release();

        if(diskStateChanged) {
            updateDiskCapacity(this);
        }
    }

    private synchronized boolean onTransactionDataItemChanged(@NonNull final DataItem item) {
        final FileTransaction transaction = new FileTransaction(item);
        if(transaction.getStatus()==FileTransaction.STATUS_IN_PROGRESS) {
            return checkForActionables(transaction);
        }

        return false;
    }

    private boolean checkForActionables(@NonNull final FileTransaction transaction) {
        boolean actioned = false;

        if (transaction.pendingCopy()) {
            if(localNode.getId().equals(transaction.sourceNode)) {
                loadSourceFile(transaction);
                actioned = true;
            }
        } else if (transaction.pendingSave()) {
            if(localNode.getId().equals(transaction.destinationNode)) {
                saveDestinationFile(transaction);
                actioned = true;
            }
        } else if (transaction.pendingDelete()) {
            if(localNode.getId().equals(transaction.sourceNode)) {
                deleteSourceFile(transaction);
                actioned = true;
            }
        }

        return actioned;
    }

    private void loadSourceFile(@NonNull final FileTransaction transaction) {
        final File file = transaction.getActionableSourceFile(this);

        if(!file.exists() || file.isDirectory()) {
            transaction.status = FileTransaction.STATUS_FAILED_FILE_NOT_FOUND;
        } else if(!file.canRead()) {
            transaction.status = FileTransaction.STATUS_FAILED_NO_READ_PERMISSION;
        } else {
            transaction.fileAsset = Asset.createFromUri(Uri.fromFile(file));
        }

        updateTransaction(transaction);
    }

    private void saveDestinationFile(@NonNull final FileTransaction transaction) {
        final File directory = transaction.getDestinationDirectoryFile(this);
        directory.mkdirs();

        if (!directory.isDirectory()) {
            transaction.status = FileTransaction.STATUS_FAILED_BAD_DESTINATION;
        } else {
            assert transaction.fileAsset!=null;
            final File file = new File(directory, transaction.getActionableSourceFileName());
            Log.d("Notary", "Saving file to: " + file.getAbsolutePath());

            if(file.exists()) {
                // File exists already.
                // Compare them. If they are the same, count it as a success.
                try {
                    final InputStream assetIn = openAssetInputStream(transaction.fileAsset);
                    final InputStream fileIn = new FileInputStream(file);

                    if(streamEquals(assetIn, fileIn)) {
                        transaction.setHasCopied();
                    } else {
                        transaction.status = FileTransaction.STATUS_FAILED_FILE_ALREADY_EXISTS;
                    }
                } catch (Exception e) {
                    transaction.status = FileTransaction.STATUS_FAILED_UNKNOWN;
                }
            } else {
                try {
                    final InputStream in = openAssetInputStream(transaction.fileAsset);
                    final FileOutputStream out = new FileOutputStream(file);

                    final byte[] buffer = new byte[1024];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        out.write(buffer, 0, count);
                    }

                    out.flush();
                    transaction.setHasCopied();

                    in.close();
                    out.close();
                } catch (Exception e) {
                    transaction.status = FileTransaction.STATUS_FAILED_UNKNOWN;
                }
            }
        }

        updateTransaction(transaction);
    }

    private boolean streamEquals(@NonNull InputStream in1, @NonNull InputStream in2) throws IOException {
        final byte[] buffer1 = new byte[1024];
        final byte[] buffer2 = new byte[1024];
        int count;
        while( (count = in1.read(buffer1)) != -1) {
            if(in2.read(buffer2)!=count || !Arrays.equals(buffer1, buffer2) ) {
                return false;
            }
        }

        return true;
    }

    private void deleteSourceFile(@NonNull final FileTransaction transaction) {
        final File file = transaction.getActionableSourceFile(this);

        if(!file.exists()) {
            transaction.setHasDeleted();
        } else if(file.delete()) {
            transaction.setHasDeleted();
        } else {
            transaction.status = FileTransaction.STATUS_FAILED_NO_DELETE_PERMISSION;
        }

        updateTransaction(transaction);
    }

    @WorkerThread
    private void updateTransaction(@NonNull final FileTransaction transaction) {
        final GoogleApiClient apiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API).build();

        final ConnectionResult result = apiClient.blockingConnect();
        if(result.isSuccess()) {

            // Delete old data
            final Uri uri = new Uri.Builder()
                    .scheme(PutDataRequest.WEAR_URI_SCHEME)
                    .path(transaction.getDataApiPath())
                    .build();
            Wearable.DataApi.deleteDataItems(apiClient, uri);

            // Put new transaction
            Wearable.DataApi.putDataItem(apiClient, transaction.asPutDataRequest());
            apiClient.disconnect();
        } else {
            throw new IllegalStateException("Unable to connect to wearable API");
        }
    }

    @WorkerThread
    private InputStream openAssetInputStream(@NonNull final Asset asset) {
        final GoogleApiClient apiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API).build();

        final ConnectionResult result = apiClient.blockingConnect();
        if(result.isSuccess()) {
            return Wearable.DataApi.getFdForAsset(apiClient, asset).await().getInputStream();
        } else {
            throw new IllegalStateException("Unable to connect to wearable API");
        }
    }


    @Override public void onMessageReceived(@NonNull final MessageEvent messageEvent) {
        super.onMessageReceived(messageEvent);

        if(messageEvent.getPath().equals(Notary.REQUEST_LIST_FILES)) {
            final byte[] data = messageEvent.getData();
            final String requestedDirectory = data.length>0?new String(data):null;
            final String usedDirectory = requestedDirectory!=null?FileTransaction.normalizePath(this, requestedDirectory):FileTransaction.getDefaultDirectory(this);

            try {
                final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                final ObjectOutputStream out = new ObjectOutputStream(bytes);

                out.writeObject(createFileListResponse(usedDirectory, requestedDirectory));

                sendMessage(messageEvent.getSourceNodeId(), Notary.RESPONSE_LIST_FILES, bytes.toByteArray());
            }catch(IOException e) {}
        }

        updateDiskCapacity(this);
    }

    private FileListContainer createFileListResponse(@NonNull final String usedDirectory, @Nullable final String requestedDirectory) {
        final File directoryFile = new File(usedDirectory);

        final FileListContainer response = new FileListContainer();
        response.directory = requestedDirectory;

        if(directoryFile.exists() && !directoryFile.isDirectory()) {
            response.outcome = FileListContainer.ERROR_DIRECTORY_NOT_FOUND;
        } else {
            File[] contents = directoryFile.listFiles();
            if(contents==null) {
                contents = new File[0];
            }
            response.files = new String[contents.length];
            response.isDirectory = new boolean[contents.length];
            for (int i = 0; i < contents.length; i++) {
                response.files[i] = contents[i].getName();
                response.isDirectory[i] = contents[i].isDirectory();
            }
        }

        return response;
    }

    @WorkerThread
    private void sendMessage(@NonNull final String node, @NonNull final String path, @NonNull final byte[] data) {
        final GoogleApiClient apiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API).build();
        final ConnectionResult result = apiClient.blockingConnect();
        if(result.isSuccess()) {
            Wearable.MessageApi.sendMessage(apiClient, node, path, data);
            apiClient.disconnect();
        }
    }
}
