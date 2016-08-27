package de.chuparch0pper.android.xposed.pogoiv;

import android.app.AndroidAppHelper;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.support.v7.app.NotificationCompat;
import android.widget.Toast;

import com.github.aeonlucid.pogoprotos.Enums;
import com.github.aeonlucid.pogoprotos.inventory.Item;
import com.github.aeonlucid.pogoprotos.networking.Responses;
import com.google.protobuf.Descriptors;
import com.google.protobuf.ProtocolMessageEnum;

import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;

public class Helper {
    public static final String PACKAGE_NAME = IVChecker.class.getPackage().getName();

    private static Context context = null;
    private static Context pokeContext = null;

    private static String[] pokemonNames = null;

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
                Toast.makeText(getPokeContext(), message, length).show();
            }
        });
    }

    public static void showNotification(final String title, final String text, final String longText) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(getPokeContext());
                mBuilder.setSmallIcon(android.R.drawable.ic_dialog_info);
                mBuilder.setContentTitle(title);
                mBuilder.setContentText(text);
                mBuilder.setStyle(new NotificationCompat.BigTextStyle().bigText(longText));
                mBuilder.setVibrate(new long[]{1000});
                mBuilder.setPriority(Notification.PRIORITY_MAX);

                NotificationManager mNotificationManager = (NotificationManager) getPokeContext().getSystemService(Context.NOTIFICATION_SERVICE);
                mNotificationManager.notify(699511, mBuilder.build());
            }
        });
    }

    /**
     * Gets Context of this module
     *
     * @return Context of "de.chuparch0pper.android.xposed.pogoiv"
     */
    public static Context getContext() {
        if (context == null) {
            try {
                context = AndroidAppHelper.currentApplication().createPackageContext(PACKAGE_NAME, Context.CONTEXT_IGNORE_SECURITY);
            } catch (PackageManager.NameNotFoundException e) {
                Helper.Log("Could not get Context " + e);
            }
        }
        return context;
    }

    /**
     * Returns the main {@link android.app.Application} object in the current process.
     *
     * @return should be Context of "com.nianticlabs.pokemongo"
     */
    public static Context getPokeContext() {
        if (pokeContext == null) {
            pokeContext = AndroidAppHelper.currentApplication();
        }
        return pokeContext;
    }

    public static void loadPokemonNames() {
        pokemonNames = getContext().getResources().getStringArray(R.array.Pokemon);
    }

    public static String[] getPokemonNames() {
        if (pokemonNames == null) {
            loadPokemonNames();
        }
        return pokemonNames;
    }

    public static String getPokeMoveName(Enums.PokemonMove pokeMove) {
        // switch (pokeMove) {} // TODO later.. there are more than 300 moves
        return prettyPrintEnum(pokeMove.toString());
    }


    public static String getCatchName(Responses.CatchPokemonResponse.CatchStatus status) {
        return prettyPrintEnum(status.toString());
    }

    public static String getItemName(Item.ItemId item) {
        return prettyPrintEnum(item.toString().replaceAll("^ITEM_", ""));
    }

    public static String getItemName(Item.ItemId item, int count) {
        String name = getItemName(item);
        if (count != 1)
            name += " (x" + count + ")";
        return name;
    }

    public static String getItemName(Item.ItemAward itemAward) {
        return getItemName(itemAward.getItemId(), itemAward.getItemCount());
    }

    public static String getGenericEnumName(ProtocolMessageEnum enumEntry) {
        return prettyPrintEnum(enumEntry.toString());
    }

    private static String prettyPrintEnum(String enums) {
        String pokeMoveName = "";
        String[] splitPokeMoveNames = enums.split("_");
        for (String stringPart : splitPokeMoveNames) {
            pokeMoveName += stringPart.charAt(0) + stringPart.substring(1).toLowerCase() + " ";
        }
        return pokeMoveName.trim();
    }


    public static String getCpName() {
        return getContext().getResources().getString(R.string.cp);
    }
}
