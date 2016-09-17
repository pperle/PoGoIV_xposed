package de.chuparch0pper.android.xposed.pogoiv;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.NotificationCompat;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NotificationReceiver extends BroadcastReceiver {

    public static final String SHOW_NOTIFICATION = "SHOW_NOTIFICATION";
    public static final String SCROLL_DOWN = "SCROLL_DOWN";
    public static final String SCROLL_UP = "SCROLL_UP";

    private Context context;

    private static int numOfLinesNotification = 9;
    private static String notificationTitle = "";
    private static String notificationText = "";

    private static List<String> notificationLongTextList = new ArrayList<>();
    private static int scrollPosition = 0;
    private static boolean firstTime = true;

    @Override
    public void onReceive(Context context, Intent intent) {
        this.context = context;

        String action = intent.getAction();
        if (SHOW_NOTIFICATION.equals(action)) {
            Bundle extras = intent.getExtras();
            notificationTitle = extras.getString("title");
            notificationText = extras.getString("text");

            // TODO a better way to determine how many lines the text needs
            notificationLongTextList = Arrays.asList(extras.getString("longText").split("\\r?\\n"));
            scrollPosition = 0;
            firstTime = true;
            showNotification(notificationTitle, notificationText, scrolledLongText(), notificationLongTextList.size() > numOfLinesNotification);

        }
        if (SCROLL_DOWN.equals(action)) {
            Log.d(SHOW_NOTIFICATION, "showNotification " + SCROLL_DOWN);
            showNotification(notificationTitle, notificationText, scrolledLongText(), true);
        }
        if (SCROLL_UP.equals(action)) {
            showNotification(notificationTitle, notificationText, scrolledLongText(true), true);
        }
    }

    private String scrolledLongText(boolean scrollUp) {
        int listSize = notificationLongTextList.size();

        if (scrollUp) {
            if (!firstTime)
                scrollPosition -= numOfLinesNotification;

            if (scrollPosition < 0) {
                // Scroll to last integer multiple of numOfLinesNotification
                scrollPosition = (listSize / numOfLinesNotification) * numOfLinesNotification;
                if (scrollPosition >= listSize)
                    scrollPosition -= numOfLinesNotification;
            }
        } else {
            if (!firstTime)
                scrollPosition += numOfLinesNotification;

            if (scrollPosition >= listSize)
                scrollPosition = 0;
        }

        List<String> subList = notificationLongTextList.subList(scrollPosition, Math.min(scrollPosition + numOfLinesNotification, listSize));
        firstTime = false;
        return TextUtils.join("\n", subList);
    }

    private String scrolledLongText() {
        return scrolledLongText(false);
    }

    private void showNotification(String title, String text, String longText, boolean showScrollButton) {

        Log.d(SHOW_NOTIFICATION, "showNotification() " + longText);

        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(context);
        mBuilder.setSmallIcon(android.R.drawable.ic_dialog_info); // or own icon R.mipmap.ic_launcher
        mBuilder.setContentTitle(title);
        mBuilder.setContentText(text);
        mBuilder.setStyle(new NotificationCompat.BigTextStyle().bigText(longText));
        mBuilder.setVibrate(new long[]{1000});
        mBuilder.setPriority(Notification.PRIORITY_MAX);

        if (showScrollButton) {
            Intent scrollDownIntent = new Intent();
            Intent scrollUpIntent = new Intent();
            scrollDownIntent.setAction(SCROLL_DOWN);
            scrollUpIntent.setAction(SCROLL_UP);
            PendingIntent pendingIntentScrollDown = PendingIntent.getBroadcast(context, 111, scrollDownIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent pendingIntentScrollUp = PendingIntent.getBroadcast(context, 112, scrollUpIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            mBuilder.addAction(android.R.drawable.arrow_down_float, "Scroll Down", pendingIntentScrollDown);
            mBuilder.addAction(android.R.drawable.arrow_up_float, "Scroll Up", pendingIntentScrollUp);
        }

        NotificationManager mNotificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.notify(699511, mBuilder.build());
    }

}
