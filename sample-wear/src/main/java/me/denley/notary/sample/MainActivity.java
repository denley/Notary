package me.denley.notary.sample;

import android.app.Activity;
import android.os.Bundle;
import android.support.v7.widget.RecyclerView;
import android.support.wearable.view.WearableListView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.google.android.gms.wearable.Node;

import me.denley.courier.Courier;
import me.denley.courier.LocalNode;
import me.denley.notary.DirectoryObserver;
import me.denley.notary.File;
import me.denley.notary.Notary;

public class MainActivity extends Activity implements WearableListView.ClickListener {

    @LocalNode Node localNode;

    WearableListView list;
    MainActivityAdapter adapter;
    DirectoryObserver observer;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        list = (WearableListView) findViewById(android.R.id.list);

        Courier.startReceiving(this);

        loadFiles();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        Courier.stopReceiving(this);
    }

    private void loadFiles() {
        final java.io.File directory = new java.io.File(Notary.getDefaultDirectory(this));
        directory.mkdir();

        if(!directory.isDirectory()) {
            throw new RuntimeException("Unable to create sample directory");
        }

        adapter = new MainActivityAdapter();
        list.setAdapter(adapter);
        observer = new DirectoryObserver(this, adapter, directory.getAbsolutePath());
        list.setClickListener(this);
    }

    @Override public void onClick(WearableListView.ViewHolder viewHolder) {
        final File file = adapter.getFile(viewHolder.getPosition());

    }

    @Override public void onTopEmptyRegionClick() {}

    private class MainActivityAdapter extends me.denley.notary.FileListAdapter {

        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup viewGroup, int viewType) {
            final View view = new TextView(MainActivity.this);
            return new WearableListView.ViewHolder(view);
        }

        @Override public void onBindViewHolder(RecyclerView.ViewHolder viewHolder, int position) {
            ((TextView)viewHolder.itemView).setText(getFile(position).getName());
        }
    }

}
