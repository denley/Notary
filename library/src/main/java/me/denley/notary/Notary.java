package me.denley.notary;

import android.content.Context;
import android.support.annotation.NonNull;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Wearable;

import java.io.File;

public class Notary {

    public static void requestFileTransfer(@NonNull final Context context, @NonNull String sourceFile, @NonNull String sourceNode,
                                           @NonNull String destinationDirectory, @NonNull String destinationNode,
                                           boolean deleteSource) {
        final FileTransaction transaction = new FileTransaction(sourceFile, sourceNode, destinationDirectory, destinationNode, deleteSource);
        putTransaction(context, transaction);
    }

    public static void requestFileDelete(@NonNull final Context context, @NonNull String file, @NonNull String node) {
        final FileTransaction transaction = new FileTransaction(file, node);
        putTransaction(context, transaction);
    }

    public static void registerFileListener(@NonNull FileListener listener, @NonNull File fileOrDirectory, @NonNull String node) {

    }

    public static void unregisterFileListener(@NonNull FileListener listener) {

    }


    private static void putTransactionAsync(@NonNull final Context context, @NonNull final FileTransaction transaction) {
        new Thread() {
            public void run() {
                putTransaction(context, transaction);
            }
        }.start();
    }

    private static void putTransaction(@NonNull Context context, @NonNull FileTransaction transaction) {
        final GoogleApiClient apiClient = new GoogleApiClient.Builder(context)
                .addApi(Wearable.API)
                .build();

        final ConnectionResult result = apiClient.blockingConnect();
        if(result.isSuccess()) {
            Wearable.DataApi.putDataItem(apiClient, transaction.asPutDataRequest());
        }
    }

}
