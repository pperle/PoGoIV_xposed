package de.chuparch0pper.android.xposed.pogoiv;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.NotificationCompat;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NotificationReceiver extends BroadcastReceiver {

    public static final String SHOW_NOTIFICATION = "SHOW_NOTIFICATION";
    public static final String SCROLL_DOWN = "SCROLL_DOWN";

    private Context context;

    private static int numOfLinesNotification = 9;
    private static String notificationTitle = "";
    private static String notificationText = "";

    private static List<String> notificationLongTextList = new ArrayList<>();
    private static int scrollPosition = 0;

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
            showNotification(notificationTitle, notificationText, scrolledLongText(), notificationLongTextList.size() > numOfLinesNotification);

        }
        if (SCROLL_DOWN.equals(action)) {
            Log.d(SHOW_NOTIFICATION, "showNotification " + SCROLL_DOWN);
            showNotification(notificationTitle, notificationText, scrolledLongText(), true);
        }
    }

    private String scrolledLongText() {
        String returnString = "";
        for (int i = scrollPosition; i < scrollPosition + numOfLinesNotification || i < notificationLongTextList.size(); scrollPosition++) {
            returnString += notificationLongTextList.get(i);
        }
        scrollPosition += numOfLinesNotification;
        return returnString;
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
            scrollDownIntent.setAction(SCROLL_DOWN);
            PendingIntent pendingIntentScrollDown = PendingIntent.getBroadcast(context, 111, scrollDownIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            mBuilder.addAction(android.R.drawable.arrow_down_float, "ScrollDown", pendingIntentScrollDown);
        }

        NotificationManager mNotificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.notify(699511, mBuilder.build());
    }

}
