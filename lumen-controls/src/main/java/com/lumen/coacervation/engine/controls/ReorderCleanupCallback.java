package com.lumen.coacervation.engine.controls;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

/**
 * RecyclerView 1.4.0 can finish a recovery after detach and pass a null RecyclerView,
 * despite its NonNull annotation. Keep this Java boundary ahead of Kotlin's parameter check.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public abstract class ReorderCleanupCallback extends ItemTouchHelper.Callback {
    @Override
    public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder holder) {
        if (recyclerView != null) {
            super.clearView(recyclerView, holder);
        } else {
            getDefaultUIUtil().clearView(holder.itemView);
        }
        onCleared(holder);
    }

    protected abstract void onCleared(@NonNull RecyclerView.ViewHolder holder);
}
