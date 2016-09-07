package de.chuparch0pper.android.xposed.pogoiv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

public class NotificationReceiver extends BroadcastReceiver {

    public static final String TOAST = "TOAST";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (TOAST.equals(action)) {
            Bundle extras = intent.getExtras();
            Toast.makeText(context, extras.getString("longText"), Toast.LENGTH_LONG).show();
        }

    }
}
