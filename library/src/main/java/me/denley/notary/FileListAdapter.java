package me.denley.notary;

import android.support.v7.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public abstract class FileListAdapter extends RecyclerView.Adapter {

    private final List<File> files = new ArrayList<>();

    List<File> getFiles(){
        return files;
    }

    public File getFile(final int position) {
        return files.get(position);
    }

    @Override public int getItemCount() {
        return files.size();
    }

}
