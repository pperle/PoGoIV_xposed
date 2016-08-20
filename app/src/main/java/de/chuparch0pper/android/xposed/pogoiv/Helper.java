package de.chuparch0pper.android.xposed.pogoiv;

import android.app.AndroidAppHelper;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.support.v7.app.NotificationCompat;
import android.widget.Toast;

import com.google.protobuf.Descriptors;

import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;

public class Helper {

    public static void Log(String message) {
        if (BuildConfig.DEBUG) {
            XposedBridge.log(message);
        }
    }

    public static void Log(String message, Set<Map.Entry<Descriptors.FieldDescriptor, Object>> entries) {
        for (Map.Entry<Descriptors.FieldDescriptor, Object> entry : entries) {
            Helper.Log(message + entry.getKey() + " - " + entry.getValue());
        }

    }

    public static void showToast(final String message, final int length) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getContext(), message, length).show();
            }
        });
    }

    public static void showNotification(final String title, final String text, final String longText) {
        final Context context = getContext();

        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(context);
                mBuilder.setSmallIcon(android.R.color.background_light);
                mBuilder.setContentTitle(title);
                mBuilder.setContentText(text);
                mBuilder.setStyle(new NotificationCompat.BigTextStyle().bigText(longText));
                mBuilder.setVibrate(new long[]{1000});
                mBuilder.setPriority(Notification.PRIORITY_MAX);

                NotificationManager mNotificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                mNotificationManager.notify(699511, mBuilder.build());
            }
        });
    }

    private static Context getContext() {
        final Context context = AndroidAppHelper.currentApplication();

        if (context == null) {
            Helper.Log("Context is null");
        }
        return context;
    }
}
