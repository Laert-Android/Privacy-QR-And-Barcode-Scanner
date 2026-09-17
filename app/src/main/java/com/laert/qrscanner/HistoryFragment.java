package com.laert.qrscanner;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

public class HistoryFragment extends Fragment {

    private ListView listView;
    private List<JSONObject> historyList;
    private TextView tvEmpty;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Push the whole screen (title row included) below the status bar.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            int topInset = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), topInset, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

        listView = view.findViewById(R.id.listView);
        tvEmpty = view.findViewById(R.id.tvEmpty);
        Button btnClear = view.findViewById(R.id.btnClear);

        btnClear.setOnClickListener(v -> {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Clear History")
                    .setMessage("Delete all scan history?")
                    .setPositiveButton("Clear", (dialog, which) -> {
                        HistoryManager.clear(requireContext());
                        loadHistory();
                        Toast.makeText(requireContext(), "History cleared",
                                Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        loadHistory();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadHistory();
    }

    private void loadHistory() {
        historyList = HistoryManager.load(requireContext());

        if (historyList.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            listView.setVisibility(View.GONE);
            return;
        }

        tvEmpty.setVisibility(View.GONE);
        listView.setVisibility(View.VISIBLE);

        ArrayAdapter<JSONObject> adapter = new ArrayAdapter<JSONObject>(
                requireContext(), R.layout.item_history, historyList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext())
                            .inflate(R.layout.item_history, parent, false);
                }
                try {
                    JSONObject item = historyList.get(position);
                    TextView tvContent = convertView.findViewById(R.id.tvContent);
                    TextView tvTime = convertView.findViewById(R.id.tvTime);
                    TextView tvFormat = convertView.findViewById(R.id.tvFormat);
                    tvContent.setText(item.getString("content"));
                    tvTime.setText(item.getString("time"));
                    tvFormat.setText(item.getString("format"));
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                return convertView;
            }
        };

        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            try {
                String content = historyList.get(position).getString("content");
                showOptions(content);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        });
    }

    private void showOptions(String content) {
        String[] options = {"Copy", "Share", "Open"};
        new AlertDialog.Builder(requireContext())
                .setTitle(content.length() > 50 ? content.substring(0, 50) + "..." : content)
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            ClipboardManager clipboard = (ClipboardManager)
                                    requireActivity().getSystemService(Context.CLIPBOARD_SERVICE);
                            clipboard.setPrimaryClip(ClipData.newPlainText("Scan", content));
                            Toast.makeText(requireContext(), "Copied!", Toast.LENGTH_SHORT).show();
                            break;
                        case 1:
                            Intent shareIntent = new Intent(Intent.ACTION_SEND);
                            shareIntent.setType("text/plain");
                            shareIntent.putExtra(Intent.EXTRA_TEXT, content);
                            startActivity(Intent.createChooser(shareIntent, "Share"));
                            break;
                        case 2:
                            try {
                                String url = content;
                                if (!url.startsWith("http://") &&
                                        !url.startsWith("https://")) {
                                    url = "https://" + url;
                                }
                                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                            } catch (Exception e) {
                                Toast.makeText(requireContext(), "Cannot open",
                                        Toast.LENGTH_SHORT).show();
                            }
                            break;
                    }
                })
                .show();
    }
}