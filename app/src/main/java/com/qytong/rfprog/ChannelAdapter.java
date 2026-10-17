package com.qytong.rfprog;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.List;

public class ChannelAdapter extends BaseAdapter {

    private final List<UnifiedChannel> items;
    private final RadioType machine;

    public ChannelAdapter(List<UnifiedChannel> items, RadioType machine) {
        this.items = items;
        this.machine = machine;
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public UnifiedChannel getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            convertView = inf.inflate(R.layout.item_channel, parent, false);
        }
        UnifiedChannel c = getItem(position);
        TextView tvIndex = (TextView) convertView.findViewById(R.id.tvIndex);
        TextView tvRx = (TextView) convertView.findViewById(R.id.tvRx);
        TextView tvTx = (TextView) convertView.findViewById(R.id.tvTx);
        TextView tvRaw = (TextView) convertView.findViewById(R.id.tvRaw);

        tvIndex.setText(String.valueOf(position + 1));
        tvRx.setText(c.rxText());
        tvTx.setText(c.summary());
        tvRaw.setText(c.isEmpty() ? "" : ("CH" + (position + 1) + " · " + (machine == RadioType.KT10R ? "KT-10R" : "UV-K5")));
        return convertView;
    }
}
