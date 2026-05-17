package com.winlator.cmod;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.winlator.cmod.store.AmazonMainActivity;
import com.winlator.cmod.store.DownloadsActivity;
import com.winlator.cmod.store.EpicMainActivity;
import com.winlator.cmod.store.GogMainActivity;
import com.winlator.cmod.store.SteamMainActivity;

public class StoreFragment extends Fragment {
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.store);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        FrameLayout frameLayout = (FrameLayout) inflater.inflate(R.layout.store_fragment, container, false);
        Context context = getContext();
        frameLayout.findViewById(R.id.StoreAmazon).setOnClickListener(view -> startActivity(new Intent(context, AmazonMainActivity.class)));
        frameLayout.findViewById(R.id.StoreEpic).setOnClickListener(view -> startActivity(new Intent(context, EpicMainActivity.class)));
        frameLayout.findViewById(R.id.StoreGOG).setOnClickListener(view -> startActivity(new Intent(context, GogMainActivity.class)));
        frameLayout.findViewById(R.id.StoreSteam).setOnClickListener(view -> startActivity(new Intent(context, SteamMainActivity.class)));
        return frameLayout;
    }
    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
        inflater.inflate(R.menu.store_menu, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        if (menuItem.getItemId() == R.id.store_menu_downloader) {
            Context context = getContext();
            startActivity(new Intent(context, DownloadsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(menuItem);
    }

    @Override
    public void startActivity(Intent intent) {
        super.startActivity(intent);
        getActivity().overridePendingTransition(0, 0);
    }
}
